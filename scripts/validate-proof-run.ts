#!/usr/bin/env tsx
/**
 * Validate a proof-run JSON file against the DevPods proof contract.
 *
 * Supports tier-specific validation:
 *   T1_EMULATOR_SYNTHETIC: 3+ sessions, no physicalBluetoothProven requirement
 *   T1_PCM_INJECTION: 3+ sessions, inputPath=synthetic_pcm
 *   T2_EMULATOR_HOST_AUDIO: 3+ sessions, inputPath=emulator_host_mic
 *   T4_PHYSICAL_ANDROID_EARBUDS: 20 sessions, physicalBluetoothProven=true, 5 interruption tests
 *
 * Usage:
 *   npx tsx scripts/validate-proof-run.ts <proof-run.json>
 *   npx tsx scripts/validate-proof-run.ts <proof-run.json> --require-tier T4_PHYSICAL_ANDROID_EARBUDS --max-age-days 7
 */
import fs from 'node:fs';
import path from 'node:path';

const VALID_TIERS = [
  'T1_EMULATOR_SYNTHETIC',
  'T1_PCM_INJECTION',
  'T2_EMULATOR_HOST_AUDIO',
  'T3_EMULATOR_SIMULATED_BLUETOOTH',
  'T4_PHYSICAL_ANDROID_EARBUDS',
] as const;

type ProofTier = typeof VALID_TIERS[number];

const VALID_ROUTE_SOURCES = ['synthetic', 'host_audio', 'netsim_bluetooth', 'physical_bluetooth'] as const;
const VALID_INPUT_PATHS = ['synthetic_text', 'synthetic_pcm', 'emulator_host_mic', 'android_bluetooth_headset', 'android_phone_mic'] as const;
const VALID_OUTPUT_PATHS = ['fake_tts', 'android_tts_speaker', 'android_tts_bluetooth', 'host_audio'] as const;
const VALID_WAKE_PATHS = ['direct_hardware', 'android_media_session', 'assistant_entry', 'push_to_talk'] as const;
const VALID_ENGINE_IDS = ['platform_speech_recognizer', 'sherpa_onnx', 'none', 'synthetic'] as const;
const VALID_BRIDGE_MODES = ['local', 'openclaw'] as const;

export interface ProofSession {
  sessionNumber: number;
  wakeDetected: boolean;
  routeSettleMs?: number;
  routeState?: string;
  sttProducedTranscript: boolean;
  bridgeReached: boolean;
  ttsPlayed: boolean;
}

export interface InterruptionTest {
  testNumber: number;
  targetMet: boolean;
  ttsStopLatencyMs?: number;
  listeningStartLatencyMs?: number;
}

export interface ProofRun {
  proofRunId: string;
  startedAt: string;
  completedAt?: string;
  proofTier: ProofTier;
  phoneModel: string;
  androidVersion: string;
  earbudModel: string;
  providerId: string;
  wakePath: string;
  inputPath: string;
  outputPath: string;
  engineId: string;
  bridgeMode: string;
  routeProofSource: string;
  physicalBluetoothProven: boolean;
  status: string;
  blockingFailures: string[];
  sessions: ProofSession[];
  interruptionTests: InterruptionTest[];
  appVersion?: string;
  bridgeVersion?: string;
  appBuildHash?: string;
  bridgeBuildHash?: string;
  redactionMetadata?: Record<string, unknown>;
  diagnosticExportHash?: string;
  calibrationProfileId?: string;
  calibratedGestureUsed?: string;
  matchedCalibratedAction?: string;
  sameProfileProof?: boolean;

  // Personalized collaborator feature evidence (Steps 7-9)
  mdnsDiscovered?: boolean;
  quickStartUsed?: boolean;
  approvalNotificationHandled?: boolean;
  wearTileSynced?: boolean;
  lockScreenDetailEnabled?: boolean;
}

export interface ValidateOptions {
  requireTier?: ProofTier;
  maxAgeDays?: number;
}

function isSimulationTier(tier: string): boolean {
  return tier === 'T1_EMULATOR_SYNTHETIC' || tier === 'T1_PCM_INJECTION' || tier === 'T2_EMULATOR_HOST_AUDIO' || tier === 'T3_EMULATOR_SIMULATED_BLUETOOTH';
}

export function validateProofRun(filePath: string, options: ValidateOptions = {}): { valid: boolean; errors: string[] } {
  const errors: string[] = [];

  if (!fs.existsSync(filePath)) {
    return { valid: false, errors: [`File not found: ${filePath}`] };
  }

  let raw: string;
  try {
    raw = fs.readFileSync(filePath, 'utf8');
  } catch {
    return { valid: false, errors: ['Cannot read file'] };
  }

  // Strip BOM if present
  if (raw.charCodeAt(0) === 0xFEFF) {
    raw = raw.slice(1);
  }

  let data: Record<string, unknown>;
  try {
    data = JSON.parse(raw) as Record<string, unknown>;
  } catch {
    return { valid: false, errors: ['Invalid JSON'] };
  }

  const isExplicitTemplate = data.status === 'template';
  const proofTier = (data.proofTier ?? 'T4_PHYSICAL_ANDROID_EARBUDS') as string;
  const isSimulation = isSimulationTier(proofTier);

  // Core required fields for all tiers
  const coreRequired = ['proofRunId', 'startedAt', 'proofTier', 'phoneModel', 'androidVersion', 'earbudModel', 'providerId', 'wakePath', 'inputPath', 'outputPath', 'engineId', 'bridgeMode', 'sessions'];
  for (const field of coreRequired) {
    if (!(field in data)) {
      errors.push(`Missing required field: ${field}`);
    }
  }

  // Tier validation
  if (data.proofTier !== undefined && !VALID_TIERS.includes(data.proofTier as ProofTier)) {
    errors.push(`Invalid proofTier: ${data.proofTier}. Must be one of: ${VALID_TIERS.join(', ')}`);
  }

  // Route proof source
  if (data.routeProofSource !== undefined && !VALID_ROUTE_SOURCES.includes(data.routeProofSource as typeof VALID_ROUTE_SOURCES[number])) {
    errors.push(`Invalid routeProofSource: ${data.routeProofSource}`);
  }

  // Input/output path validation
  if (data.inputPath !== undefined && !VALID_INPUT_PATHS.includes(data.inputPath as typeof VALID_INPUT_PATHS[number])) {
    errors.push(`Invalid inputPath: ${data.inputPath}`);
  }
  if (data.outputPath !== undefined && !VALID_OUTPUT_PATHS.includes(data.outputPath as typeof VALID_OUTPUT_PATHS[number])) {
    errors.push(`Invalid outputPath: ${data.outputPath}`);
  }

  // Physical bluetooth proven must be boolean
  if (data.physicalBluetoothProven !== undefined && typeof data.physicalBluetoothProven !== 'boolean') {
    errors.push('physicalBluetoothProven must be a boolean');
  }

  // T4-specific: require physicalBluetoothProven = true
  if (proofTier === 'T4_PHYSICAL_ANDROID_EARBUDS' && !isExplicitTemplate) {
    if (data.physicalBluetoothProven !== true) {
      errors.push('T4 proof requires physicalBluetoothProven: true');
    }
    if (data.routeProofSource !== 'physical_bluetooth') {
      errors.push('T4 proof requires routeProofSource: "physical_bluetooth"');
    }
  }

  // Simulation tiers must NOT claim physical bluetooth proven
  if (isSimulation && data.physicalBluetoothProven === true) {
    errors.push(`Simulation tier ${proofTier} cannot claim physicalBluetoothProven: true`);
  }

  // Session validation
  const sessions = data.sessions as ProofSession[] | undefined;
  if (sessions) {
    if (!Array.isArray(sessions)) {
      errors.push('sessions must be an array');
    } else {
      const requiredSessionCount = isSimulation ? 3 : 20;
      if (!isExplicitTemplate && sessions.length < requiredSessionCount) {
        errors.push(`Expected at least ${requiredSessionCount} sessions for ${proofTier}, found ${sessions.length}`);
      }

      const successfulSessions = sessions.filter(s => s.wakeDetected && s.sttProducedTranscript && s.bridgeReached && s.ttsPlayed);
      const successThreshold = isSimulation ? Math.ceil(sessions.length * 0.8) : 19;
      if (!isExplicitTemplate && successfulSessions.length < successThreshold) {
        errors.push(`Only ${successfulSessions.length}/${sessions.length} sessions succeeded. Need at least ${successThreshold} for ${proofTier}.`);
      }

      for (let i = 0; i < sessions.length; i++) {
        const s = sessions[i];
        if (s.sessionNumber !== i + 1) {
          errors.push(`Session ${i} has sessionNumber ${s.sessionNumber}, expected ${i + 1}`);
        }
      }
    }
  }

  // Interruption tests - required for T4, optional for simulation
  const interruptionTests = data.interruptionTests as InterruptionTest[] | undefined;
  if (proofTier === 'T4_PHYSICAL_ANDROID_EARBUDS' && !isExplicitTemplate) {
    if (!interruptionTests) {
      errors.push('T4 proof requires interruptionTests array');
    } else if (interruptionTests.length < 5) {
      errors.push(`Expected at least 5 interruption tests for T4, found ${interruptionTests.length}`);
    } else {
      const metTargets = interruptionTests.filter(t => t.targetMet).length;
      if (metTargets < 5) {
        errors.push(`Only ${metTargets}/${interruptionTests.length} interruption targets met. Need all 5.`);
      }
    }
  } else if (interruptionTests && !isExplicitTemplate) {
    const metTargets = interruptionTests.filter(t => t.targetMet).length;
    if (metTargets < interruptionTests.length * 0.8) {
      errors.push(`Only ${metTargets}/${interruptionTests.length} interruption targets met.`);
    }
  }

  // T4-specific calibration evidence
  if (proofTier === 'T4_PHYSICAL_ANDROID_EARBUDS' && !isExplicitTemplate) {
    if (!data.calibrationProfileId) {
      errors.push('T4 proof requires calibrationProfileId');
    }
    if (!data.calibratedGestureUsed) {
      errors.push('T4 proof requires calibratedGestureUsed');
    }
    if (!data.matchedCalibratedAction) {
      errors.push('T4 proof requires matchedCalibratedAction');
    }
    if (data.sameProfileProof !== true) {
      errors.push('T4 proof requires sameProfileProof: true');
    }
  }

  // Feature evidence type validation
  const booleanFeatureFields = ['mdnsDiscovered', 'quickStartUsed', 'approvalNotificationHandled', 'wearTileSynced', 'lockScreenDetailEnabled'] as const;
  for (const field of booleanFeatureFields) {
    if (data[field] !== undefined && typeof data[field] !== 'boolean') {
      errors.push(`${field} must be a boolean`);
    }
  }

  // Status consistency
  if (data.status === 'PASSED' && errors.length > 0) {
    errors.push('Status is "PASSED" but validation found failures');
  }

  // Require-tier check
  if (options.requireTier && data.proofTier !== options.requireTier) {
    errors.push(`Required tier ${options.requireTier}, but proof has tier ${data.proofTier}`);
  }

  // Max-age check
  if (options.maxAgeDays && data.startedAt) {
    const startedAt = new Date(data.startedAt as string);
    if (!isNaN(startedAt.getTime())) {
      const ageDays = (Date.now() - startedAt.getTime()) / (1000 * 60 * 60 * 24);
      if (ageDays > options.maxAgeDays) {
        errors.push(`Proof is ${ageDays.toFixed(1)} days old, maximum allowed is ${options.maxAgeDays} days`);
      }
    }
  }

  return { valid: errors.length === 0, errors };
}

function validateDirectory(dirPath: string, options: ValidateOptions): { valid: boolean; errors: string[]; fileCount: number } {
  const errors: string[] = [];
  let fileCount = 0;
  let allValid = true;

  if (!fs.existsSync(dirPath)) {
    return { valid: false, errors: [`Directory not found: ${dirPath}`], fileCount: 0 };
  }

  const entries = fs.readdirSync(dirPath, { withFileTypes: true });
  const files: string[] = [];

  for (const entry of entries) {
    if (entry.isFile() && entry.name.endsWith('.json') && !entry.name.startsWith('TEMPLATE')) {
      files.push(path.join(dirPath, entry.name));
    } else if (entry.isDirectory()) {
      // Look for JSON files inside subdirectories
      const nestedDir = path.join(dirPath, entry.name);
      const nestedEntries = fs.readdirSync(nestedDir, { withFileTypes: true });
      for (const nested of nestedEntries) {
        if (nested.isFile() && nested.name.endsWith('.json') && !nested.name.startsWith('TEMPLATE')) {
          files.push(path.join(nestedDir, nested.name));
        }
      }
    }
  }

  if (files.length === 0) {
    return { valid: false, errors: [`No proof-run JSON files found in ${dirPath}`], fileCount: 0 };
  }

  for (const file of files) {
    fileCount++;
    const result = validateProofRun(file, options);
    if (!result.valid) {
      allValid = false;
      errors.push(`FAIL ${path.relative(dirPath, file)}:`);
      for (const error of result.errors) {
        errors.push(`  - ${error}`);
      }
    } else {
      errors.push(`OK ${path.relative(dirPath, file)}`);
    }
  }

  return { valid: allValid, errors, fileCount };
}

function main() {
  const args = process.argv.slice(2);
  if (args.length === 0) {
    console.error('Usage: npx tsx scripts/validate-proof-run.ts <proof-run.json|directory> [--require-tier TIER] [--max-age-days N]');
    process.exit(1);
  }

  const targetPath = path.resolve(args[0]);
  const options: ValidateOptions = {};

  for (let i = 1; i < args.length; i++) {
    if (args[i] === '--require-tier' && i + 1 < args.length) {
      options.requireTier = args[++i] as ProofTier;
    } else if (args[i] === '--max-age-days' && i + 1 < args.length) {
      options.maxAgeDays = parseInt(args[++i], 10);
    }
  }

  const isDirectory = fs.existsSync(targetPath) && fs.statSync(targetPath).isDirectory();

  if (isDirectory) {
    const result = validateDirectory(targetPath, options);
    for (const msg of result.errors) {
      if (msg.startsWith('OK ')) {
        console.log(msg);
      } else {
        console.error(msg);
      }
    }
    console.log(`\nValidated ${result.fileCount} file(s) in ${targetPath}`);
    if (result.valid) {
      console.log('All proof artifacts are valid');
      process.exit(0);
    } else {
      console.error('Some proof artifacts failed validation');
      process.exit(1);
    }
  } else {
    const result = validateProofRun(targetPath, options);

    if (result.valid) {
      console.log(`OK ${path.basename(targetPath)} is valid`);
      process.exit(0);
    } else {
      console.error(`FAIL ${path.basename(targetPath)} has errors:`);
      for (const error of result.errors) {
        console.error(`  - ${error}`);
      }
      process.exit(1);
    }
  }
}

const invokedScriptName = process.argv[1] ? path.basename(process.argv[1]) : '';

if (invokedScriptName === 'validate-proof-run.ts' || invokedScriptName === 'validate-proof-run.js') {
  main();
}
