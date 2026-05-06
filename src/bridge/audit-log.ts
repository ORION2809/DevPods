import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { auditRecordSchema, type AuditRecord } from '../protocol/schemas';
import { redactText } from '../policy/redaction';

export class AuditLog {
  constructor(private readonly filePath: string) {}

  append(record: Omit<AuditRecord, 'id' | 'timestamp'>): AuditRecord {
    const entry = auditRecordSchema.parse({
      id: `audit_${randomUUID().replace(/-/g, '').slice(0, 12)}`,
      timestamp: new Date().toISOString(),
      ...record,
      detail: redactText(record.detail),
    });

    fs.mkdirSync(path.dirname(this.filePath), { recursive: true });
    fs.appendFileSync(this.filePath, `${JSON.stringify(entry)}\n`, 'utf8');
    return entry;
  }
}