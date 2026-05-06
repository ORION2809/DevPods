import { describe, expect, it } from 'vitest';
import { redactText } from '../src/policy/redaction';

describe('redaction', () => {
  it('redacts Authorization bearer headers', () => {
    const result = redactText('Authorization: Bearer sk-proj-abc123def456');

    expect(result).toContain('Authorization: Bearer [REDACTED]');
    expect(result).not.toContain('sk-proj-abc123def456');
  });

  it('redacts JSON apiKey values', () => {
    const result = redactText('{"apiKey":"sk-123","other":"value"}');

    expect(result).toContain('"apiKey":"[REDACTED]"');
    expect(result).toContain('"other":"value"');
    expect(result).not.toContain('sk-123');
  });

  it('redacts JSON token and password values', () => {
    const result = redactText('{"token":"ghp_abc123xyz987654321000","password":"supersecret"}');

    expect(result).toContain('"token":"[REDACTED]"');
    expect(result).toContain('"password":"[REDACTED]"');
    expect(result).not.toContain('ghp_abc123xyz987654321000');
    expect(result).not.toContain('supersecret');
  });

  it('redacts assignment-style secrets while preserving the key name', () => {
    const result = redactText('OPENAI_API_KEY=secret-value token: abc123');

    expect(result).toContain('OPENAI_API_KEY=[REDACTED]');
    expect(result).toContain('token: [REDACTED]');
    expect(result).not.toContain('secret-value');
    expect(result).not.toContain('abc123');
  });

  it('leaves non-secret JSON content intact', () => {
    const input = '{"apiVersion":"v1","endpoint":"/api/token"}';

    expect(redactText(input)).toBe(input);
  });
});