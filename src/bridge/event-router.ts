import { buildBridgeRequest } from './request-builder';
import type { EarbudEvent, JarvisResponse, WorkspaceRegistry } from '../protocol/schemas';
import type { IntentName } from '../protocol/types';
import { resolveWorkspace } from '../policy/allowlists';
import { evaluateIntentPolicy } from '../policy/engine';
import { createActionId, isApprovalExpired } from '../policy/approvals';
import { describeIntent } from '../jarvis/router';
import { resolveIntentWithEngine } from '../jarvis/intent-resolution-engine';
import { JarvisRuntime } from '../jarvis/runtime';
import { SessionStore } from './session-store';
import { AuditLog } from './audit-log';
import type { VoiceHabitStore } from '../personalization/voice-habit-store';
import type { OutboxStore } from '../personalization/outbox-store';
import type { ReminderStore } from '../personalization/reminder-store';
import type { AgentRuntimeDispatcher } from '../agent/agent-runtime-dispatcher';

export class EventRouter {
  constructor(
    private readonly registry: WorkspaceRegistry,
    private readonly sessionStore: SessionStore,
    private readonly auditLog: AuditLog,
    private readonly jarvisRuntime: JarvisRuntime,
    private readonly voiceHabitStore: VoiceHabitStore | null = null,
    private readonly outboxStore: OutboxStore | null = null,
    private readonly reminderStore: ReminderStore | null = null,
    private readonly agentRuntimeDispatcher: AgentRuntimeDispatcher | null = null,
  ) {}

  async dispatch(event: EarbudEvent): Promise<JarvisResponse> {
    const workspace = resolveWorkspace(this.registry, event.workspace);
    const request = buildBridgeRequest(event, workspace);

    this.auditLog.append({
      sessionId: request.sessionId,
      workspace: workspace.id,
      event: request.event,
      decision: 'received',
      status: 'acknowledged',
      actionId: request.pendingActionId,
      detail: request.utterance,
      hardwareContext: request.hardwareContext,
    });

    switch (request.event) {
      case 'wake_and_listen':
        this.sessionStore.setState(request.sessionId, 'listening');
        return this.respond(request, workspace.id, {
          speak: 'Jarvis active. What should I check?',
          display: 'Listening window opened.',
          requiresApproval: false,
          approvalRequest: null,
          actionId: null,
          status: 'acknowledged',
          nextState: 'listening',
          followUpHint: null,
        }, 'allowed');
      case 'pause':
        this.sessionStore.setState(request.sessionId, 'paused');
        return this.respond(request, workspace.id, {
          speak: 'Listening paused.',
          display: 'The session is paused because a bud was removed.',
          requiresApproval: false,
          approvalRequest: null,
          actionId: null,
          status: 'acknowledged',
          nextState: 'paused',
          followUpHint: null,
        }, 'allowed');
      case 'resume':
        this.sessionStore.setState(request.sessionId, 'idle');
        return this.respond(request, workspace.id, {
          speak: 'Passive updates resumed.',
          display: 'The session is ready for the next wake event.',
          requiresApproval: false,
          approvalRequest: null,
          actionId: null,
          status: 'acknowledged',
          nextState: 'idle',
          followUpHint: null,
        }, 'allowed');
      case 'cancel':
        const backgroundCancelOutcome = this.jarvisRuntime.cancelBackgroundWork(request.sessionId);
        this.sessionStore.clearPending(request.sessionId);
        if (backgroundCancelOutcome === 'running_cancelled') {
          this.sessionStore.clearAutonomy(request.sessionId);
          this.sessionStore.setState(request.sessionId, 'idle');
          return this.respond(request, workspace.id, {
            speak: 'Implementation paused. Tell me what to change.',
            display: 'The running background task was cancelled before completion.',
            requiresApproval: false,
            approvalRequest: null,
            actionId: null,
            status: 'cancelled',
            nextState: 'idle',
            followUpHint: 'Describe the change to make',
          }, 'cancelled');
        }

        this.sessionStore.clearAutonomy(request.sessionId);
        this.sessionStore.setState(request.sessionId, 'idle');
        return this.respond(request, workspace.id, {
          speak: 'Command cancelled.',
          display: backgroundCancelOutcome === 'queued_cancelled'
            ? 'The queued background task was cancelled before it started.'
            : 'The active or pending request was cancelled.',
          requiresApproval: false,
          approvalRequest: null,
          actionId: null,
          status: 'cancelled',
          nextState: 'idle',
          followUpHint: null,
        }, 'cancelled');
      case 'approval_action':
        return this.handleApproval(request, workspace.id);
      case 'autonomy_continue':
        return this.handleAutonomyContinue(request, workspace);
      case 'autonomy_replan':
        return this.handleAutonomyReplan(request, workspace);
      case 'quick_status':
      case 'voice_command':
        return this.handleIntentRequest(request, workspace);
      case 'learning_prompt_confirm':
        return this.handleLearningPromptConfirm(request, workspace);
      case 'learning_prompt_reject':
        return this.handleLearningPromptReject(request, workspace);
      case 'agent_plan_confirm':
      case 'agent_plan_cancel':
      case 'agent_plan_redirect':
        return this.handleAgentPlanGesture(request, workspace);
      default:
        // Exhaustiveness guard for unhandled request events
        return this.respond(request, workspace.id, {
          speak: 'That gesture is not recognised.',
          display: `Unhandled request event: ${(request as { event: string }).event}`,
          requiresApproval: false,
          approvalRequest: null,
          actionId: null,
          status: 'blocked',
          nextState: 'idle',
          followUpHint: null,
        }, 'blocked');
    }
  }

  private async handleIntentRequest(
    request: ReturnType<typeof buildBridgeRequest>,
    workspace: ReturnType<typeof resolveWorkspace>,
  ): Promise<JarvisResponse> {
    const resolution = resolveIntentWithEngine(request, this.voiceHabitStore);

    if (resolution.needsConfirmation && request.utterance) {
      this.sessionStore.setState(request.sessionId, 'idle');
      const prompt = `Did you mean ${describeIntent(resolution.intent).toLowerCase()}?`;
      this.outboxStore?.enqueue({
        sessionId: request.sessionId,
        priority: 'normal',
        kind: 'learning_prompt',
        summary: prompt,
        detail: JSON.stringify({ phrase: request.utterance, intent: resolution.intent }),
        expiresAtMs: Date.now() + 60_000,
      });
      return this.respond(request, workspace.id, {
        speak: prompt,
        display: `Learning prompt: "${request.utterance}" → ${resolution.intent}`,
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'acknowledged',
        nextState: 'idle',
        followUpHint: 'Right double tap to confirm, left to reject',
      }, 'allowed');
    }

    return this.handleResolvedIntent(request, workspace, resolution.intent);
  }

  private async handleLearningPromptConfirm(
    request: ReturnType<typeof buildBridgeRequest>,
    workspace: ReturnType<typeof resolveWorkspace>,
  ): Promise<JarvisResponse> {
    const phrase = request.utterance;
    const intentHint = request.pendingActionId; // Re-purposed to carry intent hint
    if (!phrase || !this.voiceHabitStore) {
      return this.respond(request, workspace.id, {
        speak: 'Nothing to learn.',
        display: 'Learning confirmation missing phrase or store.',
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'blocked',
        nextState: 'idle',
        followUpHint: null,
      }, 'blocked');
    }

    // Parse intent from the pendingActionId field (format: "intent:quick_status")
    const intent = intentHint?.startsWith('intent:') ? intentHint.slice(7) as IntentName : 'quick_status';
    const entry = this.voiceHabitStore.confirm(phrase, intent);
    const promoted = entry.confirmationCount >= 2;

    return this.respond(request, workspace.id, {
      speak: promoted ? 'Got it. I will remember that.' : 'Noted. One more time to confirm.',
      display: `Learned "${phrase}" → ${intent} (count: ${entry.confirmationCount})`,
      requiresApproval: false,
      approvalRequest: null,
      actionId: null,
      status: 'acknowledged',
      nextState: 'idle',
      followUpHint: null,
    }, 'allowed');
  }

  private async handleLearningPromptReject(
    request: ReturnType<typeof buildBridgeRequest>,
    workspace: ReturnType<typeof resolveWorkspace>,
  ): Promise<JarvisResponse> {
    this.sessionStore.setState(request.sessionId, 'idle');
    return this.respond(request, workspace.id, {
      speak: 'Okay, I will not learn that.',
      display: 'Learning prompt rejected.',
      requiresApproval: false,
      approvalRequest: null,
      actionId: null,
      status: 'cancelled',
      nextState: 'idle',
      followUpHint: null,
    }, 'rejected');
  }

  private async handleAgentPlanGesture(
    request: ReturnType<typeof buildBridgeRequest>,
    workspace: ReturnType<typeof resolveWorkspace>,
  ): Promise<JarvisResponse> {
    if (!this.agentRuntimeDispatcher) {
      return this.respond(request, workspace.id, {
        speak: 'Agent runtime is not available.',
        display: 'This DevPods installation does not include an agent runtime. Upgrade to the Agent tier to use planning.',
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'blocked',
        nextState: 'idle',
        followUpHint: null,
      }, 'blocked');
    }

    return this.agentRuntimeDispatcher.dispatch('agent_plan', request, workspace);
  }

  private async handleResolvedIntent(
    request: ReturnType<typeof buildBridgeRequest>,
    workspace: ReturnType<typeof resolveWorkspace>,
    intent: IntentName,
  ): Promise<JarvisResponse> {
    const decision = evaluateIntentPolicy(intent, workspace, request.hardwareContext, request.gesture);

    if (!decision.allowed) {
      this.sessionStore.clearAutonomy(request.sessionId);
      this.sessionStore.setState(request.sessionId, 'idle');
      return this.respond(request, workspace.id, {
        speak: 'That action is blocked in this workspace.',
        display: decision.reason ?? 'Policy denied the request.',
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'blocked',
        nextState: 'idle',
        followUpHint: null,
      }, 'blocked');
    }

    if (this.sessionStore.isQuickStart(request.sessionId) && decision.riskClass !== 'immediate') {
      this.sessionStore.clearAutonomy(request.sessionId);
      this.sessionStore.setState(request.sessionId, 'idle');
      return this.respond(request, workspace.id, {
        speak: `${describeIntent(intent)} requires full setup. Finish calibration to unlock all commands.`,
        display: 'Quick-start mode: read-only commands only. Complete setup for full access.',
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'blocked',
        nextState: 'idle',
        followUpHint: 'Say status or run tests for read-only results',
      }, 'blocked');
    }

    if (decision.riskClass === 'approval_required' || decision.riskClass === 'hard_approval') {
      this.sessionStore.clearAutonomy(request.sessionId);
      const actionId = createActionId();
      const expiresAt = new Date(Date.now() + request.riskPolicy.approvalTimeoutMs);
      const isHardApproval = decision.riskClass === 'hard_approval';
      const summary = describeIntent(intent);
      this.sessionStore.setPending({
        actionId,
        sessionId: request.sessionId,
        workspace: workspace.id,
        intent,
        request,
        summary,
        riskClass: decision.riskClass,
        expiresAt,
      });

      this.outboxStore?.enqueue({
        sessionId: request.sessionId,
        priority: isHardApproval ? 'critical' : 'high',
        kind: 'approval_pending',
        summary: `${isHardApproval ? 'Hard approval: ' : ''}${summary}`,
        detail: JSON.stringify({ actionType: intent, summary, riskClass: decision.riskClass, expiresInMs: request.riskPolicy.approvalTimeoutMs }),
        actionId,
        expiresAtMs: expiresAt.getTime(),
      });

      return this.respond(request, workspace.id, {
        speak: `${isHardApproval ? 'Hard approval required.' : ''} ${summary}? Right double tap to approve.`.trim(),
        display: `${isHardApproval ? 'Hard approval required. ' : ''}${summary} in workspace ${workspace.label}.`,
        requiresApproval: true,
        approvalRequest: {
          actionType: intent,
          summary,
          riskClass: decision.riskClass,
          expiresInMs: request.riskPolicy.approvalTimeoutMs,
        },
        actionId,
        status: 'blocked',
        nextState: 'approval_pending',
        followUpHint: 'Right double tap approve, left double tap reject',
      }, 'approval_requested');
    }

    if (intent === 'create_reminder') {
      return this.handleCreateReminder(request, workspace);
    }

    // Route agent-tier intents through the agent runtime dispatcher when available
    if (this.agentRuntimeDispatcher?.canHandle(intent)) {
      this.sessionStore.clearAutonomy(request.sessionId);
      this.sessionStore.setState(request.sessionId, 'thinking');
      const response = await this.agentRuntimeDispatcher.dispatch(intent, request, workspace);
      this.sessionStore.setState(request.sessionId, response.nextState);
      if (response.status === 'completed' || response.status === 'acknowledged') {
        this.sessionStore.setCompletionContext(request.sessionId, response.speak);
        if (!response.followUpHint) {
          response.followUpHint = 'Say remind me later to defer';
        }
      }
      return this.respond(request, workspace.id, response, 'completed');
    }

    this.sessionStore.clearAutonomy(request.sessionId);
    this.sessionStore.setState(request.sessionId, 'thinking');
    const response = await this.jarvisRuntime.executeIntent(intent, request, workspace);
    this.sessionStore.setState(request.sessionId, response.nextState);
    if (response.status === 'completed' || response.status === 'acknowledged') {
      this.sessionStore.setCompletionContext(request.sessionId, response.speak);
      if (!response.followUpHint) {
        response.followUpHint = 'Say remind me later to defer';
      }
    }
    return this.respond(request, workspace.id, response, 'completed');
  }

  private handleCreateReminder(
    request: ReturnType<typeof buildBridgeRequest>,
    workspace: ReturnType<typeof resolveWorkspace>,
  ): JarvisResponse {
    const utterance = request.utterance ?? '';
    const durationMs = parseReminderDuration(utterance);
    const dueAtMs = Date.now() + durationMs;
    let summary = extractReminderSummary(utterance);

    // If no explicit subject and there's a recent completion context, defer that
    if (!summary && isDeferralUtterance(utterance)) {
      const ctx = this.sessionStore.getCompletionContext(request.sessionId);
      if (ctx) {
        summary = ctx.summary;
      }
    }

    if (!summary) summary = 'Reminder';

    if (!this.reminderStore) {
      return this.respond(request, workspace.id, {
        speak: 'Reminder store is not available.',
        display: 'Reminder store is not available.',
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'error',
        nextState: 'idle',
        followUpHint: null,
      }, 'blocked');
    }

    this.reminderStore.schedule(request.sessionId, summary, dueAtMs);
    const minutes = Math.round(durationMs / 60_000);
    const timePhrase = minutes < 1 ? 'in less than a minute' : `in ${minutes} minute${minutes === 1 ? '' : 's'}`;

    return this.respond(request, workspace.id, {
      speak: `Reminder set ${timePhrase}.`,
      display: `Reminder "${summary}" set for ${timePhrase}.`,
      requiresApproval: false,
      approvalRequest: null,
      actionId: null,
      status: 'acknowledged',
      nextState: 'idle',
      followUpHint: null,
    }, 'allowed');
  }

  private async handleAutonomyContinue(
    request: ReturnType<typeof buildBridgeRequest>,
    workspace: ReturnType<typeof resolveWorkspace>,
  ): Promise<JarvisResponse> {
    const autonomy = this.sessionStore.getAutonomy(request.sessionId);
    if (!autonomy?.nextIntent) {
      this.sessionStore.setState(request.sessionId, 'idle');
      return this.respond(request, workspace.id, {
        speak: 'No active implementation plan is waiting to continue.',
        display: 'The relay did not have a queued autonomy step for this session.',
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'blocked',
        nextState: 'idle',
        followUpHint: null,
      }, 'blocked');
    }

    this.sessionStore.clearAutonomy(request.sessionId);
    return this.handleResolvedIntent(request, workspace, autonomy.nextIntent);
  }

  private async handleAutonomyReplan(
    request: ReturnType<typeof buildBridgeRequest>,
    workspace: ReturnType<typeof resolveWorkspace>,
  ): Promise<JarvisResponse> {
    if (!request.utterance) {
      this.sessionStore.clearAutonomy(request.sessionId);
      this.sessionStore.setState(request.sessionId, 'idle');
      return this.respond(request, workspace.id, {
        speak: 'I did not hear the updated plan.',
        display: 'Autonomy replan requires a spoken instruction.',
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'blocked',
        nextState: 'idle',
        followUpHint: null,
      }, 'blocked');
    }

    const resolution = resolveIntentWithEngine({
      ...request,
      event: 'voice_command',
    }, this.voiceHabitStore);
    const intent = resolution.intent;
    const decision = evaluateIntentPolicy(intent, workspace, request.hardwareContext, request.gesture);
    if (!decision.allowed) {
      this.sessionStore.clearAutonomy(request.sessionId);
      this.sessionStore.setState(request.sessionId, 'idle');
      return this.respond(request, workspace.id, {
        speak: 'That updated plan is blocked in this workspace.',
        display: decision.reason ?? 'Policy denied the requested plan update.',
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'blocked',
        nextState: 'idle',
        followUpHint: null,
      }, 'blocked');
    }

    const summary = 'Plan updated from your request.';
    const nextStep = describeIntent(intent);
    this.sessionStore.setState(request.sessionId, 'idle');
    return this.respond(request, workspace.id, {
      speak: `Plan updated. Next I will ${nextStep.toLowerCase()}. Double tap to change it or stay silent to continue.`,
      display: `Plan updated. Next step: ${nextStep}.`,
      requiresApproval: false,
      approvalRequest: null,
      actionId: null,
      status: 'acknowledged',
      nextState: 'idle',
      followUpHint: 'Double tap to change it or stay silent to continue',
      autonomy: {
        phase: 'plan',
        mode: 'continue_on_silence',
        summary,
        nextStep,
        continueAfterMs: 4000,
        nextIntent: intent,
      },
    }, 'allowed');
  }

  private async handleApproval(
    request: ReturnType<typeof buildBridgeRequest>,
    workspaceId: string,
  ): Promise<JarvisResponse> {
    // Check for active agent plan first: right double tap while awaiting plan confirmation
    const activePlan = this.sessionStore.getActivePlan(request.sessionId);
    if (
      activePlan
      && request.pendingActionId === activePlan.planId
      && request.approvalAction === 'approve'
      && this.agentRuntimeDispatcher
    ) {
      const workspace = resolveWorkspace(this.registry, workspaceId);
      return this.agentRuntimeDispatcher.dispatch('agent_plan', request, workspace);
    }

    if (!request.pendingActionId) {
      this.sessionStore.setState(request.sessionId, 'idle');
      return this.respond(request, workspaceId, {
        speak: 'Approval token missing.',
        display: 'The approval gesture did not include the required action identifier.',
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'blocked',
        nextState: 'idle',
        followUpHint: null,
      }, 'blocked');
    }

    const pending = this.sessionStore.getPending(request.sessionId, request.pendingActionId, workspaceId);

    if (!pending) {
      this.sessionStore.setState(request.sessionId, 'idle');
      return this.respond(request, workspaceId, {
        speak: 'No action is waiting for approval.',
        display: 'The approval gesture did not match an active pending action.',
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'blocked',
        nextState: 'idle',
        followUpHint: null,
      }, 'blocked');
    }

    if (isApprovalExpired(pending.expiresAt)) {
      this.sessionStore.clearPending(request.sessionId);
      this.sessionStore.setState(request.sessionId, 'idle');
      this.outboxStore?.enqueue({
        sessionId: request.sessionId,
        priority: 'normal',
        kind: 'completion_full_report',
        summary: 'Approval expired.',
        detail: JSON.stringify({ actionType: pending.intent, riskClass: pending.riskClass, result: 'expired' }),
        actionId: pending.actionId,
        expiresAtMs: Date.now() + 300_000,
      });
      return this.respond(request, workspaceId, {
        speak: 'Approval expired.',
        display: 'The pending action timed out before it was approved.',
        requiresApproval: false,
        approvalRequest: null,
        actionId: pending.actionId,
        status: 'cancelled',
        nextState: 'idle',
        followUpHint: null,
      }, 'cancelled');
    }

    if (request.approvalAction === 'reject') {
      this.sessionStore.clearPending(request.sessionId);
      this.sessionStore.setState(request.sessionId, 'idle');
      this.outboxStore?.enqueue({
        sessionId: request.sessionId,
        priority: 'normal',
        kind: 'completion_full_report',
        summary: 'Action rejected.',
        detail: JSON.stringify({ actionType: pending.intent, riskClass: pending.riskClass, result: 'rejected' }),
        actionId: pending.actionId,
        expiresAtMs: Date.now() + 300_000,
      });
      return this.respond(request, workspaceId, {
        speak: 'Action rejected.',
        display: 'The pending action was rejected by gesture.',
        requiresApproval: false,
        approvalRequest: null,
        actionId: pending.actionId,
        status: 'cancelled',
        nextState: 'idle',
        followUpHint: null,
      }, 'rejected');
    }

    if (request.approvalAction === 'cancel') {
      this.sessionStore.clearPending(request.sessionId);
      this.sessionStore.setState(request.sessionId, 'idle');
      this.outboxStore?.enqueue({
        sessionId: request.sessionId,
        priority: 'normal',
        kind: 'completion_full_report',
        summary: 'Command cancelled.',
        detail: JSON.stringify({ actionType: pending.intent, riskClass: pending.riskClass, result: 'cancelled' }),
        actionId: pending.actionId,
        expiresAtMs: Date.now() + 300_000,
      });
      return this.respond(request, workspaceId, {
        speak: 'Command cancelled.',
        display: 'The pending action was cancelled.',
        requiresApproval: false,
        approvalRequest: null,
        actionId: pending.actionId,
        status: 'cancelled',
        nextState: 'idle',
        followUpHint: null,
      }, 'cancelled');
    }

    const workspace = resolveWorkspace(this.registry, pending.workspace);
    this.sessionStore.clearPending(request.sessionId);
    this.sessionStore.setState(request.sessionId, 'running');
    const response = await this.jarvisRuntime.executeIntent(pending.intent, pending.request, workspace, pending.actionId);
    this.sessionStore.setState(request.sessionId, response.nextState);
    if (response.status === 'completed' || response.status === 'acknowledged') {
      this.sessionStore.setCompletionContext(request.sessionId, response.speak);
    }
    this.outboxStore?.enqueue({
      sessionId: request.sessionId,
      priority: 'normal',
      kind: response.status === 'error' ? 'completion_full_report' : 'completion_soft_ping',
      summary: response.speak.slice(0, 120),
      detail: response.display ?? undefined,
      actionId: pending.actionId,
      expiresAtMs: Date.now() + 300_000,
    });
    return this.respond(request, workspace.id, response, 'approved');
  }

  private respond(
    request: ReturnType<typeof buildBridgeRequest>,
    workspaceId: string,
    response: JarvisResponse,
    decision: 'allowed' | 'blocked' | 'approval_requested' | 'approved' | 'rejected' | 'cancelled' | 'completed',
  ): JarvisResponse {
    this.auditLog.append({
      sessionId: request.sessionId,
      workspace: workspaceId,
      event: request.event,
      decision,
      status: response.status,
      actionId: response.actionId,
      detail: response.display,
      hardwareContext: request.hardwareContext,
    });

    return response;
  }
}

function parseReminderDuration(utterance: string): number {
  const lower = utterance.toLowerCase();

  // Specific patterns first
  const hourMatch = /in\s+(\d+)\s*(?:hour|hr)s?/.exec(lower);
  if (hourMatch) return parseInt(hourMatch[1], 10) * 3600_000;

  const minMatch = /in\s+(\d+)\s*(?:minute|min)s?/.exec(lower);
  if (minMatch) return parseInt(minMatch[1], 10) * 60_000;

  const secMatch = /in\s+(\d+)\s*(?:second|sec)s?/.exec(lower);
  if (secMatch) return parseInt(secMatch[1], 10) * 1000;

  // Relative keywords
  if (lower.includes('in an hour') || lower.includes('in 1 hour')) return 3600_000;
  if (lower.includes('in a minute') || lower.includes('in 1 minute')) return 60_000;
  if (lower.includes('later')) return 15 * 60_000; // 15 minutes default for "later"
  if (lower.includes('tomorrow')) return 24 * 3600_000;

  // Default: 5 minutes
  return 5 * 60_000;
}

function extractReminderSummary(utterance: string): string | null {
  // "remind me to X in 5 minutes" → "X"
  const toMatch = /remind\s+me\s+(?:to\s+)?(.+?)(?:\s+in\s+\d+|\s+later|\s+tomorrow|$)/i.exec(utterance);
  if (toMatch) {
    const candidate = toMatch[1].trim();
    // Exclude bare deferral keywords from being treated as subjects
    if (candidate && !/^(later|tomorrow|defer|snooze)$/i.test(candidate)) return candidate;
  }

  return null;
}

function isDeferralUtterance(utterance: string): boolean {
  const lower = utterance.toLowerCase();
  return lower.includes('remind me later') || lower.includes('defer') || lower.includes('snooze');
}