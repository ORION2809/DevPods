import { describe, it, expect } from 'vitest';
import { SessionStore } from '../src/bridge/session-store';
import { AuditLog } from '../src/bridge/audit-log';
import { EventRouter } from '../src/bridge/event-router';
import { JarvisRuntime } from '../src/jarvis/runtime';
import { OutboxStore } from '../src/personalization/outbox-store';
import { VoiceHabitStore } from '../src/personalization/voice-habit-store';
import type { WorkspaceRegistry, EarbudEvent } from '../src/protocol/schemas';

const testRegistry: WorkspaceRegistry = {
  defaultWorkspaceId: 'test_workspace',
  workspaces: [
    {
      id: 'test_workspace',
      label: 'Test Workspace',
      rootPath: process.cwd(),
      allowedIntents: ['push', 'quick_status', 'run_tests'],
      approvalRequiredIntents: ['push', 'run_tests'],
      hardApprovalIntents: [],
      commands: {},
    },
  ],
};

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
      approvalTimeoutMs: 12000,
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

describe('Quick-start policy', () => {
  it('blocks approval-required intents when quick-start is enabled', async () => {
    const auditLog = new AuditLog('/dev/null');
    const sessionStore = new SessionStore();
    const outboxStore = new OutboxStore(':memory:');
    const voiceHabitStore = new VoiceHabitStore(':memory:');
    const jarvisRuntime = new JarvisRuntime(auditLog, async () => {});
    const router = new EventRouter(testRegistry, sessionStore, auditLog, jarvisRuntime, voiceHabitStore, outboxStore);

    sessionStore.enableQuickStart('test-session');

    const response = await router.dispatch(makeEvent());
    expect(response.status).toBe('blocked');
    expect(response.speak).toContain('requires full setup');
    expect(response.display).toContain('Quick-start mode');
  });

  it('allows immediate intents when quick-start is enabled', async () => {
    const auditLog = new AuditLog('/dev/null');
    const sessionStore = new SessionStore();
    const outboxStore = new OutboxStore(':memory:');
    const voiceHabitStore = new VoiceHabitStore(':memory:');
    const jarvisRuntime = new JarvisRuntime(auditLog, async () => {});
    const router = new EventRouter(testRegistry, sessionStore, auditLog, jarvisRuntime, voiceHabitStore, outboxStore);

    sessionStore.enableQuickStart('test-session');

    const response = await router.dispatch(makeEvent({ utterance: 'what is the status' }));
    expect(response.status).not.toBe('blocked');
  });

  it('allows all intents when quick-start is disabled', async () => {
    const auditLog = new AuditLog('/dev/null');
    const sessionStore = new SessionStore();
    const outboxStore = new OutboxStore(':memory:');
    const voiceHabitStore = new VoiceHabitStore(':memory:');
    const jarvisRuntime = new JarvisRuntime(auditLog, async () => {});
    const router = new EventRouter(testRegistry, sessionStore, auditLog, jarvisRuntime, voiceHabitStore, outboxStore);

    const response = await router.dispatch(makeEvent());
    expect(response.requiresApproval).toBe(true);
  });
});
