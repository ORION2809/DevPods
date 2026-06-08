import type {
  JarvisResponseDraft,
  VoiceContext,
  CodeQueryResult,
  SymbolContextResult,
  ImpactResult,
  DetectChangesResult,
} from './intelligence-layer-contract';
import type {
  AgentAcknowledgement,
  AgentPlanConfirmation,
  AgentProgressEvent,
  AgentCompletionReport,
} from '../agent/agent-runtime-contract';

const DEFAULT_BUDGET = 24;

function countWords(text: string): number {
  return text.trim().split(/\s+/).filter((w) => w.length > 0).length;
}

function trimToBudget(text: string, budget: number): string {
  const words = text.trim().split(/\s+/).filter((w) => w.length > 0);
  if (words.length <= budget) return text.trim();
  return words.slice(0, budget).join(' ') + '.';
}

/**
 * Formats agent conversation moments and intelligence results
 * into voice-safe spoken responses within the 24-word budget.
 *
 * Voice carries the decision. Display carries the detail.
 */
export class CodeIntelligenceVoiceFormatter {
  formatAcknowledgement(input: AgentAcknowledgement, _context: VoiceContext): string {
    const seconds = Math.ceil(input.planningEstimateMs / 1000);
    const text = `Got it. ${input.intentUnderstood.slice(0, 30)}. Plan ready in about ${seconds} seconds.`;
    return trimToBudget(text, 16);
  }

  formatPlanConfirmation(input: AgentPlanConfirmation, _context: VoiceContext): string {
    const riskWord = input.riskClass === 'hard_approval' ? 'High risk' : input.riskClass === 'approval_required' ? 'Medium risk' : 'Low risk';
    const text = `${input.steps.length} steps. ${input.summary.slice(0, 40)}. ${riskWord}. Confirm to proceed.`;
    return trimToBudget(text, DEFAULT_BUDGET);
  }

  formatProgressUpdate(input: AgentProgressEvent, _context: VoiceContext): string {
    if (input.percent == null) {
      return trimToBudget(input.summary, DEFAULT_BUDGET);
    }
    const text = `${input.percent}% complete. ${input.summary}.`;
    return trimToBudget(text, DEFAULT_BUDGET);
  }

  formatCompletionReport(input: AgentCompletionReport, _context: VoiceContext): string {
    let text: string;
    if (input.outcome === 'completed') {
      text = `Done. ${input.summary}`;
    } else if (input.outcome === 'partial') {
      text = `Completed ${input.completedSteps} of ${input.totalSteps} steps. ${input.summary}`;
    } else {
      text = `Stopped. ${input.failureReason ?? input.summary}`;
    }
    return trimToBudget(text, DEFAULT_BUDGET);
  }

  formatBlockingIssue(input: AgentProgressEvent, _context: VoiceContext): string {
    const text = `Stuck. ${input.summary}. Want to hear options?`;
    return trimToBudget(text, DEFAULT_BUDGET);
  }

  formatImpact(result: ImpactResult, _context: VoiceContext): JarvisResponseDraft {
    const text = `${result.riskLevel} impact: ${result.directCallers} callers, mostly ${result.affectedModules.slice(0, 2).join(' and ')}. Review tests first.`;
    return {
      speak: trimToBudget(text, DEFAULT_BUDGET),
      display: `Risk: ${result.riskLevel}\nCallers: ${result.directCallers}\nModules: ${result.affectedModules.join(', ')}\nFlows: ${result.affectedFlows.join(', ')}\nTests: ${result.testSuggestions.join(', ')}`,
      followUpHint: result.safeToContinue ? 'Safe to continue' : 'Review before proceeding',
    };
  }

  formatContext(result: SymbolContextResult, _context: VoiceContext): JarvisResponseDraft {
    const text = `${result.symbol} is used in ${result.callers.length} places, affecting ${result.affectedFlows.length} flows.`;
    return {
      speak: trimToBudget(text, DEFAULT_BUDGET),
      display: `Definition: ${result.definition}\nCallers: ${result.callers.join(', ')}\nCallees: ${result.callees.join(', ')}\nFlows: ${result.affectedFlows.join(', ')}\nFiles: ${result.files.join(', ')}`,
      followUpHint: null,
    };
  }

  formatChanges(result: DetectChangesResult, _context: VoiceContext): JarvisResponseDraft {
    const text = `${result.changedAreas} changes affect ${result.affectedFlows} flows. ${result.riskiestArea} is the riskiest area.`;
    return {
      speak: trimToBudget(text, DEFAULT_BUDGET),
      display: `Changed areas: ${result.changedAreas}\nAffected flows: ${result.affectedFlows}\nRiskiest: ${result.riskiestArea}\nSafe to continue: ${result.safeToContinue ? 'Yes' : 'No'}\nFiles: ${result.files.join(', ')}`,
      followUpHint: result.safeToContinue ? 'Safe to continue' : 'Review before proceeding',
    };
  }

  formatQuery(result: CodeQueryResult, _context: VoiceContext): JarvisResponseDraft {
    return {
      speak: trimToBudget(result.answer, DEFAULT_BUDGET),
      display: `Answer: ${result.answer}\nConfidence: ${result.confidence}\nFiles: ${result.files.join(', ')}\nLines: ${result.lineReferences.map((r) => `${r.file}:${r.line}`).join(', ')}`,
      followUpHint: null,
    };
  }
}
