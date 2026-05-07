import { execFile, spawn, type ChildProcess, type SpawnOptions } from 'node:child_process';

const DEFAULT_COMMAND_TIMEOUT_MS = 10_000;
const DEFAULT_BACKGROUND_TIMEOUT_MS = 120_000;
const MAX_CAPTURE_BYTES = 64 * 1024;
const OUTPUT_TRUNCATED_MARKER = '\n[OUTPUT TRUNCATED]\n';

export interface CommandResult {
  stdout: string;
  stderr: string;
  exitCode: number | null;
  timedOut: boolean;
  cancelled: boolean;
}

export interface BackgroundTask {
  pid: number | undefined;
  completion: Promise<CommandResult>;
  cancel: () => void;
}

interface OutputAccumulator {
  value: string;
  bytes: number;
  truncated: boolean;
}

export function runCommandCapture(
  command: string,
  args: string[],
  options: { cwd: string; timeoutMs?: number; env?: NodeJS.ProcessEnv },
): Promise<CommandResult> {
  const timeoutMs = options.timeoutMs ?? DEFAULT_COMMAND_TIMEOUT_MS;

  return new Promise((resolve) => {
    const child = spawn(command, args, buildSpawnOptions(options));

    const stdout = createOutputAccumulator();
    const stderr = createOutputAccumulator();
    let settled = false;
    let timedOut = false;

    const finish = (result: CommandResult) => {
      if (!settled) {
        settled = true;
        resolve(result);
      }
    };

    const timer = setTimeout(() => {
      timedOut = true;
      terminateProcessTree(child);
    }, timeoutMs);

    const stdoutStream = child.stdout;
    const stderrStream = child.stderr;

    if (!stdoutStream || !stderrStream) {
      terminateProcessTree(child);
      clearTimeout(timer);
      finish({
        stdout: stdout.value,
        stderr: 'Process output streams were unavailable.',
        exitCode: null,
        timedOut: false,
          cancelled: false,
      });
      return;
    }

    stdoutStream.on('data', (chunk) => {
      appendCapturedOutput(stdout, chunk);
    });

    stderrStream.on('data', (chunk) => {
      appendCapturedOutput(stderr, chunk);
    });

    child.on('error', (error) => {
      clearTimeout(timer);
      if (timedOut) {
        finish({ stdout: stdout.value, stderr: stderr.value || 'Timed out.', exitCode: null, timedOut: true, cancelled: false });
        return;
      }

      finish({ stdout: stdout.value, stderr: error.message, exitCode: null, timedOut: false, cancelled: false });
    });

    child.on('close', (exitCode) => {
      clearTimeout(timer);
      if (timedOut) {
        finish({ stdout: stdout.value, stderr: stderr.value || 'Timed out.', exitCode: null, timedOut: true, cancelled: false });
        return;
      }

      finish({ stdout: stdout.value, stderr: stderr.value, exitCode, timedOut: false, cancelled: false });
    });
  });
}

export function startBackgroundCommand(
  command: string,
  args: string[],
  options: { cwd: string; timeoutMs?: number; env?: NodeJS.ProcessEnv },
): BackgroundTask {
  const timeoutMs = options.timeoutMs ?? DEFAULT_BACKGROUND_TIMEOUT_MS;
  const child = spawn(command, args, buildSpawnOptions(options));
  let cancelled = false;

  const completion = new Promise<CommandResult>((resolve) => {
    const stdout = createOutputAccumulator();
    const stderr = createOutputAccumulator();
    let settled = false;
    let timedOut = false;

    const finish = (result: CommandResult) => {
      if (!settled) {
        settled = true;
        resolve(result);
      }
    };

    const timer = setTimeout(() => {
      timedOut = true;
      terminateProcessTree(child);
    }, timeoutMs);

    const stdoutStream = child.stdout;
    const stderrStream = child.stderr;

    if (!stdoutStream || !stderrStream) {
      terminateProcessTree(child);
      clearTimeout(timer);
      finish({
        stdout: stdout.value,
        stderr: 'Process output streams were unavailable.',
        exitCode: null,
        timedOut: false,
          cancelled: false,
      });
      return;
    }

    stdoutStream.on('data', (chunk) => {
      appendCapturedOutput(stdout, chunk);
    });

    stderrStream.on('data', (chunk) => {
      appendCapturedOutput(stderr, chunk);
    });

    child.on('error', (error) => {
      clearTimeout(timer);
      if (cancelled) {
        finish({ stdout: stdout.value, stderr: stderr.value || 'Cancelled.', exitCode: null, timedOut: false, cancelled: true });
        return;
      }

      if (timedOut) {
        finish({ stdout: stdout.value, stderr: stderr.value || 'Timed out.', exitCode: null, timedOut: true, cancelled: false });
        return;
      }

      finish({ stdout: stdout.value, stderr: error.message, exitCode: null, timedOut: false, cancelled: false });
    });

    child.on('close', (exitCode) => {
      clearTimeout(timer);
      if (cancelled) {
        finish({ stdout: stdout.value, stderr: stderr.value || 'Cancelled.', exitCode: null, timedOut: false, cancelled: true });
        return;
      }

      if (timedOut) {
        finish({ stdout: stdout.value, stderr: stderr.value || 'Timed out.', exitCode: null, timedOut: true, cancelled: false });
        return;
      }

      finish({ stdout: stdout.value, stderr: stderr.value, exitCode, timedOut: false, cancelled: false });
    });
  });

  return {
    pid: child.pid,
    completion,
    cancel: () => {
      if (cancelled) {
        return;
      }

      cancelled = true;
      terminateProcessTree(child);
    },
  };
}

function buildSpawnOptions(options: { cwd: string; env?: NodeJS.ProcessEnv }): SpawnOptions {
  return {
    cwd: options.cwd,
    shell: false,
    env: options.env,
    windowsHide: true,
    detached: process.platform !== 'win32',
    stdio: ['ignore', 'pipe', 'pipe'],
  };
}

function createOutputAccumulator(): OutputAccumulator {
  return {
    value: '',
    bytes: 0,
    truncated: false,
  };
}

function appendCapturedOutput(accumulator: OutputAccumulator, chunk: Buffer | string): void {
  if (accumulator.truncated) {
    return;
  }

  const chunkText = typeof chunk === 'string' ? chunk : chunk.toString();
  const chunkBytes = Buffer.byteLength(chunkText);
  const remainingBytes = MAX_CAPTURE_BYTES - accumulator.bytes;

  if (remainingBytes <= 0) {
    accumulator.value += OUTPUT_TRUNCATED_MARKER;
    accumulator.truncated = true;
    return;
  }

  if (chunkBytes <= remainingBytes) {
    accumulator.value += chunkText;
    accumulator.bytes += chunkBytes;
    return;
  }

  accumulator.value += Buffer.from(chunkText).subarray(0, remainingBytes).toString('utf8');
  accumulator.value += OUTPUT_TRUNCATED_MARKER;
  accumulator.bytes = MAX_CAPTURE_BYTES;
  accumulator.truncated = true;
}

function terminateProcessTree(child: ChildProcess): void {
  if (!child.pid) {
    try {
      child.kill('SIGKILL');
    } catch {
      // Ignore best-effort cleanup failures.
    }
    return;
  }

  if (process.platform === 'win32') {
    execFile('taskkill', ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true }, () => {
      try {
        child.kill('SIGKILL');
      } catch {
        // Ignore best-effort cleanup failures.
      }
    });
    return;
  }

  try {
    process.kill(-child.pid, 'SIGKILL');
  } catch {
    try {
      child.kill('SIGKILL');
    } catch {
      // Ignore best-effort cleanup failures.
    }
  }
}