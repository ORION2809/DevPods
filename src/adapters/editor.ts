import { getDiffSummary, getWorkspaceStatus } from './git';
import { runCommandCapture } from './process';
import { extractExplicitPathFromUtterance, resolveWorkspacePath, type WorkspacePathTarget } from './workspace';

export type OpenFileTarget = WorkspacePathTarget;

export interface OpenFileResult {
  opened: boolean;
  method: 'code' | 'disabled' | 'none';
  error: string | null;
}

const openFilePathPatterns = [/(?:open(?:\s+the)?(?:\s+file)?\s+)([A-Za-z0-9_./\\-]+\.[A-Za-z0-9_-]+)\b/i];

export async function resolveOpenFileTarget(workspaceRoot: string, utterance: string | null): Promise<OpenFileTarget> {
  const explicitPath = extractExplicitPathFromUtterance(utterance, openFilePathPatterns);
  if (explicitPath) {
    return resolveWorkspacePath(workspaceRoot, explicitPath, { mustExist: true, fileOnly: true });
  }

  const diffSummary = await getDiffSummary(workspaceRoot);
  if (diffSummary.mainFile) {
    return resolveWorkspacePath(workspaceRoot, diffSummary.mainFile, { mustExist: true, fileOnly: true });
  }

  const status = await getWorkspaceStatus(workspaceRoot);
  if (status.mainChangedFile) {
    return resolveWorkspacePath(workspaceRoot, status.mainChangedFile, { mustExist: true, fileOnly: true });
  }

  return {
    absolutePath: null,
    relativePath: null,
    reason: 'No changed file could be resolved for this workspace.',
    exists: false,
  };
}

export async function openFileInEditor(workspaceRoot: string, absolutePath: string): Promise<OpenFileResult> {
  if (process.env.JARVIS_DISABLE_OPEN === '1') {
    return {
      opened: false,
      method: 'disabled',
      error: null,
    };
  }

  const result = await runCommandCapture('code', ['--reuse-window', '--goto', absolutePath], {
    cwd: workspaceRoot,
    timeoutMs: 10000,
  });

  if (result.exitCode === 0) {
    return {
      opened: true,
      method: 'code',
      error: null,
    };
  }

  return {
    opened: false,
    method: 'none',
    error: result.stderr.trim() || result.stdout.trim() || 'Unable to open the file in VS Code.',
  };
}
