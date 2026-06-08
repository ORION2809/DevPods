import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import {
  loadParseCache,
  saveParseCache,
  getCachedParse,
  setCachedParse,
  pruneParseCache,
  PARSE_CACHE_VERSION,
} from '../../../src/intelligence/ingestion/parse-cache';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

describe('parse cache', () => {
  let tmpDir: string;

  beforeEach(() => {
    tmpDir = mkdtempSync(join(tmpdir(), 'devpods-cache-test-'));
  });

  afterEach(() => {
    rmSync(tmpDir, { recursive: true, force: true });
  });

  it('returns empty cache when no file exists', async () => {
    const cache = await loadParseCache(tmpDir);
    expect(cache.version).toBe(PARSE_CACHE_VERSION);
    expect(Object.keys(cache.entries)).toHaveLength(0);
  });

  it('saves and loads cache', async () => {
    const cache = {
      version: PARSE_CACHE_VERSION,
      entries: {
        hash1: { filePath: 'a.ts', nodes: [{ id: 'n1' }], edges: [] },
      },
    };

    await saveParseCache(tmpDir, cache);
    const loaded = await loadParseCache(tmpDir);

    expect(loaded.entries['hash1'].filePath).toBe('a.ts');
  });

  it('invalidates cache on version mismatch', async () => {
    const cache = { version: 'old', entries: { hash1: { filePath: 'a.ts', nodes: [], edges: [] } } };
    await saveParseCache(tmpDir, cache as any);
    const loaded = await loadParseCache(tmpDir);
    expect(Object.keys(loaded.entries)).toHaveLength(0);
  });

  it('gets and sets cached entries', () => {
    const cache = { version: PARSE_CACHE_VERSION, entries: {} };
    setCachedParse(cache, 'hash1', { filePath: 'a.ts', nodes: [], edges: [] });
    const entry = getCachedParse(cache, 'hash1');
    expect(entry?.filePath).toBe('a.ts');
    expect(getCachedParse(cache, 'missing')).toBeUndefined();
  });

  it('prunes unused entries', () => {
    const cache = {
      version: PARSE_CACHE_VERSION,
      entries: {
        hash1: { filePath: 'a.ts', nodes: [], edges: [] },
        hash2: { filePath: 'b.ts', nodes: [], edges: [] },
      },
    };
    pruneParseCache(cache, new Set(['hash1']));
    expect(getCachedParse(cache, 'hash1')).toBeDefined();
    expect(getCachedParse(cache, 'hash2')).toBeUndefined();
  });
});
