import { afterEach, describe, expect, it } from 'vitest';
import { createBridgeServer } from '../src/bridge/server';
import type { WorkspaceRegistry } from '../src/protocol/schemas';

describe('voice habit api', () => {
  const servers: Array<ReturnType<typeof createBridgeServer>['server']> = [];

  afterEach(async () => {
    await Promise.all(
      servers.splice(0).map(
        (server) =>
          new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve()))),
      ),
    );
  });

  it('lists learned phrases', async () => {
    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'test_workspace',
      workspaces: [],
    };

    const { server, runtime } = createBridgeServer({
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

    runtime.voiceHabitStore.set('what did I break', 'summarize_diff', 2);

    const response = await fetch(`http://127.0.0.1:${address.port}/habits`, {
      headers: { Authorization: 'Bearer relay-secret' },
    });

    expect(response.status).toBe(200);
    const payload = (await response.json()) as { phrases: Array<{ phrase: string; intent: string }> };
    expect(payload.phrases).toHaveLength(1);
    expect(payload.phrases[0].phrase).toBe('what did i break');
    expect(payload.phrases[0].intent).toBe('summarize_diff');
  });

  it('creates a habit via POST', async () => {
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

    const response = await fetch(`http://127.0.0.1:${address.port}/habits`, {
      method: 'POST',
      headers: {
        Authorization: 'Bearer relay-secret',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        phrase: 'show me changes',
        intent: 'summarize_diff',
        confirmationCount: 1,
      }),
    });

    expect(response.status).toBe(200);
    const entry = (await response.json()) as { phrase: string; intent: string; confirmationCount: number };
    expect(entry.phrase).toBe('show me changes');
    expect(entry.intent).toBe('summarize_diff');
    expect(entry.confirmationCount).toBe(1);
  });

  it('deletes a habit', async () => {
    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'test_workspace',
      workspaces: [],
    };

    const { server, runtime } = createBridgeServer({
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

    runtime.voiceHabitStore.set('delete me', 'quick_status');

    const response = await fetch(`http://127.0.0.1:${address.port}/habits/delete%20me`, {
      method: 'DELETE',
      headers: { Authorization: 'Bearer relay-secret' },
    });

    expect(response.status).toBe(200);
    const payload = (await response.json()) as { deleted: boolean };
    expect(payload.deleted).toBe(true);
  });

  it('returns 404 when deleting unknown habit', async () => {
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

    const response = await fetch(`http://127.0.0.1:${address.port}/habits/missing`, {
      method: 'DELETE',
      headers: { Authorization: 'Bearer relay-secret' },
    });

    expect(response.status).toBe(404);
  });
});
