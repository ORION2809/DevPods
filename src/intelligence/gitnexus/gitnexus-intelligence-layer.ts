/**
 * GitNexus donor-based intelligence engine.
 *
 * Implements the frozen IntelligenceLayer contract using a harvested
 * subset of the GitNexus code-intelligence stack:
 *  - LadybugDB (CodeGraph) for graph storage and Cypher queries
 *  - Tree-sitter ingestion pipeline for workspace indexing
 *  - BM25 search for symbol retrieval
 *
 * Terminology has been adapted to DevPods conventions:
 *  - repo        → workspace
 *  - analyze     → index
 *  - LadybugDB   → CodeGraph (the abstraction)
 *  - LocalBackend → IntelligenceEngine
 */

import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import type {
  IntelligenceLayer,
  CodeQueryResult,
  SymbolContextResult,
  ImpactResult,
  DetectChangesResult,
  RouteMapResult,
  ToolMapResult,
} from '../intelligence-layer-contract';
import type { IntelligenceIndexState } from '../../protocol/schemas';
import { GraphStore } from './graph/graph-store';

export interface GitNexusIntelligenceLayerOptions {
  /** Base directory for all intelligence indexes. Default: runtime-data/intelligence */
  indexBasePath?: string;
}

/** Manifest written after successful ingestion to prove the index is populated. */
interface IndexManifest {
  workspaceId: string;
  completedAt: string;
  fileCount: number;
  version: number;
}

const MANIFEST_FILE = 'index-manifest.json';
const MANIFEST_VERSION = 1;

/**
 * Intelligence engine backed by a harvested GitN subgraph stack.
 *
 * Phase 0 (current): skeleton with GraphStore initialisation.
 * Phase 1: schema creation and basic graph queries.
 * Phase 2: ingestion pipeline integration.
 * Phase 3: search stack + all six IntelligenceLayer methods.
 */
export class GitNexusIntelligenceLayer implements IntelligenceLayer {
  readonly kind = 'gitnexus' as const;

  private readonly stores = new Map<string, GraphStore>();
  private readonly indexBasePath: string;

  constructor(options: GitNexusIntelligenceLayerOptions = {}) {
    this.indexBasePath = options.indexBasePath ?? path.resolve(process.cwd(), 'runtime-data/intelligence');
  }

  // ========================================================================
  // Workspace path safety
  // ========================================================================

  /**
   * Map a workspaceId to a safe, hashed directory name.
   *
   * Raw workspaceIds may contain path-traversal characters, reserved
   * filenames, or overly long strings. We SHA-256 hash them and use
   * the hex digest as the directory name. The resolved path is
   * asserted to stay under indexBasePath.
   */
  private resolveWorkspaceDbPath(workspaceId: string): { dbPath: string; manifestPath: string; dir: string } {
    const hash = createHash('sha256').update(workspaceId).digest('hex');
    const dir = path.join(this.indexBasePath, hash);
    const resolvedDir = path.resolve(dir);
    const resolvedBase = path.resolve(this.indexBasePath);

    if (!resolvedDir.startsWith(resolvedBase + path.sep) && resolvedDir !== resolvedBase) {
      throw new Error(`Workspace index path escapes base: ${resolvedDir}`);
    }

    return {
      dir: resolvedDir,
      dbPath: path.join(resolvedDir, 'codegraph.db'),
      manifestPath: path.join(resolvedDir, MANIFEST_FILE),
    };
  }

  private getStore(workspaceId: string): GraphStore {
    let store = this.stores.get(workspaceId);
    if (!store) {
      const { dbPath } = this.resolveWorkspaceDbPath(workspaceId);
      store = new GraphStore(dbPath);
      this.stores.set(workspaceId, store);
    }
    return store;
  }

  // ========================================================================
  // Index manifest helpers
  // ========================================================================

  private async readManifest(workspaceId: string): Promise<IndexManifest | null> {
    const { manifestPath } = this.resolveWorkspaceDbPath(workspaceId);
    try {
      const raw = await fs.readFile(manifestPath, 'utf-8');
      const parsed = JSON.parse(raw) as IndexManifest;
      if (parsed.workspaceId !== workspaceId || parsed.version !== MANIFEST_VERSION) {
        return null;
      }
      return parsed;
    } catch {
      return null;
    }
  }

  /** Write the manifest after ingestion completes. Exported for ingestion pipeline. */
  async writeManifest(workspaceId: string, fileCount: number): Promise<void> {
    const { manifestPath, dir } = this.resolveWorkspaceDbPath(workspaceId);
    await fs.mkdir(dir, { recursive: true });
    const manifest: IndexManifest = {
      workspaceId,
      completedAt: new Date().toISOString(),
      fileCount,
      version: MANIFEST_VERSION,
    };
    await fs.writeFile(manifestPath, JSON.stringify(manifest, null, 2));
  }

  // ========================================================================
  // IntelligenceLayer implementation
  // ========================================================================

  async getIndexState(workspaceId: string): Promise<IntelligenceIndexState> {
    const store = this.getStore(workspaceId);
    const exists = await store.exists();
    if (!exists) {
      return 'not_indexed';
    }
    const manifest = await this.readManifest(workspaceId);
    if (!manifest) {
      // DB file exists but no manifest → schema-only or partial index
      return 'not_indexed';
    }
    try {
      await store.init();
      // Phase 3: check staleness via git metadata against manifest.completedAt
      return 'ready';
    } catch {
      return 'failed';
    }
  }

  private async ensureIndexed(workspaceId: string): Promise<IntelligenceIndexState> {
    const state = await this.getIndexState(workspaceId);
    if (state !== 'ready') {
      return state;
    }
    const store = this.getStore(workspaceId);
    await store.init();
    return state;
  }

  async query(query: string, workspaceId: string): Promise<CodeQueryResult> {
    const state = await this.ensureIndexed(workspaceId);
    if (state !== 'ready') {
      return {
        symbol: query,
        answer: `Intelligence index is ${state}. Run indexing first.`,
        files: [],
        lineReferences: [],
        confidence: 'low',
      };
    }
    // Phase 3: implement hybrid BM25 + semantic search
    return {
      symbol: query,
      answer: `Intelligence query not yet implemented for "${query}".`,
      files: [],
      lineReferences: [],
      confidence: 'low',
    };
  }

  async context(symbol: string, workspaceId: string): Promise<SymbolContextResult> {
    const state = await this.ensureIndexed(workspaceId);
    if (state !== 'ready') {
      return {
        symbol,
        definition: '',
        callers: [],
        callees: [],
        affectedFlows: [],
        files: [],
        confidence: 'low',
      };
    }
    // Phase 3: implement symbol context lookup
    return {
      symbol,
      definition: '',
      callers: [],
      callees: [],
      affectedFlows: [],
      files: [],
      confidence: 'low',
    };
  }

  async impact(symbol: string, workspaceId: string): Promise<ImpactResult> {
    const state = await this.ensureIndexed(workspaceId);
    if (state !== 'ready') {
      return {
        symbol,
        directCallers: 0,
        affectedModules: [],
        affectedFlows: [],
        testSuggestions: [],
        riskLevel: 'low',
        safeToContinue: true,
      };
    }
    // Phase 3: implement blast-radius graph traversal
    return {
      symbol,
      directCallers: 0,
      affectedModules: [],
      affectedFlows: [],
      testSuggestions: [],
      riskLevel: 'low',
      safeToContinue: true,
    };
  }

  async detectChanges(workspaceId: string): Promise<DetectChangesResult> {
    const state = await this.ensureIndexed(workspaceId);
    if (state !== 'ready') {
      return {
        changedAreas: 0,
        affectedFlows: 0,
        riskiestArea: '',
        safeToContinue: true,
        files: [],
      };
    }
    // Phase 3: implement git-diff → symbol → affected flows
    return {
      changedAreas: 0,
      affectedFlows: 0,
      riskiestArea: '',
      safeToContinue: true,
      files: [],
    };
  }

  async routeMap(route: string, workspaceId: string): Promise<RouteMapResult> {
    const state = await this.ensureIndexed(workspaceId);
    if (state !== 'ready') {
      return {
        route,
        consumers: [],
        middleware: [],
        handlers: [],
      };
    }
    // Phase 3: implement route extraction query
    return {
      route,
      consumers: [],
      middleware: [],
      handlers: [],
    };
  }

  async toolMap(tool: string, workspaceId: string): Promise<ToolMapResult> {
    const state = await this.ensureIndexed(workspaceId);
    if (state !== 'ready') {
      return {
        tool,
        callSites: [],
        implementations: [],
      };
    }
    // Phase 3: implement tool extraction query
    return {
      tool,
      callSites: [],
      implementations: [],
    };
  }

  /**
   * Dispose all open graph stores.
   */
  async dispose(): Promise<void> {
    for (const store of this.stores.values()) {
      await store.close();
    }
    this.stores.clear();
  }
}
