import type { BridgeRequest } from '../protocol/schemas';
import type { IntentName } from '../protocol/types';

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

interface SessionStateRecord {
  state: string;
  touchedAt: number;
}

interface SessionSourceRecord {
  source: string;
  touchedAt: number;
}

export class SessionStore {
  private readonly states = new Map<string, SessionStateRecord>();
  private readonly sources = new Map<string, SessionSourceRecord>();
  private readonly pendingBySession = new Map<string, PendingAction>();

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

  private prune(now = Date.now()): void {
    for (const [sessionId, record] of this.states.entries()) {
      if (now - record.touchedAt > this.ttlMs) {
        this.states.delete(sessionId);
        this.sources.delete(sessionId);
        this.pendingBySession.delete(sessionId);
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
  }
}