import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  assertSafeBridgeExposure,
  isLoopbackBridgeHost,
  resolveBridgeCommandRuntimeOptions,
  resolveBrainMode,
  resolveBridgeHost,
  resolveRelayToken,
} from '../src/cli/runtime-options';

describe('resolveBrainMode', () => {
  it('defaults to local when no CLI flag or environment override is present', () => {
    expect(resolveBrainMode(undefined, {})).toBe('local');
  });

  it('uses the environment override when no CLI flag is provided', () => {
    expect(resolveBrainMode(undefined, { JARVIS_BRAIN_MODE: 'openclaw' })).toBe('openclaw');
  });

  it('prefers the CLI flag over the environment override', () => {
    expect(resolveBrainMode('local', { JARVIS_BRAIN_MODE: 'openclaw' })).toBe('local');
  });

  it('rejects invalid brain modes', () => {
    expect(() => resolveBrainMode('invalid', {})).toThrow(
      'Invalid brain mode "invalid". Expected "local" or "openclaw".',
    );
  });
});

describe('resolveBridgeCommandRuntimeOptions', () => {
  it('exposes DevPods as the primary CLI alias while keeping the legacy binary', () => {
    const pkg = JSON.parse(fs.readFileSync(path.resolve(process.cwd(), 'package.json'), 'utf8')) as {
      name: string;
      bin: Record<string, string>;
    };

    expect(pkg.name).toBe('devpods');
    expect(pkg.bin.devpods).toBe('dist/src/cli/jarvis-earbuds.js');
    expect(pkg.bin['jarvis-earbuds']).toBe(pkg.bin.devpods);
  });

  it('keeps local mode as the default runtime', () => {
    const options = resolveBridgeCommandRuntimeOptions({
      values: {},
      env: {},
      cwd: 'C:/workspace',
    });

    expect(options).toEqual({
      configPath: path.resolve('C:/workspace', 'config/workspaces.json'),
      brainMode: 'local',
    });
  });

  it('defaults the bridge host to loopback', () => {
    expect(resolveBridgeHost(undefined, {})).toBe('127.0.0.1');
  });

  it('uses the environment override for the bridge host', () => {
    expect(resolveBridgeHost(undefined, { JARVIS_BRIDGE_HOST: '0.0.0.0' })).toBe('0.0.0.0');
  });

  it('recognizes loopback bridge hosts', () => {
    expect(isLoopbackBridgeHost('127.0.0.1')).toBe(true);
    expect(isLoopbackBridgeHost('localhost')).toBe(true);
    expect(isLoopbackBridgeHost('::1')).toBe(true);
    expect(isLoopbackBridgeHost('0.0.0.0')).toBe(false);
  });

  it('requires a relay token when binding to a non-loopback host', () => {
    expect(() => assertSafeBridgeExposure('0.0.0.0', undefined)).toThrow(
      'A relay token is required when binding the bridge to a non-loopback host.',
    );
  });

  it('allows loopback bindings without a relay token', () => {
    expect(() => assertSafeBridgeExposure('127.0.0.1', undefined)).not.toThrow();
  });

  it('prefers the CLI relay token over the environment default', () => {
    expect(resolveRelayToken('cli-token', { JARVIS_RELAY_TOKEN: 'env-token' })).toBe('cli-token');
  });

  it('resolves local-cli OpenClaw options from CLI values', () => {
    const options = resolveBridgeCommandRuntimeOptions({
      values: {
        brain: 'openclaw',
        'openclaw-transport': 'local-cli',
        'openclaw-model': 'openai/mock-rewrite-model',
        'openclaw-config-path': './runtime/openclaw-config.json',
        'openclaw-state-dir': './runtime/openclaw-state',
        'openclaw-workspace-dir': './repo',
        'openclaw-provider-plugin-ids': 'openai,mock-provider',
        'openclaw-timeout-ms': '45000',
      },
      env: {},
      cwd: 'C:/workspace',
    });

    expect(options).toEqual({
      configPath: path.resolve('C:/workspace', 'config/workspaces.json'),
      brainMode: 'openclaw',
      openclaw: {
        transport: 'local-cli',
        model: 'openai/mock-rewrite-model',
        configPath: path.resolve('C:/workspace', 'runtime/openclaw-config.json'),
        stateDir: path.resolve('C:/workspace', 'runtime/openclaw-state'),
        workspaceDir: path.resolve('C:/workspace', 'repo'),
        rewritePolicy: 'adaptive',
        foregroundBudgetMs: 250,
        backgroundBudgetMs: 750,
        providerPluginIds: ['openai', 'mock-provider'],
        timeoutMs: 45000,
      },
    });
  });

  it('uses environment defaults for HTTP OpenClaw mode', () => {
    const options = resolveBridgeCommandRuntimeOptions({
      values: {
        brain: 'openclaw',
      },
      env: {
        JARVIS_OPENCLAW_TRANSPORT: 'http',
        JARVIS_OPENCLAW_BASE_URL: 'http://127.0.0.1:8080',
        JARVIS_OPENCLAW_TOKEN: 'secret-token',
      },
      cwd: 'C:/workspace',
    });

    expect(options).toEqual({
      configPath: path.resolve('C:/workspace', 'config/workspaces.json'),
      brainMode: 'openclaw',
      openclaw: {
        transport: 'http',
        baseUrl: 'http://127.0.0.1:8080',
        token: 'secret-token',
        rewritePolicy: 'adaptive',
        foregroundBudgetMs: 250,
        backgroundBudgetMs: 750,
      },
    });
  });

  it('resolves gateway-client OpenClaw options from CLI values', () => {
    const options = resolveBridgeCommandRuntimeOptions({
      values: {
        brain: 'openclaw',
        'openclaw-transport': 'gateway-client',
        'openclaw-base-url': 'http://127.0.0.1:8080',
        'openclaw-model': 'openai/mock-rewrite-model',
        'openclaw-agent-id': 'jarvis_rewrite',
        'openclaw-timeout-ms': '45000',
      },
      env: {},
      cwd: 'C:/workspace',
    });

    expect(options).toEqual({
      configPath: path.resolve('C:/workspace', 'config/workspaces.json'),
      brainMode: 'openclaw',
      openclaw: {
        transport: 'gateway-client',
        baseUrl: 'http://127.0.0.1:8080',
        model: 'openai/mock-rewrite-model',
        agentId: 'jarvis_rewrite',
        rewritePolicy: 'adaptive',
        foregroundBudgetMs: 250,
        backgroundBudgetMs: 750,
        timeoutMs: 45000,
      },
    });
  });

  it('prefers the CLI rewrite policy over the environment default', () => {
    const options = resolveBridgeCommandRuntimeOptions({
      values: {
        brain: 'openclaw',
        'openclaw-transport': 'http',
        'openclaw-base-url': 'http://127.0.0.1:8080',
        'openclaw-rewrite-policy': 'always',
      },
      env: {
        JARVIS_OPENCLAW_REWRITE_POLICY: 'adaptive',
      },
      cwd: 'C:/workspace',
    });

    expect(options).toEqual({
      configPath: path.resolve('C:/workspace', 'config/workspaces.json'),
      brainMode: 'openclaw',
      openclaw: {
        transport: 'http',
        baseUrl: 'http://127.0.0.1:8080',
        rewritePolicy: 'always',
        foregroundBudgetMs: 250,
        backgroundBudgetMs: 750,
      },
    });
  });

  it('allows rewrite budgets to be disabled explicitly', () => {
    const options = resolveBridgeCommandRuntimeOptions({
      values: {
        brain: 'openclaw',
        'openclaw-transport': 'http',
        'openclaw-base-url': 'http://127.0.0.1:8080',
        'openclaw-foreground-budget-ms': 'off',
        'openclaw-background-budget-ms': 'none',
      },
      env: {},
      cwd: 'C:/workspace',
    });

    expect(options).toEqual({
      configPath: path.resolve('C:/workspace', 'config/workspaces.json'),
      brainMode: 'openclaw',
      openclaw: {
        transport: 'http',
        baseUrl: 'http://127.0.0.1:8080',
        rewritePolicy: 'adaptive',
      },
    });
  });

  it('fails fast when local-cli mode is missing required settings', () => {
    expect(() =>
      resolveBridgeCommandRuntimeOptions({
        values: {
          brain: 'openclaw',
          'openclaw-transport': 'local-cli',
        },
        env: {},
        cwd: 'C:/workspace',
      })).toThrow(
      'OpenClaw local-cli mode requires --openclaw-model, --openclaw-config-path, --openclaw-state-dir, and --openclaw-workspace-dir.',
    );
  });

  it('fails fast when local-cli model does not include a provider segment', () => {
    expect(() =>
      resolveBridgeCommandRuntimeOptions({
        values: {
          brain: 'openclaw',
          'openclaw-transport': 'local-cli',
          'openclaw-model': 'mock-rewrite-model',
          'openclaw-config-path': './runtime/openclaw-config.json',
          'openclaw-state-dir': './runtime/openclaw-state',
          'openclaw-workspace-dir': './repo',
        },
        env: {},
        cwd: 'C:/workspace',
      })).toThrow(
      'OpenClaw local-cli mode requires --openclaw-model in provider/model format unless --openclaw-provider-plugin-ids is provided.',
    );
  });

  it('fails fast when HTTP mode is missing the base URL', () => {
    expect(() =>
      resolveBridgeCommandRuntimeOptions({
        values: {
          brain: 'openclaw',
          'openclaw-transport': 'http',
        },
        env: {},
        cwd: 'C:/workspace',
      })).toThrow('OpenClaw HTTP mode requires --openclaw-base-url or JARVIS_OPENCLAW_BASE_URL.');
  });

  it('fails fast when gateway-client mode is missing the agent ID', () => {
    expect(() =>
      resolveBridgeCommandRuntimeOptions({
        values: {
          brain: 'openclaw',
          'openclaw-transport': 'gateway-client',
          'openclaw-base-url': 'http://127.0.0.1:8080',
          'openclaw-model': 'openai/mock-rewrite-model',
        },
        env: {},
        cwd: 'C:/workspace',
      })).toThrow(
      'OpenClaw gateway-client mode requires --openclaw-model and --openclaw-agent-id unless JARVIS_OPENCLAW_MODEL and JARVIS_OPENCLAW_AGENT_ID are set.',
    );
  });

  it('rejects timeout values above the safety ceiling', () => {
    expect(() =>
      resolveBridgeCommandRuntimeOptions({
        values: {
          brain: 'openclaw',
          'openclaw-transport': 'http',
          'openclaw-base-url': 'http://127.0.0.1:8080',
          'openclaw-timeout-ms': '300001',
        },
        env: {},
        cwd: 'C:/workspace',
      })).toThrow('Expected a positive number up to 300000 but received "300001".');
  });

  it('rejects invalid rewrite policies', () => {
    expect(() =>
      resolveBridgeCommandRuntimeOptions({
        values: {
          brain: 'openclaw',
          'openclaw-transport': 'http',
          'openclaw-base-url': 'http://127.0.0.1:8080',
          'openclaw-rewrite-policy': 'sometimes',
        },
        env: {},
        cwd: 'C:/workspace',
      })).toThrow('Invalid OpenClaw rewrite policy "sometimes". Expected "adaptive" or "always".');
  });
});