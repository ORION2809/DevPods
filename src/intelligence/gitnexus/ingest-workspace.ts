/**
 * Workspace ingestion orchestrator.
 *
 * Ties together the Phase-2 building blocks into a single pipeline:
 *   walk → hash-diff → parse → graph-write → save-meta → write-manifest
 *
 * Rebuild strategy (Phase-3a MVP): full rebuild on any change.
 *   - Fast enough for small-to-medium workspaces.
 *   - Avoids Kuzu incremental-delete complexity.
 *   - Future optimization: file-level incremental updates.
 */

import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import type { GraphStore } from './graph/graph-store';
import {
  clearGraphData,
  batchCreateNodes,
  batchCreateRelationships,
  type GraphNodeBatch,
  type GraphRelationshipBatch,
} from './graph/graph-writer';
import { walkWorkspacePaths, readFileContents } from '../ingestion/filesystem-walker';
import { computeFileHashes, diffFileHashes } from '../ingestion/file-hash';
import { loadWorkspaceMeta, saveWorkspaceMeta, type WorkspaceIndexMeta } from '../ingestion/workspace-meta';
import { parseSourceFile, type ParsedSymbol } from '../ingestion/parser';
import type { GitNexusIntelligenceLayer } from './gitnexus-intelligence-layer';

export interface IngestWorkspaceOptions {
  /** Called with progress messages and percent (0–100). */
  onProgress?: (message: string, percent: number) => void;
}

const emit = (opts: IngestWorkspaceOptions | undefined, message: string, percent: number): void => {
  opts?.onProgress?.(message, percent);
};

/** Generate a deterministic node ID from label + qualifier. */
const makeId = (label: string, qualifier: string): string => {
  return createHash('sha256').update(`${label}:${qualifier}`).digest('hex').slice(0, 16);
};

/** Map parser labels to graph node table names. */
const LABEL_TO_TABLE: Record<string, string> = {
  Class: 'Class',
  Interface: 'Interface',
  Function: 'Function',
  Method: 'Method',
  Property: 'Property',
  Const: 'CodeElement',
  Variable: 'CodeElement',
};

/** Build File/Folder nodes and CONTAINS relationships from scanned paths. */
const buildStructure = (
  filePaths: string[],
): { nodes: GraphNodeBatch[]; relationships: GraphRelationshipBatch[] } => {
  const nodes: GraphNodeBatch[] = [];
  const relationships: GraphRelationshipBatch[] = [];
  const folderIds = new Map<string, string>();
  const seenRels = new Set<string>();

  const addRel = (rel: GraphRelationshipBatch): void => {
    const key = `${rel.sourceId}\t${rel.targetId}\t${rel.type}`;
    if (seenRels.has(key)) return;
    seenRels.add(key);
    relationships.push(rel);
  };

  const getFolderId = (folderPath: string): string => {
    let id = folderIds.get(folderPath);
    if (!id) {
      id = makeId('Folder', folderPath);
      folderIds.set(folderPath, id);
      nodes.push({
        label: 'Folder',
        id,
        properties: {
          name: path.basename(folderPath) || folderPath,
          filePath: folderPath,
        },
      });
    }
    return id;
  };

  for (const filePath of filePaths) {
    const fileId = makeId('File', filePath);
    nodes.push({
      label: 'File',
      id: fileId,
      properties: {
        name: path.basename(filePath),
        filePath,
        content: '',
      },
    });

    // Parent folder chain
    const dir = path.dirname(filePath);
    if (dir !== '.') {
      const parts = dir.split('/');
      let currentPath = '';
      let parentId = '';
      for (const part of parts) {
        currentPath = currentPath ? `${currentPath}/${part}` : part;
        const folderId = getFolderId(currentPath);
        if (parentId) {
          addRel({
            sourceLabel: 'Folder',
            sourceId: parentId,
            targetLabel: 'Folder',
            targetId: folderId,
            type: 'CONTAINS',
            confidence: 1.0,
            reason: '',
          });
        }
        parentId = folderId;
      }
      // Final folder → file
      if (parentId) {
        addRel({
          sourceLabel: 'Folder',
          sourceId: parentId,
          targetLabel: 'File',
          targetId: fileId,
          type: 'CONTAINS',
          confidence: 1.0,
          reason: '',
        });
      }
    }
  }

  return { nodes, relationships };
};

/** Parse files and build symbol nodes + DEFINES relationships. */
const buildSymbols = (
  filePaths: string[],
  contents: Map<string, string>,
): { nodes: GraphNodeBatch[]; relationships: GraphRelationshipBatch[] } => {
  const nodes: GraphNodeBatch[] = [];
  const relationships: GraphRelationshipBatch[] = [];

  for (const filePath of filePaths) {
    const content = contents.get(filePath);
    if (!content) continue;

    const parseResult = parseSourceFile(filePath, content);
    if (!parseResult || parseResult.symbols.length === 0) continue;

    const fileId = makeId('File', filePath);

    for (const sym of parseResult.symbols) {
      const table = LABEL_TO_TABLE[sym.label] ?? 'CodeElement';
      const props: Record<string, string | number | boolean> = {
        name: sym.name,
        filePath: sym.filePath,
        startLine: sym.startLine,
        endLine: sym.endLine,
      };

      if (table !== 'Property') {
        props.isExported = sym.isExported;
      }

      nodes.push({
        label: table,
        id: sym.id,
        properties: props,
      });

      relationships.push({
        sourceLabel: 'File',
        sourceId: fileId,
        targetLabel: table,
        targetId: sym.id,
        type: 'DEFINES',
        confidence: 1.0,
        reason: '',
      });
    }
  }

  return { nodes, relationships };
};

/**
 * Index a workspace into its graph store.
 *
 * @param workspacePath — absolute path to the workspace root
 * @param workspaceId   — workspace identifier (used for meta + manifest)
 * @param layer         — intelligence layer that owns the GraphStore
 * @param options       — optional progress callback
 */
export const ingestWorkspace = async (
  workspacePath: string,
  workspaceId: string,
  layer: GitNexusIntelligenceLayer,
  options?: IngestWorkspaceOptions,
): Promise<void> => {
  const startMs = Date.now();
  emit(options, 'Scanning workspace...', 5);

  // ── 1. Walk workspace ────────────────────────────────────────────────────
  const scannedFiles = await walkWorkspacePaths(workspacePath);
  const filePaths = scannedFiles.map((f) => f.path);
  emit(options, `Found ${filePaths.length} files`, 10);

  // ── 2. Compute hashes and diff ───────────────────────────────────────────
  const currentHashes = await computeFileHashes(workspacePath, filePaths);

  // Resolve index directory via layer
  const { dir: indexDir } = layer.resolveWorkspaceDbPath(workspaceId);
  const storedMeta = await loadWorkspaceMeta(indexDir);

  const diff = diffFileHashes(currentHashes, storedMeta?.fileHashes);
  const manifest = await layer.readManifest(workspaceId);

  if (diff.changed.length === 0 && diff.added.length === 0 && diff.deleted.length === 0 && manifest) {
    emit(options, 'Workspace unchanged — skipping indexing', 100);
    return;
  }

  // ── 3. Read file contents ────────────────────────────────────────────────
  emit(options, 'Reading file contents...', 15);
  const contents = await readFileContents(workspacePath, filePaths);

  // ── 4. Get store and rebuild ─────────────────────────────────────────────
  const store: GraphStore = layer.getStore(workspaceId);
  await store.init();

  emit(options, 'Creating schema...', 22);
  await store.createSchema();

  // Remove manifest BEFORE clearing so a crash mid-run leaves the workspace
  // as `not_indexed` rather than `ready` with an empty graph.
  const { manifestPath } = layer.resolveWorkspaceDbPath(workspaceId);
  try { await fs.unlink(manifestPath); } catch { /* manifest may not exist */ }

  emit(options, 'Clearing previous index...', 20);
  await clearGraphData(store);

  // ── 5. Build and write structure ─────────────────────────────────────────
  emit(options, 'Writing file and folder nodes...', 30);
  const structure = buildStructure(filePaths);
  await batchCreateNodes(store, structure.nodes);
  await batchCreateRelationships(store, structure.relationships);

  // ── 6. Parse and write symbols ───────────────────────────────────────────
  emit(options, 'Parsing source files...', 50);
  const symbols = buildSymbols(filePaths, contents);
  emit(options, `Writing ${symbols.nodes.length} symbols...`, 70);
  await batchCreateNodes(store, symbols.nodes);
  await batchCreateRelationships(store, symbols.relationships);

  // ── 7. Save metadata ─────────────────────────────────────────────────────
  emit(options, 'Saving index metadata...', 90);
  const meta: WorkspaceIndexMeta = {
    workspaceId,
    workspacePath,
    indexedAt: new Date().toISOString(),
    fileCount: filePaths.length,
    schemaVersion: 1,
    fileHashes: Object.fromEntries(currentHashes),
    stats: {
      nodes: structure.nodes.length + symbols.nodes.length,
      edges: structure.relationships.length + symbols.relationships.length,
      durationMs: Date.now() - startMs,
    },
  };
  await saveWorkspaceMeta(indexDir, meta);

  // ── 8. Write manifest ────────────────────────────────────────────────────
  await layer.writeManifest(workspaceId, filePaths.length);
  emit(options, 'Indexing complete', 100);
};
