import type { AutonomyInstruction, BridgeRequest } from '../protocol/schemas';
import type { IntentName } from '../protocol/types';
import type { AgentPlanConfirmation } from '../agent/agent-runtime-contract';

export interface PendingAction {
  actionId: string;
  sessionId: string;
  workspace: string;
  intent: IntentName;
  request: BridgeRequest;
  summary: string;
  riskClass: 'approval_required' | 'hard_approval';
  expiresAt: Date;
}

export interface ActivePlan {
  planId: string;
  sessionId: string;
  workspace: string;
  planConfirmation: AgentPlanConfirmation;
  status: 'awaiting_confirmation' | 'confirmed' | 'cancelled' | 'redirected' | 'running';
  redirectUtterance?: string;
  createdAtMs: number;
}

interface SessionStateRecord {
  state: string;
  touchedAt: number;
}

interface SessionSourceRecord {
  source: string;
  touchedAt: number;
}

interface SessionAutonomyRecord {
  autonomy: AutonomyInstruction;
  expiresAt: number;
}

interface CompletionContext {
  summary: string;
  completedAtMs: number;
}

export class SessionStore {
  private readonly states = new Map<string, SessionStateRecord>();
  private readonly sources = new Map<string, SessionSourceRecord>();
  private readonly pendingBySession = new Map<string, PendingAction>();
  private readonly autonomyBySession = new Map<string, SessionAutonomyRecord>();
  private readonly quickStartSessions = new Set<string>();
  private readonly completionContextBySession = new Map<string, CompletionContext>();
  private readonly activePlanBySession = new Map<string, ActivePlan>();

  constructor(private readonly ttlMs = 60 * 60 * 1000) {}

  setState(sessionId: string, state: string): void {
    this.prune();
    this.states.set(sessionId, { state, touchedAt: Date.now() });
  }

  getState(sessionId: string): string {
    this.prune();
    const record = this.states.get(sessionId);
    if (!record) {
      return 'idle';
    }

    this.states.set(sessionId, { ...record, touchedAt: Date.now() });
    return record.state;
  }

  setSource(sessionId: string, source: string): void {
    this.prune();
    this.sources.set(sessionId, { source, touchedAt: Date.now() });
  }

  getSource(sessionId: string): string | null {
    this.prune();
    const record = this.sources.get(sessionId);
    if (!record) {
      return null;
    }

    this.sources.set(sessionId, { ...record, touchedAt: Date.now() });
    return record.source;
  }

  setPending(action: PendingAction): void {
    this.prune();
    this.pendingBySession.set(action.sessionId, action);
    this.setState(action.sessionId, 'approval_pending');
  }

  getPending(sessionId: string, actionId: string, workspaceId: string): PendingAction | null {
    this.prune();
    const pending = this.pendingBySession.get(sessionId) ?? null;
    if (!pending) {
      return null;
    }

    if (pending.actionId !== actionId || pending.workspace !== workspaceId) {
      return null;
    }

    this.setState(sessionId, 'approval_pending');

    return pending;
  }

  clearPending(sessionId: string): void {
    this.pendingBySession.delete(sessionId);
  }

  setAutonomy(sessionId: string, autonomy: AutonomyInstruction): void {
    this.prune();

    if (autonomy.continueAfterMs == null || autonomy.nextIntent == null) {
      this.autonomyBySession.delete(sessionId);
      return;
    }

    this.autonomyBySession.set(sessionId, {
      autonomy,
      expiresAt: Date.now() + autonomy.continueAfterMs,
    });
  }

  getAutonomy(sessionId: string): AutonomyInstruction | null {
    this.prune();

    const record = this.autonomyBySession.get(sessionId) ?? null;
    if (!record) {
      return null;
    }

    if (record.expiresAt < Date.now()) {
      this.autonomyBySession.delete(sessionId);
      return null;
    }

    return record.autonomy;
  }

  clearAutonomy(sessionId: string): void {
    this.autonomyBySession.delete(sessionId);
  }

  getQueueDepth(): { sessions: number; pending: number; autonomy: number } {
    this.prune();
    return {
      sessions: this.states.size,
      pending: this.pendingBySession.size,
      autonomy: this.autonomyBySession.size,
    };
  }

  enableQuickStart(sessionId: string): void {
    this.quickStartSessions.add(sessionId);
  }

  disableQuickStart(sessionId: string): void {
    this.quickStartSessions.delete(sessionId);
  }

  isQuickStart(sessionId: string): boolean {
    return this.quickStartSessions.has(sessionId);
  }

  setCompletionContext(sessionId: string, summary: string): void {
    this.completionContextBySession.set(sessionId, {
      summary,
      completedAtMs: Date.now(),
    });
  }

  getCompletionContext(sessionId: string, windowMs = 300_000): CompletionContext | null {
    const ctx = this.completionContextBySession.get(sessionId) ?? null;
    if (!ctx) return null;
    if (Date.now() - ctx.completedAtMs > windowMs) {
      this.completionContextBySession.delete(sessionId);
      return null;
    }
    return ctx;
  }

  clearCompletionContext(sessionId: string): void {
    this.completionContextBySession.delete(sessionId);
  }

  setActivePlan(plan: ActivePlan): void {
    this.prune();
    this.activePlanBySession.set(plan.sessionId, plan);
    this.setState(plan.sessionId, 'awaiting_plan_confirmation');
  }

  getActivePlan(sessionId: string): ActivePlan | null {
    this.prune();
    return this.activePlanBySession.get(sessionId) ?? null;
  }

  clearActivePlan(sessionId: string): void {
    this.activePlanBySession.delete(sessionId);
  }

  private prune(now = Date.now()): void {
    for (const [sessionId, record] of this.states.entries()) {
      if (now - record.touchedAt > this.ttlMs) {
        this.states.delete(sessionId);
        this.sources.delete(sessionId);
        this.pendingBySession.delete(sessionId);
        this.autonomyBySession.delete(sessionId);
      }
    }

    for (const [sessionId, record] of this.sources.entries()) {
      if (now - record.touchedAt > this.ttlMs) {
        this.sources.delete(sessionId);
      }
    }

    for (const [sessionId, pending] of this.pendingBySession.entries()) {
      if (pending.expiresAt.getTime() < now) {
        this.pendingBySession.delete(sessionId);
      }
    }

    for (const [sessionId, autonomy] of this.autonomyBySession.entries()) {
      if (autonomy.expiresAt < now) {
        this.autonomyBySession.delete(sessionId);
      }
    }

    for (const sessionId of this.quickStartSessions) {
      if (!this.states.has(sessionId) && !this.sources.has(sessionId)) {
        this.quickStartSessions.delete(sessionId);
      }
    }

    for (const [sessionId, ctx] of this.completionContextBySession.entries()) {
      if (now - ctx.completedAtMs > this.ttlMs) {
        this.completionContextBySession.delete(sessionId);
      }
    }

    for (const [sessionId, plan] of this.activePlanBySession.entries()) {
      if (now - plan.createdAtMs > this.ttlMs || now > plan.planConfirmation.expiresAtMs) {
        this.activePlanBySession.delete(sessionId);
      }
    }
  }
}

const IDEMPOTENCY_TTL_MS = 5 * 60 * 1000; // 5 minutes

interface CompletedIdempotencyRecord<T> {
  processedAt: number;
  response: T;
}

interface PendingIdempotencyRecord<T> {
  processedAt: number;
  responsePromise: Promise<T>;
}

type IdempotencyRecord<T> =
  | { status: 'completed'; value: CompletedIdempotencyRecord<T> }
  | { status: 'pending'; value: PendingIdempotencyRecord<T> };

export class IdempotencyStore<T> {
  private readonly keys = new Map<string, IdempotencyRecord<T>>();

  async executeOnce(key: string, producer: () => Promise<T>): Promise<{ response: T; replayed: boolean }> {
    this.prune();

    const existing = this.keys.get(key);
    if (existing) {
      if (existing.status == 'completed') {
        return { response: existing.value.response, replayed: true };
      }

      const response = await existing.value.responsePromise;
      return { response, replayed: true };
    }

    const responsePromise = producer();
    this.keys.set(key, {
      status: 'pending',
      value: {
        processedAt: Date.now(),
        responsePromise,
      },
    });

    try {
      const response = await responsePromise;
      this.keys.set(key, {
        status: 'completed',
        value: {
          processedAt: Date.now(),
          response,
        },
      });
      return { response, replayed: false };
    } catch (error) {
      this.keys.delete(key);
      throw error;
    }
  }

  private prune(now = Date.now()): void {
    for (const [key, record] of this.keys.entries()) {
      if (now - record.value.processedAt > IDEMPOTENCY_TTL_MS) {
        this.keys.delete(key);
      }
    }
  }
}
