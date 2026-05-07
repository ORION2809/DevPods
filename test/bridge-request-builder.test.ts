import { describe, expect, it } from 'vitest';
import { buildBridgeRequest } from '../src/bridge/request-builder';
import type { EarbudEvent, WorkspaceConfig } from '../src/protocol/schemas';

const workspace: WorkspaceConfig = {
  id: 'current_repo',
  label: 'Current Repo',
  rootPath: 'C:/workspace/current_repo',
  allowedIntents: ['quick_status', 'run_tests', 'open_file'],
  approvalRequiredIntents: ['run_tests'],
  hardApprovalIntents: [],
  commands: {},
};

describe('buildBridgeRequest', () => {
  it('maps tap_test_button to a wake-and-listen request', () => {
    const request = buildBridgeRequest(
      {
        source: 'android_relay',
        sessionId: 'android_tap_test',
        workspace: 'current_repo',
        device: 'both_buds',
        event: 'tap_test_button',
        timestamp: Date.now(),
      } satisfies EarbudEvent,
      workspace,
    );

    expect(request.event).toBe('wake_and_listen');
    expect(request.gesture).toBe('tap_test_button');
  });

  it('maps autonomy control events from android relay', () => {
    const continueRequest = buildBridgeRequest(
      {
        source: 'android_relay',
        sessionId: 'android_autonomy',
        workspace: 'current_repo',
        device: 'both_buds',
        event: 'android_autonomy_continue',
        timestamp: Date.now(),
      } satisfies EarbudEvent,
      workspace,
    );
    const interruptRequest = buildBridgeRequest(
      {
        source: 'android_relay',
        sessionId: 'android_autonomy',
        workspace: 'current_repo',
        device: 'both_buds',
        event: 'android_autonomy_interrupt',
        timestamp: Date.now(),
        utterance: 'what branch am i on',
      } satisfies EarbudEvent,
      workspace,
    );

    expect(continueRequest.event).toBe('autonomy_continue');
    expect(continueRequest.gesture).toBe('android_autonomy_continue');
    expect(interruptRequest.event).toBe('autonomy_replan');
    expect(interruptRequest.utterance).toBe('what branch am i on');
  });
});