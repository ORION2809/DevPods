import type {
  AgentRuntime,
  AgentRuntimeRequest,
  AgentRuntimeResponse,
  AgentCapabilitySnapshot,
  AgentRuntimeCallbacks,
  AgentAcknowledgement,
  AgentPlanConfirmation,
  AgentPlanResponse,
  AgentProgressEvent,
  AgentCompletionReport,
} from './agent-runtime-contract';
import type { IntelligenceLayer } from '../intelligence/intelligence-layer-contract';
import { CodeIntelligenceVoiceFormatter } from '../intelligence/voice-formatter';

export interface OpenClawAgentRuntimeOptions {
  /** When true, the stub simulates progress events synchronously before returning. */
  simulateProgress?: boolean;
  /** Mock planning estimate in milliseconds. */
  planningEstimateMs?: number;
  /** Optional intelligence layer for codebase perception. */
  intelligenceLayer?: IntelligenceLayer;
}

/**
 * Stub implementation of AgentRuntime for OpenClaw.
 *
 * This is a development placeholder that returns deterministic mock responses
 * for every agent lifecycle stage. Replace with real OpenClaw integration
 * when the agent brain is available.
 */
export class OpenClawAgentRuntime implements AgentRuntime {
  readonly kind = 'openclaw' as const;

  private disposed = false;
  private activeSessions = new Map<string, AbortController>();
  private readonly voiceFormatter = new CodeIntelligenceVoiceFormatter();

  constructor(private readonly options: OpenClawAgentRuntimeOptions = {}) {}

  async getCapabilities(workspaceId: string): Promise<AgentCapabilitySnapshot> {
    const indexState = this.options.intelligenceLayer
      ? await this.options.intelligenceLayer.getIndexState(workspaceId)
      : 'not_installed';
    return {
      runtime: 'openclaw',
      health: 'healthy',
      state: 'idle',
      supportsPlanConfirmation: true,
      supportsProgressEvents: true,
      intelligence: {
        available: this.options.intelligenceLayer != null && indexState !== 'not_installed' && indexState !== 'disabled',
        indexState,
        workspaces: this.options.intelligenceLayer ? [workspaceId] : [],
      },
    };
  }

  async handle(
    request: AgentRuntimeRequest,
    callbacks?: AgentRuntimeCallbacks,
  ): Promise<AgentRuntimeResponse> {
    if (this.disposed) {
      return this.buildErrorResponse(request, 'runtime_disposed', 'Agent runtime has been disposed.');
    }

    const controller = new AbortController();
    this.activeSessions.set(request.sessionId, controller);

    try {
      const response = await this.dispatchByMode(request, callbacks, controller.signal);
      return response;
    } finally {
      this.activeSessions.delete(request.sessionId);
    }
  }

  async cancel(sessionId: string, _actionId?: string): Promise<void> {
    this.activeSessions.get(sessionId)?.abort();
    this.activeSessions.delete(sessionId);
  }

  async dispose(): Promise<void> {
    this.disposed = true;
    for (const controller of this.activeSessions.values()) {
      controller.abort();
    }
    this.activeSessions.clear();
  }

  private async dispatchByMode(
    request: AgentRuntimeRequest,
    callbacks: AgentRuntimeCallbacks | undefined,
    signal: AbortSignal,
  ): Promise<AgentRuntimeResponse> {
    switch (request.mode) {
      case 'plan':
        return this.handlePlan(request, callbacks);
      case 'confirm_plan':
        return this.handleConfirmPlan(request, callbacks, signal);
      case 'execute_approved':
        return this.handleExecuteApproved(request, callbacks, signal);
      case 'cancel':
        return this.handleCancel(request);
      case 'report':
        return this.handleReport(request);
      case 'replan':
        return this.handleReplan(request, callbacks);
      case 'answer':
        return await this.handleAnswer(request);
      default:
        return this.buildErrorResponse(request, 'unsupported_mode', `Mode "${request.mode}" is not supported.`);
    }
  }

  private handlePlan(
    request: AgentRuntimeRequest,
    callbacks: AgentRuntimeCallbacks | undefined,
  ): AgentRuntimeResponse {
    const planId = this.generateId('plan');
    const estimateMs = this.options.planningEstimateMs ?? 8000;

    const acknowledgement: AgentAcknowledgement = {
      planId,
      requestId: request.requestId,
      sessionId: request.sessionId,
      intentUnderstood: request.utterance,
      planningEstimateMs: estimateMs,
    };

    callbacks?.onAcknowledgement?.(acknowledgement);

    const planConfirmation: AgentPlanConfirmation = {
      planId,
      requestId: request.requestId,
      sessionId: request.sessionId,
      summary: `Refactor ${request.utterance.slice(0, 40)}`,
      spokenSummary: '3 steps. Updates session store and approval gate. Low risk. Start?',
      confirmationPrompt: 'Right double tap to confirm, both hold to cancel.',
      riskClass: 'immediate',
      affectedFiles: ['src/bridge/session-store.ts', 'src/bridge/event-router.ts'],
      expectedCommands: [
        { description: 'Update session store', command: 'node', args: ['scripts/update-store.js'], riskClass: 'immediate', requiresApproval: false },
      ],
      steps: [
        { id: '1', title: 'Update session store', spokenSummary: 'Add plan storage', riskClass: 'immediate', affectedFiles: ['src/bridge/session-store.ts'], requiresApproval: false },
        { id: '2', title: 'Wire event router', spokenSummary: 'Route agent plans', riskClass: 'immediate', affectedFiles: ['src/bridge/event-router.ts'], requiresApproval: false },
        { id: '3', title: 'Run tests', spokenSummary: 'Run bridge tests', riskClass: 'approval_required', affectedFiles: [], requiresApproval: true },
      ],
      expiresAtMs: Date.now() + 60_000,
    };

    callbacks?.onPlanReady?.(planConfirmation);

    return {
      requestId: request.requestId,
      status: 'awaiting_plan_confirmation',
      response: this.buildJarvisResponse(request, 'Plan ready. Review on phone or confirm.'),
      acknowledgement,
      planConfirmation,
      planResponse: null,
      progress: [],
      completionReport: null,
      actionId: planId,
      error: null,
    };
  }

  private async handleConfirmPlan(
    request: AgentRuntimeRequest,
    callbacks: AgentRuntimeCallbacks | undefined,
    signal: AbortSignal,
  ): Promise<AgentRuntimeResponse> {
    const planId = request.approvedActionId ?? this.generateId('plan');

    const planResponse: AgentPlanResponse = {
      planId,
      decision: 'confirmed',
    };

    const progressEvents: AgentProgressEvent[] = [];

    if (this.options.simulateProgress && !signal.aborted) {
      const steps = [
        { kind: 'implementation_started' as const, summary: 'Starting implementation.', speak: 'Starting now.' },
        { kind: 'command_started' as const, summary: 'Updating session store.', speak: null },
        { kind: 'command_completed' as const, summary: 'Session store updated.', speak: null },
        { kind: 'tests_started' as const, summary: 'Running tests.', speak: 'Tests running.' },
        { kind: 'tests_completed' as const, summary: 'Tests passed.', speak: 'Tests passed.' },
      ];

      for (let i = 0; i < steps.length; i++) {
        if (signal.aborted) break;
        const step = steps[i];
        const event: AgentProgressEvent = {
          id: this.generateId('prog'),
          requestId: request.requestId,
          sessionId: request.sessionId,
          phase: 'running',
          kind: step.kind,
          summary: step.summary,
          speak: step.speak,
          display: step.summary,
          planId,
          actionId: planId,
          percent: Math.round(((i + 1) / steps.length) * 100),
          atMs: Date.now(),
        };
        progressEvents.push(event);
        await callbacks?.onProgress?.(event);
      }
    }

    if (signal.aborted) {
      return {
        requestId: request.requestId,
        status: 'cancelled',
        response: this.buildJarvisResponse(request, 'Plan cancelled.'),
        acknowledgement: null,
        planConfirmation: null,
        planResponse,
        progress: progressEvents,
        completionReport: null,
        actionId: planId,
        error: null,
      };
    }

    const completionReport: AgentCompletionReport = {
      planId,
      outcome: 'completed',
      completedSteps: 3,
      totalSteps: 3,
      summary: 'Done. Refactored approval flow across 2 files. Tests still passing.',
      requiresReview: false,
    };

    callbacks?.onCompletion?.(completionReport);

    return {
      requestId: request.requestId,
      status: 'done',
      response: this.buildJarvisResponse(request, completionReport.summary),
      acknowledgement: null,
      planConfirmation: null,
      planResponse,
      progress: progressEvents,
      completionReport,
      actionId: planId,
      error: null,
    };
  }

  private async handleExecuteApproved(
    request: AgentRuntimeRequest,
    callbacks: AgentRuntimeCallbacks | undefined,
    signal: AbortSignal,
  ): Promise<AgentRuntimeResponse> {
    // Same as confirm_plan for the stub
    return this.handleConfirmPlan(request, callbacks, signal);
  }

  private handleCancel(request: AgentRuntimeRequest): AgentRuntimeResponse {
    return {
      requestId: request.requestId,
      status: 'cancelled',
      response: this.buildJarvisResponse(request, 'Cancelled.'),
      acknowledgement: null,
      planConfirmation: null,
      planResponse: null,
      progress: [],
      completionReport: null,
      actionId: null,
      error: null,
    };
  }

  private handleReport(request: AgentRuntimeRequest): AgentRuntimeResponse {
    const planId = this.generateId('plan');
    const report: AgentCompletionReport = {
      planId,
      outcome: 'completed',
      completedSteps: 3,
      totalSteps: 3,
      summary: 'All steps completed. No further action needed.',
      requiresReview: false,
    };

    return {
      requestId: request.requestId,
      status: 'done',
      response: this.buildJarvisResponse(request, report.summary),
      acknowledgement: null,
      planConfirmation: null,
      planResponse: null,
      progress: [],
      completionReport: report,
      actionId: planId,
      error: null,
    };
  }

  private handleReplan(
    request: AgentRuntimeRequest,
    callbacks: AgentRuntimeCallbacks | undefined,
  ): AgentRuntimeResponse {
    // Return a new plan with the redirected intent
    const planId = this.generateId('plan');
    const estimateMs = this.options.planningEstimateMs ?? 5000;

    const acknowledgement: AgentAcknowledgement = {
      planId,
      requestId: request.requestId,
      sessionId: request.sessionId,
      intentUnderstood: request.utterance,
      planningEstimateMs: estimateMs,
    };

    callbacks?.onAcknowledgement?.(acknowledgement);

    const planConfirmation: AgentPlanConfirmation = {
      planId,
      requestId: request.requestId,
      sessionId: request.sessionId,
      summary: `Replan: ${request.utterance.slice(0, 40)}`,
      spokenSummary: '2 steps. Focused on requested area. Low risk. Start?',
      confirmationPrompt: 'Right double tap to confirm, both hold to cancel.',
      riskClass: 'immediate',
      affectedFiles: ['src/bridge/event-router.ts'],
      expectedCommands: [
        { description: 'Refocus change', command: 'node', args: ['scripts/refocus.js'], riskClass: 'immediate', requiresApproval: false },
      ],
      steps: [
        { id: '1', title: 'Refocus change', spokenSummary: 'Apply redirect', riskClass: 'immediate', affectedFiles: ['src/bridge/event-router.ts'], requiresApproval: false },
        { id: '2', title: 'Verify', spokenSummary: 'Run quick verification', riskClass: 'approval_required', affectedFiles: [], requiresApproval: true },
      ],
      expiresAtMs: Date.now() + 60_000,
    };

    callbacks?.onPlanReady?.(planConfirmation);

    return {
      requestId: request.requestId,
      status: 'awaiting_plan_confirmation',
      response: this.buildJarvisResponse(request, 'New plan ready. Review on phone or confirm.'),
      acknowledgement,
      planConfirmation,
      planResponse: null,
      progress: [],
      completionReport: null,
      actionId: planId,
      error: null,
    };
  }

  private async handleAnswer(request: AgentRuntimeRequest): Promise<AgentRuntimeResponse> {
    const intent = request.intentHint;
    const layer = this.options.intelligenceLayer;
    const context = { spokenWordBudget: request.constraints.spokenWordBudget, workspaceId: request.workspaceId, intentName: intent ?? 'answer' };

    if (layer && this.isIntelligenceIntent(intent) && request.constraints.intelligenceAvailable) {
      try {
        let draft: import('../intelligence/intelligence-layer-contract').JarvisResponseDraft;
        if (intent === 'impact_analysis') {
          const result = await layer.impact(request.utterance, request.workspaceId);
          draft = this.voiceFormatter.formatImpact(result, context);
        } else if (intent === 'detect_changes') {
          const result = await layer.detectChanges(request.workspaceId);
          draft = this.voiceFormatter.formatChanges(result, context);
        } else if (intent === 'query_symbol') {
          const result = await layer.query(request.utterance, request.workspaceId);
          draft = this.voiceFormatter.formatQuery(result, context);
        } else {
          const result = await layer.context(request.utterance, request.workspaceId);
          draft = this.voiceFormatter.formatContext(result, context);
        }
        return {
          requestId: request.requestId,
          status: 'done',
          response: {
            speak: draft.speak,
            display: draft.display,
            requiresApproval: false,
            approvalRequest: null,
            actionId: null,
            status: 'completed',
            nextState: 'idle',
            followUpHint: draft.followUpHint,
          },
          acknowledgement: null,
          planConfirmation: null,
          planResponse: null,
          progress: [],
          completionReport: null,
          actionId: null,
          error: null,
        };
      } catch {
        // Fall through to generic answer on intelligence failure
      }
    }

    return {
      requestId: request.requestId,
      status: 'done',
      response: this.buildJarvisResponse(request, `Answer: ${request.utterance.slice(0, 60)}`),
      acknowledgement: null,
      planConfirmation: null,
      planResponse: null,
      progress: [],
      completionReport: null,
      actionId: null,
      error: null,
    };
  }

  private isIntelligenceIntent(intent: string | null): boolean {
    return intent === 'impact_analysis' || intent === 'detect_changes' || intent === 'query_symbol' || intent === 'explain_flow';
  }

  private buildJarvisResponse(request: AgentRuntimeRequest, speak: string) {
    return {
      speak,
      display: speak,
      requiresApproval: false,
      approvalRequest: null,
      actionId: null,
      status: 'completed' as const,
      nextState: 'idle' as const,
      followUpHint: null,
    };
  }

  private buildErrorResponse(
    request: AgentRuntimeRequest,
    code: string,
    message: string,
  ): AgentRuntimeResponse {
    return {
      requestId: request.requestId,
      status: 'error',
      response: this.buildJarvisResponse(request, message),
      acknowledgement: null,
      planConfirmation: null,
      planResponse: null,
      progress: [],
      completionReport: null,
      actionId: null,
      error: { code, message, retryable: code !== 'runtime_disposed' },
    };
  }

  private generateId(prefix: string): string {
    return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
  }
}
