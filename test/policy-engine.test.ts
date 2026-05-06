import { describe, expect, it } from 'vitest';
import { buildRiskPolicy, evaluateIntentPolicy } from '../src/policy/engine';
import type { WorkspaceConfig } from '../src/protocol/schemas';

const workspace: WorkspaceConfig = {
  id: 'current_repo',
  label: 'firmware_earphones',
  rootPath: 'C:/repo',
  allowedIntents: ['quick_status', 'summarize_diff', 'latest_ci_failure', 'run_tests'],
  approvalRequiredIntents: ['run_tests'],
  hardApprovalIntents: ['deploy'],
  commands: {},
};

describe('policy engine', () => {
  it('builds a consistent risk policy payload', () => {
    const policy = buildRiskPolicy(workspace);
    expect(policy.requireApprovalFor).toContain('run_tests');
    expect(policy.requireHardApprovalFor).toContain('deploy');
  });

  it('allows quick status immediately', () => {
    const decision = evaluateIntentPolicy('quick_status', workspace);
    expect(decision.allowed).toBe(true);
    expect(decision.riskClass).toBe('immediate');
  });

  it('requires approval for tests', () => {
    const decision = evaluateIntentPolicy('run_tests', workspace);
    expect(decision.allowed).toBe(true);
    expect(decision.riskClass).toBe('approval_required');
  });

  it('denies intents outside the allowlist', () => {
    const decision = evaluateIntentPolicy('commit_staged', workspace);
    expect(decision.allowed).toBe(false);
    expect(decision.riskClass).toBe('denied');
  });

  it('does not let hard approval bypass the allowlist', () => {
    const decision = evaluateIntentPolicy('delete', {
      ...workspace,
      hardApprovalIntents: ['delete'],
    });

    expect(decision.allowed).toBe(false);
    expect(decision.riskClass).toBe('denied');
  });

  it('does not let approval-required intents bypass the allowlist', () => {
    const decision = evaluateIntentPolicy('delete', {
      ...workspace,
      approvalRequiredIntents: ['delete'],
    });

    expect(decision.allowed).toBe(false);
    expect(decision.riskClass).toBe('denied');
  });

  it('classifies allowed hard-approval intents without changing the allowlist gate', () => {
    const decision = evaluateIntentPolicy('deploy', {
      ...workspace,
      allowedIntents: [...workspace.allowedIntents, 'deploy'],
    });

    expect(decision.allowed).toBe(true);
    expect(decision.riskClass).toBe('hard_approval');
  });
});