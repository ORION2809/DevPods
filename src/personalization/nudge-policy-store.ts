import fs from 'node:fs';
import path from 'node:path';
import { nudgePolicySchema, type NudgePolicy } from '../protocol/schemas';

export class NudgePolicyStore {
  private readonly policies = new Map<string, NudgePolicy>();
  private readonly filePath: string | null;
  private saveTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(filePath?: string) {
    this.filePath = filePath ?? null;
    if (this.filePath) {
      this.loadFromDisk();
    }
  }

  getPolicy(sessionId: string): NudgePolicy {
    return (
      this.policies.get(sessionId) ?? {
        sessionId,
        enabled: true,
        mutedTypes: [],
        thresholds: [],
        updatedAtMs: Date.now(),
      }
    );
  }

  setPolicy(policy: NudgePolicy): NudgePolicy {
    const validated = nudgePolicySchema.parse(policy);
    this.policies.set(validated.sessionId, validated);
    this.scheduleSave();
    return validated;
  }

  resetPolicy(sessionId: string): NudgePolicy {
    const defaultPolicy: NudgePolicy = {
      sessionId,
      enabled: true,
      mutedTypes: [],
      thresholds: [],
      updatedAtMs: Date.now(),
    };
    this.policies.set(sessionId, defaultPolicy);
    this.scheduleSave();
    return defaultPolicy;
  }

  private scheduleSave(): void {
    if (!this.filePath) return;
    if (this.saveTimer) return;
    this.saveTimer = setTimeout(() => {
      this.saveTimer = null;
      this.saveToDisk();
    }, 500);
    if (typeof this.saveTimer.unref === 'function') {
      this.saveTimer.unref();
    }
  }

  private saveToDisk(): void {
    if (!this.filePath) return;
    try {
      fs.mkdirSync(path.dirname(this.filePath), { recursive: true });
      const snapshot = Object.fromEntries(this.policies);
      fs.writeFileSync(this.filePath, `${JSON.stringify(snapshot)}\n`, 'utf8');
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      process.stderr.write(`[nudge-policy-store-error] save failed: ${message}\n`);
    }
  }

  private loadFromDisk(): void {
    if (!this.filePath) return;
    try {
      if (!fs.existsSync(this.filePath)) return;
      const raw = fs.readFileSync(this.filePath, 'utf8');
      if (!raw.trim()) return;
      const snapshot = JSON.parse(raw) as Record<string, unknown>;
      for (const [sessionId, value] of Object.entries(snapshot)) {
        const parsed = nudgePolicySchema.safeParse(value);
        if (parsed.success) {
          this.policies.set(sessionId, parsed.data);
        }
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      process.stderr.write(`[nudge-policy-store-error] load failed: ${message}\n`);
    }
  }
}
