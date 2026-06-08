import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { GraphStore } from '../../../../src/intelligence/gitnexus/graph/graph-store';
import { getToolMap } from '../../../../src/intelligence/gitnexus/search/tool-map';

describe('getToolMap', () => {
  let tmpDir: string;
  let store: GraphStore;

  beforeEach(async () => {
    tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'gitnexus-tool-'));
    const dbPath = path.join(tmpDir, 'codegraph.db');
    store = new GraphStore(dbPath);
    await store.init();
    await store.createSchema();
  });

  afterEach(async () => {
    await store.close();
    await fs.rm(tmpDir, { recursive: true, force: true });
  });

  it('queries Tool nodes when populated', async () => {
    await store.query(
      `CREATE (t:Tool {id: 't1', name: 'search', filePath: 'src/tools.ts', description: 'Search tool'})`,
    );

    const result = await getToolMap(store, 'search');
    expect(result.tool).toBe('search');
    expect(result.implementations).toContain('src/tools.ts');
  });

  it('falls back to heuristic symbol search when no Tool nodes', async () => {
    await store.query(
      `CREATE (f:Function {id: 'f1', name: 'searchHandler', filePath: 'src/tools/search.ts', startLine: 5, endLine: 15, isExported: true})`,
    );

    const result = await getToolMap(store, 'search');
    expect(result.implementations.length).toBeGreaterThan(0);
    expect(result.implementations[0]).toContain('searchHandler');
  });

  it('returns empty result for unknown tool', async () => {
    const result = await getToolMap(store, 'nonexistent');
    expect(result.implementations).toHaveLength(0);
    expect(result.callSites).toHaveLength(0);
  });
});
