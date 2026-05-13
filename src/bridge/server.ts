import { randomBytes, timingSafeEqual } from 'node:crypto';
import http from 'node:http';
import { resolveOpenClawRewritePolicy } from '../openclaw/client';
import { renderPairingQrDataUrl } from '../pairing/qr';
import { buildRelayPairingPageUrl, buildRelayPairingUri } from '../pairing/uri';
import { earbudEventSchema, pairingVerifyRequestSchema, type WorkspaceRegistry } from '../protocol/schemas';
import { createBridgeRuntime, type BridgeRuntime, type BridgeRuntimeOptions } from './runtime';
import { classifyError } from './error-handler';

const MAX_REQUEST_BYTES = 8 * 1024;
const DEFAULT_SERVER_TIMEOUT_MS = 30_000;
const SERVER_TIMEOUT_BUFFER_MS = 30_000;
const PAIRING_CODE_TTL_MS = 5 * 60 * 1000;
const RATE_LIMIT_WINDOW_MS = 60_000;
const RATE_LIMIT_MAX_REQUESTS = 100;

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

        const event = earbudEventSchema.safeParse(parsedBody);
        if (!event.success) {
          response.writeHead(400, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify({ error: 'Invalid event payload' }));
          return;
        }

        if (!rateLimiter.isAllowed(event.data.sessionId)) {
          response.writeHead(429, { 'Content-Type': 'application/json' });
          response.end(JSON.stringify({ error: 'Rate limit exceeded' }));
          return;
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
    features: ['pairing_code', 'health_check', 'event_routing', 'approval_gates', 'autonomy', 'openclaw_rewrite'],
    brainMode,
    openclawTransport,
    openclawRewritePolicy,
    openclawRewriteHealth,
    openclawReady: brainMode === 'openclaw' && options.openclaw !== undefined,
    degraded: healthStatus.degraded,
  };
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
  const qrDataUrl = escapeHtml(await renderPairingQrDataUrl(payload.pairingPageUrl));

  return [
    '<!doctype html>',
    '<html lang="en">',
    '<head>',
    '<meta charset="utf-8">',
    '<meta name="viewport" content="width=device-width, initial-scale=1">',
    '<title>DevPods Relay Pairing</title>',
    '<style>',
    ':root { color-scheme: light; }',
    'body { font-family: Segoe UI, sans-serif; max-width: 58rem; margin: 2rem auto; padding: 0 1rem 3rem; line-height: 1.5; color: #0f172a; background: #f8fafc; }',
    'h1, h2 { margin-bottom: 0.35rem; }',
    'p { margin-top: 0; }',
    '.meta { color: #334155; }',
    '.layout { display: grid; gap: 1rem; grid-template-columns: repeat(auto-fit, minmax(18rem, 1fr)); align-items: start; }',
    '.panel { background: #ffffff; border: 1px solid #dbe3ef; border-radius: 1rem; padding: 1rem; box-shadow: 0 0.5rem 1.5rem rgba(15, 23, 42, 0.06); }',
    '.button { display: inline-block; padding: 0.85rem 1.25rem; background: #0f766e; color: #fff; text-decoration: none; border-radius: 0.6rem; font-weight: 600; }',
    'code, textarea { width: 100%; box-sizing: border-box; font-family: Consolas, monospace; }',
    'textarea { min-height: 5rem; padding: 0.75rem; margin-top: 0.75rem; border-radius: 0.75rem; border: 1px solid #cbd5e1; background: #f8fafc; color: #0f172a; }',
    '.qr-card { display: flex; flex-direction: column; gap: 0.75rem; align-items: center; text-align: center; }',
    '.qr-card img { width: min(100%, 18rem); border-radius: 1rem; border: 1px solid #cbd5e1; background: #fff; padding: 0.75rem; }',
    '.small { font-size: 0.95rem; color: #475569; }',
    '</style>',
    '</head>',
    '<body>',
    '<h1>DevPods Relay Pairing</h1>',
    '<p>Use this page as the desktop handoff surface for DevPods Relay. You can either scan the QR from the phone or open this page directly on the phone and tap the pairing button.</p>',
    `<p class="meta">Bridge base URL: <strong>${escapedBridgeBaseUrl}</strong><br>Workspace: <strong>${escapedWorkspace}</strong></p>`,
    '<div class="layout">',
    '<section class="panel qr-card">',
    '<h2>Scan from DevPods Relay</h2>',
    '<p class="small">In the Android relay Pairing card, tap Scan QR and point the phone at this code.</p>',
    `<img alt="Pairing QR code" data-pairing-qr-value="${escapedPairingPageUrl}" src="${qrDataUrl}">`,
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
    '</body>',
    '</html>',
  ].join('');
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
