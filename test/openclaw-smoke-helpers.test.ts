import { describe, expect, it } from 'vitest';
import {
  buildGatewayBackedSmokeRuntimeOptions,
  buildSmokeModelRef,
  resolveSmokeTransport,
} from '../simulation/openclaw/shared-smoke';

describe('openclaw smoke helpers', () => {
  it('defaults smoke transport to local-cli', () => {
    expect(resolveSmokeTransport(undefined)).toBe('local-cli');
  });

  it('rejects unsupported smoke transports', () => {
    expect(() => resolveSmokeTransport('http')).toThrow(
      'Unsupported smoke transport "http". Expected "local-cli" or "gateway-client".',
    );
  });

  it('builds local-cli runtime options for a gateway-backed smoke handle', () => {
    expect(buildGatewayBackedSmokeRuntimeOptions({
      transport: 'local-cli',
      model: 'openai/mock-rewrite-model',
      agentId: 'jarvis_rewrite',
      handle: {
        baseUrl: 'http://127.0.0.1:8080',
        token: 'token',
        configPath: 'C:/workspace/runtime/openclaw.json',
        stateDir: 'C:/workspace/runtime/state',
        workspaceDir: 'C:/workspace/runtime/workspace',
      },
    })).toEqual({
      transport: 'local-cli',
      model: 'openai/mock-rewrite-model',
      configPath: 'C:/workspace/runtime/openclaw.json',
      stateDir: 'C:/workspace/runtime/state',
      workspaceDir: 'C:/workspace/runtime/workspace',
      rewritePolicy: 'always',
      timeoutMs: 120000,
    });
  });

  it('builds gateway-client runtime options for a gateway-backed smoke handle', () => {
    expect(buildGatewayBackedSmokeRuntimeOptions({
      transport: 'gateway-client',
      model: 'openai/mock-rewrite-model',
      agentId: 'jarvis_rewrite',
      handle: {
        baseUrl: 'http://127.0.0.1:8080',
        token: 'token',
        configPath: 'C:/workspace/runtime/openclaw.json',
        stateDir: 'C:/workspace/runtime/state',
        workspaceDir: 'C:/workspace/runtime/workspace',
      },
    })).toEqual({
      transport: 'gateway-client',
      baseUrl: 'http://127.0.0.1:8080',
      token: 'token',
      model: 'openai/mock-rewrite-model',
      agentId: 'jarvis_rewrite',
      rewritePolicy: 'always',
      timeoutMs: 120000,
    });
  });

  it('normalizes provider-prefixed model refs without regex semantics', () => {
    expect(buildSmokeModelRef('mock.provider+', 'mock.provider+/rewrite-model')).toBe('mock.provider+/rewrite-model');
    expect(buildSmokeModelRef('openai', 'mock-rewrite-model')).toBe('openai/mock-rewrite-model');
  });
});