/**
 * Graph writer — batch Cypher INSERT operations for LadybugDB.
 *
 * Translates in-memory symbol structures into CREATE/MATCH statements.
 * Uses multi-node CREATE and UNWIND+batch MATCH for efficient bulk loading.
 */

import type { GraphStore } from './graph-store';

export interface GraphNodeBatch {
  label: string;
  id: string;
  properties: Record<string, string | number | boolean>;
}

export interface GraphRelationshipBatch {
  sourceLabel: string;
  sourceId: string;
  targetLabel: string;
  targetId: string;
  type: string;
  confidence: number;
  reason?: string;
}

/** Escape a string for safe inclusion in a Cypher literal. */
const escapeCypher = (value: string): string => {
  return value.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
};

/** Format a single property value for Cypher. */
const formatCypherValue = (value: string | number | boolean): string => {
  if (typeof value === 'string') return `'${escapeCypher(value)}'`;
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  return String(value);
};

/** Build a property map fragment like `{id: 'x', name: 'y'}` */
const buildPropertyMap = (props: Record<string, string | number | boolean>): string => {
  const entries = Object.entries(props).map(([k, v]) => `${k}: ${formatCypherValue(v)}`);
  return `{${entries.join(', ')}}`;
};

/** Delete all relationships, then all nodes. Idempotent. */
export const clearGraphData = async (store: GraphStore): Promise<void> => {
  try {
    await store.query("MATCH ()-[r:CodeRelation]->() DELETE r");
  } catch {
    // No relationships to delete
  }
  try {
    await store.query("MATCH (n) DELETE n");
  } catch {
    // No nodes to delete
  }
};

const DEFAULT_NODE_BATCH_SIZE = 50;

/**
 * Batch-insert nodes using multi-node CREATE statements.
 * Nodes are grouped by label; each batch contains up to `batchSize` nodes
 * of the same label.
 */
export const batchCreateNodes = async (
  store: GraphStore,
  nodes: GraphNodeBatch[],
  batchSize = DEFAULT_NODE_BATCH_SIZE,
): Promise<void> => {
  // Group by label
  const byLabel = new Map<string, GraphNodeBatch[]>();
  for (const node of nodes) {
    const list = byLabel.get(node.label) ?? [];
    list.push(node);
    byLabel.set(node.label, list);
  }

  for (const [label, list] of byLabel) {
    for (let i = 0; i < list.length; i += batchSize) {
      const chunk = list.slice(i, i + batchSize);
      const parts = chunk.map((n, idx) => `(n${idx}:${label} ${buildPropertyMap({ id: n.id, ...n.properties })})`);
      const cypher = `CREATE ${parts.join(', ')}`;
      await store.query(cypher);
    }
  }
};

const DEFAULT_REL_BATCH_SIZE = 25;

/**
 * Batch-insert relationships using UNWIND + MATCH per (sourceLabel, targetLabel) group.
 *
 * Kuzu requires the MATCH patterns to know which node tables to scan.
 * We group relationships by their endpoint labels so each query only
 * touches the necessary tables.
 */
export const batchCreateRelationships = async (
  store: GraphStore,
  relationships: GraphRelationshipBatch[],
  batchSize = DEFAULT_REL_BATCH_SIZE,
): Promise<void> => {
  // Group by composite key: sourceLabel + targetLabel
  const groupKey = (r: GraphRelationshipBatch) => `${r.sourceLabel}\t${r.targetLabel}`;
  const byEndpoints = new Map<string, GraphRelationshipBatch[]>();
  for (const rel of relationships) {
    const key = groupKey(rel);
    const list = byEndpoints.get(key) ?? [];
    list.push(rel);
    byEndpoints.set(key, list);
  }

  for (const [key, list] of byEndpoints) {
    const [sourceLabel, targetLabel] = key.split('\t');
    for (let i = 0; i < list.length; i += batchSize) {
      const chunk = list.slice(i, i + batchSize);

      // Build UNWIND list of row objects
      const rows = chunk.map((r) => {
        const reason = r.reason ?? '';
        return `{sid: '${escapeCypher(r.sourceId)}', tid: '${escapeCypher(r.targetId)}', t: '${escapeCypher(r.type)}', c: ${r.confidence}, reason: '${escapeCypher(reason)}'}`;
      });

      const cypher = `
        UNWIND [${rows.join(', ')}] AS row
        MATCH (a:${sourceLabel} {id: row.sid}), (b:${targetLabel} {id: row.tid})
        CREATE (a)-[r:CodeRelation {type: row.t, confidence: row.c, reason: row.reason}]->(b)
      `.trim();

      await store.query(cypher);
    }
  }
};
