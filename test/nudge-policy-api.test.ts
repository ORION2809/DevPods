import { afterEach, describe, expect, it } from 'vitest';
import { createBridgeServer } from '../src/bridge/server';
import type { WorkspaceRegistry } from '../src/protocol/schemas';

describe('nudge policy api', () => {
  const servers: Array<ReturnType<typeof createBridgeServer>['server']> = [];

  afterEach(async () => {
    await Promise.all(
      servers.splice(0).map(
        (server) =>
          new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve()))),
      ),
    );
  });

  it('returns default nudge policy for a session', async () => {
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
      `http://127.0.0.1:${address.port}/sessions/test-session/nudge-policy`,
      { headers: { Authorization: 'Bearer relay-secret' } },
    );

    expect(response.status).toBe(200);
    const payload = (await response.json()) as {
      sessionId: string;
      enabled: boolean;
      mutedTypes: string[];
      thresholds: unknown[];
    };
    expect(payload.sessionId).toBe('test-session');
    expect(payload.enabled).toBe(true);
    expect(payload.mutedTypes).toEqual([]);
    expect(payload.thresholds).toEqual([]);
  });

  it('updates nudge policy via POST', async () => {
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
      `http://127.0.0.1:${address.port}/sessions/${sessionId}/nudge-policy`,
      {
        method: 'POST',
        headers: {
          Authorization: 'Bearer relay-secret',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          enabled: false,
          mutedTypes: ['ci_red'],
          thresholds: [
            { type: 'uncommitted_files', changedFilesMin: 5, staleBranchHours: 12, consecutiveTestFailures: 3, ciRedHours: 2 },
          ],
        }),
      },
    );

    expect(postResponse.status).toBe(200);
    const payload = (await postResponse.json()) as {
      sessionId: string;
      enabled: boolean;
      mutedTypes: string[];
      thresholds: Array<{ type: string; changedFilesMin: number }>;
    };
    expect(payload.enabled).toBe(false);
    expect(payload.mutedTypes).toEqual(['ci_red']);
    expect(payload.thresholds.length).toBe(1);
    expect(payload.thresholds[0].type).toBe('uncommitted_files');
    expect(payload.thresholds[0].changedFilesMin).toBe(5);

    // Verify GET returns the updated policy
    const getResponse = await fetch(
      `http://127.0.0.1:${address.port}/sessions/${sessionId}/nudge-policy`,
      { headers: { Authorization: 'Bearer relay-secret' } },
    );
    expect(getResponse.status).toBe(200);
    const getPayload = (await getResponse.json()) as { enabled: boolean; mutedTypes: string[] };
    expect(getPayload.enabled).toBe(false);
    expect(getPayload.mutedTypes).toEqual(['ci_red']);
  });

  it('rejects invalid nudge policy payload', async () => {
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
      `http://127.0.0.1:${address.port}/sessions/test-session/nudge-policy`,
      {
        method: 'POST',
        headers: {
          Authorization: 'Bearer relay-secret',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          enabled: 'not-a-boolean',
        }),
      },
    );

    expect(response.status).toBe(400);
    const payload = (await response.json()) as { error: string };
    expect(payload.error).toContain('Invalid nudge policy payload');
  });
});
