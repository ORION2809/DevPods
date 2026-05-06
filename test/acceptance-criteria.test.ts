import { execFileSync } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createBridgeRuntime } from '../src/bridge/runtime';
import type { Notifier } from '../src/bridge/speaker';
import type { JarvisResponse, WorkspaceRegistry } from '../src/protocol/schemas';

class MemoryNotifier implements Notifier {
  readonly messages: JarvisResponse[] = [];

  async notify(response: JarvisResponse): Promise<void> {
    this.messages.push(response);
  }
}

async function startMockOpenClawGateway(options: {
  assistantContent: string;
}): Promise<{
  url: string;
  requestCount: () => number;
  close: () => Promise<void>;
}> {
  let requestCount = 0;
  const server = http.createServer(async (request, response) => {
    if (request.method !== 'POST' || request.url !== '/v1/chat/completions') {
      response.writeHead(404, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ error: 'Not found' }));
      return;
    }

    for await (const _chunk of request) {
      // Drain request body.
    }

    requestCount += 1;
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(
      JSON.stringify({
        id: 'chatcmpl_acceptance_1',
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model: 'openclaw/default',
        choices: [
          {
            index: 0,
            finish_reason: 'stop',
            message: {
              role: 'assistant',
              content: options.assistantContent,
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
    requestCount: () => requestCount,
    close: () => new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve()))),
  };
}

describe('acceptance criteria', () => {
  let repoDir: string;
  let auditLogPath: string;

  beforeEach(() => {
    repoDir = fs.mkdtempSync(path.join(os.tmpdir(), 'jarvis-earbuds-acceptance-'));
    auditLogPath = path.join(repoDir, 'audit.log');
    fs.writeFileSync(path.join(repoDir, 'src_feature.ts'), 'export const value = 1;\n', 'utf8');
    execFileSync('git', ['init'], { cwd: repoDir, stdio: 'ignore' });
    execFileSync('git', ['config', 'user.email', 'jarvis@example.com'], { cwd: repoDir, stdio: 'ignore' });
    execFileSync('git', ['config', 'user.name', 'Jarvis'], { cwd: repoDir, stdio: 'ignore' });
    execFileSync('git', ['add', 'src_feature.ts'], { cwd: repoDir, stdio: 'ignore' });
    execFileSync('git', ['commit', '-m', 'chore: baseline'], { cwd: repoDir, stdio: 'ignore' });
    fs.writeFileSync(path.join(repoDir, 'src_feature.ts'), 'export const value = 2;\n', 'utf8');
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

  it('wakes Jarvis on a fake triple tap without an utterance', async () => {
    const runtime = createBridgeRuntime({
      registry: {
        defaultWorkspaceId: 'current_repo',
        workspaces: [
          {
            id: 'current_repo',
            label: 'temp_repo',
            rootPath: repoDir,
            allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure'],
            approvalRequiredIntents: [],
            hardApprovalIntents: [],
            commands: {},
          },
        ],
      },
      auditLogPath,
      notifier: new MemoryNotifier(),
    });

    const response = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_wake',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'triple_tap_right',
      timestamp: Date.now(),
    });

    expect(response.status).toBe('acknowledged');
    expect(response.nextState).toBe('listening');
    expect(response.speak).toBe('Jarvis active. What should I check?');
  });

  it('routes a voice or text command to the intended repo summary flow', async () => {
    const runtime = createBridgeRuntime({
      registry: {
        defaultWorkspaceId: 'current_repo',
        workspaces: [
          {
            id: 'current_repo',
            label: 'temp_repo',
            rootPath: repoDir,
            allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure'],
            approvalRequiredIntents: [],
            hardApprovalIntents: [],
            commands: {},
          },
        ],
      },
      auditLogPath,
      notifier: new MemoryNotifier(),
    });

    const response = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_route',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'triple_tap_right',
      timestamp: Date.now(),
      utterance: 'summarize my current diff',
    });

    expect(response.status).toBe('completed');
    expect(response.display).toContain('changed files');
    expect(response.speak.split(' ').length).toBeLessThanOrEqual(24);
  });

  it('supports approval prompts and cancel gestures before execution starts', async () => {
    const markerPath = path.join(repoDir, 'ran-tests.txt');
    fs.writeFileSync(
      path.join(repoDir, 'fake-task.js'),
      `require('node:fs').writeFileSync(${JSON.stringify(markerPath)}, 'ran', 'utf8'); process.exit(0);\n`,
      'utf8',
    );
    const notifier = new MemoryNotifier();
    const runtime = createBridgeRuntime({
      registry: {
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
                args: [path.join(repoDir, 'fake-task.js')],
                timeoutMs: 2000,
              },
            },
          },
        ],
      },
      auditLogPath,
      notifier,
    });

    const approvalPrompt = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_cancel',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'triple_tap_right',
      timestamp: Date.now(),
      utterance: 'run the tests',
    });

    expect(approvalPrompt.requiresApproval).toBe(true);
    expect(approvalPrompt.status).toBe('blocked');

    const cancelResponse = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_cancel',
      workspace: 'current_repo',
      device: 'both_buds',
      event: 'both_hold_cancel',
      timestamp: Date.now(),
    });

    expect(cancelResponse.status).toBe('cancelled');
    expect(cancelResponse.speak).toBe('Command cancelled.');

    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(fs.existsSync(markerPath)).toBe(false);
    expect(notifier.messages.find((message) => message.actionId === approvalPrompt.actionId && message.status === 'completed')).toBeUndefined();
  });

  it('blocks dangerous repo actions when policy does not allow them', async () => {
    const runtime = createBridgeRuntime({
      registry: {
        defaultWorkspaceId: 'current_repo',
        workspaces: [
          {
            id: 'current_repo',
            label: 'temp_repo',
            rootPath: repoDir,
            allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure'],
            approvalRequiredIntents: [],
            hardApprovalIntents: [],
            commands: {},
          },
        ],
      },
      auditLogPath,
      notifier: new MemoryNotifier(),
    });

    const response = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_blocked_delete',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'triple_tap_right',
      timestamp: Date.now(),
      utterance: 'delete file "src_feature.ts"',
    });

    expect(response.status).toBe('blocked');
    expect(response.speak).toBe('That action is blocked in this workspace.');
  });

  it('keeps OpenClaw output short enough for ear-based UX', async () => {
    const gateway = await startMockOpenClawGateway({
      assistantContent: JSON.stringify({
        speak: 'OpenClaw generated a very long response that should be trimmed before it reaches the ear because the spoken channel must stay compact, fast, and easy to follow while the user is coding.',
        display: 'Long display text is acceptable.',
        followUpHint: 'acceptance-openclaw',
      }),
    });

    try {
      const runtime = createBridgeRuntime({
        registry: {
          defaultWorkspaceId: 'current_repo',
          workspaces: [
            {
              id: 'current_repo',
              label: 'temp_repo',
              rootPath: repoDir,
              allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure'],
              approvalRequiredIntents: [],
              hardApprovalIntents: [],
              commands: {},
            },
          ],
        },
        auditLogPath,
        notifier: new MemoryNotifier(),
        brainMode: 'openclaw',
        openclaw: {
          transport: 'http',
          baseUrl: gateway.url,
          rewritePolicy: 'always',
        },
      });

      const response = await runtime.handleEvent({
        source: 'developer_earbuds_simulator',
        sessionId: 'sess_openclaw_trim',
        workspace: 'current_repo',
        device: 'left_bud',
        event: 'left_long_press',
        timestamp: Date.now(),
      });

      expect(response.status).toBe('completed');
      expect(response.speak.split(' ').length).toBeLessThanOrEqual(24);
      expect(response.followUpHint).toBe('acceptance-openclaw');
      expect(gateway.requestCount()).toBe(1);
    } finally {
      await gateway.close();
    }
  });
});