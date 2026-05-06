import http from 'node:http';
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

async function startDelayedOpenClawGateway(delayMs: number): Promise<{
  url: string;
  close: () => Promise<void>;
}> {
  const server = http.createServer(async (request, response) => {
    if (request.method !== 'POST' || request.url !== '/v1/chat/completions') {
      response.writeHead(404, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ error: 'Not found' }));
      return;
    }

    for await (const _chunk of request) {
      // Drain request body.
    }

    await new Promise((resolve) => setTimeout(resolve, delayMs));
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(
      JSON.stringify({
        id: 'chatcmpl_notify_1',
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model: 'openclaw/default',
        choices: [
          {
            index: 0,
            finish_reason: 'stop',
            message: {
              role: 'assistant',
              content: JSON.stringify({
                speak: 'OpenClaw rewrote the notification.',
                display: 'OpenClaw rewrote the local notification.',
                followUpHint: 'slow-gateway',
              }),
            },
          },
        ],
      }),
    );
  });

  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  if (!address || typeof address === 'string') {
    throw new Error('Gateway address was not available.');
  }

  return {
    url: `http://127.0.0.1:${address.port}`,
    close: () => new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve()))),
  };
}

async function waitForMessage(
  notifier: MemoryNotifier,
  predicate: (message: JarvisResponse) => boolean,
  timeoutMs = 2000,
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

describe('bridge runtime notifications', () => {
  let repoDir: string;
  let auditLogPath: string;

  beforeEach(() => {
    repoDir = fs.mkdtempSync(path.join(os.tmpdir(), 'jarvis-earbuds-runtime-'));
    auditLogPath = path.join(repoDir, 'audit.log');
    fs.writeFileSync(path.join(repoDir, 'fake-task.js'), 'setTimeout(function () { process.exit(0); }, 25);\n', 'utf8');
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

  it('emits a completion notification after approved background work finishes', async () => {
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
          hardApprovalIntents: ['deploy'],
          commands: {
            run_tests: {
              description: 'Run workspace tests',
              command: 'node',
              args: [path.join(repoDir, 'fake-task.js')],
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
      sessionId: 'sess_notify',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'triple_tap_right',
      timestamp: Date.now(),
      utterance: 'run the tests',
    });

    expect(approvalPrompt.requiresApproval).toBe(true);

    await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_notify',
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
    expect(completion).toBeTruthy();
    expect(completion?.speak).toContain('Tests finished successfully');
    expect(fs.readFileSync(auditLogPath, 'utf8')).toContain('run_tests');
  });

  it('emits a deployment completion notification after hard-approved deploy work finishes', async () => {
    const notifier = new MemoryNotifier();
    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'current_repo',
      workspaces: [
        {
          id: 'current_repo',
          label: 'temp_repo',
          rootPath: repoDir,
          allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure', 'deploy'],
          approvalRequiredIntents: [],
          hardApprovalIntents: ['deploy'],
          commands: {
            deploy: {
              description: 'Deploy current build',
              command: 'node',
              args: [path.join(repoDir, 'fake-task.js')],
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
      sessionId: 'sess_deploy_notify',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'triple_tap_right',
      timestamp: Date.now(),
      utterance: 'deploy the build',
    });

    expect(approvalPrompt.requiresApproval).toBe(true);
    expect(approvalPrompt.approvalRequest?.riskClass).toBe('hard_approval');

    const startResponse = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_deploy_notify',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'approve_right_double_tap',
      timestamp: Date.now(),
      pendingActionId: approvalPrompt.actionId ?? undefined,
    });

    expect(startResponse.status).toBe('running');
    expect(startResponse.speak).toContain('Deployment started');

    const completion = await waitForMessage(
      notifier,
      (message) => message.status === 'completed' && message.actionId === approvalPrompt.actionId,
    );
    expect(completion).toBeTruthy();
    expect(completion?.speak).toContain('Deployment finished successfully');
    expect(fs.readFileSync(auditLogPath, 'utf8')).toContain('deploy');
  });

  it('suppresses desktop background notifications for android relay sessions', async () => {
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
          hardApprovalIntents: ['deploy'],
          commands: {
            run_tests: {
              description: 'Run workspace tests',
              command: 'node',
              args: [path.join(repoDir, 'fake-task.js')],
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
      source: 'android_relay',
      sessionId: 'android_background_notify',
      workspace: 'current_repo',
      device: 'both_buds',
      event: 'android_push_to_talk',
      timestamp: Date.now(),
      utterance: 'run the tests',
    });

    expect(approvalPrompt.requiresApproval).toBe(true);

    const startResponse = await runtime.handleEvent({
      source: 'android_relay',
      sessionId: 'android_background_notify',
      workspace: 'current_repo',
      device: 'both_buds',
      event: 'android_approve',
      timestamp: Date.now(),
      pendingActionId: approvalPrompt.actionId ?? undefined,
    });

    expect(startResponse.status).toBe('running');

    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(notifier.messages).toHaveLength(0);
  });

  it('queues overlapping background work in the same workspace and starts it after the active task finishes', async () => {
    fs.writeFileSync(path.join(repoDir, 'slow-task.js'), 'setTimeout(function () { process.exit(0); }, 150);\n', 'utf8');
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
          hardApprovalIntents: ['deploy'],
          commands: {
            run_tests: {
              description: 'Run workspace tests',
              command: 'node',
              args: [path.join(repoDir, 'slow-task.js')],
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

    const firstApproval = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_queue_one',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'triple_tap_right',
      timestamp: Date.now(),
      utterance: 'run the tests',
    });
    const firstStart = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_queue_one',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'approve_right_double_tap',
      timestamp: Date.now(),
      pendingActionId: firstApproval.actionId ?? undefined,
    });

    expect(firstStart.status).toBe('running');

    const secondApproval = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_queue_two',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'triple_tap_right',
      timestamp: Date.now(),
      utterance: 'run the tests',
    });
    const secondStart = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_queue_two',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'approve_right_double_tap',
      timestamp: Date.now(),
      pendingActionId: secondApproval.actionId ?? undefined,
    });

    expect(secondStart.status).toBe('acknowledged');
    expect(secondStart.nextState).toBe('queued');
    expect(secondStart.speak).toContain('Queued');

    const firstCompletion = await waitForMessage(
      notifier,
      (message) => message.status === 'completed' && message.actionId === firstApproval.actionId,
      4000,
    );
    const secondRunning = await waitForMessage(
      notifier,
      (message) => message.status === 'running' && message.actionId === secondApproval.actionId,
      4000,
    );
    const secondCompletion = await waitForMessage(
      notifier,
      (message) => message.status === 'completed' && message.actionId === secondApproval.actionId,
      4000,
    );

    expect(firstCompletion).toBeTruthy();
    expect(secondRunning).toBeTruthy();
    expect(secondRunning?.speak).toContain('Running tests');
    expect(secondCompletion).toBeTruthy();

    const firstCompletionIndex = notifier.messages.findIndex(
      (message) => message.status === 'completed' && message.actionId === firstApproval.actionId,
    );
    const secondRunningIndex = notifier.messages.findIndex(
      (message) => message.status === 'running' && message.actionId === secondApproval.actionId,
    );

    expect(firstCompletionIndex).toBeGreaterThanOrEqual(0);
    expect(secondRunningIndex).toBeGreaterThan(firstCompletionIndex);

    const auditLog = fs.readFileSync(auditLogPath, 'utf8');
    expect(auditLog).toContain('"decision":"queued"');
    expect(auditLog).toContain('"decision":"running"');
  });

  it('cancels queued background work before it starts', async () => {
    fs.writeFileSync(path.join(repoDir, 'slow-task.js'), 'setTimeout(function () { process.exit(0); }, 150);\n', 'utf8');
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
          hardApprovalIntents: ['deploy'],
          commands: {
            run_tests: {
              description: 'Run workspace tests',
              command: 'node',
              args: [path.join(repoDir, 'slow-task.js')],
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

    const firstApproval = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_cancel_first',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'triple_tap_right',
      timestamp: Date.now(),
      utterance: 'run the tests',
    });
    await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_cancel_first',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'approve_right_double_tap',
      timestamp: Date.now(),
      pendingActionId: firstApproval.actionId ?? undefined,
    });

    const secondApproval = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_cancel_second',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'triple_tap_right',
      timestamp: Date.now(),
      utterance: 'run the tests',
    });
    const queuedResponse = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_cancel_second',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'approve_right_double_tap',
      timestamp: Date.now(),
      pendingActionId: secondApproval.actionId ?? undefined,
    });

    expect(queuedResponse.nextState).toBe('queued');

    const cancelResponse = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_cancel_second',
      workspace: 'current_repo',
      device: 'both_buds',
      event: 'both_hold_cancel',
      timestamp: Date.now(),
    });

    expect(cancelResponse.status).toBe('cancelled');
    expect(cancelResponse.display).toContain('queued background task was cancelled');

    const firstCompletion = await waitForMessage(
      notifier,
      (message) => message.status === 'completed' && message.actionId === firstApproval.actionId,
      4000,
    );
    expect(firstCompletion).toBeTruthy();

    await new Promise((resolve) => setTimeout(resolve, 300));
    expect(
      notifier.messages.find(
        (message) => message.actionId === secondApproval.actionId && (message.status === 'running' || message.status === 'completed'),
      ),
    ).toBeUndefined();
  });

  it('does not delay background completion notifications when OpenClaw rewrite exceeds the background budget', async () => {
    const gateway = await startDelayedOpenClawGateway(1000);
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
          hardApprovalIntents: ['deploy'],
          commands: {
            run_tests: {
              description: 'Run workspace tests',
              command: 'node',
              args: [path.join(repoDir, 'fake-task.js')],
              timeoutMs: 2000,
            },
          },
        },
      ],
    };

    try {
      const runtime = createBridgeRuntime({
        registry,
        auditLogPath,
        notifier,
        brainMode: 'openclaw',
        openclaw: {
          transport: 'http',
          baseUrl: gateway.url,
          rewritePolicy: 'always',
          foregroundBudgetMs: 60,
          backgroundBudgetMs: 60,
        },
      });

      const approvalPrompt = await runtime.handleEvent({
        source: 'developer_earbuds_simulator',
        sessionId: 'sess_slow_background_rewrite',
        workspace: 'current_repo',
        device: 'right_bud',
        event: 'triple_tap_right',
        timestamp: Date.now(),
        utterance: 'run the tests',
      });

      const startedAt = Date.now();
      const startResponse = await runtime.handleEvent({
        source: 'developer_earbuds_simulator',
        sessionId: 'sess_slow_background_rewrite',
        workspace: 'current_repo',
        device: 'right_bud',
        event: 'approve_right_double_tap',
        timestamp: Date.now(),
        pendingActionId: approvalPrompt.actionId ?? undefined,
      });

      const completion = await waitForMessage(
        notifier,
        (message) => message.status === 'completed' && message.actionId === approvalPrompt.actionId,
        2000,
      );
      const elapsedMs = Date.now() - startedAt;

      expect(startResponse.status).toBe('running');
      expect(completion).toBeTruthy();
      expect(completion?.speak).toContain('Tests finished successfully');
      expect(elapsedMs).toBeLessThan(900);
      expect(runtime.getOpenClawHealthSnapshot()).toMatchObject({
        backgroundBudgetMs: 60,
      });
      expect(runtime.getOpenClawHealthSnapshot()?.counters.timedOut).toBeGreaterThanOrEqual(1);
    } finally {
      await gateway.close();
    }
  });
});