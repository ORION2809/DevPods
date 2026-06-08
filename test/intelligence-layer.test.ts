import { describe, expect, it, beforeEach } from 'vitest';
import { StubIntelligenceLayer } from '../src/intelligence/stub-intelligence-layer';
import { ReadOnlyIntelligenceStub, createReadOnlyIntelligenceLayer } from '../src/intelligence/read-only-guard';
import { CodeIntelligenceVoiceFormatter } from '../src/intelligence/voice-formatter';
import { OpenClawAgentRuntime } from '../src/agent/openclaw-agent-runtime';
import type { AgentRuntimeRequest } from '../src/agent/agent-runtime-contract';
import type { WorkspaceConfig } from '../src/protocol/schemas';

const workspace: WorkspaceConfig = {
  id: 'test_workspace',
  label: 'Test Workspace',
  rootPath: '/tmp/test',
  allowedIntents: ['impact_analysis', 'detect_changes', 'query_symbol', 'explain_flow'],
  approvalRequiredIntents: [],
  hardApprovalIntents: [],
  commands: {},
};

describe('intelligence layer', () => {
  describe('StubIntelligenceLayer', () => {
    it('returns mock query result', async () => {
      const layer = new StubIntelligenceLayer();
      const result = await layer.query('pairing', 'test');
      expect(result.symbol).toBe('pairing');
      expect(result.confidence).toBe('high');
      expect(result.files.length).toBeGreaterThan(0);
    });

    it('returns mock context result', async () => {
      const layer = new StubIntelligenceLayer();
      const result = await layer.context('server', 'test');
      expect(result.symbol).toBe('server');
      expect(result.callers.length).toBeGreaterThan(0);
      expect(result.affectedFlows.length).toBeGreaterThan(0);
    });

    it('returns mock impact result', async () => {
      const layer = new StubIntelligenceLayer();
      const result = await layer.impact('sessionStore', 'test');
      expect(result.symbol).toBe('sessionStore');
      expect(result.riskLevel).toBe('medium');
      expect(result.safeToContinue).toBe(true);
    });

    it('returns mock detectChanges result', async () => {
      const layer = new StubIntelligenceLayer();
      const result = await layer.detectChanges('test');
      expect(result.changedAreas).toBe(5);
      expect(result.safeToContinue).toBe(true);
    });

    it('returns mock routeMap result', async () => {
      const layer = new StubIntelligenceLayer();
      const result = await layer.routeMap('/health', 'test');
      expect(result.route).toBe('/health');
      expect(result.consumers.length).toBeGreaterThan(0);
    });

    it('returns mock toolMap result', async () => {
      const layer = new StubIntelligenceLayer();
      const result = await layer.toolMap('git', 'test');
      expect(result.tool).toBe('git');
      expect(result.implementations.length).toBeGreaterThan(0);
    });

    it('reports index state per workspace', async () => {
      const layer = new StubIntelligenceLayer('not_indexed');
      expect(await layer.getIndexState('test')).toBe('not_indexed');
      layer.setIndexState('test', 'ready');
      expect(await layer.getIndexState('test')).toBe('ready');
    });
  });

  describe('ReadOnlyIntelligenceGuard', () => {
    it('allows read-only methods through', async () => {
      const layer = createReadOnlyIntelligenceLayer(new StubIntelligenceLayer());
      const result = await layer.query('test', 'test');
      expect(result.answer).toBeTruthy();
    });

    it('blocks non-readonly methods via proxy', async () => {
      const stub = new StubIntelligenceLayer();
      const layer = createReadOnlyIntelligenceLayer(stub);
      // @ts-expect-error — testing runtime guard
      expect(() => layer.setIndexState('test', 'ready')).toThrow('read-only violation');
    });
  });

  describe('ReadOnlyIntelligenceStub', () => {
    it('returns safe defaults for all methods', async () => {
      const stub = new ReadOnlyIntelligenceStub();
      expect(await stub.getIndexState('test')).toBe('not_installed');
      const query = await stub.query('test', 'test');
      expect(query.confidence).toBe('low');
      const impact = await stub.impact('test', 'test');
      expect(impact.directCallers).toBe(0);
    });
  });

  describe('CodeIntelligenceVoiceFormatter', () => {
    const formatter = new CodeIntelligenceVoiceFormatter();
    const context = { spokenWordBudget: 24, workspaceId: 'test', intentName: 'impact_analysis' };

    it('formats impact within word budget', () => {
      const result = formatter.formatImpact(
        {
          symbol: 'sessionStore',
          directCallers: 12,
          affectedModules: ['approval', 'relay'],
          affectedFlows: ['pairing-flow'],
          testSuggestions: ['test.ts'],
          riskLevel: 'medium',
          safeToContinue: true,
        },
        context,
      );
      const words = result.speak.trim().split(/\s+/).length;
      expect(words).toBeLessThanOrEqual(24);
      expect(result.display).toContain('Risk:');
    });

    it('formats context within word budget', () => {
      const result = formatter.formatContext(
        {
          symbol: 'sessionStore',
          definition: 'Stores session state',
          callers: ['handleEvent', 'dispatch'],
          callees: ['resolveWorkspace'],
          affectedFlows: ['pairing-flow'],
          files: ['src/bridge/session-store.ts'],
          confidence: 'high',
        },
        context,
      );
      const words = result.speak.trim().split(/\s+/).length;
      expect(words).toBeLessThanOrEqual(24);
    });

    it('formats changes within word budget', () => {
      const result = formatter.formatChanges(
        {
          changedAreas: 5,
          affectedFlows: 2,
          riskiestArea: 'Pairing fallback',
          safeToContinue: true,
          files: ['src/bridge/server.ts'],
        },
        context,
      );
      const words = result.speak.trim().split(/\s+/).length;
      expect(words).toBeLessThanOrEqual(24);
    });

    it('formats query within word budget', () => {
      const result = formatter.formatQuery(
        {
          symbol: 'pairing',
          answer: 'Pairing starts in the bridge page then Android verifies the relay token.',
          files: ['src/bridge/server.ts'],
          lineReferences: [{ file: 'src/bridge/server.ts', line: 70 }],
          confidence: 'high',
        },
        context,
      );
      const words = result.speak.trim().split(/\s+/).length;
      expect(words).toBeLessThanOrEqual(24);
    });

    it('trims acknowledgement to 16 words', () => {
      const ack = formatter.formatAcknowledgement(
        { planId: 'p1', requestId: 'r1', sessionId: 's1', intentUnderstood: 'refactor the approval flow', planningEstimateMs: 8000 },
        context,
      );
      const words = ack.trim().split(/\s+/).length;
      expect(words).toBeLessThanOrEqual(16);
    });

    it('formats plan confirmation within budget', () => {
      const plan = formatter.formatPlanConfirmation(
        {
          planId: 'p1',
          requestId: 'r1',
          sessionId: 's1',
          summary: 'Refactor approval flow',
          spokenSummary: '3 steps. Updates session store. Low risk.',
          confirmationPrompt: 'Confirm?',
          riskClass: 'immediate',
          affectedFiles: [],
          expectedCommands: [],
          steps: [{ id: '1', title: 'Step 1', spokenSummary: null, riskClass: 'immediate', affectedFiles: [], requiresApproval: false }],
          expiresAtMs: Date.now() + 60_000,
        },
        context,
      );
      const words = plan.trim().split(/\s+/).length;
      expect(words).toBeLessThanOrEqual(24);
    });

    it('formats progress with percent', () => {
      const progress = formatter.formatProgressUpdate(
        { id: 'p1', requestId: 'r1', sessionId: 's1', phase: 'running', kind: 'command_started', summary: 'Updating store', speak: null, display: null, planId: null, actionId: null, percent: 50, atMs: Date.now() },
        context,
      );
      expect(progress).toContain('50%');
    });

    it('formats completion report', () => {
      const report = formatter.formatCompletionReport(
        { planId: 'p1', outcome: 'completed', completedSteps: 3, totalSteps: 3, summary: 'Done. All tests pass.', requiresReview: false },
        context,
      );
      expect(report).toContain('Done');
      const words = report.trim().split(/\s+/).length;
      expect(words).toBeLessThanOrEqual(24);
    });
  });

  describe('OpenClawAgentRuntime + intelligence layer', () => {
    it('uses intelligence layer for answer mode when available', async () => {
      const layer = new StubIntelligenceLayer();
      const runtime = new OpenClawAgentRuntime({ intelligenceLayer: layer });

      const request: AgentRuntimeRequest = {
        requestId: 'req_1',
        sessionId: 'session_1',
        workspaceId: 'test_workspace',
        workspace,
        bridgeRequest: {} as AgentRuntimeRequest['bridgeRequest'],
        utterance: 'what is the impact of sessionStore',
        mode: 'answer',
        intentHint: 'impact_analysis',
        approvedActionId: null,
        constraints: {
          spokenWordBudget: 24,
          requiresPlanConfirmation: true,
          allowedIntents: ['impact_analysis'],
          approvalRequiredIntents: [],
          hardApprovalIntents: [],
          intelligenceAvailable: true,
          redactionRequired: true,
        },
      };

      const response = await runtime.handle(request);
      expect(response.status).toBe('done');
      expect(response.response.speak).toContain('impact');
    });

    it('falls back to generic answer when intelligence is unavailable', async () => {
      const runtime = new OpenClawAgentRuntime();

      const request: AgentRuntimeRequest = {
        requestId: 'req_1',
        sessionId: 'session_1',
        workspaceId: 'test_workspace',
        workspace,
        bridgeRequest: {} as AgentRuntimeRequest['bridgeRequest'],
        utterance: 'what is the impact of sessionStore',
        mode: 'answer',
        intentHint: 'impact_analysis',
        approvedActionId: null,
        constraints: {
          spokenWordBudget: 24,
          requiresPlanConfirmation: true,
          allowedIntents: ['impact_analysis'],
          approvalRequiredIntents: [],
          hardApprovalIntents: [],
          intelligenceAvailable: false,
          redactionRequired: true,
        },
      };

      const response = await runtime.handle(request);
      expect(response.status).toBe('done');
      expect(response.response.speak).toContain('Answer:');
    });

    it('falls back to generic answer on intelligence failure', async () => {
      const brokenLayer = {
        kind: 'custom' as const,
        getIndexState: async () => 'ready' as const,
        query: async () => { throw new Error('index corrupt'); },
        context: async () => { throw new Error('index corrupt'); },
        impact: async () => { throw new Error('index corrupt'); },
        detectChanges: async () => { throw new Error('index corrupt'); },
        routeMap: async () => { throw new Error('index corrupt'); },
        toolMap: async () => { throw new Error('index corrupt'); },
      };
      const runtime = new OpenClawAgentRuntime({ intelligenceLayer: brokenLayer });

      const request: AgentRuntimeRequest = {
        requestId: 'req_1',
        sessionId: 'session_1',
        workspaceId: 'test_workspace',
        workspace,
        bridgeRequest: {} as AgentRuntimeRequest['bridgeRequest'],
        utterance: 'what is the impact of sessionStore',
        mode: 'answer',
        intentHint: 'impact_analysis',
        approvedActionId: null,
        constraints: {
          spokenWordBudget: 24,
          requiresPlanConfirmation: true,
          allowedIntents: ['impact_analysis'],
          approvalRequiredIntents: [],
          hardApprovalIntents: [],
          intelligenceAvailable: true,
          redactionRequired: true,
        },
      };

      const response = await runtime.handle(request);
      expect(response.status).toBe('done');
      expect(response.response.speak).toContain('Answer:');
    });

    it('reports intelligence availability in capabilities', async () => {
      const layer = new StubIntelligenceLayer();
      const runtime = new OpenClawAgentRuntime({ intelligenceLayer: layer });
      const caps = await runtime.getCapabilities('test_workspace');
      expect(caps.intelligence.available).toBe(true);
      expect(caps.intelligence.indexState).toBe('not_indexed');
    });

    it('reports intelligence unavailable when no layer', async () => {
      const runtime = new OpenClawAgentRuntime();
      const caps = await runtime.getCapabilities('test_workspace');
      expect(caps.intelligence.available).toBe(false);
      expect(caps.intelligence.indexState).toBe('not_installed');
    });
  });
});
