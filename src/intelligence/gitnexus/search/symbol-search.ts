/**
 * Symbol search — multi-strategy name matching across all code tables.
 *
 * Strategies (in order of descending confidence):
 *   1. Exact case-sensitive match
 *   2. Exact case-insensitive match (lower())
 *   3. Case-insensitive prefix match (STARTS WITH)
 *   4. Case-insensitive substring match (CONTAINS)
 *   5. File-path substring match
 *
 * Results are deduplicated by node id and ranked by confidence.
 */

import type { GraphStore } from '../graph/graph-store';

export interface SymbolMatch {
  id: string;
  label: string;
  name: string;
  filePath: string;
  startLine: number;
  endLine: number;
  isExported: boolean;
  confidence: number;
}

const CODE_TABLES = ['Function', 'Class', 'Interface', 'Method', 'Property', 'CodeElement'];

/** Cypher-safe string literal. */
const lit = (s: string): string => s.replace(/'/g, "\\'");

/** Build a search query for a single table and match predicate. */
const buildSearchQuery = (table: string, predicate: string): string => {
  return `
    MATCH (n:${table})
    WHERE ${predicate}
    RETURN n.id AS id, '${table}' AS label, n.name AS name,
           n.filePath AS filePath, n.startLine AS startLine,
           n.endLine AS endLine, n.isExported AS isExported
  `;
};

/** Execute a search query and return raw rows. */
const runSearch = async (store: GraphStore, predicate: string): Promise<SymbolMatch[]> => {
  const queries = CODE_TABLES.map((t) => buildSearchQuery(t, predicate));
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
    confidence: 0, // filled by caller
  }));
};

/**
 * Search for symbols matching `query`.
 *
 * @returns Ranked, deduplicated matches. Empty array if no matches.
 */
export const searchSymbols = async (
  store: GraphStore,
  query: string,
): Promise<SymbolMatch[]> => {
  const q = lit(query);
  const qLower = lit(query.toLowerCase());
  const seen = new Set<string>();
  const out: SymbolMatch[] = [];

  const add = (matches: SymbolMatch[], confidence: number): void => {
    for (const m of matches) {
      if (seen.has(m.id)) continue;
      seen.add(m.id);
      out.push({ ...m, confidence });
    }
  };

  // 1. Exact case-sensitive
  add(await runSearch(store, `n.name = '${q}'`), 1.0);

  // 2. Exact case-insensitive
  add(await runSearch(store, `lower(n.name) = '${qLower}'`), 0.95);

  // 3. Prefix (case-insensitive)
  add(await runSearch(store, `lower(n.name) STARTS WITH '${qLower}'`), 0.8);

  // 4. Substring (case-insensitive)
  add(await runSearch(store, `lower(n.name) CONTAINS '${qLower}'`), 0.5);

  // 5. File path match
  const fileMatches = (await store.query(`
    MATCH (n:File)
    WHERE lower(n.filePath) CONTAINS '${qLower}'
    RETURN n.filePath AS filePath, n.name AS name
  `)) as Array<{ filePath: string; name: string }>;
  for (const f of fileMatches) {
    if (seen.has(f.filePath)) continue;
    seen.add(f.filePath);
    out.push({
      id: f.filePath,
      label: 'File',
      name: f.name,
      filePath: f.filePath,
      startLine: 0,
      endLine: 0,
      isExported: false,
      confidence: 0.3,
    });
  }

  return out;
};
