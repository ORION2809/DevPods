import { execFile } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createBridgeServer } from '../src/bridge/server';
import type { WorkspaceRegistry } from '../src/protocol/schemas';

interface RecordedGatewayRequest {
  authorization: string | undefined;
  body: unknown;
}

async function startMockOpenClawGateway(options: {
  token?: string;
  assistantContent?: string;
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

    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(
      JSON.stringify({
        id: 'chatcmpl_mock_cli_1',
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
                  speak: 'OpenClaw rewrote the CLI reply.',
                  display: 'OpenClaw rewrote the local CLI response.',
                  followUpHint: 'cli-openclaw',
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

function runCli(args: string[], env: NodeJS.ProcessEnv = {}): Promise<string> {
  const tsxCliPath = path.resolve(process.cwd(), 'node_modules', 'tsx', 'dist', 'cli.mjs');

  return new Promise((resolve, reject) => {
    execFile(
      process.execPath,
      [tsxCliPath, 'src/cli/jarvis-earbuds.ts', ...args],
      {
        cwd: process.cwd(),
        env: {
          ...process.env,
          ...env,
        },
        windowsHide: true,
        timeout: 60_000,
        maxBuffer: 1024 * 1024,
      },
      (error, stdout, stderr) => {
        if (error) {
          reject(Object.assign(error, { stdout, stderr }));
          return;
        }

        resolve(stdout);
      },
    );
  });
}

async function readHealthPayload(port: number): Promise<any> {
  const response = await fetch(`http://127.0.0.1:${port}/health`);
  expect(response.status).toBe(200);
  return response.json();
}

async function waitForGatewayHealthState(port: number, expectedState: string): Promise<any> {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const payload = await readHealthPayload(port);
    if (payload.openclawRewriteHealth.connectionState === expectedState) {
      return payload;
    }

    await new Promise((resolve) => setTimeout(resolve, 25));
  }

  throw new Error(`OpenClaw gateway health never reached ${expectedState}.`);
}

describe('jarvis-earbuds CLI OpenClaw mode', () => {
  let repoDir: string;
  let workspaceConfigDir: string;
  let workspaceConfigPath: string;

  beforeEach(() => {
    repoDir = fs.mkdtempSync(path.join(os.tmpdir(), 'jarvis-earbuds-cli-repo-'));
    workspaceConfigDir = fs.mkdtempSync(path.join(process.cwd(), 'runtime-data', 'jarvis-cli-config-'));
    workspaceConfigPath = path.join(workspaceConfigDir, 'workspaces.json');
    fs.writeFileSync(
      workspaceConfigPath,
      `${JSON.stringify({
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
      }, null, 2)}\n`,
      'utf8',
    );
  });

  afterEach(() => {
    fs.rmSync(repoDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 50 });
    fs.rmSync(workspaceConfigDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 50 });
  });

  it('routes the local CLI command through OpenClaw HTTP rewrite mode', async () => {
    const gateway = await startMockOpenClawGateway({ token: 'sandbox-token' });

    try {
      const stdout = await runCli(
        [
          'local',
          'left_long_press',
          '--brain',
          'openclaw',
          '--workspaces-config',
          workspaceConfigPath,
          '--openclaw-transport',
          'http',
          '--openclaw-base-url',
          gateway.url,
          '--openclaw-token',
          'sandbox-token',
          '--openclaw-rewrite-policy',
          'always',
        ],
        {
          JARVIS_DISABLE_TTS: '1',
        },
      );

      const response = JSON.parse(stdout);
      expect(response).toMatchObject({
        status: 'completed',
        speak: 'OpenClaw rewrote the CLI reply.',
        display: 'OpenClaw rewrote the local CLI response.',
        followUpHint: 'cli-openclaw',
      });
      expect(gateway.requests).toHaveLength(1);
      expect(gateway.requests[0]?.authorization).toBe('Bearer sandbox-token');
      expect(gateway.requests[0]?.body).toMatchObject({
        model: 'openclaw/default',
      });
    } finally {
      await gateway.close();
    }
  });

  it('defaults the CLI to adaptive OpenClaw rewriting and skips short quick status replies', async () => {
    const gateway = await startMockOpenClawGateway({ token: 'sandbox-token' });

    try {
      const stdout = await runCli(
        [
          'local',
          'left_long_press',
          '--brain',
          'openclaw',
          '--workspaces-config',
          workspaceConfigPath,
          '--openclaw-transport',
          'http',
          '--openclaw-base-url',
          gateway.url,
          '--openclaw-token',
          'sandbox-token',
        ],
        {
          JARVIS_DISABLE_TTS: '1',
        },
      );

      const response = JSON.parse(stdout);
      expect(response).toMatchObject({
        status: 'completed',
        speak: 'Workspace ready. Git repository not initialized. No tests running.',
      });
      expect(gateway.requests).toHaveLength(0);
    } finally {
      await gateway.close();
    }
  });

  it('reports OpenClaw transport metadata from the health endpoint', async () => {
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
      brainMode: 'openclaw',
      openclaw: {
        transport: 'http',
        baseUrl: 'http://127.0.0.1:8080',
      },
    });

    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    try {
      await expect(readHealthPayload(address.port)).resolves.toEqual({
        ok: true,
        brainMode: 'openclaw',
        openclawTransport: 'http',
        openclawRewritePolicy: 'always',
        openclawRewriteHealth: {
          connectionState: 'not_applicable',
          lastConnectionError: null,
          foregroundBudgetMs: null,
          backgroundBudgetMs: null,
          counters: {
            rewritten: 0,
            skipped: 0,
            timedOut: 0,
            failed: 0,
          },
          lastOutcome: null,
        },
        openclawReady: true,
      });
    } finally {
      await new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
    }
  });

  it('reports gateway-client transport metadata from the health endpoint', async () => {
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
      brainMode: 'openclaw',
      openclaw: {
        transport: 'gateway-client',
        baseUrl: 'http://127.0.0.1:8080',
        model: 'openai/mock-rewrite-model',
        agentId: 'jarvis_rewrite',
        timeoutMs: 100,
      },
    });

    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    try {
      const payload = await waitForGatewayHealthState(address.port, 'failed');
      expect(payload).toMatchObject({
        ok: true,
        brainMode: 'openclaw',
        openclawTransport: 'gateway-client',
        openclawRewritePolicy: 'always',
        openclawRewriteHealth: {
          foregroundBudgetMs: null,
          backgroundBudgetMs: null,
          counters: {
            rewritten: 0,
            skipped: 0,
            timedOut: 0,
            failed: 0,
          },
          lastOutcome: null,
        },
        openclawReady: true,
      });
      expect(payload.openclawRewriteHealth.connectionState).toBe('failed');
      expect(payload.openclawRewriteHealth.lastConnectionError).toEqual(expect.any(String));
    } finally {
      await new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
    }
  });
});