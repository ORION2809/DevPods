import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { auditRecordSchema, type AuditRecord } from '../protocol/schemas';
import { redactText } from '../policy/redaction';

const MAX_AUDIT_LOG_SIZE = 10 * 1024 * 1024;
const MAX_AUDIT_LOG_FILES = 5;

export class AuditLog {
  constructor(private readonly filePath: string) {}

  append(record: Omit<AuditRecord, 'id' | 'timestamp'>): AuditRecord {
    const entry = auditRecordSchema.parse({
      id: `audit_${randomUUID().replace(/-/g, '').slice(0, 12)}`,
      timestamp: new Date().toISOString(),
      ...record,
      detail: redactText(record.detail),
    });

    try {
      fs.mkdirSync(path.dirname(this.filePath), { recursive: true });
      this.rotateIfNeeded();
      fs.appendFileSync(this.filePath, `${JSON.stringify(entry)}\n`, 'utf8');
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      process.stderr.write(`[audit-log-error] ${message}\n`);
    }

    return entry;
  }

  private rotateIfNeeded(): void {
    let stats: fs.Stats;
    try {
      stats = fs.statSync(this.filePath);
    } catch {
      return;
    }

    if (stats.size < MAX_AUDIT_LOG_SIZE) {
      return;
    }

    const dir = path.dirname(this.filePath);
    const baseName = path.basename(this.filePath);
    const ext = path.extname(baseName);
    const stem = ext ? baseName.slice(0, -ext.length) : baseName;

    for (let i = MAX_AUDIT_LOG_FILES - 1; i >= 1; i--) {
      const oldPath = path.join(dir, `${stem}.${i}${ext}`);
      const newPath = path.join(dir, `${stem}.${i + 1}${ext}`);
      try {
        fs.renameSync(oldPath, newPath);
      } catch {
        // Ignore errors for missing backup files
      }
    }

    const rotatedPath = path.join(dir, `${stem}.1${ext}`);
    try {
      fs.renameSync(this.filePath, rotatedPath);
    } catch {
      // Ignore rotation errors
    }
  }
}
