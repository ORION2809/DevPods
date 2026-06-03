import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { NudgePolicyStore } from '../src/personalization/nudge-policy-store';

function tempFile(): string {
  return path.join(os.tmpdir(), `nudge-policy-test-${Date.now()}-${Math.random().toString(36).slice(2)}.json`);
}

describe('NudgePolicyStore', () => {
  let store: NudgePolicyStore;
  let filePath: string;

  beforeEach(() => {
    filePath = tempFile();
    store = new NudgePolicyStore(filePath);
  });

  afterEach(() => {
    try {
      fs.unlinkSync(filePath);
    } catch {
      // ignore
    }
  });

  it('returns default policy for unknown session', () => {
    const policy = store.getPolicy('new-session');
    expect(policy.sessionId).toBe('new-session');
    expect(policy.enabled).toBe(true);
    expect(policy.mutedTypes).toEqual([]);
    expect(policy.thresholds).toEqual([]);
  });

  it('stores and retrieves custom policy', () => {
    const saved = store.setPolicy({
      sessionId: 'session-1',
      enabled: false,
      mutedTypes: ['ci_red'],
      thresholds: [
        { type: 'uncommitted_files', changedFilesMin: 5, staleBranchHours: 12, consecutiveTestFailures: 3, ciRedHours: 2 },
      ],
      updatedAtMs: Date.now(),
    });
    expect(saved.enabled).toBe(false);
    expect(saved.mutedTypes).toEqual(['ci_red']);
    expect(saved.thresholds.length).toBe(1);

    const retrieved = store.getPolicy('session-1');
    expect(retrieved.enabled).toBe(false);
    expect(retrieved.mutedTypes).toEqual(['ci_red']);
  });

  it('resets policy to defaults', () => {
    store.setPolicy({
      sessionId: 'session-1',
      enabled: false,
      mutedTypes: ['ci_red'],
      thresholds: [],
      updatedAtMs: Date.now(),
    });
    const reset = store.resetPolicy('session-1');
    expect(reset.enabled).toBe(true);
    expect(reset.mutedTypes).toEqual([]);
  });

  it('persists across instances', async () => {
    store.setPolicy({
      sessionId: 'session-1',
      enabled: false,
      mutedTypes: ['uncommitted_files'],
      thresholds: [],
      updatedAtMs: Date.now(),
    });

    // Wait for async save
    await new Promise((resolve) => setTimeout(resolve, 600));

    const store2 = new NudgePolicyStore(filePath);
    const policy = store2.getPolicy('session-1');
    expect(policy.enabled).toBe(false);
    expect(policy.mutedTypes).toEqual(['uncommitted_files']);
  });
});
