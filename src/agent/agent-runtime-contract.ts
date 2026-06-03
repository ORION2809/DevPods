import type { BridgeRequest, JarvisResponse, WorkspaceConfig } from '../protocol/schemas';
import type { IntentName } from '../protocol/types';

export type DevPodsInstalledTier = 'core' | 'agent' | 'intelligence';
export type AgentRuntimeKind = 'openclaw' | 'hermes';
export type AgentRuntimeHealth = 'healthy' | 'degraded' | 'unavailable';
export type AgentRuntimePhase =
  | 'idle'
  | 'thinking'
  | 'planning'
  | 'awaiting_confirmation'
  | 'running'
  | 'done'
  | 'blocked'
  | 'error'
  | 'cancelled';
export type AgentRequestMode =
  | 'answer'
  | 'plan'
  | 'confirm_plan'
  | 'execute_approved'
  | 'report'
  | 'replan'
  | 'cancel';
export type AgentRiskClass = 'immediate' | 'approval_required' | 'hard_approval';
export type IntelligenceIndexState =
  | 'not_installed'
  | 'disabled'
  | 'not_indexed'
  | 'indexing'
  | 'ready'
  | 'stale'
  | 'failed'
  | 'paused';
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

export interface BridgeCapabilitySnapshot {
  tier: DevPodsInstalledTier;
  capabilities: {
    core: {
      available: true;
      voiceLoop: true;
      approvals: true;
      gitBasics: true;
    };
    agent: {
      available: boolean;
      runtime: AgentRuntimeKind | 'none';
      healthy: boolean;
      state: AgentRuntimePhase;
      degradedReason: string | null;
    };
    intelligence: {
      available: boolean;
      indexState: IntelligenceIndexState;
      workspaces: readonly string[];
      currentWorkspace: IntelligenceWorkspaceCapability | null;
    };
  };
}

export interface IntelligenceWorkspaceCapability {
  workspaceId: string;
  indexState: IntelligenceIndexState;
  indexedCommit: string | null;
  lastIndexedAtMs: number | null;
  stalenessReason: string | null;
}

export interface AgentCapabilitySnapshot {
  runtime: AgentRuntimeKind;
  health: AgentRuntimeHealth;
  state: AgentRuntimePhase;
  supportsPlanConfirmation: boolean;
  supportsProgressEvents: boolean;
  intelligence: {
    available: boolean;
    indexState: IntelligenceIndexState;
    workspaces: readonly string[];
  };
}

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

export interface AgentRuntimeResponse {
  requestId: string;
  status: AgentRuntimeResponseStatus;
  response: JarvisResponse;
  planConfirmation: AgentPlanConfirmation | null;
  progress: readonly AgentProgressEvent[];
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
