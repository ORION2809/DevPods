import type { WorkspaceConfig } from '../protocol/schemas';
import type { WorkspaceStatus } from '../adapters/git';
import type { LatestCiFailureSummary } from '../adapters/ci';
import { getWorkspaceStatus, getDiffSummary } from '../adapters/git';
import { getLatestCiFailure } from '../adapters/ci';

interface DiffSummary {
  changedFiles: number;
  mainFile: string | null;
}

interface CacheEntry<T> {
  data: T;
  cachedAt: number;
  ttlMs: number;
}

const DEFAULT_STATUS_TTL_MS = 2000;
const DEFAULT_DIFF_TTL_MS = 2000;
const DEFAULT_CI_TTL_MS = 30000;

export class WorkspaceSnapshotCache {
  private readonly entries = new Map<string, CacheEntry<unknown>>();
  private readonly inFlight = new Map<string, Promise<unknown>>();
  private hits = 0;
  private misses = 0;

  private makeKey(workspaceRoot: string, kind: string): string {
    return `${workspaceRoot}::${kind}`;
  }

  private isExpired(entry: CacheEntry<unknown>): boolean {
    return Date.now() - entry.cachedAt > entry.ttlMs;
  }

  private getCached<T>(key: string): T | undefined {
    const entry = this.entries.get(key) as CacheEntry<T> | undefined;
    if (!entry) {
      this.misses++;
      return undefined;
    }
    if (this.isExpired(entry)) {
      this.entries.delete(key);
      this.misses++;
      return undefined;
    }
    this.hits++;
    return entry.data;
  }

  private setCached<T>(key: string, data: T, ttlMs: number): void {
    this.entries.set(key, { data, cachedAt: Date.now(), ttlMs });
  }

  private async fetchAndStore<T>(
    key: string,
    fetcher: () => Promise<T>,
    ttlMs: number,
  ): Promise<T> {
    const inFlight = this.inFlight.get(key) as Promise<T> | undefined;
    if (inFlight) return inFlight;

    const promise = fetcher()
      .then((data) => {
        this.setCached(key, data, ttlMs);
        this.inFlight.delete(key);
        return data;
      })
      .catch((error) => {
        this.inFlight.delete(key);
        throw error;
      });

    this.inFlight.set(key, promise);
    return promise;
  }

  async getStatus(
    workspace: WorkspaceConfig,
    options: { forceRefresh?: boolean } = {},
  ): Promise<WorkspaceStatus> {
    const key = this.makeKey(workspace.rootPath, 'status');
    if (!options.forceRefresh) {
      const cached = this.getCached<WorkspaceStatus>(key);
      if (cached !== undefined) return cached;
    }
    return this.fetchAndStore(key, () => getWorkspaceStatus(workspace.rootPath), DEFAULT_STATUS_TTL_MS);
  }

  async getDiffSummary(
    workspace: WorkspaceConfig,
    options: { forceRefresh?: boolean } = {},
  ): Promise<DiffSummary> {
    const key = this.makeKey(workspace.rootPath, 'diff');
    if (!options.forceRefresh) {
      const cached = this.getCached<DiffSummary>(key);
      if (cached !== undefined) return cached;
    }
    return this.fetchAndStore(key, () => getDiffSummary(workspace.rootPath), DEFAULT_DIFF_TTL_MS);
  }

  async getLatestCiFailure(
    workspace: WorkspaceConfig,
    options: { forceRefresh?: boolean } = {},
  ): Promise<LatestCiFailureSummary> {
    const key = this.makeKey(workspace.rootPath, 'ci');
    if (!options.forceRefresh) {
      const cached = this.getCached<LatestCiFailureSummary>(key);
      if (cached !== undefined) return cached;
    }
    return this.fetchAndStore(key, () => getLatestCiFailure(workspace.rootPath), DEFAULT_CI_TTL_MS);
  }

  invalidate(workspaceRoot: string): void {
    this.entries.delete(this.makeKey(workspaceRoot, 'status'));
    this.entries.delete(this.makeKey(workspaceRoot, 'diff'));
    this.entries.delete(this.makeKey(workspaceRoot, 'ci'));
  }

  getTelemetry(): { hits: number; misses: number } {
    return { hits: this.hits, misses: this.misses };
  }

  async prefetch(workspaceRoot: string): Promise<void> {
    try {
      const status = await getWorkspaceStatus(workspaceRoot);
      this.setCached(this.makeKey(workspaceRoot, 'status'), status, DEFAULT_STATUS_TTL_MS);
    } catch {
      // Safe prewarm: ignore errors so non-git directories do not break startup
    }

    try {
      const diff = await getDiffSummary(workspaceRoot);
      this.setCached(this.makeKey(workspaceRoot, 'diff'), diff, DEFAULT_DIFF_TTL_MS);
    } catch {
      // Safe prewarm: ignore errors so non-git directories do not break startup
    }
  }

  async prefetchCiSnapshot(workspaceRoot: string): Promise<void> {
    try {
      const ci = await getLatestCiFailure(workspaceRoot);
      this.setCached(this.makeKey(workspaceRoot, 'ci'), ci, DEFAULT_CI_TTL_MS);
    } catch {
      // Safe prewarm: ignore errors so non-git directories do not break startup
    }
  }
}
