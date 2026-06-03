import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { reminderSchema, type Reminder } from '../protocol/schemas';
import type { OutboxStore } from './outbox-store';

/**
 * Local reminder store with timer-based firing into the outbox.
 *
 * - Reminders are kept in memory with optional file persistence.
 * - When a reminder fires, it is enqueued as `reminder_due` into the outbox.
 * - Acked reminders are removed on next prune.
 * - No cloud sync.
 */
export class ReminderStore {
  private readonly reminders = new Map<string, Reminder>();
  private readonly timers = new Map<string, ReturnType<typeof setTimeout>>();
  private readonly filePath: string | null;
  private saveTimer: ReturnType<typeof setTimeout> | null = null;
  private outboxStore: OutboxStore | null = null;

  constructor(filePath?: string) {
    this.filePath = filePath ?? null;
    if (this.filePath) {
      this.loadFromDisk();
    }
  }

  setOutboxStore(outboxStore: OutboxStore): void {
    this.outboxStore = outboxStore;
  }

  schedule(
    sessionId: string,
    summary: string,
    dueAtMs: number,
    recurring: 'none' | 'daily' | 'weekly' = 'none',
  ): Reminder {
    const reminder: Reminder = {
      id: `rem_${randomUUID().replace(/-/g, '').slice(0, 12)}`,
      sessionId,
      summary,
      createdAtMs: Date.now(),
      dueAtMs,
      recurring,
    };
    this.reminders.set(reminder.id, reminder);
    this.setTimer(reminder);
    this.scheduleSave();
    return reminder;
  }

  cancel(reminderId: string): boolean {
    const existing = this.reminders.get(reminderId);
    if (!existing) return false;
    this.reminders.delete(reminderId);
    this.clearTimer(reminderId);
    this.scheduleSave();
    return true;
  }

  ack(reminderId: string): boolean {
    const existing = this.reminders.get(reminderId);
    if (!existing) return false;
    const updated: Reminder = { ...existing, ackedAtMs: Date.now() };
    this.reminders.set(reminderId, updated);
    this.clearTimer(reminderId);
    this.scheduleSave();
    return true;
  }

  list(sessionId: string): Reminder[] {
    this.prune();
    return Array.from(this.reminders.values())
      .filter((r) => r.sessionId === sessionId && !r.ackedAtMs)
      .sort((a, b) => a.dueAtMs - b.dueAtMs);
  }

  listAll(): Reminder[] {
    this.prune();
    return Array.from(this.reminders.values())
      .filter((r) => !r.ackedAtMs)
      .sort((a, b) => a.dueAtMs - b.dueAtMs);
  }

  private setTimer(reminder: Reminder): void {
    if (reminder.ackedAtMs) return;
    const delay = reminder.dueAtMs - Date.now();
    if (delay <= 0) {
      this.fire(reminder);
      return;
    }
    this.clearTimer(reminder.id);
    const timer = setTimeout(() => this.fire(reminder), delay);
    if (typeof timer.unref === 'function') {
      timer.unref();
    }
    this.timers.set(reminder.id, timer);
  }

  private clearTimer(reminderId: string): void {
    const timer = this.timers.get(reminderId);
    if (timer) {
      clearTimeout(timer);
      this.timers.delete(reminderId);
    }
  }

  private fire(reminder: Reminder): void {
    this.clearTimer(reminder.id);
    this.outboxStore?.enqueue({
      sessionId: reminder.sessionId,
      priority: 'high',
      kind: 'reminder_due',
      summary: reminder.summary,
      expiresAtMs: Date.now() + 300_000, // 5 minute expiry for reminder delivery
    });
    if (reminder.recurring === 'daily') {
      const nextDue = reminder.dueAtMs + 86_400_000;
      const next: Reminder = { ...reminder, dueAtMs: nextDue };
      this.reminders.set(reminder.id, next);
      this.setTimer(next);
      this.scheduleSave();
    } else if (reminder.recurring === 'weekly') {
      const nextDue = reminder.dueAtMs + 604_800_000;
      const next: Reminder = { ...reminder, dueAtMs: nextDue };
      this.reminders.set(reminder.id, next);
      this.setTimer(next);
      this.scheduleSave();
    } else {
      // One-time: mark as fired by setting ackedAtMs
      const updated: Reminder = { ...reminder, ackedAtMs: Date.now() };
      this.reminders.set(reminder.id, updated);
      this.scheduleSave();
    }
  }

  private prune(): void {
    const now = Date.now();
    let changed = false;
    for (const [id, reminder] of this.reminders) {
      // Remove acked reminders older than 7 days
      if (reminder.ackedAtMs && reminder.ackedAtMs < now - 604_800_000) {
        this.reminders.delete(id);
        this.clearTimer(id);
        changed = true;
      }
    }
    if (changed) {
      this.scheduleSave();
    }
  }

  private scheduleSave(): void {
    if (!this.filePath) return;
    if (this.saveTimer) return;
    this.saveTimer = setTimeout(() => {
      this.saveTimer = null;
      this.saveToDisk();
    }, 500);
    if (typeof this.saveTimer.unref === 'function') {
      this.saveTimer.unref();
    }
  }

  private saveToDisk(): void {
    if (!this.filePath) return;
    try {
      fs.mkdirSync(path.dirname(this.filePath), { recursive: true });
      const snapshot = {
        reminders: Array.from(this.reminders.values()),
      };
      fs.writeFileSync(this.filePath, `${JSON.stringify(snapshot, null, 2)}\n`, 'utf8');
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      process.stderr.write(`[reminder-store-error] save failed: ${message}\n`);
    }
  }

  private loadFromDisk(): void {
    if (!this.filePath) return;
    try {
      if (!fs.existsSync(this.filePath)) return;
      const raw = fs.readFileSync(this.filePath, 'utf8');
      if (!raw.trim()) return;
      const snapshot = JSON.parse(raw) as { reminders: unknown[] };
      for (const item of snapshot.reminders) {
        const parsed = reminderSchema.safeParse(item);
        if (parsed.success) {
          this.reminders.set(parsed.data.id, parsed.data);
          if (!parsed.data.ackedAtMs) {
            this.setTimer(parsed.data);
          }
        }
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      process.stderr.write(`[reminder-store-error] load failed: ${message}\n`);
    }
  }

  /** Synchronous flush for testing. */
  flush(): void {
    if (this.saveTimer) {
      clearTimeout(this.saveTimer);
      this.saveTimer = null;
    }
    this.saveToDisk();
  }

  dispose(): void {
    for (const timer of this.timers.values()) {
      clearTimeout(timer);
    }
    this.timers.clear();
    if (this.saveTimer) {
      clearTimeout(this.saveTimer);
      this.saveTimer = null;
    }
  }
}
