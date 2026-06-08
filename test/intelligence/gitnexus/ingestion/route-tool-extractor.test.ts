import { describe, it, expect } from 'vitest';
import {
  extractRoutesFromFile,
  extractToolsFromFile,
} from '../../../../src/intelligence/gitnexus/ingestion/route-tool-extractor';

describe('route-tool-extractor', () => {
  describe('extractRoutesFromFile', () => {
    it('extracts Express routes', () => {
      const content = `
        app.get('/users', getUsers);
        app.post('/users', createUser);
        router.delete('/users/:id', deleteUser);
      `;
      const routes = extractRoutesFromFile('src/routes.ts', content);
      expect(routes).toHaveLength(3);
      expect(routes[0]).toMatchObject({ method: 'GET', path: '/users' });
      expect(routes[1]).toMatchObject({ method: 'POST', path: '/users' });
      expect(routes[2]).toMatchObject({ method: 'DELETE', path: '/users/:id' });
    });

    it('extracts Fastify routes', () => {
      const content = `
        fastify.get('/health', healthHandler);
        fastify.post('/api/data', dataHandler);
      `;
      const routes = extractRoutesFromFile('src/server.ts', content);
      expect(routes).toHaveLength(2);
      expect(routes[0]).toMatchObject({ method: 'GET', path: '/health' });
    });

    it('extracts Next.js app/api filesystem routes', () => {
      const routes = extractRoutesFromFile(
        'app/api/users/route.ts',
        'export async function GET(request: Request) {}',
      );
      expect(routes).toHaveLength(1);
      expect(routes[0]).toMatchObject({ method: 'GET', path: '/api/users' });
    });

    it('deduplicates identical routes', () => {
      const content = `
        app.get('/users', handler1);
        app.get('/users', handler2);
      `;
      const routes = extractRoutesFromFile('src/routes.ts', content);
      expect(routes).toHaveLength(1);
    });

    it('returns empty array for files with no routes', () => {
      const routes = extractRoutesFromFile('src/utils.ts', 'export const add = (a, b) => a + b;');
      expect(routes).toHaveLength(0);
    });
  });

  describe('extractToolsFromFile', () => {
    it('extracts MCP server.tool definitions', () => {
      const content = `
        server.tool('search', { query: z.string() }, async ({ query }) => { ... });
        server.tool('summarize', { text: z.string() }, async ({ text }) => { ... });
      `;
      const tools = extractToolsFromFile('src/tools.ts', content);
      expect(tools).toHaveLength(2);
      expect(tools.map((t) => t.name)).toContain('search');
      expect(tools.map((t) => t.name)).toContain('summarize');
    });

    it('extracts tool objects with inputSchema', () => {
      const content = `
        const tools = [
          { name: 'fetch', description: 'Fetch data', inputSchema: { ... } },
          { name: 'post', description: 'Post data', inputSchema: { ... } },
        ];
      `;
      const tools = extractToolsFromFile('src/mcp-tools.ts', content);
      expect(tools).toHaveLength(2);
      expect(tools.map((t) => t.name)).toContain('fetch');
    });

    it('skips non-tool files', () => {
      const tools = extractToolsFromFile('src/utils.ts', 'export const add = (a, b) => a + b;');
      expect(tools).toHaveLength(0);
    });

    it('deduplicates tool names', () => {
      const content = `
        server.tool('search', ...);
        server.tool('search', ...);
      `;
      const tools = extractToolsFromFile('src/tools.ts', content);
      expect(tools).toHaveLength(1);
    });
  });
});
