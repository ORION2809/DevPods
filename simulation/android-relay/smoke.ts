import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createBridgeServer } from '../../src/bridge/server';
import type { Notifier } from '../../src/bridge/speaker';
import type { EarbudEvent, JarvisResponse, WorkspaceRegistry } from '../../src/protocol/schemas';

class MemoryNotifier implements Notifier {
  readonly messages: JarvisResponse[] = [];

  async notify(response: JarvisResponse): Promise<void> {
    this.messages.push(response);
  }
}

function logStep(message: string): void {
  process.stdout.write(`[android-relay-smoke] ${message}\n`);
}

async function main(): Promise<void> {
  const smokeStartedAt = Date.now();
  const repoDir = fs.mkdtempSync(path.join(os.tmpdir(), 'jarvis-android-relay-smoke-'));
  const auditLogPath = path.join(repoDir, 'audit.log');
  const notifier = new MemoryNotifier();
  const relayToken = 'relay-smoke-token';
  const host = '127.0.0.1';
  const sessionId = 'android_relay_smoke';

  fs.writeFileSync(path.join(repoDir, 'app.ts'), 'console.log("hello");\n', 'utf8');
  fs.writeFileSync(path.join(repoDir, 'fake-task.js'), 'setTimeout(function () { process.exit(0); }, 25);\n', 'utf8');
  fs.mkdirSync(path.join(repoDir, '.git'));

  const registry: WorkspaceRegistry = {
    defaultWorkspaceId: 'current_repo',
    workspaces: [
      {
        id: 'current_repo',
        label: 'android_relay_smoke',
        rootPath: repoDir,
        allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure', 'run_tests', 'open_file'],
        approvalRequiredIntents: ['run_tests', 'open_file'],
        hardApprovalIntents: [],
        commands: {
          run_tests: {
            description: 'Run workspace tests',
            command: 'node',
            args: [path.join(repoDir, 'fake-task.js')],
            timeoutMs: 2000,
          },
        },
      },
    ],
  };

  const { server } = createBridgeServer({
    registry,
    auditLogPath,
    notifier,
    relayToken,
  });

  await new Promise<void>((resolve) => server.listen(0, host, resolve));
  const address = server.address();
  if (!address || typeof address === 'string') {
    throw new Error('Bridge address was not available.');
  }

  const baseUrl = `http://${host}:${address.port}`;
  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${relayToken}`,
  };
  const previousDisableOpen = process.env.JARVIS_DISABLE_OPEN;
  process.env.JARVIS_DISABLE_OPEN = '1';

  logStep(`Bridge started at ${baseUrl}.`);

  try {
    const health = await measure('health', async () => {
      const response = await fetch(`${baseUrl}/health`, { headers: { Authorization: `Bearer ${relayToken}` } });
      if (!response.ok) {
        throw new Error(await response.text());
      }

      return response.json() as Promise<{ ok: boolean }>;
    });

    const quickStatus = await sendRelayEvent(baseUrl, headers, {
      source: 'android_relay',
      sessionId,
      workspace: 'current_repo',
      device: 'both_buds',
      event: 'android_status_shortcut',
      timestamp: Date.now(),
      profile: 'default',
    });

    const voiceCommand = await sendRelayEvent(baseUrl, headers, {
      source: 'android_relay',
      sessionId,
      workspace: 'current_repo',
      device: 'both_buds',
      event: 'android_push_to_talk',
      timestamp: Date.now(),
      utterance: 'what branch am I on',
      profile: 'default',
    });

    const approvalPrompt = await sendRelayEvent(baseUrl, headers, {
      source: 'android_relay',
      sessionId,
      workspace: 'current_repo',
      device: 'both_buds',
      event: 'android_push_to_talk',
      timestamp: Date.now(),
      utterance: 'open file app.ts',
      profile: 'default',
    });

    const cancel = await sendRelayEvent(baseUrl, headers, {
      source: 'android_relay',
      sessionId,
      workspace: 'current_repo',
      device: 'both_buds',
      event: 'android_cancel',
      timestamp: Date.now(),
      pendingActionId: approvalPrompt.payload.actionId ?? undefined,
      profile: 'default',
    });

    const secondPrompt = await sendRelayEvent(baseUrl, headers, {
      source: 'android_relay',
      sessionId,
      workspace: 'current_repo',
      device: 'both_buds',
      event: 'android_push_to_talk',
      timestamp: Date.now(),
      utterance: 'open file app.ts',
      profile: 'default',
    });

    const approve = await sendRelayEvent(baseUrl, headers, {
      source: 'android_relay',
      sessionId,
      workspace: 'current_repo',
      device: 'both_buds',
      event: 'android_approve',
      timestamp: Date.now(),
      pendingActionId: secondPrompt.payload.actionId ?? undefined,
      profile: 'default',
    });

    process.stdout.write(`${JSON.stringify({
      ok: true,
      baseUrl,
      relayToken,
      totalDurationMs: Date.now() - smokeStartedAt,
      latencyMs: {
        health: health.durationMs,
        quickStatus: quickStatus.durationMs,
        voiceCommand: voiceCommand.durationMs,
        approvalPrompt: approvalPrompt.durationMs,
        cancel: cancel.durationMs,
        approvalStart: approve.durationMs,
      },
      responses: {
        health: health.payload,
        quickStatus: quickStatus.payload,
        voiceCommand: voiceCommand.payload,
        approvalPrompt: approvalPrompt.payload,
        cancel: cancel.payload,
        approvalStart: approve.payload,
      },
      desktopNotifierMessages: notifier.messages.length,
      auditLogPath,
    }, null, 2)}\n`);
  } finally {
    await new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
    if (previousDisableOpen === undefined) {
      delete process.env.JARVIS_DISABLE_OPEN;
    } else {
      process.env.JARVIS_DISABLE_OPEN = previousDisableOpen;
    }
    fs.rmSync(repoDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 50 });
  }
}

async function sendRelayEvent(
  baseUrl: string,
  headers: Record<string, string>,
  event: EarbudEvent,
): Promise<{ durationMs: number; payload: JarvisResponse }> {
  return measure(event.event, async () => {
    const response = await fetch(`${baseUrl}/events`, {
      method: 'POST',
      headers,
      body: JSON.stringify(event),
    });
    if (!response.ok) {
      throw new Error(await response.text());
    }

    return response.json() as Promise<JarvisResponse>;
  });
}

async function measure<T>(
  _label: string,
  action: () => Promise<T>,
): Promise<{ durationMs: number; payload: T }> {
  const startedAt = Date.now();
  const payload = await action();
  return {
    durationMs: Date.now() - startedAt,
    payload,
  };
}

void main().catch((error) => {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
  process.exitCode = 1;
});