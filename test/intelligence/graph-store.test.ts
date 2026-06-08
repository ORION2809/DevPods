import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { GraphStore } from '../../src/intelligence/gitnexus/graph/graph-store';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

describe('graph store', () => {
  let tmpDir: string;

  beforeEach(() => {
    tmpDir = mkdtempSync(join(tmpdir(), 'devpods-graph-test-'));
  });

  afterEach(() => {
    rmSync(tmpDir, { recursive: true, force: true });
  });

  it('initialises and creates schema', async () => {
    const dbPath = join(tmpDir, 'test.db');
    const store = new GraphStore(dbPath);

    await store.init();
    await store.createSchema();

    // Verify we can query the schema
    const tables = await store.query("CALL show_tables() RETURN *");
    expect(tables.length).toBeGreaterThan(0);

    await store.close();
  });

  it('is idempotent on repeated schema creation', async () => {
    const dbPath = join(tmpDir, 'test.db');
    const store = new GraphStore(dbPath);

    await store.init();
    await store.createSchema();
    // Second call should not throw
    await store.createSchema();

    const tables = await store.query("CALL show_tables() RETURN *");
    expect(tables.length).toBeGreaterThan(0);

    await store.close();
  });

  it('reports exists correctly', async () => {
    const dbPath = join(tmpDir, 'test.db');
    const store = new GraphStore(dbPath);

    expect(await store.exists()).toBe(false);
    await store.init();
    await store.createSchema();
    expect(await store.exists()).toBe(true);

    await store.close();
  });

  it('inserts and queries nodes', async () => {
    const dbPath = join(tmpDir, 'test.db');
    const store = new GraphStore(dbPath);

    await store.init();
    await store.createSchema();

    // Insert a test File node
    await store.query(`CREATE (f:File {id: 'f1', name: 'test.ts', filePath: '/test.ts'})`);

    const rows = await store.query(`MATCH (f:File) WHERE f.id = 'f1' RETURN f.name AS name`);

    expect(rows.length).toBe(1);
    await store.close();
  });
});
