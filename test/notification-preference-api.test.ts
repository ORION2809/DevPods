import { afterEach, describe, expect, it } from 'vitest';
import { createBridgeServer } from '../src/bridge/server';
import type { WorkspaceRegistry } from '../src/protocol/schemas';

describe('notification preference api', () => {
  const servers: Array<ReturnType<typeof createBridgeServer>['server']> = [];

  afterEach(async () => {
    await Promise.all(
      servers.splice(0).map(
        (server) =>
          new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve()))),
      ),
    );
  });

  it('returns default preferences for a session', async () => {
    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'test_workspace',
      workspaces: [],
    };

    const { server } = createBridgeServer({
      registry,
      relayToken: 'relay-secret',
      pairingBaseUrl: 'http://127.0.0.1:4545',
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const response = await fetch(
      `http://127.0.0.1:${address.port}/sessions/test-session/preferences/notifications`,
      { headers: { Authorization: 'Bearer relay-secret' } },
    );

    expect(response.status).toBe(200);
    const payload = (await response.json()) as {
      sessionId: string;
      style: string;
      badgeEnabled: boolean;
      mutedKinds: string[];
      softPingTtsEnabled: boolean;
    };
    expect(payload.sessionId).toBe('test-session');
    expect(payload.style).toBe('soft');
    expect(payload.badgeEnabled).toBe(true);
    expect(payload.mutedKinds).toEqual([]);
    expect(payload.softPingTtsEnabled).toBe(true);
  });

  it('updates preferences via POST', async () => {
    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'test_workspace',
      workspaces: [],
    };

    const { server } = createBridgeServer({
      registry,
      relayToken: 'relay-secret',
      pairingBaseUrl: 'http://127.0.0.1:4545',
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const sessionId = 'test-session';
    const postResponse = await fetch(
      `http://127.0.0.1:${address.port}/sessions/${sessionId}/preferences/notifications`,
      {
        method: 'POST',
        headers: {
          Authorization: 'Bearer relay-secret',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          style: 'silent_with_badge',
          badgeEnabled: false,
          mutedKinds: ['workspace_nudge'],
          softPingTtsEnabled: false,
        }),
      },
    );

    expect(postResponse.status).toBe(200);
    const saved = (await postResponse.json()) as {
      sessionId: string;
      style: string;
      badgeEnabled: boolean;
      mutedKinds: string[];
      softPingTtsEnabled: boolean;
    };
    expect(saved.style).toBe('silent_with_badge');
    expect(saved.badgeEnabled).toBe(false);
    expect(saved.mutedKinds).toEqual(['workspace_nudge']);
    expect(saved.softPingTtsEnabled).toBe(false);

    const getResponse = await fetch(
      `http://127.0.0.1:${address.port}/sessions/${sessionId}/preferences/notifications`,
      { headers: { Authorization: 'Bearer relay-secret' } },
    );
    const fetched = (await getResponse.json()) as typeof saved;
    expect(fetched.style).toBe('silent_with_badge');
  });

  it('rejects invalid preference payloads', async () => {
    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'test_workspace',
      workspaces: [],
    };

    const { server } = createBridgeServer({
      registry,
      relayToken: 'relay-secret',
      pairingBaseUrl: 'http://127.0.0.1:4545',
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const response = await fetch(
      `http://127.0.0.1:${address.port}/sessions/test-session/preferences/notifications`,
      {
        method: 'POST',
        headers: {
          Authorization: 'Bearer relay-secret',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ style: 'invalid_style' }),
      },
    );

    expect(response.status).toBe(400);
  });

  it('rejects preference requests without authorization', async () => {
    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'test_workspace',
      workspaces: [],
    };

    const { server } = createBridgeServer({
      registry,
      relayToken: 'relay-secret',
      pairingBaseUrl: 'http://127.0.0.1:4545',
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const getResponse = await fetch(
      `http://127.0.0.1:${address.port}/sessions/test-session/preferences/notifications`,
    );
    expect(getResponse.status).toBe(401);

    const postResponse = await fetch(
      `http://127.0.0.1:${address.port}/sessions/test-session/preferences/notifications`,
      { method: 'POST' },
    );
    expect(postResponse.status).toBe(401);
  });
});
