import { bridgeRequestSchema, type BridgeRequest, type EarbudEvent, type WorkspaceConfig } from '../protocol/schemas';
import { buildRiskPolicy } from '../policy/engine';

export function buildBridgeRequest(event: EarbudEvent, workspace: WorkspaceConfig): BridgeRequest {
  const mapped = mapEvent(event);

  return bridgeRequestSchema.parse({
    source: event.source,
    sessionId: event.sessionId,
    workspace: event.workspace,
    event: mapped.event,
    utterance: mapped.utterance,
    gesture: event.event,
    riskPolicy: buildRiskPolicy(workspace),
    pendingActionId: event.pendingActionId ?? null,
    approvalAction: mapped.approvalAction,
    deviceState: {
      activeBud: mapActiveBud(event.device),
      wearState: event.wearState ?? null,
      batteryPercent: event.battery ?? null,
      profile: event.profile ?? null,
    },
  });
}

function mapEvent(event: EarbudEvent): {
  event: BridgeRequest['event'];
  utterance: string | null;
  approvalAction: BridgeRequest['approvalAction'];
} {
  switch (event.event) {
    case 'left_long_press':
    case 'android_status_shortcut':
      return { event: 'quick_status', utterance: null, approvalAction: null };
    case 'approve_right_double_tap':
    case 'android_approve':
      return { event: 'approval_action', utterance: null, approvalAction: 'approve' };
    case 'reject_left_double_tap':
    case 'android_reject':
      return { event: 'approval_action', utterance: null, approvalAction: 'reject' };
    case 'both_hold_cancel':
    case 'android_cancel':
      return { event: 'cancel', utterance: null, approvalAction: null };
    case 'remove_one_bud_pause':
      return { event: 'pause', utterance: null, approvalAction: null };
    case 'remove_both_buds_end_session':
      return { event: 'cancel', utterance: null, approvalAction: null };
    case 'put_both_in_resume':
      return { event: 'resume', utterance: null, approvalAction: null };
    case 'android_autonomy_continue':
      return { event: 'autonomy_continue', utterance: null, approvalAction: null };
    case 'android_autonomy_interrupt':
      return { event: 'autonomy_replan', utterance: event.utterance ?? null, approvalAction: null };
    case 'triple_tap_right':
    case 'tap_test_button':
    case 'headset_button_single':
    case 'android_push_to_talk':
      if (event.utterance) {
        return { event: 'voice_command', utterance: event.utterance, approvalAction: null };
      }

      return { event: 'wake_and_listen', utterance: null, approvalAction: null };
  }
}

function mapActiveBud(device: EarbudEvent['device']): 'left' | 'right' | 'both' {
  switch (device) {
    case 'left_bud':
      return 'left';
    case 'both_buds':
      return 'both';
    default:
      return 'right';
  }
}