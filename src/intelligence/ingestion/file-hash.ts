/**
 * Per-file content hashing for incremental indexing.
 *
 * On every index run we compute SHA-256 of every file's content and
 * store the map in index-meta.json. The next run diffs disk against
 * the stored map to determine which files' graph rows must be replaced.
 */

import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

const BATCH_SIZE = 100;

/**
 * Compute SHA-256 of a single file. Returns null when the file can't be read.
 */
export const computeFileHash = async (absPath: string): Promise<string | null> => {
  try {
    const buf = await fs.readFile(absPath);
    return createHash('sha256').update(buf).digest('hex');
  } catch {
    return null;
  }
};

/**
 * Compute SHA-256 hashes for many files in parallel batches.
 * Files that fail to read are omitted from the result map.
 */
export const computeFileHashes = async (
  workspacePath: string,
  relPaths: readonly string[],
): Promise<Map<string, string>> => {
  const out = new Map<string, string>();
  for (let i = 0; i < relPaths.length; i += BATCH_SIZE) {
    const batch = relPaths.slice(i, i + BATCH_SIZE);
    const results = await Promise.all(
      batch.map(async (rel) => {
        const h = await computeFileHash(path.join(workspacePath, rel));
        return h ? ([rel, h] as const) : null;
      }),
    );
    for (const r of results) if (r) out.set(r[0], r[1]);
  }
  return out;
};

/** Result of comparing current on-disk hashes against stored ones. */
export interface FileHashDiff {
  /** Files whose content hash differs from stored. */
  changed: string[];
  /** Files in the current scan that weren't in the stored map. */
  added: string[];
  /** Files in the stored map that aren't in the current scan. */
  deleted: string[];
  /** All files whose graph rows must be replaced (changed ∪ added). */
  toWrite: string[];
}

/**
 * Diff a current hash map against a previously stored one.
 * Sorted output for stable logging and deterministic test behaviour.
 */
export const diffFileHashes = (
  current: ReadonlyMap<string, string>,
  stored: Readonly<Record<string, string>> | undefined,
): FileHashDiff => {
  const storedMap = new Map<string, string>(stored ? Object.entries(stored) : []);
  const changed: string[] = [];
  const added: string[] = [];
  for (const [p, h] of current) {
    const prev = storedMap.get(p);
    if (prev === undefined) added.push(p);
    else if (prev !== h) changed.push(p);
  }
  const deleted: string[] = [];
  for (const p of storedMap.keys()) {
    if (!current.has(p)) deleted.push(p);
  }
  changed.sort();
  added.sort();
  deleted.sort();
  return {
    changed,
    added,
    deleted,
    toWrite: [...changed, ...added].sort(),
  };
};
