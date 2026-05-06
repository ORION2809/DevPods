import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { Notifier } from '../src/bridge/speaker';
import { createBridgeRuntime } from '../src/bridge/runtime';
import type { WorkspaceRegistry } from '../src/protocol/schemas';
import { openClawSandboxAgentId, startOpenClawManagedGateway } from '../src/openclaw/sandbox';
import { startMockOpenAiProvider } from '../simulation/openclaw-sandbox/mock-provider';

class MemoryNotifier implements Notifier {
  async notify(): Promise<void> {
    return;
  }
}

describe('managed openclaw mode', () => {
  let repoDir: string;
  let auditLogPath: string;

  beforeEach(() => {
    repoDir = fs.mkdtempSync(path.join(os.tmpdir(), 'jarvis-earbuds-openclaw-managed-'));
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

  it('rewrites completed quick status through the managed OpenClaw gateway-client transport', async () => {
    const provider = await startMockOpenAiProvider();
    const gateway = await startOpenClawManagedGateway({
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
          baseUrl: gateway.baseUrl,
          token: gateway.token,
          model: 'openai/mock-rewrite-model',
          agentId: openClawSandboxAgentId,
          rewritePolicy: 'always',
          timeoutMs: 240_000,
        },
      });

      const response = await runtime.handleEvent({
        source: 'developer_earbuds_simulator',
        sessionId: 'sess_openclaw_managed_gateway_client',
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
      await gateway.stop();
      await provider.stop();
    }
  }, 300_000);
});