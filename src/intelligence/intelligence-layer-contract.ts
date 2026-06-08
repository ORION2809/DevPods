import type { IntelligenceIndexState } from '../protocol/schemas';

export interface CodeQueryResult {
  symbol: string;
  answer: string;
  files: readonly string[];
  lineReferences: readonly { file: string; line: number }[];
  confidence: 'high' | 'medium' | 'low';
}

export interface SymbolContextResult {
  symbol: string;
  definition: string;
  callers: readonly string[];
  callees: readonly string[];
  affectedFlows: readonly string[];
  files: readonly string[];
  confidence: 'high' | 'medium' | 'low';
}

export interface ImpactResult {
  symbol: string;
  directCallers: number;
  affectedModules: readonly string[];
  affectedFlows: readonly string[];
  testSuggestions: readonly string[];
  riskLevel: 'low' | 'medium' | 'high';
  safeToContinue: boolean;
}

export interface DetectChangesResult {
  changedAreas: number;
  affectedFlows: number;
  riskiestArea: string;
  safeToContinue: boolean;
  files: readonly string[];
}

export interface RouteMapResult {
  route: string;
  consumers: readonly string[];
  middleware: readonly string[];
  handlers: readonly string[];
}

export interface ToolMapResult {
  tool: string;
  callSites: readonly string[];
  implementations: readonly string[];
}

export interface VoiceContext {
  spokenWordBudget: number;
  workspaceId: string;
  intentName: string;
}

export interface JarvisResponseDraft {
  speak: string;
  display: string;
  followUpHint: string | null;
}

/**
 * Read-only perception layer for codebase understanding.
 *
 * The Intelligence Layer never executes commands, approves actions,
 * writes files, or bypasses policy. It only answers questions about
 * code structure, impact, and change detection.
 */
export interface IntelligenceLayer {
  readonly kind: 'stub' | 'gitnexus' | 'custom';

  /** Returns the current index state for a workspace. */
  getIndexState(workspaceId: string): Promise<IntelligenceIndexState>;

  /** Answers a natural-language or symbol query about the codebase. */
  query(query: string, workspaceId: string): Promise<CodeQueryResult>;

  /** Retrieves structural context for a symbol (callers, callees, flows). */
  context(symbol: string, workspaceId: string): Promise<SymbolContextResult>;

  /** Analyses blast radius for a proposed change to a symbol. */
  impact(symbol: string, workspaceId: string): Promise<ImpactResult>;

  /** Detects recent code changes and their structural impact. */
  detectChanges(workspaceId: string): Promise<DetectChangesResult>;

  /** Maps a route to its consumers, middleware, and handlers. */
  routeMap(route: string, workspaceId: string): Promise<RouteMapResult>;

  /** Maps a tool/agent capability to its call sites and implementations. */
  toolMap(tool: string, workspaceId: string): Promise<ToolMapResult>;
}
