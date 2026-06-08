import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import {
  shouldIgnorePath,
  isHardcodedIgnoredDirectory,
  loadIgnoreRules,
  createIgnoreFilter,
} from '../../../src/intelligence/ingestion/ignore-service';
import { mkdtempSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

describe('ignore service', () => {
  describe('shouldIgnorePath', () => {
    it('ignores node_modules', () => {
      expect(shouldIgnorePath('node_modules/foo/bar.ts')).toBe(true);
    });

    it('ignores .git directories', () => {
      expect(shouldIgnorePath('.git/config')).toBe(true);
    });

    it('ignores dist and build outputs', () => {
      expect(shouldIgnorePath('dist/index.js')).toBe(true);
      expect(shouldIgnorePath('build/main.css')).toBe(true);
    });

    it('ignores common binary extensions', () => {
      expect(shouldIgnorePath('assets/logo.png')).toBe(true);
      expect(shouldIgnorePath('bin/app.exe')).toBe(true);
    });

    it('ignores lock files', () => {
      expect(shouldIgnorePath('package-lock.json')).toBe(true);
      expect(shouldIgnorePath('yarn.lock')).toBe(true);
    });

    it('ignores generated files', () => {
      expect(shouldIgnorePath('lib/types.d.ts')).toBe(true);
      expect(shouldIgnorePath('bundle.min.js')).toBe(true);
    });

    it('does not ignore source files', () => {
      expect(shouldIgnorePath('src/main.ts')).toBe(false);
      expect(shouldIgnorePath('lib/index.js')).toBe(false);
    });

    it('handles Windows backslashes', () => {
      expect(shouldIgnorePath('node_modules\\foo\\bar.ts')).toBe(true);
      expect(shouldIgnorePath('src\\main.ts')).toBe(false);
    });
  });

  describe('isHardcodedIgnoredDirectory', () => {
    it('returns true for ignored directory names', () => {
      expect(isHardcodedIgnoredDirectory('node_modules')).toBe(true);
      expect(isHardcodedIgnoredDirectory('.git')).toBe(true);
      expect(isHardcodedIgnoredDirectory('dist')).toBe(true);
    });

    it('returns false for source directories', () => {
      expect(isHardcodedIgnoredDirectory('src')).toBe(false);
      expect(isHardcodedIgnoredDirectory('lib')).toBe(false);
    });
  });

  describe('loadIgnoreRules', () => {
    let tmpDir: string;

    beforeEach(() => {
      tmpDir = mkdtempSync(join(tmpdir(), 'devpods-ignore-test-'));
    });

    afterEach(() => {
      rmSync(tmpDir, { recursive: true, force: true });
    });

    it('returns null when no ignore files exist', async () => {
      const ig = await loadIgnoreRules(tmpDir);
      expect(ig).toBeNull();
    });

    it('loads .gitignore rules', async () => {
      writeFileSync(join(tmpDir, '.gitignore'), '*.log\nbuild/\n');
      const ig = await loadIgnoreRules(tmpDir);
      expect(ig).not.toBeNull();
      expect(ig!.ignores('debug.log')).toBe(true);
      expect(ig!.ignores('build/')).toBe(true);
      expect(ig!.ignores('src/main.ts')).toBe(false);
    });

    it('loads .devpodsignore rules', async () => {
      writeFileSync(join(tmpDir, '.devpodsignore'), '*.tmp\n');
      const ig = await loadIgnoreRules(tmpDir);
      expect(ig).not.toBeNull();
      expect(ig!.ignores('cache.tmp')).toBe(true);
    });

    it('.devpodsignore takes precedence over .gitignore', async () => {
      writeFileSync(join(tmpDir, '.gitignore'), '*.log\n');
      writeFileSync(join(tmpDir, '.devpodsignore'), '!important.log\n');
      const ig = await loadIgnoreRules(tmpDir);
      expect(ig!.ignores('debug.log')).toBe(true);
      expect(ig!.ignores('important.log')).toBe(false);
    });

    it('respects noGitignore option', async () => {
      writeFileSync(join(tmpDir, '.gitignore'), '*.log\n');
      const ig = await loadIgnoreRules(tmpDir, { noGitignore: true });
      expect(ig).toBeNull();
    });
  });

  describe('createIgnoreFilter', () => {
    let tmpDir: string;

    beforeEach(() => {
      tmpDir = mkdtempSync(join(tmpdir(), 'devpods-ignore-test-'));
    });

    afterEach(() => {
      rmSync(tmpDir, { recursive: true, force: true });
    });

    it('combines gitignore with hardcoded rules', async () => {
      writeFileSync(join(tmpDir, '.gitignore'), 'secrets/\n');
      const filter = await createIgnoreFilter(tmpDir);

      // gitignore rule
      const secretsPath = { name: 'secrets', relative: () => 'secrets', isDirectory: () => true };
      expect(filter.childrenIgnored(secretsPath)).toBe(true);

      // hardcoded rule
      const nodePath = { name: 'node_modules', relative: () => 'node_modules', isDirectory: () => true };
      expect(filter.childrenIgnored(nodePath)).toBe(true);

      // allowed
      const srcPath = { name: 'src', relative: () => 'src', isDirectory: () => true };
      expect(filter.childrenIgnored(srcPath)).toBe(false);
    });

    it('respects negation patterns', async () => {
      writeFileSync(join(tmpDir, '.devpodsignore'), '!__tests__/\n');
      const filter = await createIgnoreFilter(tmpDir);

      const testsPath = { name: '__tests__', relative: () => '__tests__', isDirectory: () => true };
      // Negation should allow descent
      expect(filter.childrenIgnored(testsPath)).toBe(false);
    });
  });
});
