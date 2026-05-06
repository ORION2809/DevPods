import type { IntentName } from '../protocol/types';
import type { RiskPolicy, WorkspaceConfig } from '../protocol/schemas';
import { immediateIntents } from '../protocol/types';

export interface PolicyDecision {
  allowed: boolean;
  riskClass: 'immediate' | 'approval_required' | 'hard_approval' | 'denied';
  reason?: string;
}

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

export function evaluateIntentPolicy(intent: IntentName, workspace: WorkspaceConfig): PolicyDecision {
  if (!workspace.allowedIntents.includes(intent)) {
    return {
      allowed: false,
      riskClass: 'denied',
      reason: `Intent '${intent}' is not allowlisted for workspace '${workspace.id}'.`,
    };
  }

  if (workspace.hardApprovalIntents.includes(intent)) {
    return {
      allowed: true,
      riskClass: 'hard_approval',
    };
  }

  if (workspace.approvalRequiredIntents.includes(intent)) {
    return {
      allowed: true,
      riskClass: 'approval_required',
    };
  }

  if (immediateIntents.has(intent)) {
    return {
      allowed: true,
      riskClass: 'immediate',
    };
  }

  return {
    allowed: true,
    riskClass: 'approval_required',
  };
}