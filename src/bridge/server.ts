import { timingSafeEqual } from 'node:crypto';
import http from 'node:http';
import { resolveOpenClawRewritePolicy } from '../openclaw/client';
import { earbudEventSchema, type WorkspaceRegistry } from '../protocol/schemas';
import { createBridgeRuntime, type BridgeRuntime, type BridgeRuntimeOptions } from './runtime';

const MAX_REQUEST_BYTES = 8 * 1024;
const SERVER_TIMEOUT_MS = 30_000;

class RequestBodyTooLargeError extends Error {
  constructor() {
    super('Request body too large');
  }
}

export interface BridgeServerOptions extends BridgeRuntimeOptions {
  configPath?: string;
  registry?: WorkspaceRegistry;
  auditLogPath?: string;
  relayToken?: string;
}

export function createBridgeServer(options: BridgeServerOptions = {}): {
  server: http.Server;
  runtime: BridgeRuntime;
} {
  const runtime = createBridgeRuntime(options as BridgeRuntimeOptions);

  const server = http.createServer(async (request, response) => {
    try {
      if (!isAuthorizedRequest(request, options.relayToken)) {
        response.writeHead(401, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({ error: 'Unauthorized' }));
        return;
      }

      if (request.method === 'GET' && request.url === '/health') {
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify(buildHealthPayload(runtime, options)));
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

        const result = await runtime.handleEvent(event.data);
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify(result));
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

      const message = error instanceof Error ? error.message : 'Unknown error';
      response.writeHead(400, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ error: message }));
    }
  });
  server.setTimeout(SERVER_TIMEOUT_MS);

  return {
    server,
    runtime,
  };
}

function buildHealthPayload(runtime: BridgeRuntime, options: BridgeServerOptions): {
  ok: true;
  brainMode: 'local' | 'openclaw';
  openclawTransport: 'http' | 'local-cli' | 'gateway-client' | null;
  openclawRewritePolicy: 'always' | 'adaptive' | null;
  openclawRewriteHealth: ReturnType<BridgeRuntime['getOpenClawHealthSnapshot']>;
  openclawReady: boolean;
} {
  const brainMode = options.brainMode ?? 'local';
  const openclawTransport = options.openclaw
    ? options.openclaw.transport ?? 'http'
    : null;
  const openclawRewritePolicy = options.openclaw
    ? resolveOpenClawRewritePolicy(options.openclaw)
    : null;
  const openclawRewriteHealth = runtime.getOpenClawHealthSnapshot();

  return {
    ok: true,
    brainMode,
    openclawTransport,
    openclawRewritePolicy,
    openclawRewriteHealth,
    openclawReady: brainMode === 'openclaw' && options.openclaw !== undefined,
  };
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