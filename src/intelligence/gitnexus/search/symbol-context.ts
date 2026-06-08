/**
 * Symbol context — definition lookup and file-neighbour discovery.
 *
 * Provides the structural context for a symbol: where it is defined,
 * what file it lives in, and which other symbols share that file
 * (proxy for "affected flows" until call-edge extraction lands).
 */

import type { GraphStore } from '../graph/graph-store';

export interface SymbolDefinition {
  id: string;
  label: string;
  name: string;
  filePath: string;
  startLine: number;
  endLine: number;
  isExported: boolean;
}

export interface SymbolContext {
  definition: SymbolDefinition | null;
  /** Other symbols in the same file (definition neighbours). */
  neighbours: SymbolDefinition[];
  /** File path where the symbol is defined. */
  filePath: string | null;
}

const CODE_TABLES = ['Function', 'Class', 'Interface', 'Method', 'Property', 'CodeElement'];
const lit = (s: string): string => s.replace(/'/g, "\\'");

/** Look up a symbol by exact name match across all code tables. */
export const findSymbolDefinition = async (
  store: GraphStore,
  symbolName: string,
): Promise<SymbolDefinition | null> => {
  const q = lit(symbolName);
  const queries = CODE_TABLES.map(
    (t) => `
      MATCH (n:${t})
      WHERE n.name = '${q}'
      RETURN n.id AS id, '${t}' AS label, n.name AS name,
             n.filePath AS filePath, n.startLine AS startLine,
             n.endLine AS endLine, n.isExported AS isExported
    `,
  );
  const cypher = queries.join('\nUNION ALL\n');
  const rows = (await store.query(cypher)) as Array<{
    id: string;
    label: string;
    name: string;
    filePath: string;
    startLine: number;
    endLine: number;
    isExported: boolean;
  }>;

  if (rows.length === 0) return null;

  // Prefer exported symbols, then shortest name (exact > qualified)
  rows.sort((a, b) => {
    const aExp = a.isExported ? 1 : 0;
    const bExp = b.isExported ? 1 : 0;
    if (bExp !== aExp) return bExp - aExp;
    return a.name.length - b.name.length;
  });

  const r = rows[0];
  return {
    id: r.id,
    label: r.label,
    name: r.name,
    filePath: r.filePath,
    startLine: r.startLine,
    endLine: r.endLine,
    isExported: r.isExported ?? false,
  };
};

/** Find all other symbols that live in the same file. */
export const findNeighboursInFile = async (
  store: GraphStore,
  filePath: string,
): Promise<SymbolDefinition[]> => {
  const q = lit(filePath);
  const queries = CODE_TABLES.map(
    (t) => `
      MATCH (n:${t})
      WHERE n.filePath = '${q}'
      RETURN n.id AS id, '${t}' AS label, n.name AS name,
             n.filePath AS filePath, n.startLine AS startLine,
             n.endLine AS endLine, n.isExported AS isExported
      ORDER BY n.startLine
    `,
  );
  const cypher = queries.join('\nUNION ALL\n');
  const rows = (await store.query(cypher)) as Array<{
    id: string;
    label: string;
    name: string;
    filePath: string;
    startLine: number;
    endLine: number;
    isExported: boolean;
  }>;

  return rows.map((r) => ({
    id: r.id,
    label: r.label,
    name: r.name,
    filePath: r.filePath,
    startLine: r.startLine,
    endLine: r.endLine,
    isExported: r.isExported ?? false,
  }));
};

/** Full context for a symbol name. */
export const getSymbolContext = async (
  store: GraphStore,
  symbolName: string,
): Promise<SymbolContext> => {
  const definition = await findSymbolDefinition(store, symbolName);
  if (!definition) {
    return { definition: null, neighbours: [], filePath: null };
  }
  const neighbours = await findNeighboursInFile(store, definition.filePath);
  return { definition, neighbours, filePath: definition.filePath };
};
