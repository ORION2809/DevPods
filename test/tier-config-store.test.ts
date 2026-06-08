import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { TierConfigStore, resolveEffectiveTier } from '../src/personalization/tier-config-store';

function tempFile(): string {
  return path.join(os.tmpdir(), `tier-config-test-${Date.now()}-${Math.random().toString(36).slice(2)}.json`);
}

describe('TierConfigStore', () => {
  let filePath: string;

  beforeEach(() => {
    filePath = tempFile();
  });

  afterEach(() => {
    try { fs.unlinkSync(filePath); } catch { /* ignore */ }
  });

  it('defaults to core when no config file exists', () => {
    const store = new TierConfigStore(filePath);
    expect(store.getTier()).toBe('core');
  });

  it('persists tier changes to disk', () => {
    const store = new TierConfigStore(filePath);
    store.setTier('agent');

    const secondStore = new TierConfigStore(filePath);
    expect(secondStore.getTier()).toBe('agent');
  });

  it('supports upgrade from core to agent to intelligence', () => {
    const store = new TierConfigStore(filePath);
    store.setTier('core');
    expect(store.getTier()).toBe('core');

    store.setTier('agent');
    expect(store.getTier()).toBe('agent');

    store.setTier('intelligence');
    expect(store.getTier()).toBe('intelligence');
  });

  it('supports downgrade from intelligence to core', () => {
    const store = new TierConfigStore(filePath);
    store.setTier('intelligence');
    store.setTier('core');
    expect(store.getTier()).toBe('core');
  });

  it('stores and retrieves workspace intelligence consent', () => {
    const store = new TierConfigStore(filePath);
    store.setIntelligenceConsent('test_workspace', { consented: true, consentedAtMs: 123456789 });

    const consent = store.getIntelligenceConsent('test_workspace');
    expect(consent).toEqual({ consented: true, consentedAtMs: 123456789 });
  });

  it('returns null for unknown workspace consent', () => {
    const store = new TierConfigStore(filePath);
    expect(store.getIntelligenceConsent('unknown')).toBeNull();
  });

  it('removes workspace intelligence consent', () => {
    const store = new TierConfigStore(filePath);
    store.setIntelligenceConsent('ws1', { consented: true, consentedAtMs: 1 });
    store.removeIntelligenceConsent('ws1');
    expect(store.getIntelligenceConsent('ws1')).toBeNull();
  });

  it('returns full config snapshot', () => {
    const store = new TierConfigStore(filePath);
    const config = store.getConfig();
    expect(config.tier).toBe('core');
    expect(config.intelligenceConsent).toEqual({});
    expect(typeof config.updatedAtMs).toBe('number');
  });

  it('updates updatedAtMs on each mutation', () => {
    const store = new TierConfigStore(filePath);
    const before = store.getConfig().updatedAtMs;
    store.setTier('agent');
    const after = store.getConfig().updatedAtMs;
    expect(after).toBeGreaterThanOrEqual(before);
  });

  it('recovers from corrupted config file', () => {
    fs.writeFileSync(filePath, 'not valid json', 'utf8');
    const store = new TierConfigStore(filePath);
    expect(store.getTier()).toBe('core');
  });
});

describe('resolveEffectiveTier', () => {
  it('prefers CLI flag over everything else', () => {
    expect(resolveEffectiveTier('intelligence', 'agent', {})).toBe('intelligence');
  });

  it('falls back to config file tier when no CLI flag', () => {
    expect(resolveEffectiveTier(undefined, 'agent', {})).toBe('agent');
  });

  it('falls back to env variable when no CLI or config', () => {
    expect(resolveEffectiveTier(undefined, undefined, { DEVPODS_TIER: 'intelligence' })).toBe('intelligence');
  });

  it('defaults to core when nothing is set', () => {
    expect(resolveEffectiveTier(undefined, undefined, {})).toBe('core');
  });

  it('throws on invalid CLI tier values', () => {
    expect(() => resolveEffectiveTier('invalid', 'agent', {})).toThrow(
      'Invalid tier "invalid". Expected "core", "agent", or "intelligence".',
    );
  });

  it('falls back when CLI value is undefined', () => {
    expect(resolveEffectiveTier(undefined, 'agent', {})).toBe('agent');
  });

  it('ignores invalid config values and falls back to env', () => {
    expect(resolveEffectiveTier(undefined, 'invalid' as any, { DEVPODS_TIER: 'intelligence' })).toBe('intelligence');
  });
});
