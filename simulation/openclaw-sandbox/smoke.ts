import fs from 'node:fs';
import { openClawSandboxAgentId, startOpenClawSandbox } from '../../src/openclaw/sandbox';
import type { OpenClawSandboxProviderConfig } from '../../src/openclaw/sandbox';
import { startMockOpenAiProvider } from './mock-provider';
import {
  buildGatewayBackedSmokeRuntimeOptions,
  buildSmokeModelRef,
  createSmokeWorkspace,
  MemoryNotifier,
  resolveSmokeTransport,
  runOpenClawSmokeWorkflow,
} from '../openclaw/shared-smoke';

type MockProviderHandle = Awaited<ReturnType<typeof startMockOpenAiProvider>>;

function logStep(message: string): void {
  process.stdout.write(`[openclaw-smoke] ${message}\n`);
}

async function main(): Promise<void> {
  const smokeStartedAt = Date.now();
  const workspace = createSmokeWorkspace();
  const providerRequestLogPath = `${workspace.repoDir}/mock-provider.requests.jsonl`;
  const notifier = new MemoryNotifier();
  const providerMode = (process.env.JARVIS_OPENCLAW_PROVIDER ?? 'mock').trim().toLowerCase();
  const openclawTransport = resolveSmokeTransport(process.env.JARVIS_OPENCLAW_TRANSPORT);
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

  logStep('Starting actual OpenClaw gateway in an isolated sandbox.');
  const sandbox = await startOpenClawSandbox({
    provider,
    cleanupOnStop: true,
  });
  const openClawModel = buildSmokeModelRef(provider.providerId, provider.modelId);

  logStep(`OpenClaw gateway is ready at ${sandbox.baseUrl}; rewrites will use the ${openclawTransport} transport.`);

  try {
    const result = await runOpenClawSmokeWorkflow({
      smokeStartedAt,
      registry: workspace.registry,
      auditLogPath: workspace.auditLogPath,
      notifier,
      openclaw: buildGatewayBackedSmokeRuntimeOptions({
        transport: openclawTransport,
        model: openClawModel,
        agentId: openClawSandboxAgentId,
        handle: sandbox,
      }),
      logStep,
    });

    if (providerHandle && providerHandle.requests.length === 0) {
      throw new Error('Expected the OpenClaw sandbox to reach the mock provider.');
    }

    logStep('OpenClaw sandbox smoke workflow completed successfully.');

    process.stdout.write(
      `${JSON.stringify({
        ok: true,
        providerMode,
        transport: openclawTransport,
        gatewayMode: 'sandbox',
        sandboxBaseUrl: sandbox.baseUrl,
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

  process.stdout.write(`[openclaw-smoke] Mock provider request log:\n${content}\n`);
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