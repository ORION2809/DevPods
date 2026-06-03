import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { EventRouter } from '../src/bridge/event-router';
import { SessionStore } from '../src/bridge/session-store';
import { AuditLog } from '../src/bridge/audit-log';
import { JarvisRuntime } from '../src/jarvis/runtime';
import { OutboxStore } from '../src/personalization/outbox-store';
import { ReminderStore } from '../src/personalization/reminder-store';
import { resolveIntent } from '../src/jarvis/router';
import type { WorkspaceRegistry, EarbudEvent } from '../src/protocol/schemas';

function tempFile(): string {
  return path.join(os.tmpdir(), `reminder-voice-test-${Date.now()}-${Math.random().toString(36).slice(2)}.json`);
}

let workspaceRoot: string;

function makeRegistry(): WorkspaceRegistry {
  return {
    defaultWorkspaceId: 'test_workspace',
    workspaces: [
      {
        id: 'test_workspace',
        label: 'Test Workspace',
        rootPath: workspaceRoot,
        allowedIntents: ['quick_status', 'create_reminder'],
        approvalRequiredIntents: [],
        hardApprovalIntents: [],
        commands: {},
      },
    ],
  };
}

describe('reminder voice flow', () => {
  let auditLogPath: string;
  let outboxPath: string;
  let reminderPath: string;
  let router: EventRouter;
  let reminderStore: ReminderStore;

  beforeEach(() => {
    workspaceRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reminder-voice-ws-'));
    auditLogPath = tempFile();
    outboxPath = tempFile();
    reminderPath = tempFile();
    const sessionStore = new SessionStore();
    const auditLog = new AuditLog(auditLogPath);
    const jarvisRuntime = new JarvisRuntime(auditLog);
    const outboxStore = new OutboxStore(outboxPath);
    reminderStore = new ReminderStore(reminderPath);
    reminderStore.setOutboxStore(outboxStore);
    router = new EventRouter(makeRegistry(), sessionStore, auditLog, jarvisRuntime, null, outboxStore, reminderStore);
  });

  afterEach(() => {
    reminderStore.dispose();
    try { fs.unlinkSync(auditLogPath); } catch { /* ignore */ }
    try { fs.unlinkSync(outboxPath); } catch { /* ignore */ }
    try { fs.unlinkSync(reminderPath); } catch { /* ignore */ }
    try { fs.rmSync(workspaceRoot, { recursive: true }); } catch { /* ignore */ }
  });

  it('routes "remind me" utterances to create_reminder intent', () => {
    expect(resolveIntent({ event: 'voice_command', utterance: 'remind me to check CI in 5 minutes' } as any)).toBe('create_reminder');
    expect(resolveIntent({ event: 'voice_command', utterance: 'remind me later' } as any)).toBe('create_reminder');
    expect(resolveIntent({ event: 'voice_command', utterance: 'remind me in an hour' } as any)).toBe('create_reminder');
  });

  it('creates a reminder and confirms with spoken response', async () => {
    const event: EarbudEvent = {
      source: 'android_relay',
      sessionId: 'test-session',
      workspace: 'test_workspace',
      device: 'both_buds',
      event: 'android_push_to_talk',
      timestamp: Date.now(),
      utterance: 'remind me to check CI in 5 minutes',
    };

    const response = await router.dispatch(event);

    expect(response.status).toBe('acknowledged');
    expect(response.speak).toContain('Reminder set');
    expect(response.speak).toContain('5 minutes');
    expect(response.nextState).toBe('idle');

    const reminders = reminderStore.list('test-session');
    expect(reminders.length).toBe(1);
    expect(reminders[0].summary).toBe('check CI');
    expect(reminders[0].dueAtMs).toBeGreaterThan(Date.now() + 4 * 60_000);
    expect(reminders[0].dueAtMs).toBeLessThanOrEqual(Date.now() + 6 * 60_000);
  });

  it('defaults to 5 minutes when no duration is specified', async () => {
    const event: EarbudEvent = {
      source: 'android_relay',
      sessionId: 'test-session',
      workspace: 'test_workspace',
      device: 'both_buds',
      event: 'android_push_to_talk',
      timestamp: Date.now(),
      utterance: 'remind me to stand up',
    };

    const response = await router.dispatch(event);

    expect(response.status).toBe('acknowledged');
    expect(response.speak).toContain('5 minutes');

    const reminders = reminderStore.list('test-session');
    expect(reminders.length).toBe(1);
    expect(reminders[0].summary).toBe('stand up');
  });

  it('parses "later" as 15 minutes', async () => {
    const event: EarbudEvent = {
      source: 'android_relay',
      sessionId: 'test-session',
      workspace: 'test_workspace',
      device: 'both_buds',
      event: 'android_push_to_talk',
      timestamp: Date.now(),
      utterance: 'remind me later about the meeting',
    };

    const response = await router.dispatch(event);
    expect(response.speak).toContain('15 minutes');

    const reminders = reminderStore.list('test-session');
    expect(reminders[0].dueAtMs).toBeGreaterThan(Date.now() + 14 * 60_000);
    expect(reminders[0].dueAtMs).toBeLessThanOrEqual(Date.now() + 16 * 60_000);
  });

  it('parses hours correctly', async () => {
    const event: EarbudEvent = {
      source: 'android_relay',
      sessionId: 'test-session',
      workspace: 'test_workspace',
      device: 'both_buds',
      event: 'android_push_to_talk',
      timestamp: Date.now(),
      utterance: 'remind me to review PRs in 2 hours',
    };

    const response = await router.dispatch(event);
    expect(response.speak).toContain('120 minutes');

    const reminders = reminderStore.list('test-session');
    expect(reminders[0].dueAtMs).toBeGreaterThan(Date.now() + 119 * 60_000);
    expect(reminders[0].dueAtMs).toBeLessThanOrEqual(Date.now() + 121 * 60_000);
  });

  it('defers the last completion when saying "remind me later" after a soft ping', async () => {
    // First, execute a command that sets completion context
    const statusEvent: EarbudEvent = {
      source: 'android_relay',
      sessionId: 'test-session',
      workspace: 'test_workspace',
      device: 'both_buds',
      event: 'android_status_shortcut',
      timestamp: Date.now(),
    };

    const statusResponse = await router.dispatch(statusEvent);
    expect(statusResponse.status).toBe('completed');
    expect(statusResponse.followUpHint).toContain('remind me later');

    // Now say "remind me later" — should defer the last completion
    const deferEvent: EarbudEvent = {
      source: 'android_relay',
      sessionId: 'test-session',
      workspace: 'test_workspace',
      device: 'both_buds',
      event: 'android_push_to_talk',
      timestamp: Date.now(),
      utterance: 'remind me later',
    };

    const deferResponse = await router.dispatch(deferEvent);
    expect(deferResponse.status).toBe('acknowledged');
    expect(deferResponse.speak).toContain('15 minutes');

    const reminders = reminderStore.list('test-session');
    expect(reminders.length).toBe(1);
    // The reminder summary should be the last completion speak text
    expect(reminders[0].summary).toBe(statusResponse.speak);
  });

  it('creates a generic reminder when no completion context exists', async () => {
    const event: EarbudEvent = {
      source: 'android_relay',
      sessionId: 'test-session',
      workspace: 'test_workspace',
      device: 'both_buds',
      event: 'android_push_to_talk',
      timestamp: Date.now(),
      utterance: 'remind me later',
    };

    const response = await router.dispatch(event);
    expect(response.status).toBe('acknowledged');

    const reminders = reminderStore.list('test-session');
    expect(reminders.length).toBe(1);
    expect(reminders[0].summary).toBe('Reminder');
  });
});
