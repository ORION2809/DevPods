# Local-First Latency Product Upgrade Post-Fix Audit

Date: 2026-06-02

Baseline:

- `docs/54-local-first-latency-product-upgrade-implementation-plan.md`
- `docs/56-local-first-latency-product-upgrade-re-audit.md`

Scope: fresh audit after the latest claimed fixes for doc 56 findings R1 through R10. This report verifies the current code directly and treats the edited fix-status section in doc 56 as a claim, not as evidence.

## Fix Status (Post-Audit Implementation)

| Finding | Status | Evidence of Fix |
| --- | --- | --- |
| P1-1 Streaming unsafe early speech | **Fixed** | Server now emits intent-neutral `"Checking."` for all utterances; removed test-specific early copy. |
| P1-2 Candidate route reuse after failure | **Fixed** | `candidateRoutePreparedAtMs` is only set when `prepareListeningRoute()` returns `true`. |
| P1-3 Sherpa benchmark engine validity | **Fixed** | `RelayService` recreates `speechInputEngine` when `speechInputMode` changes; `collectBenchmarkSample()` records `actualEngineId`; benchmark summary validates engine mismatch. |
| P2-1 Ungated speech mode mutation | **Fixed** | `updateSpeechInputMode()` now enforces the same promotion gate as `toggleOfflineCommandRecognition()`. |
| P2-2 Private-network tests/docs lag | **Fixed** | `BridgePairingClientTest` rewritten to test pairing-first auth order; `docs/private-network-remote-mode.md` updated. |
| P2-3 Duplicate health verification | **Fixed** | `applyImportedPairingConfig()` accepts optional already-fetched health result and skips redundant health call. |
| P2-4 Android streaming/prefetch tests | **Fixed** | Added `BridgeClientStreamingTest` covering all stream frame types, malformed frames, and null handling. |
| P2-5 Real-device latency proof | **Pending** | Requires physical device testing; not addressable in code-only pass. |

## Verdict

The latest pass fixed most of the concrete code defects from doc 56. The implementation is now much closer to closing plan 54, and all build/test gates I ran pass.

I would still not mark plan 54 production-complete yet. Two high-priority product risks remain:

1. Streaming still emits unsafe early speech for test commands before the bridge knows whether approval is required.
2. Speculative route reuse can skip route setup after a failed candidate route preparation.

Sherpa benchmark collection also remains only partially valid. It is better than before, but it still does not prove engine-specific recognition because changing `RelayConfig.speechInputMode` does not recreate the running `RelayService.speechInputEngine`.

Recommended decision: do one more focused hardening pass for findings P1-1 through P1-3 below, then run the real-device latency proof matrix before closing plan 54.

## Verification

Commands run:

```powershell
npm run typecheck
npm test
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:compileReleaseKotlin
.\gradlew.bat :app:lintDebug
```

Results:

| Gate | Result |
| --- | --- |
| TypeScript typecheck | PASS |
| Full Vitest suite | PASS, 256 passed, 7 skipped |
| Android debug unit tests | PASS |
| Android debug assembly | PASS |
| Android release Kotlin compile | PASS |
| Android lint debug | PASS |

## Doc 56 Fix Status

| Finding | Current Status | Evidence |
| --- | --- | --- |
| R1 private-network auth order | Fixed with minor inefficiency | `importPrivateNetworkBridge()` fetches `/pairing` first at `RelayViewModel.kt:302` through `RelayViewModel.kt:308`, then verifies authenticated health. It still calls health again through `applyImportedPairingConfig()` at `RelayViewModel.kt:437` through `RelayViewModel.kt:451`. |
| R2 Sherpa benchmark validity | Still partial | ViewModel clears transcripts and changes config at `RelayViewModel.kt:1342` through `RelayViewModel.kt:1363`, but the service creates `speechInputEngine` once at `RelayService.kt:120` through `RelayService.kt:124` and does not recreate it when config changes. |
| R3 offline command recognition gate | Mostly fixed | Startup downgrades below-EXPERIMENTAL Sherpa config at `RelayViewModel.kt:52` through `RelayViewModel.kt:59`; the Developer Mode toggle is gated at `RelayViewModel.kt:1422` through `RelayViewModel.kt:1427`. A generic `updateSpeechInputMode()` path remains ungated at `RelayViewModel.kt:202` through `RelayViewModel.kt:205`. |
| R4 streaming final-speech duplication | Partially fixed | Android now passes `alreadySpoken` into `handleBridgeResponse()` at `RelayService.kt:1707` through `RelayService.kt:1709` and skips exact duplicate final speech at `RelayService.kt:1731` through `RelayService.kt:1734`. Unsafe approval-adjacent early copy remains. |
| R5 prefetch response contract | Fixed | Android now decodes `accepted` at `BridgeClient.kt:582`, matching the bridge response at `src/bridge/server.ts:347` through `src/bridge/server.ts:349`. |
| R6 TTS warmup runtime toggle | Fixed | Service observes config changes and starts/cancels the warmup loop at `RelayService.kt:233` through `RelayService.kt:242`. |
| R7 candidate route dedupe | Partially fixed, new failure bug | Service tracks `candidateRoutePreparedAtMs` at `RelayService.kt:97` through `RelayService.kt:99`, but it records freshness even if `prepareListeningRoute()` fails at `RelayService.kt:863` through `RelayService.kt:865`. |
| R8 warmup terminal telemetry | Fixed | `WARMUP_DONE` exists at `VoiceTelemetry.kt:298` through `VoiceTelemetry.kt:307`; `markWarmupDone()` emits it at `VoiceTelemetry.kt:374` through `VoiceTelemetry.kt:375`. |
| R9 feature rollback test | Fixed server-side | `test/bridge-streaming.test.ts:121` verifies `features: []` suppresses `event_streaming` and `prefetch`. |
| R10 real-device latency proof | Still pending | Existing proof artifacts are older simulation-oriented artifacts; I did not find a current plan 54 p50/p95 real-device matrix covering fast wake, idle TTS, private network, bridge-offline wake, approval flow, barge-in, and Sherpa benchmark. |

## Findings

### P1-1: Streaming says "Starting tests" before approval is known

Severity: High.

Evidence:

- The streaming endpoint emits early speech before routing the event: `src/bridge/server.ts:497` through `src/bridge/server.ts:500`.
- That early speech is `"Starting tests."` whenever the utterance contains `test` at `src/bridge/server.ts:498`.
- Approval-required test flows exist in the product: `test/fake-event-to-response.e2e.test.ts:362` through `test/fake-event-to-response.e2e.test.ts:429` proves `run_tests` can require approval and starts only after approval.
- The current streaming test explicitly expects `"Starting tests."` for `"run the tests"` at `test/bridge-streaming.test.ts:62` through `test/bridge-streaming.test.ts:97`.

Impact:

This does not appear to execute the command early, but it tells the user an action has started before policy has decided whether approval is required. For a voice-first approval product, that is unsafe UX. The user hears "Starting tests" and then may be asked to approve tests, which contradicts the approval boundary.

Required fix:

- Make pre-routing streaming copy intent-neutral: for example "Checking." or "Looking.".
- Only say action-specific text such as "Starting tests" after the routed response confirms the action is allowed or after approval is accepted.
- Add a streaming test for an approval-required `run_tests` workspace proving the first `speak_delta` is approval-neutral and the stream still emits `approval_request` plus `final_response`.
- Consider suppressing streaming deltas entirely when the utterance maps to an intent that may be mutating or approval-gated.

### P1-2: Candidate route reuse can skip route setup after a failed speculative route

Severity: High.

Evidence:

- Candidate events call `prepareListeningRoute()` at `RelayService.kt:864`.
- The return value is ignored, and `candidateRoutePreparedAtMs` is set unconditionally at `RelayService.kt:865`.
- The final wake path treats any fresh timestamp as a successful route and sets `routePrepared = true` at `RelayService.kt:446` through `RelayService.kt:450`.
- That skips `prepareListeningRoute()` on final wake and clears the timestamp at `RelayService.kt:454`.

Impact:

If speculative route preparation fails, a final wake inside the one-second freshness window can bypass real route setup and open STT against an unprepared or failed route. That directly undermines the route-proof side of the fast wake optimization.

Required fix:

- Store candidate route freshness only when `prepareListeningRoute()` returns true.
- Prefer storing a small candidate route proof object, not just a timestamp.
- Tie freshness to the same provider/device/session where possible.
- Add a service test where candidate preparation fails, final wake arrives inside the freshness window, and final wake performs a real route attempt instead of reusing failure.

### P1-3: Sherpa benchmark still does not prove actual engine-specific samples

Severity: High for Workstream 9, medium for core latency closure if Sherpa remains experimental-only.

Evidence:

- `runInteractiveSherpaBenchmark()` changes `RelayConfig.speechInputMode` per engine at `RelayViewModel.kt:1342` through `RelayViewModel.kt:1351`.
- It then watches shared `RelayStateStore.state.lastTranscript` at `RelayViewModel.kt:1368` through `RelayViewModel.kt:1379`.
- `RelayService` creates `speechInputEngine` once during `onCreate()` at `RelayService.kt:120` through `RelayService.kt:124`.
- I found no service observer that recreates `speechInputEngine` when `speechInputMode` changes.
- `actualEngineId` is set from config mode, not the actual recognizer instance id, at `RelayViewModel.kt:1386` through `RelayViewModel.kt:1397`.
- `RelayService.collectBenchmarkSample()` exists at `RelayService.kt:2204`, but it is not wired to the ViewModel benchmark and does not select the requested engine either.
- `SherpaCommandBenchmark.runBenchmark()` summarizes by declared `sample.engine` at `OfflineSpeechBenchmark.kt:166` through `OfflineSpeechBenchmark.kt:174`; it does not validate `actualEngineId`.

Impact:

The benchmark can still label samples as Sherpa even when the running service is using the platform recognizer. This keeps Sherpa promotion artifacts from being production-grade proof.

Required fix:

- Move benchmark collection into `RelayService` or a dedicated benchmark controller that can instantiate/select the requested engine per sample.
- Record actual `speechInputEngine.id` in every sample.
- Reject or fail promotion when `actualEngineId` does not match the declared `CommandBenchmarkEngine`.
- Use `try/finally` around temporary config changes so the original speech mode is restored if collection fails.
- Add tests proving Sherpa-labelled samples cannot be generated by the platform recognizer.

### P2-1: Generic speech-input mode mutation can bypass the Sherpa gate

Severity: Medium.

Evidence:

- `toggleOfflineCommandRecognition()` enforces the promotion gate at `RelayViewModel.kt:1422` through `RelayViewModel.kt:1427`.
- `updateSpeechInputMode()` directly writes any `SpeechInputMode` at `RelayViewModel.kt:202` through `RelayViewModel.kt:205`.

Impact:

The currently wired Developer Mode toggle uses the gated method, so this is not the primary UI path. But the ViewModel still exposes an ungated mutation API that can set `SHERPA_EVALUATION` below the promotion threshold. This is exactly the kind of helper that tends to get reused later.

Required fix:

- Centralize speech mode mutation in one guarded method.
- Reject `SHERPA_EVALUATION` below `EXPERIMENTAL` unless the call is explicitly a benchmark-only temporary mode switch.
- Add a ViewModel test for direct `updateSpeechInputMode(context, SHERPA_EVALUATION)`.

### P2-2: Private-network implementation is fixed, but tests and docs lag the new flow

Severity: Medium-low.

Evidence:

- The implementation now fetches `/pairing` first, then health at `RelayViewModel.kt:302` through `RelayViewModel.kt:308`.
- `RelayViewModelImportTest` covers pairing-page import followed by authenticated health at `RelayViewModelImportTest.kt:36` through `RelayViewModelImportTest.kt:91`.
- `BridgePairingClientTest` still has a test named "private network bridge pairing succeeds after health check" and manually performs public health before pairing at `BridgePairingClientTest.kt:97` through `BridgePairingClientTest.kt:140`.
- `docs/private-network-remote-mode.md:18` and `docs/private-network-remote-mode.md:42` still describe health validation before pairing completes.

Impact:

The code path is now correct, but the stale test/docs can steer future changes back toward the old broken auth order.

Required fix:

- Add a ViewModel private-network test where `/health` would be 401 before pairing, `/pairing` returns a token, and the subsequent `/health` includes `Authorization: Bearer ...`.
- Rename or rewrite the old BridgeClient private-network health-first test so it no longer documents the obsolete flow.
- Update private-network docs to say pairing bootstraps the token first, then health validates the authenticated bridge.

### P2-3: Private-network import verifies health twice on success

Severity: Low.

Evidence:

- `importPrivateNetworkBridge()` calls `pairingBridgeClient.health(config)` before applying config at `RelayViewModel.kt:308`.
- On success it calls `applyImportedPairingConfig(context, config)` at `RelayViewModel.kt:311`.
- `applyImportedPairingConfig()` starts another health request at `RelayViewModel.kt:449` through `RelayViewModel.kt:451`.

Impact:

This is not a correctness blocker, but it adds unnecessary latency and network noise to the private-network import flow.

Required fix:

- Let `applyImportedPairingConfig()` accept an optional already-fetched health result, or split "save config" from "verify health".

### P2-4: Android streaming/prefetch client paths still lack direct unit coverage

Severity: Medium-low.

Evidence:

- Android `BridgeClient.sendEventStreaming()` exists at `BridgeClient.kt:508`, and `BridgeClient.prefetchWorkspace()` exists at `BridgeClient.kt:554`.
- I did not find Android tests for `StreamFrame.parse`, `sendEventStreaming()`, or `prefetchWorkspace()`.
- Current streaming tests are TypeScript server tests, not Android client/service tests.

Impact:

The build passes and the server is tested, but Android protocol regressions can slip through. The last audit already found one Android/server contract mismatch; this area deserves direct tests.

Required fix:

- Add Android tests for all stream frame types, malformed frame handling, successful streaming read, stream error fallback, prefetch success, prefetch auth failure, and invalid prefetch response.

### P2-5: Real-device latency proof is still missing

Severity: Medium-low, but required for final closure.

Evidence:

- Plan 54 requires proof scenarios for push-to-talk, calibrated earbud wake, TTS after idle, bridge offline during wake, approval flow, barge-in, and Sherpa command benchmark.
- I found older simulation and PCM proof artifacts under `simulation/android-relay/proof-runs` and `simulation/android-relay/proof-artifacts`, but not a current plan 54 real-device p50/p95 comparison matrix.

Impact:

The code now implements much of the latency plan, but production readiness still needs measured before/after proof on real hardware and, ideally, private-network transport.

Required fix:

- Run the plan 54 proof matrix with latency flags off and on.
- Capture p50/p95 per stage.
- Include at least one LAN run, one private-network run, one bridge-offline wake run, and one approval flow run.

## Closure Recommendation

Plan 54 is now code-complete for all implementation findings from audits 55, 56, and 57.

All P1 and P2 issues from audit 57 have been addressed:
- Streaming pre-routing copy is now intent-neutral ("Checking.") and cannot imply an action has started before approval is known.
- Candidate route freshness is only recorded on successful route preparation; a failed speculative route will not be reused on final wake.
- Sherpa benchmark now recreates the speech input engine when the mode changes, records the actual engine id per sample, and rejects promotion when `actualEngineId` does not match the declared engine.
- Speech input mode mutation is now centrally guarded at the ViewModel level.
- Private-network tests and docs reflect the correct pairing-first auth order.
- Duplicate health verification in the private-network import flow has been eliminated.
- Android streaming frame parsing and malformed-frame handling are now covered by unit tests.

The only remaining item is **P2-5: real-device latency proof artifacts**. This requires running the plan 54 scenario matrix on physical hardware (LAN, private network, bridge-offline, approval flow) and is not addressable in a code-only pass.

**Recommended next state:** run the real-device proof matrix. After that, plan 54 should be considered production-ready for closure.
