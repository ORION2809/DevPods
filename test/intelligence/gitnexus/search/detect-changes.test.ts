import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { GraphStore } from '../../../../src/intelligence/gitnexus/graph/graph-store';
import { detectChanges } from '../../../../src/intelligence/gitnexus/search/detect-changes';
import { saveWorkspaceMeta } from '../../../../src/intelligence/ingestion/workspace-meta';
import { execFileSync } from 'node:child_process';

describe('detectChanges', () => {
  let tmpDir: string;
  let store: GraphStore;

  beforeEach(async () => {
    tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), 'gitnexus-detect-'));
    const dbPath = path.join(tmpDir, 'codegraph.db');
    store = new GraphStore(dbPath);
    await store.init();
    await store.createSchema();
  });

  afterEach(async () => {
    await store.close();
    await fs.rm(tmpDir, { recursive: true, force: true });
  });

  it('returns empty result when no workspace meta exists', async () => {
    const result = await detectChanges(store, tmpDir);
    expect(result.changedAreas).toBe(0);
    expect(result.files).toHaveLength(0);
    expect(result.safeToContinue).toBe(true);
  });

  it('returns empty result when no git changes exist', async () => {
    // Initialize a git repo so git diff works
    execFileSync('git', ['init'], { cwd: tmpDir });
    execFileSync('git', ['config', 'user.email', 'test@test.com'], { cwd: tmpDir });
    execFileSync('git', ['config', 'user.name', 'Test'], { cwd: tmpDir });

    await saveWorkspaceMeta(tmpDir, {
      workspaceId: 'test-ws',
      workspacePath: tmpDir,
      indexedAt: new Date().toISOString(),
      fileCount: 0,
      schemaVersion: 1,
    });

    const result = await detectChanges(store, tmpDir);
    expect(result.changedAreas).toBe(0);
    expect(result.files).toHaveLength(0);
  });

  it('detects changed files and maps them to symbols', async () => {
    // Use a subdirectory for the git workspace so the DB file doesn't interfere
    const wsDir = path.join(tmpDir, 'workspace');
    await fs.mkdir(wsDir, { recursive: true });

    execFileSync('git', ['init'], { cwd: wsDir });
    execFileSync('git', ['config', 'user.email', 'test@test.com'], { cwd: wsDir });
    execFileSync('git', ['config', 'user.name', 'Test'], { cwd: wsDir });

    // Create and commit a file
    const filePath = path.join(wsDir, 'src', 'handler.ts');
    await fs.mkdir(path.dirname(filePath), { recursive: true });
    await fs.writeFile(filePath, 'export function handleEvent() {}');
    execFileSync('git', ['add', '.'], { cwd: wsDir });
    execFileSync('git', ['commit', '-m', 'initial'], { cwd: wsDir });

    // Modify the file
    await fs.writeFile(filePath, 'export function handleEvent() { return 1; }');

    // Seed graph with symbol (use workspace-relative path)
    await store.query(
      `CREATE (f:Function {id: 'f1', name: 'handleEvent', filePath: 'src/handler.ts', startLine: 1, endLine: 1, isExported: true})`,
    );

    await saveWorkspaceMeta(tmpDir, {
      workspaceId: 'test-ws',
      workspacePath: wsDir,
      indexedAt: new Date().toISOString(),
      fileCount: 1,
      schemaVersion: 1,
    });

    const result = await detectChanges(store, tmpDir);
    expect(result.files.length).toBeGreaterThan(0);
    expect(result.changedAreas).toBeGreaterThan(0);
    expect(result.affectedFlows).toBe(1);
  });
});
