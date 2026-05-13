import path from 'node:path';
import type { EarbudEvent, JarvisResponse, WorkspaceRegistry } from '../protocol/schemas';
import { loadWorkspaceRegistry } from '../policy/allowlists';
import { AuditLog } from './audit-log';
import { EventRouter } from './event-router';
import { SessionStore } from './session-store';
import { JarvisRuntime } from '../jarvis/runtime';
import { createDefaultNotifier, type Notifier } from './speaker';
import {
  OpenClawGatewayClient,
  type OpenClawGatewayOptions,
  type OpenClawRewriteHealthSnapshot,
} from '../openclaw/client';
import { preflightOpenClawOptions } from '../openclaw/validation';
import { classifyError } from './error-handler';

const CIRCUIT_BREAKER_THRESHOLD = 5;
const CIRCUIT_BREAKER_RESET_MS = 30_000;

export interface BridgeRuntimeOptions {
  configPath?: string;
  registry?: WorkspaceRegistry;
  auditLogPath?: string;
  notifier?: Notifier;
  brainMode?: 'local' | 'openclaw';
  openclaw?: OpenClawGatewayOptions;
}

function syncSessionResponse(sessionStore: SessionStore, sessionId: string, response: JarvisResponse): void {
  sessionStore.setState(sessionId, response.nextState);

  if (response.autonomy?.nextIntent != null && response.autonomy.continueAfterMs != null) {
    sessionStore.setAutonomy(sessionId, response.autonomy);
    return;
  }

  sessionStore.clearAutonomy(sessionId);
}

export class BridgeRuntime {
  private consecutiveFailures = 0;
  private degradedSince: number | null = null;

  constructor(
    readonly eventRouter: EventRouter,
    private readonly sessionStore: SessionStore,
    private readonly notifier: Notifier,
    private readonly openclawClient: OpenClawGatewayClient | null,
  ) {}

  getOpenClawHealthSnapshot(): OpenClawRewriteHealthSnapshot | null {
    return this.openclawClient?.getHealthSnapshot() ?? null;
  }

  getHealthStatus(): { degraded: boolean; since: string | null } {
    return {
      degraded: this.degradedSince !== null,
      since: this.degradedSince ? new Date(this.degradedSince).toISOString() : null,
    };
  }

  isDegraded(): boolean {
    return this.degradedSince !== null;
  }

  async handleEvent(event: EarbudEvent): Promise<JarvisResponse> {
    if (this.degradedSince !== null && Date.now() - this.degradedSince > CIRCUIT_BREAKER_RESET_MS) {
      this.degradedSince = null;
      this.consecutiveFailures = 0;
    }

    if (this.isDegraded()) {
      return {
        speak: 'Bridge is currently in degraded mode. Please try again shortly.',
        display: 'The bridge is experiencing issues and has entered degraded mode.',
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'error',
        nextState: 'idle',
        followUpHint: null,
      };
    }

    try {
      this.sessionStore.setSource(event.sessionId, event.source);
      const response = await this.eventRouter.dispatch(event);
      const finalResponse = await this.openclawClient?.rewriteResponse({
        response,
        event,
        source: 'foreground',
      }) ?? response;
      syncSessionResponse(this.sessionStore, event.sessionId, finalResponse);
      if (event.source !== 'android_relay') {
        await this.notifier.notify(finalResponse);
      }
      this.consecutiveFailures = 0;
      return finalResponse;
    } catch (error) {
      this.consecutiveFailures += 1;
      if (this.consecutiveFailures >= CIRCUIT_BREAKER_THRESHOLD) {
        this.degradedSince = Date.now();
      }
      const classified = classifyError(error);
      return {
        speak: classified.userMessage,
        display: classified.userMessage,
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'error',
        nextState: 'idle',
        followUpHint: null,
      };
    }
  }
}

export function createBridgeRuntime(options: BridgeRuntimeOptions = {}): BridgeRuntime {
  const configPath = options.configPath ?? path.resolve(process.cwd(), 'config/workspaces.json');
  const registry = options.registry ?? loadWorkspaceRegistry(configPath);
  const auditLog = new AuditLog(options.auditLogPath ?? path.resolve(process.cwd(), 'runtime-data/audit.log'));
  const sessionStore = new SessionStore();
  const notifier = options.notifier ?? createDefaultNotifier();
  if (options.brainMode === 'openclaw' && options.openclaw) {
    preflightOpenClawOptions(options.openclaw);
  }
  const openclawClient = options.brainMode === 'openclaw' && options.openclaw
    ? new OpenClawGatewayClient(options.openclaw)
    : null;
  openclawClient?.prime();
  const jarvisRuntime = new JarvisRuntime(auditLog, async (sessionId, response) => {
    const finalResponse = await openclawClient?.rewriteResponse({
      response,
      source: 'background',
    }) ?? response;
    syncSessionResponse(sessionStore, sessionId, finalResponse);
    if (sessionStore.getSource(sessionId) !== 'android_relay') {
      await notifier.notify(finalResponse);
    }
  });
  const eventRouter = new EventRouter(registry, sessionStore, auditLog, jarvisRuntime);
  return new BridgeRuntime(eventRouter, sessionStore, notifier, openclawClient);
}
