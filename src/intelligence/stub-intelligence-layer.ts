import type {
  IntelligenceLayer,
  CodeQueryResult,
  SymbolContextResult,
  ImpactResult,
  DetectChangesResult,
  RouteMapResult,
  ToolMapResult,
} from './intelligence-layer-contract';
import type { IntelligenceIndexState } from '../protocol/schemas';

export class StubIntelligenceLayer implements IntelligenceLayer {
  readonly kind = 'stub' as const;

  private readonly indexStates = new Map<string, IntelligenceIndexState>();

  constructor(defaultState: IntelligenceIndexState = 'not_indexed') {
    this.indexStates.set('default', defaultState);
  }

  setIndexState(workspaceId: string, state: IntelligenceIndexState): void {
    this.indexStates.set(workspaceId, state);
  }

  async getIndexState(workspaceId: string): Promise<IntelligenceIndexState> {
    return this.indexStates.get(workspaceId) ?? this.indexStates.get('default') ?? 'not_indexed';
  }

  async query(query: string, _workspaceId: string): Promise<CodeQueryResult> {
    return {
      symbol: query,
      answer: `Pairing starts in the bridge page, then Android verifies and stores the relay token.`,
      files: ['src/bridge/server.ts', 'src/bridge/request-builder.ts'],
      lineReferences: [{ file: 'src/bridge/server.ts', line: 70 }],
      confidence: 'high',
    };
  }

  async context(symbol: string, _workspaceId: string): Promise<SymbolContextResult> {
    return {
      symbol,
      definition: `Function that resolves ${symbol} from the workspace registry.`,
      callers: ['handleEvent', 'dispatch'],
      callees: ['resolveWorkspace', 'loadWorkspaceRegistry'],
      affectedFlows: ['pairing-flow', 'event-dispatch'],
      files: [`src/bridge/${symbol}.ts`],
      confidence: 'medium',
    };
  }

  async impact(symbol: string, _workspaceId: string): Promise<ImpactResult> {
    return {
      symbol,
      directCallers: 12,
      affectedModules: ['approval', 'relay', 'session-store'],
      affectedFlows: ['pairing-flow', 'approval-flow'],
      testSuggestions: ['bridge-capability.test.ts', 'event-router.test.ts'],
      riskLevel: 'medium',
      safeToContinue: true,
    };
  }

  async detectChanges(_workspaceId: string): Promise<DetectChangesResult> {
    return {
      changedAreas: 5,
      affectedFlows: 2,
      riskiestArea: 'Pairing fallback in bridge server',
      safeToContinue: true,
      files: ['src/bridge/server.ts', 'src/bridge/event-router.ts'],
    };
  }

  async routeMap(route: string, _workspaceId: string): Promise<RouteMapResult> {
    return {
      route,
      consumers: ['src/bridge/event-router.ts'],
      middleware: ['auth'],
      handlers: [`handle${route.replace(/\//g, '_')}`],
    };
  }

  async toolMap(tool: string, _workspaceId: string): Promise<ToolMapResult> {
    return {
      tool,
      callSites: ['src/agent/openclaw-agent-runtime.ts'],
      implementations: [`src/adapters/${tool}.ts`],
    };
  }
}
