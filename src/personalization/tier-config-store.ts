import fs from 'node:fs';
import path from 'node:path';
import { z } from 'zod';
import type { DevPodsInstalledTier } from '../protocol/schemas';
import { devPodsInstalledTierSchema } from '../protocol/schemas';

export const workspaceIntelligenceConsentSchema = z.object({
  consented: z.boolean(),
  consentedAtMs: z.number().int().nullable(),
});

export const tierConfigSchema = z.object({
  tier: devPodsInstalledTierSchema.default('core'),
  intelligenceConsent: z.record(z.string(), workspaceIntelligenceConsentSchema).default({}),
  updatedAtMs: z.number().int().default(() => Date.now()),
});

export type TierConfig = z.infer<typeof tierConfigSchema>;
export type WorkspaceIntelligenceConsent = z.infer<typeof workspaceIntelligenceConsentSchema>;

const DEFAULT_CONFIG: TierConfig = {
  tier: 'core',
  intelligenceConsent: {},
  updatedAtMs: Date.now(),
};

/**
 * Persistent store for the user's chosen DevPods tier and per-workspace
 * intelligence consent. Follows the same JSON-file pattern as other
 * personalization stores.
 */
export class TierConfigStore {
  private config: TierConfig;
  private readonly filePath: string | null;

  constructor(filePath?: string) {
    this.filePath = filePath ?? null;
    this.config = this.loadFromDisk();
  }

  getConfig(): TierConfig {
    return { ...this.config };
  }

  getTier(): DevPodsInstalledTier {
    return this.config.tier;
  }

  setTier(tier: DevPodsInstalledTier): TierConfig {
    const validated = devPodsInstalledTierSchema.parse(tier);
    this.config = {
      ...this.config,
      tier: validated,
      updatedAtMs: Date.now(),
    };
    this.saveToDisk();
    return { ...this.config };
  }

  getIntelligenceConsent(workspaceId: string): WorkspaceIntelligenceConsent | null {
    return this.config.intelligenceConsent[workspaceId] ?? null;
  }

  setIntelligenceConsent(workspaceId: string, consent: WorkspaceIntelligenceConsent): TierConfig {
    const validated = workspaceIntelligenceConsentSchema.parse(consent);
    this.config = {
      ...this.config,
      intelligenceConsent: {
        ...this.config.intelligenceConsent,
        [workspaceId]: validated,
      },
      updatedAtMs: Date.now(),
    };
    this.saveToDisk();
    return { ...this.config };
  }

  removeIntelligenceConsent(workspaceId: string): TierConfig {
    const { [workspaceId]: _removed, ...rest } = this.config.intelligenceConsent;
    this.config = {
      ...this.config,
      intelligenceConsent: rest,
      updatedAtMs: Date.now(),
    };
    this.saveToDisk();
    return { ...this.config };
  }

  private saveToDisk(): void {
    if (!this.filePath) return;
    try {
      fs.mkdirSync(path.dirname(this.filePath), { recursive: true });
      fs.writeFileSync(this.filePath, `${JSON.stringify(this.config, null, 2)}\n`, 'utf8');
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      process.stderr.write(`[tier-config-store-error] save failed: ${message}\n`);
    }
  }

  private loadFromDisk(): TierConfig {
    if (!this.filePath) return { ...DEFAULT_CONFIG };
    try {
      if (!fs.existsSync(this.filePath)) return { ...DEFAULT_CONFIG };
      const raw = fs.readFileSync(this.filePath, 'utf8');
      if (!raw.trim()) return { ...DEFAULT_CONFIG };
      const parsed = tierConfigSchema.safeParse(JSON.parse(raw));
      return parsed.success ? parsed.data : { ...DEFAULT_CONFIG };
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      process.stderr.write(`[tier-config-store-error] load failed: ${message}\n`);
      return { ...DEFAULT_CONFIG };
    }
  }
}

/**
 * Resolve the effective tier: CLI flag wins, then config file, then env, then default.
 *
 * Throws when the CLI explicitly provides an invalid tier so the user knows
 * immediately instead of silently falling back to core.
 */
export function resolveEffectiveTier(
  cliTier: string | boolean | undefined,
  configFileTier: DevPodsInstalledTier | undefined,
  env: NodeJS.ProcessEnv = {},
): DevPodsInstalledTier {
  const cli = typeof cliTier === 'string' ? cliTier.trim() : undefined;
  if (cli !== undefined) {
    if (cli === 'core' || cli === 'agent' || cli === 'intelligence') {
      return cli;
    }
    throw new Error(
      `Invalid tier "${cli}". Expected "core", "agent", or "intelligence".`,
    );
  }
  if (configFileTier === 'core' || configFileTier === 'agent' || configFileTier === 'intelligence') {
    return configFileTier;
  }
  const envTier = env.DEVPODS_TIER?.trim();
  if (envTier === 'core' || envTier === 'agent' || envTier === 'intelligence') {
    return envTier;
  }
  return 'core';
}
