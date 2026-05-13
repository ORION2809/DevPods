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

  it('validates an android relay tap test event fixture', () => {
    const parsed = earbudEventSchema.parse({
      source: 'android_relay',
      sessionId: 'android_tap_test',
      workspace: 'current_repo',
      device: 'both_buds',
      event: 'tap_test_button',
      timestamp: Date.now(),
      profile: 'default',
    });

    expect(parsed.source).toBe('android_relay');
    expect(parsed.event).toBe('tap_test_button');
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

  it('validates a Jarvis response with autonomy instructions', () => {
    const parsed = jarvisResponseSchema.parse({
      speak: 'Tests finished successfully. Next I will refresh the repo status.',
      display: 'Tests finished successfully. Next step: refresh the repo status.',
      requiresApproval: false,
      approvalRequest: null,
      actionId: 'action_456',
      status: 'completed',
      nextState: 'idle',
      followUpHint: 'Double tap to interrupt or stay silent to continue',
      autonomy: {
        phase: 'report',
        mode: 'continue_on_silence',
        summary: 'Tests finished successfully.',
        nextStep: 'Refresh the repo status.',
        continueAfterMs: 4000,
        nextIntent: 'quick_status',
      },
    });

    expect(parsed.autonomy?.nextIntent).toBe('quick_status');
    expect(parsed.autonomy?.continueAfterMs).toBe(4000);
  });

  it('rejects an invalid autonomy mode', () => {
    expect(() => jarvisResponseSchema.parse({
      speak: 'Plan updated.',
      display: 'Plan updated.',
      requiresApproval: false,
      approvalRequest: null,
      actionId: null,
      status: 'acknowledged',
      nextState: 'idle',
      followUpHint: null,
      autonomy: {
        phase: 'plan',
        mode: 'invalid_mode',
        summary: 'Plan updated.',
        nextStep: 'Run workspace tests.',
        continueAfterMs: 4000,
        nextIntent: 'run_tests',
      },
    })).toThrow();
  });

  it('validates an earbud event with hardware context', () => {
    const parsed = earbudEventSchema.parse({
      source: 'android_relay',
      sessionId: 'android_sess_hw',
      workspace: 'current_repo',
      device: 'both_buds',
      event: 'headset_button_single',
      timestamp: Date.now(),
      hardwareContext: {
        provider: 'librepods_airpods',
        wakeSource: 'right_single_press',
        deviceConfidence: 'proven',
        earState: 'both_in_ear',
        batteryState: 'ok',
        deviceModel: 'AirPods Pro 2',
        connectionState: 'connected',
      },
    });

    expect(parsed.hardwareContext?.provider).toBe('librepods_airpods');
    expect(parsed.hardwareContext?.deviceConfidence).toBe('proven');
    expect(parsed.hardwareContext?.earState).toBe('both_in_ear');
  });

  it('defaults hardware context fields when omitted', () => {
    const parsed = earbudEventSchema.parse({
      source: 'android_relay',
      sessionId: 'android_sess_min',
      workspace: 'current_repo',
      device: 'both_buds',
      event: 'android_push_to_talk',
      timestamp: Date.now(),
    });

    expect(parsed.hardwareContext).toBeUndefined();
  });

  it('validates a bridge request with hardware context', () => {
    const parsed = bridgeRequestSchema.parse({
      source: 'android_relay',
      sessionId: 'sess_hw',
      workspace: 'current_repo',
      event: 'voice_command',
      utterance: 'run tests',
      gesture: 'headset_button_single',
      riskPolicy: {
        profile: 'default',
        allowReadOnly: true,
        allowSafeWithoutApproval: true,
        requireApprovalFor: [],
        requireHardApprovalFor: [],
        approvalTimeoutMs: 12000,
      },
      pendingActionId: null,
      approvalAction: null,
      deviceState: {
        activeBud: 'both',
        wearState: 'in_ear',
        batteryPercent: 80,
        profile: 'default',
      },
      hardwareContext: {
        provider: 'android_media_session',
        deviceConfidence: 'observed',
        earState: 'in_ear',
        batteryState: 'ok',
      },
    });

    expect(parsed.hardwareContext?.provider).toBe('android_media_session');
    expect(parsed.hardwareContext?.wakeSource).toBeNull();
  });
});