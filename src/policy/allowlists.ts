import fs from 'node:fs';
import path from 'node:path';
import { workspaceRegistrySchema, type WorkspaceConfig, type WorkspaceRegistry } from '../protocol/schemas';

export function loadWorkspaceRegistry(configPath: string): WorkspaceRegistry {
  const safeConfigPath = validatePathWithinBase(configPath, process.cwd());
  const raw = fs.readFileSync(safeConfigPath, 'utf8');
  const parsed = workspaceRegistrySchema.parse(JSON.parse(raw));

  return {
    ...parsed,
    workspaces: parsed.workspaces.map((workspace) => ({
      ...workspace,
      rootPath: path.isAbsolute(workspace.rootPath)
        ? workspace.rootPath
        : path.resolve(path.dirname(safeConfigPath), workspace.rootPath),
    })),
  };
}

export function resolveWorkspace(registry: WorkspaceRegistry, workspaceId?: string): WorkspaceConfig {
  const id = workspaceId ?? registry.defaultWorkspaceId;
  const workspace = registry.workspaces.find((candidate) => candidate.id === id);

  if (!workspace) {
    throw new Error(`Workspace '${id}' is not allowlisted.`);
  }

  const stats = fs.statSync(workspace.rootPath, { throwIfNoEntry: false });
  if (!stats || !stats.isDirectory()) {
    throw new Error(`Workspace root '${workspace.rootPath}' is not available.`);
  }

  return workspace;
}

function validatePathWithinBase(candidatePath: string, basePath: string): string {
  const resolvedBase = path.resolve(basePath);
  const resolvedCandidate = path.resolve(candidatePath);
  const relative = path.relative(resolvedBase, resolvedCandidate);

  if (relative.startsWith('..') || path.isAbsolute(relative)) {
    throw new Error(`Path '${candidatePath}' is outside the allowed base directory.`);
  }

  return resolvedCandidate;
}