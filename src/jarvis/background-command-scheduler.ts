import type { BackgroundTask, CommandResult } from '../adapters/process';

interface BackgroundCommandQueueEntry {
  readonly sessionId: string;
  readonly taskFactory: () => BackgroundTask;
  readonly onDeferredStart?: () => Promise<void> | void;
  readonly onComplete: (result: CommandResult) => Promise<void> | void;
  readonly onError: (error: unknown) => Promise<void> | void;
}

interface BackgroundCommandLane {
  readonly active: BackgroundCommandQueueEntry | null;
  readonly queue: BackgroundCommandQueueEntry[];
}

export interface ScheduledBackgroundCommand {
  readonly started: boolean;
  readonly tasksAhead: number;
  readonly pid: number | undefined;
}

export class BackgroundCommandScheduler {
  private readonly lanes = new Map<string, BackgroundCommandLane>();

  cancel(sessionId: string): { cancelledQueued: boolean; running: boolean } {
    let cancelledQueued = false;
    let running = false;

    for (const [workspaceId, lane] of this.lanes.entries()) {
      if (lane.active?.sessionId === sessionId) {
        running = true;
      }

      const nextQueue = lane.queue.filter((entry) => entry.sessionId !== sessionId);
      if (nextQueue.length === lane.queue.length) {
        continue;
      }

      cancelledQueued = true;
      if (!lane.active && nextQueue.length === 0) {
        this.lanes.delete(workspaceId);
        continue;
      }

      this.lanes.set(workspaceId, {
        active: lane.active,
        queue: nextQueue,
      });
    }

    return { cancelledQueued, running };
  }

  schedule(workspaceId: string, entry: BackgroundCommandQueueEntry): ScheduledBackgroundCommand {
    const lane = this.lanes.get(workspaceId) ?? { active: null, queue: [] };

    if (!lane.active) {
      return this.startEntry(workspaceId, { active: null, queue: lane.queue }, entry, false);
    }

    const updatedLane: BackgroundCommandLane = {
      active: lane.active,
      queue: [...lane.queue, entry],
    };
    this.lanes.set(workspaceId, updatedLane);

    return {
      started: false,
      tasksAhead: updatedLane.queue.length,
      pid: undefined,
    };
  }

  private startEntry(
    workspaceId: string,
    lane: BackgroundCommandLane,
    entry: BackgroundCommandQueueEntry,
    deferred: boolean,
  ): ScheduledBackgroundCommand {
    const nextLane: BackgroundCommandLane = {
      active: entry,
      queue: lane.queue,
    };
    this.lanes.set(workspaceId, nextLane);

    let task: BackgroundTask;
    try {
      task = entry.taskFactory();
    } catch (error) {
      void (async () => {
        await Promise.resolve(entry.onError(error)).catch(() => {
          return;
        });
        this.advanceQueue(workspaceId, entry);
      })();

      return {
        started: true,
        tasksAhead: 0,
        pid: undefined,
      };
    }

    const deferredStartNotification = deferred
      ? Promise.resolve(entry.onDeferredStart?.()).catch(() => {
        return;
      })
      : Promise.resolve();

    void task.completion
      .then(async (result) => {
        await deferredStartNotification;
        await entry.onComplete(result);
      })
      .catch(async (error) => {
        await deferredStartNotification;
        await entry.onError(error);
      })
      .finally(() => {
        this.advanceQueue(workspaceId, entry);
      });

    return {
      started: true,
      tasksAhead: 0,
      pid: task.pid,
    };
  }

  private advanceQueue(workspaceId: string, completedEntry: BackgroundCommandQueueEntry): void {
    const lane = this.lanes.get(workspaceId);
    if (!lane || lane.active !== completedEntry) {
      return;
    }

    const [nextEntry, ...remainingQueue] = lane.queue;

    if (!nextEntry) {
      this.lanes.delete(workspaceId);
      return;
    }

    this.startEntry(workspaceId, { active: null, queue: remainingQueue }, nextEntry, true);
  }
}