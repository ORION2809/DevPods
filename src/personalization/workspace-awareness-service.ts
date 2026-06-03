import { OutboxStore } from './outbox-store.js';
import { NudgePolicyStore } from './nudge-policy-store.js';
import {
  WorkspaceConfig,
  NudgePolicy,
  NudgeType,
} from '../protocol/schemas.js';
import {
  getWorkspaceStatus,
  WorkspaceStatus,
} from '../adapters/git.js';
import {
  getLatestCiFailure,
  LatestCiFailureSummary,
} from '../adapters/ci.js';
import { formatWorkspaceLabel } from '../adapters/workspace.js';

export interface WorkspaceAwarenessConfig {
  /** Polling interval in ms (default 60000) */
  pollIntervalMs: number;
  /** Minimum time between nudges of the same type for the same workspace (default 300000 = 5 min) */
  nudgeCooldownMs: number;
  /** Quiet hours start: no nudges during quiet period (default 18 = 18:00) */
  quietStartHour: number;
  /** Quiet hours end: quiet period ends at this hour (default 9 = 09:00) */
  quietEndHour: number;
  /** Maximum nudges per session per poll cycle (default 3) */
  maxNudgesPerCycle: number;
}

const DEFAULT_CONFIG: WorkspaceAwarenessConfig = {
  pollIntervalMs: 60000,
  nudgeCooldownMs: 300000,
  quietStartHour: 18,
  quietEndHour: 9,
  maxNudgesPerCycle: 3,
};

interface WorkspaceSnapshot {
  workspace: WorkspaceConfig;
  gitStatus: WorkspaceStatus | null;
  ciFailure: LatestCiFailureSummary | null;
  fetchedAtMs: number;
}

interface NudgeState {
  lastNudgedAtMs: Record<string, number>;
  consecutiveTestFailures: Record<string, number>;
}

export class WorkspaceAwarenessService {
  private timer: ReturnType<typeof setInterval> | null = null;
  private readonly config: WorkspaceAwarenessConfig;
  private readonly state: NudgeState = {
    lastNudgedAtMs: {},
    consecutiveTestFailures: {},
  };
  private lastPollMs = 0;

  constructor(
    private readonly workspaces: WorkspaceConfig[],
    private readonly outboxStore: OutboxStore,
    private readonly policyStore: NudgePolicyStore,
    config?: Partial<WorkspaceAwarenessConfig>,
  ) {
    this.config = { ...DEFAULT_CONFIG, ...config };
  }

  start(): void {
    if (this.timer) return;
    this.timer = setInterval(() => {
      this.pollWorkspaces().catch(() => {
        /* errors are swallowed to keep timer alive */
      });
    }, this.config.pollIntervalMs);
    if (typeof this.timer.unref === 'function') {
      this.timer.unref();
    }
  }

  stop(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  isRunning(): boolean {
    return this.timer !== null;
  }

  getLastPollMs(): number {
    return this.lastPollMs;
  }

  async pollWorkspaces(): Promise<void> {
    this.lastPollMs = Date.now();
    const snapshots = await this.fetchSnapshots();

    for (const snapshot of snapshots) {
      const nudges = this.evaluateSnapshot(snapshot);
      if (nudges.length === 0) continue;

      // Apply per-cycle limit
      const toEnqueue = nudges.slice(0, this.config.maxNudgesPerCycle);
      for (const nudge of toEnqueue) {
        this.enqueueNudge(nudge);
      }
    }
  }

  private async fetchSnapshots(): Promise<WorkspaceSnapshot[]> {
    const results: WorkspaceSnapshot[] = [];
    for (const workspace of this.workspaces) {
      try {
        const [gitStatus, ciFailure] = await Promise.allSettled([
          getWorkspaceStatus(workspace.rootPath),
          getLatestCiFailure(workspace.rootPath),
        ]);
        results.push({
          workspace,
          gitStatus: gitStatus.status === 'fulfilled' ? gitStatus.value : null,
          ciFailure: ciFailure.status === 'fulfilled' ? ciFailure.value : null,
          fetchedAtMs: Date.now(),
        });
      } catch {
        // Skip workspaces that fail to fetch
      }
    }
    return results;
  }

  private evaluateSnapshot(snapshot: WorkspaceSnapshot): Array<{
    sessionId: string;
    type: NudgeType;
    summary: string;
    detail: string;
    workspaceLabel: string;
  }> {
    const nudges: Array<{
      sessionId: string;
      type: NudgeType;
      summary: string;
      detail: string;
      workspaceLabel: string;
    }> = [];

    const sessionIds = this.outboxStore.getAllSessionIds();
    if (sessionIds.length === 0) return nudges;

    const label = formatWorkspaceLabel(snapshot.workspace);

    for (const sessionId of sessionIds) {
      const policy = this.getPolicy(sessionId);
      if (!policy.enabled) continue;

      for (const threshold of policy.thresholds) {
        if (policy.mutedTypes.includes(threshold.type)) continue;
        if (!this.isInActiveHours()) continue;
        if (this.isOnCooldown(sessionId, threshold.type)) continue;

        const shouldNudge = this.checkThreshold(threshold.type, threshold, snapshot);
        if (shouldNudge) {
          const { summary, detail } = this.buildNudgeMessage(threshold.type, snapshot);
          nudges.push({
            sessionId,
            type: threshold.type,
            summary,
            detail,
            workspaceLabel: label,
          });
          this.recordNudge(sessionId, threshold.type);
          break; // one nudge per workspace per session per poll
        }
      }
    }

    return nudges;
  }

  private getPolicy(sessionId: string): NudgePolicy {
    const stored = this.policyStore.getPolicy(sessionId);
    if (stored.thresholds.length === 0) {
      return {
        ...stored,
        thresholds: DEFAULT_THRESHOLDS,
      };
    }
    return stored;
  }

  private isInActiveHours(): boolean {
    const now = new Date();
    const hour = now.getHours();
    const quietStart = this.config.quietStartHour;
    const quietEnd = this.config.quietEndHour;

    if (quietStart < quietEnd) {
      // Quiet period is a contiguous block within the day (e.g., 18:00–09:00 is NOT this case)
      // Active when OUTSIDE the quiet window: hour < quietStart OR hour >= quietEnd
      return hour < quietStart || hour >= quietEnd;
    }
    // Quiet period wraps midnight (e.g., 22:00–08:00)
    // Active when NOT in quiet window: hour >= quietEnd && hour < quietStart
    return hour >= quietEnd && hour < quietStart;
  }

  private isOnCooldown(sessionId: string, type: NudgeType): boolean {
    const key = `${sessionId}:${type}`;
    const last = this.state.lastNudgedAtMs[key] ?? 0;
    const diff = Date.now() - last;
    // If the clock moved backward (e.g., fake timers in tests or NTP adjust),
    // treat as not on cooldown.
    if (diff < 0) return false;
    return diff < this.config.nudgeCooldownMs;
  }

  private recordNudge(sessionId: string, type: NudgeType): void {
    const key = `${sessionId}:${type}`;
    this.state.lastNudgedAtMs[key] = Date.now();
  }

  private checkThreshold(
    type: NudgeType,
    threshold: { type: NudgeType; changedFilesMin: number; staleBranchHours: number; consecutiveTestFailures: number; ciRedHours: number },
    snapshot: WorkspaceSnapshot,
  ): boolean {
    switch (type) {
      case 'uncommitted_files': {
        const count = snapshot.gitStatus?.changedFiles ?? 0;
        return count >= threshold.changedFilesMin;
      }
      case 'stale_branch': {
        const lastCommitAtMs = snapshot.gitStatus?.lastCommitAtMs;
        const changed = snapshot.gitStatus?.changedFiles ?? 0;
        if (!lastCommitAtMs || changed === 0) return false;
        const hoursSinceCommit = (Date.now() - lastCommitAtMs) / (60 * 60 * 1000);
        return hoursSinceCommit >= threshold.staleBranchHours;
      }
      case 'tests_failing': {
        const key = snapshot.workspace.rootPath;
        const current = this.state.consecutiveTestFailures[key] ?? 0;
        const ciRed = snapshot.ciFailure !== null && snapshot.ciFailure.reason === null;
        if (ciRed) {
          this.state.consecutiveTestFailures[key] = current + 1;
        } else {
          this.state.consecutiveTestFailures[key] = 0;
        }
        return (this.state.consecutiveTestFailures[key] ?? 0) >= threshold.consecutiveTestFailures;
      }
      case 'ci_red': {
        const ciRed = snapshot.ciFailure !== null && snapshot.ciFailure.reason === null;
        if (!ciRed) return false;
        const failedAtMs = snapshot.ciFailure?.failedAtMs;
        if (!failedAtMs) return true;
        const hoursSinceFailure = (Date.now() - failedAtMs) / (60 * 60 * 1000);
        return hoursSinceFailure >= threshold.ciRedHours;
      }
      case 'ready_to_push': {
        const aheadBy = snapshot.gitStatus?.aheadBy ?? 0;
        const behindBy = snapshot.gitStatus?.behindBy ?? 0;
        const changed = snapshot.gitStatus?.changedFiles ?? 0;
        const repoDetected = snapshot.gitStatus?.repoDetected ?? false;
        return repoDetected && changed === 0 && aheadBy > 0 && behindBy === 0;
      }
      default:
        return false;
    }
  }

  private buildNudgeMessage(
    type: NudgeType,
    snapshot: WorkspaceSnapshot,
  ): { summary: string; detail: string } {
    const label = formatWorkspaceLabel(snapshot.workspace);
    const changed = snapshot.gitStatus?.changedFiles ?? 0;

    switch (type) {
      case 'uncommitted_files':
        return {
          summary: `${label}: ${changed} changed files`,
          detail: `You have ${changed} uncommitted changes in ${label}. Say "commit changes" to stage and commit.`,
        };
      case 'stale_branch':
        return {
          summary: `${label}: changes pending`,
          detail: `You've been working in ${label} with uncommitted changes. Consider committing to keep history clean.`,
        };
      case 'tests_failing': {
        const wf = snapshot.ciFailure?.workflowName ?? 'CI';
        return {
          summary: `${label}: ${wf} failing`,
          detail: `Tests have failed ${this.state.consecutiveTestFailures[snapshot.workspace.rootPath] ?? 0} times in a row in ${label}.`,
        };
      }
      case 'ci_red': {
        const wf = snapshot.ciFailure?.workflowName ?? 'CI';
        return {
          summary: `${label}: ${wf} is red`,
          detail: `The ${wf} workflow is failing in ${label}. Check details when you're ready.`,
        };
      }
      case 'ready_to_push':
        return {
          summary: `${label}: ready to push`,
          detail: `Your branch in ${label} is clean. Say "push changes" to push to remote.`,
        };
      default:
        return { summary: 'Workspace nudge', detail: '' };
    }
  }

  private enqueueNudge(nudge: {
    sessionId: string;
    type: NudgeType;
    summary: string;
    detail: string;
    workspaceLabel: string;
  }): void {
    this.outboxStore.enqueue({
      sessionId: nudge.sessionId,
      kind: 'workspace_nudge',
      priority: 'normal',
      summary: nudge.summary,
      detail: nudge.detail,
      expiresAtMs: Date.now() + 300000,
    });
  }
}

const DEFAULT_THRESHOLDS: Array<{
  type: NudgeType;
  changedFilesMin: number;
  staleBranchHours: number;
  consecutiveTestFailures: number;
  ciRedHours: number;
}> = [
  { type: 'uncommitted_files', changedFilesMin: 10, staleBranchHours: 24, consecutiveTestFailures: 2, ciRedHours: 1 },
  { type: 'stale_branch', changedFilesMin: 1, staleBranchHours: 24, consecutiveTestFailures: 2, ciRedHours: 1 },
  { type: 'tests_failing', changedFilesMin: 1, staleBranchHours: 24, consecutiveTestFailures: 2, ciRedHours: 1 },
  { type: 'ci_red', changedFilesMin: 1, staleBranchHours: 24, consecutiveTestFailures: 2, ciRedHours: 1 },
  { type: 'ready_to_push', changedFilesMin: 0, staleBranchHours: 24, consecutiveTestFailures: 2, ciRedHours: 1 },
];
