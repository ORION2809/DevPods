/**
 * Detect changes — git-diff based structural impact analysis.
 *
 * Maps changed files (from git diff) to indexed symbols, then
 * estimates blast radius using the same heuristics as impact().
 */

import { execFileSync } from 'node:child_process';
import path from 'node:path';
import type { GraphStore } from '../graph/graph-store';
import type { DetectChangesResult } from '../../intelligence-layer-contract';
import { loadWorkspaceMeta } from '../../ingestion/workspace-meta';
import { getSymbolImpact } from './symbol-impact';

/** Run git diff and return changed file paths. */
const getChangedFiles = (workspacePath: string): string[] => {
  try {
    const output = execFileSync('git', ['diff', '--name-only'], {
      cwd: workspacePath,
      encoding: 'utf-8',
      windowsHide: true,
      maxBuffer: 256 * 1024 * 1024,
    });
    return output
      .split('\n')
      .map((l) => l.trim())
      .filter(Boolean);
  } catch {
    return [];
  }
};

/** Get staged files too. */
const getStagedFiles = (workspacePath: string): string[] => {
  try {
    const output = execFileSync('git', ['diff', '--staged', '--name-only'], {
      cwd: workspacePath,
      encoding: 'utf-8',
      windowsHide: true,
      maxBuffer: 256 * 1024 * 1024,
    });
    return output
      .split('\n')
      .map((l) => l.trim())
      .filter(Boolean);
  } catch {
    return [];
  }
};

const CODE_TABLES = ['Function', 'Class', 'Interface', 'Method', 'Property', 'CodeElement'];
const lit = (s: string): string => s.replace(/'/g, "\\'");

/**
 * Find symbols that live in any of the given file paths.
 * Returns a map from file path to list of symbol names.
 */
const findSymbolsInFiles = async (
  store: GraphStore,
  filePaths: string[],
): Promise<Map<string, string[]>> => {
  if (filePaths.length === 0) return new Map();

  // Build OR conditions for file paths
  const conditions = filePaths.map((fp) => `n.filePath = '${lit(fp)}'`);
  const queries = CODE_TABLES.map(
    (t) => `
      MATCH (n:${t})
      WHERE ${conditions.join(' OR ')}
      RETURN n.filePath AS filePath, n.name AS name
    `,
  );

  const cypher = queries.join('\nUNION ALL\n');
  const rows = (await store.query(cypher)) as Array<{ filePath: string; name: string }>;

  const result = new Map<string, string[]>();
  for (const r of rows) {
    const list = result.get(r.filePath) ?? [];
    list.push(r.name);
    result.set(r.filePath, list);
  }
  return result;
};

/**
 * Detect recent changes and their structural impact.
 *
 * 1. Loads workspace path from meta
 * 2. Runs git diff --name-only (unstaged + staged)
 * 3. Queries graph for symbols in changed files
 * 4. Assesses risk via impact heuristics
 */
export const detectChanges = async (
  store: GraphStore,
  indexDir: string,
): Promise<DetectChangesResult> => {
  const meta = await loadWorkspaceMeta(indexDir);
  if (!meta?.workspacePath) {
    return {
      changedAreas: 0,
      affectedFlows: 0,
      riskiestArea: '',
      safeToContinue: true,
      files: [],
    };
  }

  const unstaged = getChangedFiles(meta.workspacePath);
  const staged = getStagedFiles(meta.workspacePath);
  const allChanged = [...new Set([...unstaged, ...staged])];

  if (allChanged.length === 0) {
    return {
      changedAreas: 0,
      affectedFlows: 0,
      riskiestArea: '',
      safeToContinue: true,
      files: [],
    };
  }

  // Resolve to workspace-relative paths stored in the graph
  const workspacePath = meta.workspacePath;
  const normalizedChanged = allChanged.map((fp) => {
    const abs = path.resolve(workspacePath, fp);
    return path.relative(workspacePath, abs).replace(/\\/g, '/');
  });

  const fileToSymbols = await findSymbolsInFiles(store, normalizedChanged);

  // Assess risk for each changed symbol
  let highestRisk: 'low' | 'medium' | 'high' = 'low';
  let riskiestSymbol = '';
  const riskOrder = { low: 0, medium: 1, high: 2 };

  for (const [filePath, symbols] of fileToSymbols) {
    for (const sym of symbols) {
      const impact = await getSymbolImpact(store, sym);
      if (impact && riskOrder[impact.riskLevel] > riskOrder[highestRisk]) {
        highestRisk = impact.riskLevel;
        riskiestSymbol = sym;
      }
    }
  }

  // Group files by top-level directory for "changed areas"
  const areas = new Set<string>();
  for (const fp of normalizedChanged) {
    const firstPart = fp.split('/')[0];
    if (firstPart) areas.add(firstPart);
  }

  const totalSymbols = [...fileToSymbols.values()].reduce((sum, arr) => sum + arr.length, 0);

  return {
    changedAreas: areas.size,
    affectedFlows: totalSymbols,
    riskiestArea: riskiestSymbol || normalizedChanged[0] || '',
    safeToContinue: highestRisk !== 'high',
    files: normalizedChanged,
  };
};
