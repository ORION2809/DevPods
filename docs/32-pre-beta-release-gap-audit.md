# Pre-Beta Release Gap Audit

Generated: 2026-05-20

## Scope

This document audits the implementation of:

- [30-silero-vad-sherpa-onnx-perfect-implementation-plan.md](30-silero-vad-sherpa-onnx-perfect-implementation-plan.md)
- [31-emulator-and-physical-e2e-automation-plan.md](31-emulator-and-physical-e2e-automation-plan.md)

Against the current codebase and the requirements of:

- [29-beta-release-reliability-audit-and-plan.md](29-beta-release-reliability-audit-and-plan.md)

## Executive Verdict

| Plan | Status | Summary |
|------|--------|---------|
| Doc 30 (Sherpa/Silero) | **Scaffold-only** | Module, flags, and catalog split exist. No native runtime, real engines, capture ownership, or physical proof. |
| Doc 31 (E2E Automation) | **Mostly implemented** for T0-T2/T1_PCM | Emulator harness is real, automated, and honest about limits. T4 physical proof is pending. |
| Doc 29 (Beta Readiness) | **Blockers remain open** | Node tests and security allowlist are red. No physical proof artifact exists. |

---

## Detailed Audit: Doc 30 (Sherpa/Silero)

### Step 0: Green Gates

| Gate | Result |
|------|--------|
| `npm test` | **FAIL** - 124/129 passed; 5 OpenClaw tests fail on missing `@mariozechner/pi-agent-core` |
| `npm run audit:allowlist` | **FAIL** - Stale `@mistralai/mistralai` exception |
| `npm run typecheck` / `build` | Pass |
| Android unit / lint / assemble | Pass |
| Proof template valid | **FAIL** - `docs/proof-run-template.json` has `status: "passed"` with 1 session |

**Verdict:** Not satisfied. Same P0 blockers from Doc 29 are still present.

### Step 1: Sherpa Runtime Module

| Requirement | Status | Evidence |
|-------------|--------|----------|
| `:sherpa-runtime` module exists | | `android-relay/sherpa-runtime/` created |
| `settings.gradle.kts` includes module | | `include(":sherpa-runtime")` |
| `build.gradle.kts` configured | | Library module exists |
| Namespace `com.openclaw.relay.sherpa` | | Set correctly |
| `jniLibs/arm64-v8a` with native libs | **MISSING** | Directory does not exist |
| Kotlin API wrappers (`Vad`, `OnlineRecognizer`) | **MISSING** | Not present |
| `SherpaRuntimeAvailability` | **STUB** | Exists but never attempts native load; hardcodes `isNativeLibraryLoadable = false` |
| ProGuard keep rules | | `consumer-rules.pro` present |
| App dependency wired | | `app/build.gradle.kts` line 79 |

**Verdict:** Empty shell. No `sherpa-onnx-jni` libraries vendored.

### Step 2: VAD/STT Model Catalog Split

| Requirement | Status |
|-------------|--------|
| `SherpaModelType` enum | |
| `SherpaModelSpec` with all required fields | |
| Built-in catalog entries (VAD + STT) | |
| `SherpaModelManager` | **PARTIAL** - Missing atomic temp dir, install marker, rollback, low-storage preflight, partial download cleanup, list-by-type |
| Legacy `OfflineSpeechModelSpec` compatibility | |
| Unit tests | |

**Verdict:** Core catalog and inspection done. Manager lacks production-grade download atomicity.

### Step 3: Audio Capture Ownership

| Requirement | Status |
|-------------|--------|
| `AudioCaptureOwner` / `MicCaptureCoordinator` | **MISSING** |
| Platform STT integration | **MISSING** |
| Route probe integration | **MISSING** |
| Sherpa VAD/STT integration | **MISSING** |
| Typed failure reasons (`mic_capture_busy`, etc.) | **MISSING** |

**Verdict:** Critical gap. This is explicitly called "the biggest reliability bug to avoid" in Doc 30.

### Step 4: Real Silero VAD Probe

| Requirement | Status |
|-------------|--------|
| Real `SherpaVadProbe` implementation | **MISSING** - Current is placeholder |
| `SherpaVadConfig` | **MISSING** |
| `AudioRecord` capture loop | **MISSING** |
| PCM conversion & `Vad.acceptWaveform` | **MISSING** |
| Telemetry / diagnostic observations | **MISSING** |

### Step 5: VAD In Proof Runs And Diagnostics

| Requirement | Status |
|-------------|--------|
| VAD fields in proof summary | **MISSING** |
| Blocking failure reasons | **MISSING** |
| Diagnostic export includes VAD | **MISSING** |
| Developer Mode VAD UI | **MISSING** |

### Step 6: Real Sherpa Streaming STT

| Requirement | Status |
|-------------|--------|
| Real `SherpaSpeechInputEngine` | **MISSING** - Current is placeholder |
| `OnlineRecognizerConfig` | **MISSING** |
| `AudioRecord` capture loop | **MISSING** |
| Session lifecycle callbacks | **MISSING** |
| Platform fallback | **PARTIAL** - Fallback exists but Sherpa can never be ready |

### Step 7: Benchmark And Compare

- `OfflineSpeechBenchmark` referenced in tests.
- No executable comparison possible because Sherpa cannot run.

### Step 8: Feature Flags And UX

| Requirement | Status |
|-------------|--------|
| `SherpaFeatureFlags` | |
| Developer Mode controls (install/run/clear) | **MISSING** |
| Status labels | **MISSING** |
| Normal Settings toggle blocked | |

### Step 9: CI, Artifacts, And Supply Chain

| Requirement | Status |
|-------------|--------|
| `docs/sherpa-onnx-provenance.md` | **MISSING** |
| Native artifact checksum validation in CI | **MISSING** |
| Build matrix (default vs Sherpa-enabled) | **MISSING** |
| Artifact-size check | **MISSING** |
| Smoke test loading native lib | **MISSING** |

### Step 10: Physical Proof And Beta Promotion

| Requirement | Status |
|-------------|--------|
| Real proof artifacts under `artifacts/proof-runs/` | **MISSING** |
| `docs/release-matrix.md` updated with Sherpa rows | **MISSING** |

---

## Detailed Audit: Doc 31 (E2E Automation)

### Phase 0: Green Gates

Doc 31 marks Phase 0 as "Complete" but Doc 29 correctly identifies `npm test` and `npm run audit:allowlist` as still failing. The E2E plan accepts pre-existing OpenClaw env failures; the beta audit treats them as P0 blockers.

| Gate | Android | Node |
|------|---------|------|
| TypeScript typecheck / build | N/A | |
| Node tests | N/A | **FAIL** (5 OpenClaw env failures) |
| Security allowlist | N/A | **FAIL** |
| Android unit tests | | N/A |
| Android lint | | N/A |
| Android assemble | | N/A |

### Phase 1: Environment Probe Harness

| Requirement | Status |
|-------------|--------|
| `probe-e2e-environment.ps1` | |
| Emits `environment.json` | |
| Classifies machine tier | |

### Phase 2: Debug Proof Runner

| Requirement | Status |
|-------------|--------|
| `DebugProofRunnerReceiver` in `src/debug/java` | |
| `FLAG_DEBUGGABLE` guarded | |
| `RUN_PROOF` / `EXPORT_PROOF` actions | |
| Reflection-isolated release builds | |
| App-private artifact storage | |

### Phase 3: Synthetic Engines

| Requirement | Status |
|-------------|--------|
| `SyntheticSpeechInputEngine` | |
| `SyntheticSpeechOutputEngine` | |
| Full lifecycle emulation | |
| Failure injection | |
| Barge-in `STOPPED` metrics | |

### Phase 4: Host Audio Injection (T2)

| Requirement | Status |
|-------------|--------|
| `run-t2-proof.ps1` | |
| `play-t2-audio.ps1` | |
| `install-vb-cable.ps1` | |
| VB-Cable loopback verified | |
| App-side STT hardening (12s watchdog) | |
| Barge-in 5/5 target hits recorded | |
| **Honest blocker documented** | - Google/SODA STT in emulator does not produce stable transcripts from virtual-cable fixtures |

**Verdict:** T2 is a diagnostic stress test, not beta release proof. Correctly labeled.

### Phase 4.5: Android PCM Injection

| Requirement | Status |
|-------------|--------|
| `PcmInjectionSpeechInputEngine` in `src/debug/java` | |
| `run-pcm-proof.ps1` / `npm run proof:pcm` | |
| Unit test coverage | |
| `storesRawAudio = false` | |

### Phase 5: Playwright / UI Artifact Runner

| Requirement | Status |
|-------------|--------|
| `capture-android-ui.ps1` | |
| `capture-bridge-ui.ts` | |
| `generate-proof-report.ts` | |
| Self-contained HTML report with base64 images | |
| End-to-end pipeline verified | |

### Phase 6: Physical Device Runner (T4)

| Requirement | Status |
|-------------|--------|
| Device-selection support (`adb -s`) | **MISSING** |
| Physical proof checklist automation | **MISSING** |
| T4 artifact production | **MISSING** |

### Phase 7: CI And Release Gates

| Requirement | Status |
|-------------|--------|
| T1 in CI | **MISSING** |
| T2 on labeled audio-lab machine | **MISSING** |
| T4 as manual release gate | **MISSING** |
| Proof artifact validation in CI | **MISSING** |
| Trend summaries (p50/p95) | **MISSING** |

---

## Pre-Beta Release Gaps

These gaps must be closed before the application can enter a controlled beta honestly.

### P0 - Repository Gates Must Be Green

| # | Gap | Required Fix | Owner |
|---|-----|--------------|-------|
| 1 | `npm test` fails (5 OpenClaw tests) | Make `@mariozechner/pi-agent-core` dependency explicit, or skip gateway-client tests when runtime is absent | Bridge / Node |
| 2 | `npm run audit:allowlist` fails | Remove stale `@mistralai/mistralai` exception, or restore the pinned package | Security / Node |
| 3 | Proof template is invalid | Change `docs/proof-run-template.json` status to `"template"` or create a separate 20-session valid example | Release / Docs |

### P0 - Physical Proof Required

| # | Gap | Required Fix | Owner |
|---|-----|--------------|-------|
| 4 | No T4 physical proof artifact exists | Run 20 tap-to-command sessions + 5 barge-in + 3 disconnect/reconnect + 3 process-death on a real Android phone with real earbuds | Release / Android |
| 5 | `docs/release-matrix.md` has no real rows | Update with at least one verified phone/Android/earbud combination after T4 proof | Release / Docs |
| 6 | No `artifacts/proof-runs/` checked in | Commit one redacted T4 artifact that passes `scripts/validate-proof-run.ts` | Release |

### P0 - Runtime Safety Gaps

| # | Gap | Required Fix | Owner |
|---|-----|--------------|-------|
| 7 | Idempotency not on every event | Make `idempotencyKey` mandatory for all non-read-only Android relay events; reject duplicate unsafe events server-side | Bridge / Android |
| 8 | Protocol version not enforced | Define one format, require it in `earbudEventSchema`, reject unsupported major versions at `/events` | Bridge / Android |
| 9 | Home Ready can overstate readiness | Create `SpeakNowReadiness` model requiring current `listenReadiness`, STT availability, TTS readiness, bridge health, route/fallback state, and proof freshness | Android |
| 10 | TTS lacks audio-focus ownership | Add `AudioAttributes`, request/abandon focus, handle transient/permanent loss, record focus results in metrics | Android |

### P1 - Diagnostics And Automation

| # | Gap | Required Fix | Owner |
|---|-----|--------------|-------|
| 11 | Proof artifact validation not in CI | Replace inline CI validation with `npx tsx scripts/validate-proof-run.ts`; add beta/release mode that requires at least one artifact | CI / Release |
| 12 | Disconnect/reconnect and process-death proof not automated | Add manual or instrumented proof steps for lifecycle recovery | Android / Automation |
| 13 | `docs/sherpa-onnx-provenance.md` missing | Add Sherpa version, source URL, license, artifact checksums, ABIs, modifications, reviewer | Docs / Security |
| 14 | No build matrix for Sherpa vs default | Add CI flavors so base APK does not accidentally include large STT models | CI / Android |
| 15 | No artifact-size check in CI | Prevent large model files from being committed to base APK | CI |

### P2 - Sherpa/Silero (Post-Beta Or Feature-Flagged)

| # | Gap | Required Fix | Owner |
|---|-----|--------------|-------|
| 16 | No native `sherpa-onnx-jni` libraries | Vendor `arm64-v8a` (and `x86_64` for emulator) into `sherpa-runtime/src/main/jniLibs/` | Android / Native |
| 17 | `SherpaRuntimeAvailability.probe()` is a no-op | Attempt `System.loadLibrary("sherpa-onnx-jni")` and report real load status without crashing | Android |
| 18 | No `AudioCaptureOwner` | Implement mic lease to prevent platform STT + VAD + STT + route probe contention | Android |
| 19 | `SherpaVadProbe` is placeholder | Implement real Silero VAD using `AudioRecord` + Sherpa `Vad` Kotlin API | Android |
| 20 | `SherpaSpeechInputEngine` is placeholder | Implement real Sherpa streaming STT with `OnlineRecognizer` | Android |
| 21 | No VAD integration in proof/diagnostics | Add VAD fields to proof summary, blocking failures, and diagnostic export | Android |
| 22 | No benchmark harness comparing platform vs Sherpa | Extend `OfflineSpeechBenchmark` only after Sherpa STT is real | Android |
| 23 | No Developer Mode controls for models/VAD/benchmarks | Add install/run/clear/export UI behind debug/internal build flags | Android |
| 24 | No low-storage preflight in `SherpaModelManager` | Add storage check before download; user-actionable failure message | Android |
| 25 | No atomic download/rollback in `SherpaModelManager` | Use temp directory + move + install marker; rollback on checksum failure | Android |

### P2 - E2E Automation Expansion

| # | Gap | Required Fix | Owner |
|---|-----|--------------|-------|
| 26 | T1 not wired to CI | Add emulator T1 run to CI pipeline | CI / Automation |
| 27 | T4 not enforced as release gate | Make beta/release validation fail without current T4 artifact | CI / Release |
| 28 | No device-selection in scripts | Add `adb -s` support for physical device runner | Automation |
| 29 | No trend summaries | Add p50/p95 latency and failure category aggregation from artifacts | Automation |

---

## Recommended Priority Order

1. **Fix `npm test` and `npm run audit:allowlist`** (closes Doc 29 P0 gates)
2. **Fix `docs/proof-run-template.json`** (honest template status)
3. **Run first T4 physical proof** and commit redacted artifact + update release matrix
4. **Close runtime safety gaps:** idempotency, protocol version, SpeakNowReadiness, TTS audio focus
5. **Wire proof artifact validation to CI**
6. **Vendor real `sherpa-onnx-jni` libraries** into `:sherpa-runtime`
7. **Implement `AudioCaptureOwner`** before any real Sherpa mic usage
8. **Implement real `SherpaVadProbe`** as feature-flagged diagnostic
9. **Implement real `SherpaSpeechInputEngine`** behind experimental flag only after VAD proves stable
10. **Add `docs/sherpa-onnx-provenance.md`** and CI build matrix

---

## Bottom Line

- **Doc 31 is the success story.** The emulator E2E harness (T1 synthetic, T1_PCM injection, T2 host-audio diagnostic) is real, automated, and honest about its limits.
- **Doc 30 is scaffold-only.** The first PR slice (empty module, flags, spec split, placeholder tests) is done, but the native runtime, real engines, capture ownership, and physical proof are entirely missing.
- **Doc 29 blockers are still open.** The repository is not green, no physical proof exists, and Sherpa/Silero must remain hidden from beta users until a real native runtime lands and is benchmarked.

**The next release milestone should be:** green gates, strict protocol/idempotency, "Speak Now" readiness, production TTS audio focus, and one real T4 physical proof artifact. After that, the application can enter a controlled beta honestly.
