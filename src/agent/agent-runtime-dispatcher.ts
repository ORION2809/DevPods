import type {
  BridgeRequest,
  JarvisResponse,
  WorkspaceConfig,
  ApprovalRequest,
} from '../protocol/schemas';
import type { IntentName } from '../protocol/types';
import type { SessionStore } from '../bridge/session-store';
import type { AuditLog } from '../bridge/audit-log';
import type { TierConfigStore } from '../personalization/tier-config-store';
import type {
  AgentRuntime,
  AgentRuntimeRequest,
  AgentRuntimeResponse,
  AgentRuntimeConstraints,
  AgentProgressEvent,
  AgentPlanConfirmation,
} from './agent-runtime-contract';
import type { DevPodsInstalledTier } from '../protocol/schemas';

const AGENT_INTENTS: readonly IntentName[] = [
  'agent_plan',
  'agent_report',
  'agent_cancel',
  'agent_redirect',
  'impact_analysis',
  'detect_changes',
  'query_symbol',
  'explain_flow',
];

function isAgentIntent(intent: IntentName): boolean {
  return AGENT_INTENTS.includes(intent);
}

function intentToMode(intent: IntentName, event: BridgeRequest['event']): AgentRuntimeRequest['mode'] {
  switch (intent) {
    case 'agent_plan':
      return 'plan';
    case 'agent_report':
      return 'report';
    case 'agent_cancel':
      return 'cancel';
    case 'agent_redirect':
      return 'replan';
    case 'impact_analysis':
    case 'detect_changes':
    case 'query_symbol':
    case 'explain_flow':
      return 'answer';
    default:
      return event === 'autonomy_replan' ? 'replan' : 'answer';
  }
}

export interface AgentRuntimeDispatcherOptions {
  agentRuntime: AgentRuntime;
  sessionStore: SessionStore;
  auditLog: AuditLog;
  tier: DevPodsInstalledTier;
  tierConfigStore?: TierConfigStore;
}

/**
 * Bridges EventRouter and AgentRuntime.
 *
 * - Builds AgentRuntimeRequest with policy constraints
 * - Converts AgentRuntimeResponse to JarvisResponse
 * - Manages active plan storage in SessionStore
 * - Routes progress events to outbox
 */
export class AgentRuntimeDispatcher {
  constructor(private readonly options: AgentRuntimeDispatcherOptions) {}

  canHandle(intent: IntentName): boolean {
    return isAgentIntent(intent);
  }

  async dispatch(
    intent: IntentName,
    request: BridgeRequest,
    workspace: WorkspaceConfig,
  ): Promise<JarvisResponse> {
    // Handle plan gestures first
    if (request.event === 'agent_plan_confirm') {
      return this.handlePlanConfirm(request, workspace);
    }
    if (request.event === 'agent_plan_cancel') {
      return this.handlePlanCancel(request, workspace);
    }
    if (request.event === 'agent_plan_redirect') {
      return this.handlePlanRedirect(request, workspace);
    }

    const agentRequest = this.buildAgentRuntimeRequest(intent, request, workspace);

    const progressEvents: AgentProgressEvent[] = [];
    const response = await this.options.agentRuntime.handle(agentRequest, {
      onProgress: (event) => {
        progressEvents.push(event);
      },
      onPlanReady: (plan) => {
        this.options.sessionStore.setActivePlan({
          planId: plan.planId,
          sessionId: plan.sessionId,
          workspace: workspace.id,
          planConfirmation: plan,
          status: 'awaiting_confirmation',
          createdAtMs: Date.now(),
        });
      },
    });

    return this.convertToJarvisResponse(response, request, workspace, progressEvents);
  }

  private buildAgentRuntimeRequest(
    intent: IntentName,
    request: BridgeRequest,
    workspace: WorkspaceConfig,
  ): AgentRuntimeRequest {
    const mode = intentToMode(intent, request.event);
    const constraints: AgentRuntimeConstraints = {
      spokenWordBudget: 24,
      requiresPlanConfirmation: true,
      allowedIntents: [
        'quick_status',
        'summarize_diff',
        'latest_ci_failure',
        'run_tests',
        'create_commit_message',
        'commit_staged',
        'open_file',
        'push',
        'deploy',
        'delete',
        'revert',
        'create_reminder',
        'agent_plan',
        'agent_report',
        'agent_cancel',
        'agent_redirect',
        'impact_analysis',
        'detect_changes',
        'query_symbol',
        'explain_flow',
      ],
      approvalRequiredIntents: [
        'run_tests',
        'commit_staged',
        'open_file',
        'push',
        'deploy',
        'delete',
        'revert',
      ],
      hardApprovalIntents: ['push', 'deploy', 'delete', 'revert'],
      intelligenceAvailable: this.isIntelligenceAvailableForWorkspace(workspace.id),
      redactionRequired: true,
    };

    return {
      requestId: `${request.sessionId}_${Date.now()}`,
      sessionId: request.sessionId,
      workspaceId: workspace.id,
      workspace,
      bridgeRequest: request,
      utterance: request.utterance ?? '',
      mode,
      intentHint: intent,
      approvedActionId: request.pendingActionId ?? null,
      constraints,
    };
  }

  private async handlePlanConfirm(
    request: BridgeRequest,
    workspace: WorkspaceConfig,
  ): Promise<JarvisResponse> {
    const plan = this.options.sessionStore.getActivePlan(request.sessionId);
    if (!plan) {
      return this.buildFallbackResponse(request, workspace, 'No plan is waiting for confirmation.');
    }

    if (request.pendingActionId !== plan.planId) {
      return this.buildFallbackResponse(request, workspace, 'Plan confirmation token does not match the active plan.');
    }

    if (Date.now() > plan.planConfirmation.expiresAtMs) {
      this.options.sessionStore.clearActivePlan(request.sessionId);
      return this.buildFallbackResponse(request, workspace, 'Plan confirmation expired.');
    }

    plan.status = 'confirmed';
    this.options.sessionStore.setActivePlan(plan);

    const agentRequest = this.buildAgentRuntimeRequest('agent_plan', request, workspace);
    agentRequest.mode = 'confirm_plan';
    agentRequest.approvedActionId = plan.planId;

    const progressEvents: AgentProgressEvent[] = [];
    const response = await this.options.agentRuntime.handle(agentRequest, {
      onProgress: (event) => {
        progressEvents.push(event);
      },
    });

    if (response.status === 'done' || response.status === 'running') {
      this.options.sessionStore.clearActivePlan(request.sessionId);
    }

    return this.convertToJarvisResponse(response, request, workspace, progressEvents);
  }

  private async handlePlanCancel(
    request: BridgeRequest,
    workspace: WorkspaceConfig,
  ): Promise<JarvisResponse> {
    const plan = this.options.sessionStore.getActivePlan(request.sessionId);
    if (!plan) {
      return this.buildFallbackResponse(request, workspace, 'No active plan to cancel.');
    }

    this.options.sessionStore.clearActivePlan(request.sessionId);
    await this.options.agentRuntime.cancel(request.sessionId, plan.planId);

    this.options.auditLog.append({
      sessionId: request.sessionId,
      workspace: workspace.id,
      event: request.event,
      decision: 'cancelled',
      status: 'cancelled',
      actionId: plan.planId,
      detail: 'Agent plan cancelled by user gesture.',
      hardwareContext: request.hardwareContext,
    });

    return {
      speak: 'Cancelled.',
      display: 'The agent plan was cancelled.',
      requiresApproval: false,
      approvalRequest: null,
      actionId: null,
      status: 'cancelled',
      nextState: 'idle',
      followUpHint: null,
    };
  }

  private async handlePlanRedirect(
    request: BridgeRequest,
    workspace: WorkspaceConfig,
  ): Promise<JarvisResponse> {
    const plan = this.options.sessionStore.getActivePlan(request.sessionId);
    if (!plan) {
      return this.buildFallbackResponse(request, workspace, 'No active plan to redirect.');
    }

    this.options.sessionStore.clearActivePlan(request.sessionId);
    await this.options.agentRuntime.cancel(request.sessionId, plan.planId);

    const redirectUtterance = request.utterance ?? '';
    const agentRequest = this.buildAgentRuntimeRequest('agent_redirect', request, workspace);
    agentRequest.mode = 'replan';
    agentRequest.utterance = redirectUtterance;

    const progressEvents: AgentProgressEvent[] = [];
    const response = await this.options.agentRuntime.handle(agentRequest, {
      onProgress: (event) => {
        progressEvents.push(event);
      },
      onPlanReady: (newPlan) => {
        this.options.sessionStore.setActivePlan({
          planId: newPlan.planId,
          sessionId: newPlan.sessionId,
          workspace: workspace.id,
          planConfirmation: newPlan,
          status: 'awaiting_confirmation',
          redirectUtterance,
          createdAtMs: Date.now(),
        });
      },
    });

    return this.convertToJarvisResponse(response, request, workspace, progressEvents);
  }

  private convertToJarvisResponse(
    agentResponse: AgentRuntimeResponse,
    request: BridgeRequest,
    workspace: WorkspaceConfig,
    progressEvents: AgentProgressEvent[],
  ): JarvisResponse {
    const base: JarvisResponse = {
      speak: agentResponse.response.speak,
      display: agentResponse.response.display,
      requiresApproval: false,
      approvalRequest: null,
      actionId: agentResponse.actionId,
      status: 'completed',
      nextState: 'idle',
      followUpHint: null,
    };

    switch (agentResponse.status) {
      case 'thinking': {
        const ack = agentResponse.acknowledgement;
        if (ack) {
          base.speak = `Got it. ${ack.intentUnderstood.slice(0, 30)}. Plan ready in about ${Math.ceil(ack.planningEstimateMs / 1000)} seconds.`;
          base.display = `Acknowledged: ${ack.intentUnderstood}`;
        }
        base.status = 'acknowledged';
        base.nextState = 'thinking';
        break;
      }
      case 'awaiting_plan_confirmation': {
        const plan = agentResponse.planConfirmation;
        if (plan) {
          base.speak = plan.spokenSummary;
          base.display = `Plan: ${plan.summary}\nSteps: ${plan.steps.length}\nRisk: ${plan.riskClass}\nExpires: ${new Date(plan.expiresAtMs).toISOString()}`;
          base.requiresApproval = true;
          base.approvalRequest = this.planToApprovalRequest(plan);
          base.actionId = plan.planId;
        }
        base.status = 'blocked';
        base.nextState = 'awaiting_plan_confirmation';
        break;
      }
      case 'running': {
        base.speak = agentResponse.response.speak;
        base.display = agentResponse.response.display;
        base.status = 'running';
        base.nextState = 'running';
        break;
      }
      case 'done': {
        const report = agentResponse.completionReport;
        if (report) {
          base.speak = report.summary;
          base.display = `Outcome: ${report.outcome}\nSteps: ${report.completedSteps}/${report.totalSteps}\n${report.requiresReview ? 'Requires review.' : ''}`;
        }
        base.status = 'completed';
        base.nextState = 'idle';
        break;
      }
      case 'blocked': {
        base.speak = agentResponse.response.speak || 'Agent is blocked.';
        base.display = agentResponse.response.display || 'The agent cannot proceed without direction.';
        base.status = 'blocked';
        base.nextState = 'idle';
        break;
      }
      case 'error': {
        const error = agentResponse.error;
        base.speak = error?.message ?? 'Agent encountered an error.';
        base.display = error?.message ?? 'Agent encountered an error.';
        base.status = 'error';
        base.nextState = 'idle';
        break;
      }
      case 'cancelled': {
        base.speak = 'Cancelled.';
        base.display = 'The agent plan or action was cancelled.';
        base.status = 'cancelled';
        base.nextState = 'idle';
        break;
      }
    }

    // Audit the agent turn
    this.options.auditLog.append({
      sessionId: request.sessionId,
      workspace: workspace.id,
      event: request.event,
      decision: 'allowed',
      status: base.status,
      actionId: base.actionId,
      detail: base.display,
      hardwareContext: request.hardwareContext,
    });

    return base;
  }

  private planToApprovalRequest(plan: AgentPlanConfirmation): ApprovalRequest {
    return {
      actionType: 'agent_plan',
      summary: plan.summary,
      riskClass: plan.riskClass === 'hard_approval' ? 'hard_approval' : 'approval_required',
      expiresInMs: plan.expiresAtMs - Date.now(),
    };
  }

  private buildFallbackResponse(
    request: BridgeRequest,
    workspace: WorkspaceConfig,
    speak: string,
  ): JarvisResponse {
    this.options.auditLog.append({
      sessionId: request.sessionId,
      workspace: workspace.id,
      event: request.event,
      decision: 'blocked',
      status: 'blocked',
      actionId: null,
      detail: speak,
      hardwareContext: request.hardwareContext,
    });

    return {
      speak,
      display: speak,
      requiresApproval: false,
      approvalRequest: null,
      actionId: null,
      status: 'blocked',
      nextState: 'idle',
      followUpHint: null,
    };
  }

  private isIntelligenceAvailableForWorkspace(workspaceId: string): boolean {
    if (this.options.tier !== 'intelligence') {
      return false;
    }
    const consent = this.options.tierConfigStore?.getIntelligenceConsent(workspaceId);
    return consent?.consented ?? false;
  }
}
