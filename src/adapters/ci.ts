import { isGitRepository } from './git';
import { runCommandCapture } from './process';

const failingConclusions = new Set(['action_required', 'cancelled', 'failure', 'startup_failure', 'stale', 'timed_out']);
const githubComponentPattern = /^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,99})$/;
const maxCiResponseBytes = 1_000_000;

export interface LatestCiFailureSummary {
  repository: string | null;
  workflowName: string | null;
  title: string | null;
  branch: string | null;
  url: string | null;
  sha: string | null;
  reason: string | null;
}

interface WorkflowRun {
  conclusion?: string | null;
  name?: string | null;
  display_title?: string | null;
  head_branch?: string | null;
  html_url?: string | null;
  head_sha?: string | null;
}

export async function getLatestCiFailure(
  cwd: string,
  fetchImpl: typeof fetch = fetch,
): Promise<LatestCiFailureSummary> {
  if (!(await isGitRepository(cwd))) {
    return {
      repository: null,
      workflowName: null,
      title: null,
      branch: null,
      url: null,
      sha: null,
      reason: 'The current workspace is not a git repository.',
    };
  }

  const remoteUrl = await getOriginRemoteUrl(cwd);
  if (!remoteUrl) {
    return {
      repository: null,
      workflowName: null,
      title: null,
      branch: null,
      url: null,
      sha: null,
      reason: 'No GitHub Actions remote is configured for this workspace.',
    };
  }

  const repository = parseGitHubRepository(remoteUrl);
  if (!repository) {
    return {
      repository: null,
      workflowName: null,
      title: null,
      branch: null,
      url: null,
      sha: null,
      reason: 'The origin remote is not a GitHub repository.',
    };
  }

  const requestUrl = new URL(`https://api.github.com/repos/${repository.owner}/${repository.repo}/actions/runs`);
  requestUrl.searchParams.set('status', 'completed');
  requestUrl.searchParams.set('per_page', '10');

  try {
    const response = await fetchImpl(requestUrl, {
      headers: {
        Accept: 'application/vnd.github+json',
        'User-Agent': 'jarvis-earbuds-mvp',
        'X-GitHub-Api-Version': '2022-11-28',
      },
      signal: AbortSignal.timeout(10000),
    });

    if (!response.ok) {
      return {
        repository: `${repository.owner}/${repository.repo}`,
        workflowName: null,
        title: null,
        branch: null,
        url: null,
        sha: null,
        reason:
          response.status === 403 || response.status === 404
            ? 'GitHub Actions data is unavailable for this repository without additional access.'
            : `GitHub Actions lookup failed with status ${response.status}.`,
      };
    }

    const contentLengthHeader = response.headers.get('content-length');
    const contentLength = contentLengthHeader ? Number.parseInt(contentLengthHeader, 10) : Number.NaN;
    if (Number.isFinite(contentLength) && contentLength > maxCiResponseBytes) {
      return {
        repository: `${repository.owner}/${repository.repo}`,
        workflowName: null,
        title: null,
        branch: null,
        url: null,
        sha: null,
        reason: 'GitHub Actions lookup returned too much data for the local runtime to process safely.',
      };
    }

    const payload = (await response.json()) as { workflow_runs?: unknown };
    const runs = Array.isArray(payload.workflow_runs) ? payload.workflow_runs.filter(isWorkflowRun) : [];
    const failure = runs.find((run) => failingConclusions.has(run.conclusion ?? ''));

    if (!failure) {
      return {
        repository: `${repository.owner}/${repository.repo}`,
        workflowName: null,
        title: null,
        branch: null,
        url: null,
        sha: null,
        reason: `No recent GitHub Actions failures were found for ${repository.owner}/${repository.repo}.`,
      };
    }

    return {
      repository: `${repository.owner}/${repository.repo}`,
      workflowName: failure.name ?? 'GitHub Actions',
      title: failure.display_title ?? 'Latest failed workflow run',
      branch: failure.head_branch ?? null,
      url: failure.html_url ?? null,
      sha: failure.head_sha?.slice(0, 7) ?? null,
      reason: null,
    };
  } catch (error) {
    return {
      repository: `${repository.owner}/${repository.repo}`,
      workflowName: null,
      title: null,
      branch: null,
      url: null,
      sha: null,
      reason:
        error instanceof Error && error.name === 'AbortError'
          ? 'GitHub Actions lookup timed out.'
          : 'GitHub Actions lookup failed locally. Check network connectivity.',
    };
  }
}

async function getOriginRemoteUrl(cwd: string): Promise<string | null> {
  const result = await runCommandCapture('git', ['remote', 'get-url', 'origin'], { cwd, timeoutMs: 5000 });
  if (result.exitCode !== 0) {
    return null;
  }

  const remoteUrl = result.stdout.trim();
  return remoteUrl || null;
}

function parseGitHubRepository(remoteUrl: string): { owner: string; repo: string } | null {
  const normalized = remoteUrl.trim().replace(/^ssh:\/\/git@github\.com\//i, 'git@github.com:');
  const match = normalized.match(/github\.com[:/]([^/]+)\/([^/]+?)(?:\.git)?$/i);
  if (!match) {
    return null;
  }

  const owner = match[1];
  const repo = match[2];
  if (
    !githubComponentPattern.test(owner)
    || !githubComponentPattern.test(repo)
    || owner.includes('..')
    || repo.includes('..')
  ) {
    return null;
  }

  return {
    owner,
    repo,
  };
}

function isWorkflowRun(value: unknown): value is WorkflowRun {
  if (!value || typeof value !== 'object') {
    return false;
  }

  const run = value as Record<string, unknown>;
  return (
    typeof run.conclusion === 'string'
    && (run.name === null || run.name === undefined || typeof run.name === 'string')
    && (run.display_title === null || run.display_title === undefined || typeof run.display_title === 'string')
    && (run.head_branch === null || run.head_branch === undefined || typeof run.head_branch === 'string')
    && (run.html_url === null || run.html_url === undefined || typeof run.html_url === 'string')
    && (run.head_sha === null || run.head_sha === undefined || typeof run.head_sha === 'string')
  );
}