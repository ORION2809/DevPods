import fs from 'node:fs';
import path from 'node:path';
import type { z } from 'zod';
import {
  notificationPreferenceSchema,
  type NotificationPreference,
  type NotificationStyle,
  type OutboxEventKind,
} from '../protocol/schemas';

export interface DeliveryPolicy {
  speak: boolean;
  badge: boolean;
  tone: boolean;
  fullReport: boolean;
}

type NotificationPreferenceInput = z.input<typeof notificationPreferenceSchema>;

const DEFAULT_PREFERENCE: NotificationPreference = {
  sessionId: '_global',
  style: 'soft',
  badgeEnabled: true,
  mutedKinds: [],
  softPingTtsEnabled: true,
  nudgeTtsEnabled: true,
  reminderTtsEnabled: true,
  showSensitiveInNotifications: false,
  wearApprovalEnabled: true,
  updatedAtMs: Date.now(),
};

/**
 * Local per-session notification preferences.
 *
 * - Stored in a JSON file under runtime-data/.
 * - No cloud sync.
 * - Android can GET/SET preferences via bridge endpoints.
 */
export class NotificationPreferenceStore {
  private readonly preferences = new Map<string, NotificationPreference>();
  private readonly filePath: string | null;
  private saveTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(filePath?: string) {
    this.filePath = filePath ?? null;
    if (this.filePath) {
      this.loadFromDisk();
    }
  }

  getPreferences(sessionId: string): NotificationPreference {
    return this.preferences.get(sessionId) ?? { ...DEFAULT_PREFERENCE, sessionId };
  }

  setPreferences(preference: NotificationPreferenceInput): NotificationPreference {
    const validated = notificationPreferenceSchema.parse(preference);
    this.preferences.set(validated.sessionId, validated);
    this.scheduleSave();
    return validated;
  }

  resetPreferences(sessionId: string): NotificationPreference {
    const reset = { ...DEFAULT_PREFERENCE, sessionId };
    this.preferences.set(sessionId, reset);
    this.scheduleSave();
    return reset;
  }

  /** Synchronous flush for testing. */
  flush(): void {
    if (this.saveTimer) {
      clearTimeout(this.saveTimer);
      this.saveTimer = null;
    }
    this.saveToDisk();
  }

  /**
   * Compute delivery policy for an outbox event kind given a session's preferences.
   */
  getPolicy(sessionId: string, eventKind: OutboxEventKind): DeliveryPolicy {
    const prefs = this.getPreferences(sessionId);

    if (prefs.mutedKinds.includes(eventKind)) {
      return { speak: false, badge: prefs.badgeEnabled, tone: false, fullReport: false };
    }

    switch (prefs.style) {
      case 'aggressive':
        return {
          speak: eventKind !== 'badge_update',
          badge: prefs.badgeEnabled,
          tone: true,
          fullReport: eventKind === 'completion_full_report' || eventKind === 'approval_pending',
        };
      case 'soft':
        return {
          speak: this.isSoftSpeakEnabled(prefs, eventKind),
          badge: prefs.badgeEnabled,
          tone: eventKind === 'approval_pending' || eventKind === 'reminder_due',
          fullReport: eventKind === 'completion_full_report' || eventKind === 'approval_pending',
        };
      case 'silent_with_badge':
        return {
          speak: false,
          badge: prefs.badgeEnabled,
          tone: false,
          fullReport: false,
        };
      default:
        return { speak: false, badge: prefs.badgeEnabled, tone: false, fullReport: false };
    }
  }

  private isSoftSpeakEnabled(prefs: NotificationPreference, eventKind: OutboxEventKind): boolean {
    switch (eventKind) {
      case 'completion_soft_ping':
        return prefs.softPingTtsEnabled;
      case 'workspace_nudge':
        return prefs.nudgeTtsEnabled;
      case 'reminder_due':
        return prefs.reminderTtsEnabled;
      case 'approval_pending':
        return true;
      case 'completion_full_report':
        return true;
      case 'learning_prompt':
        return true;
      case 'badge_update':
        return false;
      default:
        return false;
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
      const snapshot = Object.fromEntries(this.preferences);
      fs.writeFileSync(this.filePath, `${JSON.stringify(snapshot, null, 2)}\n`, 'utf8');
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      process.stderr.write(`[notification-preference-store-error] save failed: ${message}\n`);
    }
  }

  private loadFromDisk(): void {
    if (!this.filePath) return;
    try {
      if (!fs.existsSync(this.filePath)) return;
      const raw = fs.readFileSync(this.filePath, 'utf8');
      if (!raw.trim()) return;
      const snapshot = JSON.parse(raw) as Record<string, unknown>;
      for (const [sessionId, value] of Object.entries(snapshot)) {
        const parsed = notificationPreferenceSchema.safeParse(value);
        if (parsed.success) {
          this.preferences.set(sessionId, parsed.data);
        }
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      process.stderr.write(`[notification-preference-store-error] load failed: ${message}\n`);
    }
  }
}
