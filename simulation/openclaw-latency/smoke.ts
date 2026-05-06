import fs from 'node:fs';
import { openClawSandboxAgentId, startOpenClawSandbox } from '../../src/openclaw/sandbox';
import type { OpenClawSandboxProviderConfig } from '../../src/openclaw/sandbox';
import { startMockOpenAiProvider } from '../openclaw-sandbox/mock-provider';
import {
  buildSmokeModelRef,
  createSmokeWorkspace,
  MemoryNotifier,
  resolveSmokeTransport,
  runOpenClawSmokeWorkflow,
} from '../openclaw/shared-smoke';

type MockProviderHandle = Awaited<ReturnType<typeof startMockOpenAiProvider>>;

function logStep(message: string): void {
  process.stdout.write(`[openclaw-latency-smoke] ${message}\n`);
}

async function main(): Promise<void> {
  const smokeStartedAt = Date.now();
  const workspace = createSmokeWorkspace('jarvis-openclaw-latency-smoke-repo-');
  const providerRequestLogPath = `${workspace.repoDir}/mock-provider.requests.jsonl`;
  const notifier = new MemoryNotifier();
  const providerMode = (process.env.JARVIS_OPENCLAW_PROVIDER ?? 'mock').trim().toLowerCase();
  const openclawTransport = resolveSmokeTransport(process.env.JARVIS_OPENCLAW_TRANSPORT);
  const foregroundBudgetMs = resolveBudget(process.env.JARVIS_OPENCLAW_FOREGROUND_BUDGET_MS, 250);
  const backgroundBudgetMs = resolveBudget(process.env.JARVIS_OPENCLAW_BACKGROUND_BUDGET_MS, 750);
  let providerHandle: MockProviderHandle | null = null;
  let provider: OpenClawSandboxProviderConfig;

  logStep(`Preparing temporary workspace in ${workspace.repoDir}.`);

  if (providerMode === 'nvidia') {
    logStep('Using NVIDIA-backed OpenClaw provider mode.');
    provider = createNvidiaProvider();
  } else {
    logStep('Starting mock OpenAI-compatible provider for deterministic sandbox responses.');
    providerHandle = await startMockOpenAiProvider({ requestLogPath: providerRequestLogPath });
    provider = {
      providerId: 'openai',
      modelId: 'mock-rewrite-model',
      modelName: 'Mock Rewrite Model',
      baseUrl: providerHandle.baseUrl,
      api: 'openai-completions',
      apiKey: 'mock-provider-key',
      reasoning: false,
    };
  }

  logStep('Starting actual OpenClaw gateway in an isolated sandbox for latency-mode validation.');
  const sandbox = await startOpenClawSandbox({
    provider,
    cleanupOnStop: true,
  });
  const openClawModel = buildSmokeModelRef(provider.providerId, provider.modelId);

  logStep(
    `OpenClaw gateway is ready at ${sandbox.baseUrl}; shipped latency mode will use ${openclawTransport} with budgets ${foregroundBudgetMs ?? 'off'}/${backgroundBudgetMs ?? 'off'}ms.`,
  );

  try {
    const result = await runOpenClawSmokeWorkflow({
      smokeStartedAt,
      registry: workspace.registry,
      auditLogPath: workspace.auditLogPath,
      notifier,
      openclaw: buildLatencySmokeRuntimeOptions({
        transport: openclawTransport,
        model: openClawModel,
        agentId: openClawSandboxAgentId,
        foregroundBudgetMs,
        backgroundBudgetMs,
        handle: sandbox,
      }),
      logStep,
    });

    if (providerHandle && providerHandle.requests.length === 0) {
      logStep('No provider request was observed before the latency budgets elapsed; this is valid for the shipped fast-fallback path.');
    }

    logStep('OpenClaw latency smoke workflow completed successfully.');

    process.stdout.write(
      `${JSON.stringify({
        ok: true,
        providerMode,
        transport: openclawTransport,
        gatewayMode: 'sandbox',
        sandboxBaseUrl: sandbox.baseUrl,
        budgetsMs: {
          foreground: foregroundBudgetMs ?? null,
          background: backgroundBudgetMs ?? null,
        },
        providerRequestCount: providerHandle?.requests.length ?? null,
        bridgePort: result.bridgePort,
        quickStatusSpeak: result.quickStatusSpeak,
        approvalSpeak: result.approvalSpeak,
        completionSpeak: result.completionSpeak,
        latencyMs: result.latencyMs,
      }, null, 2)}\n`,
    );
  } catch (error) {
    if (providerHandle) {
      logStep(`Mock provider parsed request count: ${providerHandle.requests.length}.`);
    }

    dumpProviderRequestLog(providerRequestLogPath);
    throw error;
  } finally {
    logStep('Stopping bridge server and OpenClaw sandbox.');
    await workspace.cleanup();
    await sandbox.stop();
    await providerHandle?.stop();
  }
}

function buildLatencySmokeRuntimeOptions(options: {
  transport: ReturnType<typeof resolveSmokeTransport>;
  model: string;
  agentId: string;
  foregroundBudgetMs: number | undefined;
  backgroundBudgetMs: number | undefined;
  handle: {
    baseUrl: string;
    token: string;
    configPath: string;
    stateDir: string;
    workspaceDir: string;
  };
}) {
  if (options.transport === 'gateway-client') {
    return {
      transport: 'gateway-client' as const,
      baseUrl: options.handle.baseUrl,
      token: options.handle.token,
      model: options.model,
      agentId: options.agentId,
      rewritePolicy: 'adaptive' as const,
      ...(options.foregroundBudgetMs !== undefined ? { foregroundBudgetMs: options.foregroundBudgetMs } : {}),
      ...(options.backgroundBudgetMs !== undefined ? { backgroundBudgetMs: options.backgroundBudgetMs } : {}),
      timeoutMs: 120_000,
    };
  }

  return {
    transport: 'local-cli' as const,
    model: options.model,
    configPath: options.handle.configPath,
    stateDir: options.handle.stateDir,
    workspaceDir: options.handle.workspaceDir,
    rewritePolicy: 'adaptive' as const,
    ...(options.foregroundBudgetMs !== undefined ? { foregroundBudgetMs: options.foregroundBudgetMs } : {}),
    ...(options.backgroundBudgetMs !== undefined ? { backgroundBudgetMs: options.backgroundBudgetMs } : {}),
    timeoutMs: 120_000,
  };
}

function resolveBudget(rawValue: string | undefined, defaultValue: number): number | undefined {
  const candidate = rawValue?.trim();

  if (!candidate) {
    return defaultValue;
  }

  if (candidate === 'off' || candidate === 'none') {
    return undefined;
  }

  const parsedValue = Number(candidate);
  if (!Number.isFinite(parsedValue) || parsedValue <= 0) {
    throw new Error(`Expected a positive rewrite budget or "off" but received "${candidate}".`);
  }

  return Math.floor(parsedValue);
}

function dumpProviderRequestLog(logPath: string): void {
  if (!fs.existsSync(logPath)) {
    logStep('Mock provider request log was not created.');
    return;
  }

  const content = fs.readFileSync(logPath, 'utf8').trim();
  if (!content) {
    logStep('Mock provider request log is empty.');
    return;
  }

  process.stdout.write(`[openclaw-latency-smoke] Mock provider request log:\n${content}\n`);
}

function createNvidiaProvider() {
  const nvidiaApiKey = process.env.NVIDIA_API_KEY?.trim() || process.env.NVIDIA_NIM_API_KEY?.trim();
  if (!nvidiaApiKey) {
    throw new Error('NVIDIA_API_KEY or NVIDIA_NIM_API_KEY must be set for NVIDIA sandbox mode.');
  }

  return {
    providerId: 'nvidia',
    modelId: 'nvidia/nemotron-3-super-120b-a12b',
    modelName: 'nvidia/nemotron-3-super-120b-a12b',
    baseUrl: 'https://integrate.api.nvidia.com/v1',
    api: 'openai-completions' as const,
    apiKey: nvidiaApiKey,
    reasoning: true,
  };
}

void main().catch((error) => {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 1;
});