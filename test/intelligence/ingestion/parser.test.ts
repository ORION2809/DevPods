import { describe, expect, it } from 'vitest';
import { parseSourceFile, type ParseResult } from '../../../src/intelligence/ingestion/parser';

describe('parser', () => {
  const parse = (path: string, content: string): ParseResult | null => {
    return parseSourceFile(path, content);
  };

  describe('TypeScript', () => {
    it('extracts classes', () => {
      const result = parse('src/user.ts', 'export class User {}');
      expect(result).not.toBeNull();
      expect(result!.symbols).toHaveLength(1);
      expect(result!.symbols[0].label).toBe('Class');
      expect(result!.symbols[0].name).toBe('User');
      expect(result!.symbols[0].isExported).toBe(true);
    });

    it('extracts functions', () => {
      const result = parse('src/utils.ts', 'function add(a: number, b: number): number { return a + b; }');
      expect(result).not.toBeNull();
      expect(result!.symbols).toHaveLength(1);
      expect(result!.symbols[0].label).toBe('Function');
      expect(result!.symbols[0].name).toBe('add');
    });

    it('extracts methods', () => {
      const result = parse('src/service.ts', `
        class UserService {
          getUser(id: string) { return id; }
        }
      `);
      expect(result).not.toBeNull();
      const methods = result!.symbols.filter((s) => s.label === 'Method');
      expect(methods).toHaveLength(1);
      expect(methods[0].name).toBe('getUser');
    });

    it('extracts arrow functions in const', () => {
      const result = parse('src/arrow.ts', 'const greet = (name: string) => `Hello ${name}`;');
      expect(result).not.toBeNull();
      const funcs = result!.symbols.filter((s) => s.label === 'Function');
      expect(funcs).toHaveLength(1);
      expect(funcs[0].name).toBe('greet');
    });

    it('extracts interfaces', () => {
      const result = parse('src/types.ts', 'interface Config { port: number; }');
      expect(result).not.toBeNull();
      const interfaces = result!.symbols.filter((s) => s.label === 'Interface');
      expect(interfaces).toHaveLength(1);
      expect(interfaces[0].name).toBe('Config');
    });

    it('computes content hash', () => {
      const result = parse('src/a.ts', 'const x = 1;');
      expect(result).not.toBeNull();
      expect(result!.contentHash).toHaveLength(64);
    });

    it('returns line numbers', () => {
      const result = parse('src/lines.ts', '\n\nclass Foo {\n  bar() {}\n}\n');
      expect(result).not.toBeNull();
      const cls = result!.symbols.find((s) => s.label === 'Class');
      expect(cls!.startLine).toBe(3);
      expect(cls!.endLine).toBe(5);
    });
  });

  describe('JavaScript', () => {
    it('extracts functions', () => {
      const result = parse('src/app.js', 'function hello() { return "world"; }');
      expect(result).not.toBeNull();
      expect(result!.symbols[0].label).toBe('Function');
      expect(result!.symbols[0].name).toBe('hello');
    });

    it('extracts classes', () => {
      const result = parse('src/model.js', 'class Model {}');
      expect(result).not.toBeNull();
      expect(result!.symbols[0].label).toBe('Class');
    });
  });

  describe('Kotlin', () => {
    it('extracts functions', () => {
      const result = parse('src/App.kt', 'fun main() { println("hello") }');
      expect(result).not.toBeNull();
      expect(result!.symbols[0].label).toBe('Function');
      expect(result!.symbols[0].name).toBe('main');
    });

    it('extracts classes', () => {
      const result = parse('src/User.kt', 'class User(val name: String)');
      expect(result).not.toBeNull();
      const classes = result!.symbols.filter((s) => s.label === 'Class');
      expect(classes).toHaveLength(1);
      expect(classes[0].name).toBe('User');
    });
  });

  describe('unsupported files', () => {
    it('returns null for unknown extensions', () => {
      const result = parse('README.md', '# Hello');
      expect(result).toBeNull();
    });

    it('returns null for .json', () => {
      const result = parse('package.json', '{}');
      expect(result).toBeNull();
    });
  });
});
