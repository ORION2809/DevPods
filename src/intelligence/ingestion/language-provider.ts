/**
 * Language provider — maps file extensions to tree-sitter parsers and queries.
 */

import Parser from 'tree-sitter';
import TypeScript from 'tree-sitter-typescript';
import JavaScript from 'tree-sitter-javascript';
import Kotlin from 'tree-sitter-kotlin';
import {
  TYPESCRIPT_QUERIES,
  JAVASCRIPT_QUERIES,
  KOTLIN_QUERIES,
} from './tree-sitter-queries';

export type SupportedLanguage = 'typescript' | 'tsx' | 'javascript' | 'jsx' | 'kotlin';

export interface LanguageConfig {
  language: SupportedLanguage;
  /** Tree-sitter language grammar (native binding). */
  parser: unknown;
  queries: string;
}

const EXTENSION_MAP: Record<string, SupportedLanguage> = {
  '.ts': 'typescript',
  '.tsx': 'tsx',
  '.js': 'javascript',
  '.jsx': 'jsx',
  '.kt': 'kotlin',
  '.kts': 'kotlin',
};

export const detectLanguage = (filePath: string): SupportedLanguage | null => {
  const ext = filePath.slice(filePath.lastIndexOf('.')).toLowerCase();
  return EXTENSION_MAP[ext] ?? null;
};

export const getLanguageConfig = (lang: SupportedLanguage): LanguageConfig => {
  switch (lang) {
    case 'typescript':
      return { language: 'typescript', parser: TypeScript.typescript, queries: TYPESCRIPT_QUERIES };
    case 'tsx':
      return { language: 'tsx', parser: TypeScript.tsx, queries: TYPESCRIPT_QUERIES };
    case 'javascript':
      return { language: 'javascript', parser: JavaScript, queries: JAVASCRIPT_QUERIES };
    case 'jsx':
      return { language: 'jsx', parser: JavaScript, queries: JAVASCRIPT_QUERIES };
    case 'kotlin':
      return { language: 'kotlin', parser: Kotlin, queries: KOTLIN_QUERIES };
    default:
      throw new Error(`Unsupported language: ${lang}`);
  }
};
