/**
 * Tree-sitter symbol parser — extracts code elements from source files.
 *
 * Phase 2d scope: functions, classes, interfaces, methods, properties.
 * Skipped for now: calls, imports, heritage, HOC-wrapped arrows, routes, tools.
 */

import Parser from 'tree-sitter';
import { createHash } from 'node:crypto';
import { detectLanguage, getLanguageConfig, type SupportedLanguage } from './language-provider';

export interface ParsedSymbol {
  /** Deterministic node ID. */
  id: string;
  /** Graph node label: Function, Class, Interface, Method, Property, Const, Variable. */
  label: string;
  /** Symbol name. */
  name: string;
  /** Workspace-relative file path. */
  filePath: string;
  /** 1-indexed start line. */
  startLine: number;
  /** 1-indexed end line. */
  endLine: number;
  /** Source language. */
  language: SupportedLanguage;
  /** Whether the symbol is exported. */
  isExported: boolean;
}

export interface ParseResult {
  symbols: ParsedSymbol[];
  /** SHA-256 of the file content. */
  contentHash: string;
}

const DEFINITION_LABEL_MAP: Record<string, string> = {
  'definition.class': 'Class',
  'definition.interface': 'Interface',
  'definition.function': 'Function',
  'definition.method': 'Method',
  'definition.property': 'Property',
  'definition.const': 'Const',
  'definition.variable': 'Variable',
};

const DEFINITION_CAPTURE_KEYS = Object.keys(DEFINITION_LABEL_MAP);

function generateId(label: string, qualifier: string): string {
  return createHash('sha256').update(`${label}:${qualifier}`).digest('hex').slice(0, 16);
}

function getLabelFromCaptures(captureMap: Record<string, Parser.SyntaxNode>): string | null {
  for (const key of DEFINITION_CAPTURE_KEYS) {
    if (captureMap[key]) {
      return DEFINITION_LABEL_MAP[key];
    }
  }
  return null;
}

function getDefinitionNode(captureMap: Record<string, Parser.SyntaxNode>): Parser.SyntaxNode | null {
  for (const key of DEFINITION_CAPTURE_KEYS) {
    if (captureMap[key]) {
      return captureMap[key];
    }
  }
  return null;
}

function isExported(node: Parser.SyntaxNode, _name: string): boolean {
  // Simple heuristic: walk up to find export_statement or export modifier
  let current: Parser.SyntaxNode | null = node;
  while (current) {
    if (current.type === 'export_statement' || current.type === 'modifiers') {
      return true;
    }
    current = current.parent;
  }
  return false;
}

/**
 * Parse a single source file and extract symbols.
 *
 * @param filePath — workspace-relative path (used for node IDs and output)
 * @param content — file source text
 * @returns ParseResult with symbols and content hash, or null if language not supported
 */
export const parseSourceFile = (filePath: string, content: string): ParseResult | null => {
  const lang = detectLanguage(filePath);
  if (!lang) {
    return null;
  }

  const config = getLanguageConfig(lang);
  const parser = new Parser();
  parser.setLanguage(config.parser as Parser.Language);

  const tree = parser.parse(content);
  const query = new Parser.Query(config.parser as Parser.Language, config.queries);
  const matches = query.matches(tree.rootNode);

  const symbols: ParsedSymbol[] = [];

  for (const match of matches) {
    const captureMap: Record<string, Parser.SyntaxNode> = {};
    for (const c of match.captures) {
      captureMap[c.name] = c.node;
    }

    const label = getLabelFromCaptures(captureMap);
    const defNode = getDefinitionNode(captureMap);
    const nameNode = captureMap['name'];

    if (!label || !defNode || !nameNode) {
      continue;
    }

    const name = nameNode.text;
    const qualifiedName = `${filePath}:${name}`;

    symbols.push({
      id: generateId(label, qualifiedName),
      label,
      name,
      filePath,
      startLine: defNode.startPosition.row + 1,
      endLine: defNode.endPosition.row + 1,
      language: lang,
      isExported: isExported(defNode, name),
    });
  }

  const contentHash = createHash('sha256').update(content).digest('hex');

  return { symbols, contentHash };
};
