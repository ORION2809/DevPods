import type {
  BridgeRequest,
  BridgeCapabilitySnapshot,
  AgentCapabilitySnapshot,
  DevPodsInstalledTier,
  AgentRuntimeKind,
  AgentRuntimeHealth,
  AgentRuntimePhase,
  IntelligenceIndexState,
  IntelligenceWorkspaceCapability,
  JarvisResponse,
  WorkspaceConfig,
} from '../protocol/schemas';
import type { IntentName } from '../protocol/types';

export type {
  BridgeCapabilitySnapshot,
  AgentCapabilitySnapshot,
  DevPodsInstalledTier,
  AgentRuntimeKind,
  AgentRuntimeHealth,
  AgentRuntimePhase,
  IntelligenceIndexState,
  IntelligenceWorkspaceCapability,
};

export type AgentRequestMode =
  | 'answer'
  | 'plan'
  | 'confirm_plan'
  | 'execute_approved'
  | 'report'
  | 'replan'
  | 'cancel';
export type AgentRiskClass = 'immediate' | 'approval_required' | 'hard_approval';
export type AgentProgressKind =
  | 'thinking'
  | 'plan_ready'
  | 'implementation_started'
  | 'command_started'
  | 'command_completed'
  | 'tests_started'
  | 'tests_completed'
  | 'needs_direction'
  | 'completed'
  | 'failed';
export type AgentRuntimeResponseStatus =
  | 'thinking'
  | 'awaiting_plan_confirmation'
  | 'running'
  | 'done'
  | 'blocked'
  | 'error'
  | 'cancelled';

export interface AgentRuntimeRequest {
  requestId: string;
  sessionId: string;
  workspaceId: string;
  workspace: WorkspaceConfig;
  bridgeRequest: BridgeRequest;
  utterance: string;
  mode: AgentRequestMode;
  intentHint: IntentName | null;
  approvedActionId: string | null;
  constraints: AgentRuntimeConstraints;
}

export interface AgentRuntimeConstraints {
  spokenWordBudget: number;
  requiresPlanConfirmation: boolean;
  allowedIntents: readonly IntentName[];
  approvalRequiredIntents: readonly IntentName[];
  hardApprovalIntents: readonly IntentName[];
  intelligenceAvailable: boolean;
  redactionRequired: boolean;
}

export interface AgentAcknowledgement {
  planId: string;
  requestId: string;
  sessionId: string;
  intentUnderstood: string;
  planningEstimateMs: number;
}

export interface AgentPlanConfirmation {
  planId: string;
  requestId: string;
  sessionId: string;
  summary: string;
  spokenSummary: string;
  confirmationPrompt: string;
  riskClass: AgentRiskClass;
  affectedFiles: readonly string[];
  expectedCommands: readonly AgentPlannedCommand[];
  steps: readonly AgentPlanStep[];
  expiresAtMs: number;
}

export interface AgentPlanStep {
  id: string;
  title: string;
  spokenSummary: string | null;
  riskClass: AgentRiskClass;
  affectedFiles: readonly string[];
  requiresApproval: boolean;
}

export interface AgentPlannedCommand {
  description: string;
  command: string;
  args: readonly string[];
  riskClass: AgentRiskClass;
  requiresApproval: boolean;
}

export interface AgentPlanResponse {
  planId: string;
  decision: 'confirmed' | 'redirected' | 'cancelled';
  redirectUtterance?: string;
}

export interface AgentProgressEvent {
  id: string;
  requestId: string;
  sessionId: string;
  phase: AgentRuntimePhase;
  kind: AgentProgressKind;
  summary: string;
  speak: string | null;
  display: string | null;
  planId: string | null;
  actionId: string | null;
  percent: number | null;
  atMs: number;
}

export interface AgentCompletionReport {
  planId: string;
  outcome: 'completed' | 'failed' | 'partial';
  completedSteps: number;
  totalSteps: number;
  summary: string;
  failureReason?: string;
  nextSuggestion?: string;
  requiresReview: boolean;
}

export interface AgentRuntimeResponse {
  requestId: string;
  status: AgentRuntimeResponseStatus;
  response: JarvisResponse;
  acknowledgement: AgentAcknowledgement | null;
  planConfirmation: AgentPlanConfirmation | null;
  planResponse: AgentPlanResponse | null;
  progress: readonly AgentProgressEvent[];
  completionReport: AgentCompletionReport | null;
  actionId: string | null;
  error: AgentRuntimeError | null;
}

export interface AgentRuntimeError {
  code: string;
  message: string;
  retryable: boolean;
}

export interface AgentRuntimeCallbacks {
  onProgress(event: AgentProgressEvent): void | Promise<void>;
  onPlanReady?(plan: AgentPlanConfirmation): void | Promise<void>;
  onResponseDraft?(response: JarvisResponse): void | Promise<void>;
  onAcknowledgement?(ack: AgentAcknowledgement): void | Promise<void>;
  onCompletion?(report: AgentCompletionReport): void | Promise<void>;
}

export interface AgentRuntime {
  readonly kind: AgentRuntimeKind;

  getCapabilities(workspaceId: string): Promise<AgentCapabilitySnapshot>;

  handle(
    request: AgentRuntimeRequest,
    callbacks?: AgentRuntimeCallbacks,
  ): Promise<AgentRuntimeResponse>;

  cancel(sessionId: string, actionId?: string): Promise<void>;

  dispose?(): Promise<void>;
}
