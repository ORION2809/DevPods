import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { GraphStore } from '../../../../src/intelligence/gitnexus/graph/graph-store';
import { batchCreateNodes } from '../../../../src/intelligence/gitnexus/graph/graph-writer';
import { getSymbolContext, findSymbolDefinition } from '../../../../src/intelligence/gitnexus/search/symbol-context';

describe('symbol-context', () => {
  let tmpDir: string;
  let store: GraphStore;

  beforeEach(async () => {
    tmpDir = mkdtempSync(join(tmpdir(), 'devpods-ctx-test-'));
    store = new GraphStore(join(tmpDir, 'test.db'));
    await store.init();
    await store.createSchema();

    await batchCreateNodes(store, [
      { label: 'Function', id: 'f1', properties: { id: 'f1', name: 'handleEvent', filePath: 'src/bridge.ts', startLine: 10, endLine: 20, isExported: true } },
      { label: 'Class', id: 'c1', properties: { id: 'c1', name: 'Bridge', filePath: 'src/bridge.ts', startLine: 1, endLine: 5, isExported: true } },
      { label: 'Method', id: 'm1', properties: { id: 'm1', name: 'onClick', filePath: 'src/ui.ts', startLine: 5, endLine: 15, isExported: false } },
    ]);
  });

  afterEach(async () => {
    await store.close();
    rmSync(tmpDir, { recursive: true, force: true });
  });

  it('finds symbol definition', async () => {
    const def = await findSymbolDefinition(store, 'handleEvent');
    expect(def).not.toBeNull();
    expect(def!.name).toBe('handleEvent');
    expect(def!.filePath).toBe('src/bridge.ts');
    expect(def!.isExported).toBe(true);
  });

  it('returns null for unknown symbol', async () => {
    const def = await findSymbolDefinition(store, 'zzzz');
    expect(def).toBeNull();
  });

  it('returns context with neighbours in same file', async () => {
    const ctx = await getSymbolContext(store, 'handleEvent');
    expect(ctx.definition).not.toBeNull();
    expect(ctx.filePath).toBe('src/bridge.ts');
    expect(ctx.neighbours.some((n) => n.name === 'Bridge')).toBe(true);
  });

  it('returns empty context for missing symbol', async () => {
    const ctx = await getSymbolContext(store, 'missing');
    expect(ctx.definition).toBeNull();
    expect(ctx.neighbours).toEqual([]);
    expect(ctx.filePath).toBeNull();
  });
});
