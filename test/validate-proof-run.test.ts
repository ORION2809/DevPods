import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import { afterEach, describe, expect, it } from 'vitest';

import { validateProofRun, type ProofRun } from '../scripts/validate-proof-run';

const tempDirs: string[] = [];

function writeProofRunFile(proofRun: ProofRun): string {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'proof-run-'));
  tempDirs.push(directory);
  const filePath = path.join(directory, 'proof-run.json');
  fs.writeFileSync(filePath, JSON.stringify(proofRun, null, 2));
  return filePath;
}

function createSession(n: number): ProofRun['sessions'][number] {
  return {
    sessionNumber: n,
    wakeDetected: true,
    routeSettleMs: 180,
    sttProducedTranscript: true,
    bridgeReached: true,
    ttsPlayed: true,
  };
}

function createInterruptionTest(n: number): ProofRun['interruptionTests'][number] {
  return { testNumber: n, targetMet: true };
}

function createMinimalProofRun(status: ProofRun['status'], proofTier: ProofRun['proofTier'] = 'T4_PHYSICAL_ANDROID_EARBUDS'): ProofRun {
  const isSimulation = proofTier.startsWith('T1') || proofTier.startsWith('T2') || proofTier.startsWith('T3');
  const sessionCount = isSimulation ? 3 : 20;
  const interruptionCount = isSimulation ? 1 : 5;

  const base: ProofRun = {
    proofRunId: 'PHONE-ANDROID-EARBUDS-YYYYMMDD-HHMMSS',
    startedAt: '2026-05-19T10:00:00Z',
    proofTier,
    phoneModel: 'Pixel 8',
    androidVersion: '15',
    earbudModel: 'AirPods Pro 2',
    providerId: 'apple_airpods',
    wakePath: 'android_media_session',
    inputPath: proofTier === 'T4_PHYSICAL_ANDROID_EARBUDS' ? 'android_bluetooth_headset' : 'synthetic_text',
    outputPath: proofTier === 'T4_PHYSICAL_ANDROID_EARBUDS' ? 'android_tts_bluetooth' : 'fake_tts',
    engineId: 'platform_speech_recognizer',
    bridgeMode: 'local',
    routeProofSource: proofTier === 'T4_PHYSICAL_ANDROID_EARBUDS' ? 'physical_bluetooth' : 'synthetic',
    physicalBluetoothProven: proofTier === 'T4_PHYSICAL_ANDROID_EARBUDS',
    status,
    blockingFailures: [],
    sessions: Array.from({ length: sessionCount }, (_, i) => createSession(i + 1)),
    interruptionTests: Array.from({ length: interruptionCount }, (_, i) => createInterruptionTest(i + 1)),
  };

  if (proofTier === 'T4_PHYSICAL_ANDROID_EARBUDS') {
    base.calibrationProfileId = 'test-profile-123';
    base.calibratedGestureUsed = 'SINGLE_PRESS';
    base.matchedCalibratedAction = 'WAKE_AND_LISTEN';
    base.sameProfileProof = true;
  }

  return base;
}

describe('validateProofRun', () => {
  afterEach(() => {
    while (tempDirs.length > 0) {
      const directory = tempDirs.pop();
      if (directory) {
        fs.rmSync(directory, { recursive: true, force: true });
      }
    }
  });

  it('accepts explicit template files with minimal sample data', () => {
    const filePath = writeProofRunFile(createMinimalProofRun('template'));

    const result = validateProofRun(filePath);

    expect(result).toEqual({ valid: true, errors: [] });
  });

  it('keeps strict beta thresholds for passed artifacts', () => {
    const proofRun = createMinimalProofRun('passed');
    proofRun.sessions = [createSession(1)];
    proofRun.interruptionTests = [createInterruptionTest(1)];
    const filePath = writeProofRunFile(proofRun);

    const result = validateProofRun(filePath);

    expect(result.valid).toBe(false);
    expect(result.errors.some(e => e.includes('Expected at least 20 sessions'))).toBe(true);
    expect(result.errors.some(e => e.includes('Expected at least 5 interruption tests'))).toBe(true);
  });

  it('validates simulation tier with lower session threshold', () => {
    const filePath = writeProofRunFile(createMinimalProofRun('PASSED', 'T1_EMULATOR_SYNTHETIC'));

    const result = validateProofRun(filePath);

    expect(result.valid).toBe(true);
  });

  it('rejects simulation tier claiming physicalBluetoothProven', () => {
    const proofRun = createMinimalProofRun('template', 'T1_EMULATOR_SYNTHETIC');
    proofRun.physicalBluetoothProven = true;
    const filePath = writeProofRunFile(proofRun);

    const result = validateProofRun(filePath);

    expect(result.valid).toBe(false);
    expect(result.errors.some(e => e.includes('cannot claim physicalBluetoothProven'))).toBe(true);
  });

  it('requires T4 to have physicalBluetoothProven true for non-template', () => {
    const proofRun = createMinimalProofRun('PASSED', 'T4_PHYSICAL_ANDROID_EARBUDS');
    proofRun.physicalBluetoothProven = false;
    proofRun.routeProofSource = 'physical_bluetooth';
    proofRun.inputPath = 'android_bluetooth_headset';
    proofRun.outputPath = 'android_tts_bluetooth';
    const filePath = writeProofRunFile(proofRun);

    const result = validateProofRun(filePath);

    expect(result.valid).toBe(false);
    expect(result.errors.some(e => e.includes('T4 proof requires physicalBluetoothProven'))).toBe(true);
  });

  it('supports --require-tier option', () => {
    const filePath = writeProofRunFile(createMinimalProofRun('template', 'T1_EMULATOR_SYNTHETIC'));

    const result = validateProofRun(filePath, { requireTier: 'T4_PHYSICAL_ANDROID_EARBUDS' });

    expect(result.valid).toBe(false);
    expect(result.errors.some(e => e.includes('Required tier'))).toBe(true);
  });

  it('rejects T4 with fewer than 20 sessions', () => {
    const proofRun = createMinimalProofRun('PASSED', 'T4_PHYSICAL_ANDROID_EARBUDS');
    proofRun.sessions = Array.from({ length: 19 }, (_, i) => createSession(i + 1));
    const filePath = writeProofRunFile(proofRun);

    const result = validateProofRun(filePath);

    expect(result.valid).toBe(false);
    expect(result.errors.some(e => e.includes('Expected at least 20 sessions'))).toBe(true);
  });

  it('rejects T4 with fewer than 19 successful sessions', () => {
    const proofRun = createMinimalProofRun('PASSED', 'T4_PHYSICAL_ANDROID_EARBUDS');
    proofRun.sessions = Array.from({ length: 20 }, (_, i) =>
      i < 2
        ? { sessionNumber: i + 1, wakeDetected: false, sttProducedTranscript: false, bridgeReached: false, ttsPlayed: false }
        : createSession(i + 1)
    );
    const filePath = writeProofRunFile(proofRun);

    const result = validateProofRun(filePath);

    expect(result.valid).toBe(false);
    expect(result.errors.some(e => e.includes('Only 18/20 sessions succeeded'))).toBe(true);
  });

  it('rejects T4 with fewer than 5 interruption tests', () => {
    const proofRun = createMinimalProofRun('PASSED', 'T4_PHYSICAL_ANDROID_EARBUDS');
    proofRun.interruptionTests = [
      { testNumber: 1, targetMet: true },
      { testNumber: 2, targetMet: true },
      { testNumber: 3, targetMet: true },
      { testNumber: 4, targetMet: true },
    ];
    const filePath = writeProofRunFile(proofRun);

    const result = validateProofRun(filePath);

    expect(result.valid).toBe(false);
    expect(result.errors.some(e => e.includes('Expected at least 5 interruption tests'))).toBe(true);
  });

  it('rejects T4 when not all interruption targets are met', () => {
    const proofRun = createMinimalProofRun('PASSED', 'T4_PHYSICAL_ANDROID_EARBUDS');
    proofRun.interruptionTests = Array.from({ length: 5 }, (_, i) =>
      i === 0
        ? { testNumber: 1, targetMet: false }
        : { testNumber: i + 1, targetMet: true }
    );
    const filePath = writeProofRunFile(proofRun);

    const result = validateProofRun(filePath);

    expect(result.valid).toBe(false);
    expect(result.errors.some(e => e.includes('Only 4/5 interruption targets met'))).toBe(true);
  });

  it('accepts T4 when all contract requirements are satisfied', () => {
    const proofRun = createMinimalProofRun('PASSED', 'T4_PHYSICAL_ANDROID_EARBUDS');
    const filePath = writeProofRunFile(proofRun);

    const result = validateProofRun(filePath);

    expect(result.valid).toBe(true);
    expect(result.errors).toEqual([]);
  });

  it('rejects T4 PASSED artifact with physicalBluetoothProven false', () => {
    const proofRun = createMinimalProofRun('PASSED', 'T4_PHYSICAL_ANDROID_EARBUDS');
    proofRun.physicalBluetoothProven = false;
    const filePath = writeProofRunFile(proofRun);

    const result = validateProofRun(filePath);

    expect(result.valid).toBe(false);
    expect(result.errors.some(e => e.includes('T4 proof requires physicalBluetoothProven'))).toBe(true);
  });

  it('accepts proof run with feature evidence fields', () => {
    const proofRun = createMinimalProofRun('PASSED', 'T1_EMULATOR_SYNTHETIC');
    proofRun.mdnsDiscovered = true;
    proofRun.quickStartUsed = true;
    proofRun.approvalNotificationHandled = false;
    proofRun.wearTileSynced = false;
    proofRun.lockScreenDetailEnabled = true;
    const filePath = writeProofRunFile(proofRun);

    const result = validateProofRun(filePath);

    expect(result.valid).toBe(true);
  });

  it('rejects non-boolean feature evidence fields', () => {
    const proofRun = createMinimalProofRun('template', 'T1_EMULATOR_SYNTHETIC');
    // @ts-expect-error intentional wrong type
    proofRun.mdnsDiscovered = 'yes';
    // @ts-expect-error intentional wrong type
    proofRun.quickStartUsed = 1;
    const filePath = writeProofRunFile(proofRun);

    const result = validateProofRun(filePath);

    expect(result.valid).toBe(false);
    expect(result.errors.some(e => e.includes('mdnsDiscovered must be a boolean'))).toBe(true);
    expect(result.errors.some(e => e.includes('quickStartUsed must be a boolean'))).toBe(true);
  });
});
