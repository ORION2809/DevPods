import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { ReminderStore } from '../src/personalization/reminder-store';
import { OutboxStore } from '../src/personalization/outbox-store';

function tempFile(): string {
  return path.join(os.tmpdir(), `reminder-test-${Date.now()}-${Math.random().toString(36).slice(2)}.json`);
}

describe('ReminderStore', () => {
  let store: ReminderStore;
  let outboxStore: OutboxStore;
  let filePath: string;
  let outboxFilePath: string;

  beforeEach(() => {
    filePath = tempFile();
    outboxFilePath = tempFile();
    store = new ReminderStore(filePath);
    outboxStore = new OutboxStore(outboxFilePath);
    store.setOutboxStore(outboxStore);
  });

  afterEach(() => {
    store.dispose();
    try {
      fs.unlinkSync(filePath);
      fs.unlinkSync(outboxFilePath);
    } catch {
      // ignore
    }
  });

  it('schedules a reminder and lists it', () => {
    const reminder = store.schedule('session-1', 'Check tests', Date.now() + 3600_000);
    expect(reminder.id).toMatch(/^rem_/);
    expect(reminder.summary).toBe('Check tests');

    const list = store.list('session-1');
    expect(list).toHaveLength(1);
    expect(list[0].summary).toBe('Check tests');
  });

  it('cancels a reminder', () => {
    const reminder = store.schedule('session-1', 'Cancel me', Date.now() + 3600_000);
    expect(store.cancel(reminder.id)).toBe(true);
    expect(store.list('session-1')).toHaveLength(0);
    expect(store.cancel('unknown')).toBe(false);
  });

  it('acks a reminder', () => {
    const reminder = store.schedule('session-1', 'Ack me', Date.now() + 3600_000);
    expect(store.ack(reminder.id)).toBe(true);
    expect(store.list('session-1')).toHaveLength(0);
    expect(store.ack('unknown')).toBe(false);
  });

  it('fires overdue reminder immediately', () => {
    const reminder = store.schedule('session-1', 'Overdue', Date.now() - 100);
    expect(reminder.id).toBeTruthy();

    // Should have fired and enqueued into outbox
    const outbox = outboxStore.poll('session-1');
    expect(outbox.events).toHaveLength(1);
    expect(outbox.events[0].kind).toBe('reminder_due');
    expect(outbox.events[0].summary).toBe('Overdue');
  });

  it('fires reminder after delay', async () => {
    store.schedule('session-1', 'Soon', Date.now() + 50);

    // Before delay
    expect(outboxStore.poll('session-1').events).toHaveLength(0);

    // Wait for timer
    await new Promise((resolve) => setTimeout(resolve, 150));

    const outbox = outboxStore.poll('session-1');
    expect(outbox.events).toHaveLength(1);
    expect(outbox.events[0].kind).toBe('reminder_due');
    expect(outbox.events[0].summary).toBe('Soon');
  });

  it('isolates reminders by session', () => {
    store.schedule('session-a', 'A', Date.now() + 3600_000);
    store.schedule('session-b', 'B', Date.now() + 3600_000);

    expect(store.list('session-a')).toHaveLength(1);
    expect(store.list('session-b')).toHaveLength(1);
    expect(store.list('session-a')[0].summary).toBe('A');
  });

  it('lists only unacked reminders', () => {
    const r1 = store.schedule('session-1', 'Active', Date.now() + 3600_000);
    const r2 = store.schedule('session-1', 'Acked', Date.now() + 3600_000);
    store.ack(r2.id);

    const list = store.list('session-1');
    expect(list).toHaveLength(1);
    expect(list[0].summary).toBe('Active');
  });

  it('persists and restores across instances', () => {
    const reminder = store.schedule('session-1', 'Persisted', Date.now() + 3600_000);
    store.flush();

    const store2 = new ReminderStore(filePath);
    const list = store2.list('session-1');
    expect(list).toHaveLength(1);
    expect(list[0].summary).toBe('Persisted');
    expect(list[0].id).toBe(reminder.id);
    store2.dispose();
  });

  it('prunes old acked reminders', () => {
    const reminder = store.schedule('session-1', 'Old', Date.now() + 3600_000);
    store.ack(reminder.id);

    // Manually set ackedAtMs to 8 days ago
    const old = { ...reminder, ackedAtMs: Date.now() - 8 * 86400_000 };
    (store as unknown as { reminders: Map<string, unknown> }).reminders.set(reminder.id, old);

    store.list('session-1'); // triggers prune
    expect(store.list('session-1')).toHaveLength(0);
  });

  it('recurring daily reminder reschedules after fire', async () => {
    const now = Date.now();
    store.schedule('session-1', 'Daily', now + 50, 'daily');

    await new Promise((resolve) => setTimeout(resolve, 150));

    // Should have fired and rescheduled
    const list = store.list('session-1');
    expect(list).toHaveLength(1);
    expect(list[0].dueAtMs).toBeGreaterThan(now + 86_400_000 - 1000);
  });

  it('recurring weekly reminder reschedules after fire', async () => {
    const now = Date.now();
    store.schedule('session-1', 'Weekly', now + 50, 'weekly');

    await new Promise((resolve) => setTimeout(resolve, 150));

    const list = store.list('session-1');
    expect(list).toHaveLength(1);
    expect(list[0].dueAtMs).toBeGreaterThan(now + 604_800_000 - 1000);
  });
});
