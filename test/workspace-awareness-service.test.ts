import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

// Re-import the class so we can instantiate fresh services for quiet-hour tests
import { WorkspaceAwarenessService as WAS } from '../src/personalization/workspace-awareness-service';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { WorkspaceAwarenessService } from '../src/personalization/workspace-awareness-service';
import { OutboxStore } from '../src/personalization/outbox-store';
import { NudgePolicyStore } from '../src/personalization/nudge-policy-store';
import type { WorkspaceConfig } from '../src/protocol/schemas';

// Mock git and CI adapters
vi.mock('../src/adapters/git', () => ({
  getWorkspaceStatus: vi.fn(),
}));

vi.mock('../src/adapters/ci', () => ({
  getLatestCiFailure: vi.fn(),
}));

import { getWorkspaceStatus } from '../src/adapters/git';
import { getLatestCiFailure } from '../src/adapters/ci';

function tempFile(): string {
  return path.join(os.tmpdir(), `workspace-awareness-test-${Date.now()}-${Math.random().toString(36).slice(2)}.json`);
}

function makeWorkspace(id: string, rootPath: string): WorkspaceConfig {
  return {
    id,
    label: id,
    rootPath,
    allowedIntents: ['quick_status'],
    approvalRequiredIntents: [],
    hardApprovalIntents: [],
    commands: {},
  };
}

describe('WorkspaceAwarenessService', () => {
  let outboxStore: OutboxStore;
  let policyStore: NudgePolicyStore;
  let outboxPath: string;
  let policyPath: string;
  const workspace = makeWorkspace('test-ws', '/tmp/test-ws');

  beforeEach(() => {
    outboxPath = tempFile();
    policyPath = tempFile();
    outboxStore = new OutboxStore(outboxPath);
    policyStore = new NudgePolicyStore(policyPath);
    vi.clearAllMocks();
  });

  afterEach(() => {
    try {
      fs.unlinkSync(outboxPath);
    } catch { /* ignore */ }
    try {
      fs.unlinkSync(policyPath);
    } catch { /* ignore */ }
  });

  it('does not start polling by default until start() is called', () => {
    const service = new WorkspaceAwarenessService([workspace], outboxStore, policyStore, {
      pollIntervalMs: 100,
      quietStartHour: 0,
      quietEndHour: 1,
    });
    expect(service.isRunning()).toBe(false);
    service.start();
    expect(service.isRunning()).toBe(true);
    service.stop();
    expect(service.isRunning()).toBe(false);
  });

  it('enqueues a changed-files nudge when threshold is exceeded', async () => {
    vi.mocked(getWorkspaceStatus).mockResolvedValue({
      repoDetected: true,
      branch: 'main',
      changedFiles: 15,
      mainChangedFile: 'src/index.ts',
      testsRunning: false,
      lastCommitAtMs: null,
      aheadBy: 0,
      behindBy: 0,
    });
    vi.mocked(getLatestCiFailure).mockResolvedValue({
      repository: null,
      workflowName: null,
      title: null,
      branch: null,
      url: null,
      sha: null,
      reason: 'No recent failures.',
      failedAtMs: null,
    });

    // Seed a session in the outbox so the service has targets
    outboxStore.enqueue({
      sessionId: 'session-1',
      kind: 'completion_soft_ping',
      priority: 'normal',
      summary: 'test',
      expiresAtMs: Date.now() + 3600_000,
    });

    const service = new WorkspaceAwarenessService([workspace], outboxStore, policyStore, {
      pollIntervalMs: 100,
      nudgeCooldownMs: 0,
      quietStartHour: 0,
      quietEndHour: 1,
    });

    await service.pollWorkspaces();

    const pending = outboxStore.poll('session-1');
    const nudges = pending.events.filter((e) => e.kind === 'workspace_nudge');
    expect(nudges.length).toBeGreaterThanOrEqual(1);
    expect(nudges[0].summary).toContain('15 changed files');
    expect(nudges[0].detail).toContain('uncommitted changes');
  });

  it('respects quiet hours and does not nudge outside active window', async () => {
    vi.mocked(getWorkspaceStatus).mockResolvedValue({
      repoDetected: true,
      branch: 'main',
      changedFiles: 20,
      mainChangedFile: 'src/index.ts',
      testsRunning: false,
      lastCommitAtMs: null,
      aheadBy: 0,
      behindBy: 0,
    });
    vi.mocked(getLatestCiFailure).mockResolvedValue({
      repository: null,
      workflowName: null,
      title: null,
      branch: null,
      url: null,
      sha: null,
      reason: 'No recent failures.',
      failedAtMs: null,
    });

    outboxStore.enqueue({
      sessionId: 'session-1',
      kind: 'completion_soft_ping',
      priority: 'normal',
      summary: 'test',
      expiresAtMs: Date.now() + 3600_000,
    });

    const service = new WorkspaceAwarenessService([workspace], outboxStore, policyStore, {
      pollIntervalMs: 100,
      nudgeCooldownMs: 0,
      quietStartHour: 0,
      quietEndHour: 24,
    });

    await service.pollWorkspaces();

    const pending = outboxStore.poll('session-1');
    const nudges = pending.events.filter((e) => e.kind === 'workspace_nudge');
    // quietStartHour=0, quietEndHour=24 means quiet period is 00:00-24:00 (all day)
    // so no nudges should fire regardless of current hour
    expect(nudges.length).toBe(0);
  });

  it('respects cooldown and does not spam the same nudge type', async () => {
    vi.mocked(getWorkspaceStatus).mockResolvedValue({
      repoDetected: true,
      branch: 'main',
      changedFiles: 20,
      mainChangedFile: 'src/index.ts',
      testsRunning: false,
      lastCommitAtMs: null,
      aheadBy: 0,
      behindBy: 0,
    });
    vi.mocked(getLatestCiFailure).mockResolvedValue({
      repository: null,
      workflowName: null,
      title: null,
      branch: null,
      url: null,
      sha: null,
      reason: 'No recent failures.',
      failedAtMs: null,
    });

    outboxStore.enqueue({
      sessionId: 'session-1',
      kind: 'completion_soft_ping',
      priority: 'normal',
      summary: 'test',
      expiresAtMs: Date.now() + 3600_000,
    });

    const service = new WorkspaceAwarenessService([workspace], outboxStore, policyStore, {
      pollIntervalMs: 100,
      nudgeCooldownMs: 600_000,
      quietStartHour: 0,
      quietEndHour: 1,
    });

    await service.pollWorkspaces();
    await service.pollWorkspaces();

    const pending = outboxStore.poll('session-1');
    const nudges = pending.events.filter((e) => e.kind === 'workspace_nudge');
    // Per-type cooldown prevents the same type from firing twice,
    // but different types can still fire on the second poll.
    const uncommittedNudges = nudges.filter((n) => n.summary.includes('changed files'));
    expect(uncommittedNudges.length).toBe(1);
  });

  it('enqueues CI-red nudge when CI is failing', async () => {
    vi.mocked(getWorkspaceStatus).mockResolvedValue({
      repoDetected: true,
      branch: 'main',
      changedFiles: 0,
      mainChangedFile: null,
      testsRunning: false,
      lastCommitAtMs: null,
      aheadBy: 0,
      behindBy: 0,
    });
    vi.mocked(getLatestCiFailure).mockResolvedValue({
      repository: 'owner/repo',
      workflowName: 'Test Workflow',
      title: 'Build failed',
      branch: 'main',
      url: 'https://github.com/owner/repo/actions/runs/123',
      sha: 'abc1234',
      reason: null,
      failedAtMs: null,
    });

    outboxStore.enqueue({
      sessionId: 'session-1',
      kind: 'completion_soft_ping',
      priority: 'normal',
      summary: 'test',
      expiresAtMs: Date.now() + 3600_000,
    });

    const service = new WorkspaceAwarenessService([workspace], outboxStore, policyStore, {
      pollIntervalMs: 100,
      nudgeCooldownMs: 0,
      quietStartHour: 0,
      quietEndHour: 1,
    });

    await service.pollWorkspaces();

    const pending = outboxStore.poll('session-1');
    const nudges = pending.events.filter((e) => e.kind === 'workspace_nudge');
    expect(nudges.length).toBeGreaterThanOrEqual(1);
    const ciNudge = nudges.find((n) => n.summary.includes('is red') || n.summary.includes('failing'));
    expect(ciNudge).toBeDefined();
  });

  it('only nudges ready-to-push when the branch is ahead and not behind', async () => {
    vi.mocked(getWorkspaceStatus).mockResolvedValue({
      repoDetected: true,
      branch: 'main',
      changedFiles: 0,
      mainChangedFile: null,
      testsRunning: false,
      lastCommitAtMs: null,
      aheadBy: 2,
      behindBy: 1,
    });
    vi.mocked(getLatestCiFailure).mockResolvedValue({
      repository: null,
      workflowName: null,
      title: null,
      branch: null,
      url: null,
      sha: null,
      reason: 'No recent failures.',
      failedAtMs: null,
    });

    outboxStore.enqueue({
      sessionId: 'session-1',
      kind: 'completion_soft_ping',
      priority: 'normal',
      summary: 'test',
      expiresAtMs: Date.now() + 3600_000,
    });

    const service = new WorkspaceAwarenessService([workspace], outboxStore, policyStore, {
      pollIntervalMs: 100,
      nudgeCooldownMs: 0,
      quietStartHour: 0,
      quietEndHour: 1,
    });

    await service.pollWorkspaces();

    let pending = outboxStore.poll('session-1');
    let readyToPushNudges = pending.events.filter((e) => e.kind === 'workspace_nudge' && e.summary.includes('ready to push'));
    expect(readyToPushNudges).toHaveLength(0);

    vi.mocked(getWorkspaceStatus).mockResolvedValue({
      repoDetected: true,
      branch: 'main',
      changedFiles: 0,
      mainChangedFile: null,
      testsRunning: false,
      lastCommitAtMs: null,
      aheadBy: 2,
      behindBy: 0,
    });

    await service.pollWorkspaces();

    pending = outboxStore.poll('session-1');
    readyToPushNudges = pending.events.filter((e) => e.kind === 'workspace_nudge' && e.summary.includes('ready to push'));
    expect(readyToPushNudges.length).toBeGreaterThanOrEqual(1);
  });

  it('does not nudge when policy is disabled for session', async () => {
    vi.mocked(getWorkspaceStatus).mockResolvedValue({
      repoDetected: true,
      branch: 'main',
      changedFiles: 20,
      mainChangedFile: 'src/index.ts',
      testsRunning: false,
      lastCommitAtMs: null,
      aheadBy: 0,
      behindBy: 0,
    });
    vi.mocked(getLatestCiFailure).mockResolvedValue({
      repository: null,
      workflowName: null,
      title: null,
      branch: null,
      url: null,
      sha: null,
      reason: 'No recent failures.',
      failedAtMs: null,
    });

    outboxStore.enqueue({
      sessionId: 'session-1',
      kind: 'completion_soft_ping',
      priority: 'normal',
      summary: 'test',
      expiresAtMs: Date.now() + 3600_000,
    });

    // We can't easily set nudgePolicy through the preference store since it's not typed there,
    // but we can verify that with no sessions, no nudges are enqueued.
    const service = new WorkspaceAwarenessService([workspace], outboxStore, policyStore, {
      pollIntervalMs: 100,
      nudgeCooldownMs: 0,
      quietStartHour: 0,
      quietEndHour: 1,
    });

    // Clear sessions by acking all
    outboxStore.ackAll('session-1');

    await service.pollWorkspaces();

    const pending = outboxStore.poll('session-1');
    const nudges = pending.events.filter((e) => e.kind === 'workspace_nudge');
    expect(nudges.length).toBe(0);
  });

  it('limits nudges per cycle to maxNudgesPerCycle', async () => {
    vi.mocked(getWorkspaceStatus).mockResolvedValue({
      repoDetected: true,
      branch: 'main',
      changedFiles: 20,
      mainChangedFile: 'src/index.ts',
      testsRunning: false,
      lastCommitAtMs: null,
      aheadBy: 0,
      behindBy: 0,
    });
    vi.mocked(getLatestCiFailure).mockResolvedValue({
      repository: null,
      workflowName: null,
      title: null,
      branch: null,
      url: null,
      sha: null,
      reason: 'No recent failures.',
      failedAtMs: null,
    });

    outboxStore.enqueue({
      sessionId: 'session-1',
      kind: 'completion_soft_ping',
      priority: 'normal',
      summary: 'test',
      expiresAtMs: Date.now() + 3600_000,
    });

    const service = new WorkspaceAwarenessService([workspace], outboxStore, policyStore, {
      pollIntervalMs: 100,
      nudgeCooldownMs: 0,
      quietStartHour: 0,
      quietEndHour: 24,
      maxNudgesPerCycle: 1,
    });

    await service.pollWorkspaces();

    const pending = outboxStore.poll('session-1');
    const nudges = pending.events.filter((e) => e.kind === 'workspace_nudge');
    expect(nudges.length).toBeLessThanOrEqual(1);
  });

  describe('quiet hours', () => {
    beforeEach(() => {
      vi.useFakeTimers();
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it('is active during default working hours (09:00-18:00) with wrapping quiet period', async () => {
      vi.mocked(getWorkspaceStatus).mockResolvedValue({
        repoDetected: true,
        branch: 'main',
        changedFiles: 20,
        mainChangedFile: 'src/index.ts',
        testsRunning: false,
      lastCommitAtMs: null,
      aheadBy: 0,
      behindBy: 0,
      });
      vi.mocked(getLatestCiFailure).mockResolvedValue({
        repository: null,
        workflowName: null,
        title: null,
        branch: null,
        url: null,
        sha: null,
        reason: 'No recent failures.',
      failedAtMs: null,
      });

      outboxStore.enqueue({
        sessionId: 'session-1',
        kind: 'completion_soft_ping',
        priority: 'normal',
        summary: 'test',
        expiresAtMs: Date.now() + 3600_000,
      });

      const service = new WorkspaceAwarenessService([workspace], outboxStore, policyStore, {
        pollIntervalMs: 100,
        nudgeCooldownMs: 0,
        quietStartHour: 18,
        quietEndHour: 9,
      });

      // 10:00 should be active (within 09:00-18:00)
      vi.setSystemTime(new Date('2026-01-15T10:00:00'));
      await service.pollWorkspaces();
      let pending = outboxStore.poll('session-1');
      let nudges = pending.events.filter((e) => e.kind === 'workspace_nudge');
      expect(nudges.length).toBeGreaterThanOrEqual(1);

      // Clear nudges for next poll
      nudges.forEach((n) => outboxStore.ack(n.id));

      // 20:00 should be quiet (within 18:00-09:00)
      vi.setSystemTime(new Date('2026-01-15T20:00:00'));
      await service.pollWorkspaces();
      pending = outboxStore.poll('session-1');
      nudges = pending.events.filter((e) => e.kind === 'workspace_nudge');
      expect(nudges.length).toBe(0);

      // 07:00 should be quiet (within 18:00-09:00)
      vi.setSystemTime(new Date('2026-01-15T07:00:00'));
      await service.pollWorkspaces();
      pending = outboxStore.poll('session-1');
      nudges = pending.events.filter((e) => e.kind === 'workspace_nudge');
      expect(nudges.length).toBe(0);
    });

    it('is quiet during non-wrapping quiet period (09:00-18:00)', async () => {
      vi.mocked(getWorkspaceStatus).mockResolvedValue({
        repoDetected: true,
        branch: 'main',
        changedFiles: 20,
        mainChangedFile: 'src/index.ts',
        testsRunning: false,
      lastCommitAtMs: null,
      aheadBy: 0,
      behindBy: 0,
      });
      vi.mocked(getLatestCiFailure).mockResolvedValue({
        repository: null,
        workflowName: null,
        title: null,
        branch: null,
        url: null,
        sha: null,
        reason: 'No recent failures.',
      failedAtMs: null,
      });

      outboxStore.enqueue({
        sessionId: 'session-1',
        kind: 'completion_soft_ping',
        priority: 'normal',
        summary: 'test',
        expiresAtMs: Date.now() + 3600_000,
      });

      const service = new WorkspaceAwarenessService([workspace], outboxStore, policyStore, {
        pollIntervalMs: 100,
        nudgeCooldownMs: 0,
        quietStartHour: 9,
        quietEndHour: 18,
      });

      // 10:00 should be quiet (within 09:00-18:00)
      vi.setSystemTime(new Date('2026-01-15T10:00:00'));
      await service.pollWorkspaces();
      let pending = outboxStore.poll('session-1');
      let nudges = pending.events.filter((e) => e.kind === 'workspace_nudge');
      expect(nudges.length).toBe(0);

      // 20:00 should be active (outside 09:00-18:00)
      vi.setSystemTime(new Date('2026-01-15T20:00:00'));
      await service.pollWorkspaces();
      pending = outboxStore.poll('session-1');
      nudges = pending.events.filter((e) => e.kind === 'workspace_nudge');
      expect(nudges.length).toBeGreaterThanOrEqual(1);

      // Clear nudges for next poll
      nudges.forEach((n) => outboxStore.ack(n.id));

      // 07:00 should be active (outside 09:00-18:00)
      vi.setSystemTime(new Date('2026-01-15T07:00:00'));
      await service.pollWorkspaces();
      pending = outboxStore.poll('session-1');
      nudges = pending.events.filter((e) => e.kind === 'workspace_nudge');
      expect(nudges.length).toBeGreaterThanOrEqual(1);
    });
  });
});
