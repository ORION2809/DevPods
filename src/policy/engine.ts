import type { IntentName } from '../protocol/types';
import type { HardwareContext, RiskPolicy, WorkspaceConfig } from '../protocol/schemas';
import { immediateIntents } from '../protocol/types';

export interface PolicyDecision {
  allowed: boolean;
  riskClass: 'immediate' | 'approval_required' | 'hard_approval' | 'denied';
  reason?: string;
  hardwareContext?: HardwareContext | null;
}

const physicalInterruptGestures = new Set<string>([
  'both_hold_cancel',
  'remove_one_bud_pause',
  'remove_both_buds_end_session',
]);

export function buildRiskPolicy(workspace: WorkspaceConfig): RiskPolicy {
  return {
    profile: 'default',
    allowReadOnly: true,
    allowSafeWithoutApproval: true,
    requireApprovalFor: workspace.approvalRequiredIntents,
    requireHardApprovalFor: workspace.hardApprovalIntents,
    approvalTimeoutMs: 12000,
  };
}

export function evaluateIntentPolicy(
  intent: IntentName,
  workspace: WorkspaceConfig,
  hardwareContext?: HardwareContext | null,
  gesture?: string | null,
): PolicyDecision {
  if (!workspace.allowedIntents.includes(intent)) {
    return {
      allowed: false,
      riskClass: 'denied',
      reason: `Intent '${intent}' is not allowlisted for workspace '${workspace.id}'.`,
      hardwareContext,
    };
  }

  if (workspace.hardApprovalIntents.includes(intent)) {
    return {
      allowed: true,
      riskClass: 'hard_approval',
      hardwareContext,
    };
  }

  const isProvenPhysicalInterrupt = hardwareContext?.deviceConfidence === 'proven'
    && gesture != null
    && physicalInterruptGestures.has(gesture);

  if (workspace.approvalRequiredIntents.includes(intent)) {
    if (isProvenPhysicalInterrupt) {
      return {
        allowed: true,
        riskClass: 'immediate',
        hardwareContext,
      };
    }

    return {
      allowed: true,
      riskClass: 'approval_required',
      hardwareContext,
    };
  }

  if (immediateIntents.has(intent)) {
    return {
      allowed: true,
      riskClass: 'immediate',
      hardwareContext,
    };
  }

  return {
    allowed: true,
    riskClass: isProvenPhysicalInterrupt ? 'immediate' : 'approval_required',
    hardwareContext,
  };
}