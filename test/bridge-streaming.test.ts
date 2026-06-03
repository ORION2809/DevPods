import { afterEach, describe, expect, it } from 'vitest';
import { createBridgeServer } from '../src/bridge/server';

describe('bridge streaming api', () => {
  const servers: Array<ReturnType<typeof createBridgeServer>['server']> = [];

  afterEach(async () => {
    await Promise.all(
      servers.splice(0).map(
        (server) =>
          new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve()))),
      ),
    );
  });

  it('returns NDJSON frames for a valid event', async () => {
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

    const response = await fetch(`http://127.0.0.1:${address.port}/events/stream`, {
      method: 'POST',
      headers: {
        Authorization: 'Bearer relay-secret',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        source: 'developer_earbuds_simulator',
        sessionId: 'stream-session',
        workspace: 'current_repo',
        device: 'right_bud',
        event: 'triple_tap_right',
        timestamp: Date.now(),
        utterance: 'what is the status',
      }),
    });

    expect(response.status).toBe(200);
    expect(response.headers.get('content-type')).toContain('application/x-ndjson');

    const text = await response.text();
    const lines = text.trim().split('\n').filter(Boolean);
    const frames = lines.map((line) => JSON.parse(line));

    expect(frames[0]).toEqual({ type: 'started' });
    expect(frames[1]).toMatchObject({ type: 'speak_delta', delta: 'Checking.' });
    expect(frames[2]).toMatchObject({ type: 'display_delta', delta: 'Checking.' });
    expect(frames[frames.length - 1]).toEqual({ type: 'done' });

    const finalResponseFrame = frames.find((f) => f.type === 'final_response');
    expect(finalResponseFrame).toBeTruthy();
    expect(finalResponseFrame.response.status).toBe('completed');
  });

  it('streams intent-neutral early copy for any utterance', async () => {
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

    const response = await fetch(`http://127.0.0.1:${address.port}/events/stream`, {
      method: 'POST',
      headers: {
        Authorization: 'Bearer relay-secret',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        source: 'developer_earbuds_simulator',
        sessionId: 'stream-session',
        workspace: 'current_repo',
        device: 'right_bud',
        event: 'triple_tap_right',
        timestamp: Date.now(),
        utterance: 'run the tests',
      }),
    });

    expect(response.status).toBe(200);
    const text = await response.text();
    const lines = text.trim().split('\n').filter(Boolean);
    const frames = lines.map((line) => JSON.parse(line));

    const speakDelta = frames.find((f) => f.type === 'speak_delta');
    expect(speakDelta.delta).toBe('Checking.');
  });

  it('exposes event_streaming in health features', async () => {
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

    const response = await fetch(`http://127.0.0.1:${address.port}/health`, {
      headers: { Authorization: 'Bearer relay-secret' },
    });

    expect(response.status).toBe(200);
    const payload = (await response.json()) as { features: string[] };
    expect(payload.features).toContain('event_streaming');
  });

  it('suppresses event_streaming and prefetch when features is empty', async () => {
    const { server } = createBridgeServer({
      relayToken: 'relay-secret',
      pairingBaseUrl: 'http://127.0.0.1:4545',
      features: [],
    });
    servers.push(server);
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (!address || typeof address === 'string') {
      throw new Error('Server address was not available.');
    }

    const response = await fetch(`http://127.0.0.1:${address.port}/health`, {
      headers: { Authorization: 'Bearer relay-secret' },
    });

    expect(response.status).toBe(200);
    const payload = (await response.json()) as { features: string[] };
    expect(payload.features).not.toContain('event_streaming');
    expect(payload.features).not.toContain('prefetch');
  });

  it('emits neutral early copy and approval frames for protected actions', async () => {
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

    const response = await fetch(`http://127.0.0.1:${address.port}/events/stream`, {
      method: 'POST',
      headers: {
        Authorization: 'Bearer relay-secret',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        source: 'developer_earbuds_simulator',
        sessionId: 'stream-session',
        workspace: 'current_repo',
        device: 'right_bud',
        event: 'triple_tap_right',
        timestamp: Date.now(),
        utterance: 'run the tests',
      }),
    });

    expect(response.status).toBe(200);
    expect(response.headers.get('content-type')).toContain('application/x-ndjson');

    const text = await response.text();
    const lines = text.trim().split('\n').filter(Boolean);
    const frames = lines.map((line) => JSON.parse(line));

    const speakDeltas = frames.filter((f) => f.type === 'speak_delta');
    const firstSpeakDelta = speakDeltas[0];
    expect(firstSpeakDelta.delta).toBe('Checking.');

    // No action-specific speech before approval is known
    const actionSpecificSpeech = speakDeltas.find((f) => f.delta.includes('Starting') || f.delta.includes('tests'));
    expect(actionSpecificSpeech).toBeUndefined();

    // Approval request must appear before final_response
    const approvalIndex = frames.findIndex((f) => f.type === 'approval_request');
    const finalIndex = frames.findIndex((f) => f.type === 'final_response');
    expect(approvalIndex).toBeGreaterThan(-1);
    expect(finalIndex).toBeGreaterThan(-1);
    expect(approvalIndex).toBeLessThan(finalIndex);

    // final_response must contain approval state
    const finalFrame = frames[finalIndex];
    expect(finalFrame.response.requiresApproval).toBe(true);
    expect(finalFrame.response.approvalRequest).toBeTruthy();
  });

  it('keeps legacy /events endpoint unchanged', async () => {
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

    const response = await fetch(`http://127.0.0.1:${address.port}/events`, {
      method: 'POST',
      headers: {
        Authorization: 'Bearer relay-secret',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        source: 'developer_earbuds_simulator',
        sessionId: 'legacy-session',
        workspace: 'current_repo',
        device: 'right_bud',
        event: 'triple_tap_right',
        timestamp: Date.now(),
      }),
    });

    expect(response.status).toBe(200);
    expect(response.headers.get('content-type')).toContain('application/json');
    const payload = (await response.json()) as { status: string };
    expect(payload.status).toBe('acknowledged');
  });
});
