import { describe, expect, it, afterEach } from 'vitest';
import { createBridgeServer } from '../src/bridge/server';
import type { BridgeCapabilitySnapshot, DevPodsInstalledTier } from '../src/protocol/schemas';
import { TierConfigStore } from '../src/personalization/tier-config-store';

describe('bridge capability negotiation', () => {
  const servers: import('http').Server[] = [];

  afterEach(() => {
    for (const server of servers) {
      server.close();
    }
    servers.length = 0;
  });

  async function getHealthPayload(port: number): Promise<Record<string, unknown>> {
    const response = await fetch(`http://127.0.0.1:${port}/health`, {
      headers: { Authorization: 'Bearer relay-secret' },
    });
    expect(response.status).toBe(200);
    return response.json() as Promise<Record<string, unknown>>;
  }

  it('defaults to core tier with core capabilities only', async () => {
    const { server } = createBridgeServer({
      relayToken: 'relay-secret',
      pairingBaseUrl: 'http://127.0.0.1:4545',
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const payload = await getHealthPayload(address.port);
    expect(payload.tier).toBe('core');

    const snapshot = payload.capabilities as BridgeCapabilitySnapshot;
    expect(snapshot.tier).toBe('core');
    expect(snapshot.capabilities.core.available).toBe(true);
    expect(snapshot.capabilities.core.voiceLoop).toBe(true);
    expect(snapshot.capabilities.core.approvals).toBe(true);
    expect(snapshot.capabilities.core.gitBasics).toBe(true);
    expect(snapshot.capabilities.agent.available).toBe(false);
    expect(snapshot.capabilities.agent.runtime).toBe('none');
    expect(snapshot.capabilities.intelligence.available).toBe(false);
    expect(snapshot.capabilities.intelligence.indexState).toBe('not_installed');
  });

  it('advertises agent tier when configured', async () => {
    const { server } = createBridgeServer({
      relayToken: 'relay-secret',
      pairingBaseUrl: 'http://127.0.0.1:4545',
      tier: 'agent',
      brainMode: 'openclaw',
      openclaw: { transport: 'http', baseUrl: 'http://127.0.0.1:9999' },
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const payload = await getHealthPayload(address.port);
    expect(payload.tier).toBe('agent');

    const snapshot = payload.capabilities as BridgeCapabilitySnapshot;
    expect(snapshot.tier).toBe('agent');
    expect(snapshot.capabilities.agent.available).toBe(true);
    expect(snapshot.capabilities.agent.runtime).toBe('openclaw');
    expect(snapshot.capabilities.intelligence.available).toBe(false);
    expect(snapshot.capabilities.intelligence.indexState).toBe('not_installed');
  });

  it('advertises intelligence tier with intelligence capability', async () => {
    const tierConfigPath = `runtime-data/tier-config-test-${Date.now()}.json`;
    const store = new TierConfigStore(tierConfigPath);
    store.setTier('intelligence');
    store.setIntelligenceConsent('intelligence_workspace', { consented: true, consentedAtMs: Date.now() });

    const { server } = createBridgeServer({
      relayToken: 'relay-secret',
      pairingBaseUrl: 'http://127.0.0.1:4545',
      tier: 'intelligence',
      tierConfigStorePath: tierConfigPath,
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const payload = await getHealthPayload(address.port);
    expect(payload.tier).toBe('intelligence');

    const snapshot = payload.capabilities as BridgeCapabilitySnapshot;
    expect(snapshot.tier).toBe('intelligence');
    expect(snapshot.capabilities.intelligence.available).toBe(true);
    expect(snapshot.capabilities.intelligence.indexState).toBe('not_indexed');
    expect(snapshot.capabilities.intelligence.workspaces).toContain('intelligence_workspace');
    expect(snapshot.capabilities.intelligence.currentWorkspace).toBeNull();

    // Clean up
    try { require('fs').unlinkSync(tierConfigPath); } catch { /* ignore */ }
  });

  it('round-trips tier through CLI option resolution', async () => {
    const { resolveBridgeCommandRuntimeOptions } = await import('../src/cli/runtime-options');
    const options = resolveBridgeCommandRuntimeOptions({
      values: { tier: 'intelligence' },
      env: {},
      cwd: 'C:/workspace',
    });
    expect(options.tier).toBe('intelligence');
  });

  it('reads tier from environment variable', async () => {
    const { resolveBridgeCommandRuntimeOptions } = await import('../src/cli/runtime-options');
    const options = resolveBridgeCommandRuntimeOptions({
      values: {},
      env: { DEVPODS_TIER: 'agent' },
      cwd: 'C:/workspace',
    });
    expect(options.tier).toBe('agent');
  });

  it('respects workspace intelligence consent in capability snapshot', async () => {
    const tierConfigPath = `runtime-data/tier-config-test-${Date.now()}.json`;
    const store = new TierConfigStore(tierConfigPath);
    store.setTier('intelligence');
    store.setIntelligenceConsent('test_workspace', { consented: true, consentedAtMs: Date.now() });

    const { server } = createBridgeServer({
      relayToken: 'relay-secret',
      pairingBaseUrl: 'http://127.0.0.1:4545',
      tier: 'intelligence',
      tierConfigStorePath: tierConfigPath,
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const payload = await getHealthPayload(address.port);
    const snapshot = payload.capabilities as BridgeCapabilitySnapshot;
    expect(snapshot.capabilities.intelligence.available).toBe(true);
    expect(snapshot.capabilities.intelligence.workspaces).toContain('test_workspace');

    // Clean up
    try { require('fs').unlinkSync(tierConfigPath); } catch { /* ignore */ }
  });

  it('disables intelligence when workspace consent is missing', async () => {
    const tierConfigPath = `runtime-data/tier-config-test-${Date.now()}.json`;
    const store = new TierConfigStore(tierConfigPath);
    store.setTier('intelligence');
    // No consent for any workspace

    const { server } = createBridgeServer({
      relayToken: 'relay-secret',
      pairingBaseUrl: 'http://127.0.0.1:4545',
      tier: 'intelligence',
      tierConfigStorePath: tierConfigPath,
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const payload = await getHealthPayload(address.port);
    const snapshot = payload.capabilities as BridgeCapabilitySnapshot;
    expect(snapshot.capabilities.intelligence.available).toBe(false);
    expect(snapshot.capabilities.intelligence.indexState).toBe('disabled');

    // Clean up
    try { require('fs').unlinkSync(tierConfigPath); } catch { /* ignore */ }
  });
});
