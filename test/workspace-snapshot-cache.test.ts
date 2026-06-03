import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { WorkspaceConfig } from '../src/protocol/schemas';
import { WorkspaceSnapshotCache } from '../src/jarvis/workspace-snapshot-cache';
import { JarvisRuntime } from '../src/jarvis/runtime';
import { AuditLog } from '../src/bridge/audit-log';
import * as gitAdapter from '../src/adapters/git';
import * as ciAdapter from '../src/adapters/ci';

describe('workspace snapshot cache', () => {
  let repoDir: string;
  let auditLogPath: string;
  let cache: WorkspaceSnapshotCache;

  beforeEach(() => {
    repoDir = fs.mkdtempSync(path.join(os.tmpdir(), 'jarvis-cache-'));
    auditLogPath = path.join(repoDir, 'audit.log');
    execFileSync('git', ['init'], { cwd: repoDir, stdio: 'ignore' });
    execFileSync('git', ['config', 'user.email', 'jarvis@example.com'], { cwd: repoDir, stdio: 'ignore' });
    execFileSync('git', ['config', 'user.name', 'Jarvis'], { cwd: repoDir, stdio: 'ignore' });
    execFileSync('git', ['commit', '--allow-empty', '-m', 'chore: baseline'], { cwd: repoDir, stdio: 'ignore' });
    cache = new WorkspaceSnapshotCache();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    try {
      fs.rmSync(repoDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 50 });
    } catch (error) {
      if (!(error instanceof Error) || !error.message.includes('EPERM')) {
        throw error;
      }
    }
  });

  const buildWorkspace = (): WorkspaceConfig => ({
    id: 'test',
    label: 'test',
    rootPath: repoDir,
    allowedIntents: [],
    approvalRequiredIntents: [],
    hardApprovalIntents: [],
    commands: {},
  });

  it('returns hot data inside TTL', async () => {
    const workspace = buildWorkspace();
    const spy = vi.spyOn(gitAdapter, 'getWorkspaceStatus');

    const status1 = await cache.getStatus(workspace);
    const status2 = await cache.getStatus(workspace);

    expect(status1).toBe(status2);
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('bypasses cache with forceRefresh', async () => {
    const workspace = buildWorkspace();
    const spy = vi.spyOn(gitAdapter, 'getWorkspaceStatus');

    await cache.getStatus(workspace);
    await cache.getStatus(workspace, { forceRefresh: true });

    expect(spy).toHaveBeenCalledTimes(2);
  });

  it('prefetch warms cache safely', async () => {
    const nonGitDir = fs.mkdtempSync(path.join(os.tmpdir(), 'jarvis-nogit-'));
    try {
      await expect(cache.prefetch(nonGitDir)).resolves.not.toThrow();

      const workspace: WorkspaceConfig = {
        id: 'test',
        label: 'test',
        rootPath: nonGitDir,
        allowedIntents: [],
        approvalRequiredIntents: [],
        hardApprovalIntents: [],
        commands: {},
      };
      const spy = vi.spyOn(gitAdapter, 'getWorkspaceStatus');
      const status = await cache.getStatus(workspace);
      expect(status.repoDetected).toBe(false);
      expect(spy).toHaveBeenCalledTimes(0);
    } finally {
      fs.rmSync(nonGitDir, { recursive: true, force: true });
    }
  });

  it('invalidates cache so stale data does not survive mutations', async () => {
    fs.writeFileSync(path.join(repoDir, 'feat.ts'), 'export const x = 1;\n');
    execFileSync('git', ['add', 'feat.ts'], { cwd: repoDir, stdio: 'ignore' });

    const workspace = buildWorkspace();
    const auditLog = new AuditLog(auditLogPath);
    const runtime = new JarvisRuntime(auditLog, undefined, cache);

    const beforeStatus = await cache.getStatus(workspace);
    expect(beforeStatus.changedFiles).toBe(1);

    await runtime.executeIntent(
      'commit_staged',
      {
        sessionId: 's1',
        source: 'test',
        workspace: 'test',
        event: 'triple_tap_right',
        utterance: 'commit staged',
        gesture: null,
        riskPolicy: { approvalRequired: false, hardApproval: false },
        pendingActionId: null,
        approvalAction: null,
        deviceState: { activeBud: null, wearState: null, batteryPercent: null, profile: null },
        hardwareContext: null,
      } as any,
      workspace,
    );

    const afterStatus = await cache.getStatus(workspace);
    expect(afterStatus.changedFiles).toBe(0);
  });

  it('caches diff summary with the same TTL as status', async () => {
    fs.writeFileSync(path.join(repoDir, 'feat.ts'), 'export const x = 1;\n');

    const workspace = buildWorkspace();
    const spy = vi.spyOn(gitAdapter, 'getDiffSummary');

    const diff1 = await cache.getDiffSummary(workspace);
    const diff2 = await cache.getDiffSummary(workspace);

    expect(diff1).toBe(diff2);
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('caches CI failure snapshot with a longer TTL', async () => {
    const workspace = buildWorkspace();
    const spy = vi.spyOn(ciAdapter, 'getLatestCiFailure');

    const ci1 = await cache.getLatestCiFailure(workspace);
    const ci2 = await cache.getLatestCiFailure(workspace);

    expect(ci1).toBe(ci2);
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('invalidate removes all snapshot kinds for a workspace', async () => {
    fs.writeFileSync(path.join(repoDir, 'feat.ts'), 'export const x = 1;\n');

    const workspace = buildWorkspace();
    const statusSpy = vi.spyOn(gitAdapter, 'getWorkspaceStatus');
    const diffSpy = vi.spyOn(gitAdapter, 'getDiffSummary');
    const ciSpy = vi.spyOn(ciAdapter, 'getLatestCiFailure');

    await cache.getStatus(workspace);
    await cache.getDiffSummary(workspace);
    await cache.getLatestCiFailure(workspace);

    cache.invalidate(repoDir);

    await cache.getStatus(workspace);
    await cache.getDiffSummary(workspace);
    await cache.getLatestCiFailure(workspace);

    expect(statusSpy).toHaveBeenCalledTimes(2);
    expect(diffSpy).toHaveBeenCalledTimes(2);
    expect(ciSpy).toHaveBeenCalledTimes(2);
  });
});
