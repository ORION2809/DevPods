import fs from 'node:fs';
import type { BridgeRequest, JarvisResponse, WorkspaceConfig } from '../protocol/schemas';
import type { IntentName } from '../protocol/types';
import { getLatestCiFailure } from '../adapters/ci';
import { openFileInEditor, resolveOpenFileTarget } from '../adapters/editor';
import { commitStagedChanges, getCommitMessageSuggestion, getDiffSummary, getWorkspaceStatus, pushCurrentBranch, revertTrackedFile } from '../adapters/git';
import { startBackgroundCommand, type CommandResult } from '../adapters/process';
import { extractExplicitPathFromUtterance, resolveWorkspacePath } from '../adapters/workspace';
import { optimizeSpeak } from './voice-optimize';
import { AuditLog } from '../bridge/audit-log';
import { redactText } from '../policy/redaction';
import { BackgroundCommandScheduler } from './background-command-scheduler';

const allowedCommands = new Set(['npm', 'node', 'git']);
const dangerousArgumentPattern = /[;&|`$<>\n\r]/;
const maxArgumentLength = 1000;
const deletePathPatterns = [/(?:delete(?:\s+the)?(?:\s+file)?\s+)([A-Za-z0-9_./\\-]+(?:\.[A-Za-z0-9_-]+)?)\b/i];
const revertPathPatterns = [/(?:revert(?:\s+the)?(?:\s+file)?\s+)([A-Za-z0-9_./\\-]+(?:\.[A-Za-z0-9_-]+)?)\b/i];

interface BackgroundCommandCopy {
  missingSpeak: string;
  missingDisplay: string;
  startSpeak: string;
  successSpeak: string;
  timeoutSpeak: string;
  failureSpeak: string;
  backgroundFailureSpeak: string;
}

export class JarvisRuntime {
  private readonly backgroundCommandScheduler = new BackgroundCommandScheduler();

  constructor(
    private readonly auditLog: AuditLog,
    private readonly notifyBackground?: (sessionId: string, response: JarvisResponse) => Promise<void> | void,
  ) {}

  cancelBackgroundWork(sessionId: string): 'queued_cancelled' | 'running' | 'none' {
    const outcome = this.backgroundCommandScheduler.cancel(sessionId);

    if (outcome.cancelledQueued) {
      return 'queued_cancelled';
    }

    if (outcome.running) {
      return 'running';
    }

    return 'none';
  }

  async executeIntent(
    intent: IntentName,
    request: BridgeRequest,
    workspace: WorkspaceConfig,
    actionId?: string,
  ): Promise<JarvisResponse> {
    switch (intent) {
      case 'quick_status':
        return this.quickStatus(workspace);
      case 'summarize_diff':
        return this.summarizeDiff(workspace);
      case 'latest_ci_failure':
        return this.latestCiFailure(workspace);
      case 'run_tests':
        return this.runTests(request, workspace, actionId);
      case 'create_commit_message':
        return this.createCommitMessage(workspace);
      case 'commit_staged':
        return this.commitStaged(workspace);
      case 'open_file':
        return this.openFile(request, workspace);
      case 'push':
        return this.push(workspace);
      case 'deploy':
        return this.deploy(request, workspace, actionId);
      case 'delete':
        return this.deleteFile(request, workspace);
      case 'revert':
        return this.revertFile(request, workspace);
      default:
        return {
          speak: 'That action is not implemented yet.',
          display: 'The requested intent exists in the contract but is not implemented in this MVP.',
          requiresApproval: false,
          approvalRequest: null,
          actionId: null,
          status: 'blocked',
          nextState: 'idle',
          followUpHint: null,
        };
    }
  }

  private async quickStatus(workspace: WorkspaceConfig): Promise<JarvisResponse> {
    const status = await getWorkspaceStatus(workspace.rootPath);

    if (!status.repoDetected) {
      return {
        speak: optimizeSpeak('Workspace ready. Git repository not initialized. No tests running.'),
        display: `Workspace ${workspace.label} is allowlisted, but no git repository is initialized at ${workspace.rootPath}.`,
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'completed',
        nextState: 'idle',
        followUpHint: null,
      };
    }

    const changedLabel = `${status.changedFiles} file${status.changedFiles === 1 ? '' : 's'} changed`;
    return {
      speak: optimizeSpeak(`${status.branch}. ${changedLabel}. No tests running.`),
      display: `Branch ${status.branch}. ${changedLabel}.${status.mainChangedFile ? ` Main file: ${status.mainChangedFile}.` : ''}`,
      requiresApproval: false,
      approvalRequest: null,
      actionId: null,
      status: 'completed',
      nextState: 'idle',
      followUpHint: null,
    };
  }

  private async summarizeDiff(workspace: WorkspaceConfig): Promise<JarvisResponse> {
    const summary = await getDiffSummary(workspace.rootPath);

    if (summary.changedFiles === 0) {
      return {
        speak: optimizeSpeak('No local changes detected.'),
        display: 'No local diff was found for the selected workspace.',
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'completed',
        nextState: 'idle',
        followUpHint: null,
      };
    }

    return {
      speak: optimizeSpeak(
        `${summary.changedFiles} files changed.${summary.mainFile ? ` Main work is in ${summary.mainFile}.` : ''}`,
      ),
      display: `${summary.changedFiles} changed files.${summary.mainFile ? ` Main file: ${summary.mainFile}.` : ''}`,
      requiresApproval: false,
      approvalRequest: null,
      actionId: null,
      status: 'completed',
      nextState: 'idle',
      followUpHint: null,
    };
  }

  private async latestCiFailure(workspace: WorkspaceConfig): Promise<JarvisResponse> {
    const summary = await getLatestCiFailure(workspace.rootPath);

    if (summary.reason) {
      return {
        speak: optimizeSpeak(summary.reason),
        display: summary.reason,
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'completed',
        nextState: 'idle',
        followUpHint: summary.repository,
      };
    }

    return {
      speak: optimizeSpeak(`Latest CI failure: ${summary.workflowName} on ${summary.branch ?? 'unknown branch'}.`),
      display: `${summary.repository}: ${summary.workflowName} failed on ${summary.branch ?? 'unknown branch'}. ${summary.title ?? ''}${summary.sha ? ` SHA ${summary.sha}.` : ''}${summary.url ? ` ${summary.url}` : ''}`.trim(),
      requiresApproval: false,
      approvalRequest: null,
      actionId: null,
      status: 'completed',
      nextState: 'idle',
      followUpHint: summary.url,
    };
  }

  private async runTests(
    request: BridgeRequest,
    workspace: WorkspaceConfig,
    actionId?: string,
  ): Promise<JarvisResponse> {
    return this.runBackgroundWorkspaceCommand(request, workspace, actionId, 'run_tests', {
      missingSpeak: 'Run tests is not configured for this workspace.',
      missingDisplay: 'The workspace allowlist does not define a run_tests command.',
      startSpeak: 'Running tests. I will notify you when they finish.',
      successSpeak: 'Tests finished successfully.',
      timeoutSpeak: 'Tests timed out. Check the local logs.',
      failureSpeak: 'Tests failed. Check the latest output.',
      backgroundFailureSpeak: 'Tests failed before completion. Check the local logs.',
    });
  }

  private async createCommitMessage(workspace: WorkspaceConfig): Promise<JarvisResponse> {
    const suggestion = await getCommitMessageSuggestion(workspace.rootPath);

    if (!suggestion.message) {
      return {
        speak: optimizeSpeak('No local changes are ready for a commit message.'),
        display: 'No staged or working-tree changes were found for commit-message generation.',
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'completed',
        nextState: 'idle',
        followUpHint: null,
      };
    }

    return {
      speak: optimizeSpeak('Suggested commit message ready.'),
      display: `Suggested ${suggestion.source === 'staged' ? 'staged' : 'working tree'} commit message: ${suggestion.message}`,
      requiresApproval: false,
      approvalRequest: null,
      actionId: null,
      status: 'completed',
      nextState: 'idle',
      followUpHint: suggestion.mainFile,
    };
  }

  private async commitStaged(workspace: WorkspaceConfig): Promise<JarvisResponse> {
    const result = await commitStagedChanges(workspace.rootPath);

    if (!result.committed) {
      return {
        speak: optimizeSpeak(result.error ?? 'Unable to commit staged files.'),
        display: result.error ?? 'The staged commit failed.',
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: result.fileCount === 0 ? 'completed' : 'error',
        nextState: 'idle',
        followUpHint: result.mainFile,
      };
    }

    return {
      speak: optimizeSpeak('Committed staged files successfully.'),
      display: `Created commit: ${result.message}`,
      requiresApproval: false,
      approvalRequest: null,
      actionId: null,
      status: 'completed',
      nextState: 'idle',
      followUpHint: result.mainFile,
    };
  }

  private async openFile(request: BridgeRequest, workspace: WorkspaceConfig): Promise<JarvisResponse> {
    const target = await resolveOpenFileTarget(workspace.rootPath, request.utterance);

    if (!target.absolutePath || !target.relativePath) {
      return {
        speak: optimizeSpeak(target.reason ?? 'Unable to resolve a file to open.'),
        display: target.reason ?? 'No workspace file could be resolved.',
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'blocked',
        nextState: 'idle',
        followUpHint: null,
      };
    }

    const openResult = await openFileInEditor(workspace.rootPath, target.absolutePath);
    const actionSummary = openResult.opened ? `Opened ${target.relativePath} in VS Code.` : `Resolved file target: ${target.relativePath}.`;
    const detail = openResult.error ? `${actionSummary} ${openResult.error}` : actionSummary;

    return {
      speak: optimizeSpeak(openResult.opened ? `Opened ${target.relativePath}.` : 'Open file target ready.'),
      display: detail,
      requiresApproval: false,
      approvalRequest: null,
      actionId: null,
      status: 'completed',
      nextState: 'idle',
      followUpHint: target.relativePath,
    };
  }

  private async push(workspace: WorkspaceConfig): Promise<JarvisResponse> {
    const result = await pushCurrentBranch(workspace.rootPath);

    if (!result.pushed) {
      return {
        speak: optimizeSpeak(result.error ?? 'Unable to push the current branch.'),
        display: result.error ?? 'The branch push failed.',
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'blocked',
        nextState: 'idle',
        followUpHint: result.branch,
      };
    }

    return {
      speak: optimizeSpeak(`Pushed ${result.branch} to ${result.remote}.`),
      display: `Pushed branch ${result.branch} to ${result.remote}.`,
      requiresApproval: false,
      approvalRequest: null,
      actionId: null,
      status: 'completed',
      nextState: 'idle',
      followUpHint: result.branch,
    };
  }

  private async deploy(
    request: BridgeRequest,
    workspace: WorkspaceConfig,
    actionId?: string,
  ): Promise<JarvisResponse> {
    return this.runBackgroundWorkspaceCommand(request, workspace, actionId, 'deploy', {
      missingSpeak: 'Deploy is not configured for this workspace.',
      missingDisplay: 'The workspace allowlist does not define a deploy command.',
      startSpeak: 'Deployment started. I will notify you when it finishes.',
      successSpeak: 'Deployment finished successfully.',
      timeoutSpeak: 'Deployment timed out. Check the local logs.',
      failureSpeak: 'Deployment failed. Check the latest output.',
      backgroundFailureSpeak: 'Deployment failed before completion. Check the local logs.',
    });
  }

  private async deleteFile(request: BridgeRequest, workspace: WorkspaceConfig): Promise<JarvisResponse> {
    const explicitPath = extractExplicitPathFromUtterance(request.utterance, deletePathPatterns);
    if (!explicitPath) {
      return {
        speak: optimizeSpeak('Delete requires an explicit workspace file path.'),
        display: 'Provide a specific file path for delete, for example: delete file "src/example.ts".',
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'blocked',
        nextState: 'idle',
        followUpHint: null,
      };
    }

    const target = resolveWorkspacePath(workspace.rootPath, explicitPath, { mustExist: true, fileOnly: true });
    if (!target.absolutePath || !target.relativePath) {
      return {
        speak: optimizeSpeak(target.reason ?? 'Unable to resolve the file to delete.'),
        display: target.reason ?? 'No workspace file could be resolved for delete.',
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'blocked',
        nextState: 'idle',
        followUpHint: null,
      };
    }

    try {
      const deleteTarget = resolveWorkspacePath(workspace.rootPath, explicitPath, { mustExist: true, fileOnly: true });
      if (!deleteTarget.absolutePath || !deleteTarget.relativePath) {
        return {
          speak: optimizeSpeak(deleteTarget.reason ?? 'Unable to resolve the file to delete.'),
          display: deleteTarget.reason ?? 'No workspace file could be resolved for delete.',
          requiresApproval: false,
          approvalRequest: null,
          actionId: null,
          status: 'blocked',
          nextState: 'idle',
          followUpHint: null,
        };
      }

      fs.rmSync(deleteTarget.absolutePath, { maxRetries: 3, retryDelay: 100 });
      return {
        speak: optimizeSpeak(`Deleted ${deleteTarget.relativePath}.`),
        display: `Deleted workspace file: ${deleteTarget.relativePath}.`,
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'completed',
        nextState: 'idle',
        followUpHint: deleteTarget.relativePath,
      };
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      return {
        speak: optimizeSpeak('Delete failed. Check the local logs.'),
        display: redactText(`Unable to delete ${target.relativePath}: ${message}`),
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'error',
        nextState: 'idle',
        followUpHint: target.relativePath,
      };
    }
  }

  private async revertFile(request: BridgeRequest, workspace: WorkspaceConfig): Promise<JarvisResponse> {
    const explicitPath = extractExplicitPathFromUtterance(request.utterance, revertPathPatterns);
    if (!explicitPath) {
      return {
        speak: optimizeSpeak('Revert requires an explicit workspace file path.'),
        display: 'Provide a specific file path for revert, for example: revert file "src/example.ts".',
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'blocked',
        nextState: 'idle',
        followUpHint: null,
      };
    }

    const target = resolveWorkspacePath(workspace.rootPath, explicitPath, { mustExist: false, fileOnly: true });
    if (!target.relativePath) {
      return {
        speak: optimizeSpeak(target.reason ?? 'Unable to resolve the file to revert.'),
        display: target.reason ?? 'No workspace file could be resolved for revert.',
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'blocked',
        nextState: 'idle',
        followUpHint: null,
      };
    }

    const result = await revertTrackedFile(workspace.rootPath, target.relativePath);
    if (!result.reverted) {
      return {
        speak: optimizeSpeak(result.error ?? 'Unable to revert the requested file.'),
        display: result.error ?? 'The requested revert failed.',
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'blocked',
        nextState: 'idle',
        followUpHint: result.mainFile,
      };
    }

    return {
      speak: optimizeSpeak(`Reverted ${result.mainFile}.`),
      display: `Reverted local changes for ${result.mainFile}.`,
      requiresApproval: false,
      approvalRequest: null,
      actionId: null,
      status: 'completed',
      nextState: 'idle',
      followUpHint: result.mainFile,
    };
  }

  private async runBackgroundWorkspaceCommand(
    request: BridgeRequest,
    workspace: WorkspaceConfig,
    actionId: string | undefined,
    commandName: string,
    copy: BackgroundCommandCopy,
  ): Promise<JarvisResponse> {
    const command = workspace.commands[commandName];
    if (!command) {
      return {
        speak: optimizeSpeak(copy.missingSpeak),
        display: copy.missingDisplay,
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'blocked',
        nextState: 'idle',
        followUpHint: null,
      };
    }

    if (!allowedCommands.has(command.command)) {
      return {
        speak: optimizeSpeak('That command is not allowed.'),
        display: `The configured command '${command.command}' is outside the local allowlist.`,
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'blocked',
        nextState: 'idle',
        followUpHint: null,
      };
    }

    if (command.args.some((arg) => arg.length > maxArgumentLength || dangerousArgumentPattern.test(arg))) {
      return {
        speak: optimizeSpeak('Command arguments failed validation.'),
        display: 'The configured arguments contain forbidden shell metacharacters.',
        requiresApproval: false,
        approvalRequest: null,
        actionId: null,
        status: 'blocked',
        nextState: 'idle',
        followUpHint: null,
      };
    }

    const activeActionId = actionId ?? null;
    const scheduledTask = this.backgroundCommandScheduler.schedule(workspace.id, {
      sessionId: request.sessionId,
      taskFactory: () => startBackgroundCommand(command.command, command.args, {
        cwd: workspace.rootPath,
        timeoutMs: command.timeoutMs,
      }),
      onDeferredStart: async () => {
        const notification = buildBackgroundCommandStartResponse(command.description, workspace.rootPath, activeActionId, copy);
        this.auditLog.append({
          sessionId: request.sessionId,
          workspace: workspace.id,
          event: commandName,
          decision: 'running',
          status: notification.status,
          actionId: activeActionId,
          detail: notification.display,
        });

        await this.notifyBackground?.(request.sessionId, notification);
      },
      onComplete: async (result) => {
        const completedSuccessfully = result.exitCode === 0 && !result.timedOut;
        const notification = buildBackgroundCommandCompletionResponse(result, activeActionId, copy);

        this.auditLog.append({
          sessionId: request.sessionId,
          workspace: workspace.id,
          event: commandName,
          decision: completedSuccessfully ? 'completed' : 'failed',
          status: notification.status,
          actionId: activeActionId,
          detail: notification.display,
        });

        await this.notifyBackground?.(request.sessionId, notification);
      },
      onError: async (error) => {
        const message = error instanceof Error ? error.message : String(error);
        const notification: JarvisResponse = {
          speak: optimizeSpeak(copy.backgroundFailureSpeak),
          display: redactText(`Background task failure: ${message}`),
          requiresApproval: false,
          approvalRequest: null,
          actionId: activeActionId,
          status: 'error',
          nextState: 'idle',
          followUpHint: null,
        };

        this.auditLog.append({
          sessionId: request.sessionId,
          workspace: workspace.id,
          event: commandName,
          decision: 'failed',
          status: notification.status,
          actionId: activeActionId,
          detail: notification.display,
        });

        await this.notifyBackground?.(request.sessionId, notification);
      },
    });

    if (!scheduledTask.started) {
      const queuedResponse = buildQueuedBackgroundCommandResponse(
        command.description,
        workspace.rootPath,
        activeActionId,
        scheduledTask.tasksAhead,
      );

      this.auditLog.append({
        sessionId: request.sessionId,
        workspace: workspace.id,
        event: commandName,
        decision: 'queued',
        status: queuedResponse.status,
        actionId: activeActionId,
        detail: queuedResponse.display,
      });

      return queuedResponse;
    }

    return buildBackgroundCommandStartResponse(command.description, workspace.rootPath, activeActionId, copy);
  }
}

function buildBackgroundCommandStartResponse(
  commandDescription: string,
  workspaceRootPath: string,
  actionId: string | null,
  copy: BackgroundCommandCopy,
): JarvisResponse {
  return {
    speak: optimizeSpeak(copy.startSpeak),
    display: `Started ${commandDescription.toLowerCase()} in ${workspaceRootPath}.`,
    requiresApproval: false,
    approvalRequest: null,
    actionId,
    status: 'running',
    nextState: 'running',
    followUpHint: 'Background task started',
  };
}

function buildQueuedBackgroundCommandResponse(
  commandDescription: string,
  workspaceRootPath: string,
  actionId: string | null,
  tasksAhead: number,
): JarvisResponse {
  const aheadLabel = `${tasksAhead} task${tasksAhead === 1 ? '' : 's'} ahead`;

  return {
    speak: optimizeSpeak(`Queued ${commandDescription.toLowerCase()}. ${aheadLabel}.`),
    display: `Queued ${commandDescription.toLowerCase()} in ${workspaceRootPath}. ${aheadLabel}.`,
    requiresApproval: false,
    approvalRequest: null,
    actionId,
    status: 'acknowledged',
    nextState: 'queued',
    followUpHint: `Queued behind ${aheadLabel}`,
  };
}

function buildBackgroundCommandCompletionResponse(
  result: CommandResult,
  actionId: string | null,
  copy: BackgroundCommandCopy,
): JarvisResponse {
  const rawSummary = redactText(`${result.stdout}\n${result.stderr}`.trim()) ?? '';

  if (result.timedOut) {
    return {
      speak: optimizeSpeak(copy.timeoutSpeak),
      display: rawSummary || 'The configured test command timed out.',
      requiresApproval: false,
      approvalRequest: null,
      actionId,
      status: 'error',
      nextState: 'idle',
      followUpHint: null,
    };
  }

  if (result.exitCode === 0) {
    return {
      speak: optimizeSpeak(copy.successSpeak),
      display: rawSummary || 'The configured test command completed successfully.',
      requiresApproval: false,
      approvalRequest: null,
      actionId,
      status: 'completed',
      nextState: 'idle',
      followUpHint: null,
    };
  }

  return {
    speak: optimizeSpeak(copy.failureSpeak),
    display: rawSummary || 'The configured test command failed.',
    requiresApproval: false,
    approvalRequest: null,
    actionId,
    status: 'error',
    nextState: 'idle',
    followUpHint: null,
  };
}