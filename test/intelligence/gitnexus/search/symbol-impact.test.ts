import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { GraphStore } from '../../../../src/intelligence/gitnexus/graph/graph-store';
import { batchCreateNodes } from '../../../../src/intelligence/gitnexus/graph/graph-writer';
import { getSymbolImpact } from '../../../../src/intelligence/gitnexus/search/symbol-impact';

describe('symbol-impact', () => {
  let tmpDir: string;
  let store: GraphStore;

  beforeEach(async () => {
    tmpDir = mkdtempSync(join(tmpdir(), 'devpods-impact-test-'));
    store = new GraphStore(join(tmpDir, 'test.db'));
    await store.init();
    await store.createSchema();

    await batchCreateNodes(store, [
      { label: 'Function', id: 'f1', properties: { id: 'f1', name: 'handleEvent', filePath: 'src/bridge.ts', startLine: 10, endLine: 20, isExported: true } },
      { label: 'Class', id: 'c1', properties: { id: 'c1', name: 'Bridge', filePath: 'src/bridge.ts', startLine: 1, endLine: 5, isExported: true } },
    ]);
  });

  afterEach(async () => {
    await store.close();
    rmSync(tmpDir, { recursive: true, force: true });
  });

  it('returns impact for known symbol', async () => {
    const imp = await getSymbolImpact(store, 'handleEvent');
    expect(imp.definition).not.toBeNull();
    expect(imp.definition!.name).toBe('handleEvent');
    expect(imp.neighbourCount).toBe(1);
    expect(imp.isExported).toBe(true);
    expect(imp.riskLevel).toBe('medium');
  });

  it('returns low risk for unknown symbol', async () => {
    const imp = await getSymbolImpact(store, 'missing');
    expect(imp.definition).toBeNull();
    expect(imp.riskLevel).toBe('low');
  });
});
