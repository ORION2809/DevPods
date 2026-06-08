import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import {
  computeFileHash,
  computeFileHashes,
  diffFileHashes,
} from '../../../src/intelligence/ingestion/file-hash';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

describe('file hash', () => {
  let tmpDir: string;

  beforeEach(() => {
    tmpDir = mkdtempSync(join(tmpdir(), 'devpods-hash-test-'));
  });

  afterEach(() => {
    rmSync(tmpDir, { recursive: true, force: true });
  });

  it('computes SHA-256 of a file', async () => {
    writeFileSync(join(tmpDir, 'a.ts'), 'hello');
    const hash = await computeFileHash(join(tmpDir, 'a.ts'));
    expect(hash).toBe('2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824');
  });

  it('returns null for missing files', async () => {
    const hash = await computeFileHash(join(tmpDir, 'missing.ts'));
    expect(hash).toBeNull();
  });

  it('computes hashes for many files', async () => {
    writeFileSync(join(tmpDir, 'a.ts'), 'alpha');
    writeFileSync(join(tmpDir, 'b.ts'), 'beta');

    const hashes = await computeFileHashes(tmpDir, ['a.ts', 'b.ts']);
    expect(hashes.size).toBe(2);
    expect(hashes.has('a.ts')).toBe(true);
    expect(hashes.has('b.ts')).toBe(true);
  });

  it('diffs current against stored hashes', () => {
    const current = new Map([
      ['a.ts', 'hashA'],
      ['b.ts', 'hashB'],
      ['c.ts', 'hashC'],
    ]);
    const stored = {
      'a.ts': 'hashA', // unchanged
      'b.ts': 'oldB', // changed
      'd.ts': 'hashD', // deleted
    };

    const diff = diffFileHashes(current, stored);
    expect(diff.changed).toEqual(['b.ts']);
    expect(diff.added).toEqual(['c.ts']);
    expect(diff.deleted).toEqual(['d.ts']);
    expect(diff.toWrite).toEqual(['b.ts', 'c.ts']);
  });

  it('handles empty stored hashes', () => {
    const current = new Map([['a.ts', 'hashA']]);
    const diff = diffFileHashes(current, undefined);
    expect(diff.added).toEqual(['a.ts']);
    expect(diff.toWrite).toEqual(['a.ts']);
  });
});
