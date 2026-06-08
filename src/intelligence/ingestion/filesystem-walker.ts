/**
 * Filesystem walker — ported from GitNexus donor, terminology adapted.
 *
 * Two-phase design:
 *  1. Scan: enumerate paths + stat sizes (memory-light)
 *  2. Read: batch-read contents for selected paths
 */

import fs from 'node:fs/promises';
import path from 'node:path';
import { glob } from 'glob';
import { createIgnoreFilter } from './ignore-service';

export interface FileEntry {
  path: string;
  content: string;
}

export interface ScannedFile {
  path: string;
  size: number;
}

/** Default threshold: 512 KB. Files larger than this are skipped. */
export const DEFAULT_MAX_FILE_SIZE_BYTES = 512 * 1024;

const READ_CONCURRENCY = 32;

/**
 * Resolve the effective file-size skip threshold (bytes).
 * Reads `DEVPODS_INTELLIGENCE_MAX_FILE_SIZE` (KB).
 */
const getMaxFileSizeBytes = (): number => {
  const raw = process.env.DEVPODS_INTELLIGENCE_MAX_FILE_SIZE;
  if (!raw) return DEFAULT_MAX_FILE_SIZE_BYTES;
  const parsed = Number(raw);
  if (!Number.isFinite(parsed) || parsed <= 0 || !Number.isInteger(parsed)) {
    return DEFAULT_MAX_FILE_SIZE_BYTES;
  }
  return parsed * 1024;
};

/**
 * Phase 1: Scan workspace — stat files to get paths + sizes, no content loaded.
 */
export const walkWorkspacePaths = async (
  workspacePath: string,
  onProgress?: (current: number, total: number, filePath: string) => void,
): Promise<ScannedFile[]> => {
  const ignoreFilter = await createIgnoreFilter(workspacePath);
  const maxFileSizeBytes = getMaxFileSizeBytes();

  const filtered = await glob('**/*', {
    cwd: workspacePath,
    nodir: true,
    dot: false,
    ignore: ignoreFilter,
  });

  const entries: ScannedFile[] = [];
  let processed = 0;
  let skippedLarge = 0;
  const skippedLargePaths: string[] = [];

  for (let start = 0; start < filtered.length; start += READ_CONCURRENCY) {
    const batch = filtered.slice(start, start + READ_CONCURRENCY);
    const results = await Promise.allSettled(
      batch.map(async (relativePath) => {
        const fullPath = path.join(workspacePath, relativePath);
        const stat = await fs.stat(fullPath);
        if (stat.size > maxFileSizeBytes) {
          skippedLarge++;
          skippedLargePaths.push(relativePath.replace(/\\/g, '/'));
          return null;
        }
        return { path: relativePath.replace(/\\/g, '/'), size: stat.size };
      }),
    );

    for (const result of results) {
      processed++;
      if (result.status === 'fulfilled' && result.value !== null) {
        entries.push(result.value);
        onProgress?.(processed, filtered.length, result.value.path);
      } else {
        const idx = results.indexOf(result);
        onProgress?.(processed, filtered.length, batch[idx] ?? '');
      }
    }
  }

  if (skippedLarge > 0) {
    skippedLargePaths.sort();
    const preview = skippedLargePaths.slice(0, 5);
    // eslint-disable-next-line no-console
    console.warn(`Skipped ${skippedLarge} large files (>${maxFileSizeBytes / 1024}KB)`);
    for (const p of preview) {
      // eslint-disable-next-line no-console
      console.warn(`  - ${p}`);
    }
    if (skippedLargePaths.length > 5) {
      // eslint-disable-next-line no-console
      console.warn(`  ...and ${skippedLargePaths.length - 5} more`);
    }
  }

  return entries;
};

/**
 * Phase 2: Read file contents for a specific set of relative paths.
 * Returns a Map for O(1) lookup. Silently skips files that fail to read.
 */
export const readFileContents = async (
  workspacePath: string,
  relativePaths: string[],
): Promise<Map<string, string>> => {
  const contents = new Map<string, string>();

  for (let start = 0; start < relativePaths.length; start += READ_CONCURRENCY) {
    const batch = relativePaths.slice(start, start + READ_CONCURRENCY);
    const results = await Promise.allSettled(
      batch.map(async (relativePath) => {
        const fullPath = path.join(workspacePath, relativePath);
        const content = await fs.readFile(fullPath, 'utf-8');
        return { path: relativePath, content };
      }),
    );

    for (const result of results) {
      if (result.status === 'fulfilled') {
        contents.set(result.value.path, result.value.content);
      }
    }
  }

  return contents;
};

/**
 * Convenience API — scans and reads everything into memory.
 */
export const walkWorkspace = async (
  workspacePath: string,
  onProgress?: (current: number, total: number, filePath: string) => void,
): Promise<FileEntry[]> => {
  const scanned = await walkWorkspacePaths(workspacePath, onProgress);
  const contents = await readFileContents(
    workspacePath,
    scanned.map((f) => f.path),
  );
  return scanned
    .filter((f) => contents.has(f.path))
    .map((f) => ({ path: f.path, content: contents.get(f.path)! }));
};
