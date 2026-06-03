import { afterEach, describe, expect, it } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { createBridgeServer } from '../src/bridge/server';
import type { WorkspaceRegistry } from '../src/protocol/schemas';

function tempFile(): string {
  return path.join(os.tmpdir(), `reminder-api-test-${Date.now()}-${Math.random().toString(36).slice(2)}.json`);
}

describe('reminder api', () => {
  const servers: Array<ReturnType<typeof createBridgeServer>['server']> = [];
  const tempFiles: string[] = [];

  afterEach(async () => {
    await Promise.all(
      servers.splice(0).map(
        (server) =>
          new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve()))),
      ),
    );
    for (const f of tempFiles.splice(0)) {
      try { fs.unlinkSync(f); } catch { /* ignore */ }
    }
  });

  it('creates and lists reminders', async () => {
    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'test_workspace',
      workspaces: [],
    };

    const reminderStorePath = tempFile();
    tempFiles.push(reminderStorePath);
    const { server } = createBridgeServer({
      registry,
      relayToken: 'relay-secret',
      pairingBaseUrl: 'http://127.0.0.1:4545',
      reminderStorePath,
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const sessionId = 'test-session';
    const createResponse = await fetch(
      `http://127.0.0.1:${address.port}/sessions/${sessionId}/reminders`,
      {
        method: 'POST',
        headers: {
          Authorization: 'Bearer relay-secret',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          summary: 'Check CI',
          dueAtMs: Date.now() + 3600_000,
          recurring: 'none',
        }),
      },
    );

    expect(createResponse.status).toBe(201);
    const reminder = (await createResponse.json()) as { id: string; summary: string; sessionId: string };
    expect(reminder.summary).toBe('Check CI');
    expect(reminder.sessionId).toBe(sessionId);

    const listResponse = await fetch(
      `http://127.0.0.1:${address.port}/sessions/${sessionId}/reminders`,
      { headers: { Authorization: 'Bearer relay-secret' } },
    );
    const listPayload = (await listResponse.json()) as { reminders: Array<{ summary: string }> };
    expect(listPayload.reminders).toHaveLength(1);
    expect(listPayload.reminders[0].summary).toBe('Check CI');
  });

  it('cancels a reminder', async () => {
    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'test_workspace',
      workspaces: [],
    };

    const reminderStorePath = tempFile();
    tempFiles.push(reminderStorePath);
    const { server, runtime } = createBridgeServer({
      registry,
      relayToken: 'relay-secret',
      pairingBaseUrl: 'http://127.0.0.1:4545',
      reminderStorePath,
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const reminder = runtime.reminderStore.schedule('session-1', 'Cancel me', Date.now() + 3600_000);

    const cancelResponse = await fetch(
      `http://127.0.0.1:${address.port}/sessions/session-1/reminders/${reminder.id}`,
      {
        method: 'DELETE',
        headers: { Authorization: 'Bearer relay-secret' },
      },
    );

    expect(cancelResponse.status).toBe(200);
    const payload = (await cancelResponse.json()) as { cancelled: boolean };
    expect(payload.cancelled).toBe(true);
  });

  it('returns 404 when cancelling unknown reminder', async () => {
    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'test_workspace',
      workspaces: [],
    };

    const reminderStorePath = tempFile();
    tempFiles.push(reminderStorePath);
    const { server } = createBridgeServer({
      registry,
      relayToken: 'relay-secret',
      pairingBaseUrl: 'http://127.0.0.1:4545',
      reminderStorePath,
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const response = await fetch(
      `http://127.0.0.1:${address.port}/sessions/session-1/reminders/nonexistent`,
      {
        method: 'DELETE',
        headers: { Authorization: 'Bearer relay-secret' },
      },
    );

    expect(response.status).toBe(404);
  });

  it('rejects invalid reminder payloads', async () => {
    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'test_workspace',
      workspaces: [],
    };

    const reminderStorePath = tempFile();
    tempFiles.push(reminderStorePath);
    const { server } = createBridgeServer({
      registry,
      relayToken: 'relay-secret',
      pairingBaseUrl: 'http://127.0.0.1:4545',
      reminderStorePath,
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const response = await fetch(
      `http://127.0.0.1:${address.port}/sessions/session-1/reminders`,
      {
        method: 'POST',
        headers: {
          Authorization: 'Bearer relay-secret',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ summary: '', dueAtMs: 'not-a-number' }),
      },
    );

    expect(response.status).toBe(400);
  });

  it('rejects reminder requests without authorization', async () => {
    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'test_workspace',
      workspaces: [],
    };

    const reminderStorePath = tempFile();
    tempFiles.push(reminderStorePath);
    const { server } = createBridgeServer({
      registry,
      relayToken: 'relay-secret',
      pairingBaseUrl: 'http://127.0.0.1:4545',
      reminderStorePath,
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const getResponse = await fetch(`http://127.0.0.1:${address.port}/sessions/some/reminders`);
    expect(getResponse.status).toBe(401);

    const postResponse = await fetch(
      `http://127.0.0.1:${address.port}/sessions/some/reminders`,
      { method: 'POST' },
    );
    expect(postResponse.status).toBe(401);
  });
});
