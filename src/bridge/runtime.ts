import path from 'node:path';
import type {
  EarbudEvent,
  JarvisResponse,
  WorkspaceRegistry,
  DevPodsInstalledTier,
  BridgeCapabilitySnapshot,
  IntelligenceWorkspaceCapability,
  IntelligenceIndexState,
} from '../protocol/schemas';
import { loadWorkspaceRegistry, resolveWorkspace } from '../policy/allowlists';
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
import { OutboxStore } from '../personalization/outbox-store';
import { NotificationPreferenceStore } from '../personalization/notification-preference-store';
import { ReminderStore } from '../personalization/reminder-store';
import { VoiceHabitStore } from '../personalization/voice-habit-store';
import { WorkspaceAwarenessService } from '../personalization/workspace-awareness-service';
import { NudgePolicyStore } from '../personalization/nudge-policy-store';
import { WorkspaceSnapshotCache } from '../jarvis/workspace-snapshot-cache';
import { TierConfigStore } from '../personalization/tier-config-store';
import { OpenClawAgentRuntime } from '../agent/openclaw-agent-runtime';
import { AgentRuntimeDispatcher } from '../agent/agent-runtime-dispatcher';
import { StubIntelligenceLayer } from '../intelligence/stub-intelligence-layer';
import { GitNexusIntelligenceLayer } from '../intelligence/gitnexus';
import type { IntelligenceLayer } from '../intelligence/intelligence-layer-contract';
import { createReadOnlyIntelligenceLayer } from '../intelligence/read-only-guard';

const CIRCUIT_BREAKER_THRESHOLD = 5;
const CIRCUIT_BREAKER_RESET_MS = 30_000;

export interface BridgeRuntimeOptions {
  configPath?: string;
  registry?: WorkspaceRegistry;
  auditLogPath?: string;
  notifier?: Notifier;
  brainMode?: 'local' | 'openclaw';
  /** Installed product tier. Defaults to 'core' for backward compatibility. */
  tier?: DevPodsInstalledTier;
  /** Path to the tier configuration store. Defaults to runtime-data/tier-config.json. */
  tierConfigStorePath?: string;
  openclaw?: OpenClawGatewayOptions;
  outboxStorePath?: string;
  notificationPreferenceStorePath?: string;
  reminderStorePath?: string;
  voiceHabitStorePath?: string;
  workspaceAwarenessConfig?: Partial<import('../personalization/workspace-awareness-service').WorkspaceAwarenessConfig>;
  nudgePolicyStorePath?: string;
  workspaceSnapshotCache?: WorkspaceSnapshotCache;
  /** When false, the workspace snapshot cache is bypassed and all reads go directly to the adapters. */
  workspaceSnapshotCacheEnabled?: boolean;
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
  private lastErrorCategory: string | null = null;

  constructor(
    readonly eventRouter: EventRouter,
    private readonly sessionStore: SessionStore,
    private readonly notifier: Notifier,
    private readonly openclawClient: OpenClawGatewayClient | null,
    readonly outboxStore: OutboxStore,
    readonly notificationPreferenceStore: NotificationPreferenceStore,
    readonly reminderStore: ReminderStore,
    readonly voiceHabitStore: VoiceHabitStore,
    readonly nudgePolicyStore: NudgePolicyStore,
    readonly workspaceAwarenessService: WorkspaceAwarenessService | null,
    readonly workspaceSnapshotCache: WorkspaceSnapshotCache = new WorkspaceSnapshotCache(),
    readonly registry: WorkspaceRegistry,
    readonly workspaceSnapshotCacheEnabled: boolean = true,
    private readonly tier: DevPodsInstalledTier = 'core',
    private readonly tierConfigStore: TierConfigStore | null = null,
    private readonly intelligenceLayer: IntelligenceLayer | null = null,
  ) {}

  getOpenClawHealthSnapshot(): OpenClawRewriteHealthSnapshot | null {
    return this.openclawClient?.getHealthSnapshot() ?? null;
  }

  getTier(): DevPodsInstalledTier {
    return this.tier;
  }

  async getCapabilitySnapshot(workspaceId?: string): Promise<BridgeCapabilitySnapshot> {
    const openclawHealth = this.openclawClient?.getHealthSnapshot() ?? null;
    const openclawHealthy = openclawHealth !== null && (openclawHealth.connectionState === 'connected' || openclawHealth.connectionState === 'not_applicable');
    const healthStatus = this.getHealthStatus();
    const isAgentTier = this.tier === 'agent' || this.tier === 'intelligence';
    const isIntelligenceTier = this.tier === 'intelligence';

    const consent = workspaceId && this.tierConfigStore
      ? this.tierConfigStore.getIntelligenceConsent(workspaceId)
      : null;
    const hasAnyConsent = isIntelligenceTier && this.tierConfigStore
      ? Object.values(this.tierConfigStore.getConfig().intelligenceConsent).some((c) => c.consented)
      : false;
    const intelligenceAvailable = workspaceId
      ? isIntelligenceTier && (consent?.consented ?? false)
      : isIntelligenceTier && hasAnyConsent;

    const indexedWorkspaces = isIntelligenceTier && this.tierConfigStore
      ? Object.entries(this.tierConfigStore.getConfig().intelligenceConsent)
          .filter(([, c]) => c.consented)
          .map(([id]) => id)
      : [];

    let currentWorkspace: IntelligenceWorkspaceCapability | null = null;
    if (workspaceId && intelligenceAvailable && this.intelligenceLayer) {
      const indexState = await this.intelligenceLayer.getIndexState(workspaceId);
      currentWorkspace = {
        workspaceId,
        indexState,
        indexedCommit: null,
        lastIndexedAtMs: null,
        stalenessReason: indexState === 'stale' ? 'Index is out of date' : null,
      };
    }

    let indexState: IntelligenceIndexState;
    if (!isIntelligenceTier) {
      indexState = 'not_installed';
    } else if (!intelligenceAvailable) {
      indexState = 'disabled';
    } else if (this.intelligenceLayer && workspaceId) {
      indexState = await this.intelligenceLayer.getIndexState(workspaceId);
    } else {
      indexState = 'not_indexed';
    }

    return {
      tier: this.tier,
      capabilities: {
        core: {
          available: true,
          voiceLoop: true,
          approvals: true,
          gitBasics: true,
        },
        agent: {
          available: isAgentTier,
          runtime: isAgentTier ? 'openclaw' : 'none',
          healthy: isAgentTier && openclawHealthy && !healthStatus.degraded,
          state: isAgentTier && openclawHealthy ? 'idle' : 'blocked',
          degradedReason: healthStatus.degraded
            ? (healthStatus.lastErrorCategory ?? 'bridge_degraded')
            : (!openclawHealthy && isAgentTier ? 'openclaw_unhealthy' : null),
        },
        intelligence: {
          available: intelligenceAvailable,
          indexState,
          workspaces: indexedWorkspaces,
          currentWorkspace,
        },
      },
    };
  }

  enableQuickStart(sessionId: string): void {
    this.sessionStore.enableQuickStart(sessionId);
  }

  disableQuickStart(sessionId: string): void {
    this.sessionStore.disableQuickStart(sessionId);
  }

  isQuickStart(sessionId: string): boolean {
    return this.sessionStore.isQuickStart(sessionId);
  }

  getHealthStatus(): { degraded: boolean; since: string | null; lastErrorCategory: string | null; queueDepth: ReturnType<SessionStore['getQueueDepth']>; outboxPendingCount: number; workspaceAwarenessRunning: boolean; workspaceSnapshotCacheEnabled: boolean } {
    return {
      degraded: this.degradedSince !== null,
      since: this.degradedSince ? new Date(this.degradedSince).toISOString() : null,
      lastErrorCategory: this.lastErrorCategory,
      queueDepth: this.sessionStore.getQueueDepth(),
      outboxPendingCount: this.outboxStore.getTotalPendingCount(),
      workspaceAwarenessRunning: this.workspaceAwarenessService?.isRunning() ?? false,
      workspaceSnapshotCacheEnabled: this.workspaceSnapshotCacheEnabled,
    };
  }

  isDegraded(): boolean {
    return this.degradedSince !== null;
  }

  dispose(): void {
    this.workspaceAwarenessService?.stop();
  }

  prefetchWorkspaceData(_sessionId: string, workspaceId: string, kinds: string[]): void {
    if (!this.workspaceSnapshotCacheEnabled) return;
    try {
      const workspace = resolveWorkspace(this.registry, workspaceId);
      const rootPath = workspace.rootPath;
      if (kinds.includes('workspace_status') || kinds.includes('diff_stat')) {
        this.workspaceSnapshotCache.prefetch(rootPath).catch(() => {
          // Safe fire-and-forget prewarm
        });
      }
      if (kinds.includes('ci_snapshot')) {
        this.workspaceSnapshotCache.prefetchCiSnapshot(rootPath).catch(() => {
          // Safe fire-and-forget prewarm
        });
      }
    } catch {
      // Workspace not allowlisted or unavailable; ignore prefetch
    }
  }

  private isOpenClawHealthy(): boolean {
    const health = this.openclawClient?.getHealthSnapshot();
    if (!health) {
      return false;
    }
    if (health.connectionState === 'failed') {
      return false;
    }
    // If recent failures exceed successes, treat as unhealthy
    const totalRecent = health.counters.rewritten + health.counters.failed + health.counters.timedOut;
    if (totalRecent > 0 && health.counters.failed + health.counters.timedOut > health.counters.rewritten) {
      return false;
    }
    return true;
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
      const finalResponse = this.isOpenClawHealthy()
        ? await this.openclawClient?.rewriteResponse({
          response,
          event,
          source: 'foreground',
        }) ?? response
        : response;
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
      this.lastErrorCategory = classified.category;
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
  const outboxStore = new OutboxStore(options.outboxStorePath ?? path.resolve(process.cwd(), 'runtime-data/outbox.json'));
  const notificationPreferenceStore = new NotificationPreferenceStore(
    options.notificationPreferenceStorePath ?? path.resolve(process.cwd(), 'runtime-data/notification-preferences.json'),
  );
  const reminderStore = new ReminderStore(
    options.reminderStorePath ?? path.resolve(process.cwd(), 'runtime-data/reminders.json'),
  );
  reminderStore.setOutboxStore(outboxStore);
  const voiceHabitStore = new VoiceHabitStore(
    options.voiceHabitStorePath ?? path.resolve(process.cwd(), 'runtime-data/voice-habits.json'),
  );
  if (options.brainMode === 'openclaw' && options.openclaw) {
    preflightOpenClawOptions(options.openclaw);
  }
  const openclawClient = options.brainMode === 'openclaw' && options.openclaw
    ? new OpenClawGatewayClient(options.openclaw)
    : null;
  openclawClient?.prime();
  const workspaceSnapshotCache = options.workspaceSnapshotCache ?? new WorkspaceSnapshotCache();
  const jarvisRuntime = new JarvisRuntime(auditLog, async (sessionId, response) => {
    const finalResponse = await openclawClient?.rewriteResponse({
      response,
      source: 'background',
    }) ?? response;
    syncSessionResponse(sessionStore, sessionId, finalResponse);
    if (sessionStore.getSource(sessionId) !== 'android_relay') {
      await notifier.notify(finalResponse);
    }
    // Enqueue soft ping for Android-originated sessions so the phone can poll
    const source = sessionStore.getSource(sessionId);
    if (source === 'android_relay') {
      const isError = finalResponse.status === 'error';
      outboxStore.enqueue({
        sessionId,
        priority: isError ? 'high' : 'normal',
        kind: isError ? 'completion_full_report' : 'completion_soft_ping',
        summary: finalResponse.speak.slice(0, 120),
        detail: finalResponse.display ?? undefined,
        actionId: finalResponse.actionId ?? undefined,
        expiresAtMs: Date.now() + 3600_000,
      });
    }
  }, workspaceSnapshotCache);
  const nudgePolicyStore = new NudgePolicyStore(
    options.nudgePolicyStorePath ?? path.resolve(process.cwd(), 'runtime-data/nudge-policies.json'),
  );
  const workspaceAwarenessService = new WorkspaceAwarenessService(
    registry.workspaces,
    outboxStore,
    nudgePolicyStore,
    options.workspaceAwarenessConfig,
  );
  workspaceAwarenessService.start();
  const tierConfigStore = new TierConfigStore(
    options.tierConfigStorePath ?? path.resolve(process.cwd(), 'runtime-data/tier-config.json'),
  );
  const isAgentTier = (options.tier ?? 'core') === 'agent' || (options.tier ?? 'core') === 'intelligence';
  const isIntelligenceTier = (options.tier ?? 'core') === 'intelligence';
  const rawIntelligenceLayer = isIntelligenceTier
    ? new GitNexusIntelligenceLayer({ indexBasePath: path.resolve(process.cwd(), 'runtime-data/intelligence') })
    : undefined;
  const intelligenceLayer = rawIntelligenceLayer ? createReadOnlyIntelligenceLayer(rawIntelligenceLayer) : undefined;
  const agentRuntime = isAgentTier ? new OpenClawAgentRuntime({ simulateProgress: false, intelligenceLayer }) : null;
  const agentRuntimeDispatcher = agentRuntime
    ? new AgentRuntimeDispatcher({ agentRuntime, sessionStore, auditLog, tier: options.tier ?? 'core', tierConfigStore })
    : null;
  const eventRouter = new EventRouter(registry, sessionStore, auditLog, jarvisRuntime, voiceHabitStore, outboxStore, reminderStore, agentRuntimeDispatcher);
  return new BridgeRuntime(
    eventRouter,
    sessionStore,
    notifier,
    openclawClient,
    outboxStore,
    notificationPreferenceStore,
    reminderStore,
    voiceHabitStore,
    nudgePolicyStore,
    workspaceAwarenessService,
    workspaceSnapshotCache,
    registry,
    options.workspaceSnapshotCacheEnabled ?? true,
    options.tier ?? 'core',
    tierConfigStore,
    intelligenceLayer ?? null,
  );
}
