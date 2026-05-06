import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { WorkspaceRegistry } from '../src/protocol/schemas';
import type { Notifier } from '../src/bridge/speaker';
import { createBridgeRuntime } from '../src/bridge/runtime';
import { getWorkspaceStatus } from '../src/adapters/git';

class MemoryNotifier implements Notifier {
  async notify(): Promise<void> {
    return;
  }
}

function getCurrentBranch(cwd: string): string {
  return execFileSync('git', ['rev-parse', '--abbrev-ref', 'HEAD'], {
    cwd,
    encoding: 'utf8',
  }).trim();
}

describe('jarvis local actions', () => {
  let repoDir: string;
  let auditLogPath: string;

  beforeEach(() => {
    repoDir = fs.mkdtempSync(path.join(os.tmpdir(), 'jarvis-earbuds-actions-'));
    auditLogPath = path.join(repoDir, 'audit.log');
    execFileSync('git', ['init'], { cwd: repoDir, stdio: 'ignore' });
    execFileSync('git', ['config', 'user.email', 'jarvis@example.com'], { cwd: repoDir, stdio: 'ignore' });
    execFileSync('git', ['config', 'user.name', 'Jarvis'], { cwd: repoDir, stdio: 'ignore' });
  });

  afterEach(() => {
    try {
      fs.rmSync(repoDir, { recursive: true, force: true, maxRetries: 5, retryDelay: 50 });
    } catch (error) {
      if (!(error instanceof Error) || !error.message.includes('EPERM')) {
        throw error;
      }
    }
  });

  it('generates a deterministic commit message from staged changes', async () => {
    const filePath = path.join(repoDir, 'src_feature.ts');
    fs.writeFileSync(filePath, 'export const value = 1;\n', 'utf8');
    execFileSync('git', ['add', 'src_feature.ts'], { cwd: repoDir, stdio: 'ignore' });

    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'current_repo',
      workspaces: [
        {
          id: 'current_repo',
          label: 'temp_repo',
          rootPath: repoDir,
          allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure', 'run_tests', 'create_commit_message'],
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
      sessionId: 'sess_commit',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'triple_tap_right',
      timestamp: Date.now(),
      utterance: 'create a commit message',
    });

    expect(response.status).toBe('completed');
    expect(response.speak).toContain('Suggested commit message ready');
    expect(response.display).toContain('feat: update src feature');
  });

  it('blocks approval gestures that omit the pending action id', async () => {
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
    fs.writeFileSync(path.join(repoDir, 'fake-task.js'), 'process.exit(0);\n', 'utf8');

    const runtime = createBridgeRuntime({
      registry,
      auditLogPath,
      notifier: new MemoryNotifier(),
    });

    await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_blocked_approval',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'triple_tap_right',
      timestamp: Date.now(),
      utterance: 'run the tests',
    });

    const response = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_blocked_approval',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'approve_right_double_tap',
      timestamp: Date.now(),
    });

    expect(response.status).toBe('blocked');
    expect(response.speak).toContain('Approval token missing');
  });

  it('commits staged files only after hard approval', async () => {
    const filePath = path.join(repoDir, 'src_feature.ts');
    fs.writeFileSync(filePath, 'export const committed = true;\n', 'utf8');
    execFileSync('git', ['add', 'src_feature.ts'], { cwd: repoDir, stdio: 'ignore' });

    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'current_repo',
      workspaces: [
        {
          id: 'current_repo',
          label: 'temp_repo',
          rootPath: repoDir,
          allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure', 'run_tests', 'create_commit_message', 'commit_staged'],
          approvalRequiredIntents: ['run_tests'],
          hardApprovalIntents: ['commit_staged', 'deploy'],
          commands: {},
        },
      ],
    };

    const runtime = createBridgeRuntime({
      registry,
      auditLogPath,
      notifier: new MemoryNotifier(),
    });

    const approvalPrompt = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_commit_staged',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'triple_tap_right',
      timestamp: Date.now(),
      utterance: 'commit the staged files',
    });

    expect(approvalPrompt.requiresApproval).toBe(true);
    expect(approvalPrompt.approvalRequest?.riskClass).toBe('hard_approval');
    expect(approvalPrompt.actionId).toBeTruthy();

    const approvalResponse = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_commit_staged',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'approve_right_double_tap',
      timestamp: Date.now(),
      pendingActionId: approvalPrompt.actionId ?? undefined,
    });

    expect(approvalResponse.status).toBe('completed');
    expect(approvalResponse.speak).toContain('Committed staged files');

    const committedMessage = execFileSync('git', ['log', '-1', '--pretty=%s'], {
      cwd: repoDir,
      encoding: 'utf8',
    }).trim();

    expect(committedMessage).toBe('feat: update src feature');
  });

  it('opens the main changed file only after approval', async () => {
    execFileSync('git', ['commit', '--allow-empty', '-m', 'chore: baseline'], { cwd: repoDir, stdio: 'ignore' });
    const filePath = path.join(repoDir, 'src_feature.ts');
    fs.writeFileSync(filePath, 'export const value = 1;\n', 'utf8');

    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'current_repo',
      workspaces: [
        {
          id: 'current_repo',
          label: 'temp_repo',
          rootPath: repoDir,
          allowedIntents: [
            'quick_status',
            'summarize_diff',
            'latest_ci_failure',
            'run_tests',
            'create_commit_message',
            'open_file',
            'commit_staged',
          ],
          approvalRequiredIntents: ['run_tests', 'open_file'],
          hardApprovalIntents: ['commit_staged', 'deploy'],
          commands: {},
        },
      ],
    };

    const previousDisableOpen = process.env.JARVIS_DISABLE_OPEN;
    process.env.JARVIS_DISABLE_OPEN = '1';

    try {
      const runtime = createBridgeRuntime({
        registry,
        auditLogPath,
        notifier: new MemoryNotifier(),
      });

      const approvalPrompt = await runtime.handleEvent({
        source: 'developer_earbuds_simulator',
        sessionId: 'sess_open_file',
        workspace: 'current_repo',
        device: 'right_bud',
        event: 'triple_tap_right',
        timestamp: Date.now(),
        utterance: 'open the main file',
      });

      expect(approvalPrompt.requiresApproval).toBe(true);
      expect(approvalPrompt.approvalRequest?.riskClass).toBe('approval_required');

      const approvalResponse = await runtime.handleEvent({
        source: 'developer_earbuds_simulator',
        sessionId: 'sess_open_file',
        workspace: 'current_repo',
        device: 'right_bud',
        event: 'approve_right_double_tap',
        timestamp: Date.now(),
        pendingActionId: approvalPrompt.actionId ?? undefined,
      });

      expect(approvalResponse.status).toBe('completed');
      expect(approvalResponse.speak).toContain('Open file target ready');
      expect(approvalResponse.display).toContain('src_feature.ts');
    } finally {
      if (previousDisableOpen === undefined) {
        delete process.env.JARVIS_DISABLE_OPEN;
      } else {
        process.env.JARVIS_DISABLE_OPEN = previousDisableOpen;
      }
    }
  });

  it('reports when no staged changes exist after commit approval', async () => {
    execFileSync('git', ['commit', '--allow-empty', '-m', 'chore: baseline'], { cwd: repoDir, stdio: 'ignore' });

    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'current_repo',
      workspaces: [
        {
          id: 'current_repo',
          label: 'temp_repo',
          rootPath: repoDir,
          allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure', 'run_tests', 'create_commit_message', 'commit_staged'],
          approvalRequiredIntents: ['run_tests'],
          hardApprovalIntents: ['commit_staged', 'deploy'],
          commands: {},
        },
      ],
    };

    const runtime = createBridgeRuntime({
      registry,
      auditLogPath,
      notifier: new MemoryNotifier(),
    });

    const approvalPrompt = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_empty_commit',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'triple_tap_right',
      timestamp: Date.now(),
      utterance: 'commit the staged files',
    });

    const approvalResponse = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_empty_commit',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'approve_right_double_tap',
      timestamp: Date.now(),
      pendingActionId: approvalPrompt.actionId ?? undefined,
    });

    expect(approvalResponse.status).toBe('completed');
    expect(approvalResponse.speak).toContain('No staged changes are ready to commit');
  });

  it('blocks open_file requests that escape the workspace root', async () => {
    execFileSync('git', ['commit', '--allow-empty', '-m', 'chore: baseline'], { cwd: repoDir, stdio: 'ignore' });
    const outsideFile = path.join(os.tmpdir(), `jarvis-outside-${Date.now()}.txt`);
    const outsideRelativePath = path.relative(repoDir, outsideFile).replace(/\\/g, '/');
    fs.writeFileSync(outsideFile, 'outside\n', 'utf8');

    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'current_repo',
      workspaces: [
        {
          id: 'current_repo',
          label: 'temp_repo',
          rootPath: repoDir,
          allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure', 'run_tests', 'create_commit_message', 'open_file'],
          approvalRequiredIntents: ['run_tests', 'open_file'],
          hardApprovalIntents: ['commit_staged', 'deploy'],
          commands: {},
        },
      ],
    };

    const previousDisableOpen = process.env.JARVIS_DISABLE_OPEN;
    process.env.JARVIS_DISABLE_OPEN = '1';

    try {
      const runtime = createBridgeRuntime({
        registry,
        auditLogPath,
        notifier: new MemoryNotifier(),
      });

      const approvalPrompt = await runtime.handleEvent({
        source: 'developer_earbuds_simulator',
        sessionId: 'sess_open_escape',
        workspace: 'current_repo',
        device: 'right_bud',
        event: 'triple_tap_right',
        timestamp: Date.now(),
        utterance: `open file ${outsideRelativePath}`,
      });

      const approvalResponse = await runtime.handleEvent({
        source: 'developer_earbuds_simulator',
        sessionId: 'sess_open_escape',
        workspace: 'current_repo',
        device: 'right_bud',
        event: 'approve_right_double_tap',
        timestamp: Date.now(),
        pendingActionId: approvalPrompt.actionId ?? undefined,
      });

      expect(approvalResponse.status).toBe('blocked');
      expect(approvalResponse.speak).toContain('outside the allowlisted workspace root');
    } finally {
      if (previousDisableOpen === undefined) {
        delete process.env.JARVIS_DISABLE_OPEN;
      } else {
        process.env.JARVIS_DISABLE_OPEN = previousDisableOpen;
      }

      fs.rmSync(outsideFile, { force: true });
    }
  });

  it('ignores the audit log when summarizing workspace status', async () => {
    execFileSync('git', ['commit', '--allow-empty', '-m', 'chore: baseline'], { cwd: repoDir, stdio: 'ignore' });
    fs.writeFileSync(path.join(repoDir, 'audit.log'), 'runtime event\n', 'utf8');
    fs.writeFileSync(path.join(repoDir, 'src_feature.ts'), 'export const value = 1;\n', 'utf8');

    const status = await getWorkspaceStatus(repoDir);

    expect(status.changedFiles).toBe(1);
    expect(status.mainChangedFile).toBe('src_feature.ts');
  });

  it('pushes the current branch only after hard approval', async () => {
    const remoteDir = fs.mkdtempSync(path.join(os.tmpdir(), 'jarvis-earbuds-remote-'));

    try {
      const filePath = path.join(repoDir, 'pushed.ts');
      fs.writeFileSync(filePath, 'export const version = 1;\n', 'utf8');
      execFileSync('git', ['add', 'pushed.ts'], { cwd: repoDir, stdio: 'ignore' });
      execFileSync('git', ['commit', '-m', 'feat: prepare push'], { cwd: repoDir, stdio: 'ignore' });

      const branch = getCurrentBranch(repoDir);

      execFileSync('git', ['init', '--bare', remoteDir], { stdio: 'ignore' });
      execFileSync('git', ['remote', 'add', 'origin', remoteDir], { cwd: repoDir, stdio: 'ignore' });
      execFileSync('git', ['push', '-u', 'origin', branch], { cwd: repoDir, stdio: 'ignore' });

      fs.writeFileSync(filePath, 'export const version = 2;\n', 'utf8');
      execFileSync('git', ['add', 'pushed.ts'], { cwd: repoDir, stdio: 'ignore' });
      execFileSync('git', ['commit', '-m', 'feat: publish followup'], { cwd: repoDir, stdio: 'ignore' });

      const registry: WorkspaceRegistry = {
        defaultWorkspaceId: 'current_repo',
        workspaces: [
          {
            id: 'current_repo',
            label: 'temp_repo',
            rootPath: repoDir,
            allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure', 'run_tests', 'push'],
            approvalRequiredIntents: ['run_tests'],
            hardApprovalIntents: ['push', 'deploy'],
            commands: {},
          },
        ],
      };

      const runtime = createBridgeRuntime({
        registry,
        auditLogPath,
        notifier: new MemoryNotifier(),
      });

      const approvalPrompt = await runtime.handleEvent({
        source: 'developer_earbuds_simulator',
        sessionId: 'sess_push',
        workspace: 'current_repo',
        device: 'right_bud',
        event: 'triple_tap_right',
        timestamp: Date.now(),
        utterance: 'push the current branch',
      });

      expect(approvalPrompt.requiresApproval).toBe(true);
      expect(approvalPrompt.approvalRequest?.riskClass).toBe('hard_approval');

      const approvalResponse = await runtime.handleEvent({
        source: 'developer_earbuds_simulator',
        sessionId: 'sess_push',
        workspace: 'current_repo',
        device: 'right_bud',
        event: 'approve_right_double_tap',
        timestamp: Date.now(),
        pendingActionId: approvalPrompt.actionId ?? undefined,
      });

      expect(approvalResponse.status).toBe('completed');
      expect(approvalResponse.speak).toContain('Pushed');

      const remoteMessage = execFileSync('git', ['--git-dir', remoteDir, 'log', '-1', '--pretty=%s', `refs/heads/${branch}`], {
        encoding: 'utf8',
      }).trim();

      expect(remoteMessage).toBe('feat: publish followup');
    } finally {
      fs.rmSync(remoteDir, { recursive: true, force: true });
    }
  });

  it('deletes an explicit workspace file only after hard approval', async () => {
    const filePath = path.join(repoDir, 'delete_me.ts');
    fs.writeFileSync(filePath, 'export const doomed = true;\n', 'utf8');

    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'current_repo',
      workspaces: [
        {
          id: 'current_repo',
          label: 'temp_repo',
          rootPath: repoDir,
          allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure', 'run_tests', 'delete'],
          approvalRequiredIntents: ['run_tests'],
          hardApprovalIntents: ['delete', 'deploy'],
          commands: {},
        },
      ],
    };

    const runtime = createBridgeRuntime({
      registry,
      auditLogPath,
      notifier: new MemoryNotifier(),
    });

    const approvalPrompt = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_delete',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'triple_tap_right',
      timestamp: Date.now(),
      utterance: 'delete file "delete_me.ts"',
    });

    expect(approvalPrompt.requiresApproval).toBe(true);
    expect(approvalPrompt.approvalRequest?.riskClass).toBe('hard_approval');

    const approvalResponse = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_delete',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'approve_right_double_tap',
      timestamp: Date.now(),
      pendingActionId: approvalPrompt.actionId ?? undefined,
    });

    expect(approvalResponse.status).toBe('completed');
    expect(approvalResponse.speak).toContain('Deleted');
    expect(fs.existsSync(filePath)).toBe(false);
  });

  it('reverts an explicit tracked file only after hard approval', async () => {
    const filePath = path.join(repoDir, 'tracked.ts');
    fs.writeFileSync(filePath, 'export const value = 1;\n', 'utf8');
    execFileSync('git', ['add', 'tracked.ts'], { cwd: repoDir, stdio: 'ignore' });
    execFileSync('git', ['commit', '-m', 'feat: add tracked file'], { cwd: repoDir, stdio: 'ignore' });

    fs.writeFileSync(filePath, 'export const value = 2;\n', 'utf8');
    execFileSync('git', ['add', 'tracked.ts'], { cwd: repoDir, stdio: 'ignore' });

    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'current_repo',
      workspaces: [
        {
          id: 'current_repo',
          label: 'temp_repo',
          rootPath: repoDir,
          allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure', 'run_tests', 'revert'],
          approvalRequiredIntents: ['run_tests'],
          hardApprovalIntents: ['revert', 'deploy'],
          commands: {},
        },
      ],
    };

    const runtime = createBridgeRuntime({
      registry,
      auditLogPath,
      notifier: new MemoryNotifier(),
    });

    const approvalPrompt = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_revert',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'triple_tap_right',
      timestamp: Date.now(),
      utterance: 'revert file "tracked.ts"',
    });

    expect(approvalPrompt.requiresApproval).toBe(true);
    expect(approvalPrompt.approvalRequest?.riskClass).toBe('hard_approval');

    const approvalResponse = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_revert',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'approve_right_double_tap',
      timestamp: Date.now(),
      pendingActionId: approvalPrompt.actionId ?? undefined,
    });

    expect(approvalResponse.status).toBe('completed');
    expect(approvalResponse.speak).toContain('Reverted');
    expect(fs.readFileSync(filePath, 'utf8')).toBe('export const value = 1;\n');
  });

  it('blocks delete requests for protected git paths', async () => {
    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'current_repo',
      workspaces: [
        {
          id: 'current_repo',
          label: 'temp_repo',
          rootPath: repoDir,
          allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure', 'run_tests', 'delete'],
          approvalRequiredIntents: ['run_tests'],
          hardApprovalIntents: ['delete', 'deploy'],
          commands: {},
        },
      ],
    };

    const runtime = createBridgeRuntime({
      registry,
      auditLogPath,
      notifier: new MemoryNotifier(),
    });

    const approvalPrompt = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_delete_protected',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'triple_tap_right',
      timestamp: Date.now(),
      utterance: 'delete file ".git/config"',
    });

    const approvalResponse = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_delete_protected',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'approve_right_double_tap',
      timestamp: Date.now(),
      pendingActionId: approvalPrompt.actionId ?? undefined,
    });

    expect(approvalResponse.status).toBe('blocked');
    expect(approvalResponse.speak).toContain('protected by the local runtime');
  });

  it('blocks revert requests for protected git paths', async () => {
    const registry: WorkspaceRegistry = {
      defaultWorkspaceId: 'current_repo',
      workspaces: [
        {
          id: 'current_repo',
          label: 'temp_repo',
          rootPath: repoDir,
          allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure', 'run_tests', 'revert'],
          approvalRequiredIntents: ['run_tests'],
          hardApprovalIntents: ['revert', 'deploy'],
          commands: {},
        },
      ],
    };

    const runtime = createBridgeRuntime({
      registry,
      auditLogPath,
      notifier: new MemoryNotifier(),
    });

    const approvalPrompt = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_revert_protected',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'triple_tap_right',
      timestamp: Date.now(),
      utterance: 'revert file ".git/HEAD"',
    });

    const approvalResponse = await runtime.handleEvent({
      source: 'developer_earbuds_simulator',
      sessionId: 'sess_revert_protected',
      workspace: 'current_repo',
      device: 'right_bud',
      event: 'approve_right_double_tap',
      timestamp: Date.now(),
      pendingActionId: approvalPrompt.actionId ?? undefined,
    });

    expect(approvalResponse.status).toBe('blocked');
    expect(approvalResponse.speak).toContain('protected by the local runtime');
  });
});