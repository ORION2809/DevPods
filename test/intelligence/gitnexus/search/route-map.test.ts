import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { GraphStore } from '../../../../src/intelligence/gitnexus/graph/graph-store';
import { getRouteMap } from '../../../../src/intelligence/gitnexus/search/route-map';

describe('getRouteMap', () => {
  let tmpDir: string;
  let store: GraphStore;

  beforeEach(async () => {
    tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'gitnexus-route-'));
    const dbPath = path.join(tmpDir, 'codegraph.db');
    store = new GraphStore(dbPath);
    await store.init();
    await store.createSchema();
  });

  afterEach(async () => {
    await store.close();
    await fs.rm(tmpDir, { recursive: true, force: true });
  });

  it('queries Route nodes when populated', async () => {
    await store.query(
      `CREATE (r:Route {id: 'r1', name: 'GET /api/users', filePath: 'src/routes.ts', middleware: ['auth']})`,
    );

    const result = await getRouteMap(store, '/api/users');
    expect(result.route).toBe('GET /api/users');
    expect(result.handlers).toContain('src/routes.ts');
    expect(result.middleware).toContain('auth');
  });

  it('falls back to heuristic symbol search when no Route nodes', async () => {
    await store.query(
      `CREATE (f:Function {id: 'f1', name: 'getUsers', filePath: 'src/api/users.ts', startLine: 10, endLine: 20, isExported: true})`,
    );

    const result = await getRouteMap(store, 'users');
    expect(result.handlers.length).toBeGreaterThan(0);
    expect(result.handlers[0]).toContain('users.ts');
  });

  it('returns empty result for unknown route', async () => {
    const result = await getRouteMap(store, '/nonexistent');
    expect(result.handlers).toHaveLength(0);
    expect(result.consumers).toHaveLength(0);
  });
});
