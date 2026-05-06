import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { WorkspaceRegistry } from '../src/protocol/schemas';
import type { Notifier } from '../src/bridge/speaker';
import { createBridgeRuntime } from '../src/bridge/runtime';

class MemoryNotifier implements Notifier {
  async notify(): Promise<void> {
    return;
  }
}

describe('DevPods CI actions', () => {
  let repoDir: string;
  let auditLogPath: string;

  beforeEach(() => {
    repoDir = fs.mkdtempSync(path.join(os.tmpdir(), 'jarvis-earbuds-ci-'));
    auditLogPath = path.join(repoDir, 'audit.log');
    execFileSync('git', ['init'], { cwd: repoDir, stdio: 'ignore' });
    execFileSync('git', ['config', 'user.email', 'jarvis@example.com'], { cwd: repoDir, stdio: 'ignore' });
    execFileSync('git', ['config', 'user.name', 'Jarvis'], { cwd: repoDir, stdio: 'ignore' });
    execFileSync('git', ['commit', '--allow-empty', '-m', 'chore: baseline'], { cwd: repoDir, stdio: 'ignore' });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();

    try {
      fs.rmSync(repoDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 50 });
    } catch (error) {
      if (!(error instanceof Error) || !error.message.includes('EPERM')) {
        throw error;
      }
    }
  });

  it('reads the latest GitHub Actions failure from the origin remote', async () => {
    execFileSync('git', ['remote', 'add', 'origin', 'git@github.com:octo/demo.git'], {
      cwd: repoDir,
      stdio: 'ignore',
    });

    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({
        'content-length': '512',
      }),
      json: async () => ({
        workflow_runs: [
          {
            conclusion: 'failure',
            status: 'completed',
            name: 'CI',
            display_title: 'Fix broken tests',
            head_branch: 'main',
            html_url: 'https://github.com/octo/demo/actions/runs/123',
            head_sha: 'abcdef1234567890',
          },
        ],
      }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'current_repo',
      workspaces: [
        {
          id: 'current_repo',
          label: 'temp_repo',
          rootPath: repoDir,
          allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure', 'run_tests'],
          approvalRequiredIntents: ['run_tests'],
          hardApprovalIntents: ['deploy'],
          commands: {},
        },
      ],
    };

    const runtime = createBridgeRuntime({
      registry,
      auditLogPath,
      notifier: new MemoryNotifier(),
    });

    const response = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_ci_failure',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'triple_tap_right',
      timestamp: Date.now(),
      utterance: 'show latest ci failure',
    });

    expect(response.status).toBe('completed');
    expect(response.speak).toContain('Latest CI failure');
    expect(response.display).toContain('CI');
    expect(response.display).toContain('Fix broken tests');
    expect(response.display).toContain('octo/demo');
    expect(fetchMock).toHaveBeenCalledOnce();

    const [requestUrl, requestOptions] = fetchMock.mock.calls[0] as [URL, RequestInit];
    expect(requestUrl.toString()).toContain('/repos/octo/demo/actions/runs');
    expect(requestUrl.searchParams.get('status')).toBe('completed');
    expect(requestUrl.searchParams.get('per_page')).toBe('10');
    expect(requestOptions.headers).toMatchObject({
      Accept: 'application/vnd.github+json',
      'User-Agent': 'devpods',
      'X-GitHub-Api-Version': '2022-11-28',
    });
  });

  it('gracefully reports when no GitHub origin remote is configured', async () => {
    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'current_repo',
      workspaces: [
        {
          id: 'current_repo',
          label: 'temp_repo',
          rootPath: repoDir,
          allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure', 'run_tests'],
          approvalRequiredIntents: ['run_tests'],
          hardApprovalIntents: ['deploy'],
          commands: {},
        },
      ],
    };

    const runtime = createBridgeRuntime({
      registry,
      auditLogPath,
      notifier: new MemoryNotifier(),
    });

    const response = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_ci_missing_remote',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'triple_tap_right',
      timestamp: Date.now(),
      utterance: 'show latest ci failure',
    });

    expect(response.status).toBe('completed');
    expect(response.speak).toContain('No GitHub Actions remote is configured');
  });

  it('gracefully reports when GitHub Actions data is inaccessible', async () => {
    execFileSync('git', ['remote', 'add', 'origin', 'https://github.com/octo/private-demo.git'], {
      cwd: repoDir,
      stdio: 'ignore',
    });

    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      headers: new Headers(),
    });
    vi.stubGlobal('fetch', fetchMock);

    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'current_repo',
      workspaces: [
        {
          id: 'current_repo',
          label: 'temp_repo',
          rootPath: repoDir,
          allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure', 'run_tests'],
          approvalRequiredIntents: ['run_tests'],
          hardApprovalIntents: ['deploy'],
          commands: {},
        },
      ],
    };

    const runtime = createBridgeRuntime({
      registry,
      auditLogPath,
      notifier: new MemoryNotifier(),
    });

    const response = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_ci_inaccessible',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'triple_tap_right',
      timestamp: Date.now(),
      utterance: 'show latest ci failure',
    });

    expect(response.status).toBe('completed');
    expect(response.speak).toContain('GitHub Actions data is unavailable');
  });
});