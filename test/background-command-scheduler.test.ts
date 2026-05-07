import { describe, expect, it, vi } from 'vitest';
import type { BackgroundTask, CommandResult } from '../src/adapters/process';
import { BackgroundCommandScheduler } from '../src/jarvis/background-command-scheduler';

function createBackgroundTask(
  completion: Promise<CommandResult>,
  pid = 100,
  cancel = vi.fn(),
): BackgroundTask {
  return {
    pid,
    completion,
    cancel,
  };
}

describe('background command scheduler', () => {
  it('starts tasks immediately in different workspaces', () => {
    const scheduler = new BackgroundCommandScheduler();

    const firstResult = scheduler.schedule('workspace_a', {
      sessionId: 'session_a',
      taskFactory: () => createBackgroundTask(new Promise(() => {
        return;
      }), 101),
      onComplete: vi.fn(),
      onError: vi.fn(),
    });
    const secondResult = scheduler.schedule('workspace_b', {
      sessionId: 'session_b',
      taskFactory: () => createBackgroundTask(new Promise(() => {
        return;
      }), 202),
      onComplete: vi.fn(),
      onError: vi.fn(),
    });

    expect(firstResult.started).toBe(true);
    expect(firstResult.pid).toBe(101);
    expect(secondResult.started).toBe(true);
    expect(secondResult.pid).toBe(202);
  });

  it('waits for a task-factory error handler before starting the next queued task', async () => {
    const scheduler = new BackgroundCommandScheduler();
    const timeline: string[] = [];
    const errorHandlerControl: { release: () => void } = {
      release: () => {
        throw new Error('Expected the error handler resolver to be initialized.');
      },
    };
    const errorHandled = new Promise<void>((resolve) => {
      errorHandlerControl.release = resolve;
    });

    scheduler.schedule('workspace_a', {
      sessionId: 'session_error',
      taskFactory: () => {
        throw new Error('synthetic launch failure');
      },
      onComplete: () => {
        timeline.push('unexpected-complete');
      },
      onError: async () => {
        timeline.push('error-start');
        await errorHandled;
        timeline.push('error-finish');
      },
    });

    const deferredStart = vi.fn(() => {
      timeline.push('next-start');
    });
    const secondTask = scheduler.schedule('workspace_a', {
      sessionId: 'session_next',
      taskFactory: () => createBackgroundTask(Promise.resolve({
        stdout: '',
        stderr: '',
        exitCode: 0,
        timedOut: false,
        cancelled: false,
      }), 303),
      onDeferredStart: deferredStart,
      onComplete: () => {
        timeline.push('next-complete');
      },
      onError: vi.fn(),
    });

    expect(secondTask.started).toBe(false);
    expect(secondTask.tasksAhead).toBe(1);

    await Promise.resolve();
    expect(timeline).toEqual(['error-start']);
    expect(deferredStart).not.toHaveBeenCalled();

    errorHandlerControl.release();
    await vi.waitFor(() => {
      expect(timeline).toEqual(['error-start', 'error-finish', 'next-start', 'next-complete']);
      expect(deferredStart).toHaveBeenCalledTimes(1);
    });
  });

  it('cancels a running task and releases the next queued task', async () => {
    const scheduler = new BackgroundCommandScheduler();
    const releaseFirstTask: { finish: () => void } = {
      finish: () => {
        throw new Error('Expected cancellation handler to be initialized.');
      },
    };
    const firstCompletion = new Promise<CommandResult>((resolve) => {
      releaseFirstTask.finish = () => {
        resolve({
          stdout: '',
          stderr: '',
          exitCode: null,
          timedOut: false,
          cancelled: true,
        });
      };
    });
    const cancelFirstTask = vi.fn(() => {
      releaseFirstTask.finish();
    });
    const firstComplete = vi.fn();
    const firstError = vi.fn();

    scheduler.schedule('workspace_a', {
      sessionId: 'session_running',
      taskFactory: () => createBackgroundTask(firstCompletion, 101, cancelFirstTask),
      onComplete: firstComplete,
      onError: firstError,
    });

    const nextDeferredStart = vi.fn();
    const nextComplete = vi.fn();
    const secondTask = scheduler.schedule('workspace_a', {
      sessionId: 'session_waiting',
      taskFactory: () => createBackgroundTask(Promise.resolve({
        stdout: '',
        stderr: '',
        exitCode: 0,
        timedOut: false,
        cancelled: false,
      }), 202),
      onDeferredStart: nextDeferredStart,
      onComplete: nextComplete,
      onError: vi.fn(),
    });

    expect(secondTask.started).toBe(false);

    const cancelled = scheduler.cancel('session_running');
    expect(cancelled.cancelledRunning).toBe(true);
    expect(cancelled.cancelledQueued).toBe(false);
    expect(cancelFirstTask).toHaveBeenCalledTimes(1);

    await vi.waitFor(() => {
      expect(firstComplete).not.toHaveBeenCalled();
      expect(firstError).not.toHaveBeenCalled();
      expect(nextDeferredStart).toHaveBeenCalledTimes(1);
      expect(nextComplete).toHaveBeenCalledTimes(1);
    });
  });
});