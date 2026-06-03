import { afterEach, describe, expect, it } from 'vitest';
import { createBridgeServer } from '../src/bridge/server';
import type { WorkspaceRegistry } from '../src/protocol/schemas';

describe('bridge outbox api', () => {
  const servers: Array<ReturnType<typeof createBridgeServer>['server']> = [];

  afterEach(async () => {
    await Promise.all(
      servers.splice(0).map(
        (server) =>
          new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve()))),
      ),
    );
  });

  it('polls outbox events for a session', async () => {
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

    const sessionId = 'test-session';
    runtime.outboxStore.enqueue({
      sessionId,
      priority: 'normal',
      kind: 'completion_soft_ping',
      summary: 'Tests finished',
      expiresAtMs: Date.now() + 3600_000,
    });

    const response = await fetch(`http://127.0.0.1:${address.port}/sessions/${sessionId}/outbox`, {
      headers: { Authorization: 'Bearer relay-secret' },
    });

    expect(response.status).toBe(200);
    const payload = (await response.json()) as { events: Array<{ summary: string }>; cursor: string };
    expect(payload.events).toHaveLength(1);
    expect(payload.events[0].summary).toBe('Tests finished');
    expect(typeof payload.cursor).toBe('string');
  });

  it('pages outbox events with cursor', async () => {
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

    const sessionId = 'cursor-session';
    const e1 = runtime.outboxStore.enqueue({
      sessionId,
      priority: 'normal',
      kind: 'completion_soft_ping',
      summary: 'First',
      expiresAtMs: Date.now() + 3600_000,
    });
    runtime.outboxStore.enqueue({
      sessionId,
      priority: 'normal',
      kind: 'completion_soft_ping',
      summary: 'Second',
      expiresAtMs: Date.now() + 3600_000,
    });

    const response1 = await fetch(`http://127.0.0.1:${address.port}/sessions/${sessionId}/outbox`, {
      headers: { Authorization: 'Bearer relay-secret' },
    });
    const payload1 = (await response1.json()) as { events: Array<{ summary: string }>; cursor: string };
    expect(payload1.events).toHaveLength(2);

    const response2 = await fetch(
      `http://127.0.0.1:${address.port}/sessions/${sessionId}/outbox?after=${e1.id}`,
      { headers: { Authorization: 'Bearer relay-secret' } },
    );
    const payload2 = (await response2.json()) as { events: Array<{ summary: string }>; cursor: string };
    expect(payload2.events).toHaveLength(1);
    expect(payload2.events[0].summary).toBe('Second');
  });

  it('acks an outbox event', async () => {
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

    const sessionId = 'ack-session';
    const event = runtime.outboxStore.enqueue({
      sessionId,
      priority: 'normal',
      kind: 'completion_soft_ping',
      summary: 'Ack me',
      expiresAtMs: Date.now() + 3600_000,
    });

    const ackResponse = await fetch(
      `http://127.0.0.1:${address.port}/sessions/${sessionId}/outbox/${event.id}/ack`,
      {
        method: 'POST',
        headers: { Authorization: 'Bearer relay-secret' },
      },
    );

    expect(ackResponse.status).toBe(200);
    const ackPayload = (await ackResponse.json()) as { acked: boolean };
    expect(ackPayload.acked).toBe(true);

    const pollResponse = await fetch(`http://127.0.0.1:${address.port}/sessions/${sessionId}/outbox`, {
      headers: { Authorization: 'Bearer relay-secret' },
    });
    const pollPayload = (await pollResponse.json()) as { events: unknown[] };
    expect(pollPayload.events).toHaveLength(0);
  });

  it('returns 404 when acking unknown event', async () => {
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

    const ackResponse = await fetch(
      `http://127.0.0.1:${address.port}/sessions/any/outbox/nonexistent/ack`,
      {
        method: 'POST',
        headers: { Authorization: 'Bearer relay-secret' },
      },
    );

    expect(ackResponse.status).toBe(404);
  });

  it('rejects outbox requests without authorization', async () => {
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

    const pollResponse = await fetch(`http://127.0.0.1:${address.port}/sessions/some/outbox`);
    expect(pollResponse.status).toBe(401);

    const ackResponse = await fetch(
      `http://127.0.0.1:${address.port}/sessions/some/outbox/ev/ack`,
      { method: 'POST' },
    );
    expect(ackResponse.status).toBe(401);
  });
});
