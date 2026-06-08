/**
 * Ignore service — ported from GitNexus donor, terminology adapted.
 *
 * Combines three layers of file filtering:
 *  1. User rules (.gitignore / .devpodsignore)
 *  2. Hardcoded directory, extension, and filename lists
 *  3. Generated-file heuristics
 *
 * Precedence: user's negation patterns (!pattern) override hardcoded rules.
 */

import ignore, { type Ignore } from 'ignore';
import fs from 'node:fs/promises';
import nodePath from 'node:path';

// --------------------------------------------------------------------------
// Hardcoded ignore lists — deterministic safety net
// --------------------------------------------------------------------------

const DEFAULT_IGNORE_LIST = new Set([
  // Version Control
  '.git',
  '.svn',
  '.hg',
  '.bzr',

  // IDEs & Editors
  '.idea',
  '.vscode',
  '.vs',
  '.eclipse',
  '.settings',
  '.DS_Store',
  'Thumbs.db',

  // Dependencies
  'node_modules',
  'bower_components',
  'jspm_packages',
  'vendor',
  'third_party',
  '3rdparty',
  'venv',
  '.venv',
  'env',
  '.env',
  '__pycache__',
  '.pytest_cache',
  '.mypy_cache',
  'site-packages',
  '.tox',
  'eggs',
  '.eggs',
  'lib64',
  'parts',
  'sdist',
  'wheels',

  // Build Outputs
  'dist',
  'build',
  'out',
  'output',
  'bin',
  'obj',
  'target',
  '.next',
  '.nuxt',
  '.output',
  '.vercel',
  '.netlify',
  '.serverless',
  '_build',
  'public/build',
  '.parcel-cache',
  '.turbo',
  '.svelte-kit',
  '.gradle',
  '.kotlin',
  'captures',
  'generated',
  'intermediates',
  'jniLibs',
  'merged_native_libs',
  'stripped_native_libs',

  // Test & Coverage
  'coverage',
  '.nyc_output',
  'htmlcov',
  '.coverage',
  '__tests__',
  '__mocks__',
  '.jest',

  // Logs & Temp
  'logs',
  'log',
  'tmp',
  'temp',
  'cache',
  '.cache',
  '.tmp',
  '.temp',

  // Generated/Compiled
  '.generated',
  'generated',
  'auto-generated',
  'monaco-workers',
  '.terraform',

  // Misc
  '.husky',
  '.github',
  '.circleci',
  '.gitlab',
  'fixtures',
  'snapshots',
  '__snapshots__',
]);

const IGNORED_EXTENSIONS = new Set([
  // Images
  '.png',
  '.jpg',
  '.jpeg',
  '.gif',
  '.svg',
  '.ico',
  '.webp',
  '.bmp',
  '.tiff',
  '.tif',
  '.psd',
  '.ai',
  '.sketch',
  '.fig',
  '.xd',

  // Archives
  '.zip',
  '.tar',
  '.gz',
  '.rar',
  '.7z',
  '.bz2',
  '.xz',
  '.tgz',

  // Binary/Compiled
  '.exe',
  '.dll',
  '.so',
  '.dylib',
  '.a',
  '.lib',
  '.o',
  '.obj',
  '.class',
  '.jar',
  '.war',
  '.ear',
  '.pyc',
  '.pyo',
  '.pyd',
  '.beam',
  '.wasm',
  '.node',

  // Documents
  '.pdf',
  '.doc',
  '.docx',
  '.xls',
  '.xlsx',
  '.ppt',
  '.pptx',
  '.odt',
  '.ods',
  '.odp',

  // Media
  '.mp4',
  '.mp3',
  '.wav',
  '.mov',
  '.avi',
  '.mkv',
  '.flv',
  '.wmv',
  '.ogg',
  '.webm',
  '.flac',
  '.aac',
  '.m4a',

  // Fonts
  '.woff',
  '.woff2',
  '.ttf',
  '.eot',
  '.otf',

  // Databases
  '.db',
  '.sqlite',
  '.sqlite3',
  '.mdb',
  '.accdb',

  // Minified/Bundled files
  '.min.js',
  '.min.css',
  '.bundle.js',
  '.chunk.js',

  // Source maps
  '.map',

  // Lock files
  '.lock',

  // Certificates & Keys
  '.pem',
  '.key',
  '.crt',
  '.cer',
  '.p12',
  '.pfx',

  // Data files
  '.csv',
  '.tsv',
  '.parquet',
  '.avro',
  '.feather',
  '.npy',
  '.npz',
  '.pkl',
  '.pickle',
  '.h5',
  '.hdf5',

  // Misc binary
  '.bin',
  '.dat',
  '.data',
  '.raw',
  '.iso',
  '.img',
  '.dmg',
]);

const IGNORED_FILES = new Set([
  'package-lock.json',
  'yarn.lock',
  'pnpm-lock.yaml',
  'composer.lock',
  'Gemfile.lock',
  'poetry.lock',
  'Cargo.lock',
  'go.sum',
  '.gitignore',
  '.gitattributes',
  '.npmrc',
  '.yarnrc',
  '.editorconfig',
  '.prettierrc',
  '.prettierignore',
  '.eslintignore',
  '.dockerignore',
  'Thumbs.db',
  '.DS_Store',
  'LICENSE',
  'LICENSE.md',
  'LICENSE.txt',
  'CHANGELOG.md',
  'CHANGELOG',
  'CONTRIBUTING.md',
  'CODE_OF_CONDUCT.md',
  'SECURITY.md',
  '.env',
  '.env.local',
  '.env.development',
  '.env.production',
  '.env.test',
  '.env.example',
]);

// --------------------------------------------------------------------------
// Pure hardcoded filter (deterministic, no per-repo config)
// --------------------------------------------------------------------------

export const shouldIgnorePath = (filePath: string): boolean => {
  const normalizedPath = filePath.replace(/\\/g, '/');
  const normalizedPathLower = normalizedPath.toLowerCase();
  const parts = normalizedPath.split('/');
  const fileName = parts[parts.length - 1];
  const fileNameLower = fileName.toLowerCase();

  // Laravel compiled Blade templates
  if (/(^|\/)storage\/framework\/views(\/|$)/.test(normalizedPathLower)) {
    return true;
  }

  // Hardcoded directory segments
  for (const part of parts) {
    if (DEFAULT_IGNORE_LIST.has(part)) {
      return true;
    }
  }

  // Exact filename matches
  if (IGNORED_FILES.has(fileName) || IGNORED_FILES.has(fileNameLower)) {
    return true;
  }

  // Extension checks
  const lastDotIndex = fileNameLower.lastIndexOf('.');
  if (lastDotIndex !== -1) {
    const ext = fileNameLower.substring(lastDotIndex);
    if (IGNORED_EXTENSIONS.has(ext)) return true;

    const secondLastDot = fileNameLower.lastIndexOf('.', lastDotIndex - 1);
    if (secondLastDot !== -1) {
      const compoundExt = fileNameLower.substring(secondLastDot);
      if (IGNORED_EXTENSIONS.has(compoundExt)) return true;
    }
  }

  // Generated/bundled heuristics
  if (
    fileNameLower.includes('.bundle.') ||
    fileNameLower.includes('.chunk.') ||
    fileNameLower.includes('.generated.') ||
    fileNameLower.endsWith('.d.ts')
  ) {
    return true;
  }

  return false;
};

export const isHardcodedIgnoredDirectory = (name: string): boolean => {
  return DEFAULT_IGNORE_LIST.has(name);
};

// --------------------------------------------------------------------------
// User-configurable ignore rules (.gitignore / .devpodsignore)
// --------------------------------------------------------------------------

export interface IgnoreOptions {
  /** Skip .gitignore parsing, only read .devpodsignore. */
  noGitignore?: boolean;
}

export const loadIgnoreRules = async (
  workspacePath: string,
  options?: IgnoreOptions,
): Promise<Ignore | null> => {
  const ig = ignore();
  let hasRules = false;

  const skipGitignore = options?.noGitignore ?? !!process.env.DEVPODS_INTELLIGENCE_NO_GITIGNORE;
  const filenames = skipGitignore ? ['.devpodsignore'] : ['.gitignore', '.devpodsignore'];

  for (const filename of filenames) {
    try {
      const content = await fs.readFile(nodePath.join(workspacePath, filename), 'utf-8');
      ig.add(content);
      hasRules = true;
    } catch (err: unknown) {
      const code = (err as NodeJS.ErrnoException).code;
      if (code !== 'ENOENT') {
        // eslint-disable-next-line no-console
        console.warn(`Warning: could not read ${filename}: ${(err as Error).message}`);
      }
    }
  }

  return hasRules ? ig : null;
};

// --------------------------------------------------------------------------
// Negation propagation
// --------------------------------------------------------------------------

const hasExplicitUnignore = (ig: Ignore, rel: string): boolean => {
  if (ig.test(rel).unignored) return true;
  if (ig.test(rel + '/').unignored) return true;
  const parts = rel.split('/');
  for (let i = parts.length - 1; i > 0; i--) {
    const ancestor = parts.slice(0, i).join('/') + '/';
    if (ig.test(ancestor).unignored) return true;
  }
  return false;
};

// --------------------------------------------------------------------------
// Glob-compatible ignore filter
// --------------------------------------------------------------------------

/** Minimal Path shape that glob v11 passes to ignore callbacks. */
interface GlobPath {
  name: string;
  relative(): string;
  isDirectory(): boolean;
}

export const createIgnoreFilter = async (workspacePath: string, options?: IgnoreOptions) => {
  const ig = await loadIgnoreRules(workspacePath, options);

  return {
    ignored(p: GlobPath): boolean {
      const rel = p.relative().replace(/\\/g, '/');
      if (!rel) return false;
      if (ig && hasExplicitUnignore(ig, rel) && !ig.ignores(rel)) return false;
      if (ig && ig.ignores(rel)) return true;
      return shouldIgnorePath(rel);
    },
    childrenIgnored(p: GlobPath): boolean {
      const rel = p.relative().replace(/\\/g, '/');
      if (ig && rel && hasExplicitUnignore(ig, rel) && !ig.ignores(rel + '/')) return false;
      if (DEFAULT_IGNORE_LIST.has(p.name)) return true;
      if (ig && rel && ig.ignores(rel + '/')) return true;
      return false;
    },
  };
};
