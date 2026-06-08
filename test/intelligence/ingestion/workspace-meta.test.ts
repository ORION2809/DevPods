import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { loadWorkspaceMeta, saveWorkspaceMeta, INDEX_SCHEMA_VERSION } from '../../../src/intelligence/ingestion/workspace-meta';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

describe('workspace meta', () => {
  let tmpDir: string;

  beforeEach(() => {
    tmpDir = mkdtempSync(join(tmpdir(), 'devpods-meta-test-'));
  });

  afterEach(() => {
    rmSync(tmpDir, { recursive: true, force: true });
  });

  it('returns null when no meta exists', async () => {
    const meta = await loadWorkspaceMeta(tmpDir);
    expect(meta).toBeNull();
  });

  it('saves and loads meta', async () => {
    const meta = {
      workspaceId: 'ws1',
      workspacePath: '/projects/ws1',
      indexedAt: new Date().toISOString(),
      fileCount: 42,
      schemaVersion: INDEX_SCHEMA_VERSION,
      fileHashes: { 'src/main.ts': 'abc123' },
    };

    await saveWorkspaceMeta(tmpDir, meta);
    const loaded = await loadWorkspaceMeta(tmpDir);

    expect(loaded).toEqual(meta);
  });

  it('returns null on schema version mismatch', async () => {
    const meta = {
      workspaceId: 'ws1',
      workspacePath: '/projects/ws1',
      indexedAt: new Date().toISOString(),
      fileCount: 1,
      schemaVersion: 999,
    };

    await saveWorkspaceMeta(tmpDir, meta as any);
    const loaded = await loadWorkspaceMeta(tmpDir);
    expect(loaded).toBeNull();
  });
});
