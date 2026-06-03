import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { EventRouter } from '../src/bridge/event-router';
import { SessionStore } from '../src/bridge/session-store';
import { AuditLog } from '../src/bridge/audit-log';
import { JarvisRuntime } from '../src/jarvis/runtime';
import { OutboxStore } from '../src/personalization/outbox-store';
import { VoiceHabitStore } from '../src/personalization/voice-habit-store';
import { loadWorkspaceRegistry } from '../src/policy/allowlists';
import type { WorkspaceRegistry, EarbudEvent } from '../src/protocol/schemas';

function tempFile(): string {
  return path.join(os.tmpdir(), `approval-outbox-test-${Date.now()}-${Math.random().toString(36).slice(2)}.json`);
}

function makeEvent(overrides: Partial<EarbudEvent> = {}): EarbudEvent {
  return {
    sessionId: 'test-session',
    source: 'android_relay',
    protocolVersion: 1,
    event: 'android_push_to_talk',
    utterance: 'push my changes',
    workspace: 'test_workspace',
    riskPolicy: {
      profile: 'default',
      allowReadOnly: true,
      allowSafeWithoutApproval: true,
      requireApprovalFor: ['push'],
      requireHardApprovalFor: [],
      approvalTimeoutMs: 5000,
    },
    hardwareContext: null,
    idempotencyKey: null,
    device: 'right_bud',
    wearState: 'in_ear',
    battery: 80,
    profile: null,
    ...overrides,
  } as EarbudEvent;
}

describe('approval outbox events', () => {
  let auditLog: AuditLog;
  let sessionStore: SessionStore;
  let outboxStore: OutboxStore;
  let voiceHabitStore: VoiceHabitStore;
  let router: EventRouter;
  let auditPath: string;
  let outboxPath: string;
  let habitPath: string;

  const registry: WorkspaceRegistry = {
    defaultWorkspaceId: 'test_workspace',
    workspaces: [
      {
        id: 'test_workspace',
        label: 'Test Workspace',
        rootPath: process.cwd(),
        allowedIntents: ['push', 'quick_status'],
        approvalRequiredIntents: ['push'],
        hardApprovalIntents: [],
        commands: {},
      },
    ],
  };

  beforeEach(() => {
    auditPath = tempFile();
    outboxPath = tempFile();
    habitPath = tempFile();
    auditLog = new AuditLog(auditPath);
    sessionStore = new SessionStore();
    outboxStore = new OutboxStore(outboxPath);
    voiceHabitStore = new VoiceHabitStore(habitPath);

    const jarvisRuntime = new JarvisRuntime(auditLog, async () => {
      // background completion noop
    });
    router = new EventRouter(registry, sessionStore, auditLog, jarvisRuntime, voiceHabitStore, outboxStore);
  });

  afterEach(() => {
    try { fs.unlinkSync(auditPath); } catch { /* ignore */ }
    try { fs.unlinkSync(outboxPath); } catch { /* ignore */ }
    try { fs.unlinkSync(habitPath); } catch { /* ignore */ }
  });

  it('enqueues approval_pending outbox event when approval is required', async () => {
    const event = makeEvent();
    const response = await router.dispatch(event);

    expect(response.requiresApproval).toBe(true);
    expect(response.status).toBe('blocked');

    const poll = outboxStore.poll('test-session');
    const approvalEvents = poll.events.filter((e) => e.kind === 'approval_pending');
    expect(approvalEvents.length).toBe(1);
    expect(approvalEvents[0].priority).toBe('high');
    expect(approvalEvents[0].summary).toContain('Push');
    expect(approvalEvents[0].actionId).toBe(response.actionId);
    expect(approvalEvents[0].detail).toContain('push');
  });

  it('enqueues approval_pending with critical priority for hard approval', async () => {
    const event = makeEvent({
      utterance: 'deploy to production',
    });

    // Make deploy an allowed intent
    const deployRegistry: WorkspaceRegistry = {
      defaultWorkspaceId: 'test_workspace',
      workspaces: [
        {
          id: 'test_workspace',
          label: 'Test Workspace',
          rootPath: process.cwd(),
          allowedIntents: ['deploy', 'quick_status'],
          approvalRequiredIntents: [],
          hardApprovalIntents: ['deploy'],
          commands: {},
        },
      ],
    };

    const jarvisRuntime = new JarvisRuntime(auditLog, async () => {});
    const deployRouter = new EventRouter(deployRegistry, sessionStore, auditLog, jarvisRuntime, voiceHabitStore, outboxStore);

    const response = await deployRouter.dispatch(event);
    expect(response.requiresApproval).toBe(true);

    const poll = outboxStore.poll('test-session');
    const approvalEvents = poll.events.filter((e) => e.kind === 'approval_pending');
    expect(approvalEvents.length).toBe(1);
    expect(approvalEvents[0].priority).toBe('critical');
  });

  it('enqueues completion event when approval is rejected', async () => {
    const event = makeEvent();
    const approvalResponse = await router.dispatch(event);
    expect(approvalResponse.requiresApproval).toBe(true);

    const rejectEvent = makeEvent({
      event: 'android_reject',
      pendingActionId: approvalResponse.actionId ?? undefined,
      utterance: undefined,
    });

    await router.dispatch(rejectEvent);

    const poll = outboxStore.poll('test-session');
    const completionEvents = poll.events.filter((e) => e.kind === 'completion_full_report');
    expect(completionEvents.length).toBe(1);
    expect(completionEvents[0].summary).toContain('rejected');
    expect(completionEvents[0].actionId).toBe(approvalResponse.actionId);
  });

  it('enqueues completion event when approval is approved', async () => {
    const event = makeEvent();

    const approvalResponse = await router.dispatch(event);
    expect(approvalResponse.requiresApproval).toBe(true);

    const approveEvent = makeEvent({
      event: 'android_approve',
      pendingActionId: approvalResponse.actionId ?? undefined,
      utterance: undefined,
    });

    await router.dispatch(approveEvent);

    const poll = outboxStore.poll('test-session');
    const completionEvents = poll.events.filter((e) => e.kind === 'completion_soft_ping' || e.kind === 'completion_full_report');
    expect(completionEvents.length).toBe(1);
    // The summary could be the push result or an error depending on test environment
    expect(completionEvents[0].summary).toBeTruthy();
  });

  it('includes actionId and riskClass in approval_pending detail for notification payload', async () => {
    const event = makeEvent();
    const response = await router.dispatch(event);
    expect(response.requiresApproval).toBe(true);

    const poll = outboxStore.poll('test-session');
    const approvalEvents = poll.events.filter((e) => e.kind === 'approval_pending');
    expect(approvalEvents.length).toBe(1);

    const approvalEvent = approvalEvents[0];
    expect(approvalEvent.actionId).toBe(response.actionId);
    expect(approvalEvent.expiresAtMs).toBeGreaterThan(Date.now());
    expect(approvalEvent.detail).toBeTruthy();

    const detail = JSON.parse(approvalEvent.detail ?? '{}');
    expect(detail.actionType).toBe('push');
    expect(detail.riskClass).toBe('approval_required');
    expect(detail.expiresInMs).toBeGreaterThan(0);
  });

  it('prevents session B from acking session A outbox event', async () => {
    const event = makeEvent();
    const response = await router.dispatch(event);
    expect(response.requiresApproval).toBe(true);

    const pollA = outboxStore.poll('test-session');
    const eventId = pollA.events[0].id;

    // Session B tries to ack session A's event
    const acked = outboxStore.ack(eventId, 'other-session');
    expect(acked).toBe(false);

    // Event should still be visible to session A
    const pollA2 = outboxStore.poll('test-session');
    expect(pollA2.events.length).toBeGreaterThan(0);
    expect(pollA2.events.some((e) => e.id === eventId)).toBe(true);
  });
});
