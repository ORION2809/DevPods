import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { walkWorkspacePaths, readFileContents, walkWorkspace } from '../../../src/intelligence/ingestion/filesystem-walker';
import { mkdtempSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

describe('filesystem walker', () => {
  let tmpDir: string;

  beforeEach(() => {
    tmpDir = mkdtempSync(join(tmpdir(), 'devpods-walker-test-'));
  });

  afterEach(() => {
    rmSync(tmpDir, { recursive: true, force: true });
  });

  describe('walkWorkspacePaths', () => {
    it('scans all source files', async () => {
      mkdirSync(join(tmpDir, 'src'));
      writeFileSync(join(tmpDir, 'src', 'main.ts'), 'console.log("hello");');
      writeFileSync(join(tmpDir, 'src', 'utils.ts'), 'export const x = 1;');

      const scanned = await walkWorkspacePaths(tmpDir);
      const paths = scanned.map((f) => f.path).sort();
      expect(paths).toEqual(['src/main.ts', 'src/utils.ts']);
    });

    it('ignores node_modules', async () => {
      mkdirSync(join(tmpDir, 'src'));
      writeFileSync(join(tmpDir, 'src', 'main.ts'), 'ok');
      mkdirSync(join(tmpDir, 'node_modules', 'foo'), { recursive: true });
      writeFileSync(join(tmpDir, 'node_modules', 'foo', 'index.js'), 'vendor');

      const scanned = await walkWorkspacePaths(tmpDir);
      expect(scanned.map((f) => f.path)).toEqual(['src/main.ts']);
    });

    it('ignores large files', async () => {
      const originalEnv = process.env.DEVPODS_INTELLIGENCE_MAX_FILE_SIZE;
      process.env.DEVPODS_INTELLIGENCE_MAX_FILE_SIZE = '1'; // 1 KB

      writeFileSync(join(tmpDir, 'small.ts'), 'x');
      writeFileSync(join(tmpDir, 'large.ts'), 'x'.repeat(2048));

      try {
        const scanned = await walkWorkspacePaths(tmpDir);
        expect(scanned.map((f) => f.path)).toEqual(['small.ts']);
      } finally {
        if (originalEnv === undefined) {
          delete process.env.DEVPODS_INTELLIGENCE_MAX_FILE_SIZE;
        } else {
          process.env.DEVPODS_INTELLIGENCE_MAX_FILE_SIZE = originalEnv;
        }
      }
    });

    it('respects .gitignore', async () => {
      writeFileSync(join(tmpDir, '.gitignore'), '*.log\n');
      writeFileSync(join(tmpDir, 'app.ts'), 'ok');
      writeFileSync(join(tmpDir, 'debug.log'), 'noise');

      const scanned = await walkWorkspacePaths(tmpDir);
      expect(scanned.map((f) => f.path)).toEqual(['app.ts']);
    });
  });

  describe('readFileContents', () => {
    it('reads files into a map', async () => {
      mkdirSync(join(tmpDir, 'src'));
      writeFileSync(join(tmpDir, 'src', 'a.ts'), 'alpha');
      writeFileSync(join(tmpDir, 'src', 'b.ts'), 'beta');

      const contents = await readFileContents(tmpDir, ['src/a.ts', 'src/b.ts']);
      expect(contents.get('src/a.ts')).toBe('alpha');
      expect(contents.get('src/b.ts')).toBe('beta');
    });

    it('silently skips missing files', async () => {
      const contents = await readFileContents(tmpDir, ['missing.ts']);
      expect(contents.has('missing.ts')).toBe(false);
    });
  });

  describe('walkWorkspace', () => {
    it('returns path + content for all source files', async () => {
      mkdirSync(join(tmpDir, 'src'));
      writeFileSync(join(tmpDir, 'src', 'main.ts'), 'export {}');

      const entries = await walkWorkspace(tmpDir);
      expect(entries).toHaveLength(1);
      expect(entries[0].path).toBe('src/main.ts');
      expect(entries[0].content).toBe('export {}');
    });
  });
});
