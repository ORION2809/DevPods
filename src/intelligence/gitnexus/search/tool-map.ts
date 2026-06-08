/**
 * Tool map — find tool definitions and their call sites.
 *
 * Phase 3c: Heuristic search over code symbols (Tool nodes may not
 * be populated yet). Searches for functions/classes whose names match
 * the tool, plus any Tool nodes in the graph.
 *
 * Phase 3d+: Queries Tool nodes directly when populated by ingestion.
 */

import type { GraphStore } from '../graph/graph-store';
import type { ToolMapResult } from '../../intelligence-layer-contract';

const lit = (s: string): string => s.replace(/'/g, "\\'");

interface ToolNode {
  id: string;
  name: string;
  filePath: string;
  description: string;
}

interface SymbolMatch {
  name: string;
  filePath: string;
  label: string;
  startLine: number;
}

/** Query Tool nodes directly (populated in Phase 3d+). */
const queryToolNodes = async (
  store: GraphStore,
  toolPattern: string,
): Promise<ToolNode[]> => {
  try {
    const q = lit(toolPattern);
    const rows = (await store.query(`
      MATCH (n:Tool)
      WHERE n.name CONTAINS '${q}'
      RETURN n.id AS id, n.name AS name, n.filePath AS filePath,
             n.description AS description
    `)) as Array<{
      id: string;
      name: string;
      filePath: string;
      description: string;
    }>;
    return rows.map((r) => ({
      id: r.id,
      name: r.name,
      filePath: r.filePath,
      description: r.description ?? '',
    }));
  } catch {
    return [];
  }
};

/** Heuristic: search code symbols that likely implement the tool. */
const findImplementations = async (
  store: GraphStore,
  toolPattern: string,
): Promise<SymbolMatch[]> => {
  const q = lit(toolPattern);
  const qLower = lit(toolPattern.toLowerCase());

  const codeTables = ['Function', 'Method', 'Class'];
  const queries = codeTables.map(
    (t) => `
      MATCH (n:${t})
      WHERE lower(n.name) CONTAINS '${qLower}'
         OR n.filePath CONTAINS '${q}'
      RETURN n.name AS name, n.filePath AS filePath,
             '${t}' AS label, n.startLine AS startLine
      LIMIT 20
    `,
  );

  const cypher = queries.join('\nUNION ALL\n');
  const rows = (await store.query(cypher)) as Array<{
    name: string;
    filePath: string;
    label: string;
    startLine: number;
  }>;

  return rows.map((r) => ({
    name: r.name,
    filePath: r.filePath,
    label: r.label,
    startLine: r.startLine,
  }));
};

/** Heuristic: find files that reference the tool name (call sites). */
const findCallSites = async (
  store: GraphStore,
  toolPattern: string,
): Promise<string[]> => {
  const q = lit(toolPattern);
  try {
    const rows = (await store.query(`
      MATCH (n:File)
      WHERE n.filePath CONTAINS '${q}'
      RETURN n.filePath AS filePath
      LIMIT 10
    `)) as Array<{ filePath: string }>;
    return rows.map((r) => r.filePath);
  } catch {
    return [];
  }
};

/**
 * Map a tool to its call sites and implementations.
 *
 * First tries Tool nodes (Phase 3d+), then falls back to
 * heuristic symbol search over the codebase.
 */
export const getToolMap = async (
  store: GraphStore,
  tool: string,
): Promise<ToolMapResult> => {
  const toolNodes = await queryToolNodes(store, tool);

  if (toolNodes.length > 0) {
    const t = toolNodes[0];
    return {
      tool: t.name,
      callSites: [t.filePath],
      implementations: [t.filePath],
    };
  }

  // Heuristic fallback
  const implementations = await findImplementations(store, tool);
  const callSites = await findCallSites(store, tool);

  if (implementations.length === 0) {
    return {
      tool,
      callSites: [],
      implementations: [],
    };
  }

  return {
    tool,
    callSites: callSites.slice(0, 5),
    implementations: implementations.map((i) => `${i.label} ${i.name} in ${i.filePath}:${i.startLine}`).slice(0, 5),
  };
};
