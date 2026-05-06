import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createBridgeServer } from '../src/bridge/server';
import type { JarvisResponse, WorkspaceRegistry } from '../src/protocol/schemas';

class MemoryNotifier {
  readonly messages: JarvisResponse[] = [];

  async notify(response: JarvisResponse): Promise<void> {
    this.messages.push(response);
  }
}

describe('fake event to response', () => {
  let repoDir: string;
  let auditLogPath: string;

  beforeEach(() => {
    repoDir = fs.mkdtempSync(path.join(os.tmpdir(), 'jarvis-earbuds-repo-'));
    auditLogPath = path.join(repoDir, 'audit.log');
    fs.writeFileSync(path.join(repoDir, 'app.ts'), 'console.log("hello")\n', 'utf8');
    fs.writeFileSync(
      path.join(repoDir, 'fake-task.js'),
      'setTimeout(function () { process.exit(0); }, 25);\n',
      'utf8',
    );
    fs.mkdirSync(path.join(repoDir, '.git'));
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

  it('handles a quick-status gesture end to end', async () => {
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
          commands: {},
        },
      ],
    };

    const { server } = createBridgeServer({ registry, auditLogPath });
    await new Promise<void>((resolve) => server.listen(0, resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const response = await fetch(`http://127.0.0.1:${address.port}/events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        source: 'developer_earbuds_simulator',
        sessionId: 'sess_status',
        workspace: 'current_repo',
        device: 'left_bud',
        event: 'left_long_press',
        timestamp: Date.now(),
        wearState: 'in_ear',
        battery: 80,
        profile: 'coding_mode',
      }),
    });

    const payload = (await response.json()) as {
      speak: string;
      requiresApproval: boolean;
      status: string;
      nextState: string;
    };

    expect(response.ok).toBe(true);
    expect(payload.requiresApproval).toBe(false);
    expect(payload.status).toBe('completed');
    expect(payload.nextState).toBe('idle');
    expect(payload.speak.split(' ').length).toBeLessThanOrEqual(24);
    expect(fs.readFileSync(auditLogPath, 'utf8')).toContain('quick_status');

    await new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
  });

  it('handles an android relay quick-status shortcut without desktop notification', async () => {
    const notifier = new MemoryNotifier();
    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'current_repo',
      workspaces: [
        {
          id: 'current_repo',
          label: 'temp_repo',
          rootPath: repoDir,
          allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure', 'run_tests', 'open_file'],
          approvalRequiredIntents: ['run_tests', 'open_file'],
          hardApprovalIntents: ['deploy'],
          commands: {},
        },
      ],
    };

    const { server } = createBridgeServer({ registry, auditLogPath, notifier });
    await new Promise<void>((resolve) => server.listen(0, resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const response = await fetch(`http://127.0.0.1:${address.port}/events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        source: 'android_relay',
        sessionId: 'android_status',
        workspace: 'current_repo',
        device: 'both_buds',
        event: 'android_status_shortcut',
        timestamp: Date.now(),
        profile: 'default',
      }),
    });

    const payload = (await response.json()) as {
      speak: string;
      requiresApproval: boolean;
      status: string;
      nextState: string;
    };

    expect(response.ok).toBe(true);
    expect(payload.requiresApproval).toBe(false);
    expect(payload.status).toBe('completed');
    expect(payload.nextState).toBe('idle');
    expect(payload.speak.split(' ').length).toBeLessThanOrEqual(24);
    expect(notifier.messages).toHaveLength(0);

    await new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
  });

  it('handles android relay approval and cancel flows through the existing /events lifecycle', async () => {
    const notifier = new MemoryNotifier();
    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'current_repo',
      workspaces: [
        {
          id: 'current_repo',
          label: 'temp_repo',
          rootPath: repoDir,
          allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure', 'run_tests', 'open_file'],
          approvalRequiredIntents: ['run_tests', 'open_file'],
          hardApprovalIntents: ['deploy'],
          commands: {},
        },
      ],
    };

    const previousDisableOpen = process.env.JARVIS_DISABLE_OPEN;
    process.env.JARVIS_DISABLE_OPEN = '1';

    const { server } = createBridgeServer({ registry, auditLogPath, notifier });
    await new Promise<void>((resolve) => server.listen(0, resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    try {
      const promptResponse = await fetch(`http://127.0.0.1:${address.port}/events`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          source: 'android_relay',
          sessionId: 'android_open_file',
          workspace: 'current_repo',
          device: 'both_buds',
          event: 'android_push_to_talk',
          timestamp: Date.now(),
          utterance: 'open file app.ts',
        }),
      });

      const promptPayload = (await promptResponse.json()) as {
        requiresApproval: boolean;
        actionId: string;
        nextState: string;
      };

      expect(promptResponse.ok).toBe(true);
      expect(promptPayload.requiresApproval).toBe(true);
      expect(promptPayload.nextState).toBe('approval_pending');

      const cancelResponse = await fetch(`http://127.0.0.1:${address.port}/events`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          source: 'android_relay',
          sessionId: 'android_open_file',
          workspace: 'current_repo',
          device: 'both_buds',
          event: 'android_cancel',
          timestamp: Date.now(),
          pendingActionId: promptPayload.actionId,
        }),
      });

      const cancelPayload = (await cancelResponse.json()) as {
        status: string;
        nextState: string;
      };

      expect(cancelResponse.ok).toBe(true);
      expect(cancelPayload.status).toBe('cancelled');
      expect(cancelPayload.nextState).toBe('idle');

      const secondPromptResponse = await fetch(`http://127.0.0.1:${address.port}/events`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          source: 'android_relay',
          sessionId: 'android_open_file',
          workspace: 'current_repo',
          device: 'both_buds',
          event: 'android_push_to_talk',
          timestamp: Date.now(),
          utterance: 'open file app.ts',
        }),
      });

      const secondPromptPayload = (await secondPromptResponse.json()) as {
        requiresApproval: boolean;
        actionId: string;
      };

      expect(secondPromptResponse.ok).toBe(true);
      expect(secondPromptPayload.requiresApproval).toBe(true);

      const approvalResponse = await fetch(`http://127.0.0.1:${address.port}/events`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          source: 'android_relay',
          sessionId: 'android_open_file',
          workspace: 'current_repo',
          device: 'both_buds',
          event: 'android_approve',
          timestamp: Date.now(),
          pendingActionId: secondPromptPayload.actionId,
        }),
      });

      const approvalPayload = (await approvalResponse.json()) as {
        status: string;
        speak: string;
      };

      expect(approvalResponse.ok).toBe(true);
      expect(approvalPayload.status).toBe('completed');
      expect(approvalPayload.speak).toContain('Open file target ready');
      expect(notifier.messages).toHaveLength(0);
    } finally {
      await new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
      if (previousDisableOpen === undefined) {
        delete process.env.JARVIS_DISABLE_OPEN;
      } else {
        process.env.JARVIS_DISABLE_OPEN = previousDisableOpen;
      }
    }
  });

  it('requires a relay bearer token when the bridge is started with relay auth enabled', async () => {
    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'current_repo',
      workspaces: [
        {
          id: 'current_repo',
          label: 'temp_repo',
          rootPath: repoDir,
          allowedIntents: ['quick_status'],
          approvalRequiredIntents: [],
          hardApprovalIntents: [],
          commands: {},
        },
      ],
    };

    const { server } = createBridgeServer({
      registry,
      auditLogPath,
      relayToken: 'relay-secret',
    });
    await new Promise<void>((resolve) => server.listen(0, resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    try {
      const unauthorizedResponse = await fetch(`http://127.0.0.1:${address.port}/events`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          source: 'android_relay',
          sessionId: 'android_auth',
          workspace: 'current_repo',
          device: 'both_buds',
          event: 'android_status_shortcut',
          timestamp: Date.now(),
        }),
      });

      expect(unauthorizedResponse.status).toBe(401);

      const authorizedResponse = await fetch(`http://127.0.0.1:${address.port}/events`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: 'Bearer relay-secret',
        },
        body: JSON.stringify({
          source: 'android_relay',
          sessionId: 'android_auth',
          workspace: 'current_repo',
          device: 'both_buds',
          event: 'android_status_shortcut',
          timestamp: Date.now(),
        }),
      });

      expect(authorizedResponse.status).toBe(200);
    } finally {
      await new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
    }
  });

  it('requires approval for run-tests and starts after approval', async () => {
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

    const { server } = createBridgeServer({ registry, auditLogPath });
    await new Promise<void>((resolve) => server.listen(0, resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const approvalPrompt = await fetch(`http://127.0.0.1:${address.port}/events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        source: 'developer_earbuds_simulator',
        sessionId: 'sess_approval',
        workspace: 'current_repo',
        device: 'right_bud',
        event: 'triple_tap_right',
        timestamp: Date.now(),
        utterance: 'run the tests',
      }),
    });

    const promptPayload = (await approvalPrompt.json()) as { requiresApproval: boolean; actionId: string; nextState: string };

    expect(promptPayload.requiresApproval).toBe(true);
    expect(promptPayload.nextState).toBe('approval_pending');

    const approvalResponse = await fetch(`http://127.0.0.1:${address.port}/events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        source: 'developer_earbuds_simulator',
        sessionId: 'sess_approval',
        workspace: 'current_repo',
        device: 'right_bud',
        event: 'approve_right_double_tap',
        timestamp: Date.now(),
        pendingActionId: promptPayload.actionId,
      }),
    });

    const approvalPayload = (await approvalResponse.json()) as { status: string; nextState: string; actionId: string };

    expect(approvalPayload.status).toBe('running');
    expect(approvalPayload.nextState).toBe('running');
    expect(approvalPayload.actionId).toBe(promptPayload.actionId);

    await new Promise((resolve) => setTimeout(resolve, 75));

    await new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
  });
});