/**
 * Route and tool extraction — lightweight regex-based source scanning.
 *
 * Detects common framework patterns without full tree-sitter framework plugins:
 *   - Express/Fastify/Koa: app.get('/path', handler)
 *   - Next.js: filesystem routes in app/api/ or pages/api/
 *   - MCP tools: server.tool('name', ...)
 *
 * This is an MVP extractor. Full framework plugin extraction is deferred
 * to Phase 2+ (see donor: src/core/ingestion/route-extractors/).
 */

export interface RouteDef {
  /** HTTP method or 'ANY' */
  method: string;
  /** Route path e.g. /api/users */
  path: string;
  /** File where the route is defined */
  filePath: string;
  /** Handler function name if detectable */
  handlerName?: string;
  /** Middleware names if detectable */
  middleware?: string[];
}

export interface ToolDef {
  /** Tool name */
  name: string;
  /** File where the tool is defined */
  filePath: string;
  /** Description if extractable */
  description: string;
}

// ── Express-style route patterns ───────────────────────────────────────────

const EXPRESS_METHODS = ['get', 'post', 'put', 'patch', 'delete', 'head', 'options', 'all', 'use'];

/** Match: app.get('/path', handler) or router.post('/path', handler) */
const expressRoutePattern = new RegExp(
  `(?:app|router|server|api)\\.(${EXPRESS_METHODS.join('|')})\\s*\\(\\s*['"\`]([^'"\`]+)['"\`]`,
  'g',
);

/** Match: fastify.get('/path', opts, handler) */
const fastifyRoutePattern = new RegExp(
  `fastify\\.(${EXPRESS_METHODS.join('|')})\\s*\\(\\s*['"\`]([^'"\`]+)['"\`]`,
  'g',
);

// ── Next.js filesystem route detection ─────────────────────────────────────

const isNextjsApiRoute = (filePath: string): boolean => {
  const p = filePath.replace(/\\/g, '/');
  return (
    p.includes('app/api/') ||
    p.includes('pages/api/') ||
    p.includes('src/app/api/') ||
    p.includes('src/pages/api/')
  );
};

/** Convert a Next.js API route file path to a route path. */
const nextjsFileToRoute = (filePath: string): string | null => {
  const p = filePath.replace(/\\/g, '/');
  const apiIdx = p.indexOf('/api/');
  if (apiIdx < 0) return null;

  // Keep the /api prefix: app/api/users/route.ts → /api/users
  let routePart = p.slice(apiIdx);
  // Remove file extension
  routePart = routePart.replace(/\.(ts|tsx|js|jsx)$/, '');
  // Remove route segment files (route.ts, page.ts)
  routePart = routePart.replace(/\/route$/, '');
  routePart = routePart.replace(/\/page$/, '');
  // Dynamic segments: [id] → :id
  routePart = routePart.replace(/\[(\w+)\]/g, ':$1');

  return routePart || '/';
};

// ── MCP tool patterns ──────────────────────────────────────────────────────

/** Match: server.tool('name', ...) or server.tool("name", ...) */
const mcpToolPattern = /[\w$]+\.tool\s*\(\s*['"`](\w+)['"`]/g;

/** Match: name: 'toolName' near inputSchema (tool object pattern) */
const toolObjectPattern = /name\s*:\s*['"`](\w+)['"`]/g;

// ── Extraction functions ───────────────────────────────────────────────────

/**
 * Extract route definitions from a single file's content.
 */
export const extractRoutesFromFile = (
  filePath: string,
  content: string,
): RouteDef[] => {
  const routes: RouteDef[] = [];
  const seen = new Set<string>();

  const add = (method: string, path: string, handlerName?: string): void => {
    const key = `${method}|${path}`;
    if (seen.has(key)) return;
    seen.add(key);
    routes.push({
      method: method.toUpperCase(),
      path,
      filePath,
      handlerName,
      middleware: [],
    });
  };

  // Express-style routes
  let match: RegExpExecArray | null;
  expressRoutePattern.lastIndex = 0;
  while ((match = expressRoutePattern.exec(content)) !== null) {
    add(match[1], match[2]);
  }

  fastifyRoutePattern.lastIndex = 0;
  while ((match = fastifyRoutePattern.exec(content)) !== null) {
    add(match[1], match[2]);
  }

  // Next.js filesystem routes
  if (isNextjsApiRoute(filePath)) {
    const routePath = nextjsFileToRoute(filePath);
    if (routePath) {
      // Detect exported handler names
      const handlerMatch = content.match(/export\s+(?:async\s+)?function\s+(\w+)/);
      const method = inferHttpMethodFromHandler(handlerMatch?.[1] ?? '');
      add(method, routePath, handlerMatch?.[1]);
    }
  }

  return routes;
};

/** Infer HTTP method from handler function name. */
const inferHttpMethodFromHandler = (name: string): string => {
  const lower = name.toLowerCase();
  if (lower.startsWith('get')) return 'GET';
  if (lower.startsWith('post')) return 'POST';
  if (lower.startsWith('put')) return 'PUT';
  if (lower.startsWith('patch')) return 'PATCH';
  if (lower.startsWith('delete')) return 'DELETE';
  if (lower.startsWith('head')) return 'HEAD';
  if (lower === 'handler') return 'ANY';
  return 'ANY';
};

/**
 * Extract tool definitions from a single file's content.
 */
export const extractToolsFromFile = (
  filePath: string,
  content: string,
): ToolDef[] => {
  const tools: ToolDef[] = [];
  const seen = new Set<string>();

  // Only scan files that look like they define tools
  const p = filePath.replace(/\\/g, '/').toLowerCase();
  const looksLikeToolFile =
    p.includes('tool') ||
    p.includes('mcp') ||
    content.includes('inputSchema') ||
    content.includes('.tool(');

  if (!looksLikeToolFile) return [];

  let match: RegExpExecArray | null;

  mcpToolPattern.lastIndex = 0;
  while ((match = mcpToolPattern.exec(content)) !== null) {
    const name = match[1];
    if (seen.has(name)) continue;
    seen.add(name);
    tools.push({ name, filePath, description: '' });
  }

  // Tool object pattern — only if inputSchema is present to reduce false positives
  if (content.includes('inputSchema')) {
    toolObjectPattern.lastIndex = 0;
    while ((match = toolObjectPattern.exec(content)) !== null) {
      const name = match[1];
      if (seen.has(name)) continue;
      seen.add(name);
      tools.push({ name, filePath, description: '' });
    }
  }

  return tools;
};
