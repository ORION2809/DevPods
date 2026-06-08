/**
 * Route map — find route definitions and their handlers.
 *
 * Phase 3c: Heuristic search over code symbols (Route nodes may not
 * be populated yet). Searches for functions/classes whose names or
 * file paths suggest route handling, plus any Route nodes in graph.
 *
 * Phase 3d+: Queries Route nodes directly when populated by ingestion.
 */

import type { GraphStore } from '../graph/graph-store';
import type { RouteMapResult } from '../../intelligence-layer-contract';

const lit = (s: string): string => s.replace(/'/g, "\\'");

interface RouteNode {
  id: string;
  name: string;
  filePath: string;
  middleware: string[];
}

interface SymbolHandler {
  name: string;
  filePath: string;
  label: string;
  startLine: number;
}

/** Query Route nodes directly (populated in Phase 3d+). */
const queryRouteNodes = async (
  store: GraphStore,
  routePattern: string,
): Promise<RouteNode[]> => {
  try {
    const q = lit(routePattern);
    const rows = (await store.query(`
      MATCH (n:Route)
      WHERE n.name CONTAINS '${q}'
      RETURN n.id AS id, n.name AS name, n.filePath AS filePath,
             n.middleware AS middleware
    `)) as Array<{
      id: string;
      name: string;
      filePath: string;
      middleware: string[] | null;
    }>;
    return rows.map((r) => ({
      id: r.id,
      name: r.name,
      filePath: r.filePath,
      middleware: r.middleware ?? [],
    }));
  } catch {
    return [];
  }
};

/** Heuristic: search code symbols that likely handle routes. */
const findHandlerSymbols = async (
  store: GraphStore,
  routePattern: string,
): Promise<SymbolHandler[]> => {
  const q = lit(routePattern);
  const qLower = lit(routePattern.toLowerCase());

  const codeTables = ['Function', 'Method', 'Class'];
  const queries = codeTables.map(
    (t) => `
      MATCH (n:${t})
      WHERE lower(n.name) CONTAINS '${qLower}'
         OR n.filePath CONTAINS '${q}'
      RETURN n.name AS name, n.filePath AS filePath,
             '${t}' AS label, n.startLine AS startLine
      LIMIT 20
    `,
  );

  const cypher = queries.join('\nUNION ALL\n');
  const rows = (await store.query(cypher)) as Array<{
    name: string;
    filePath: string;
    label: string;
    startLine: number;
  }>;

  return rows.map((r) => ({
    name: r.name,
    filePath: r.filePath,
    label: r.label,
    startLine: r.startLine,
  }));
};

/** Heuristic: find files that reference the route pattern (consumers). */
const findConsumers = async (
  store: GraphStore,
  routePattern: string,
): Promise<string[]> => {
  const q = lit(routePattern);
  try {
    const rows = (await store.query(`
      MATCH (n:File)
      WHERE n.filePath CONTAINS '${q}'
      RETURN n.filePath AS filePath
      LIMIT 10
    `)) as Array<{ filePath: string }>;
    return rows.map((r) => r.filePath);
  } catch {
    return [];
  }
};

/**
 * Map a route to its consumers, middleware, and handlers.
 *
 * First tries Route nodes (Phase 3d+), then falls back to
 * heuristic symbol search over the codebase.
 */
export const getRouteMap = async (
  store: GraphStore,
  route: string,
): Promise<RouteMapResult> => {
  const routeNodes = await queryRouteNodes(store, route);

  if (routeNodes.length > 0) {
    const r = routeNodes[0];
    return {
      route: r.name,
      consumers: [], // Would need FETCHES edges for full consumer tracking
      middleware: r.middleware,
      handlers: [r.filePath],
    };
  }

  // Heuristic fallback
  const handlers = await findHandlerSymbols(store, route);
  const consumers = await findConsumers(store, route);

  if (handlers.length === 0) {
    return {
      route,
      consumers: [],
      middleware: [],
      handlers: [],
    };
  }

  // Derive middleware guess from file path patterns
  const middlewareGuess: string[] = [];
  const handlerPaths = handlers.map((h) => h.filePath);
  for (const fp of handlerPaths) {
    if (fp.includes('middleware')) middlewareGuess.push('middleware');
    if (fp.includes('auth')) middlewareGuess.push('auth');
  }

  return {
    route,
    consumers,
    middleware: [...new Set(middlewareGuess)],
    handlers: handlerPaths.slice(0, 5),
  };
};
