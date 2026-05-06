import { buildBridgeRequest } from './request-builder';
import type { EarbudEvent, JarvisResponse, WorkspaceRegistry } from '../protocol/schemas';
import type { IntentName } from '../protocol/types';
import { resolveWorkspace } from '../policy/allowlists';
import { evaluateIntentPolicy } from '../policy/engine';
import { createActionId, isApprovalExpired } from '../policy/approvals';
import { describeIntent, resolveIntent } from '../jarvis/router';
import { JarvisRuntime } from '../jarvis/runtime';
import { SessionStore } from './session-store';
import { AuditLog } from './audit-log';

export class EventRouter {
  constructor(
    private readonly registry: WorkspaceRegistry,
    private readonly sessionStore: SessionStore,
    private readonly auditLog: AuditLog,
    private readonly jarvisRuntime: JarvisRuntime,
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
        if (backgroundCancelOutcome === 'running') {
          this.sessionStore.setState(request.sessionId, 'running');
          return this.respond(request, workspace.id, {
            speak: 'Task is already running and cannot be cancelled.',
            display: 'The approved background task has already started and will finish on its own.',
            requiresApproval: false,
            approvalRequest: null,
            actionId: null,
            status: 'blocked',
            nextState: 'running',
            followUpHint: 'Wait for completion notification',
          }, 'blocked');
        }

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
      case 'quick_status':
      case 'voice_command':
        return this.handleIntentRequest(request, workspace);
    }
  }

  private async handleIntentRequest(
    request: ReturnType<typeof buildBridgeRequest>,
    workspace: ReturnType<typeof resolveWorkspace>,
  ): Promise<JarvisResponse> {
    const intent = resolveIntent(request);
    const decision = evaluateIntentPolicy(intent, workspace);

    if (!decision.allowed) {
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

    if (decision.riskClass === 'approval_required' || decision.riskClass === 'hard_approval') {
      const actionId = createActionId();
      const expiresAt = new Date(Date.now() + request.riskPolicy.approvalTimeoutMs);
      const isHardApproval = decision.riskClass === 'hard_approval';
      this.sessionStore.setPending({
        actionId,
        sessionId: request.sessionId,
        workspace: workspace.id,
        intent,
        request,
        summary: describeIntent(intent),
        riskClass: decision.riskClass,
        expiresAt,
      });

      return this.respond(request, workspace.id, {
        speak: `${isHardApproval ? 'Hard approval required.' : ''} ${describeIntent(intent)}? Right double tap to approve.`.trim(),
        display: `${isHardApproval ? 'Hard approval required. ' : ''}${describeIntent(intent)} in workspace ${workspace.label}.`,
        requiresApproval: true,
        approvalRequest: {
          actionType: intent,
          summary: describeIntent(intent),
          riskClass: decision.riskClass,
          expiresInMs: request.riskPolicy.approvalTimeoutMs,
        },
        actionId,
        status: 'blocked',
        nextState: 'approval_pending',
        followUpHint: 'Right double tap approve, left double tap reject',
      }, 'approval_requested');
    }

    this.sessionStore.setState(request.sessionId, 'thinking');
    const response = await this.jarvisRuntime.executeIntent(intent, request, workspace);
    this.sessionStore.setState(request.sessionId, response.nextState);
    return this.respond(request, workspace.id, response, 'completed');
  }

  private async handleApproval(
    request: ReturnType<typeof buildBridgeRequest>,
    workspaceId: string,
  ): Promise<JarvisResponse> {
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
    });

    return response;
  }
}