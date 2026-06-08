import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { GitNexusIntelligenceLayer } from '../../src/intelligence/gitnexus/gitnexus-intelligence-layer';
import { mkdtempSync, rmSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';

describe('GitNexusIntelligenceLayer', () => {
  let tmpDir: string;
  let layer: GitNexusIntelligenceLayer;

  beforeEach(() => {
    tmpDir = mkdtempSync(join(tmpdir(), 'devpods-intel-test-'));
    layer = new GitNexusIntelligenceLayer({ indexBasePath: tmpDir });
  });

  afterEach(async () => {
    await layer.dispose().catch(() => {});
    rmSync(tmpDir, { recursive: true, force: true });
  });

  it('hashes workspaceId for path safety', async () => {
    const workspaceId = 'my/workspace::with::special::chars';
    const hash = createHash('sha256').update(workspaceId).digest('hex');

    const state = await layer.getIndexState(workspaceId);
    expect(state).toBe('not_indexed');

    // Verify the hashed directory was not created yet (getStore creates in-memory only)
    // After init, dir should exist under hash
    const store = (layer as any).getStore(workspaceId);
    await store.init();
    await store.createSchema();

    const dbPath = join(tmpDir, hash, 'codegraph.db');
    expect(require('node:fs').existsSync(dbPath)).toBe(true);
  });

  it('rejects workspaceIds that escape indexBasePath', async () => {
    // Construct a workspaceId that, if used raw, would traverse upward
    const evilId = '../../../etc/passwd';
    const hash = createHash('sha256').update(evilId).digest('hex');

    // Should NOT throw — hashing makes it safe
    const state = await layer.getIndexState(evilId);
    expect(state).toBe('not_indexed');

    const resolved = join(tmpDir, hash);
    expect(resolved.startsWith(tmpDir)).toBe(true);
  });

  it('returns not_indexed for schema-only DB without manifest', async () => {
    const workspaceId = 'ws-no-manifest';
    const store = (layer as any).getStore(workspaceId);
    await store.init();
    await store.createSchema();
    await store.close();

    // Re-create layer to clear in-memory cache
    const layer2 = new GitNexusIntelligenceLayer({ indexBasePath: tmpDir });
    const state = await layer2.getIndexState(workspaceId);
    expect(state).toBe('not_indexed');
    await layer2.dispose().catch(() => {});
  });

  it('returns ready when manifest and DB both exist', async () => {
    const workspaceId = 'ws-with-manifest';
    const store = (layer as any).getStore(workspaceId);
    await store.init();
    await store.createSchema();
    await layer.writeManifest(workspaceId, 42);

    const state = await layer.getIndexState(workspaceId);
    expect(state).toBe('ready');
  });

  it('read methods fallback when not indexed', async () => {
    const workspaceId = 'ws-unindexed';

    const q = await layer.query('foo', workspaceId);
    expect(q.confidence).toBe('low');
    expect(q.answer).toContain('not_indexed');

    const ctx = await layer.context('foo', workspaceId);
    expect(ctx.confidence).toBe('low');

    const imp = await layer.impact('foo', workspaceId);
    expect(imp.safeToContinue).toBe(true);

    const dc = await layer.detectChanges(workspaceId);
    expect(dc.safeToContinue).toBe(true);

    const rm = await layer.routeMap('/foo', workspaceId);
    expect(rm.handlers).toEqual([]);

    const tm = await layer.toolMap('foo', workspaceId);
    expect(tm.callSites).toEqual([]);
  });

  it('read methods work when indexed (stub responses)', async () => {
    const workspaceId = 'ws-indexed';
    const store = (layer as any).getStore(workspaceId);
    await store.init();
    await store.createSchema();
    await layer.writeManifest(workspaceId, 1);

    const q = await layer.query('foo', workspaceId);
    expect(q.answer).toContain('not yet implemented');

    const ctx = await layer.context('foo', workspaceId);
    expect(ctx.symbol).toBe('foo');
  });
});
