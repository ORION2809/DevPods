import fs from 'node:fs';
import path from 'node:path';
import type { WorkspaceConfig } from '../protocol/schemas';

export interface WorkspacePathTarget {
  absolutePath: string | null;
  relativePath: string | null;
  reason: string | null;
  exists: boolean;
}

const protectedWorkspacePaths = ['.git'];

export function formatWorkspaceLabel(workspace: WorkspaceConfig): string {
  return workspace.label || path.basename(workspace.rootPath);
}

export function extractExplicitPathFromUtterance(utterance: string | null, patterns: RegExp[]): string | null {
  if (!utterance) {
    return null;
  }

  const trimmed = utterance.trim();
  const quotedMatch = trimmed.match(/["']([^"']+)["']/);
  if (quotedMatch?.[1]) {
    return quotedMatch[1].trim();
  }

  for (const pattern of patterns) {
    const match = trimmed.match(pattern);
    if (match?.[1]) {
      return match[1].trim();
    }
  }

  return null;
}

export function resolveWorkspacePath(
  workspaceRoot: string,
  candidatePath: string,
  options: { mustExist?: boolean; fileOnly?: boolean } = {},
): WorkspacePathTarget {
  const mustExist = options.mustExist ?? true;
  const fileOnly = options.fileOnly ?? true;
  const normalizedCandidate = candidatePath.replace(/\\/g, '/').trim();

  if (!normalizedCandidate) {
    return {
      absolutePath: null,
      relativePath: null,
      reason: 'The requested file path is empty.',
      exists: false,
    };
  }

  const workspaceRootRealPath = fs.realpathSync.native(workspaceRoot);
  const absoluteCandidatePath = path.resolve(workspaceRootRealPath, normalizedCandidate);
  const exists = fs.existsSync(absoluteCandidatePath);
  let effectivePath = absoluteCandidatePath;

  try {
    effectivePath = exists ? fs.realpathSync.native(absoluteCandidatePath) : absoluteCandidatePath;
  } catch {
    return {
      absolutePath: null,
      relativePath: null,
      reason: 'The requested file does not exist in the workspace.',
      exists: false,
    };
  }

  const normalizedRelativePath = path.relative(workspaceRootRealPath, effectivePath).replace(/\\/g, '/');

  if (normalizedRelativePath.startsWith('..') || path.isAbsolute(normalizedRelativePath)) {
    return {
      absolutePath: null,
      relativePath: null,
      reason: 'The requested file is outside the allowlisted workspace root.',
      exists,
    };
  }

  if (isProtectedWorkspacePath(normalizedRelativePath)) {
    return {
      absolutePath: null,
      relativePath: null,
      reason: 'The requested file path is protected by the local runtime.',
      exists,
    };
  }

  if (mustExist && !exists) {
    return {
      absolutePath: null,
      relativePath: null,
      reason: 'The requested file does not exist in the workspace.',
      exists: false,
    };
  }

  if (exists && fileOnly) {
    try {
      if (!fs.statSync(effectivePath).isFile()) {
        return {
          absolutePath: null,
          relativePath: null,
          reason: 'The requested file does not exist in the workspace.',
          exists: true,
        };
      }
    } catch {
      return {
        absolutePath: null,
        relativePath: null,
        reason: 'The requested file does not exist in the workspace.',
        exists: false,
      };
    }
  }

  return {
    absolutePath: effectivePath,
    relativePath: normalizedRelativePath || path.basename(effectivePath).replace(/\\/g, '/'),
    reason: null,
    exists,
  };
}

function isProtectedWorkspacePath(relativePath: string): boolean {
  return protectedWorkspacePaths.some((protectedPath) => relativePath === protectedPath || relativePath.startsWith(`${protectedPath}/`));
}