import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createBridgeServer } from '../../src/bridge/server';
import type { Notifier } from '../../src/bridge/speaker';
import type { OpenClawGatewayOptions } from '../../src/openclaw/client';
import type { JarvisResponse, WorkspaceRegistry } from '../../src/protocol/schemas';
import { sendEvent } from '../../src/simulator/client';

export class MemoryNotifier implements Notifier {
  readonly messages: JarvisResponse[] = [];

  async notify(response: JarvisResponse): Promise<void> {
    this.messages.push(response);
  }
}

export interface SmokeWorkspaceHandle {
  readonly repoDir: string;
  readonly auditLogPath: string;
  readonly registry: WorkspaceRegistry;
  cleanup(): Promise<void>;
}

export type SmokeTransport = 'local-cli' | 'gateway-client';

interface GatewayRuntimeHandle {
  readonly baseUrl: string;
  readonly token: string;
  readonly configPath: string;
  readonly stateDir: string;
  readonly workspaceDir: string;
}

export interface OpenClawSmokeWorkflowResult {
  readonly bridgePort: number;
  readonly quickStatusSpeak: string;
  readonly approvalSpeak: string;
  readonly completionSpeak: string;
  readonly latencyMs: {
    readonly quickStatus: number;
    readonly approvalPrompt: number;
    readonly approvalStart: number;
    readonly completionWait: number;
    readonly total: number;
  };
}

export function resolveSmokeTransport(rawValue: string | undefined): SmokeTransport {
  const candidate = rawValue?.trim().toLowerCase() ?? 'local-cli';

  if (candidate === 'local-cli' || candidate === 'gateway-client') {
    return candidate;
  }

  throw new Error(`Unsupported smoke transport "${candidate}". Expected "local-cli" or "gateway-client".`);
}

export function buildGatewayBackedSmokeRuntimeOptions(options: {
  transport: SmokeTransport;
  model: string;
  agentId: string;
  handle: GatewayRuntimeHandle;
}): OpenClawGatewayOptions {
  if (options.transport === 'gateway-client') {
    return {
      transport: 'gateway-client',
      baseUrl: options.handle.baseUrl,
      token: options.handle.token,
      model: options.model,
      agentId: options.agentId,
      rewritePolicy: 'always',
      timeoutMs: 120_000,
    };
  }

  return {
    transport: 'local-cli',
    model: options.model,
    configPath: options.handle.configPath,
    stateDir: options.handle.stateDir,
    workspaceDir: options.handle.workspaceDir,
    rewritePolicy: 'always',
    timeoutMs: 120_000,
  };
}

export function buildSmokeModelRef(providerId: string, modelId: string): string {
  const providerPrefix = `${providerId}/`;
  const normalizedModelId = modelId.startsWith(providerPrefix)
    ? modelId.slice(providerPrefix.length)
    : modelId;

  return `${providerId}/${normalizedModelId}`;
}

export function createSmokeWorkspace(prefix = 'jarvis-openclaw-smoke-repo-'): SmokeWorkspaceHandle {
  const repoDir = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  const auditLogPath = path.join(repoDir, 'audit.log');

  fs.writeFileSync(path.join(repoDir, 'app.ts'), 'export const message = "hello";\n', 'utf8');
  fs.writeFileSync(path.join(repoDir, 'fake-task.js'), 'setTimeout(function () { process.exit(0); }, 25);\n', 'utf8');
  execFileSync('git', ['init'], { cwd: repoDir, stdio: 'ignore' });
  execFileSync('git', ['config', 'user.email', 'jarvis@example.com'], { cwd: repoDir, stdio: 'ignore' });
  execFileSync('git', ['config', 'user.name', 'Jarvis'], { cwd: repoDir, stdio: 'ignore' });
  execFileSync('git', ['add', 'app.ts'], { cwd: repoDir, stdio: 'ignore' });
  execFileSync('git', ['commit', '-m', 'chore: baseline'], { cwd: repoDir, stdio: 'ignore' });
  fs.writeFileSync(path.join(repoDir, 'app.ts'), 'export const message = "updated";\n', 'utf8');

  return {
    repoDir,
    auditLogPath,
    registry: {
      defaultWorkspaceId: 'current_repo',
      workspaces: [
        {
          id: 'current_repo',
          label: 'smoke_repo',
          rootPath: repoDir,
          allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure', 'run_tests'],
          approvalRequiredIntents: ['run_tests'],
          hardApprovalIntents: ['deploy'],
          commands: {
            run_tests: {
              description: 'Run workspace tests',
              command: 'node',
              args: [path.join(repoDir, 'fake-task.js')],
              timeoutMs: 2_000,
            },
          },
        },
      ],
    },
    async cleanup(): Promise<void> {
      try {
        fs.rmSync(repoDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 50 });
      } catch (error) {
        const code = error instanceof Error ? (error as NodeJS.ErrnoException).code : undefined;
        if (code !== 'EPERM' && code !== 'ENOTEMPTY' && code !== 'EBUSY') {
          throw error;
        }
      }
    },
  };
}

export async function runOpenClawSmokeWorkflow(options: {
  smokeStartedAt: number;
  registry: WorkspaceRegistry;
  auditLogPath: string;
  notifier: MemoryNotifier;
  openclaw: OpenClawGatewayOptions;
  logStep: (message: string) => void;
}): Promise<OpenClawSmokeWorkflowResult> {
  const { server } = createBridgeServer({
    registry: options.registry,
    auditLogPath: options.auditLogPath,
    notifier: options.notifier,
    brainMode: 'openclaw',
    openclaw: options.openclaw,
  });

  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  if (!address || typeof address === 'string') {
    throw new Error('Bridge server address was not available.');
  }

  options.logStep(`Bridge server is listening on port ${address.port}.`);

  try {
    options.logStep('Sending quick-status event through the bridge and OpenClaw gateway.');
    const quickStatusStartedAt = Date.now();
    const statusResponse = await sendEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'smoke-status',
      workspace: 'current_repo',
      device: 'left_bud',
      event: 'left_long_press',
      timestamp: Date.now(),
      wearState: 'in_ear',
      battery: 81,
      profile: 'coding_mode',
    }, address.port) as JarvisResponse;
    const quickStatusMs = Date.now() - quickStatusStartedAt;

    if (statusResponse.status !== 'completed') {
      throw new Error(`Expected quick status to complete, got ${statusResponse.status}.`);
    }

    options.logStep('Sending run_tests request and verifying the approval prompt stays local.');
    const approvalPromptStartedAt = Date.now();
    const approvalPrompt = await sendEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'smoke-tests',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'triple_tap_right',
      timestamp: Date.now(),
      utterance: 'run the tests',
    }, address.port) as JarvisResponse;
    const approvalPromptMs = Date.now() - approvalPromptStartedAt;

    if (!approvalPrompt.requiresApproval) {
      throw new Error('Expected run_tests to require approval before contacting OpenClaw.');
    }

    options.logStep('Approving the pending run_tests action and waiting for the background completion notification.');
    const approvalStartStartedAt = Date.now();
    const started = await sendEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'smoke-tests',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'approve_right_double_tap',
      timestamp: Date.now(),
      pendingActionId: approvalPrompt.actionId ?? undefined,
    }, address.port) as JarvisResponse;
    const approvalStartMs = Date.now() - approvalStartStartedAt;

    if (started.status !== 'running') {
      throw new Error(`Expected approved test run to start, got ${started.status}.`);
    }

    const completionWaitStartedAt = Date.now();
    const completion = await waitForNotification(
      () => options.notifier.messages.find(
        (message) => message.status === 'completed' && message.actionId === approvalPrompt.actionId,
      ),
      70_000,
    );
    const completionWaitMs = Date.now() - completionWaitStartedAt;
    if (!completion) {
      throw new Error('Expected a background completion notification after approval.');
    }

    return {
      bridgePort: address.port,
      quickStatusSpeak: statusResponse.speak,
      approvalSpeak: approvalPrompt.speak,
      completionSpeak: completion.speak,
      latencyMs: {
        quickStatus: quickStatusMs,
        approvalPrompt: approvalPromptMs,
        approvalStart: approvalStartMs,
        completionWait: completionWaitMs,
        total: Date.now() - options.smokeStartedAt,
      },
    };
  } finally {
    await new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
  }
}

async function waitForNotification<T>(resolveValue: () => T | undefined, timeoutMs: number): Promise<T | undefined> {
  const deadline = Date.now() + timeoutMs;

  while (Date.now() < deadline) {
    const value = resolveValue();
    if (value !== undefined) {
      return value;
    }

    await new Promise((resolve) => setTimeout(resolve, 250));
  }

  return resolveValue();
}