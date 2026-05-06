import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { Notifier } from '../src/bridge/speaker';
import { createBridgeRuntime } from '../src/bridge/runtime';
import { OpenClawGatewayClient } from '../src/openclaw/client';
import type { WorkspaceRegistry } from '../src/protocol/schemas';
import { openClawSandboxAgentId, startOpenClawSandbox } from '../src/openclaw/sandbox';
import { startMockOpenAiProvider } from '../simulation/openclaw-sandbox/mock-provider';

class MemoryNotifier implements Notifier {
  async notify(): Promise<void> {
    return;
  }
}

interface RecordedGatewayRequest {
  authorization: string | undefined;
  body: unknown;
}

async function startMockOpenClawGateway(options: {
  token?: string;
  assistantContent?: string;
  delayMs?: number;
} = {}): Promise<{
  url: string;
  requests: RecordedGatewayRequest[];
  close: () => Promise<void>;
}> {
  const requests: RecordedGatewayRequest[] = [];
  const server = http.createServer(async (request, response) => {
    if (request.method !== 'POST' || request.url !== '/v1/chat/completions') {
      response.writeHead(404, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ error: 'Not found' }));
      return;
    }

    const chunks: string[] = [];
    for await (const chunk of request) {
      chunks.push(typeof chunk === 'string' ? chunk : chunk.toString('utf8'));
    }

    requests.push({
      authorization: request.headers.authorization,
      body: JSON.parse(chunks.join('')),
    });

    if (options.token && request.headers.authorization !== `Bearer ${options.token}`) {
      response.writeHead(401, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ error: 'Unauthorized' }));
      return;
    }

    if ((options.delayMs ?? 0) > 0) {
      await new Promise((resolve) => setTimeout(resolve, options.delayMs));
    }

    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(
      JSON.stringify({
        id: 'chatcmpl_mock_1',
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model: 'openclaw/default',
        choices: [
          {
            index: 0,
            finish_reason: 'stop',
            message: {
              role: 'assistant',
              content:
                options.assistantContent
                ?? JSON.stringify({
                  speak: 'OpenClaw rewrote the reply.',
                  display: 'OpenClaw rewrote the local response.',
                  followUpHint: 'openclaw',
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
    requests,
    close: () => new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve()))),
  };
}

describe('openclaw mode', () => {
  let repoDir: string;
  let auditLogPath: string;

  beforeEach(() => {
    repoDir = fs.mkdtempSync(path.join(os.tmpdir(), 'jarvis-earbuds-openclaw-'));
    auditLogPath = path.join(repoDir, 'audit.log');
    fs.writeFileSync(path.join(repoDir, 'fake-task.js'), 'process.exit(0);\n', 'utf8');
  });

  afterEach(() => {
    try {
      fs.rmSync(repoDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 50 });
    } catch (error) {
      const code = error instanceof Error ? (error as NodeJS.ErrnoException).code : undefined;
      if (code !== 'EPERM' && code !== 'ENOTEMPTY' && code !== 'EBUSY') {
        throw error;
      }
    }
  });

  it('rewrites completed quick status through the OpenClaw gateway', async () => {
    const gateway = await startMockOpenClawGateway({ token: 'sandbox-token' });
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

    try {
      const runtime = createBridgeRuntime({
        registry,
        auditLogPath,
        notifier: new MemoryNotifier(),
        brainMode: 'openclaw',
        openclaw: {
          baseUrl: gateway.url,
          token: 'sandbox-token',
          rewritePolicy: 'always',
        },
      });

      const response = await runtime.handleEvent({
        source: 'developer_earbuds_simulator',
        sessionId: 'sess_openclaw_status',
        workspace: 'current_repo',
        device: 'left_bud',
        event: 'left_long_press',
        timestamp: Date.now(),
      });

      expect(response.status).toBe('completed');
      expect(response.speak).toBe('OpenClaw rewrote the reply.');
      expect(response.display).toBe('OpenClaw rewrote the local response.');
      expect(response.followUpHint).toBe('openclaw');
      expect(gateway.requests).toHaveLength(1);
      expect(gateway.requests[0]?.authorization).toBe('Bearer sandbox-token');
      expect(gateway.requests[0]?.body).toMatchObject({
        model: 'openclaw/default',
      });
    } finally {
      await gateway.close();
    }
  });

  it('keeps approval prompts local and does not call OpenClaw before approval', async () => {
    const gateway = await startMockOpenClawGateway({ token: 'sandbox-token' });
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
        notifier: new MemoryNotifier(),
        brainMode: 'openclaw',
        openclaw: {
          baseUrl: gateway.url,
          token: 'sandbox-token',
          rewritePolicy: 'always',
        },
      });

      const prompt = await runtime.handleEvent({
        source: 'developer_earbuds_simulator',
        sessionId: 'sess_openclaw_approval',
        workspace: 'current_repo',
        device: 'right_bud',
        event: 'triple_tap_right',
        timestamp: Date.now(),
        utterance: 'run the tests',
      });

      expect(prompt.requiresApproval).toBe(true);
      expect(prompt.status).toBe('blocked');
      expect(prompt.nextState).toBe('approval_pending');
      expect(gateway.requests).toHaveLength(0);
    } finally {
      await gateway.close();
    }
  });

  it('rewrites completed quick status through the local OpenClaw CLI transport', async () => {
    const provider = await startMockOpenAiProvider();
    const sandbox = await startOpenClawSandbox({
      cleanupOnStop: true,
      provider: {
        providerId: 'openai',
        modelId: 'mock-rewrite-model',
        modelName: 'Mock Rewrite Model',
        baseUrl: provider.baseUrl,
        api: 'openai-completions',
        apiKey: 'mock-provider-key',
        reasoning: false,
      },
    });
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

    try {
      const runtime = createBridgeRuntime({
        registry,
        auditLogPath,
        notifier: new MemoryNotifier(),
        brainMode: 'openclaw',
        openclaw: {
          transport: 'local-cli',
          model: 'openai/mock-rewrite-model',
          configPath: sandbox.configPath,
          stateDir: sandbox.stateDir,
          workspaceDir: sandbox.workspaceDir,
          rewritePolicy: 'always',
          timeoutMs: 120_000,
        },
      });

      const response = await runtime.handleEvent({
        source: 'developer_earbuds_simulator',
        sessionId: 'sess_openclaw_local_cli',
        workspace: 'current_repo',
        device: 'left_bud',
        event: 'left_long_press',
        timestamp: Date.now(),
      });

      expect(response.status).toBe('completed');
      expect(response.speak).toBe('OpenClaw sandbox completed the response.');
      expect(response.display).toBe('The mock provider returned a deterministic sandbox response.');
      expect(response.followUpHint).toBe('mock-provider');
      expect(provider.requests).toHaveLength(1);
    } finally {
      await sandbox.stop();
      await provider.stop();
    }
  }, 180_000);

  it('rewrites completed quick status through the resident OpenClaw gateway-client transport', async () => {
    const provider = await startMockOpenAiProvider();
    const sandbox = await startOpenClawSandbox({
      cleanupOnStop: true,
      provider: {
        providerId: 'openai',
        modelId: 'mock-rewrite-model',
        modelName: 'Mock Rewrite Model',
        baseUrl: provider.baseUrl,
        api: 'openai-completions',
        apiKey: 'mock-provider-key',
        reasoning: false,
      },
    });
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

    try {
      const runtime = createBridgeRuntime({
        registry,
        auditLogPath,
        notifier: new MemoryNotifier(),
        brainMode: 'openclaw',
        openclaw: {
          transport: 'gateway-client',
          baseUrl: sandbox.baseUrl,
          token: sandbox.token,
          model: 'openai/mock-rewrite-model',
          agentId: openClawSandboxAgentId,
          rewritePolicy: 'always',
          timeoutMs: 240_000,
        },
      });

      const response = await runtime.handleEvent({
        source: 'developer_earbuds_simulator',
        sessionId: 'sess_openclaw_gateway_client',
        workspace: 'current_repo',
        device: 'left_bud',
        event: 'left_long_press',
        timestamp: Date.now(),
      });

      expect(response.status).toBe('completed');
      expect(response.speak).toBe('OpenClaw sandbox completed the response.');
      expect(response.display).toBe('The mock provider returned a deterministic sandbox response.');
      expect(response.followUpHint).toBe('mock-provider');
      expect(provider.requests).toHaveLength(1);
    } finally {
      await sandbox.stop();
      await provider.stop();
    }
  }, 300_000);

  it('falls back to the local response when foreground rewrite exceeds the latency budget', async () => {
    const gateway = await startMockOpenClawGateway({ token: 'sandbox-token', delayMs: 1000 });
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

    try {
      const runtime = createBridgeRuntime({
        registry,
        auditLogPath,
        notifier: new MemoryNotifier(),
        brainMode: 'openclaw',
        openclaw: {
          transport: 'http',
          baseUrl: gateway.url,
          token: 'sandbox-token',
          rewritePolicy: 'always',
          foregroundBudgetMs: 60,
        },
      });

      const startedAt = Date.now();
      const response = await runtime.handleEvent({
        source: 'developer_earbuds_simulator',
        sessionId: 'sess_openclaw_budgeted_foreground',
        workspace: 'current_repo',
        device: 'left_bud',
        event: 'left_long_press',
        timestamp: Date.now(),
      });
      const durationMs = Date.now() - startedAt;

      expect(response.status).toBe('completed');
      expect(response.speak).toBe('Workspace ready. Git repository not initialized. No tests running.');
      expect(durationMs).toBeLessThan(750);
      expect(runtime.getOpenClawHealthSnapshot()).toMatchObject({
        foregroundBudgetMs: 60,
        lastOutcome: {
          source: 'foreground',
          outcome: 'timed_out',
        },
      });
      expect(gateway.requests).toHaveLength(1);
    } finally {
      await gateway.close();
    }
  });

  it('uses adaptive rewriting only when the local response is verbose enough to benefit', async () => {
    const gateway = await startMockOpenClawGateway({ token: 'sandbox-token' });
    const shortClient = new OpenClawGatewayClient({
      transport: 'http',
      baseUrl: gateway.url,
      token: 'sandbox-token',
      rewritePolicy: 'adaptive',
    });
    const hintedShortClient = new OpenClawGatewayClient({
      transport: 'http',
      baseUrl: gateway.url,
      token: 'sandbox-token',
      rewritePolicy: 'adaptive',
    });
    const longClient = new OpenClawGatewayClient({
      transport: 'http',
      baseUrl: gateway.url,
      token: 'sandbox-token',
      rewritePolicy: 'adaptive',
    });

    try {
      const skipped = await shortClient.rewriteResponse({
        source: 'foreground',
        response: {
          speak: 'Workspace ready. Git repository not initialized. No tests running.',
          display: 'Short local response.',
          requiresApproval: false,
          approvalRequest: null,
          actionId: null,
          status: 'completed',
          nextState: 'idle',
          followUpHint: null,
        },
      });

      const hintedSkip = await hintedShortClient.rewriteResponse({
        source: 'foreground',
        response: {
          speak: 'Latest CI failure ready.',
          display: 'Short local response with a structured follow-up hint.',
          requiresApproval: false,
          approvalRequest: null,
          actionId: null,
          status: 'completed',
          nextState: 'idle',
          followUpHint: 'https://github.com/openclaw/openclaw/actions/runs/1',
        },
      });

      const rewritten = await longClient.rewriteResponse({
        source: 'foreground',
        response: {
          speak: 'This summary is intentionally too long for the ear channel and should be compressed while preserving the main follow up hint for the developer.',
          display: 'Long local response.',
          requiresApproval: false,
          approvalRequest: null,
          actionId: null,
          status: 'completed',
          nextState: 'idle',
          followUpHint: 'src/main.ts',
        },
      });

      expect(skipped.speak).toBe('Workspace ready. Git repository not initialized. No tests running.');
  expect(hintedSkip.speak).toBe('Latest CI failure ready.');
  expect(hintedSkip.followUpHint).toBe('https://github.com/openclaw/openclaw/actions/runs/1');
      expect(rewritten.speak).toBe('OpenClaw rewrote the reply.');
      expect(rewritten.followUpHint).toBe('openclaw');
      expect(gateway.requests).toHaveLength(1);
    } finally {
      await gateway.close();
    }
  });
});