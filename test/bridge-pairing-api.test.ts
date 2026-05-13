import { afterEach, describe, expect, it } from 'vitest';
import { createBridgeServer } from '../src/bridge/server';
import type { WorkspaceRegistry } from '../src/protocol/schemas';

function expectPairingCode(value: unknown): asserts value is string {
  expect(typeof value).toBe('string');
  expect((value as string).length).toBe(6);
}

describe('bridge pairing api', () => {
  const servers: Array<ReturnType<typeof createBridgeServer>['server']> = [];

  afterEach(async () => {
    await Promise.all(
      servers.splice(0).map(
        (server) =>
          new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve()))),
      ),
    );
  });

  it('returns a pairing payload as json without requiring prior relay authorization', async () => {
    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'android_repo',
      workspaces: [],
    };

    const { server } = createBridgeServer({
      registry,
      relayToken: 'relay-secret',
      pairingBaseUrl: 'http://192.168.1.10:4545',
      pairingCode: 'ABC123',
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const response = await fetch(`http://127.0.0.1:${address.port}/pairing`, {
      headers: { Accept: 'application/json' },
    });

    expect(response.status).toBe(200);
    expect(response.headers.get('content-type')).toContain('application/json');
    const payload = (await response.json()) as {
      bridgeBaseUrl: string;
      pairingCode: string;
      workspace: string;
      pairingUri: string;
      pairingPageUrl: string;
    };
    expect(payload.bridgeBaseUrl).toBe('http://192.168.1.10:4545');
    expectPairingCode(payload.pairingCode);
    expect(payload.workspace).toBe('android_repo');
    expect(payload.pairingUri).toBe(
      `devpods://pair?bridgeBaseUrl=http%3A%2F%2F192.168.1.10%3A4545&pairingCode=${payload.pairingCode}&workspace=android_repo`,
    );
    expect(payload.pairingPageUrl).toBe('http://192.168.1.10:4545/pairing');
  });

  it('renders a phone-usable html pairing page by default', async () => {
    const { server } = createBridgeServer({
      pairingBaseUrl: 'https://bridge.example.test/relay',
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const response = await fetch(`http://127.0.0.1:${address.port}/pairing`);
    const body = await response.text();

    expect(response.status).toBe(200);
    expect(response.headers.get('content-type')).toContain('text/html');
    expect(body).toContain('DevPods Relay Pairing');
    expect(body).toContain('Scan from DevPods Relay');
    expect(body).toContain('data-pairing-qr-value="https://bridge.example.test/relay/pairing"');
    expect(body).toContain('data:image/svg+xml');
    expect(body).toContain('devpods://pair?bridgeBaseUrl=https%3A%2F%2Fbridge.example.test%2Frelay&amp;workspace=current_repo');
    expect(body).toContain('Open DevPods Relay');
    expect(body).not.toContain('Relay token');
  });

  it('returns a clear conflict when no safe pairing base url is available', async () => {
    const { server } = createBridgeServer({});
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const response = await fetch(`http://127.0.0.1:${address.port}/pairing`, {
      headers: { Accept: 'application/json' },
    });

    expect(response.status).toBe(409);
    await expect(response.json()).resolves.toEqual({
      error: 'Pairing unavailable for the current bridge binding. Start the bridge with --pairing-base-url and a LAN-reachable URL.',
    });
  });

  it('omits the pairing code from the payload when relay auth is disabled', async () => {
    const { server } = createBridgeServer({
      pairingBaseUrl: 'https://bridge.example.test/relay',
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const response = await fetch(`http://127.0.0.1:${address.port}/pairing`, {
      headers: { Accept: 'application/json' },
    });

    const payload = (await response.json()) as {
      pairingCode?: string;
      pairingUri: string;
    };

    expect(response.status).toBe(200);
    expect(payload.pairingCode).toBeUndefined();
    expect(payload.pairingUri).toBe('devpods://pair?bridgeBaseUrl=https%3A%2F%2Fbridge.example.test%2Frelay&workspace=current_repo');
  });

  it('exchanges a valid pairing code for the relay token at /pairing/verify', async () => {
    const { server } = createBridgeServer({
      relayToken: 'relay-secret',
      pairingBaseUrl: 'http://192.168.1.10:4545',
      pairingCode: 'ABC123',
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const verifyResponse = await fetch(`http://127.0.0.1:${address.port}/pairing/verify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pairingCode: 'ABC123' }),
    });

    expect(verifyResponse.status).toBe(200);
    const verifyPayload = (await verifyResponse.json()) as { relayToken: string };
    expect(verifyPayload.relayToken).toBe('relay-secret');
  });

  it('rejects an invalid pairing code at /pairing/verify', async () => {
    const { server } = createBridgeServer({
      relayToken: 'relay-secret',
      pairingBaseUrl: 'http://192.168.1.10:4545',
      pairingCode: 'ABC123',
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const verifyResponse = await fetch(`http://127.0.0.1:${address.port}/pairing/verify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pairingCode: 'WRONG1' }),
    });

    expect(verifyResponse.status).toBe(401);
  });

  it('invalidates the pairing code after successful verification', async () => {
    const { server } = createBridgeServer({
      relayToken: 'relay-secret',
      pairingBaseUrl: 'http://192.168.1.10:4545',
      pairingCode: 'ABC123',
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const first = await fetch(`http://127.0.0.1:${address.port}/pairing/verify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pairingCode: 'ABC123' }),
    });
    expect(first.status).toBe(200);

    const second = await fetch(`http://127.0.0.1:${address.port}/pairing/verify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pairingCode: 'ABC123' }),
    });
    expect(second.status).toBe(401);
  });

  it('returns empty relay token from verify when relay auth is disabled', async () => {
    const { server } = createBridgeServer({
      pairingBaseUrl: 'http://192.168.1.10:4545',
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const verifyResponse = await fetch(`http://127.0.0.1:${address.port}/pairing/verify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pairingCode: 'ANYCODE' }),
    });

    expect(verifyResponse.status).toBe(200);
    const verifyPayload = (await verifyResponse.json()) as { relayToken: string };
    expect(verifyPayload.relayToken).toBe('');
  });
});