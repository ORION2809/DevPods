/**
 * Workspace index metadata — lightweight registry per workspace.
 *
 * Each indexed workspace stores metadata alongside its graph DB:
 *   runtime-data/intelligence/<hash>/index-meta.json
 *
 * Tracks file hashes for incremental indexing, schema version for
 * compatibility, and indexing timestamps for staleness checks.
 */

import fs from 'node:fs/promises';
import path from 'node:path';

/** Bump whenever incremental-indexing invariants change incompatibly. */
export const INDEX_SCHEMA_VERSION = 1;

export interface WorkspaceIndexMeta {
  /** Workspace identifier (hashed for path safety elsewhere). */
  workspaceId: string;
  /** Absolute path to the workspace root at index time. */
  workspacePath: string;
  /** ISO timestamp of the last successful indexing run. */
  indexedAt: string;
  /** Number of source files indexed. */
  fileCount: number;
  /** Schema version at index time. */
  schemaVersion: number;
  /** SHA-256 of every file's content at last index. Keys are workspace-relative paths. */
  fileHashes?: Record<string, string>;
  /** Crash-recovery dirty flag. Set before destructive DB mutation; cleared on success. */
  incrementalInProgress?: {
    startedAt: number;
    toWriteCount: number;
  };
  /** Optional indexing statistics. */
  stats?: {
    nodes?: number;
    edges?: number;
    durationMs?: number;
  };
}

/**
 * Load workspace metadata from the index directory.
 * Returns null if the file does not exist or is corrupt.
 */
export const loadWorkspaceMeta = async (indexDir: string): Promise<WorkspaceIndexMeta | null> => {
  try {
    const metaPath = path.join(indexDir, 'index-meta.json');
    const raw = await fs.readFile(metaPath, 'utf-8');
    const parsed = JSON.parse(raw) as WorkspaceIndexMeta;
    if (parsed.schemaVersion !== INDEX_SCHEMA_VERSION) {
      // Schema mismatch — force full rebuild
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
};

/**
 * Save workspace metadata atomically (tmp-file + rename).
 */
export const saveWorkspaceMeta = async (
  indexDir: string,
  meta: WorkspaceIndexMeta,
): Promise<void> => {
  await fs.mkdir(indexDir, { recursive: true });
  const metaPath = path.join(indexDir, 'index-meta.json');
  const tmpPath = `${metaPath}.tmp`;
  await fs.writeFile(tmpPath, JSON.stringify(meta, null, 2), 'utf-8');
  await fs.rename(tmpPath, metaPath);
};
