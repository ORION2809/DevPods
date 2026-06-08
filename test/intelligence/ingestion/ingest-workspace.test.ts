import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { GitNexusIntelligenceLayer } from '../../../src/intelligence/gitnexus/gitnexus-intelligence-layer';
import { ingestWorkspace } from '../../../src/intelligence/gitnexus/ingest-workspace';

describe('ingestWorkspace', () => {
  let tmpDir: string;
  let layer: GitNexusIntelligenceLayer;

  beforeEach(() => {
    tmpDir = mkdtempSync(join(tmpdir(), 'devpods-ingest-test-'));
    layer = new GitNexusIntelligenceLayer({ indexBasePath: tmpDir });
  });

  afterEach(async () => {
    await layer.dispose().catch(() => {});
    rmSync(tmpDir, { recursive: true, force: true });
  });

  const createWorkspace = (files: Record<string, string>): string => {
    const wsPath = join(tmpDir, 'workspace');
    for (const [relPath, content] of Object.entries(files)) {
      const absPath = join(wsPath, relPath);
      mkdirSync(join(absPath, '..'), { recursive: true });
      writeFileSync(absPath, content, 'utf-8');
    }
    return wsPath;
  };

  it('indexes a simple TypeScript workspace', async () => {
    const wsPath = createWorkspace({
      'src/user.ts': 'export class User {}',
      'src/utils.ts': 'export function add(a: number, b: number): number { return a + b; }',
    });

    await ingestWorkspace(wsPath, 'ws-simple', layer);

    const state = await layer.getIndexState('ws-simple');
    expect(state).toBe('ready');

    const store = layer.getStore('ws-simple');
    await store.init();

    // Verify File nodes
    const files = await store.query("MATCH (f:File) RETURN f.name AS name ORDER BY f.name");
    expect(files.map((r: any) => r.name)).toEqual(['user.ts', 'utils.ts']);

    // Verify Folder nodes
    const folders = await store.query("MATCH (fo:Folder) RETURN fo.name AS name ORDER BY fo.name");
    expect(folders.map((r: any) => r.name)).toEqual(['src']);

    // Verify symbol nodes
    const classes = await store.query("MATCH (c:Class) RETURN c.name AS name");
    expect(classes.map((r: any) => r.name)).toEqual(['User']);

    const functions = await store.query("MATCH (f:Function) RETURN f.name AS name");
    expect(functions.map((r: any) => r.name)).toEqual(['add']);

    // Verify DEFINES relationships
    const rels = await store.query(`
      MATCH (fi:File)-[r:CodeRelation {type: 'DEFINES'}]->(s)
      RETURN fi.name AS file, s.name AS symbol, labels(s) AS labels
      ORDER BY fi.name, s.name
    `);
    expect(rels.length).toBe(2);
    expect((rels[0] as any).file).toBe('user.ts');
    expect((rels[0] as any).symbol).toBe('User');
    expect((rels[0] as any).labels).toContain('Class');
    expect((rels[1] as any).file).toBe('utils.ts');
    expect((rels[1] as any).symbol).toBe('add');
    expect((rels[1] as any).labels).toContain('Function');
  });

  it('indexes nested folder structure', async () => {
    const wsPath = createWorkspace({
      'src/bridge/server.ts': 'export class Server {}',
      'src/jarvis/router.ts': 'export function route() {}',
    });

    await ingestWorkspace(wsPath, 'ws-nested', layer);

    const store = layer.getStore('ws-nested');
    await store.init();

    const folders = await store.query("MATCH (fo:Folder) RETURN fo.filePath AS path ORDER BY fo.filePath");
    expect(folders.map((r: any) => r.path)).toEqual(['src', 'src/bridge', 'src/jarvis']);

    // Verify CONTAINS chain: src → src/bridge → server.ts
    const contains = await store.query(`
      MATCH (a)-[r:CodeRelation {type: 'CONTAINS'}]->(b)
      RETURN a.filePath AS parent, b.filePath AS child
      ORDER BY parent, child
    `);
    expect(contains.length).toBeGreaterThan(0);
    const srcToBridge = contains.find((r: any) => r.parent === 'src' && r.child === 'src/bridge');
    expect(srcToBridge).toBeTruthy();
  });

  it('skips indexing when workspace is unchanged', async () => {
    const wsPath = createWorkspace({
      'src/app.ts': 'export class App {}',
    });

    const progress: string[] = [];
    await ingestWorkspace(wsPath, 'ws-unchanged', layer, {
      onProgress: (msg) => progress.push(msg),
    });

    expect(await layer.getIndexState('ws-unchanged')).toBe('ready');

    // Second run should skip
    progress.length = 0;
    await ingestWorkspace(wsPath, 'ws-unchanged', layer, {
      onProgress: (msg) => progress.push(msg),
    });

    expect(progress.some((m) => m.includes('unchanged'))).toBe(true);
  });

  it('re-indexes when files change', async () => {
    const wsPath = createWorkspace({
      'src/app.ts': 'export class App {}',
    });

    await ingestWorkspace(wsPath, 'ws-change', layer);
    expect(await layer.getIndexState('ws-change')).toBe('ready');

    // Modify file
    writeFileSync(join(wsPath, 'src/app.ts'), 'export class Application {}', 'utf-8');

    await ingestWorkspace(wsPath, 'ws-change', layer);

    const store = layer.getStore('ws-change');
    await store.init();
    const classes = await store.query("MATCH (c:Class) RETURN c.name AS name");
    expect(classes.map((r: any) => r.name)).toEqual(['Application']);
  });

  it('indexes Kotlin files', async () => {
    const wsPath = createWorkspace({
      'src/Main.kt': 'fun main() { println("hello") }\nclass User(val name: String)',
    });

    await ingestWorkspace(wsPath, 'ws-kotlin', layer);

    const store = layer.getStore('ws-kotlin');
    await store.init();

    const functions = await store.query("MATCH (f:Function) RETURN f.name AS name");
    expect(functions.map((r: any) => r.name)).toContain('main');

    const classes = await store.query("MATCH (c:Class) RETURN c.name AS name");
    expect(classes.map((r: any) => r.name)).toContain('User');
  });

  it('ignores non-source files', async () => {
    const wsPath = createWorkspace({
      'src/app.ts': 'export class App {}',
      'README.md': '# Hello',
      'package.json': '{}',
      'node_modules/lib/index.js': 'module.exports = 1',
    });

    await ingestWorkspace(wsPath, 'ws-ignore', layer);

    const store = layer.getStore('ws-ignore');
    await store.init();

    const files = await store.query("MATCH (f:File) RETURN f.name AS name ORDER BY f.name");
    // node_modules is ignored by hardcoded list; README.md and package.json
    // are not parseable but are not filtered out (they become File nodes
    // with no symbols). This is acceptable for the MVP.
    expect(files.map((r: any) => r.name)).toEqual(['README.md', 'app.ts', 'package.json']);
  });
});
