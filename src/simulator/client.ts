import fs from 'node:fs';
import path from 'node:path';
import { earbudEventSchema, type EarbudEvent } from '../protocol/schemas';
import { approvalAliases } from '../protocol/types';

const allowedFixtures = new Set([
  'triple_tap_right',
  'left_long_press',
  'approve_right_double_tap',
  'reject_left_double_tap',
  'both_hold_cancel',
  'remove_one_bud_pause',
  'remove_both_buds_end_session',
  'put_both_in_resume',
]);

export interface SendEventOptions {
  baseUrl?: string;
  host?: string;
  port?: number;
  token?: string;
  timeoutMs?: number;
  utterance?: string;
  sessionId?: string;
  workspace?: string;
}

export async function sendEvent(
  event: EarbudEvent,
  options: number | SendEventOptions = {},
  timeoutMs = 120_000,
): Promise<unknown> {
  const resolvedOptions = typeof options === 'number'
    ? { port: options, timeoutMs }
    : options;
  const response = await fetch(`${resolveBridgeBaseUrl(resolvedOptions)}/events`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(resolvedOptions.token ? { Authorization: `Bearer ${resolvedOptions.token}` } : {}),
    },
    body: JSON.stringify(event),
    signal: AbortSignal.timeout(resolvedOptions.timeoutMs ?? timeoutMs),
  });

  if (!response.ok) {
    throw new Error(await response.text());
  }

  return response.json();
}

export function resolveBridgeBaseUrl(options: Pick<SendEventOptions, 'baseUrl' | 'host' | 'port'> = {}): string {
  if (options.baseUrl) {
    return options.baseUrl.replace(/\/+$/, '');
  }

  return `http://${options.host ?? '127.0.0.1'}:${options.port ?? 4545}`;
}

export function loadFixtureEvent(nameOrAlias: string, options: SendEventOptions = {}): EarbudEvent {
  const eventName = approvalAliases[nameOrAlias] ?? nameOrAlias;
  if (!allowedFixtures.has(eventName)) {
    throw new Error(`Fixture '${eventName}' is not allowed.`);
  }

  const eventsDir = path.resolve(process.cwd(), 'simulation/fake-earbud-events/events');
  const fixturePath = path.resolve(eventsDir, `${eventName}.json`);
  const relative = path.relative(eventsDir, fixturePath);
  if (relative.startsWith('..') || path.isAbsolute(relative)) {
    throw new Error('Fixture path is outside the allowed events directory.');
  }

  const raw = fs.readFileSync(fixturePath, 'utf8');
  const parsed = JSON.parse(raw) as EarbudEvent;

  return earbudEventSchema.parse({
    ...parsed,
    timestamp: Date.now(),
    utterance: options.utterance ?? parsed.utterance,
    sessionId: options.sessionId ?? parsed.sessionId,
    workspace: options.workspace ?? parsed.workspace,
  });
}