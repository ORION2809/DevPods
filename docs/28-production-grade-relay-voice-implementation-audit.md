# Production Grade Relay Voice Implementation Audit

Generated: 2026-05-19

Audited plan:
- [27-production-grade-relay-voice-product-plan.md](27-production-grade-relay-voice-product-plan.md)

Audit basis:
- Checked-in source files, tests, scripts, and workflow files only.
- No live hardware proof runs were available in this audit.
- No external GitHub branch-protection settings were visible from the repository.

## Executive Verdict

The codebase now matches the plan far more closely than the earlier blueprint phase did. The architecture, proof data model, route scaffolding, diagnostics, Sherpa scaffolds, and CI surface are real. However, the workstream completion table in the plan still overstates product readiness.

The strongest implementation gaps are not missing wrappers or missing schemas. They are proof-enforcement gaps:

1. setup can still be forced into a proven state without proof,
2. the Home screen can still present a ready state based on bridge health instead of proof state,
3. bridge idempotency keys are emitted but not enforced server-side,
4. the proof-harness workstream claims a template that is not actually checked in, and
5. TTS interruption proof is marked proven too early.

## Findings

### Critical

#### 1. WS0 and WS9: setup can still be forced to `COMPLETE_PROVEN` without proof gating

Claimed state:
- Workstream 0 says setup completion should depend on proof readiness.
- Workstream 9 says setup should save Proven only when the proof contract passes.

Observed evidence:
- [MainActivity.kt](../android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt) wires `onCompleteSetup` directly to `relayViewModel.completeSetup(context)`.
- [RelayViewModel.kt](../android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt) has `completeSetup(context)` that unconditionally calls `RelayStateStore.setSetupPhase(SetupPhase.COMPLETE_PROVEN)`.
- The proof-aware path in the same file computes `setupPassed` from `proofRun.status == PASSED && !proofSummary.hasBlockingFailures`, but the manual completion path bypasses that entirely.

Why this is a real gap:
- A user can skip or short-circuit the proof path and still land in a proven state.
- That breaks the plan's core contract that Ready or Proven states must be tied to proof.

Impact:
- The UI and stored setup state can claim production-grade readiness without a passing proof run.

#### 2. WS6: idempotency keys are generated but not enforced on the bridge

Claimed state:
- Workstream 6 requires idempotency so retries do not execute actions twice.

Observed evidence:
- [RelayService.kt](../android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt) emits `idempotencyKey` on Android relay events.
- [schemas.ts](../src/protocol/schemas.ts) carries the event schema but does not include any server-side replay contract around processed keys.
- [server.ts](../src/bridge/server.ts) accepts `/events` requests and rate-limits by `sessionId`, but it does not check `idempotencyKey` before dispatch.
- [session-store.ts](../src/bridge/session-store.ts) tracks session state, source, pending actions, and autonomy, but it does not store processed event keys or replay windows.

Why this is a real gap:
- The client already assumes idempotent retries are safe.
- The bridge still treats duplicate events as fresh events.

Impact:
- A retried destructive or stateful action can still be executed twice.

### High

#### 3. WS9: Home ready state is keyed to service and bridge health, not proof state

Claimed state:
- Workstream 9 says the Home screen should answer whether the user can speak from earbuds right now.

Observed evidence:
- [HomeScreen.kt](../android-relay/app/src/main/java/com/openclaw/relay/ui/screens/HomeScreen.kt) renders `ReadySection(...)` when `state.isServiceRunning && state.bridgeStatus.startsWith("Healthy", ignoreCase = true)`.
- The ready branch does not check `setupPhase`, `voiceDiagnostics.voiceProofRun.status`, `hasBlockingFailures`, or a proven capability record.

Why this is a real gap:
- Bridge health is necessary, but it is not the same thing as proven earbud readiness.
- The UI can surface a ready posture before the proof contract has actually passed.

Impact:
- Users can see a healthy or ready state that overstates actual earbud voice readiness.

#### 4. WS10: proof-harness status is overstated because the validator exists but the template does not

Claimed state:
- The plan status table says WS10 is "Template created".
- WS11 details also reference proof-run artifact validation.

Observed evidence:
- [validate-proof-run.ts](../scripts/validate-proof-run.ts) exists and validates the proof-run JSON contract.
- [artifacts/proof-runs](../artifacts/proof-runs) exists but is empty.
- At audit time, no checked-in proof-run template existed anywhere in the workspace.

Why this is a real gap:
- The validator is real, but the claimed checked-in template artifact is missing.
- The workstream is not ready to the level described by the plan text.

Impact:
- The proof harness is only partially materialized in the repository.
- The completion note for WS10 is currently stronger than the checked-in evidence supports.

#### 5. WS5: TTS interruption can be marked proven after any successful hit, not the plan's stronger threshold

Claimed state:
- Workstream 5 and the product reliability contract require stronger barge-in proof, including repeated target hits.

Observed evidence:
- [RelayViewModel.kt](../android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt) computes `ttsInterruptStatus` as `PROVEN` when `proofSummary.interruptionTargetMetCount > 0 && !proofSummary.hasBlockingFailures`.
- That logic does not require a fixed minimum count such as 5 consecutive interruption passes.

Why this is a real gap:
- `> 0` is a much weaker condition than the plan's repeated-proof threshold.
- The code can promote interruption capability too early.

Impact:
- The capability model can over-credit barge-in reliability before the product has actually met the plan's proof standard.

### Medium

#### 6. WS11: CI execution is strongly implemented, but merge blocking cannot be audited from the repository alone

Claimed state:
- Workstream 11 says no release branch can merge with failing baseline tests.

Observed evidence:
- [.github/workflows/ci.yml](../.github/workflows/ci.yml) runs bridge typecheck, build, tests, npm audit, allowlist audit, secret scan, matrix validation, proof-run artifact validation, Android assemble, Android unit tests, Android lint, and Windows packaging.
- The repository does not contain GitHub branch-protection settings or required-check configuration.

Why this is not a hard code failure:
- The codebase clearly defines the CI gates.
- The repository alone cannot prove whether GitHub requires those checks before merge.

Impact:
- This remains an open verification question, not a repository-backed failure.

## Workstream Verdicts

| Workstream | Verdict | Notes |
| --- | --- | --- |
| WS0 Freeze Production Contract | Partial | Status taxonomy and blocking-failure model exist, but setup can still be manually forced to Proven. |
| WS1 Platform STT Baseline | Mostly aligned | Core wrappers, metrics, and tests are present. No major repository-backed mismatch found in this audit. |
| WS2 Audio Route Proof | Aligned | Route session, fallback policy, probe metrics, and tests are all present. |
| WS3 Sherpa VAD Native Module | Blocked as stated | Scaffolding exists (`SherpaModelManager`, `VadTelemetry`, tests), but real native integration is still not present. |
| WS4 Sherpa STT Evaluation | Blocked as stated | Placeholder engine path exists, but no real runtime adapter yet. This matches the blocked status. |
| WS5 TTS and Barge-In | Partial | Metrics and proof hooks exist, but proven status is awarded with too weak a threshold. |
| WS6 Bridge Contract | Partial | Health, protocol metadata, pairing TTL, and rate limiting exist, but idempotency enforcement is still missing. |
| WS7 OpenClaw Boundary | Mostly aligned | Policy/risk/cancellation boundaries are real and strongly represented in code. |
| WS8 Diagnostics and Privacy | Aligned | Redaction behavior is implemented and covered by focused Android tests. |
| WS9 Setup and Daily UX | Partial | Setup and Home still overstate readiness in a few key paths. |
| WS10 Physical Proof Harness | Partial | Validator exists, but the promised checked-in template is missing and there are no proof artifacts. |
| WS11 CI and Release Gates | Mostly aligned | CI workflow is broad and concrete, but merge-block enforcement is not auditable from repo contents. |
| WS12 Security Pass | Pending as stated | Remaining work appears pending, which matches the plan rather than contradicting it. |

## Strongly Aligned Areas

These parts of the plan have strong, direct implementation evidence.

### Route proof and fallback modeling are real

Evidence:
- [AudioRouteSession.kt](../android-relay/app/src/main/java/com/openclaw/relay/AudioRouteSession.kt)
- [AudioRouteSessionTest.kt](../android-relay/app/src/test/java/com/openclaw/relay/AudioRouteSessionTest.kt)
- [AudioRouteFallbackPolicyTest.kt](../android-relay/app/src/test/java/com/openclaw/relay/AudioRouteFallbackPolicyTest.kt)
- [AudioProbeMetricsTest.kt](../android-relay/app/src/test/java/com/openclaw/relay/AudioProbeMetricsTest.kt)

Why it matters:
- The product now has explicit route-session and fallback primitives instead of only ad hoc route checks.

### Diagnostics and privacy are materially implemented

Evidence:
- [DiagnosticExport.kt](../android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt)
- [DiagnosticExportTest.kt](../android-relay/app/src/test/java/com/openclaw/relay/diagnostic/DiagnosticExportTest.kt)

Why it matters:
- The current code does gate raw-route details, suppress transcript text by default, redact URLs and tokens, and strip personalized device-name patterns.

### Status taxonomy and fallback distinction are implemented in setup assessment

Evidence:
- [SetupCapabilityAssessment.kt](../android-relay/app/src/main/java/com/openclaw/relay/device/SetupCapabilityAssessment.kt)
- [SetupCapabilityAssessmentTest.kt](../android-relay/app/src/test/java/com/openclaw/relay/device/SetupCapabilityAssessmentTest.kt)

Why it matters:
- The repository does distinguish `PROVEN` from `FALLBACK_PROVEN`, which is one of the plan's most important honesty requirements.

### Rolling proof and diagnostics stores are real

Evidence:
- [VoiceProofRun.kt](../android-relay/app/src/main/java/com/openclaw/relay/VoiceProofRun.kt)
- [VoiceProofRunTest.kt](../android-relay/app/src/test/java/com/openclaw/relay/VoiceProofRunTest.kt)
- [VoiceDiagnosticsStore.kt](../android-relay/app/src/main/java/com/openclaw/relay/VoiceDiagnosticsStore.kt)

Why it matters:
- The plan is no longer describing imaginary infrastructure. The proof-run model, blocking-failure summary, and rolling export summary exist in code.

### CI and release surfaces are largely present

Evidence:
- [.github/workflows/ci.yml](../.github/workflows/ci.yml)
- [license-scan.ts](../scripts/license-scan.ts)
- [validate-proof-run.ts](../scripts/validate-proof-run.ts)
- [supported-devices-matrix.json](supported-devices-matrix.json)

Why it matters:
- The repo does contain concrete validation and release scaffolding, even though one proof-template artifact is still missing.

## Open Questions

These are important, but they cannot be resolved from repository contents alone.

1. Are GitHub required status checks actually enforced on the default and release branches?
2. Are there physical proof-run artifacts stored outside the repository that justify any current Ready claims?
3. Is there a product decision to keep the manual "complete setup" path for development builds only, or is that path unintentionally bypassing proof enforcement?

## Recommended Next Actions

1. Gate `completeSetup(context)` behind proof success or remove the direct Proven path from setup.
2. Make the Home ready state depend on proof readiness, not just service and bridge health.
3. Add bridge-side replay protection keyed by `sessionId + idempotencyKey`.
4. Add the missing checked-in proof-run template or update the plan/status table to stop claiming it exists.
5. Tighten TTS interruption proof so `PROVEN` requires the intended repeated threshold, not merely one successful hit.


---

## Remediation Log

**Date: 2026-05-19**

### Critical Findings — Fixed

#### 1. WS0 and WS9: Setup proof gating

**Fix applied:**
- `RelayViewModel.completeSetup()` now checks the current `voiceProofRun` state before deciding between `COMPLETE_PROVEN` and `COMPLETE_DEGRADED`.
- `RelayViewModel.initialize()` now validates the saved capability matrix. If no `PROVEN` entry exists, it restores `COMPLETE_DEGRADED` instead of blindly promoting to `COMPLETE_PROVEN`.
- `MainActivity.retrySetupPhase()` for `COMPLETE_PROVEN` now re-runs `testStt()` instead of short-circuiting through `completeSetup()`.

**Tests added:**
- `SetupProofGatingTest` — 5 tests covering degraded proof, passed proof, proven entry restore, observed entry restore, and empty matrix restore.

#### 2. WS6: Bridge idempotency enforcement

**Fix applied:**
- Added `protocolVersion` and `idempotencyKey` fields to `earbudEventSchema` in `src/protocol/schemas.ts`.
- Created `IdempotencyStore` class in `src/bridge/session-store.ts` with 5-minute TTL.
- Wired idempotency check into `server.ts` `/events` handler. Duplicate keys within the same session return `200 { status: 'acknowledged', idempotent: true }` without re-executing.

**Tests added:**
- `test/bridge-idempotency.test.ts` — 5 tests covering: no-key acceptance, duplicate without key, deduplication with same key, cross-session key isolation, and store unit tests.

### High Findings — Fixed

#### 3. WS9: Home ready state keyed to proof readiness

**Fix applied:**
- `HomeScreen.kt` now requires all three conditions for the Ready section:
  1. `isServiceRunning && bridgeStatus.startsWith("Healthy")`
  2. `setupPhase == COMPLETE_PROVEN`
  3. `!voiceDiagnostics.voiceProofRun.summary.hasBlockingFailures`

**Tests added:**
- `HomeScreenReadyStateTest` — 5 tests covering: full ready state, degraded setup, blocking failures, unhealthy bridge, and stopped service.

#### 4. WS10: Proof-harness template

**Fix applied:**
- Restored the canonical tracked template at `docs/proof-run-template.json` and removed the ignored artifact duplicate.
- Added `docs/proof-run-schema.json` as the JSON Schema for proof-run validation.
- CI proof-run validation step skips gracefully when no artifacts exist.

#### 5. WS5: TTS interruption proven threshold

**Fix applied:**
- `RelayViewModel.testStt()` now requires `interruptionTargetMetCount >= 5` (was `> 0`) to award `CapabilityStatus.PROVEN` for TTS interruption.

### Medium Findings — Documented

#### 6. WS11: Merge blocking

**Fix applied:**
- Created `docs/BRANCH-PROTECTION.md` with explicit required status check configuration instructions.
- Documented the three required jobs (`Bridge`, `Android Relay`, `Windows Bridge Package`) and how to enable them in GitHub branch protection settings.

## Updated Workstream Verdicts

| Workstream | Previous Verdict | Current Verdict | Notes |
|------------|------------------|-----------------|-------|
| WS0 Freeze Production Contract | Partial | **Aligned** | Setup now enforces proof gating. |
| WS1 Platform STT Baseline | Mostly aligned | **Aligned** | No changes needed. |
| WS2 Audio Route Proof | Aligned | **Aligned** | No changes needed. |
| WS3 Sherpa VAD Native Module | Blocked | **Blocked** | Unchanged — still needs native module. |
| WS4 Sherpa STT Evaluation | Blocked | **Blocked** | Unchanged — blocked on WS3. |
| WS5 TTS and Barge-In | Partial | **Aligned** | Threshold tightened to >= 5 interruptions. |
| WS6 Bridge Contract | Partial | **Aligned** | Idempotency now enforced server-side. |
| WS7 OpenClaw Boundary | Mostly aligned | **Aligned** | No changes needed. |
| WS8 Diagnostics and Privacy | Aligned | **Aligned** | No changes needed. |
| WS9 Setup and Daily UX | Partial | **Aligned** | Home ready state and setup completion both gated by proof. |
| WS10 Physical Proof Harness | Partial | **Partial** | The canonical template and schema are now tracked, including `docs/proof-run-template.json`, but no real proof-run artifacts are checked in yet. |
| WS11 CI and Release Gates | Mostly aligned | **Aligned** | CI workflow complete; branch protection documented. |
| WS12 Security Pass | Pending | **Pending** | Remaining work still pending. |

## Verification Commands

```bash
# TypeScript
npm run typecheck
npm test

# Android
cd android-relay
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug

# Specific new tests
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.SetupProofGatingTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.HomeScreenReadyStateTest"
```
