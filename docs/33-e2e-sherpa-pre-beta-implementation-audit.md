# E2E, Sherpa/Silero, And Pre-Beta Implementation Audit

Generated: 2026-05-21

## Scope

This audit checks the current codebase against:

- `docs/30-silero-vad-sherpa-onnx-perfect-implementation-plan.md`
- `docs/31-emulator-and-physical-e2e-automation-plan.md`
- `docs/32-pre-beta-release-gap-audit.md`

The audit is based on code, scripts, tests, CI configuration, and local verification commands. It does not include a fresh physical Android plus earbuds T4 run.

## Executive Verdict

The implementation is no longer "Sherpa/Silero scaffold-only." There is a real `:sherpa-runtime` module, JNI libraries are present for `arm64-v8a` and `x86_64`, native checksums are verified during Gradle prebuild, the app has real Sherpa VAD/STT classes, model management has checksum and rollback behavior, TTS audio focus exists, Speak Now readiness exists, and T1/T1_PCM/T2 simulation infrastructure is real.

It is still not beta-ready.

The remaining blockers are product-grade blockers, not polish:

1. No T4 physical proof runner or validated physical proof artifact exists.
2. Release-facing `artifacts/proof-runs/` is empty.
3. Simulation proof artifacts do not match the canonical proof-run validator contract.
4. Sherpa STT appears unable to become the active production engine through the normal config path because production readiness still hardcodes `nativeDependencyLinked = false`.
5. Microphone capture ownership is only partially integrated, so the "SpeechRecognizer and Sherpa AudioRecord never overlap" guarantee is not satisfied.
6. CI still allows proof validation to skip when no proof artifacts exist and has no beta/release mode requiring a current T4 proof.
7. Bridge idempotency and protocol version remain optional at the schema boundary.

## Verification Performed

| Gate | Result | Notes |
| --- | --- | --- |
| `npm run typecheck` | Pass | TypeScript typecheck passed. |
| `npm run build` | Pass | Bridge build passed. |
| `npm test` | Pass | 124 tests passed, 7 skipped. |
| `npm run audit:allowlist` | Pass | 0 package vulnerabilities at moderate threshold. |
| `npm audit --audit-level=moderate` | Pass | 0 vulnerabilities. |
| `npx tsx scripts/license-scan.ts` | Pass | No forbidden licenses detected. |
| `npx tsx scripts/validate-proof-run.ts docs/proof-run-template.json` | Pass | Template is now valid as a template. |
| `.\gradlew.bat :sherpa-runtime:assembleDebug :sherpa-runtime:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` | Pass | Android build, unit tests, lint, and `verifySherpaNativeLibs` passed. |
| `npx tsx scripts/validate-proof-run.ts simulation/android-relay/proof-runs/codex-t1-pcm-20260520-verify2/codex-t1-pcm-20260520-verify2.json` | Fail | Fails as `Invalid JSON` because the pulled PowerShell artifact contains a UTF-8 BOM. Even after BOM removal, its shape differs from the canonical proof contract. |

## Status Against Doc 31: Emulator And Physical E2E Automation

| Requirement | Status | Evidence |
| --- | --- | --- |
| T1 emulator synthetic proof | Implemented | `simulation/android-relay/run-t1-proof.ps1`, `DebugProofRunnerReceiver`, `SyntheticSpeechInputEngine`, existing T1 proof runs under `simulation/android-relay/proof-runs/`. |
| T1_PCM deterministic PCM injection | Implemented but release contract mismatch | `simulation/android-relay/run-pcm-proof.ps1`, `PcmInjectionSpeechInputEngine`, `PcmInjectionSpeechInputEngineTest`; existing T1_PCM run passed 5/5 locally, but canonical validator rejects the artifact. |
| T2 emulator host-audio harness | Implemented as diagnostic | `simulation/android-relay/run-t2-proof.ps1`, VB-Cable installer, capture scripts, report generation. Existing T2 artifacts honestly show STT/route failures in the emulator path while barge-in can pass. |
| HTML proof reports and UI captures | Implemented for simulation | `capture-android-ui.ps1`, `capture-bridge-ui.ts`, `generate-proof-report.ts`, report artifacts under `simulation/android-relay/proof-runs/`. |
| T4 physical Android plus earbuds proof | Missing | No `run-t4-proof.ps1`, no physical proof runner, no T4 artifact, no populated release matrix row. |
| Canonical proof JSON contract | Partial | `docs/proof-run-template.json`, `docs/proof-run-schema.json`, and `scripts/validate-proof-run.ts` exist, but simulation artifacts use a different schema and can be BOM-prefixed. |
| CI/release proof gate | Missing | `.github/workflows/ci.yml` skips when `artifacts/proof-runs/` is missing or empty and does not call the shared validator. No beta/release mode requires T4. |
| "Only T4 can mark a combo physically proven" | Documented but not enforced | `docs/release-matrix.md` says this, but no code or CI gate enforces it. |

### Doc 31 Finding

Doc 31 is implemented for the emulator simulation lane, but not for the release lane. The current proof system can create useful development artifacts, but the beta gate still has no physical proof artifact, no validator-compatible generated proof JSON, and no CI requirement that a physical proof exists.

## Status Against Doc 30: Silero VAD And Sherpa-ONNX

| Requirement | Status | Evidence |
| --- | --- | --- |
| Native Sherpa runtime module | Mostly implemented | `android-relay/sherpa-runtime`, JNI libraries, Kotlin API wrappers, `SherpaNativeLoader`, `SherpaRuntimeAvailability`, Gradle checksum task. |
| Provenance and checksums | Partial | `docs/sherpa-onnx-provenance.md` exists and checksums are wired in Gradle; reviewer is still "Maintainer review pending" and the STT model license line still says verify per-model. |
| Model catalog and checksum-verified install | Mostly implemented | `SherpaModelSpec`, `SherpaModelInspector`, `SherpaModelManager` include type-aware model specs, trusted URL checks, low-storage preflight, temp install, metadata, rollback, and checksums. |
| No raw audio storage | Appears satisfied in Sherpa classes | `SherpaVadProbe` and `SherpaSpeechInputEngine` process buffers in memory and do not persist PCM. |
| Audio capture ownership | Partial | `AudioCaptureOwner` exists, and Sherpa VAD/STT acquire it. Platform STT and `AudioRecordRouteProbe` do not acquire it, so the global non-overlap contract is not met. |
| Real Silero VAD | Partial | `SherpaVadProbe` uses Sherpa `Vad` over `AudioRecord`, but has no direct unit/instrumented tests, typed failure taxonomy, or physical proof. |
| Real Sherpa STT | Partial with activation risk | `SherpaSpeechInputEngine` uses `OnlineRecognizer`, but production readiness hardcodes `nativeDependencyLinked = false`, so `readiness.canRunOffline` remains false in the normal factory path. |
| Platform STT remains default | Implemented | `SpeechInputMode.PLATFORM` is the default and Sherpa flags default off. |
| Clean fallback behavior | Partial | The app falls back to platform STT with a visible error, but the reason comes from legacy readiness and can hide actual Sherpa runtime/model status. |
| Benchmarks and promotion gates | Missing | `OfflineSpeechBenchmark` exists, but there is no complete benchmark runner, physical benchmark artifact, or promotion gate proving Sherpa is better than platform STT. |
| Developer UX for model install/run/clear | Missing | Config flags and storage exist, but UI search found no Sherpa/offline model install or clear controls in the main UI screens. |
| CI build matrix and size/ABI guard | Missing | The app depends on `project(":sherpa-runtime")` unconditionally. There is no default-vs-Sherpa flavor matrix, artifact size budget, or release ABI guard. |

### Doc 30 Finding

The Sherpa/Silero work has moved from scaffold to an experimental native integration. It is not yet production-grade. The most important issue is not native linking anymore; it is operational correctness: activation, capture exclusivity, proof, benchmarks, user-facing controls, and release gating.

## Status Against Doc 32: Pre-Beta Gap Audit

Doc 32 is now stale in several positive ways:

- The proof template now validates as a template.
- Bridge typecheck/build/tests/audit/license gates are green locally.
- Android Gradle assemble/unit/lint gates are green locally.
- TTS audio focus and interruption metrics are implemented.
- Speak Now readiness exists and blocks/degrades for several real conditions.
- Sherpa native runtime and model-management code exist.
- T1_PCM proof infrastructure exists.

But Doc 32 remains accurate on the release blockers:

- No physical T4 artifact exists.
- `docs/release-matrix.md` still has only `Pending first proof`.
- `artifacts/proof-runs/` is empty.
- CI proof validation still skips empty proof artifacts.
- Idempotency and protocol version are still optional in `src/protocol/schemas.ts`.
- No strict beta gate requires a current T4 physical proof.

## Blocking Findings

### P0-1: No Physical T4 Proof Exists

There is no physical-device proof runner and no release-facing T4 artifact. `docs/release-matrix.md` still contains only the pending placeholder, and `artifacts/proof-runs/` is empty.

Impact: the product cannot honestly claim a phone/earbud combination is beta-ready or physically proven.

Required fix:

- Add `simulation/android-relay/run-t4-proof.ps1` or equivalent.
- Support `-DeviceSerial`, `-EarbudModel`, `-ProviderId`, `-PhoneModel`, and artifact metadata.
- Run 20 wake-to-command sessions, 5 barge-in tests, disconnect/reconnect, app process death, bridge restart, and diagnostic privacy review.
- Save a redacted validator-clean artifact under `artifacts/proof-runs/`.
- Update `docs/release-matrix.md` from that artifact only.

### P0-2: Proof Artifacts Have Two Incompatible Dialects

The canonical validator expects fields like `proofRunId`, `startedAt`, `phoneModel`, `androidVersion`, `earbudModel`, `providerId`, `wakePath`, `inputPath`, `outputPath`, `engineId`, `bridgeMode`, and `sessions[].sessionNumber`.

The simulation artifacts use fields like `artifactId`, `proofTier`, `timestamp`, `successfulSessionCount`, `targetSessionCount`, and per-session `index`, `routeState`, `routeSelectedDeviceType`, `sttSucceeded`.

Additionally, `run-t1-proof.ps1` pulls artifacts with PowerShell `Set-Content -Encoding UTF8`, which produced a BOM-prefixed JSON file on this machine. `scripts/validate-proof-run.ts` fails that artifact as `Invalid JSON`.

Impact: the harness can say "passed" while the release validator cannot consume the artifact.

Required fix:

- Make all proof generators emit one canonical schema.
- Write UTF-8 without BOM.
- Include `proofTier`, `routeProofSource`, `physicalBluetoothProven`, `inputPath`, `outputPath`, app/bridge build hashes, and redaction metadata in the canonical schema.
- Update `scripts/validate-proof-run.ts` to validate tier-specific requirements.
- Add a beta mode that fails unless at least one current `T4_PHYSICAL_ANDROID_EARBUDS` artifact has `physicalBluetoothProven: true`.

### P0-3: Sherpa STT Is Implemented But Likely Not Activatable

`SpeechInputEngineFactory.create()` only returns `SherpaSpeechInputEngine` when `readiness.canRunOffline` is true. The production `OfflineSpeechReadiness.evaluate(config)` path builds `legacyResult` with `nativeDependencyLinked = false`, then copies Sherpa readiness into it without recomputing `canRunOffline`.

Impact: even with native libraries and models present, selecting `SHERPA_EVALUATION` can fall back to platform STT because the legacy readiness path says the native dependency is missing.

Required fix:

- Compute `nativeDependencyLinked` from `SherpaRuntimeAvailability.probe()`.
- Use `SherpaReadiness.sttReady` to determine Sherpa activation.
- Add unit tests proving:
  - Ready native runtime plus ready STT model selects `SherpaSpeechInputEngine`.
  - Missing native library falls back to platform STT with a typed reason.
  - Missing or checksum-failed model falls back to platform STT with a typed reason.

### P0-4: Microphone Capture Exclusivity Is Not Globally Enforced

`AudioCaptureOwner` exists and is used by `SherpaVadProbe` and `SherpaSpeechInputEngine`, but platform `SpeechRecognizer` and `AudioRecordRouteProbe` do not acquire the same lease.

Impact: the core Doc 30 guarantee is not met: platform STT, route probing, Sherpa VAD, and Sherpa STT can still overlap in some paths.

Required fix:

- Inject `AudioCaptureOwner` into `PlatformSpeechRecognizerEngine`.
- Inject it into `AudioRecordRouteProbe`.
- Require leases for all microphone users.
- Add tests for STT-vs-probe, STT-vs-VAD, VAD-vs-probe, cancellation, timeout release, and process restart cleanup.

### P0-5: CI Does Not Enforce Release Proof

The CI workflow has an inline proof validation step that skips when `artifacts/proof-runs/` does not exist or contains no JSON files. It does not call `scripts/validate-proof-run.ts`, and it has no mode that requires a T4 artifact.

Impact: a beta branch can pass CI with no physical proof.

Required fix:

- Replace inline validation with the shared validator.
- Add `scripts/validate-proof-run.ts --require-tier T4_PHYSICAL_ANDROID_EARBUDS --max-age-days 7` or equivalent.
- Make release/beta workflows fail on missing proof artifacts.
- Keep simulation proof as a dev gate, not a substitute for T4.

## High Priority Findings

### P1-1: Bridge Idempotency And Protocol Version Are Still Optional

`src/protocol/schemas.ts` still marks `protocolVersion` and `idempotencyKey` optional. `src/bridge/server.ts` deduplicates only when an idempotency key exists, and tests still cover accepting duplicate events without keys.

Impact: duplicate unsafe events can still pass through the bridge contract if a client omits the key.

Required fix:

- Require `protocolVersion` and `idempotencyKey` for Android relay events.
- Reject unsupported protocol versions.
- Reject missing idempotency keys for non-read-only events.
- Keep only health/pairing/status endpoints exempt if needed.

### P1-2: Sherpa Runtime Is Linked Into The Default App Without A Release Matrix

`android-relay/app/build.gradle.kts` unconditionally includes `implementation(project(":sherpa-runtime"))`. The provenance doc says default release is "linked but feature-flagged off."

Impact: the default APK carries native runtime size and native loading risk before the Sherpa path is beta-proven.

Required fix:

- Add build flavors such as `platformOnly` and `sherpaInternal`, or add a deliberate signed-off decision that default beta includes Sherpa.
- Add artifact size and ABI checks.
- Prevent emulator-only `x86_64` native libraries from inflating physical-device release builds unless intentionally shipped.

### P1-3: Sherpa VAD Metrics Need Production Hardening

`SherpaVadProbe` is real, but several details are not production-grade yet:

- `speechStartDelayMs` is set to an absolute timestamp, not elapsed delay from probe start.
- `rmsFramesAboveNoiseFloor` currently maps to speech-window count, not an RMS noise-floor metric.
- Failure callbacks are user-facing strings, not typed proof/diagnostic codes.
- No direct tests cover runtime load failure, missing model, checksum mismatch, mic busy, permission denied, no signal, wrong mic suspected, and VAD speech detection.

Impact: diagnostics can be misleading exactly when the user needs them to explain a route or microphone failure.

Required fix:

- Fix elapsed timing.
- Separate VAD speech windows from RMS/noise-floor metrics.
- Emit typed failure reasons.
- Add unit/instrumented tests with fake runtime/model/status/audio providers.

### P1-4: No Production Sherpa Benchmark Or Promotion Gate Exists

There is no physical benchmark artifact comparing platform STT, platform on-device STT, and Sherpa STT across latency, accuracy, endpointing, battery, CPU, memory, and barge-in behavior.

Impact: Sherpa cannot be safely promoted as default or recommended.

Required fix:

- Add an internal benchmark runner.
- Store benchmark artifacts with model/runtime checksums.
- Require better-or-equal reliability before exposing Sherpa outside developer mode.

### P1-5: No User-Facing Sherpa Model Management UX Exists

Config flags and model storage exist, but there is no complete UI flow for model install, checksum status, low-storage handling, retry, clear model, or fallback explanation.

Impact: users can get stuck in an unavailable Sherpa mode with support-only diagnostics.

Required fix:

- Add a developer/offline speech panel.
- Show runtime status, model status, checksum status, storage footprint, and active engine.
- Keep platform STT as the obvious safe fallback.

## Edge Case Coverage Matrix

| Edge Case | Current Status |
| --- | --- |
| Bridge restart mid-session | Partially covered in plan/scripts, not T4-proven. |
| App process death | Not T4-proven. |
| Android permission revoked | Unit coverage exists in pieces, not T4-proven. |
| STT timeout/no speech | Partially handled in engines and proof summaries. |
| Low input signal | T2 diagnostics exist, but T2 has known emulator limits and failed STT in existing artifact. |
| Wrong mic suspected | Metrics exist, but no physical proof and VAD heuristic needs hardening. |
| Earbuds disconnected/reconnected | Not T4-proven. |
| TTS start failure/focus loss | Metrics and audio focus exist; physical proof missing. |
| TTS barge-in miss | Metrics and proof failure reason exist; existing T2 direct artifact hit 5/5 interruption targets, but physical proof missing. |
| TTS feedback loop into STT | Not physically proven. |
| Raw transcript/audio export disabled | Diagnostic redaction tests exist; physical export review missing. |
| Stale proof after app/bridge/Android change | Speak Now degrades stale proof, but CI/release does not enforce proof freshness. |
| Different earbud model after proof | Speak Now can degrade on selected device type mismatch, but no T4 artifact/model binding exists. |
| Sherpa native load failure | Runtime probe exists; factory activation/fallback needs tests. |
| Sherpa model checksum mismatch | Model manager and inspector support it; user UX and proof integration incomplete. |
| Platform STT and Sherpa/route-probe overlap | Not resolved. |

## What Is Ready

The following can be treated as usable engineering infrastructure:

- Bridge TypeScript build/test/audit lane.
- Android app debug build/unit/lint lane.
- Sherpa native checksum verification during Gradle prebuild.
- T1 synthetic proof harness for fast emulator regression.
- T1_PCM deterministic PCM injection harness for STT callback contract regression.
- T2 host-audio harness for diagnostic exploration.
- HTML proof reports for simulation artifacts.
- TTS audio focus metrics.
- Speak Now readiness computation.
- Diagnostic redaction foundations.

## What Is Not Ready

The following should not be treated as beta-ready:

- Physical earbud reliability claims.
- Release matrix claims for specific earbuds.
- Sherpa STT as a production option.
- Silero VAD as a proven route/mic validator.
- CI as a beta release gate.
- Proof artifacts as a single source of truth.
- Strict event replay protection.

## Recommended Next Work Before Beta

1. Unify proof JSON.
   - Fix BOM output.
   - Generate one canonical proof contract from T1, T1_PCM, T2, and T4.
   - Add tier-specific validation.

2. Build the T4 physical runner.
   - Use a real Android device serial.
   - Collect 20 sessions, 5 barge-in trials, disconnect/reconnect, app death, bridge restart, and diagnostic privacy review.
   - Save to `artifacts/proof-runs/`.

3. Make CI fail without physical proof in beta/release mode.
   - Use the shared validator.
   - Require current T4.
   - Require release matrix row consistency.

4. Fix Sherpa activation.
   - Replace legacy `nativeDependencyLinked = false` in production readiness.
   - Gate activation on real runtime and model readiness.
   - Add selection/fallback tests.

5. Make microphone capture ownership universal.
   - Platform STT, route probe, Sherpa VAD, and Sherpa STT must all lease the same coordinator.
   - Add contention and release tests.

6. Harden bridge protocol.
   - Require idempotency keys and protocol versions.
   - Reject duplicate unsafe events.
   - Keep explicit exemptions small and tested.

7. Add Sherpa developer UX.
   - Install/check/clear models.
   - Show runtime/model/checksum/storage status.
   - Explain fallback clearly.

8. Add Sherpa physical benchmark and promotion gate.
   - Compare platform STT and Sherpa on real devices.
   - Record latency, endpointing, reliability, battery, memory, and CPU.
   - Keep Sherpa disabled for beta until it wins or justifies its tradeoff.

## Beta Release Call

Current state: not ready for public beta.

Best next milestone: produce one validator-clean T4 physical artifact for a named Android phone plus real earbuds, wire that artifact into CI/release validation, and then fix the Sherpa activation/capture-ownership gaps before exposing offline speech beyond developer mode.

