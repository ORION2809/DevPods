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
import path from 'node:path';

export interface GitNexusIntelligenceLayerOptions {
  /** Base directory for all intelligence indexes. Default: runtime-data/intelligence */
  indexBasePath?: string;
}

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

  private getStore(workspaceId: string): GraphStore {
    let store = this.stores.get(workspaceId);
    if (!store) {
      const dbPath = path.join(this.indexBasePath, workspaceId, 'codegraph.db');
      store = new GraphStore(dbPath);
      this.stores.set(workspaceId, store);
    }
    return store;
  }

  async getIndexState(workspaceId: string): Promise<IntelligenceIndexState> {
    const store = this.getStore(workspaceId);
    const exists = await store.exists();
    if (!exists) {
      return 'not_indexed';
    }
    try {
      await store.init();
      // Phase 2: check staleness via git metadata
      return 'ready';
    } catch {
      return 'failed';
    }
  }

  async query(query: string, workspaceId: string): Promise<CodeQueryResult> {
    const store = this.getStore(workspaceId);
    await store.init();
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
    const store = this.getStore(workspaceId);
    await store.init();
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
    const store = this.getStore(workspaceId);
    await store.init();
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
    const store = this.getStore(workspaceId);
    await store.init();
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
    const store = this.getStore(workspaceId);
    await store.init();
    // Phase 3: implement route extraction query
    return {
      route,
      consumers: [],
      middleware: [],
      handlers: [],
    };
  }

  async toolMap(tool: string, workspaceId: string): Promise<ToolMapResult> {
    const store = this.getStore(workspaceId);
    await store.init();
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
