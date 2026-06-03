import { afterEach, describe, expect, it } from 'vitest';
import { createBridgeServer } from '../src/bridge/server';
import type { WorkspaceRegistry } from '../src/protocol/schemas';

describe('bridge prefetch api', () => {
  const servers: Array<ReturnType<typeof createBridgeServer>['server']> = [];

  afterEach(async () => {
    await Promise.all(
      servers.splice(0).map(
        (server) =>
          new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve()))),
      ),
    );
  });

  it('returns 202 for a valid prefetch request with auth', async () => {
    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'test_workspace',
      workspaces: [
        {
          id: 'test_workspace',
          label: 'Test',
          rootPath: process.cwd(),
          allowedIntents: ['quick_status'],
          approvalRequiredIntents: [],
          hardApprovalIntents: [],
          commands: {},
        },
      ],
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
      `http://127.0.0.1:${address.port}/sessions/sess-1/workspaces/test_workspace/prefetch`,
      {
        method: 'POST',
        headers: {
          Authorization: 'Bearer relay-secret',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          kinds: ['workspace_status'],
          idempotencyKey: 'prefetch-1',
        }),
      },
    );

    expect(response.status).toBe(202);
    const payload = (await response.json()) as { accepted: boolean };
    expect(payload.accepted).toBe(true);
  });

  it('rejects prefetch without authorization', async () => {
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
      `http://127.0.0.1:${address.port}/sessions/sess-1/workspaces/test_workspace/prefetch`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          kinds: ['workspace_status'],
          idempotencyKey: 'prefetch-2',
        }),
      },
    );

    expect(response.status).toBe(401);
  });

  it('rejects invalid prefetch payload', async () => {
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
      `http://127.0.0.1:${address.port}/sessions/sess-1/workspaces/test_workspace/prefetch`,
      {
        method: 'POST',
        headers: {
          Authorization: 'Bearer relay-secret',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ kinds: 'invalid' }),
      },
    );

    expect(response.status).toBe(400);
  });

  it('exposes cache hit/miss telemetry in health', async () => {
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

    // Trigger a miss
    runtime.workspaceSnapshotCache.getTelemetry();

    const response = await fetch(`http://127.0.0.1:${address.port}/health`, {
      headers: { Authorization: 'Bearer relay-secret' },
    });

    expect(response.status).toBe(200);
    const payload = (await response.json()) as {
      cache_hit_miss_telemetry: { hits: number; misses: number };
    };
    expect(payload.cache_hit_miss_telemetry).toBeDefined();
    expect(typeof payload.cache_hit_miss_telemetry.hits).toBe('number');
    expect(typeof payload.cache_hit_miss_telemetry.misses).toBe('number');
  });
});
