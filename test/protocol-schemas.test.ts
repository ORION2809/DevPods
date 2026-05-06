import { describe, expect, it } from 'vitest';
import { bridgeRequestSchema, earbudEventSchema, jarvisResponseSchema } from '../src/protocol/schemas';

describe('protocol schemas', () => {
  it('validates a simulated earbud event fixture', () => {
    const parsed = earbudEventSchema.parse({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_1',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'left_long_press',
      timestamp: Date.now(),
    });

    expect(parsed.sessionId).toBe('sess_1');
  });

  it('validates an android relay wake event fixture', () => {
    const parsed = earbudEventSchema.parse({
      source: 'android_relay',
      sessionId: 'android_sess_1',
      workspace: 'current_repo',
      device: 'both_buds',
      event: 'headset_button_single',
      timestamp: Date.now(),
      profile: 'default',
    });

    expect(parsed.source).toBe('android_relay');
    expect(parsed.event).toBe('headset_button_single');
  });

  it('validates a bridge request', () => {
    const parsed = bridgeRequestSchema.parse({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_2',
      workspace: 'current_repo',
      event: 'quick_status',
      utterance: null,
      gesture: 'left_long_press',
      riskPolicy: {
        profile: 'default',
        allowReadOnly: true,
        allowSafeWithoutApproval: true,
        requireApprovalFor: ['run_tests'],
        requireHardApprovalFor: ['deploy'],
        approvalTimeoutMs: 12000,
      },
      pendingActionId: null,
      approvalAction: null,
      deviceState: {
        activeBud: 'left',
        wearState: 'in_ear',
        batteryPercent: 70,
        profile: 'coding_mode',
      },
    });

    expect(parsed.event).toBe('quick_status');
  });

  it('validates a Jarvis response shape', () => {
    const parsed = jarvisResponseSchema.parse({
      speak: 'Feature audio bridge. Four files changed. No tests running.',
      display: 'Branch feature/audio-bridge. Four files changed. No tests are running.',
      requiresApproval: false,
      approvalRequest: null,
      actionId: null,
      status: 'completed',
      nextState: 'idle',
      followUpHint: null,
    });

    expect(parsed.speak.split(' ').length).toBeLessThanOrEqual(24);
  });

  it('validates a queued Jarvis response shape', () => {
    const parsed = jarvisResponseSchema.parse({
      speak: 'Queued run workspace tests. One task ahead.',
      display: 'Queued run workspace tests in C:/workspace. One task ahead.',
      requiresApproval: false,
      approvalRequest: null,
      actionId: 'action_123',
      status: 'acknowledged',
      nextState: 'queued',
      followUpHint: 'Queued behind 1 task ahead',
    });

    expect(parsed.nextState).toBe('queued');
  });
});