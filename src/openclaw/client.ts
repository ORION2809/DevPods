import { execFile } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import path from 'node:path';
import { Worker } from 'node:worker_threads';
import { z } from 'zod';
import type { EarbudEvent, JarvisResponse } from '../protocol/schemas';
import { optimizeSpeak } from '../jarvis/voice-optimize';
import {
  buildOpenClawEnvironment,
  ensureOpenClawRuntimeCompatibility,
  resolveOpenClawCommand,
} from './sandbox';

const completionEnvelopeSchema = z.object({
  choices: z
    .array(
      z.object({
        message: z.object({
          content: z.union([
            z.string(),
            z.array(
              z.object({
                type: z.string(),
                text: z.string().optional(),
              }),
            ),
          ]),
        }),
      }),
    )
    .min(1),
});

const rewriteSchema = z.object({
  speak: z.string().min(1),
  display: z.string().nullable().optional(),
  followUpHint: z.string().nullable().optional(),
});

const localCliEnvelopeSchema = z.object({
  outputs: z
    .array(
      z.object({
        text: z.string().nullable().optional(),
      }),
    )
    .min(1),
});

const gatewayAgentEnvelopeSchema = z.object({
  result: z.object({
    payloads: z
      .array(
        z.object({
          text: z.string().nullable().optional(),
        }),
      )
      .min(1),
  }),
});

export type OpenClawRewritePolicy = 'always' | 'adaptive';

type RewriteSource = RewriteContext['source'];

type OpenClawRewriteOutcome = 'rewritten' | 'skipped' | 'timed_out' | 'failed';

type OpenClawConnectionState = 'not_applicable' | 'idle' | 'connecting' | 'connected' | 'failed';

export interface OpenClawRewriteHealthSnapshot {
  readonly connectionState: OpenClawConnectionState;
  readonly lastConnectionError: string | null;
  readonly foregroundBudgetMs: number | null;
  readonly backgroundBudgetMs: number | null;
  readonly counters: {
    readonly rewritten: number;
    readonly skipped: number;
    readonly timedOut: number;
    readonly failed: number;
  };
  readonly lastOutcome: {
    readonly source: RewriteSource;
    readonly outcome: OpenClawRewriteOutcome;
    readonly durationMs: number;
    readonly at: string;
  } | null;
}

interface OpenClawHttpOptions {
  transport?: 'http';
  baseUrl: string;
  token?: string;
  model?: string;
  timeoutMs?: number;
  rewritePolicy?: OpenClawRewritePolicy;
  foregroundBudgetMs?: number;
  backgroundBudgetMs?: number;
}

interface OpenClawLocalCliOptions {
  transport: 'local-cli';
  model: string;
  configPath: string;
  stateDir: string;
  workspaceDir: string;
  timeoutMs?: number;
  providerPluginIds?: string[];
  rewritePolicy?: OpenClawRewritePolicy;
  foregroundBudgetMs?: number;
  backgroundBudgetMs?: number;
}

interface OpenClawGatewayClientOptions {
  transport: 'gateway-client';
  baseUrl: string;
  token?: string;
  model: string;
  agentId: string;
  timeoutMs?: number;
  rewritePolicy?: OpenClawRewritePolicy;
  foregroundBudgetMs?: number;
  backgroundBudgetMs?: number;
}

export type OpenClawGatewayOptions = OpenClawHttpOptions | OpenClawLocalCliOptions | OpenClawGatewayClientOptions;

interface RewriteContext {
  response: JarvisResponse;
  event?: EarbudEvent;
  source: 'foreground' | 'background';
}

type ResidentGatewayWorkerRequest =
  | {
      type: 'connect';
      payload: {
        openClawEntryPath: string;
        options: OpenClawGatewayClientOptions;
      };
    }
  | {
      type: 'rewrite';
      payload: {
        message: string;
        agentId: string;
        model: string;
        sessionId: string;
        timeoutMs: number;
        idempotencyKey: string;
      };
    }
  | {
      type: 'dispose';
      payload?: undefined;
    };

interface ResidentGatewayWorkerResponse {
  id: string;
  ok: boolean;
  result?: unknown;
  error?: string;
}

interface ResidentGatewayConnection {
  request<T>(message: ResidentGatewayWorkerRequest, timeoutMs: number): Promise<T>;
  stop(): Promise<void>;
}

const gatewayWorkerUrl = new URL(
  `data:text/javascript,${encodeURIComponent(String.raw`
    import path from 'node:path';
    import { pathToFileURL } from 'node:url';
    import { parentPort } from 'node:worker_threads';

    let client = null;

    if (!parentPort) {
      throw new Error('OpenClaw gateway worker started without a parent port.');
    }

    parentPort.on('message', async (message) => {
      const { id, type, payload } = message;

      try {
        if (type === 'connect') {
          if (client) {
            reply(id, { connected: true });
            return;
          }

          client = await createClient(payload.openClawEntryPath, payload.options);
          reply(id, { connected: true });
          return;
        }

        if (type === 'rewrite') {
          if (!client) {
            throw new Error('OpenClaw gateway worker is not connected.');
          }

          const result = await client.request(
            'agent',
            {
              message: payload.message,
              agentId: payload.agentId,
              model: payload.model,
              sessionId: payload.sessionId,
              modelRun: true,
              promptMode: 'none',
              deliver: false,
              timeout: Math.max(1, Math.ceil(payload.timeoutMs / 1000)),
              idempotencyKey: payload.idempotencyKey,
            },
            {
              expectFinal: true,
              timeoutMs: payload.timeoutMs,
            },
          );
          reply(id, result);
          return;
        }

        if (type === 'dispose') {
          await stopClient();
          reply(id, { disposed: true });
          return;
        }

        throw new Error(
          type === undefined
            ? 'OpenClaw gateway worker received a request without a type.'
            : 'OpenClaw gateway worker received an unknown request type: ' + String(type),
        );
      } catch (error) {
        replyError(id, error);
      }
    });

    async function createClient(openClawEntryPath, options) {
      const clientModuleUrl = pathToFileURL(path.join(path.dirname(openClawEntryPath), 'client-C_yF1Jx2.js')).href;
      const module = await import(clientModuleUrl);
      const GatewayClient = module.t;

      if (typeof GatewayClient !== 'function') {
        throw new Error('Installed OpenClaw package does not expose the internal GatewayClient runtime.');
      }

      const url = resolveGatewayWebSocketUrl(options.baseUrl);
      const timeoutMs = Math.min(options.timeoutMs ?? 120000, 15000);

      return new Promise((resolve, reject) => {
        let settled = false;
        let gatewayClient;
        const connectionTimer = setTimeout(() => {
          settle(new Error('OpenClaw gateway-client connection timed out after ' + timeoutMs + 'ms.'));
        }, timeoutMs);

        const clearConnectionTimer = () => clearTimeout(connectionTimer);

        const settle = (error) => {
          if (settled) {
            return;
          }

          settled = true;
          clearConnectionTimer();
          if (error) {
            if (gatewayClient) {
              gatewayClient.stop();
            }
            reject(error);
            return;
          }

          resolve(gatewayClient);
        };

        gatewayClient = new GatewayClient({
          url,
          token: options.token,
          requestTimeoutMs: options.timeoutMs ?? 120000,
          clientName: 'gateway-client',
          clientDisplayName: 'Jarvis Earbuds Rewrite Client',
          clientVersion: '0.1.0',
          platform: process.platform,
          mode: 'backend',
          role: 'operator',
          scopes: ['operator.admin'],
          deviceIdentity: null,
          minProtocol: 3,
          maxProtocol: 3,
          onHelloOk: () => settle(),
          onConnectError: (error) => settle(error),
          onClose: (code, reason) => {
            client = null;
            gatewayClient.stop();
            if (!settled) {
              settle(new Error('OpenClaw gateway-client closed during startup (' + code + '): ' + (reason || 'no close reason')));
            }
          },
        });
        gatewayClient.start();
      });
    }

    async function stopClient() {
      if (!client) {
        return;
      }

      const activeClient = client;
      client = null;

      try {
        await activeClient.stopAndWait({ timeoutMs: 1000 });
      } catch {
        activeClient.stop();
      }
    }

    function resolveGatewayWebSocketUrl(baseUrl) {
      const url = new URL(baseUrl);
      url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
      url.pathname = '/';
      url.search = '';
      url.hash = '';
      return url.toString();
    }

    function reply(id, result) {
      parentPort.postMessage({ id, ok: true, result });
    }

    function replyError(id, error) {
      parentPort.postMessage({
        id,
        ok: false,
        error: error instanceof Error ? error.message : String(error),
      });
    }
  `)}`,
);

const requireForOpenClaw = createRequire(path.join(process.cwd(), 'package.json'));

export class OpenClawGatewayClient {
  private residentGatewayConnectionPromise: Promise<ResidentGatewayConnection> | null = null;

  private connectionState: OpenClawConnectionState;

  private lastConnectionError: string | null = null;

  private rewriteCounters = {
    rewritten: 0,
    skipped: 0,
    timedOut: 0,
    failed: 0,
  };

  private lastRewriteOutcome: OpenClawRewriteHealthSnapshot['lastOutcome'] = null;

  constructor(private readonly options: OpenClawGatewayOptions) {
    this.connectionState = isGatewayClientOptions(options) ? 'idle' : 'not_applicable';
  }

  prime(): void {
    if (!isGatewayClientOptions(this.options)) {
      return;
    }

    void this.getResidentGatewayConnection(this.options, Math.min(this.options.timeoutMs ?? 120_000, 15_000)).catch(() => {
      return;
    });
  }

  getHealthSnapshot(): OpenClawRewriteHealthSnapshot {
    return {
      connectionState: this.connectionState,
      lastConnectionError: this.lastConnectionError,
      foregroundBudgetMs: normalizeBudgetMs(this.options.foregroundBudgetMs),
      backgroundBudgetMs: normalizeBudgetMs(this.options.backgroundBudgetMs),
      counters: {
        rewritten: this.rewriteCounters.rewritten,
        skipped: this.rewriteCounters.skipped,
        timedOut: this.rewriteCounters.timedOut,
        failed: this.rewriteCounters.failed,
      },
      lastOutcome: this.lastRewriteOutcome,
    };
  }

  async rewriteResponse(context: RewriteContext): Promise<JarvisResponse> {
    const startedAt = Date.now();
    if (!shouldRewrite(context.response, resolveOpenClawRewritePolicy(this.options))) {
      this.recordRewriteOutcome(context.source, 'skipped', Date.now() - startedAt);
      return context.response;
    }

    const budgetMs = resolveRewriteBudgetMs(this.options, context.source);

    try {
      const rewritten = await this.requestRewrite(context, budgetMs);
      this.recordRewriteOutcome(context.source, 'rewritten', Date.now() - startedAt);
      return {
        ...context.response,
        speak: optimizeSpeak(rewritten.speak),
        display: rewritten.display ?? context.response.display,
        followUpHint: rewritten.followUpHint ?? context.response.followUpHint,
      };
    } catch (error) {
      this.recordRewriteOutcome(
        context.source,
        isTimeoutLikeError(error) ? 'timed_out' : 'failed',
        Date.now() - startedAt,
      );
      return context.response;
    }
  }

  private recordRewriteOutcome(source: RewriteSource, outcome: OpenClawRewriteOutcome, durationMs: number): void {
    if (outcome === 'rewritten') {
      this.rewriteCounters.rewritten += 1;
    } else if (outcome === 'skipped') {
      this.rewriteCounters.skipped += 1;
    } else if (outcome === 'timed_out') {
      this.rewriteCounters.timedOut += 1;
    } else {
      this.rewriteCounters.failed += 1;
    }

    this.lastRewriteOutcome = {
      source,
      outcome,
      durationMs,
      at: new Date().toISOString(),
    };
  }

  private async requestRewrite(
    context: RewriteContext,
    budgetMs: number | undefined,
  ): Promise<z.infer<typeof rewriteSchema>> {
    const options = this.options;
    const timeoutMs = resolveRewriteTimeoutMs(options.timeoutMs, budgetMs, 120_000);

    if (isLocalCliOptions(options)) {
      return this.requestLocalCliRewrite(context, options, timeoutMs);
    }

    if (isGatewayClientOptions(options)) {
      return this.requestGatewayClientRewrite(context, options, timeoutMs, budgetMs);
    }

    return this.requestHttpRewrite(context, options, timeoutMs);
  }

  private async requestHttpRewrite(
    context: RewriteContext,
    options: OpenClawHttpOptions,
    timeoutMs: number,
  ): Promise<z.infer<typeof rewriteSchema>> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), timeoutMs);

    try {
      const response = await fetch(resolveChatCompletionsUrl(options.baseUrl), {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}),
        },
        body: JSON.stringify({
          model: options.model ?? 'openclaw/default',
          stream: false,
          messages: buildMessages(context),
        }),
        signal: controller.signal,
      });

      if (!response.ok) {
        throw new Error(`OpenClaw gateway returned HTTP ${response.status}`);
      }

      const payload = completionEnvelopeSchema.parse(await response.json());
      const content = readMessageContent(payload.choices[0]?.message.content);
      return rewriteSchema.parse(parseRewriteJson(content));
    } finally {
      clearTimeout(timeout);
    }
  }

  private async requestLocalCliRewrite(
    context: RewriteContext,
    options: OpenClawLocalCliOptions,
    timeoutMs: number,
  ): Promise<z.infer<typeof rewriteSchema>> {
    ensureOpenClawRuntimeCompatibility();

    const command = resolveOpenClawCommand();
    const result = await execFileAsync(command.command, [
      ...command.prefixArgs,
      'capability',
      'model',
      'run',
      '--local',
      '--json',
      '--model',
      options.model,
      '--prompt',
      buildLocalCliPrompt(context),
    ], {
      cwd: process.cwd(),
      env: buildOpenClawEnvironment({
        configPath: options.configPath,
        stateDir: options.stateDir,
        workspaceDir: options.workspaceDir,
        providerPluginIds: resolveProviderPluginIds(options),
      }),
      windowsHide: true,
      timeout: timeoutMs,
      maxBuffer: 1024 * 1024,
    });
    const payload = localCliEnvelopeSchema.parse(JSON.parse(result.stdout));
    const content = payload.outputs.map((output) => output.text ?? '').find((text) => text.trim().length > 0);

    if (!content) {
      throw new Error('OpenClaw local CLI did not return text output.');
    }

    return rewriteSchema.parse(parseRewriteJson(content));
  }

  private async requestGatewayClientRewrite(
    context: RewriteContext,
    options: OpenClawGatewayClientOptions,
    timeoutMs: number,
    budgetMs: number | undefined,
  ): Promise<z.infer<typeof rewriteSchema>> {
    const connection = await this.getResidentGatewayConnection(options, budgetMs);

    try {
      const payload = gatewayAgentEnvelopeSchema.parse(await connection.request({
        type: 'rewrite',
        payload: {
          message: buildLocalCliPrompt(context),
          agentId: options.agentId,
          model: options.model,
          sessionId: buildGatewayRewriteSessionId(),
          timeoutMs,
          idempotencyKey: randomUUID(),
        },
      }, timeoutMs));
      const content = payload.result.payloads.map((output) => output.text ?? '').find((text) => text.trim().length > 0);

      if (!content) {
        throw new Error('OpenClaw gateway-client transport did not return text output.');
      }

      return rewriteSchema.parse(parseRewriteJson(content));
    } catch (error) {
      if (!isTimeoutLikeError(error)) {
        await this.resetResidentGatewayConnection();
      }
      throw error;
    }
  }

  private async getResidentGatewayConnection(
    options: OpenClawGatewayClientOptions,
    budgetMs?: number,
  ): Promise<ResidentGatewayConnection> {
    if (!this.residentGatewayConnectionPromise) {
      this.connectionState = 'connecting';
      this.lastConnectionError = null;
      let connectionPromise!: Promise<ResidentGatewayConnection>;
      connectionPromise = this.createResidentGatewayConnection(
        options,
        resolveConnectionTimeoutMs(options.timeoutMs, budgetMs),
        () => this.residentGatewayConnectionPromise === connectionPromise,
      );
      this.residentGatewayConnectionPromise = connectionPromise;
    }

    return this.residentGatewayConnectionPromise;
  }

  private async createResidentGatewayConnection(
    options: OpenClawGatewayClientOptions,
    connectTimeoutMs: number,
    isActiveConnection: () => boolean,
  ): Promise<ResidentGatewayConnection> {
    const worker = new Worker(gatewayWorkerUrl);
    const openClawEntryPath = requireForOpenClaw.resolve('openclaw');
    const pendingRequests = new Map<string, {
      resolve: (value: unknown) => void;
      reject: (error: Error) => void;
      timeout: NodeJS.Timeout;
    }>();
    let stopped = false;

    const clearPendingRequests = (error?: Error) => {
      for (const pendingRequest of pendingRequests.values()) {
        clearTimeout(pendingRequest.timeout);
        if (error) {
          pendingRequest.reject(error);
        }
      }
      pendingRequests.clear();
    };

    const stopWorker = async (
      nextState: Extract<OpenClawConnectionState, 'idle' | 'failed'> = 'idle',
      errorMessage: string | null = null,
    ): Promise<void> => {
      if (stopped) {
        return;
      }

      stopped = true;
      if (isActiveConnection()) {
        this.residentGatewayConnectionPromise = null;
        this.connectionState = nextState;
        this.lastConnectionError = errorMessage;
      }
      clearPendingRequests();
      await worker.terminate();
    };

    const rejectPendingRequests = (error: Error) => {
      if (stopped) {
        return;
      }

      stopped = true;
      if (isActiveConnection()) {
        this.residentGatewayConnectionPromise = null;
        this.connectionState = 'failed';
        this.lastConnectionError = error.message;
      }
      clearPendingRequests(error);
      void worker.terminate();
    };

    worker.on('message', (response: ResidentGatewayWorkerResponse) => {
      const pendingRequest = pendingRequests.get(response.id);
      if (!pendingRequest) {
        return;
      }

      pendingRequests.delete(response.id);
      clearTimeout(pendingRequest.timeout);
      if (response.ok) {
        pendingRequest.resolve(response.result);
        return;
      }

      pendingRequest.reject(new Error(response.error ?? 'OpenClaw gateway worker request failed.'));
    });

    worker.on('error', (error) => {
      rejectPendingRequests(error);
    });

    worker.on('exit', (code) => {
      if (stopped) {
        return;
      }

      rejectPendingRequests(new Error(`OpenClaw gateway worker exited with code ${code}.`));
    });

    const request = <T>(message: ResidentGatewayWorkerRequest, timeoutMs: number): Promise<T> => new Promise((resolve, reject) => {
      if (stopped) {
        reject(new Error('OpenClaw gateway worker is not available.'));
        return;
      }

      const id = randomUUID();
      const timeout = setTimeout(() => {
        pendingRequests.delete(id);
        reject(new Error(`OpenClaw gateway worker ${message.type} timed out after ${timeoutMs}ms.`));
      }, timeoutMs);

      pendingRequests.set(id, {
        resolve: resolve as (value: unknown) => void,
        reject,
        timeout,
      });

      try {
        worker.postMessage({
          id,
          type: message.type,
          payload: message.payload,
        });
      } catch (error) {
        pendingRequests.delete(id);
        clearTimeout(timeout);
        reject(error instanceof Error ? error : new Error(String(error)));
      }
    });

    try {
      await request({
        type: 'connect',
        payload: {
          openClawEntryPath,
          options,
        },
      }, connectTimeoutMs);
      this.connectionState = 'connected';
      this.lastConnectionError = null;
    } catch (error) {
      const connectionError = error instanceof Error ? error : new Error(String(error));
      await stopWorker('failed', connectionError.message);
      throw error;
    }

    return {
      request,
      stop: async () => {
        if (stopped) {
          return;
        }

        try {
          await request({ type: 'dispose' }, 2_000);
        } catch {
          return;
        } finally {
          await stopWorker();
        }
      },
    };
  }

  private async resetResidentGatewayConnection(): Promise<void> {
    const pendingConnection = this.residentGatewayConnectionPromise;

    if (!pendingConnection) {
      return;
    }

    try {
      const connection = await pendingConnection;
      await connection.stop();
    } catch {
      return;
    }
  }
}

function shouldRewrite(response: JarvisResponse, rewritePolicy: OpenClawRewritePolicy): boolean {
  if (response.requiresApproval) {
    return false;
  }

  if (response.status !== 'completed' && response.status !== 'running') {
    return false;
  }

  if (rewritePolicy === 'always') {
    return true;
  }

  return requiresAdaptiveRewrite(response);
}

export function resolveOpenClawRewritePolicy(
  options: Pick<OpenClawGatewayOptions, 'rewritePolicy'>,
): OpenClawRewritePolicy {
  // Manual constructions that bypass CLI/runtime resolution keep forced rewriting unless they opt into adaptive mode explicitly.
  return options.rewritePolicy ?? 'always';
}

function requiresAdaptiveRewrite(response: JarvisResponse): boolean {
  const wordCount = countWords(response.speak);

  if (wordCount > 24 || response.speak.includes('\n')) {
    return true;
  }

  if (wordCount > 16) {
    return true;
  }

  return Boolean(response.followUpHint) && wordCount > 12;
}

function countWords(text: string): number {
  return text.trim().split(/\s+/).filter(Boolean).length;
}

function resolveChatCompletionsUrl(baseUrl: string): string {
  return `${baseUrl.replace(/\/+$/, '')}/v1/chat/completions`;
}

function buildLocalCliPrompt(context: RewriteContext): string {
  const [systemMessage, userMessage] = buildMessages(context);
  return `${systemMessage?.content ?? ''} Context JSON: ${userMessage?.content ?? '{}'}`;
}

function buildMessages(context: RewriteContext): Array<{ role: 'system' | 'user'; content: string }> {
  return [
    {
      role: 'system',
      content:
        'You are rewriting a local developer assistant reply for voice-first earbuds. '
        + 'Return strict JSON only with keys speak, display, and followUpHint. '
        + 'Keep speak to one short sentence with at most 24 words. '
        + 'Do not invent approvals, commands, or status changes. Preserve the original meaning.',
    },
    {
      role: 'user',
      content: JSON.stringify({
        source: context.source,
        event: context.event
          ? {
              source: context.event.source,
              sessionId: context.event.sessionId,
              workspace: context.event.workspace,
              event: context.event.event,
              utterance: context.event.utterance ?? null,
              profile: context.event.profile ?? null,
            }
          : null,
        localResponse: {
          speak: context.response.speak,
          display: context.response.display,
          followUpHint: context.response.followUpHint,
          status: context.response.status,
          nextState: context.response.nextState,
        },
      }),
    },
  ];
}

function readMessageContent(content: z.infer<typeof completionEnvelopeSchema>['choices'][number]['message']['content']): string {
  if (typeof content === 'string') {
    return content;
  }

  return content
    .map((part) => part.text ?? '')
    .join(' ')
    .trim();
}

function parseRewriteJson(content: string): unknown {
  try {
    return JSON.parse(content);
  } catch {
    const start = content.indexOf('{');
    const end = content.lastIndexOf('}');
    if (start >= 0 && end > start) {
      return JSON.parse(content.slice(start, end + 1));
    }
    throw new Error('OpenClaw response did not contain JSON.');
  }
}

function isLocalCliOptions(options: OpenClawGatewayOptions): options is OpenClawLocalCliOptions {
  return options.transport === 'local-cli';
}

function isGatewayClientOptions(options: OpenClawGatewayOptions): options is OpenClawGatewayClientOptions {
  return options.transport === 'gateway-client';
}

function resolveProviderPluginIds(options: OpenClawLocalCliOptions): string[] {
  const explicitPluginIds = options.providerPluginIds?.map((pluginId) => pluginId.trim()).filter(Boolean) ?? [];
  if (explicitPluginIds.length > 0) {
    return explicitPluginIds;
  }

  const providerId = options.model.split('/', 1)[0]?.trim();
  return providerId ? [providerId] : [];
}

function execFileAsync(
  command: string,
  args: string[],
  options: {
    cwd: string;
    env: NodeJS.ProcessEnv;
    windowsHide: boolean;
    timeout: number;
    maxBuffer: number;
  },
): Promise<{ stdout: string; stderr: string }> {
  return new Promise((resolve, reject) => {
    execFile(command, args, options, (error, stdout, stderr) => {
      if (error) {
        reject(Object.assign(error, { stdout, stderr }));
        return;
      }

      resolve({ stdout, stderr });
    });
  });
}

function buildGatewayRewriteSessionId(): string {
  return `jarvis-rewrite-${randomUUID()}`;
}

function resolveRewriteBudgetMs(
  options: Pick<OpenClawGatewayOptions, 'foregroundBudgetMs' | 'backgroundBudgetMs'>,
  source: RewriteSource,
): number | undefined {
  return normalizeBudgetMs(source === 'foreground' ? options.foregroundBudgetMs : options.backgroundBudgetMs) ?? undefined;
}

function normalizeBudgetMs(value: number | undefined): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) {
    return null;
  }

  return Math.floor(value);
}

function resolveRewriteTimeoutMs(
  configuredTimeoutMs: number | undefined,
  budgetMs: number | undefined,
  defaultTimeoutMs: number,
): number {
  const baseTimeoutMs = typeof configuredTimeoutMs === 'number' && Number.isFinite(configuredTimeoutMs) && configuredTimeoutMs > 0
    ? Math.floor(configuredTimeoutMs)
    : defaultTimeoutMs;
  const normalizedBudgetMs = normalizeBudgetMs(budgetMs) ?? null;

  return normalizedBudgetMs === null ? baseTimeoutMs : Math.min(baseTimeoutMs, normalizedBudgetMs);
}

function resolveConnectionTimeoutMs(configuredTimeoutMs: number | undefined, budgetMs: number | undefined): number {
  return Math.min(resolveRewriteTimeoutMs(configuredTimeoutMs, budgetMs, 120_000), 15_000);
}

function isTimeoutLikeError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error);
  return /timed out|timeout|aborted/i.test(message);
}