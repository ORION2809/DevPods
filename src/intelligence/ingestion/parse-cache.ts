/**
 * Parse cache — content-addressed storage for parsed file results.
 *
 * When a file's content hasn't changed since the last index run,
 * its parsed symbols can be reused instead of re-parsing with tree-sitter.
 *
 * Cache key: SHA-256 of file content.
 * Storage: JSON file in the workspace index directory.
 */

import fs from 'node:fs/promises';
import path from 'node:path';

/** Version bumps invalidate the entire cache when parser rules change. */
export const PARSE_CACHE_VERSION = '1';

export interface ParseCacheEntry {
  /** File path (workspace-relative). */
  filePath: string;
  /** Parser-produced nodes for this file. */
  nodes: unknown[];
  /** Parser-produced edges for this file. */
  edges: unknown[];
}

export interface ParseCache {
  version: string;
  entries: Record<string, ParseCacheEntry>;
}

/**
 * Load the parse cache from disk. Returns empty cache on corruption or version mismatch.
 */
export const loadParseCache = async (indexDir: string): Promise<ParseCache> => {
  try {
    const cachePath = path.join(indexDir, 'parse-cache.json');
    const raw = await fs.readFile(cachePath, 'utf-8');
    const parsed = JSON.parse(raw) as ParseCache;
    if (parsed.version !== PARSE_CACHE_VERSION) {
      return { version: PARSE_CACHE_VERSION, entries: {} };
    }
    return parsed;
  } catch {
    return { version: PARSE_CACHE_VERSION, entries: {} };
  }
};

/**
 * Save the parse cache atomically.
 */
export const saveParseCache = async (indexDir: string, cache: ParseCache): Promise<void> => {
  await fs.mkdir(indexDir, { recursive: true });
  const cachePath = path.join(indexDir, 'parse-cache.json');
  const tmpPath = `${cachePath}.tmp`;
  await fs.writeFile(tmpPath, JSON.stringify(cache, null, 2), 'utf-8');
  await fs.rename(tmpPath, cachePath);
};

/**
 * Look up a cached parse result by content hash.
 */
export const getCachedParse = (
  cache: ParseCache,
  contentHash: string,
): ParseCacheEntry | undefined => {
  return cache.entries[contentHash];
};

/**
 * Store a parsed result in the cache.
 */
export const setCachedParse = (
  cache: ParseCache,
  contentHash: string,
  entry: ParseCacheEntry,
): void => {
  cache.entries[contentHash] = entry;
};

/**
 * Remove cache entries whose hashes are no longer referenced.
 */
export const pruneParseCache = (cache: ParseCache, usedHashes: Set<string>): void => {
  for (const key of Object.keys(cache.entries)) {
    if (!usedHashes.has(key)) {
      delete cache.entries[key];
    }
  }
};
