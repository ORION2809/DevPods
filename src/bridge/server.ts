import { randomBytes, timingSafeEqual } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { resolveOpenClawRewritePolicy } from '../openclaw/client';
import { renderPairingQrDataUrl } from '../pairing/qr';
import { buildRelayPairingPageUrl, buildRelayPairingUri } from '../pairing/uri';
import { earbudEventSchema, androidRelayEventSchema, SUPPORTED_PROTOCOL_VERSIONS, pairingVerifyRequestSchema, outboxPollResponseSchema, notificationPreferenceSchema, reminderCreateRequestSchema, learnedPhraseSchema, nudgePolicySchema, prefetchRequestSchema, type JarvisResponse, type WorkspaceRegistry } from '../protocol/schemas';
import type { IntentName } from '../protocol/types';
import { createBridgeRuntime, type BridgeRuntime, type BridgeRuntimeOptions } from './runtime';
import { classifyError } from './error-handler';
import { IdempotencyStore } from './session-store';

const MAX_REQUEST_BYTES = 8 * 1024;
const DEFAULT_SERVER_TIMEOUT_MS = 30_000;
const SERVER_TIMEOUT_BUFFER_MS = 30_000;
const PAIRING_CODE_TTL_MS = 5 * 60 * 1000;
const RATE_LIMIT_WINDOW_MS = 60_000;
const RATE_LIMIT_MAX_REQUESTS = 100;
const BRAND_ASSET_DIR_CANDIDATES = [
  path.resolve(process.cwd(), 'assets', 'brand', 'source'),
  path.resolve(__dirname, '..', '..', 'assets', 'brand', 'source'),
  path.resolve(__dirname, '..', '..', '..', 'assets', 'brand', 'source'),
];
const brandAssetCache = new Map<string, string | null>();

class RequestBodyTooLargeError extends Error {
  constructor() {
    super('Request body too large');
  }
}

class SessionRateLimiter {
  private readonly sessions = new Map<string, { count: number; resetAt: number }>();

  isAllowed(sessionId: string): boolean {
    const now = Date.now();
    const record = this.sessions.get(sessionId);
    if (!record || now > record.resetAt) {
      this.sessions.set(sessionId, { count: 1, resetAt: now + RATE_LIMIT_WINDOW_MS });
      return true;
    }
    if (record.count >= RATE_LIMIT_MAX_REQUESTS) {
      return false;
    }
    record.count += 1;
    return true;
  }
}

export interface BridgeServerOptions extends BridgeRuntimeOptions {
  configPath?: string;
  registry?: WorkspaceRegistry;
  auditLogPath?: string;
  relayToken?: string;
  pairingBaseUrl?: string;
  pairingCode?: string;
  /** Explicitly advertised features. If omitted, defaults to all implemented features. */
  features?: string[];
}

function generatePairingCode(): string {
  return randomBytes(4).toString('base64url').slice(0, 6).toUpperCase();
}

export function createBridgeServer(options: BridgeServerOptions = {}): {
  server: http.Server;
  runtime: BridgeRuntime;
} {
  const runtime = createBridgeRuntime(options as BridgeRuntimeOptions);
  let activePairingCode: string | null = options.relayToken ? (options.pairingCode ?? generatePairingCode()) : null;
  let activePairingCodeExpiresAt: number | null = activePairingCode ? Date.now() + PAIRING_CODE_TTL_MS : null;
  const rateLimiter = new SessionRateLimiter();
  const idempotencyStore = new IdempotencyStore<JarvisResponse>();

  function isPairingCodeValid(): boolean {
    if (!activePairingCode || !activePairingCodeExpiresAt) return false;
    if (Date.now() > activePairingCodeExpiresAt) {
      activePairingCode = null;
      activePairingCodeExpiresAt = null;
      return false;
    }
    return true;
  }

  function rotatePairingCode(): string | null {
    if (!options.relayToken) return null;
    activePairingCode = generatePairingCode();
    activePairingCodeExpiresAt = Date.now() + PAIRING_CODE_TTL_MS;
    return activePairingCode;
  }

  const server = http.createServer(async (request, response) => {
    const startTime = Date.now();
    response.on('finish', () => {
      const duration = Date.now() - startTime;
      const timestamp = new Date().toISOString();
      process.stderr.write(`[${timestamp}] ${request.method} ${request.url} ${response.statusCode} ${duration}ms\n`);
    });

    try {
      if (requiresRelayAuthorization(request) && !isAuthorizedRequest(request, options.relayToken)) {
        response.writeHead(401, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({ error: 'Unauthorized' }));
        return;
      }

      if (request.method === 'GET' && request.url === '/health') {
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify(buildHealthPayload(runtime, options)));
        return;
      }

      if (request.method === 'GET' && request.url === '/pairing') {
        const validCode = isPairingCodeValid() ? activePairingCode : rotatePairingCode();
        const pairingPayload = buildPairingPayload(options, validCode);
        if (!pairingPayload) {
          respondWithPairingUnavailable(request, response);
          return;
        }

        if (prefersJson(request)) {
          response.writeHead(200, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify(pairingPayload));
          return;
        }

        try {
          response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
          response.end(await renderPairingPage(pairingPayload));
          return;
        } catch (error) {
          const classified = classifyError(error);
          response.writeHead(500, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify({ error: classified.userMessage, category: classified.category }));
          return;
        }
      }

      if (request.method === 'POST' && request.url === '/pairing/verify') {
        const body = await readRequestBody(request);
        let parsedBody: unknown;

        try {
          parsedBody = JSON.parse(body);
        } catch {
          response.writeHead(400, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify({ error: 'Invalid JSON' }));
          return;
        }

        const verifyRequest = pairingVerifyRequestSchema.safeParse(parsedBody);
        if (!verifyRequest.success) {
          response.writeHead(400, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify({ error: 'Invalid verify payload' }));
          return;
        }

        if (!options.relayToken) {
          response.writeHead(200, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify({ relayToken: '' }));
          return;
        }

        if (isPairingCodeValid() && activePairingCode && timingSafeEqual(Buffer.from(activePairingCode), Buffer.from(verifyRequest.data.pairingCode))) {
          activePairingCode = null;
          activePairingCodeExpiresAt = null;
          response.writeHead(200, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify({ relayToken: options.relayToken }));
          return;
        }

        response.writeHead(401, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({ error: 'Invalid or expired pairing code' }));
        return;
      }

      if (request.method === 'POST' && request.url === '/pairing/regenerate') {
        const validCode = isPairingCodeValid() ? activePairingCode : rotatePairingCode();
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({
          pairingCode: validCode,
          expiresAt: activePairingCodeExpiresAt,
        }));
        return;
      }

      const quickStartMatch = matchQuickStart(request);
      if (quickStartMatch) {
        const { sessionId } = quickStartMatch;
        if (request.method === 'POST') {
          runtime.enableQuickStart(sessionId);
          response.writeHead(200, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify({ quickStart: true }));
          return;
        }
        if (request.method === 'DELETE') {
          runtime.disableQuickStart(sessionId);
          response.writeHead(200, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify({ quickStart: false }));
          return;
        }
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({ quickStart: runtime.isQuickStart(sessionId) }));
        return;
      }

      const outboxPollMatch = matchOutboxPoll(request);
      if (outboxPollMatch) {
        const { sessionId } = outboxPollMatch;
        const afterCursor = new URL(request.url ?? '/', 'http://localhost').searchParams.get('after') ?? undefined;
        const pollResult = runtime.outboxStore.poll(sessionId, afterCursor);
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify(outboxPollResponseSchema.parse(pollResult)));
        return;
      }

      const outboxAckMatch = matchOutboxAck(request);
      if (outboxAckMatch) {
        const { sessionId, eventId } = outboxAckMatch;
        const acked = runtime.outboxStore.ack(eventId, sessionId);
        response.writeHead(acked ? 200 : 404, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({ acked }));
        return;
      }

      const notificationPrefMatch = matchNotificationPreference(request);
      if (notificationPrefMatch) {
        const { sessionId } = notificationPrefMatch;
        if (request.method === 'GET') {
          const prefs = runtime.notificationPreferenceStore.getPreferences(sessionId);
          response.writeHead(200, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify(prefs));
          return;
        }
        if (request.method === 'POST') {
          const body = await readRequestBody(request);
          let parsedBody: unknown;
          try {
            parsedBody = JSON.parse(body);
          } catch {
            response.writeHead(400, { 'Content-Type': 'application/json' });
            response.end(JSON.stringify({ error: 'Invalid JSON' }));
            return;
          }
          const preference = notificationPreferenceSchema.safeParse({ ...(parsedBody as Record<string, unknown>), sessionId });
          if (!preference.success) {
            response.writeHead(400, { 'Content-Type': 'application/json' });
            response.end(JSON.stringify({ error: 'Invalid preference payload' }));
            return;
          }
          const saved = runtime.notificationPreferenceStore.setPreferences(preference.data);
          response.writeHead(200, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify(saved));
          return;
        }
      }

      const reminderListMatch = matchReminderList(request);
      if (reminderListMatch) {
        const { sessionId } = reminderListMatch;
        if (request.method === 'GET') {
          const reminders = runtime.reminderStore.list(sessionId);
          response.writeHead(200, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify({ reminders }));
          return;
        }
        if (request.method === 'POST') {
          const body = await readRequestBody(request);
          let parsedBody: unknown;
          try {
            parsedBody = JSON.parse(body);
          } catch {
            response.writeHead(400, { 'Content-Type': 'application/json' });
            response.end(JSON.stringify({ error: 'Invalid JSON' }));
            return;
          }
          const createRequest = reminderCreateRequestSchema.safeParse({ ...(parsedBody as Record<string, unknown>), sessionId });
          if (!createRequest.success) {
            response.writeHead(400, { 'Content-Type': 'application/json' });
            response.end(JSON.stringify({ error: 'Invalid reminder payload' }));
            return;
          }
          const reminder = runtime.reminderStore.schedule(
            createRequest.data.sessionId,
            createRequest.data.summary,
            createRequest.data.dueAtMs,
            createRequest.data.recurring,
          );
          response.writeHead(201, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify(reminder));
          return;
        }
      }

      const reminderDeleteMatch = matchReminderDelete(request);
      if (reminderDeleteMatch) {
        const { reminderId } = reminderDeleteMatch;
        const cancelled = runtime.reminderStore.cancel(reminderId);
        response.writeHead(cancelled ? 200 : 404, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({ cancelled }));
        return;
      }

      const nudgePolicyMatch = matchNudgePolicy(request);
      if (nudgePolicyMatch) {
        const { sessionId } = nudgePolicyMatch;
        if (request.method === 'GET') {
          const policy = runtime.nudgePolicyStore.getPolicy(sessionId);
          response.writeHead(200, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify(policy));
          return;
        }
        if (request.method === 'POST') {
          const body = await readRequestBody(request);
          let parsedBody: unknown;
          try {
            parsedBody = JSON.parse(body);
          } catch {
            response.writeHead(400, { 'Content-Type': 'application/json' });
            response.end(JSON.stringify({ error: 'Invalid JSON' }));
            return;
          }
          const policy = nudgePolicySchema.safeParse({ ...(parsedBody as Record<string, unknown>), sessionId });
          if (!policy.success) {
            response.writeHead(400, { 'Content-Type': 'application/json' });
            response.end(JSON.stringify({ error: 'Invalid nudge policy payload' }));
            return;
          }
          const saved = runtime.nudgePolicyStore.setPolicy(policy.data);
          response.writeHead(200, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify(saved));
          return;
        }
      }

      const prefetchMatch = matchPrefetch(request);
      if (prefetchMatch) {
        const { sessionId, workspaceId } = prefetchMatch;
        const body = await readRequestBody(request);
        let parsedBody: unknown;
        try {
          parsedBody = JSON.parse(body);
        } catch {
          response.writeHead(400, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify({ error: 'Invalid JSON' }));
          return;
        }
        const prefetchRequest = prefetchRequestSchema.safeParse(parsedBody);
        if (!prefetchRequest.success) {
          response.writeHead(400, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify({ error: 'Invalid prefetch payload' }));
          return;
        }
        runtime.prefetchWorkspaceData(sessionId, workspaceId, prefetchRequest.data.kinds);
        response.writeHead(202, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({ accepted: true }));
        return;
      }

      if (request.method === 'GET' && request.url === '/habits') {
        const phrases = runtime.voiceHabitStore.list();
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({ phrases }));
        return;
      }

      if (request.method === 'POST' && request.url === '/habits') {
        const body = await readRequestBody(request);
        let parsedBody: unknown;
        try {
          parsedBody = JSON.parse(body);
        } catch {
          response.writeHead(400, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify({ error: 'Invalid JSON' }));
          return;
        }
        const entry = learnedPhraseSchema.safeParse(parsedBody);
        if (!entry.success) {
          response.writeHead(400, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify({ error: 'Invalid habit payload' }));
          return;
        }
        runtime.voiceHabitStore.set(entry.data.phrase, entry.data.intent as IntentName, entry.data.confirmationCount);
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify(runtime.voiceHabitStore.get(entry.data.phrase)));
        return;
      }

      if (request.method === 'DELETE' && request.url?.startsWith('/habits/')) {
        const phrase = decodeURIComponent(request.url.slice(8));
        const deleted = runtime.voiceHabitStore.delete(phrase);
        response.writeHead(deleted ? 200 : 404, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({ deleted }));
        return;
      }

      if (request.method === 'POST' && request.url === '/events') {
        const body = await readRequestBody(request);
        let parsedBody: unknown;

        try {
          parsedBody = JSON.parse(body);
        } catch {
          response.writeHead(400, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify({ error: 'Invalid JSON' }));
          return;
        }

        const isAndroidRelay = (parsedBody as Record<string, unknown>)?.source === 'android_relay';
        const event = (isAndroidRelay ? androidRelayEventSchema : earbudEventSchema).safeParse(parsedBody);
        if (!event.success) {
          response.writeHead(400, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify({ error: 'Invalid event payload', detail: event.error.errors.map(e => `${e.path.join('.')}: ${e.message}`).join('; ') }));
          return;
        }

        if (isAndroidRelay) {
          const protocolVersion = (event.data as Record<string, unknown>).protocolVersion as string | undefined;
          if (protocolVersion && !SUPPORTED_PROTOCOL_VERSIONS.includes(protocolVersion as typeof SUPPORTED_PROTOCOL_VERSIONS[number])) {
            response.writeHead(400, { 'Content-Type': 'application/json' });
            response.end(JSON.stringify({ error: `Unsupported protocol version: ${protocolVersion}. Supported: ${SUPPORTED_PROTOCOL_VERSIONS.join(', ')}` }));
            return;
          }
        }

        if (!rateLimiter.isAllowed(event.data.sessionId)) {
          response.writeHead(429, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify({ error: 'Rate limit exceeded' }));
          return;
        }

        const idempotencyKey = event.data.idempotencyKey;
        if (idempotencyKey) {
          const compositeKey = `${event.data.sessionId}:${idempotencyKey}`;
          try {
            const result = await idempotencyStore.executeOnce(compositeKey, () => runtime.handleEvent(event.data));
            response.writeHead(200, { 'Content-Type': 'application/json' });
            response.end(JSON.stringify(result.response));
            return;
          } catch (error) {
            const classified = classifyError(error);
            response.writeHead(classified.retryable ? 503 : 500, { 'Content-Type': 'application/json' });
            response.end(JSON.stringify({ error: classified.userMessage, category: classified.category }));
            return;
          }
        }

        try {
          const result = await runtime.handleEvent(event.data);
          response.writeHead(200, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify(result));
          return;
        } catch (error) {
          const classified = classifyError(error);
          response.writeHead(classified.retryable ? 503 : 500, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify({ error: classified.userMessage, category: classified.category }));
          return;
        }
      }

      if (request.method === 'POST' && request.url === '/events/stream') {
        const body = await readRequestBody(request);
        let parsedBody: unknown;

        try {
          parsedBody = JSON.parse(body);
        } catch {
          response.writeHead(400, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify({ error: 'Invalid JSON' }));
          return;
        }

        const isAndroidRelay = (parsedBody as Record<string, unknown>)?.source === 'android_relay';
        const event = (isAndroidRelay ? androidRelayEventSchema : earbudEventSchema).safeParse(parsedBody);
        if (!event.success) {
          response.writeHead(400, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify({ error: 'Invalid event payload', detail: event.error.errors.map(e => `${e.path.join('.')}: ${e.message}`).join('; ') }));
          return;
        }

        if (isAndroidRelay) {
          const protocolVersion = (event.data as Record<string, unknown>).protocolVersion as string | undefined;
          if (protocolVersion && !SUPPORTED_PROTOCOL_VERSIONS.includes(protocolVersion as typeof SUPPORTED_PROTOCOL_VERSIONS[number])) {
            response.writeHead(400, { 'Content-Type': 'application/json' });
            response.end(JSON.stringify({ error: `Unsupported protocol version: ${protocolVersion}. Supported: ${SUPPORTED_PROTOCOL_VERSIONS.join(', ')}` }));
            return;
          }
        }

        if (!rateLimiter.isAllowed(event.data.sessionId)) {
          response.writeHead(429, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify({ error: 'Rate limit exceeded' }));
          return;
        }

        response.writeHead(200, { 'Content-Type': 'application/x-ndjson' });

        const writeFrame = (frame: unknown) => {
          response.write(JSON.stringify(frame) + '\n');
        };

        writeFrame({ type: 'started' });

        // P1-1: Pre-routing streaming copy must be intent-neutral to avoid implying
        // an action has started before approval/policy is known.
        const safeEarlySpeak = 'Checking.';
        writeFrame({ type: 'speak_delta', delta: safeEarlySpeak });
        writeFrame({ type: 'display_delta', delta: safeEarlySpeak });

        const streamResult = (result: JarvisResponse) => {
          // Always emit a terminal final_response so clients can reconcile state,
          // actionId, nextState, autonomy, and display regardless of approval flow.
          if (result.requiresApproval && result.approvalRequest) {
            writeFrame({ type: 'approval_request', approvalRequest: result.approvalRequest });
          }
          writeFrame({ type: 'final_response', response: result });
        };

        const streamError = (error: unknown) => {
          const classified = classifyError(error);
          writeFrame({ type: 'error', error: classified.userMessage, category: classified.category });
        };

        const idempotencyKey = event.data.idempotencyKey;
        if (idempotencyKey) {
          const compositeKey = `${event.data.sessionId}:${idempotencyKey}`;
          try {
            const result = await idempotencyStore.executeOnce(compositeKey, () => runtime.handleEvent(event.data));
            streamResult(result.response);
          } catch (error) {
            streamError(error);
          }
        } else {
          try {
            const result = await runtime.handleEvent(event.data);
            streamResult(result);
          } catch (error) {
            streamError(error);
          }
        }

        writeFrame({ type: 'done' });
        response.end();
        return;
      }

      response.writeHead(404, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ error: 'Not found' }));
    } catch (error) {
      if (error instanceof RequestBodyTooLargeError) {
        response.writeHead(413, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({ error: error.message }));
        return;
      }

      const classified = classifyError(error);
      response.writeHead(500, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ error: classified.userMessage, category: classified.category }));
    }
  });
  server.setTimeout(resolveServerTimeoutMs(options));

  return {
    server,
    runtime,
  };
}

function resolveServerTimeoutMs(options: Pick<BridgeServerOptions, 'openclaw'>): number {
  const openClawTimeoutMs = options.openclaw?.timeoutMs;

  if (typeof openClawTimeoutMs !== 'number' || !Number.isFinite(openClawTimeoutMs) || openClawTimeoutMs <= 0) {
    return DEFAULT_SERVER_TIMEOUT_MS;
  }

  return Math.max(DEFAULT_SERVER_TIMEOUT_MS, Math.floor(openClawTimeoutMs) + SERVER_TIMEOUT_BUFFER_MS);
}

function buildHealthPayload(runtime: BridgeRuntime, options: BridgeServerOptions): {
  ok: true;
  bridgeVersion: string;
  protocolVersion: number;
  minAppVersion: string;
  features: string[];
  brainMode: 'local' | 'openclaw';
  openclawTransport: 'http' | 'local-cli' | 'gateway-client' | null;
  openclawRewritePolicy: 'always' | 'adaptive' | null;
  openclawRewriteHealth: ReturnType<BridgeRuntime['getOpenClawHealthSnapshot']>;
  openclawReady: boolean;
  degraded: boolean;
  queueDepth: ReturnType<BridgeRuntime['getHealthStatus']>['queueDepth'];
  lastErrorCategory: string | null;
  workspaceRegistryLoaded: boolean;
  cache_hit_miss_telemetry: { hits: number; misses: number };
  workspaceSnapshotCacheEnabled: boolean;
} {
  const brainMode = options.brainMode ?? 'local';
  const openclawTransport = options.openclaw
    ? options.openclaw.transport ?? 'http'
    : null;
  const openclawRewritePolicy = options.openclaw
    ? resolveOpenClawRewritePolicy(options.openclaw)
    : null;
  const openclawRewriteHealth = runtime.getOpenClawHealthSnapshot();
  const healthStatus = runtime.getHealthStatus();

  return {
    ok: true,
    bridgeVersion: '1.0.0',
    protocolVersion: 1,
    minAppVersion: '1.0.0',
    features: options.features ?? computeDefaultFeatures(options),
    brainMode,
    openclawTransport,
    openclawRewritePolicy,
    openclawRewriteHealth,
    openclawReady: brainMode === 'openclaw' && options.openclaw !== undefined,
    degraded: healthStatus.degraded,
    queueDepth: healthStatus.queueDepth,
    lastErrorCategory: healthStatus.lastErrorCategory,
    workspaceRegistryLoaded: options.registry !== undefined || options.configPath !== undefined,
    cache_hit_miss_telemetry: runtime.workspaceSnapshotCache.getTelemetry(),
    workspaceSnapshotCacheEnabled: healthStatus.workspaceSnapshotCacheEnabled,
  };
}

function computeDefaultFeatures(options: BridgeServerOptions): string[] {
  const features = [
    'pairing_code',
    'health_check',
    'event_routing',
    'approval_gates',
    'autonomy',
    'openclaw_rewrite',
    'outbox',
  ];
  // Only advertise streaming/prefetch if the server explicitly opts in or
  // if no override is provided (backward-compatible default).
  if (options.features?.includes('event_streaming') ?? true) {
    features.push('event_streaming');
  }
  if (options.features?.includes('prefetch') ?? true) {
    features.push('prefetch');
  }
  return features;
}

function buildPairingPayload(options: BridgeServerOptions, pairingCode: string | null): {
  bridgeBaseUrl: string;
  pairingCode?: string;
  workspace: string;
  pairingUri: string;
  pairingPageUrl: string;
} | null {
  const bridgeBaseUrl = options.pairingBaseUrl?.trim();
  if (!bridgeBaseUrl) {
    return null;
  }

  const workspace = options.registry?.defaultWorkspaceId ?? 'current_repo';
  const pairingPayload = {
    bridgeBaseUrl,
    ...(pairingCode ? { pairingCode } : {}),
    workspace,
  };

  return {
    ...pairingPayload,
    pairingUri: buildRelayPairingUri(pairingPayload),
    pairingPageUrl: buildRelayPairingPageUrl(bridgeBaseUrl),
  };
}

async function renderPairingPage(payload: NonNullable<ReturnType<typeof buildPairingPayload>>): Promise<string> {
  const escapedBridgeBaseUrl = escapeHtml(payload.bridgeBaseUrl);
  const escapedPairingUri = escapeHtml(payload.pairingUri);
  const escapedPairingPageUrl = escapeHtml(payload.pairingPageUrl);
  const escapedWorkspace = escapeHtml(payload.workspace);
  const [qrDataUrl, wordmarkDataUrl, appIconDataUrl] = await Promise.all([
    renderPairingQrDataUrl(payload.pairingPageUrl),
    loadBrandAssetDataUrl('devpods-wordmark-light-16x9.png'),
    loadBrandAssetDataUrl('devpods-app-icon-tile.png'),
  ]);
  const escapedQrDataUrl = escapeHtml(qrDataUrl);
  const escapedWordmarkDataUrl = wordmarkDataUrl ? escapeHtml(wordmarkDataUrl) : null;
  const escapedAppIconDataUrl = appIconDataUrl ? escapeHtml(appIconDataUrl) : null;
  const brandMarkup = escapedWordmarkDataUrl
    ? `<img class="brand-wordmark" alt="DevPods wordmark" src="${escapedWordmarkDataUrl}">`
    : '<p class="eyebrow">DevPods</p>';
  const faviconMarkup = escapedAppIconDataUrl
    ? `<link rel="icon" type="image/png" href="${escapedAppIconDataUrl}">`
    : '';

  return [
    '<!doctype html>',
    '<html lang="en">',
    '<head>',
    '<meta charset="utf-8">',
    '<meta name="viewport" content="width=device-width, initial-scale=1">',
    '<title>DevPods Relay Pairing</title>',
    faviconMarkup,
    '<style>',
    ':root { color-scheme: light; }',
    'body { margin: 0; min-height: 100vh; font-family: Inter, "Segoe UI", Arial, sans-serif; line-height: 1.5; color: #0d1b1e; background: radial-gradient(circle at 10% 10%, rgba(53, 214, 139, 0.20), transparent 28rem), radial-gradient(circle at 88% 4%, rgba(244, 184, 96, 0.20), transparent 24rem), #e9e2d6; }',
    '.page { max-width: 72rem; margin: 0 auto; padding: 2rem 1rem 3rem; }',
    '.hero { margin-bottom: 1.5rem; border-radius: 2rem; padding: 1.5rem; background: linear-gradient(145deg, rgba(255,255,255,0.78), rgba(255,255,255,0.42)), rgba(255,252,244,0.92); border: 1px solid rgba(255,255,255,0.78); box-shadow: inset 0 1px 0 rgba(255,255,255,0.9), 0 1.25rem 3rem rgba(13, 27, 30, 0.10); }',
    '.hero-copy { max-width: 42rem; }',
    '.brand-wordmark { display: block; width: min(100%, 18rem); margin-bottom: 1rem; }',
    '.eyebrow { margin: 0 0 0.75rem; color: #0f766e; font-size: 0.95rem; font-weight: 800; letter-spacing: 0.08em; text-transform: uppercase; }',
    'h1, h2 { margin: 0 0 0.35rem; }',
    'h1 { font-size: clamp(2rem, 4vw, 3rem); line-height: 1.04; letter-spacing: -0.04em; }',
    'p { margin: 0; }',
    '.lede { color: #4b5f5d; font-size: 1rem; max-width: 42rem; }',
    '.meta { margin-top: 1rem; display: inline-flex; flex-wrap: wrap; gap: 0.75rem; color: #173235; font-size: 0.95rem; font-weight: 600; }',
    '.meta-chip { padding: 0.55rem 0.8rem; border-radius: 999px; background: rgba(221, 248, 233, 0.90); color: #0f766e; }',
    '.layout { display: grid; gap: 1rem; grid-template-columns: repeat(auto-fit, minmax(18rem, 1fr)); align-items: stretch; }',
    '.panel { background: linear-gradient(145deg, rgba(255,255,255,0.78), rgba(255,255,255,0.38)), rgba(255,252,244,0.88); border: 1px solid rgba(255,255,255,0.80); border-radius: 1.5rem; padding: 1.25rem; box-shadow: inset 0 1px 0 rgba(255,255,255,0.88), 0 1rem 2.5rem rgba(13, 27, 30, 0.08); }',
    '.qr-card { display: flex; flex-direction: column; gap: 0.85rem; align-items: center; text-align: center; }',
    '.qr-card img { width: min(100%, 18rem); border-radius: 1.25rem; border: 1px solid rgba(216, 222, 213, 0.9); background: #fff; padding: 0.85rem; box-shadow: 0 0.75rem 1.5rem rgba(13, 27, 30, 0.08); }',
    '.button { display: inline-block; padding: 0.9rem 1.35rem; background: #0f766e; color: #fff; text-decoration: none; border-radius: 999px; font-weight: 700; box-shadow: 0 0.75rem 1.375rem rgba(13, 27, 30, 0.16); }',
    'code, textarea { width: 100%; box-sizing: border-box; font-family: Consolas, monospace; }',
    'textarea { min-height: 5rem; padding: 0.8rem; margin-top: 0.85rem; border-radius: 1rem; border: 1px solid #d8ded5; background: rgba(255,255,255,0.72); color: #0f172a; }',
    '.small { font-size: 0.95rem; color: #566867; }',
    '</style>',
    '</head>',
    '<body>',
    '<main class="page">',
    '<section class="hero">',
    '<div class="hero-copy">',
    brandMarkup,
    '<h1>DevPods Relay Pairing</h1>',
    '<p class="lede">Use this page as the desktop handoff surface for DevPods Relay. Scan the QR from the phone or open the page directly on Android and launch the pairing handoff.</p>',
    '<div class="meta">',
    `<span class="meta-chip">Bridge: ${escapedBridgeBaseUrl}</span>`,
    `<span class="meta-chip">Workspace: ${escapedWorkspace}</span>`,
    '</div>',
    '</div>',
    '</section>',
    '<div class="layout">',
    '<section class="panel qr-card">',
    '<h2>Scan from DevPods Relay</h2>',
    '<p class="small">In the Android relay Pairing card, tap Scan QR and point the phone at this code.</p>',
    `<img alt="Pairing QR code" data-pairing-qr-value="${escapedPairingPageUrl}" src="${escapedQrDataUrl}">`,
    `<textarea readonly>${escapedPairingPageUrl}</textarea>`,
    '</section>',
    '<section class="panel">',
    '<h2>Open on the phone</h2>',
    '<p>If this page is already open on the phone, use the app deep link directly.</p>',
    `<p><a class="button" href="${escapedPairingUri}">Open DevPods Relay</a></p>`,
    '<p class="small">If the button does not launch the app, copy the deep link below into the Relay pairing field.</p>',
    `<textarea readonly>${escapedPairingUri}</textarea>`,
    '</section>',
    '</div>',
    '</main>',
    '</body>',
    '</html>',
  ].join('');
}

async function loadBrandAssetDataUrl(filename: string): Promise<string | null> {
  const cached = brandAssetCache.get(filename);
  if (cached !== undefined) {
    return cached;
  }

  for (const assetDir of BRAND_ASSET_DIR_CANDIDATES) {
    try {
      const buffer = await readFile(path.resolve(assetDir, filename));
      const dataUrl = `data:image/png;base64,${buffer.toString('base64')}`;
      brandAssetCache.set(filename, dataUrl);
      return dataUrl;
    } catch {
      // Continue through the candidate directories until one resolves.
    }
  }

  brandAssetCache.set(filename, null);
  return null;
}

function respondWithPairingUnavailable(request: http.IncomingMessage, response: http.ServerResponse): void {
  const message = 'Pairing unavailable for the current bridge binding. Start the bridge with --pairing-base-url and a LAN-reachable URL.';
  if (prefersJson(request)) {
    response.writeHead(409, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ error: message }));
    return;
  }

  response.writeHead(409, { 'Content-Type': 'text/html; charset=utf-8' });
  response.end([
    '<!doctype html>',
    '<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">',
    '<title>Pairing unavailable</title></head><body>',
    '<h1>Pairing unavailable</h1>',
    `<p>${escapeHtml(message)}</p>`,
    '</body></html>',
  ].join(''));
}

function prefersJson(request: http.IncomingMessage): boolean {
  const acceptHeader = request.headers.accept;
  return typeof acceptHeader === 'string' && acceptHeader.toLowerCase().includes('application/json');
}

function requiresRelayAuthorization(request: http.IncomingMessage): boolean {
  if (request.method === 'GET' && request.url === '/pairing') {
    return false;
  }
  if (request.method === 'POST' && request.url === '/pairing/verify') {
    return false;
  }
  return true;
}

function escapeHtml(value: string): string {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function matchOutboxPoll(request: http.IncomingMessage): { sessionId: string } | null {
  if (request.method !== 'GET') return null;
  const url = request.url ?? '';
  const match = /^\/sessions\/([^/]+)\/outbox(?:\?|$)/.exec(url);
  if (!match) return null;
  return { sessionId: match[1] };
}

function matchOutboxAck(request: http.IncomingMessage): { sessionId: string; eventId: string } | null {
  if (request.method !== 'POST') return null;
  const url = request.url ?? '';
  const match = /^\/sessions\/([^/]+)\/outbox\/([^/]+)\/ack(?:\?|$)/.exec(url);
  if (!match) return null;
  return { sessionId: match[1], eventId: match[2] };
}

function matchNotificationPreference(request: http.IncomingMessage): { sessionId: string } | null {
  if (request.method !== 'GET' && request.method !== 'POST') return null;
  const url = request.url ?? '';
  const match = /^\/sessions\/([^/]+)\/preferences\/notifications(?:\?|$)/.exec(url);
  if (!match) return null;
  return { sessionId: match[1] };
}

function matchReminderList(request: http.IncomingMessage): { sessionId: string } | null {
  if (request.method !== 'GET' && request.method !== 'POST') return null;
  const url = request.url ?? '';
  const match = /^\/sessions\/([^/]+)\/reminders(?:\?|$)/.exec(url);
  if (!match) return null;
  return { sessionId: match[1] };
}

function matchReminderDelete(request: http.IncomingMessage): { reminderId: string } | null {
  if (request.method !== 'DELETE') return null;
  const url = request.url ?? '';
  const match = /^\/sessions\/[^/]+\/reminders\/([^/]+)(?:\?|$)/.exec(url);
  if (!match) return null;
  return { reminderId: match[1] };
}

function matchNudgePolicy(request: http.IncomingMessage): { sessionId: string } | null {
  if (request.method !== 'GET' && request.method !== 'POST') return null;
  const url = request.url ?? '';
  const match = /^\/sessions\/([^/]+)\/nudge-policy(?:\?|$)/.exec(url);
  if (!match) return null;
  return { sessionId: match[1] };
}

function matchQuickStart(request: http.IncomingMessage): { sessionId: string } | null {
  if (request.method !== 'GET' && request.method !== 'POST' && request.method !== 'DELETE') return null;
  const url = request.url ?? '';
  const match = /^\/sessions\/([^/]+)\/quick-start(?:\?|$)/.exec(url);
  if (!match) return null;
  return { sessionId: match[1] };
}

function matchPrefetch(request: http.IncomingMessage): { sessionId: string; workspaceId: string } | null {
  if (request.method !== 'POST') return null;
  const url = request.url ?? '';
  const match = /^\/sessions\/([^/]+)\/workspaces\/([^/]+)\/prefetch(?:\?|$)/.exec(url);
  if (!match) return null;
  return { sessionId: match[1], workspaceId: match[2] };
}

function readRequestBody(request: http.IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    let body = '';
    let bytesRead = 0;
    request.setEncoding('utf8');
    request.on('data', (chunk) => {
      bytesRead += Buffer.byteLength(chunk);
      if (bytesRead > MAX_REQUEST_BYTES) {
        request.destroy();
        reject(new RequestBodyTooLargeError());
        return;
      }
      body += chunk;
    });
    request.on('end', () => resolve(body));
    request.on('error', reject);
  });
}

function isAuthorizedRequest(request: http.IncomingMessage, relayToken: string | undefined): boolean {
  if (!relayToken) {
    return true;
  }

  const authorizationHeader = request.headers.authorization;
  if (!authorizationHeader) {
    return false;
  }

  const expected = Buffer.from(`Bearer ${relayToken}`);
  const received = Buffer.from(authorizationHeader);

  if (expected.length !== received.length) {
    return false;
  }

  return timingSafeEqual(expected, received);
}
