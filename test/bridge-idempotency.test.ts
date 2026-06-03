import { afterEach, describe, expect, it } from 'vitest';
import { createBridgeServer } from '../src/bridge/server';
import { IdempotencyStore } from '../src/bridge/session-store';

describe('bridge idempotency', () => {
  const servers: Array<ReturnType<typeof createBridgeServer>['server']> = [];

  afterEach(async () => {
    await Promise.all(
      servers.splice(0).map(
        (server) =>
          new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve()))),
      ),
    );
  });

  it('accepts an android relay event with required protocolVersion and idempotencyKey', async () => {
    const { server } = createBridgeServer({});
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const response = await fetch(`http://127.0.0.1:${address.port}/events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        source: 'android_relay',
        sessionId: 'test-session',
        workspace: 'current_repo',
        device: 'both_buds',
        event: 'android_push_to_talk',
        timestamp: Date.now(),
        protocolVersion: '1',
        idempotencyKey: 'idem-test-session-1',
      }),
    });

    expect(response.status).toBe(200);
    const payload = (await response.json()) as { status: string };
    expect(payload.status).toBe('acknowledged');
  });

  it('accepts protocolVersion "1.0" from Android relay', async () => {
    const { server } = createBridgeServer({});
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const response = await fetch(`http://127.0.0.1:${address.port}/events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        source: 'android_relay',
        sessionId: 'test-session',
        workspace: 'current_repo',
        device: 'both_buds',
        event: 'android_push_to_talk',
        timestamp: Date.now(),
        protocolVersion: '1.0',
        idempotencyKey: 'idem-test-session-1.0',
      }),
    });

    expect(response.status).toBe(200);
    const payload = (await response.json()) as { status: string };
    expect(payload.status).toBe('acknowledged');
  });

  it('rejects an android relay event missing protocolVersion', async () => {
    const { server } = createBridgeServer({});
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const response = await fetch(`http://127.0.0.1:${address.port}/events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        source: 'android_relay',
        sessionId: 'test-session',
        workspace: 'current_repo',
        device: 'both_buds',
        event: 'android_push_to_talk',
        timestamp: Date.now(),
        idempotencyKey: 'idem-test-session-2',
      }),
    });

    expect(response.status).toBe(400);
    const payload = (await response.json()) as { error: string };
    expect(payload.error).toContain('Invalid event payload');
  });

  it('rejects an android relay event missing idempotencyKey', async () => {
    const { server } = createBridgeServer({});
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const response = await fetch(`http://127.0.0.1:${address.port}/events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        source: 'android_relay',
        sessionId: 'test-session',
        workspace: 'current_repo',
        device: 'both_buds',
        event: 'android_push_to_talk',
        timestamp: Date.now(),
        protocolVersion: '1',
      }),
    });

    expect(response.status).toBe(400);
    const payload = (await response.json()) as { error: string };
    expect(payload.error).toContain('Invalid event payload');
  });

  it('rejects an unsupported protocol version for android relay events', async () => {
    const { server } = createBridgeServer({});
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const response = await fetch(`http://127.0.0.1:${address.port}/events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        source: 'android_relay',
        sessionId: 'test-session',
        workspace: 'current_repo',
        device: 'both_buds',
        event: 'android_push_to_talk',
        timestamp: Date.now(),
        protocolVersion: '99',
        idempotencyKey: 'idem-test-session-3',
      }),
    });

    expect(response.status).toBe(400);
    const payload = (await response.json()) as { error: string };
    expect(payload.error).toContain('Unsupported protocol version');
  });

  it('accepts a developer earbuds simulator event without protocolVersion or idempotencyKey', async () => {
    const { server } = createBridgeServer({});
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const response = await fetch(`http://127.0.0.1:${address.port}/events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        source: 'developer_earbuds_simulator',
        sessionId: 'test-session',
        workspace: 'current_repo',
        device: 'both_buds',
        event: 'android_push_to_talk',
        timestamp: Date.now(),
      }),
    });

    expect(response.status).toBe(200);
    const payload = (await response.json()) as { status: string };
    expect(payload.status).toBe('acknowledged');
  });

  it('accepts a duplicate event when no idempotency key is provided (simulator)', async () => {
    const { server } = createBridgeServer({});
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const body = JSON.stringify({
      source: 'developer_earbuds_simulator',
      sessionId: 'test-session',
      workspace: 'current_repo',
      device: 'both_buds',
      event: 'android_push_to_talk',
      timestamp: Date.now(),
    });

    const first = await fetch(`http://127.0.0.1:${address.port}/events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
    });
    expect(first.status).toBe(200);

    const second = await fetch(`http://127.0.0.1:${address.port}/events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
    });
    expect(second.status).toBe(200);
  });

  it('deduplicates android relay events with the same idempotency key within the same session', async () => {
    const { server } = createBridgeServer({});
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const body = JSON.stringify({
      source: 'android_relay',
      sessionId: 'test-session',
      workspace: 'current_repo',
      device: 'both_buds',
      event: 'android_push_to_talk',
      timestamp: Date.now(),
      protocolVersion: '1',
      idempotencyKey: 'idem-test-session-12345',
    });

    const first = await fetch(`http://127.0.0.1:${address.port}/events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
    });
    expect(first.status).toBe(200);
    const firstPayload = await first.json();
    expect(firstPayload).toMatchObject({
      status: 'acknowledged',
      nextState: 'listening',
      requiresApproval: false,
    });
    expect(firstPayload).not.toHaveProperty('idempotent');

    const second = await fetch(`http://127.0.0.1:${address.port}/events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
    });
    expect(second.status).toBe(200);
    const secondPayload = await second.json();
    expect(secondPayload).toEqual(firstPayload);
  });

  it('allows the same idempotency key from different sessions (android relay)', async () => {
    const { server } = createBridgeServer({});
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const bodyA = JSON.stringify({
      source: 'android_relay',
      sessionId: 'session-a',
      workspace: 'current_repo',
      device: 'both_buds',
      event: 'android_push_to_talk',
      timestamp: Date.now(),
      protocolVersion: '1',
      idempotencyKey: 'shared-key-123',
    });

    const bodyB = JSON.stringify({
      source: 'android_relay',
      sessionId: 'session-b',
      workspace: 'current_repo',
      device: 'both_buds',
      event: 'android_push_to_talk',
      timestamp: Date.now(),
      protocolVersion: '1',
      idempotencyKey: 'shared-key-123',
    });

    const first = await fetch(`http://127.0.0.1:${address.port}/events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: bodyA,
    });
    expect(first.status).toBe(200);

    const second = await fetch(`http://127.0.0.1:${address.port}/events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: bodyB,
    });
    expect(second.status).toBe(200);
    const secondPayload = (await second.json()) as { idempotent?: boolean };
    expect(secondPayload.idempotent).toBeUndefined();
  });
});

describe('IdempotencyStore', () => {
  it('replays a completed response without rerunning the producer', async () => {
    const store = new IdempotencyStore<string>();
    let callCount = 0;

    const first = await store.executeOnce('key-1', async () => {
      callCount += 1;
      return 'value-1';
    });

    const second = await store.executeOnce('key-1', async () => {
      callCount += 1;
      return 'value-2';
    });

    expect(first).toEqual({ response: 'value-1', replayed: false });
    expect(second).toEqual({ response: 'value-1', replayed: true });
    expect(callCount).toBe(1);
  });

  it('reuses the first in-flight response for concurrent duplicates', async () => {
    const store = new IdempotencyStore<string>();
    let callCount = 0;
    let release!: (value: string) => void;
    const pendingResponse = new Promise<string>((resolve) => {
      release = resolve;
    });

    const first = store.executeOnce('key-1', async () => {
      callCount += 1;
      return pendingResponse;
    });
    const second = store.executeOnce('key-1', async () => {
      callCount += 1;
      return 'value-2';
    });

    expect(callCount).toBe(1);
    release('value-1');

    await expect(first).resolves.toEqual({ response: 'value-1', replayed: false });
    await expect(second).resolves.toEqual({ response: 'value-1', replayed: true });
  });
});
