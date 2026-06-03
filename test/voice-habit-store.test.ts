import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { VoiceHabitStore } from '../src/personalization/voice-habit-store';

function tempFile(): string {
  return path.join(os.tmpdir(), `voice-habit-test-${Date.now()}-${Math.random().toString(36).slice(2)}.json`);
}

describe('VoiceHabitStore', () => {
  let store: VoiceHabitStore;
  let filePath: string;

  beforeEach(() => {
    filePath = tempFile();
    store = new VoiceHabitStore(filePath);
  });

  afterEach(() => {
    try {
      fs.unlinkSync(filePath);
    } catch {
      // ignore
    }
  });

  it('returns null for unknown phrase', () => {
    expect(store.resolve('unknown phrase')).toEqual({ intent: null, confidence: 0, promoted: false });
  });

  it('records and resolves a learned phrase', () => {
    store.set('what did I break', 'summarize_diff');
    const result = store.resolve('what did I break');
    expect(result.intent).toBe('summarize_diff');
    expect(result.confidence).toBe(0.5);
    expect(result.promoted).toBe(false);
  });

  it('promotes after threshold confirmations', () => {
    store.confirm('what did I break', 'summarize_diff');
    expect(store.resolve('what did I break').promoted).toBe(false);

    store.confirm('what did I break', 'summarize_diff');
    const result = store.resolve('what did I break');
    expect(result.promoted).toBe(true);
    expect(result.confidence).toBe(1.0);
  });

  it('normalizes phrase before lookup', () => {
    store.set('  WHAT did I BREAK  ', 'summarize_diff');
    expect(store.resolve('what did i break').intent).toBe('summarize_diff');
  });

  it('lists phrases sorted by recency', async () => {
    store.set('phrase a', 'quick_status');
    await new Promise((r) => setTimeout(r, 10));
    store.set('phrase b', 'run_tests');
    await new Promise((r) => setTimeout(r, 10));
    store.confirm('phrase b', 'run_tests');

    const list = store.list();
    expect(list).toHaveLength(2);
    expect(list[0].phrase).toBe('phrase b'); // more recent
  });

  it('deletes a phrase', () => {
    store.set('delete me', 'quick_status');
    expect(store.delete('delete me')).toBe(true);
    expect(store.resolve('delete me').intent).toBeNull();
    expect(store.delete('missing')).toBe(false);
  });

  it('persists and restores across instances', () => {
    store.set('persisted phrase', 'summarize_diff', 2);
    store.flush();

    const store2 = new VoiceHabitStore(filePath);
    const result = store2.resolve('persisted phrase');
    expect(result.intent).toBe('summarize_diff');
    expect(result.promoted).toBe(true);
  });

  it('get returns entry or null', () => {
    store.set('get me', 'quick_status');
    expect(store.get('get me')?.phrase).toBe('get me');
    expect(store.get('missing')).toBeNull();
  });

  it('confirm increments count and updates lastConfirmedAtMs', () => {
    const before = Date.now();
    const entry = store.confirm('test', 'quick_status');
    expect(entry.confirmationCount).toBe(1);
    expect(entry.lastConfirmedAtMs).toBeGreaterThanOrEqual(before);
  });
});
