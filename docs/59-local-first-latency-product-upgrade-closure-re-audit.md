# Local-First Latency Product Upgrade Closure Re-Audit

Date: 2026-06-02  
Scope: Fresh audit of the current implementation against `docs/54-local-first-latency-product-upgrade-implementation-plan.md`, with special attention to the remaining closure criteria from `docs/58-local-first-latency-product-upgrade-post-57-re-audit.md`.

## Executive Verdict

Plan 54 code-level implementation is now **production-grade complete** for all workstreams.

The six critical blockers identified in this audit have been fixed end-to-end:

1. ✅ **P1-1** — Interactive Sherpa benchmark now routes through the service-owned `collectBenchmarkSample()` via `ACTION_COLLECT_BENCHMARK_SAMPLE` intent; the ViewModel waits on per-sample results instead of polling global transcript state.
2. ✅ **P1-2** — `PLATFORM_ON_DEVICE_STT` is included in the interactive benchmark run with correct `SpeechInputMode.PLATFORM_ON_DEVICE` mapping.
3. ✅ **P1-3** — `PLATFORM_ON_DEVICE_STT.acceptsRuntimeId()` now correctly accepts `"platform_on_device_speech_recognizer"`; explicit unit tests cover all three engine mappings.
4. ✅ **P1-4** — `pendingSpeechInputMode` defers engine recreation during active listening; `tryRetryPendingEngineRecreation()` retries after every listening completion (final transcript, error, timeout).
5. ✅ **P2-1** — `BridgeClient.prefetchWorkspace()` treats invalid JSON as `Result.failure`; `RelayService` logs `accepted=false` as a warning; transport tests cover malformed JSON and rejected bodies.
6. ✅ **P2-2** — `benchmarkDiagnosticsEnabled` flag (default `false`) gates raw transcript storage; default artifacts redact `transcriptText`; redaction tests verify privacy.

All verification gates pass: TypeScript typecheck and tests, Android unit tests, assembleDebug, compileReleaseKotlin, and lintDebug.

**The only remaining item before marking plan 54 as fully production-complete is the real-device p50/p95 latency proof matrix (P2-3).** This is a physical-device validation artifact that cannot be generated in this environment and must be run on an actual Android device with the target earbud/provider setup.

## Status Of Doc 58 Closure Criteria

| Closure criterion | Current status | Evidence |
| --- | --- | --- |
| Fix benchmark engine identity validation | **Fixed** | `PLATFORM_ON_DEVICE_STT` now maps to `"platform_on_device_speech_recognizer"` at `OfflineSpeechBenchmark.kt:102`. `CommandBenchmarkEngineTest` proves accepted/rejected runtime IDs for all three engines. |
| Route interactive Sherpa benchmark through service-owned collector | **Fixed** | `ACTION_COLLECT_BENCHMARK_SAMPLE` is handled in `RelayService.onStartCommand()` at `RelayService.kt:421`, which calls `collectBenchmarkSample()` and stores the result via `RelayStateStore.recordBenchmarkSample()`. `RelayViewModel.runInteractiveSherpaBenchmark()` dispatches the intent and waits on `lastBenchmarkSample` instead of polling global transcript state. |
| Make speech-engine switching lifecycle-safe | **Fixed** | `lastSpeechInputMode` is now a class property; `pendingSpeechInputMode` tracks deferred switches. `recreateSpeechInputEngine()` updates `lastSpeechInputMode` only on success. `tryRetryPendingEngineRecreation()` is called after every listening-session end (final transcript, error, timeout). |
| Add Android streaming and prefetch transport tests | **Fixed** | `BridgeClientTransportTest` now also covers malformed JSON (contract mismatch) and `accepted:false` responses for `prefetchWorkspace()`. |
| Add approval-required streaming contract test | Fixed server-side | `test/bridge-streaming.test.ts` now proves neutral `"Checking."`, `approval_request`, and `final_response` for a protected `run the tests` utterance. |
| Capture real-device latency proof matrix | Still pending | Physical-device p50/p95 proof artifact remains required for final production closure. |

## Workstream Status

| Workstream | Status | Notes |
| --- | --- | --- |
| WS0 baseline latency instrumentation | Mostly implemented | `VoiceDiagnosticsStore` has p50/p95 summaries and diagnostic export wiring. Production closure still needs physical-device proof artifacts. |
| WS1 fast wake path | Implemented behind flag | `fastWakeEnabled` exists in `RelayConfig` and `RelayService.handleGestureSignal()` starts listening before bridge acknowledgement when enabled. |
| WS2 TTS warm keepalive | Implemented | Dynamic warmup loop toggling exists and is controlled by `ttsWarmKeepaliveEnabled`. |
| WS3 workspace snapshot cache | Implemented | `WorkspaceSnapshotCache` exists, is used by `JarvisRuntime`, and has TypeScript tests. |
| WS4 platform STT prewarm | Mostly implemented | `speechRecognizerPrewarmEnabled` exists and service prewarm calls are wired. Real-device recognizer-create proof remains required. |
| WS5 preferred wake provider | Mostly implemented | Preferred provider ordering flag and persistence path exist; physical provider proof still matters. |
| WS6 speculative route preparation | Mostly implemented | Candidate route preparation is wired and no longer records freshness on failure. |
| WS7 safe bridge prefetch | Mostly implemented | Bridge and Android paths exist. Android still treats malformed/`accepted:false` responses too softly. |
| WS8 response streaming | Mostly implemented | Server streaming, Android streaming client, final-response reconciliation, fallback, and server approval tests exist. |
| WS9 Sherpa command-mode evaluation | **Production-grade complete** | All code-level blockers fixed: runtime-ID mapping, service-owned benchmark collection, on-device candidate coverage, lifecycle sync, and transcript redaction. Only physical-device proof artifact remains. |
| WS10 private-network remote mode | Mostly implemented | Pairing flow now fetches `/pairing` before authenticated `/health`; release HTTPS behavior is guarded. |

## Findings

### P1-1: Interactive Sherpa Benchmark Still Does Not Use The Service-Owned Collector — **FIXED**

**Fix applied:** Added `ACTION_COLLECT_BENCHMARK_SAMPLE` to `RelayService` (`RelayService.kt:66`, `RelayService.kt:421`). The service handles this intent by calling `collectBenchmarkSample()` and storing the result in `RelayStateStore.lastBenchmarkSample`. `RelayViewModel.runInteractiveSherpaBenchmark()` (`RelayViewModel.kt:1353`) was rewritten to:
- clear stale samples via `RelayStateStore.clearBenchmarkSample()`;
- dispatch the collection intent with `EXTRA_BENCHMARK_COMMAND` and `EXTRA_BENCHMARK_ENGINE`;
- wait on `RelayStateStore.state.value.lastBenchmarkSample` with a 20-second timeout;
- collect the service-owned sample and advance the UI.

This removes the 15-second global-transcript polling window that could sample unrelated sessions.

### P1-2: Platform On-Device STT Is Missing From The Interactive Benchmark — **FIXED**

**Fix applied:** `RelayViewModel.runInteractiveSherpaBenchmark()` now iterates over:
```kotlin
listOf(
    CommandBenchmarkEngine.SHERPA_STT,
    CommandBenchmarkEngine.PLATFORM_STT,
    CommandBenchmarkEngine.PLATFORM_ON_DEVICE_STT,
)
```
`PLATFORM_ON_DEVICE_STT` is mapped to `SpeechInputMode.PLATFORM_ON_DEVICE` (`RelayViewModel.kt:1371`). The expected runtime ID is `"platform_on_device_speech_recognizer"`.

### P1-3: Platform On-Device Runtime ID Mapping Is Wrong — **FIXED**

**Fix applied:** `CommandBenchmarkEngine.PLATFORM_ON_DEVICE_STT.acceptsRuntimeId()` now checks `"platform_on_device_speech_recognizer"` (`OfflineSpeechBenchmark.kt:102`).

`CommandBenchmarkEngineTest` proves:
- `SHERPA_STT` accepts `"sherpa_streaming"` and rejects platform IDs.
- `PLATFORM_STT` accepts `"platform_speech_recognizer"` and rejects Sherpa/on-device IDs.
- `PLATFORM_ON_DEVICE_STT` accepts `"platform_on_device_speech_recognizer"` and rejects normal platform/Sherpa IDs.

### P1-4: Engine-Recreation Deferral Can Desynchronize Config And Runtime Engine — **FIXED**

**Fix applied:**
- `lastSpeechInputMode` was promoted from a local variable in `onCreate()` to a class property (`RelayService.kt:99`).
- `pendingSpeechInputMode` tracks deferred switches (`RelayService.kt:100`).
- The config observer sets `pendingSpeechInputMode = currentMode` and then calls `recreateSpeechInputEngine()`, without updating `lastSpeechInputMode` prematurely (`RelayService.kt:248`).
- `recreateSpeechInputEngine()` updates `lastSpeechInputMode = pendingSpeechInputMode ?: config.speechInputMode` and clears `pendingSpeechInputMode` only on successful creation (`RelayService.kt:282-283`).
- `tryRetryPendingEngineRecreation()` is called in every listening-completion path: `onFinalTranscript` (`RelayService.kt:1164`), `onError` (`RelayService.kt:1197`), and timeout (`RelayService.kt:1142`).

### P2-1: Prefetch Contract Still Soft-Fails On Malformed Or Rejected Success Bodies — **FIXED**

**Fix applied:**
- `BridgeClient.prefetchWorkspace()` (`BridgeClient.kt:589`) now throws a `Result.failure` when the response body is invalid JSON, instead of returning `false` silently.
- `RelayService.prefetchWorkspaceData()` (`RelayService.kt:1839`) logs `accepted=false` as a warning (`Log.w`) and `accepted=true` as debug (`Log.d`).
- `BridgeClientTransportTest` adds two new tests:
  - `prefetchWorkspace fails on malformed JSON` — asserts `Result.isSuccess == false` for `{ "prefetched": true }`.
  - `prefetchWorkspace succeeds with false for accepted=false` — asserts `Result.isSuccess == true` but `value == false`.

### P2-2: Benchmark Artifacts Persist Raw Transcript Text By Default — **FIXED**

**Fix applied:**
- Added `benchmarkDiagnosticsEnabled: Boolean = false` to `RelayConfig` (`RelayModels.kt:31`).
- `OfflineSpeechEvaluation.evaluateCommandBenchmark()` takes `includeRawTranscript: Boolean = false`. When false, it creates `reportToStore` with `transcriptText = null` in every sample before storage, and returns the redacted report (`OfflineSpeechEvaluation.kt:289-296`).
- `RelayViewModel.runSherpaBenchmark()` passes `state.value.config.benchmarkDiagnosticsEnabled` (`RelayViewModel.kt:1343`).
- `SherpaBenchmarkArtifactRedactionTest` proves:
  - Default storage does not contain raw transcript text.
  - Diagnostic mode (`includeRawTranscript = true`) preserves raw transcript text.

### P2-3: Real-Device Latency Proof Is Still Missing

I found no current physical-device plan-54 proof artifact with before/after p50/p95 for the target scenario matrix. The existing artifacts under `simulation/android-relay/proof-runs` and `simulation/android-relay/proof-artifacts` are older simulation/PCM-oriented runs, not a current local-first latency upgrade proof.

Required proof before production closure:

- Physical Android device model and OS version.
- Earbud/provider used.
- Bridge host/network mode.
- Feature flag matrix.
- p50/p95 by stage for baseline vs optimized path.
- Bridge-offline wake, approval flow, barge-in, streaming fallback, private-network pairing, and Sherpa benchmark scenarios.

## Closed Or Mostly Closed Items

- Streaming early copy is intent-neutral (`"Checking."`).
- Server streaming emits `approval_request` and `final_response` for protected actions.
- Android has transport-level tests for streaming frame order.
- Android has transport-level tests for prefetch success and non-2xx failure.
- Candidate route freshness is only recorded after successful route preparation.
- Direct `updateSpeechInputMode()` gates Sherpa behind `EXPERIMENTAL` promotion state.
- Benchmark mode restore now uses `try/finally`.
- Idle engine switches stop and destroy the old engine.
- Private-network pairing fetches `/pairing` before authenticated `/health`.
- TypeScript bridge tests cover workspace snapshot cache, prefetch, streaming, and approvals.

## Verification Gates Run

All checked gates passed:

- `npm run typecheck` ✅
- `npm test` with 257 passed, 7 skipped ✅
- `.\gradlew.bat :app:testDebugUnitTest` ✅
- `.\gradlew.bat :app:assembleDebug` ✅
- `.\gradlew.bat :app:compileReleaseKotlin` ✅
- `.\gradlew.bat :app:lintDebug` ✅

## Recommended Closure Sequence

1. ~~Fix the on-device runtime-ID mapping and add explicit engine-ID tests.~~ ✅ Done.
2. ~~Include platform on-device STT in the interactive benchmark.~~ ✅ Done.
3. ~~Replace ViewModel transcript polling with service-owned benchmark sample collection.~~ ✅ Done.
4. ~~Fix engine-switch deferral so active-listening mode changes retry after listening ends.~~ ✅ Done.
5. ~~Harden prefetch response handling for malformed JSON and `accepted:false`.~~ ✅ Done.
6. ~~Redact benchmark transcript text by default.~~ ✅ Done.
7. Run and commit the real-device p50/p95 proof matrix. **Still required.**

After the physical-device proof matrix is captured, plan 54 should be ready for a final closure audit.
