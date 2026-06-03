# Local-First Latency Product Upgrade Post-57 Re-Audit

Date: 2026-06-02  
Scope: Current codebase verification against `docs/54-local-first-latency-product-upgrade-implementation-plan.md` and the blocking/post-fix findings in `docs/57-local-first-latency-product-upgrade-post-fix-audit.md`.

## Verdict

Do not mark the plan-54 latency upgrade as production-complete yet.

The latest fixes close the two highest-risk runtime regressions from doc 57: unsafe streaming early speech is now intent-neutral, and failed speculative route preparation is no longer reused on the final wake. The private-network pairing flow and duplicate health check are also fixed, and all checked build gates pass.

The remaining production blocker is Workstream 9 / Sherpa command-mode promotion. The code now has service-side machinery to collect actual speech engine IDs, but the interactive benchmark path still does not use that collector, and the benchmark validator compares incompatible identifier namespaces. That means the current benchmark still is not a trustworthy proof that Sherpa samples were collected from Sherpa and platform samples were collected from the platform recognizer.

## Status Against Doc 57 Findings

| Finding | Current Status | Evidence |
| --- | --- | --- |
| P1-1 streaming unsafe early speech | Fixed | `/events/stream` now emits only `"Checking."` before routing/approval at `src/bridge/server.ts:497`. Tests assert the neutral copy in `test/bridge-streaming.test.ts:53` and `test/bridge-streaming.test.ts:97`. |
| P1-2 candidate route reuse after failure | Fixed | Candidate route freshness is only recorded when `prepareListeningRoute()` returns true at `RelayService.kt:886`. Final wake reuse still consumes only a fresh timestamp at `RelayService.kt:470`. |
| P1-3 Sherpa benchmark engine validity | Partially fixed, still blocking | `RelayService.collectBenchmarkSample()` records `speechInputEngine.id` at `RelayService.kt:2344`, but `runInteractiveSherpaBenchmark()` still records `stateFlow.value.config.speechInputMode.name` at `RelayViewModel.kt:1407`. The validator compares actual IDs to enum names at `OfflineSpeechBenchmark.kt:207`. |
| P2-1 direct Sherpa mode mutation | Fixed for normal UI/API path | `updateSpeechInputMode()` blocks `SHERPA_EVALUATION` until promotion state is `EXPERIMENTAL` or higher at `RelayViewModel.kt:202`. The benchmark path intentionally bypasses this, but needs safer restore handling. |
| P2-2 private-network pairing docs/tests | Fixed enough for closure | `importPrivateNetworkBridge()` now fetches `/pairing` first and then authenticated `/health` at `RelayViewModel.kt:311`. The unit test now checks that order and authenticated health at `BridgePairingClientTest.kt:97`. |
| P2-3 duplicate health verification | Fixed | `applyImportedPairingConfig()` accepts `alreadyFetchedHealth` and records it without a second health request at `RelayViewModel.kt:461`. |
| P2-4 Android streaming/prefetch tests | Partially fixed | `BridgeClientStreamingTest` covers `StreamFrame.parse`, including approval frames and malformed lines. It still does not exercise `BridgeClient.sendEventStreaming()` over a real mock HTTP stream or `BridgeClient.prefetchWorkspace()` request/response handling. |
| P2-5 real-device latency proof | Still pending | I did not find a fresh plan-54 proof artifact with before/after p50/p95 on a physical Android device. Existing proof artifacts are not sufficient to close production latency claims. |

## Remaining Findings

### P1: Sherpa Benchmark Validator Compares The Wrong Engine Identity

`CommandBenchmarkEngine` uses enum names:

- `PLATFORM_STT`
- `PLATFORM_ON_DEVICE_STT`
- `SHERPA_STT`

Real speech engines expose different runtime IDs:

- `PlatformSpeechRecognizerEngine.id = "platform_speech_recognizer"` at `PlatformSpeechRecognizerEngine.kt:9`
- `SherpaSpeechInputEngine.id = "sherpa_streaming"` at `SherpaSpeechInputEngine.kt:47`

The benchmark summary currently counts mismatches with:

```kotlin
it.actualEngineId != null && it.actualEngineId != engine.name
```

at `OfflineSpeechBenchmark.kt:207`.

This means honest service-collected Sherpa samples with `actualEngineId = "sherpa_streaming"` will not match `CommandBenchmarkEngine.SHERPA_STT.name`, and honest platform samples with `actualEngineId = "platform_speech_recognizer"` will not match `CommandBenchmarkEngine.PLATFORM_STT.name`.

Impact:

- The benchmark can reject valid samples for the wrong reason.
- The tests can still pass because they mostly omit `actualEngineId`, which bypasses mismatch validation.
- If callers fake `actualEngineId` as enum names, the benchmark validates the declared config label instead of the actual recognizer instance.

Required fix:

- Add an explicit mapping from declared benchmark engine to acceptable runtime engine IDs.
- For `PLATFORM_ON_DEVICE_STT`, do not rely on `platform_speech_recognizer` alone, because normal platform STT and on-device platform STT share the same engine class. Store a separate requested mode/capability field if on-device distinction matters.
- Add tests where `actualEngineId = "sherpa_streaming"` passes for `SHERPA_STT`, `actualEngineId = "platform_speech_recognizer"` passes for `PLATFORM_STT`, and cross-engine IDs fail.

### P1: Interactive Sherpa Benchmark Still Does Not Use The Service Collector

There is now a stronger service-side sample collector:

- `RelayService.collectBenchmarkSample()` prepares the audio route, starts the active `speechInputEngine`, records session timings, and stores `actualEngineId = speechInputEngine.id` at `RelayService.kt:2239` through `RelayService.kt:2345`.

But the user-facing interactive benchmark still runs inside `RelayViewModel.runInteractiveSherpaBenchmark()`:

- It mutates `RelayConfig.speechInputMode` directly at `RelayViewModel.kt:1368`.
- It waits for shared `RelayStateStore.state.lastTranscript` at `RelayViewModel.kt:1390`.
- It records `actualEngineId` from config mode name, not from the recognizer instance, at `RelayViewModel.kt:1407`.

Impact:

- The benchmark still observes global transcript state instead of owning the speech sample lifecycle.
- A stale transcript, unrelated listening session, or delayed service engine recreation can pollute the sample.
- The report can claim an engine label without proving which engine produced the transcript.

Required fix:

- Route the interactive benchmark through a single benchmark controller/service API that collects one sample at a time from the active engine and returns the service-recorded sample.
- Ensure each engine switch waits until the service has recreated the engine and records the runtime engine ID before the prompt is accepted.
- Add a regression test that fails if a Sherpa-labeled sample is collected while the service engine ID is platform, and vice versa.

### P2: Speech Engine Recreate Does Not Destroy The Previous Engine

The service observes `speechInputMode` changes and recreates the engine at `RelayService.kt:243`, which is the right direction. However, `recreateSpeechInputEngine()` assigns a new engine instance at `RelayService.kt:253` without destroying or stopping the previous one first. The old engine is only destroyed in `onDestroy()` at `RelayService.kt:404`.

Impact:

- Switching modes during benchmark or developer testing can leak recognizer resources.
- If a mode changes while a listening session is active, the old engine may still own audio capture while the service points at a new engine.
- This is especially risky around Sherpa because the native runtime and microphone capture lifecycle are more sensitive than a pure config flag.

Required fix:

- Serialize mode switching with active listening/benchmark sessions.
- Stop the current engine if needed, then destroy it before replacing it.
- Make the switch observable: update readiness only after the new engine is initialized.
- Add a test or instrumentation hook around “mode switch while idle” and “mode switch while listening is rejected/deferred.”

### P2: Benchmark Mode Restore Needs `try/finally`

`runInteractiveSherpaBenchmark()` stores `originalMode` at `RelayViewModel.kt:1357` and restores it after all samples at `RelayViewModel.kt:1429`. If the coroutine throws before the restore block, the app can remain in temporary benchmark speech mode.

Impact:

- A failed benchmark can leave `SHERPA_EVALUATION` enabled even when the user did not explicitly promote it.
- This weakens the otherwise fixed `updateSpeechInputMode()` promotion gate.

Required fix:

- Wrap the benchmark loop in `try/finally`.
- Restore the original mode and finish/clear benchmark session state in the `finally` block.

### P2: Android Streaming And Prefetch Tests Are Still Thin

`BridgeClientStreamingTest` now covers JSON parsing for `StreamFrame.parse`, which is useful. The production paths added by plan 54 are broader than parsing:

- `BridgeClient.sendEventStreaming()` reads incremental HTTP frames at `BridgeClient.kt:508`.
- `BridgeClient.prefetchWorkspace()` sends `/prefetch` requests at `BridgeClient.kt:554`.
- `RelayService` consumes streaming frames at `RelayService.kt:1701`.
- `RelayService` calls prefetch from wake/candidate paths at `RelayService.kt:512` and `RelayService.kt:908`.

Missing test coverage:

- A mock HTTP server test where `sendEventStreaming()` receives multiple NDJSON frames and invokes callbacks in order.
- Streaming approval flow test where an `approval_request` frame is followed by `final_response` and `done`.
- Streaming malformed-frame tolerance test at the client transport layer, not only parser level.
- `prefetchWorkspace()` success test that asserts request body, auth header, and `accepted` response parsing.
- `prefetchWorkspace()` failure tests for non-2xx, invalid JSON, and auth rejection.

### P2: Streaming Approval-Safety Should Have A Direct Contract Test

The server now uses neutral early copy, which closes the original unsafe behavior. The next useful hardening test is a direct approval-required streaming scenario:

- Send an utterance that routes to a protected action such as running tests.
- Assert the first speech/display delta is `"Checking."`.
- Assert no action-specific speech appears before the bridge emits `approval_request`.
- Assert the stream still emits `final_response` after `approval_request` so Android can reconcile state.

This is not a current blocker because the implementation at `src/bridge/server.ts:497` through `src/bridge/server.ts:509` is structurally safe, but it should be covered before calling the streaming protocol production-grade.

### P2: Real-Device Latency Proof Is Still Required

The code can pass unit and build gates while still missing the product proof that plan 54 is explicitly about: reducing real wake-to-response latency without degrading graceful fallback.

Required proof artifact:

- Physical Android device model, OS version, earbud/provider, bridge host details.
- Feature flag matrix for baseline vs enabled workstreams.
- p50/p95 for wake-to-ready, first partial, final transcript, bridge response, first TTS audio, and total perceived response.
- Failure-mode runs: bridge offline during wake, mDNS unavailable, route prepare failure, approval-required command, and streaming fallback to non-streaming `/events`.

## Closed Items Worth Keeping

The following fixes look good and should stay:

- Streaming early copy is now intent-neutral and cannot imply a command is running before routing/approval.
- Candidate route timestamps are only recorded after successful route preparation.
- Direct UI/API speech mode mutation now respects Sherpa promotion state.
- Private-network pairing now follows the correct unauthenticated `/pairing` then authenticated `/health` sequence.
- Duplicate health verification after private-network import is removed.
- Android `StreamFrame.parse` now has parser-level tests.

## Verification Gates Run

All checked gates passed:

- `npm run typecheck`
- `npm test` with 256 passed, 7 skipped
- `.\gradlew.bat :app:testDebugUnitTest`
- `.\gradlew.bat :app:assembleDebug`
- `.\gradlew.bat :app:compileReleaseKotlin`
- `.\gradlew.bat :app:lintDebug`

## Closure Criteria Before Marking Plan 54 Done

1. Fix benchmark engine identity validation with explicit runtime-ID mapping.
2. Route interactive Sherpa benchmark samples through the service-owned collector or an equivalent single-owner benchmark controller.
3. Make speech engine mode switching lifecycle-safe by stopping/destroying the old engine and serializing switches against active listening.
4. Add client transport tests for `sendEventStreaming()` and `prefetchWorkspace()`.
5. Add one approval-required streaming contract test.
6. Capture and commit the real-device latency proof matrix.

After those are complete, the implementation can be considered a production-grade closure candidate for plan 54.
