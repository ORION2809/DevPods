import fs from 'node:fs';
import type { OpenClawGatewayOptions } from './client';
import {
  ensureOpenClawRuntimeCompatibility,
  resolveOpenClawCommand,
} from './sandbox';

export function preflightOpenClawOptions(options: OpenClawGatewayOptions): void {
  if (isLocalCliOptions(options)) {
    ensureOpenClawRuntimeCompatibility();
    resolveOpenClawCommand();

    if (!fs.existsSync(options.configPath)) {
      throw new Error(`OpenClaw config path does not exist: ${options.configPath}`);
    }

    if (!fs.existsSync(options.workspaceDir)) {
      throw new Error(`OpenClaw workspace directory does not exist: ${options.workspaceDir}`);
    }

    return;
  }

  try {
    new URL(options.baseUrl);
  } catch {
    throw new Error(`OpenClaw base URL is invalid: ${options.baseUrl}`);
  }

  if (isGatewayClientOptions(options)) {
    if (!options.model.trim()) {
      throw new Error('OpenClaw gateway-client mode requires a non-empty model.');
    }

    if (!options.agentId.trim()) {
      throw new Error('OpenClaw gateway-client mode requires a non-empty agent ID.');
    }
  }
}

export function assertLocalCliModelConfiguration(model: string, providerPluginIds: string[]): void {
  if (providerPluginIds.length > 0) {
    return;
  }

  const modelParts = model.split('/').map((part) => part.trim()).filter(Boolean);
  if (modelParts.length === 2) {
    return;
  }

  throw new Error(
    'OpenClaw local-cli mode requires --openclaw-model in provider/model format unless --openclaw-provider-plugin-ids is provided.',
  );
}

function isLocalCliOptions(options: OpenClawGatewayOptions): options is Extract<OpenClawGatewayOptions, { transport: 'local-cli' }> {
  return options.transport === 'local-cli';
}

function isGatewayClientOptions(
  options: OpenClawGatewayOptions,
): options is Extract<OpenClawGatewayOptions, { transport: 'gateway-client' }> {
  return options.transport === 'gateway-client';
}