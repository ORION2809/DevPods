import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { describe, expect, it } from 'vitest';
import { runCommandCapture, startBackgroundCommand } from '../src/adapters/process';

function isProcessAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

async function waitForProcessExit(pid: number, timeoutMs: number): Promise<boolean> {
  const deadline = Date.now() + timeoutMs;

  while (Date.now() < deadline) {
    if (!isProcessAlive(pid)) {
      return true;
    }

    await delay(100);
  }

  return !isProcessAlive(pid);
}

function createParentChildProbeScript(): string {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'jarvis-process-adapter-'));
  const scriptPath = path.join(tempDir, 'parent-child-probe.js');

  fs.writeFileSync(
    scriptPath,
    [
      "const { spawn } = require('node:child_process');",
      "const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 30000)'], { stdio: 'ignore' });",
      "process.stdout.write(String(child.pid));",
      "setInterval(() => {}, 30000);",
    ].join('\n'),
    'utf8',
  );

  return scriptPath;
}

describe('process adapter', () => {
  it('caps stdout accumulation and marks truncation', async () => {
    const result = await runCommandCapture(process.execPath, ['-e', "process.stdout.write('x'.repeat(200000))"], {
      cwd: process.cwd(),
      timeoutMs: 5_000,
    });

    expect(result.timedOut).toBe(false);
    expect(result.stdout.length).toBeLessThan(80_000);
    expect(result.stdout).toContain('[OUTPUT TRUNCATED]');
  });

  it('caps stderr accumulation and marks truncation', async () => {
    const result = await runCommandCapture(process.execPath, ['-e', "process.stderr.write('y'.repeat(200000))"], {
      cwd: process.cwd(),
      timeoutMs: 5_000,
    });

    expect(result.timedOut).toBe(false);
    expect(result.stderr.length).toBeLessThan(80_000);
    expect(result.stderr).toContain('[OUTPUT TRUNCATED]');
  });

  it('kills the spawned child tree when a foreground command times out', async () => {
    const scriptPath = createParentChildProbeScript();

    try {
      const result = await runCommandCapture(process.execPath, [scriptPath], {
        cwd: process.cwd(),
        timeoutMs: 300,
      });

      const childPid = Number(result.stdout.trim());
      expect(result.timedOut).toBe(true);
      expect(childPid).toBeGreaterThan(0);
      expect(await waitForProcessExit(childPid, 5_000)).toBe(true);
    } finally {
      fs.rmSync(path.dirname(scriptPath), { recursive: true, force: true, maxRetries: 5, retryDelay: 50 });
    }
  });

  it('kills the spawned child tree when a background command times out', async () => {
    const scriptPath = createParentChildProbeScript();

    try {
      const task = startBackgroundCommand(process.execPath, [scriptPath], {
        cwd: process.cwd(),
        timeoutMs: 300,
      });
      const result = await task.completion;
      const childPid = Number(result.stdout.trim());

      expect(result.timedOut).toBe(true);
      expect(result.cancelled).toBe(false);
      expect(task.pid).toBeTypeOf('number');
      expect(childPid).toBeGreaterThan(0);
      expect(isProcessAlive(task.pid as number)).toBe(false);
      expect(isProcessAlive(childPid)).toBe(false);
      expect(await waitForProcessExit(childPid, 5_000)).toBe(true);
      expect(await waitForProcessExit(task.pid as number, 5_000)).toBe(true);
    } finally {
      fs.rmSync(path.dirname(scriptPath), { recursive: true, force: true, maxRetries: 5, retryDelay: 50 });
    }
  });

  it('kills the spawned child tree when a background command is explicitly cancelled', async () => {
    const scriptPath = createParentChildProbeScript();

    try {
      const task = startBackgroundCommand(process.execPath, [scriptPath], {
        cwd: process.cwd(),
        timeoutMs: 5_000,
      });

      await delay(100);
      task.cancel();

      const result = await task.completion;
      const childPid = Number(result.stdout.trim());

      expect(result.timedOut).toBe(false);
      expect(result.cancelled).toBe(true);
      expect(task.pid).toBeTypeOf('number');
      expect(childPid).toBeGreaterThan(0);
      expect(isProcessAlive(task.pid as number)).toBe(false);
      expect(isProcessAlive(childPid)).toBe(false);
      expect(await waitForProcessExit(childPid, 5_000)).toBe(true);
      expect(await waitForProcessExit(task.pid as number, 5_000)).toBe(true);
    } finally {
      fs.rmSync(path.dirname(scriptPath), { recursive: true, force: true, maxRetries: 5, retryDelay: 50 });
    }
  });
});