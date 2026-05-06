import { runCommandCapture } from './process';
import path from 'node:path';

export interface WorkspaceStatus {
  repoDetected: boolean;
  branch: string;
  changedFiles: number;
  mainChangedFile: string | null;
  testsRunning: boolean;
}

export interface CommitMessageSuggestion {
  source: 'staged' | 'working_tree';
  message: string | null;
  fileCount: number;
  mainFile: string | null;
}

export interface StagedCommitResult {
  committed: boolean;
  message: string | null;
  fileCount: number;
  mainFile: string | null;
  error: string | null;
}

export interface PushResult {
  pushed: boolean;
  branch: string | null;
  remote: string | null;
  error: string | null;
}

export interface RevertResult {
  reverted: boolean;
  fileCount: number;
  mainFile: string | null;
  error: string | null;
}

const ignoredWorkspaceFiles = new Set(['audit.log']);
const nonInteractiveGitEnv = {
  ...process.env,
  GIT_TERMINAL_PROMPT: '0',
};

export async function isGitRepository(cwd: string): Promise<boolean> {
  const result = await runCommandCapture('git', ['rev-parse', '--is-inside-work-tree'], { cwd, timeoutMs: 5000 });
  return result.exitCode === 0 && result.stdout.trim() === 'true';
}

export async function getWorkspaceStatus(cwd: string): Promise<WorkspaceStatus> {
  if (!(await isGitRepository(cwd))) {
    return {
      repoDetected: false,
      branch: 'not-a-git-repo',
      changedFiles: 0,
      mainChangedFile: null,
      testsRunning: false,
    };
  }

  const branch = await runCommandCapture('git', ['rev-parse', '--abbrev-ref', 'HEAD'], { cwd });
  const porcelain = await runCommandCapture('git', ['status', '--porcelain'], { cwd });
  if (branch.exitCode !== 0 || porcelain.exitCode !== 0) {
    return {
      repoDetected: true,
      branch: branch.stdout.trim() || 'unknown',
      changedFiles: 0,
      mainChangedFile: null,
      testsRunning: false,
    };
  }

  const changedLines = porcelain.stdout
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .filter((line) => !isIgnoredWorkspaceFile(extractPathFromPorcelain(line)));

  return {
    repoDetected: true,
    branch: branch.stdout.trim() || 'unknown',
    changedFiles: changedLines.length,
    mainChangedFile: extractPathFromPorcelain(changedLines[0] ?? null),
    testsRunning: false,
  };
}

export async function getDiffSummary(cwd: string): Promise<{ changedFiles: number; mainFile: string | null }> {
  if (!(await isGitRepository(cwd))) {
    return {
      changedFiles: 0,
      mainFile: null,
    };
  }

  const diff = await runCommandCapture('git', ['diff', '--stat'], { cwd });
  if (diff.exitCode !== 0) {
    return {
      changedFiles: 0,
      mainFile: null,
    };
  }

  const statLines = diff.stdout
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('-') && !line.includes('file changed'))
    .filter((line) => !isIgnoredWorkspaceFile(extractPathFromStat(line)));

  return {
    changedFiles: statLines.length,
    mainFile: extractPathFromStat(statLines[0] ?? null),
  };
}

export async function getCommitMessageSuggestion(cwd: string): Promise<CommitMessageSuggestion> {
  if (!(await isGitRepository(cwd))) {
    return {
      source: 'working_tree',
      message: null,
      fileCount: 0,
      mainFile: null,
    };
  }

  const staged = await summarizeNameStatus(cwd, ['diff', '--cached', '--name-status']);
  if (staged.fileCount > 0) {
    return {
      ...staged,
      source: 'staged',
      message: buildCommitMessage(staged.files),
    };
  }

  const workingTree = await summarizeNameStatus(cwd, ['diff', '--name-status']);
  return {
    ...workingTree,
    source: 'working_tree',
    message: workingTree.fileCount > 0 ? buildCommitMessage(workingTree.files) : null,
  };
}

export async function commitStagedChanges(cwd: string): Promise<StagedCommitResult> {
  if (!(await isGitRepository(cwd))) {
    return {
      committed: false,
      message: null,
      fileCount: 0,
      mainFile: null,
      error: 'The current workspace is not a git repository.',
    };
  }

  const staged = await summarizeNameStatus(cwd, ['diff', '--cached', '--name-status']);
  if (staged.fileCount === 0) {
    return {
      committed: false,
      message: null,
      fileCount: 0,
      mainFile: null,
      error: 'No staged changes are ready to commit.',
    };
  }

  const message = buildCommitMessage(staged.files);
  const result = await runCommandCapture('git', ['commit', '-m', message], {
    cwd,
    timeoutMs: 30000,
  });

  if (result.exitCode !== 0) {
    return {
      committed: false,
      message,
      fileCount: staged.fileCount,
      mainFile: staged.mainFile,
      error: result.stderr.trim() || result.stdout.trim() || 'Git commit failed.',
    };
  }

  return {
    committed: true,
    message,
    fileCount: staged.fileCount,
    mainFile: staged.mainFile,
    error: null,
  };
}

export async function pushCurrentBranch(cwd: string): Promise<PushResult> {
  if (!(await isGitRepository(cwd))) {
    return {
      pushed: false,
      branch: null,
      remote: null,
      error: 'The current workspace is not a git repository.',
    };
  }

  const branchResult = await runCommandCapture('git', ['rev-parse', '--abbrev-ref', 'HEAD'], {
    cwd,
    timeoutMs: 5000,
    env: nonInteractiveGitEnv,
  });

  const branch = branchResult.stdout.trim() || null;
  if (branchResult.exitCode !== 0 || !branch) {
    return {
      pushed: false,
      branch: null,
      remote: null,
      error: 'Unable to determine the current branch.',
    };
  }

  const upstreamResult = await runCommandCapture('git', ['rev-parse', '--abbrev-ref', '--symbolic-full-name', '@{u}'], {
    cwd,
    timeoutMs: 5000,
    env: nonInteractiveGitEnv,
  });

  if (upstreamResult.exitCode !== 0) {
    return {
      pushed: false,
      branch,
      remote: null,
      error: 'No upstream branch is configured for the current branch.',
    };
  }

  const upstream = upstreamResult.stdout.trim();
  const remote = upstream.split('/')[0] ?? null;
  const pushResult = await runCommandCapture('git', ['push', '--porcelain'], {
    cwd,
    timeoutMs: 30000,
    env: nonInteractiveGitEnv,
  });

  if (pushResult.exitCode !== 0) {
    return {
      pushed: false,
      branch,
      remote,
      error: pushResult.stderr.trim() || pushResult.stdout.trim() || 'Git push failed.',
    };
  }

  return {
    pushed: true,
    branch,
    remote,
    error: null,
  };
}

export async function revertTrackedFile(cwd: string, relativePath: string): Promise<RevertResult> {
  if (!(await isGitRepository(cwd))) {
    return {
      reverted: false,
      fileCount: 0,
      mainFile: null,
      error: 'The current workspace is not a git repository.',
    };
  }

  const trackedResult = await runCommandCapture('git', ['ls-files', '--error-unmatch', '--', relativePath], {
    cwd,
    timeoutMs: 5000,
    env: nonInteractiveGitEnv,
  });

  if (trackedResult.exitCode !== 0) {
    return {
      reverted: false,
      fileCount: 0,
      mainFile: relativePath,
      error: 'The requested file is not tracked by git.',
    };
  }

  const restoreResult = await runCommandCapture('git', ['restore', '--source=HEAD', '--staged', '--worktree', '--', relativePath], {
    cwd,
    timeoutMs: 30000,
    env: nonInteractiveGitEnv,
  });

  if (restoreResult.exitCode !== 0) {
    return {
      reverted: false,
      fileCount: 0,
      mainFile: relativePath,
      error: restoreResult.stderr.trim() || restoreResult.stdout.trim() || 'Git restore failed.',
    };
  }

  return {
    reverted: true,
    fileCount: 1,
    mainFile: relativePath,
    error: null,
  };
}

function extractPathFromPorcelain(line: string | null): string | null {
  if (!line || line.length < 4) {
    return null;
  }

  const pathPortion = line.slice(3).trim();
  if (!pathPortion) {
    return null;
  }

  return pathPortion.split(' -> ').at(-1)?.trim() || null;
}

function extractPathFromStat(line: string | null): string | null {
  if (!line) {
    return null;
  }

  const pipeIndex = line.indexOf('|');
  return (pipeIndex === -1 ? line : line.slice(0, pipeIndex)).trim() || null;
}

async function summarizeNameStatus(
  cwd: string,
  args: string[],
): Promise<{ fileCount: number; mainFile: string | null; files: string[] }> {
  const result = await runCommandCapture('git', args, { cwd });
  if (result.exitCode !== 0) {
    return {
      fileCount: 0,
      mainFile: null,
      files: [],
    };
  }

  const lines = result.stdout
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);
  const files = lines
    .map((line) => line.split(/\s+/).slice(1).at(-1) ?? null)
    .filter((value): value is string => Boolean(value));

  return {
    fileCount: files.length,
    mainFile: files[0] ?? null,
    files,
  };
}

function buildCommitMessage(files: string[]): string {
  const type = classifyCommitType(files);
  const subject = buildCommitSubject(files);
  return `${type}: ${subject}`;
}

function classifyCommitType(files: string[]): 'docs' | 'test' | 'chore' | 'feat' {
  if (files.every((file) => file.endsWith('.md') || file.startsWith('docs/'))) {
    return 'docs';
  }

  if (files.every((file) => /(^test\/|\.test\.|\.spec\.)/i.test(file))) {
    return 'test';
  }

  if (files.every((file) => ['package.json', 'package-lock.json', 'tsconfig.json'].includes(file) || file.startsWith('config/'))) {
    return 'chore';
  }

  return 'feat';
}

function buildCommitSubject(files: string[]): string {
  if (files.length === 0) {
    return 'update workspace';
  }

  if (files.length === 1) {
    return `update ${humanizeFile(files[0])}`;
  }

  const topLevelDirs = new Set(files.map((file) => file.split('/')[0]));
  if (topLevelDirs.size === 1) {
    return `update ${Array.from(topLevelDirs)[0]} flow`;
  }

  return `update ${files.length} files`;
}

function humanizeFile(filePath: string): string {
  const normalized = filePath.replace(/\\/g, '/');
  const baseName = path.basename(normalized, path.extname(normalized));
  return sanitizeForCommitMessage(baseName.replace(/[-_]+/g, ' '));
}

function sanitizeForCommitMessage(text: string): string {
  return text.replace(/[\x00-\x1F\x7F]/g, '').trim() || 'workspace';
}

function isIgnoredWorkspaceFile(filePath: string | null): boolean {
  if (!filePath) {
    return false;
  }

  return ignoredWorkspaceFiles.has(filePath.replace(/\\/g, '/'));
}