import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { OutboxStore } from '../src/personalization/outbox-store';

function tempFile(): string {
  return path.join(os.tmpdir(), `outbox-test-${Date.now()}-${Math.random().toString(36).slice(2)}.json`);
}

describe('OutboxStore', () => {
  let store: OutboxStore;
  let filePath: string;

  beforeEach(() => {
    filePath = tempFile();
    store = new OutboxStore(filePath);
  });

  afterEach(() => {
    try {
      fs.unlinkSync(filePath);
    } catch {
      // ignore
    }
  });

  it('enqueues and polls events', () => {
    const event = store.enqueue({
      sessionId: 'session-1',
      priority: 'normal',
      kind: 'completion_soft_ping',
      summary: 'Tests finished',
      expiresAtMs: Date.now() + 3600_000,
    });

    expect(event.id).toBeTruthy();
    expect(event.createdAtMs).toBeGreaterThan(0);

    const poll = store.poll('session-1');
    expect(poll.events).toHaveLength(1);
    expect(poll.events[0].summary).toBe('Tests finished');
    expect(poll.cursor).toBe(event.id);
  });

  it('acks events and removes them from poll', () => {
    const event = store.enqueue({
      sessionId: 'session-1',
      priority: 'normal',
      kind: 'completion_soft_ping',
      summary: 'Ack me',
      expiresAtMs: Date.now() + 3600_000,
    });

    expect(store.ack(event.id)).toBe(true);

    const poll = store.poll('session-1');
    expect(poll.events).toHaveLength(0);
  });

  it('returns false when acking unknown event', () => {
    expect(store.ack('unknown-id')).toBe(false);
  });

  it('pages with cursor', () => {
    const e1 = store.enqueue({
      sessionId: 'session-1',
      priority: 'normal',
      kind: 'completion_soft_ping',
      summary: 'First',
      expiresAtMs: Date.now() + 3600_000,
    });
    const e2 = store.enqueue({
      sessionId: 'session-1',
      priority: 'normal',
      kind: 'completion_soft_ping',
      summary: 'Second',
      expiresAtMs: Date.now() + 3600_000,
    });

    const poll1 = store.poll('session-1');
    expect(poll1.events).toHaveLength(2);

    const poll2 = store.poll('session-1', e1.id);
    expect(poll2.events).toHaveLength(1);
    expect(poll2.events[0].id).toBe(e2.id);
  });

  it('purges expired events', () => {
    store.enqueue({
      sessionId: 'session-1',
      priority: 'normal',
      kind: 'completion_soft_ping',
      summary: 'Expired',
      expiresAtMs: Date.now() - 1,
    });

    const poll = store.poll('session-1');
    expect(poll.events).toHaveLength(0);
  });

  it('isolates events by sessionId', () => {
    store.enqueue({
      sessionId: 'session-a',
      priority: 'normal',
      kind: 'completion_soft_ping',
      summary: 'A',
      expiresAtMs: Date.now() + 3600_000,
    });
    store.enqueue({
      sessionId: 'session-b',
      priority: 'normal',
      kind: 'completion_soft_ping',
      summary: 'B',
      expiresAtMs: Date.now() + 3600_000,
    });

    expect(store.poll('session-a').events).toHaveLength(1);
    expect(store.poll('session-b').events).toHaveLength(1);
  });

  it('persists and restores across instances', () => {
    const e1 = store.enqueue({
      sessionId: 'session-1',
      priority: 'normal',
      kind: 'completion_soft_ping',
      summary: 'Persisted',
      expiresAtMs: Date.now() + 3600_000,
    });
    store.ack(e1.id);

    // Create new instance pointing at same file
    const store2 = new OutboxStore(filePath);
    const poll = store2.poll('session-1');
    expect(poll.events).toHaveLength(0);
  });

  it('getPendingCount returns correct count', () => {
    store.enqueue({
      sessionId: 'session-1',
      priority: 'normal',
      kind: 'completion_soft_ping',
      summary: 'One',
      expiresAtMs: Date.now() + 3600_000,
    });
    store.enqueue({
      sessionId: 'session-1',
      priority: 'normal',
      kind: 'completion_soft_ping',
      summary: 'Two',
      expiresAtMs: Date.now() + 3600_000,
    });

    expect(store.getPendingCount('session-1')).toBe(2);
    expect(store.getPendingCount('other')).toBe(0);
  });
});
