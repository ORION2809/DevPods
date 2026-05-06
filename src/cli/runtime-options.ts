import path from 'node:path';
import type { BridgeRuntimeOptions } from '../bridge/runtime';
import type { OpenClawGatewayOptions, OpenClawRewritePolicy } from '../openclaw/client';
import {
  assertLocalCliModelConfiguration,
  preflightOpenClawOptions as preflightOpenClawRuntimeOptions,
} from '../openclaw/validation';

type RuntimeOptionValues = Record<string, string | boolean | undefined>;

type BrainMode = NonNullable<BridgeRuntimeOptions['brainMode']>;

interface ResolveBridgeCommandRuntimeOptionsInput {
  values: RuntimeOptionValues;
  env?: NodeJS.ProcessEnv;
  cwd?: string;
}

export function resolveBrainMode(cliValue: string | boolean | undefined, env: NodeJS.ProcessEnv = {}): BrainMode {
  const candidate = stringifyOption(cliValue) ?? env.JARVIS_BRAIN_MODE?.trim() ?? 'local';

  if (candidate === 'local' || candidate === 'openclaw') {
    return candidate;
  }

  throw new Error(`Invalid brain mode "${candidate}". Expected "local" or "openclaw".`);
}

export function resolveBridgeHost(cliValue: string | boolean | undefined, env: NodeJS.ProcessEnv = {}): string {
  const candidate = stringifyOption(cliValue) ?? env.JARVIS_BRIDGE_HOST?.trim() ?? '127.0.0.1';

  if (!candidate) {
    throw new Error('Bridge host must not be empty.');
  }

  return candidate;
}

export function resolveRelayToken(
  cliValue: string | boolean | undefined,
  env: NodeJS.ProcessEnv = {},
): string | undefined {
  return stringifyOption(cliValue) ?? env.JARVIS_RELAY_TOKEN?.trim() ?? undefined;
}

export function isLoopbackBridgeHost(host: string): boolean {
  const normalizedHost = host.trim().toLowerCase();

  return normalizedHost === '127.0.0.1'
    || normalizedHost === 'localhost'
    || normalizedHost === '::1';
}

export function assertSafeBridgeExposure(host: string, relayToken: string | undefined): void {
  if (!isLoopbackBridgeHost(host) && !relayToken) {
    throw new Error('A relay token is required when binding the bridge to a non-loopback host.');
  }
}

export function resolveBridgeCommandRuntimeOptions(
  input: ResolveBridgeCommandRuntimeOptionsInput,
): Pick<BridgeRuntimeOptions, 'configPath' | 'brainMode' | 'openclaw'> {
  const cwd = input.cwd ?? process.cwd();
  const env = input.env ?? process.env;
  const configPath = resolvePathFromInput({
    cliValue: input.values['workspaces-config'],
    envValue: env.JARVIS_WORKSPACES_CONFIG,
    cwd,
    defaultValue: 'config/workspaces.json',
  });
  const brainMode = resolveBrainMode(input.values.brain, env);

  if (brainMode === 'local') {
    return {
      configPath,
      brainMode,
    };
  }

  return {
    configPath,
    brainMode,
    openclaw: resolveOpenClawOptions(input.values, env, cwd),
  };
}

export function preflightOpenClawOptions(options: OpenClawGatewayOptions): void {
  return preflightOpenClawRuntimeOptions(options);
}

function resolveOpenClawOptions(
  values: RuntimeOptionValues,
  env: NodeJS.ProcessEnv,
  cwd: string,
): OpenClawGatewayOptions {
  const transport = resolveTransport(values, env);
  const timeoutMs = resolveOptionalNumber(values['openclaw-timeout-ms'], env.JARVIS_OPENCLAW_TIMEOUT_MS);
  const token = stringifyOption(values['openclaw-token']) ?? env.JARVIS_OPENCLAW_TOKEN?.trim();
  const rewritePolicy = resolveRewritePolicy(values['openclaw-rewrite-policy'], env.JARVIS_OPENCLAW_REWRITE_POLICY);
  const foregroundBudgetMs = resolveRewriteBudgetMs(
    values['openclaw-foreground-budget-ms'],
    env.JARVIS_OPENCLAW_FOREGROUND_BUDGET_MS,
    250,
  );
  const backgroundBudgetMs = resolveRewriteBudgetMs(
    values['openclaw-background-budget-ms'],
    env.JARVIS_OPENCLAW_BACKGROUND_BUDGET_MS,
    750,
  );

  if (transport === 'http') {
    const baseUrl = stringifyOption(values['openclaw-base-url']) ?? env.JARVIS_OPENCLAW_BASE_URL?.trim();
    if (!baseUrl) {
      throw new Error('OpenClaw HTTP mode requires --openclaw-base-url or JARVIS_OPENCLAW_BASE_URL.');
    }

    const model = stringifyOption(values['openclaw-model']) ?? env.JARVIS_OPENCLAW_MODEL?.trim();

    return {
      transport: 'http',
      baseUrl,
      ...(token ? { token } : {}),
      ...(model ? { model } : {}),
      rewritePolicy,
      ...(foregroundBudgetMs !== undefined ? { foregroundBudgetMs } : {}),
      ...(backgroundBudgetMs !== undefined ? { backgroundBudgetMs } : {}),
      ...(timeoutMs !== undefined ? { timeoutMs } : {}),
    };
  }

  if (transport === 'gateway-client') {
    const baseUrl = stringifyOption(values['openclaw-base-url']) ?? env.JARVIS_OPENCLAW_BASE_URL?.trim();
    if (!baseUrl) {
      throw new Error('OpenClaw gateway-client mode requires --openclaw-base-url or JARVIS_OPENCLAW_BASE_URL.');
    }

    const model = stringifyOption(values['openclaw-model']) ?? env.JARVIS_OPENCLAW_MODEL?.trim();
    const agentId = stringifyOption(values['openclaw-agent-id']) ?? env.JARVIS_OPENCLAW_AGENT_ID?.trim();

    if (!model || !agentId) {
      throw new Error(
        'OpenClaw gateway-client mode requires --openclaw-model and --openclaw-agent-id unless JARVIS_OPENCLAW_MODEL and JARVIS_OPENCLAW_AGENT_ID are set.',
      );
    }

    return {
      transport: 'gateway-client',
      baseUrl,
      model,
      agentId,
      ...(token ? { token } : {}),
      rewritePolicy,
      ...(foregroundBudgetMs !== undefined ? { foregroundBudgetMs } : {}),
      ...(backgroundBudgetMs !== undefined ? { backgroundBudgetMs } : {}),
      ...(timeoutMs !== undefined ? { timeoutMs } : {}),
    };
  }

  const model = stringifyOption(values['openclaw-model']) ?? env.JARVIS_OPENCLAW_MODEL?.trim();
  const configPath = resolveOptionalPath(values['openclaw-config-path'], env.JARVIS_OPENCLAW_CONFIG_PATH, cwd);
  const stateDir = resolveOptionalPath(values['openclaw-state-dir'], env.JARVIS_OPENCLAW_STATE_DIR, cwd);
  const workspaceDir = resolveOptionalPath(values['openclaw-workspace-dir'], env.JARVIS_OPENCLAW_WORKSPACE_DIR, cwd);

  if (!model || !configPath || !stateDir || !workspaceDir) {
    throw new Error(
      'OpenClaw local-cli mode requires --openclaw-model, --openclaw-config-path, --openclaw-state-dir, and --openclaw-workspace-dir.',
    );
  }

  const providerPluginIds = resolveProviderPluginIds(
    stringifyOption(values['openclaw-provider-plugin-ids']) ?? env.JARVIS_OPENCLAW_PROVIDER_PLUGIN_IDS,
  );
  assertLocalCliModelConfiguration(model, providerPluginIds);

  return {
    transport: 'local-cli',
    model,
    configPath,
    stateDir,
    workspaceDir,
    rewritePolicy,
    ...(foregroundBudgetMs !== undefined ? { foregroundBudgetMs } : {}),
    ...(backgroundBudgetMs !== undefined ? { backgroundBudgetMs } : {}),
    ...(providerPluginIds.length > 0 ? { providerPluginIds } : {}),
    ...(timeoutMs !== undefined ? { timeoutMs } : {}),
  };
}

function resolveTransport(values: RuntimeOptionValues, env: NodeJS.ProcessEnv): 'http' | 'local-cli' | 'gateway-client' {
  const candidate = stringifyOption(values['openclaw-transport']) ?? env.JARVIS_OPENCLAW_TRANSPORT?.trim() ?? 'local-cli';

  if (candidate === 'http' || candidate === 'local-cli' || candidate === 'gateway-client') {
    return candidate;
  }

  throw new Error(`Invalid OpenClaw transport "${candidate}". Expected "http", "local-cli", or "gateway-client".`);
}

function resolvePathFromInput(options: {
  cliValue: string | boolean | undefined;
  envValue: string | undefined;
  cwd: string;
  defaultValue: string;
}): string {
  const value = stringifyOption(options.cliValue) ?? options.envValue?.trim() ?? options.defaultValue;
  return path.resolve(options.cwd, value);
}

function resolveOptionalPath(
  cliValue: string | boolean | undefined,
  envValue: string | undefined,
  cwd: string,
): string | undefined {
  const value = stringifyOption(cliValue) ?? envValue?.trim();
  return value ? path.resolve(cwd, value) : undefined;
}

function resolveOptionalNumber(
  cliValue: string | boolean | undefined,
  envValue: string | undefined,
): number | undefined {
  const rawValue = stringifyOption(cliValue) ?? envValue?.trim();

  if (!rawValue) {
    return undefined;
  }

  const parsedValue = Number(rawValue);
  if (!Number.isFinite(parsedValue) || parsedValue <= 0 || parsedValue > 300_000) {
    throw new Error(`Expected a positive number up to 300000 but received "${rawValue}".`);
  }

  return parsedValue;
}

function resolveProviderPluginIds(rawValue: string | undefined): string[] {
  return rawValue?.split(',').map((value) => value.trim()).filter(Boolean) ?? [];
}

function resolveRewriteBudgetMs(
  cliValue: string | boolean | undefined,
  envValue: string | undefined,
  defaultValue: number,
): number | undefined {
  const rawValue = stringifyOption(cliValue) ?? envValue?.trim();

  if (!rawValue) {
    return defaultValue;
  }

  if (rawValue === 'off' || rawValue === 'none') {
    return undefined;
  }

  const parsedValue = Number(rawValue);
  if (!Number.isFinite(parsedValue) || parsedValue <= 0 || parsedValue > 300_000) {
    throw new Error(`Expected a positive number up to 300000 or "off" but received "${rawValue}".`);
  }

  return parsedValue;
}

function resolveRewritePolicy(
  cliValue: string | boolean | undefined,
  envValue: string | undefined,
): OpenClawRewritePolicy {
  const candidate = stringifyOption(cliValue) ?? envValue?.trim() ?? 'adaptive';

  if (candidate === 'adaptive' || candidate === 'always') {
    return candidate;
  }

  throw new Error(`Invalid OpenClaw rewrite policy "${candidate}". Expected "adaptive" or "always".`);
}

function stringifyOption(value: string | boolean | undefined): string | undefined {
  return typeof value === 'string' ? value.trim() : undefined;
}

