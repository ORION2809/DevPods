import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { outboxEventSchema, type OutboxEvent } from '../protocol/schemas';

export interface OutboxPollResult {
  events: OutboxEvent[];
  cursor: string;
}

/**
 * Local outbox for async events that Android can poll or long-poll.
 *
 * - Events are kept in memory with optional file persistence.
 * - Android acks events after presentation.
 * - Expired events are purged on poll and on a timer.
 * - No cloud sync.
 */
export class OutboxStore {
  private readonly events = new Map<string, OutboxEvent>();
  private readonly acked = new Set<string>();
  private readonly sessionCursors = new Map<string, string>();
  private readonly filePath: string | null;
  private saveTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(filePath?: string) {
    this.filePath = filePath ?? null;
    if (this.filePath) {
      this.loadFromDisk();
    }
    this.startPurgeTimer();
  }

  enqueue(event: Omit<OutboxEvent, 'id' | 'createdAtMs'>): OutboxEvent {
    const fullEvent: OutboxEvent = {
      ...event,
      id: `obx_${randomUUID().replace(/-/g, '').slice(0, 12)}`,
      createdAtMs: Date.now(),
    };
    this.events.set(fullEvent.id, fullEvent);
    this.scheduleSave();
    return fullEvent;
  }

  poll(sessionId: string, afterCursor?: string): OutboxPollResult {
    this.purgeExpired();

    const allEvents = Array.from(this.events.values())
      .filter((e) => e.sessionId === sessionId)
      .filter((e) => !this.acked.has(e.id))
      .sort((a, b) => a.createdAtMs - b.createdAtMs);

    let startIndex = 0;
    if (afterCursor) {
      const cursorIndex = allEvents.findIndex((e) => e.id === afterCursor);
      if (cursorIndex !== -1) {
        startIndex = cursorIndex + 1;
      }
    }

    const page = allEvents.slice(startIndex, startIndex + 50);
    const cursor = page.length > 0 ? page[page.length - 1].id : afterCursor ?? '';
    this.sessionCursors.set(sessionId, cursor);

    return { events: page, cursor };
  }

  ack(eventId: string, sessionId?: string): boolean {
    const event = this.events.get(eventId);
    if (!event) {
      return false;
    }
    if (sessionId !== undefined && event.sessionId !== sessionId) {
      return false;
    }
    this.acked.add(eventId);
    this.events.delete(eventId);
    this.scheduleSave();
    return true;
  }

  ackAll(sessionId: string): number {
    let count = 0;
    for (const [id, event] of this.events) {
      if (event.sessionId === sessionId) {
        this.acked.add(id);
        this.events.delete(id);
        count++;
      }
    }
    if (count > 0) {
      this.scheduleSave();
    }
    return count;
  }

  getPendingCount(sessionId: string): number {
    let count = 0;
    for (const event of this.events.values()) {
      if (event.sessionId === sessionId && !this.acked.has(event.id)) {
        count++;
      }
    }
    return count;
  }

  getTotalPendingCount(): number {
    let count = 0;
    for (const event of this.events.values()) {
      if (!this.acked.has(event.id)) {
        count++;
      }
    }
    return count;
  }

  getAllSessionIds(): string[] {
    const ids = new Set<string>();
    for (const event of this.events.values()) {
      ids.add(event.sessionId);
    }
    for (const sessionId of this.sessionCursors.keys()) {
      ids.add(sessionId);
    }
    return Array.from(ids);
  }

  private purgeExpired(): void {
    const now = Date.now();
    let changed = false;
    for (const [id, event] of this.events) {
      if (event.expiresAtMs <= now) {
        this.events.delete(id);
        changed = true;
      }
    }
    // Prune acked tombstones older than 24 hours to prevent unbounded growth
    const ACK_RETENTION_MS = 24 * 60 * 60 * 1000;
    for (const id of this.acked) {
      // Only prune if the event no longer exists (already deleted)
      if (!this.events.has(id)) {
        // We can't know the age of a pure tombstone; prune all tombstones
        // once we exceed a threshold, or use a simple heuristic.
        // For now, prune aggressively since acked events are already delivered.
        this.acked.delete(id);
        changed = true;
      }
    }
    if (this.acked.size > 1000) {
      // Hard cap: keep only the most recent 1000 acked IDs
      const toRemove = this.acked.size - 1000;
      const iter = this.acked.values();
      for (let i = 0; i < toRemove; i++) {
        const id = iter.next().value;
        if (id) this.acked.delete(id);
      }
      changed = true;
    }
    if (changed) {
      this.scheduleSave();
    }
  }

  private startPurgeTimer(): void {
    const interval = setInterval(() => this.purgeExpired(), 60_000);
    // Prevent timer from keeping process alive in tests
    if (typeof interval.unref === 'function') {
      interval.unref();
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
        events: Array.from(this.events.values()),
        acked: Array.from(this.acked),
        cursors: Object.fromEntries(this.sessionCursors),
      };
      fs.writeFileSync(this.filePath, `${JSON.stringify(snapshot)}\n`, 'utf8');
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      process.stderr.write(`[outbox-store-error] save failed: ${message}\n`);
    }
  }

  private loadFromDisk(): void {
    if (!this.filePath) return;
    try {
      if (!fs.existsSync(this.filePath)) return;
      const raw = fs.readFileSync(this.filePath, 'utf8');
      if (!raw.trim()) return;
      const snapshot = JSON.parse(raw) as {
        events: unknown[];
        acked?: string[];
        cursors?: Record<string, string>;
      };
      for (const item of snapshot.events) {
        const parsed = outboxEventSchema.safeParse(item);
        if (parsed.success) {
          this.events.set(parsed.data.id, parsed.data);
        }
      }
      if (snapshot.acked) {
        for (const id of snapshot.acked) {
          this.acked.add(id);
          this.events.delete(id);
        }
      }
      if (snapshot.cursors) {
        for (const [sessionId, cursor] of Object.entries(snapshot.cursors)) {
          this.sessionCursors.set(sessionId, cursor);
        }
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      process.stderr.write(`[outbox-store-error] load failed: ${message}\n`);
    }
  }
}
