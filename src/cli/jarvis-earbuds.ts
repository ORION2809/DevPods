import { parseArgs } from 'node:util';
import { createInterface } from 'node:readline/promises';
import { createBridgeServer } from '../bridge/server';
import { createBridgeRuntime } from '../bridge/runtime';
import type { Notifier } from '../bridge/speaker';
import { resolveOpenClawRewritePolicy } from '../openclaw/client';
import { loadFixtureEvent, resolveBridgeBaseUrl, sendEvent } from '../simulator/client';
import {
  assertSafeBridgeExposure,
  preflightOpenClawOptions,
  resolveBridgeHost,
  resolveBridgeCommandRuntimeOptions,
  resolveRelayToken,
} from './runtime-options';

const sharedRuntimeOptionDefinitions = {
  brain: { type: 'string' },
  'workspaces-config': { type: 'string' },
  'openclaw-transport': { type: 'string' },
  'openclaw-base-url': { type: 'string' },
  'openclaw-token': { type: 'string' },
  'openclaw-model': { type: 'string' },
  'openclaw-agent-id': { type: 'string' },
  'openclaw-rewrite-policy': { type: 'string' },
  'openclaw-foreground-budget-ms': { type: 'string' },
  'openclaw-background-budget-ms': { type: 'string' },
  'openclaw-config-path': { type: 'string' },
  'openclaw-state-dir': { type: 'string' },
  'openclaw-workspace-dir': { type: 'string' },
  'openclaw-provider-plugin-ids': { type: 'string' },
  'openclaw-timeout-ms': { type: 'string' },
} as const;

const sharedNetworkOptionDefinitions = {
  host: { type: 'string' },
  'relay-token': { type: 'string' },
} as const;

const silentNotifier: Notifier = {
  async notify(): Promise<void> {
    return;
  },
};

async function main(): Promise<void> {
  const [command, ...rest] = process.argv.slice(2);

  switch (command) {
    case 'start': {
      const { values } = parseArgs({
        args: rest,
        options: {
          port: { type: 'string', default: '4545' },
          ...sharedNetworkOptionDefinitions,
          ...sharedRuntimeOptionDefinitions,
        },
        allowPositionals: true,
      });
      const port = parsePort(values.port, 'port');
      const host = resolveBridgeHost(values.host);
      const relayToken = resolveRelayToken(values['relay-token']);
      assertSafeBridgeExposure(host, relayToken);
      const runtimeOptions = resolveBridgeCommandRuntimeOptions({ values });
      if (runtimeOptions.openclaw) {
        preflightOpenClawOptions(runtimeOptions.openclaw);
      }
      const { server } = createBridgeServer({
        ...runtimeOptions,
        ...(relayToken ? { relayToken } : {}),
      });
      server.listen(port, host, () => {
        if (host !== '127.0.0.1' && host !== 'localhost' && host !== '::1') {
          process.stderr.write('Warning: bridge traffic is cleartext over HTTP. Use only on a trusted LAN.\n');
        }
        process.stdout.write(
          `DevPods Bridge listening on http://${host}:${port} (${formatRuntimeSummary(runtimeOptions)})\n`,
        );
      });
      return;
    }
    case 'local': {
      const [fixture, ...optionArgs] = rest;
      if (!fixture) {
        throw new Error('A fixture name or alias is required.');
      }
      const { values } = parseArgs({
        args: optionArgs,
        options: {
          utterance: { type: 'string' },
          'session-id': { type: 'string' },
          workspace: { type: 'string' },
          ...sharedRuntimeOptionDefinitions,
        },
      });
      const runtimeOptions = resolveBridgeCommandRuntimeOptions({ values });
      if (runtimeOptions.openclaw) {
        preflightOpenClawOptions(runtimeOptions.openclaw);
      }
      const runtime = createBridgeRuntime({
        ...runtimeOptions,
        notifier: silentNotifier,
      });
      const event = loadFixtureEvent(fixture, {
        utterance: values.utterance,
        sessionId: values['session-id'],
        workspace: values.workspace,
      });
      const result = await runtime.handleEvent(event);
      process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
      return;
    }
    case 'send': {
      const [fixture, ...optionArgs] = rest;
      if (!fixture) {
        throw new Error('A fixture name or alias is required.');
      }
      const { values } = parseArgs({
        args: optionArgs,
        options: {
          port: { type: 'string', default: '4545' },
          ...sharedNetworkOptionDefinitions,
          utterance: { type: 'string' },
          'session-id': { type: 'string' },
          workspace: { type: 'string' },
        },
      });
      const event = loadFixtureEvent(fixture, {
        utterance: values.utterance,
        sessionId: values['session-id'],
        workspace: values.workspace,
      });
      const result = await sendEvent(event, {
        host: resolveBridgeHost(values.host),
        port: Number(values.port),
        token: resolveRelayToken(values['relay-token']),
      });
      process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
      return;
    }
    case 'listen': {
      const { values, positionals } = parseArgs({
        args: rest,
        options: {
          utterance: { type: 'string' },
          'session-id': { type: 'string' },
          workspace: { type: 'string' },
          ...sharedRuntimeOptionDefinitions,
        },
        allowPositionals: true,
      });
      let utterance = (values.utterance ?? positionals.join(' ')).trim();
      if (!utterance) {
        const rl = createInterface({
          input: process.stdin,
          output: process.stdout,
        });
        utterance = (await rl.question('What should Jarvis check? ')).trim();
        rl.close();
      }

      if (!utterance) {
        throw new Error('An utterance is required.');
      }

      const runtimeOptions = resolveBridgeCommandRuntimeOptions({ values });
      if (runtimeOptions.openclaw) {
        preflightOpenClawOptions(runtimeOptions.openclaw);
      }
      const runtime = createBridgeRuntime({
        ...runtimeOptions,
        notifier: silentNotifier,
      });
      const event = loadFixtureEvent('triple_tap_right', {
        utterance,
        sessionId: values['session-id'],
        workspace: values.workspace,
      });
      const result = await runtime.handleEvent(event);
      process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
      return;
    }
    case 'say': {
      const { values, positionals } = parseArgs({
        args: rest,
        options: {
          port: { type: 'string', default: '4545' },
          ...sharedNetworkOptionDefinitions,
        },
        allowPositionals: true,
      });
      const utterance = positionals.join(' ').trim();
      if (!utterance) {
        throw new Error('An utterance is required.');
      }
      const event = loadFixtureEvent('triple_tap_right', {
        utterance,
      });
      const result = await sendEvent(event, {
        host: resolveBridgeHost(values.host),
        port: Number(values.port),
        token: resolveRelayToken(values['relay-token']),
      });
      process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
      return;
    }
    case 'health': {
      const { values } = parseArgs({
        args: rest,
        options: {
          port: { type: 'string', default: '4545' },
          ...sharedNetworkOptionDefinitions,
        },
        allowPositionals: true,
      });
      const port = parsePort(values.port, 'port');
      const response = await fetch(`${resolveBridgeBaseUrl({ host: resolveBridgeHost(values.host), port })}/health`, {
        headers: resolveRelayToken(values['relay-token'])
          ? { Authorization: `Bearer ${resolveRelayToken(values['relay-token'])}` }
          : undefined,
      });
      process.stdout.write(`${await response.text()}\n`);
      return;
    }
    default:
      process.stdout.write(
        'Usage: devpods <start|local|send|listen|say|health> [--brain local|openclaw] [--workspaces-config path]\n',
      );
  }
}

function parsePort(value: string | boolean | undefined, label: string): number {
  const port = Number(value);
  if (!Number.isInteger(port) || port <= 0 || port > 65535) {
    throw new Error(`Expected ${label} to be an integer between 1 and 65535.`);
  }

  return port;
}

function formatRuntimeSummary(options: ReturnType<typeof resolveBridgeCommandRuntimeOptions>): string {
  if (!options.openclaw) {
    return 'brain=local';
  }

  const foregroundBudgetMs = options.openclaw.foregroundBudgetMs ?? null;
  const backgroundBudgetMs = options.openclaw.backgroundBudgetMs ?? null;
  const budgetSummary = foregroundBudgetMs === null && backgroundBudgetMs === null
    ? 'budgets=off'
    : `budgets=${foregroundBudgetMs ?? 'off'}/${backgroundBudgetMs ?? 'off'}ms`;

  return `brain=openclaw, transport=${options.openclaw.transport ?? 'http'}, rewritePolicy=${resolveOpenClawRewritePolicy(options.openclaw)}, ${budgetSummary}`;
}

void main().catch((error) => {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 1;
});