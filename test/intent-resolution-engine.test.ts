import { describe, it, expect } from 'vitest';
import { resolveIntentWithEngine } from '../src/jarvis/intent-resolution-engine';
import { VoiceHabitStore } from '../src/personalization/voice-habit-store';
import type { BridgeRequest } from '../src/protocol/schemas';

function makeRequest(utterance: string, event: string = 'voice_command'): BridgeRequest {
  return {
    source: 'android_relay',
    sessionId: 'test-session',
    workspace: 'test',
    event: event as BridgeRequest['event'],
    utterance,
    gesture: null,
    riskPolicy: {
      profile: 'default',
      allowReadOnly: true,
      allowSafeWithoutApproval: false,
      requireApprovalFor: ['run_tests', 'open_file', 'summarize_diff'],
      requireHardApprovalFor: ['push', 'deploy', 'delete', 'revert'],
      approvalTimeoutMs: 12000,
    },
    pendingActionId: null,
    approvalAction: null,
    deviceState: {
      activeBud: null,
      wearState: null,
      batteryPercent: null,
      profile: null,
    },
    hardwareContext: null,
  };
}

describe('IntentResolutionEngine', () => {
  it('uses fixed rules when no habit store', () => {
    const result = resolveIntentWithEngine(makeRequest('show me the diff'), null);
    expect(result.intent).toBe('summarize_diff');
    expect(result.source).toBe('fixed_rule');
    expect(result.needsConfirmation).toBe(false);
  });

  it('uses promoted habit before fixed rules', () => {
    const store = new VoiceHabitStore();
    store.set('what did I break', 'summarize_diff', 2);

    const result = resolveIntentWithEngine(makeRequest('what did I break'), store);
    expect(result.intent).toBe('summarize_diff');
    expect(result.source).toBe('habit');
    expect(result.confidence).toBe(1.0);
    expect(result.needsConfirmation).toBe(false);
  });

  it('requires confirmation for unpromoted habit', () => {
    const store = new VoiceHabitStore();
    store.set('what did I break', 'summarize_diff', 1);

    const result = resolveIntentWithEngine(makeRequest('what did I break'), store);
    expect(result.intent).toBe('summarize_diff');
    expect(result.source).toBe('habit');
    expect(result.confidence).toBe(0.5);
    expect(result.needsConfirmation).toBe(true);
  });

  it('falls back to fixed rules when no habit match', () => {
    const store = new VoiceHabitStore();
    store.set('other phrase', 'quick_status', 2);

    const result = resolveIntentWithEngine(makeRequest('run tests'), store);
    expect(result.intent).toBe('run_tests');
    expect(result.source).toBe('fixed_rule');
  });

  it('falls back to quick_status for unknown utterance', () => {
    const store = new VoiceHabitStore();
    const result = resolveIntentWithEngine(makeRequest('something random'), store);
    expect(result.intent).toBe('quick_status');
    expect(result.source).toBe('fallback');
    expect(result.confidence).toBe(0.3);
  });

  it('returns quick_status for quick_status event regardless of utterance', () => {
    const store = new VoiceHabitStore();
    const result = resolveIntentWithEngine(makeRequest('anything', 'quick_status'), store);
    expect(result.intent).toBe('quick_status');
    expect(result.source).toBe('fixed_rule');
  });
});
