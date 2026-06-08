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

const READONLY_METHODS = new Set([
  'getIndexState',
  'query',
  'context',
  'impact',
  'detectChanges',
  'routeMap',
  'toolMap',
]);

/**
 * Wraps an IntelligenceLayer to enforce the read-only guarantee at runtime.
 *
 * Any method call not in the allowed read-only set throws a security error.
 * This prevents accidental or malicious write operations from reaching the
 * codebase even if the underlying layer is compromised or extended.
 */
export function createReadOnlyIntelligenceLayer(layer: IntelligenceLayer): IntelligenceLayer {
  return new Proxy(layer, {
    get(target, prop, receiver) {
      if (typeof prop !== 'string') {
        return Reflect.get(target, prop, receiver);
      }
      const value = Reflect.get(target, prop, receiver);
      if (typeof value === 'function' && !READONLY_METHODS.has(prop)) {
        throw new Error(
          `Intelligence layer read-only violation: method "${prop}" is not in the allowed read-only set.`,
        );
      }
      return value;
    },
  });
}

/**
 * A read-only stub that throws on any write-like operation name.
 * Use this as the default when no real intelligence layer is configured.
 */
export class ReadOnlyIntelligenceStub implements IntelligenceLayer {
  readonly kind = 'stub' as const;

  async getIndexState(_workspaceId: string): Promise<IntelligenceIndexState> {
    return 'not_installed';
  }

  async query(_query: string, _workspaceId: string): Promise<CodeQueryResult> {
    return {
      symbol: '',
      answer: 'Intelligence layer is not installed.',
      files: [],
      lineReferences: [],
      confidence: 'low',
    };
  }

  async context(_symbol: string, _workspaceId: string): Promise<SymbolContextResult> {
    return {
      symbol: '',
      definition: '',
      callers: [],
      callees: [],
      affectedFlows: [],
      files: [],
      confidence: 'low',
    };
  }

  async impact(_symbol: string, _workspaceId: string): Promise<ImpactResult> {
    return {
      symbol: '',
      directCallers: 0,
      affectedModules: [],
      affectedFlows: [],
      testSuggestions: [],
      riskLevel: 'low',
      safeToContinue: true,
    };
  }

  async detectChanges(_workspaceId: string): Promise<DetectChangesResult> {
    return {
      changedAreas: 0,
      affectedFlows: 0,
      riskiestArea: '',
      safeToContinue: true,
      files: [],
    };
  }

  async routeMap(_route: string, _workspaceId: string): Promise<RouteMapResult> {
    return { route: '', consumers: [], middleware: [], handlers: [] };
  }

  async toolMap(_tool: string, _workspaceId: string): Promise<ToolMapResult> {
    return { tool: '', callSites: [], implementations: [] };
  }
}
