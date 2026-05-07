import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { JarvisResponse, WorkspaceRegistry } from '../src/protocol/schemas';
import type { Notifier } from '../src/bridge/speaker';
import { createBridgeRuntime } from '../src/bridge/runtime';

class MemoryNotifier implements Notifier {
  readonly messages: JarvisResponse[] = [];

  async notify(response: JarvisResponse): Promise<void> {
    this.messages.push(response);
  }
}

async function waitForMessage(
  notifier: MemoryNotifier,
  predicate: (message: JarvisResponse) => boolean,
  timeoutMs = 4000,
): Promise<JarvisResponse | undefined> {
  const deadline = Date.now() + timeoutMs;

  while (Date.now() < deadline) {
    const match = notifier.messages.find(predicate);
    if (match) {
      return match;
    }

    await new Promise((resolve) => setTimeout(resolve, 25));
  }

  return notifier.messages.find(predicate);
}

describe('bridge autonomy workflow', () => {
  let repoDir: string;
  let auditLogPath: string;

  beforeEach(() => {
    repoDir = fs.mkdtempSync(path.join(os.tmpdir(), 'jarvis-earbuds-autonomy-'));
    auditLogPath = path.join(repoDir, 'audit.log');
    fs.writeFileSync(path.join(repoDir, 'fast-task.js'), 'process.exit(0);\n', 'utf8');
    fs.writeFileSync(path.join(repoDir, 'slow-task.js'), 'setTimeout(function () { process.exit(0); }, 10000);\n', 'utf8');
  });

  afterEach(() => {
    try {
      fs.rmSync(repoDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 50 });
    } catch (error) {
      if (!(error instanceof Error) || !error.message.includes('EPERM')) {
        throw error;
      }
    }
  });

  it('turns an interrupt utterance into a continue-on-silence plan and executes it', async () => {
    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'current_repo',
      workspaces: [
        {
          id: 'current_repo',
          label: 'temp_repo',
          rootPath: repoDir,
          allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure', 'run_tests'],
          approvalRequiredIntents: ['run_tests'],
          hardApprovalIntents: [],
          commands: {},
        },
      ],
    };

    const runtime = createBridgeRuntime({
      registry,
      auditLogPath,
      notifier: new MemoryNotifier(),
    });

    const planned = await runtime.handleEvent({
      source: 'android_relay',
      sessionId: 'sess_plan',
      workspace: 'current_repo',
      device: 'both_buds',
      event: 'android_autonomy_interrupt',
      timestamp: Date.now(),
      utterance: 'what branch am i on',
    });

    expect(planned.status).toBe('acknowledged');
    expect(planned.autonomy?.phase).toBe('plan');
    expect(planned.autonomy?.nextIntent).toBe('quick_status');

    const continued = await runtime.handleEvent({
      source: 'android_relay',
      sessionId: 'sess_plan',
      workspace: 'current_repo',
      device: 'both_buds',
      event: 'android_autonomy_continue',
      timestamp: Date.now(),
    });

    expect(continued.status).toBe('completed');
    expect(continued.speak).toContain('Workspace ready');
  });

  it('adds autonomy instructions to successful background completion notifications', async () => {
    const notifier = new MemoryNotifier();
    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'current_repo',
      workspaces: [
        {
          id: 'current_repo',
          label: 'temp_repo',
          rootPath: repoDir,
          allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure', 'run_tests'],
          approvalRequiredIntents: ['run_tests'],
          hardApprovalIntents: [],
          commands: {
            run_tests: {
              description: 'Run workspace tests',
              command: 'node',
              args: [path.join(repoDir, 'fast-task.js')],
              timeoutMs: 2000,
            },
          },
        },
      ],
    };

    const runtime = createBridgeRuntime({
      registry,
      auditLogPath,
      notifier,
    });

    const approvalPrompt = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_background_autonomy',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'triple_tap_right',
      timestamp: Date.now(),
      utterance: 'run the tests',
    });

    await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_background_autonomy',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'approve_right_double_tap',
      timestamp: Date.now(),
      pendingActionId: approvalPrompt.actionId ?? undefined,
    });

    const completion = await waitForMessage(
      notifier,
      (message) => message.status === 'completed' && message.actionId === approvalPrompt.actionId,
    );

    expect(completion?.autonomy?.phase).toBe('report');
    expect(completion?.autonomy?.nextIntent).toBe('quick_status');
    expect(completion?.autonomy?.continueAfterMs).toBeGreaterThan(0);
  });

  it('cancels a running background task instead of reporting it as unstoppable', async () => {
    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'current_repo',
      workspaces: [
        {
          id: 'current_repo',
          label: 'temp_repo',
          rootPath: repoDir,
          allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure', 'run_tests'],
          approvalRequiredIntents: ['run_tests'],
          hardApprovalIntents: [],
          commands: {
            run_tests: {
              description: 'Run workspace tests',
              command: 'node',
              args: [path.join(repoDir, 'slow-task.js')],
              timeoutMs: 30000,
            },
          },
        },
      ],
    };

    const runtime = createBridgeRuntime({
      registry,
      auditLogPath,
      notifier: new MemoryNotifier(),
    });

    const approvalPrompt = await runtime.handleEvent({
      source: 'android_relay',
      sessionId: 'sess_cancel_running',
      workspace: 'current_repo',
      device: 'both_buds',
      event: 'android_push_to_talk',
      timestamp: Date.now(),
      utterance: 'run the tests',
    });
    const started = await runtime.handleEvent({
      source: 'android_relay',
      sessionId: 'sess_cancel_running',
      workspace: 'current_repo',
      device: 'both_buds',
      event: 'android_approve',
      timestamp: Date.now(),
      pendingActionId: approvalPrompt.actionId ?? undefined,
    });

    expect(started.status).toBe('running');

    const cancelled = await runtime.handleEvent({
      source: 'android_relay',
      sessionId: 'sess_cancel_running',
      workspace: 'current_repo',
      device: 'both_buds',
      event: 'android_cancel',
      timestamp: Date.now(),
      pendingActionId: approvalPrompt.actionId ?? undefined,
    });

    expect(cancelled.status).toBe('cancelled');
    expect(cancelled.speak).toContain('paused');
  });
});