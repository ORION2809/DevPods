import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { NotificationPreferenceStore } from '../src/personalization/notification-preference-store';

function tempFile(): string {
  return path.join(os.tmpdir(), `notification-prefs-test-${Date.now()}-${Math.random().toString(36).slice(2)}.json`);
}

describe('NotificationPreferenceStore', () => {
  let store: NotificationPreferenceStore;
  let filePath: string;

  beforeEach(() => {
    filePath = tempFile();
    store = new NotificationPreferenceStore(filePath);
  });

  afterEach(() => {
    try {
      fs.unlinkSync(filePath);
    } catch {
      // ignore
    }
  });

  it('returns default preferences for unknown session', () => {
    const prefs = store.getPreferences('new-session');
    expect(prefs.sessionId).toBe('new-session');
    expect(prefs.style).toBe('soft');
    expect(prefs.badgeEnabled).toBe(true);
    expect(prefs.mutedKinds).toEqual([]);
    expect(prefs.softPingTtsEnabled).toBe(true);
    expect(prefs.nudgeTtsEnabled).toBe(true);
    expect(prefs.reminderTtsEnabled).toBe(true);
    expect(prefs.wearApprovalEnabled).toBe(true);
  });

  it('stores and retrieves custom preferences', () => {
    const saved = store.setPreferences({
      sessionId: 'session-1',
      style: 'silent_with_badge',
      badgeEnabled: false,
      mutedKinds: ['workspace_nudge'],
      softPingTtsEnabled: false,
      nudgeTtsEnabled: true,
      reminderTtsEnabled: true,
      showSensitiveInNotifications: false,
      updatedAtMs: Date.now(),
    });

    expect(saved.style).toBe('silent_with_badge');

    const retrieved = store.getPreferences('session-1');
    expect(retrieved.style).toBe('silent_with_badge');
    expect(retrieved.badgeEnabled).toBe(false);
    expect(retrieved.mutedKinds).toEqual(['workspace_nudge']);
    expect(retrieved.softPingTtsEnabled).toBe(false);
  });

  it('persists and restores across instances', () => {
    store.setPreferences({
      sessionId: 'session-1',
      style: 'aggressive',
      badgeEnabled: true,
      mutedKinds: ['completion_soft_ping'],
      softPingTtsEnabled: true,
      nudgeTtsEnabled: false,
      reminderTtsEnabled: true,
      showSensitiveInNotifications: false,
      updatedAtMs: Date.now(),
    });
    store.flush();

    const store2 = new NotificationPreferenceStore(filePath);
    const prefs = store2.getPreferences('session-1');
    expect(prefs.style).toBe('aggressive');
    expect(prefs.mutedKinds).toEqual(['completion_soft_ping']);
  });

  it('computes soft policy correctly', () => {
    store.setPreferences({
      sessionId: 's',
      style: 'soft',
      badgeEnabled: true,
      mutedKinds: [],
      softPingTtsEnabled: true,
      nudgeTtsEnabled: false,
      reminderTtsEnabled: true,
      showSensitiveInNotifications: false,
      updatedAtMs: Date.now(),
    });

    expect(store.getPolicy('s', 'completion_soft_ping')).toEqual({
      speak: true, badge: true, tone: false, fullReport: false,
    });

    expect(store.getPolicy('s', 'workspace_nudge')).toEqual({
      speak: false, badge: true, tone: false, fullReport: false,
    });

    expect(store.getPolicy('s', 'reminder_due')).toEqual({
      speak: true, badge: true, tone: true, fullReport: false,
    });

    expect(store.getPolicy('s', 'approval_pending')).toEqual({
      speak: true, badge: true, tone: true, fullReport: true,
    });

    expect(store.getPolicy('s', 'badge_update')).toEqual({
      speak: false, badge: true, tone: false, fullReport: false,
    });
  });

  it('computes aggressive policy correctly', () => {
    store.setPreferences({
      sessionId: 's',
      style: 'aggressive',
      badgeEnabled: true,
      mutedKinds: [],
      softPingTtsEnabled: true,
      nudgeTtsEnabled: true,
      reminderTtsEnabled: true,
      showSensitiveInNotifications: false,
      updatedAtMs: Date.now(),
    });

    expect(store.getPolicy('s', 'completion_soft_ping')).toEqual({
      speak: true, badge: true, tone: true, fullReport: false,
    });

    expect(store.getPolicy('s', 'workspace_nudge')).toEqual({
      speak: true, badge: true, tone: true, fullReport: false,
    });
  });

  it('computes silent_with_badge policy correctly', () => {
    store.setPreferences({
      sessionId: 's',
      style: 'silent_with_badge',
      badgeEnabled: true,
      mutedKinds: [],
      softPingTtsEnabled: true,
      nudgeTtsEnabled: true,
      reminderTtsEnabled: true,
      showSensitiveInNotifications: false,
      updatedAtMs: Date.now(),
    });

    expect(store.getPolicy('s', 'completion_soft_ping')).toEqual({
      speak: false, badge: true, tone: false, fullReport: false,
    });
  });

  it('respects muted kinds across all styles', () => {
    store.setPreferences({
      sessionId: 's',
      style: 'aggressive',
      badgeEnabled: true,
      mutedKinds: ['workspace_nudge'],
      softPingTtsEnabled: true,
      nudgeTtsEnabled: true,
      reminderTtsEnabled: true,
      showSensitiveInNotifications: false,
      updatedAtMs: Date.now(),
    });

    expect(store.getPolicy('s', 'workspace_nudge')).toEqual({
      speak: false, badge: true, tone: false, fullReport: false,
    });
  });

  it('resets preferences to defaults', () => {
    store.setPreferences({
      sessionId: 's',
      style: 'aggressive',
      badgeEnabled: false,
      mutedKinds: ['workspace_nudge'],
      softPingTtsEnabled: false,
      nudgeTtsEnabled: false,
      reminderTtsEnabled: false,
      showSensitiveInNotifications: false,
      updatedAtMs: Date.now(),
    });

    const reset = store.resetPreferences('s');
    expect(reset.style).toBe('soft');
    expect(reset.badgeEnabled).toBe(true);
    expect(reset.mutedKinds).toEqual([]);
  });
});
