import fs from 'node:fs';
import path from 'node:path';
import { learnedPhraseSchema, type LearnedPhrase } from '../protocol/schemas';
import type { IntentName } from '../protocol/types';

const PROMOTION_THRESHOLD = 2;

export interface IntentResolution {
  intent: IntentName;
  confidence: number;
  source: 'habit' | 'fixed_rule' | 'fallback';
  needsConfirmation: boolean;
}

/**
 * Local voice habit store for phrase -> intent learning.
 *
 * - Phrases are normalized (lowercase, trimmed) before storage.
 * - A mapping is promoted after PROMOTION_THRESHOLD confirmations.
 * - Unpromoted mappings require confirmation before execution.
 * - No cloud sync.
 */
export class VoiceHabitStore {
  private readonly phrases = new Map<string, LearnedPhrase>();
  private readonly filePath: string | null;
  private saveTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(filePath?: string) {
    this.filePath = filePath ?? null;
    if (this.filePath) {
      this.loadFromDisk();
    }
  }

  /**
   * Look up a phrase and return resolution details.
   */
  resolve(phrase: string): { intent: IntentName | null; confidence: number; promoted: boolean } {
    const normalized = this.normalize(phrase);
    const entry = this.phrases.get(normalized);
    if (!entry) {
      return { intent: null, confidence: 0, promoted: false };
    }
    const promoted = entry.confirmationCount >= PROMOTION_THRESHOLD;
    return {
      intent: entry.intent as IntentName,
      confidence: promoted ? 1.0 : 0.5,
      promoted,
    };
  }

  /**
   * Record a confirmed phrase -> intent mapping.
   * Returns the updated entry.
   */
  confirm(phrase: string, intent: IntentName): LearnedPhrase {
    const normalized = this.normalize(phrase);
    const existing = this.phrases.get(normalized);
    const updated: LearnedPhrase = {
      phrase: normalized,
      intent,
      confirmationCount: (existing?.confirmationCount ?? 0) + 1,
      createdAtMs: existing?.createdAtMs ?? Date.now(),
      lastConfirmedAtMs: Date.now(),
    };
    this.phrases.set(normalized, updated);
    this.scheduleSave();
    return updated;
  }

  /**
   * Directly set a phrase mapping (for testing or manual entry).
   */
  set(phrase: string, intent: IntentName, confirmationCount: number = 0): LearnedPhrase {
    const normalized = this.normalize(phrase);
    const entry: LearnedPhrase = {
      phrase: normalized,
      intent,
      confirmationCount,
      createdAtMs: Date.now(),
      lastConfirmedAtMs: confirmationCount > 0 ? Date.now() : undefined,
    };
    this.phrases.set(normalized, entry);
    this.scheduleSave();
    return entry;
  }

  delete(phrase: string): boolean {
    const normalized = this.normalize(phrase);
    const existed = this.phrases.has(normalized);
    this.phrases.delete(normalized);
    if (existed) {
      this.scheduleSave();
    }
    return existed;
  }

  list(): LearnedPhrase[] {
    return Array.from(this.phrases.values()).sort(
      (a, b) => (b.lastConfirmedAtMs ?? b.createdAtMs) - (a.lastConfirmedAtMs ?? a.createdAtMs),
    );
  }

  get(phrase: string): LearnedPhrase | null {
    return this.phrases.get(this.normalize(phrase)) ?? null;
  }

  private normalize(phrase: string): string {
    return phrase.toLowerCase().trim().replace(/\s+/g, ' ');
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
      const snapshot = { phrases: Array.from(this.phrases.values()) };
      fs.writeFileSync(this.filePath, `${JSON.stringify(snapshot, null, 2)}\n`, 'utf8');
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      process.stderr.write(`[voice-habit-store-error] save failed: ${message}\n`);
    }
  }

  private loadFromDisk(): void {
    if (!this.filePath) return;
    try {
      if (!fs.existsSync(this.filePath)) return;
      const raw = fs.readFileSync(this.filePath, 'utf8');
      if (!raw.trim()) return;
      const snapshot = JSON.parse(raw) as { phrases: unknown[] };
      for (const item of snapshot.phrases) {
        const parsed = learnedPhraseSchema.safeParse(item);
        if (parsed.success) {
          this.phrases.set(parsed.data.phrase, parsed.data);
        }
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      process.stderr.write(`[voice-habit-store-error] load failed: ${message}\n`);
    }
  }

  /** Synchronous flush for testing. */
  flush(): void {
    if (this.saveTimer) {
      clearTimeout(this.saveTimer);
      this.saveTimer = null;
    }
    this.saveToDisk();
  }
}
