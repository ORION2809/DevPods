import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { GraphStore } from '../../../../src/intelligence/gitnexus/graph/graph-store';
import { clearGraphData, batchCreateNodes } from '../../../../src/intelligence/gitnexus/graph/graph-writer';
import { searchSymbols } from '../../../../src/intelligence/gitnexus/search/symbol-search';

describe('symbol-search', () => {
  let tmpDir: string;
  let store: GraphStore;

  beforeEach(async () => {
    tmpDir = mkdtempSync(join(tmpdir(), 'devpods-search-test-'));
    store = new GraphStore(join(tmpDir, 'test.db'));
    await store.init();
    await store.createSchema();

    // Seed test graph
    const nodes = [
      { label: 'Function', id: 'f1', properties: { id: 'f1', name: 'handleEvent', filePath: 'src/bridge.ts', startLine: 10, endLine: 20, isExported: true } },
      { label: 'Class', id: 'c1', properties: { id: 'c1', name: 'EventHandler', filePath: 'src/bridge.ts', startLine: 30, endLine: 50, isExported: true } },
      { label: 'Method', id: 'm1', properties: { id: 'm1', name: 'onClick', filePath: 'src/ui.ts', startLine: 5, endLine: 15, isExported: false } },
      { label: 'Function', id: 'f2', properties: { id: 'f2', name: 'handleRequest', filePath: 'src/server.ts', startLine: 1, endLine: 10, isExported: true } },
      { label: 'File', id: 'file1', properties: { id: 'file1', name: 'bridge.ts', filePath: 'src/bridge.ts', content: '' } },
    ];
    await batchCreateNodes(store, nodes);
  });

  afterEach(async () => {
    await store.close();
    rmSync(tmpDir, { recursive: true, force: true });
  });

  it('finds exact match with highest confidence', async () => {
    const matches = await searchSymbols(store, 'handleEvent');
    expect(matches.length).toBeGreaterThanOrEqual(1);
    expect(matches[0].name).toBe('handleEvent');
    expect(matches[0].confidence).toBe(1.0);
    expect(matches[0].label).toBe('Function');
  });

  it('finds case-insensitive exact match', async () => {
    const matches = await searchSymbols(store, 'handleevent');
    expect(matches.some((m) => m.name === 'handleEvent' && m.confidence === 0.95)).toBe(true);
  });

  it('finds prefix matches', async () => {
    const matches = await searchSymbols(store, 'handle');
    expect(matches.some((m) => m.name === 'handleEvent')).toBe(true);
    expect(matches.some((m) => m.name === 'handleRequest')).toBe(true);
  });

  it('finds substring matches', async () => {
    const matches = await searchSymbols(store, 'Event');
    expect(matches.some((m) => m.name === 'handleEvent')).toBe(true);
    expect(matches.some((m) => m.name === 'EventHandler')).toBe(true);
  });

  it('finds file path matches', async () => {
    const matches = await searchSymbols(store, 'bridge');
    expect(matches.some((m) => m.label === 'File')).toBe(true);
  });

  it('returns empty array for unknown symbol', async () => {
    const matches = await searchSymbols(store, 'zzzzzzz');
    expect(matches).toEqual([]);
  });

  it('deduplicates across match strategies', async () => {
    const matches = await searchSymbols(store, 'handleEvent');
    const ids = matches.map((m) => m.id);
    expect(new Set(ids).size).toBe(ids.length);
  });
});
