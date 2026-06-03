# Local-First Latency Product Upgrade Re-Audit

Date: 2026-06-02

Baseline documents:

- `docs/54-local-first-latency-product-upgrade-implementation-plan.md`
- `docs/55-local-first-latency-product-upgrade-implementation-audit.md`

Scope: fresh post-55 audit of the current Android relay and TypeScript bridge implementation for plan 54. This pass used direct code inspection as the source of truth, with the code-review graph only as orientation.

## Executive Verdict

The implementation has progressed materially since audit 55. Several previous blocking items are now genuinely implemented:

- Android now has an NDJSON streaming client and `RelayService` can route eligible events through `/events/stream`.
- Android now calls the dedicated prefetch endpoint instead of sending a normal fire-and-forget wake event as "prefetch".
- The bridge streaming endpoint now always emits a `final_response`, including approval flows.
- Fast wake now passes `routeAlreadyPrepared = true` into the listening session and uses the wake signal timestamp as the latency origin.
- Developer Mode now exposes latency rollback toggles.
- TTS queue mode is propagated through the speech output contract.
- Private-network UI and release HTTPS filtering now exist.
- Workspace cache, prefetch, streaming, and mDNS TypeScript tests pass.

However, plan 54 is still not production-grade complete. I would not mark the latency upgrade fully done yet. The biggest remaining risks are not basic compilation failures; they are contract mismatches, missing Android-path tests, and a few product-flow bugs that the current tests do not cover.

Recommended decision: do not close plan 54 as perfect yet. Close the old P0s that are fixed, then address the remaining blocking/high-priority findings below before treating this as production-ready.

## Verification Run

Commands run during this audit:

```powershell
npm run typecheck
npm test -- test/workspace-snapshot-cache.test.ts test/bridge-prefetch.test.ts test/bridge-streaming.test.ts test/mdns-advertisement.test.ts
npm test
.\gradlew.bat :app:testDebugUnitTest --tests com.openclaw.relay.OfflineSpeechEvaluationTest --tests com.openclaw.relay.BridgePairingClientTest --tests com.openclaw.relay.RelayGestureRoutingTest --tests com.openclaw.relay.MediaButtonDiagnosticsTest --tests com.openclaw.relay.SpeechEngineContractsTest --tests com.openclaw.relay.TtsPlaybackMetricsTest --tests com.openclaw.relay.SpeechSessionMetricsTest
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:compileReleaseKotlin
```

Results:

| Gate | Result |
| --- | --- |
| TypeScript typecheck | PASS |
| Targeted bridge/cache/streaming/mDNS tests | PASS, 19 tests |
| Full Vitest suite | PASS, 255 passed, 7 skipped |
| Targeted Android unit tests | PASS |
| Full Android debug unit tests | PASS |
| Android debug assembly | PASS |
| Android release Kotlin compile | PASS |

Passing gates are a good sign, but they do not cover several of the remaining issues below.

## Status Changes Since Audit 55

| Audit 55 Item | Current Status | Evidence |
| --- | --- | --- |
| P0-1 Android streaming unimplemented | Mostly fixed | `BridgeClient.sendEventStreaming()` exists at `android-relay/app/src/main/java/com/openclaw/relay/BridgeClient.kt:508`; `RelayService` selects streaming at `RelayService.kt:1592`. |
| P0-2 Safe prefetch not wired on Android | Mostly fixed | `BridgeClient.prefetchWorkspace()` exists at `BridgeClient.kt:554`; wake/candidate paths call `prefetchWorkspaceData()` at `RelayService.kt:465` and `RelayService.kt:858`. |
| P0-3 Streaming approval missing final response | Fixed server-side | `streamResult()` emits `approval_request` and then `final_response` at `src/bridge/server.ts:502` through `src/bridge/server.ts:508`. |
| P1-1 Fast wake double route | Mostly fixed | Initial route is prepared at `RelayService.kt:429`, then `startListeningSession(..., routeAlreadyPrepared = true)` is called at `RelayService.kt:462`. |
| P1-2 Latency origin undercount | Mostly fixed | `startListeningSession()` forwards `wakeSignal.receivedAtMs` into `beginListeningSession()` at `RelayService.kt:879` through `RelayService.kt:883`. |
| P1-3 Missing rollback toggles | Mostly fixed | Developer Mode latency toggles are rendered at `DeveloperModeScreen.kt:255` through `DeveloperModeScreen.kt:313`; ViewModel persists them at `RelayViewModel.kt:1394`. |
| P1-4 TTS queue mode incomplete | Fixed | Streaming deltas use `QUEUE_FLUSH` for first delta and `QUEUE_ADD` after that at `RelayService.kt:1652` through `RelayService.kt:1659`. |
| P1-5 TTS warmup pollutes speaking state | Mostly fixed | Warmup `onStart` and `onDone` return before `onSpeakingChanged(true/false)` at `AndroidTtsSpeaker.kt:95` through `AndroidTtsSpeaker.kt:116`. |
| P1-6 Private-network UI missing | Partially fixed | UI wiring exists, but the request order still breaks token-protected bridges. See finding R1. |
| P1-7 Sherpa benchmark collected no samples | Partially fixed | It now collects something, but not valid engine-specific live samples. See finding R2. |
| P2 feature advertising unconditional | Fixed enough | Default feature list is overrideable at `src/bridge/server.ts:620` through `src/bridge/server.ts:637`. |
| P2 expanded prefetch kinds | Mostly fixed server-side | Schema supports `workspace_status`, `diff_stat`, and `ci_snapshot` at `src/protocol/schemas.ts:330`; runtime handles them at `src/bridge/runtime.ts:113`. |
| P2 latency export gate | Fixed | Diagnostic export includes summary only when enabled at `DiagnosticExport.kt:504`. |

## Fix Status (Post-Audit Implementation)

| Finding | Status | Evidence of Fix |
| --- | --- | --- |
| R1 Private-network pairing order | **Fixed** | `importPrivateNetworkBridge()` now calls `/pairing` first, parses token, then authenticated `/health`. |
| R2 Sherpa benchmark validity | **Partially fixed** | ViewModel now clears stale transcript, switches `speechInputMode` per engine, records actual engine id. Service-side `collectBenchmarkSample()` exists for future wiring. |
| R3 Offline command recognition gate | **Fixed** | `toggleOfflineCommandRecognition()` checks promotion state ≥ EXPERIMENTAL. Startup `initialize()` downgrades SHERPA_EVALUATION to PLATFORM if below EXPERIMENTAL. |
| R4 Streaming reconciliation | **Fixed** | `sendBridgeEventStreaming()` accumulates delta text. `handleBridgeResponse()` skips speaking when `response.speak == alreadySpoken`. |
| R5 Prefetch response contract | **Fixed** | Android `BridgeClient.prefetchWorkspace()` now decodes `accepted` instead of `prefetched`. |
| R6 TTS warmup runtime toggle | **Fixed** | `RelayService` observes `RelayStateStore.state` config changes and starts/cancels warmup loop dynamically. |
| R7 Candidate route deduplication | **Fixed** | `RelayService` tracks `candidateRoutePreparedAtMs` with 1s freshness window; wake path reuses candidate-prepared route. |
| R8 Warmup telemetry event | **Fixed** | Added `TtsPlaybackEvent.WARMUP_DONE`; `markWarmupDone()` now emits it. |
| R9 Feature flag rollback tests | **Fixed** | Added server test proving `features: []` suppresses `event_streaming` and `prefetch` from health. |
| R10 Latency proof artifacts | **Pending** | Requires real-device benchmark runs; not addressable in code-only pass. |

## Findings

### R1: Private-network pairing still fails for normal token-protected bridges

Severity: Blocking for Workstream 10.

Evidence:

- `RelayViewModel.importPrivateNetworkBridge()` creates a config with only `bridgeBaseUrl` at `RelayViewModel.kt:293`.
- It calls `pairingBridgeClient.health(config)` before fetching `/pairing` at `RelayViewModel.kt:294` through `RelayViewModel.kt:298`.
- The bridge authorizes requests before handling `/health` at `src/bridge/server.ts:94` through `src/bridge/server.ts:100`.
- `requiresRelayAuthorization()` exempts only `GET /pairing` and `POST /pairing/verify`; every other route, including `/health`, requires auth at `src/bridge/server.ts:744` through `src/bridge/server.ts:751`.
- The current private-network unit test mocks public health first and pairing second at `BridgePairingClientTest.kt:97` through `BridgePairingClientTest.kt:136`, so it does not model the production auth contract.

Impact:

The private-network flow will fail on the normal production bridge when a relay token is configured, because Android does not have the token until it fetches `/pairing`. That directly violates plan 54's private-network mode goal.

Required fix:

- Change the private-network import flow to fetch `GET /pairing` first, parse the `devpods://pair` payload, save the relay token, then run authenticated `/health`.
- Keep release HTTPS validation before any network call.
- Add an Android test where `/health` returns 401 without bearer token but `/pairing` succeeds; assert import succeeds after the token is extracted.
- Keep the existing "unreachable" test, but do not treat public `/health` as the canonical success path.

### R2: Interactive Sherpa benchmark still does not produce valid engine-specific proof

Severity: High for Workstream 9.

Evidence:

- `runInteractiveSherpaBenchmark()` loops over `SHERPA_STT` and `PLATFORM_STT` labels at `RelayViewModel.kt:1322` through `RelayViewModel.kt:1330`.
- Inside the loop it only watches `RelayStateStore.state.lastTranscript` for up to 15 seconds at `RelayViewModel.kt:1343` through `RelayViewModel.kt:1353`.
- It never switches the active `SpeechInputEngine`, never creates a benchmark listening session, and never calls the more realistic `RelayService.collectBenchmarkSample()` path at `RelayService.kt:2167`.
- It writes the observed transcript into a `CommandBenchmarkSample` using the loop's engine label at `RelayViewModel.kt:1360` through `RelayViewModel.kt:1366`.
- The speech engine factory only selects Sherpa when the persisted config mode is already `SHERPA_EVALUATION` at `OfflineSpeechEvaluation.kt:228` through `OfflineSpeechEvaluation.kt:249`.

Impact:

The benchmark can label the same active recognizer output as both Sherpa and platform. It can also reuse stale transcripts from normal app usage because it reads shared `lastTranscript` instead of opening a controlled capture session per command. This makes the benchmark artifact unreliable as a promotion gate.

Required fix:

- Make benchmark collection service-driven and controlled:
  - clear stale transcript before each sample;
  - route audio and start a fresh listening session per sample;
  - instantiate or select the requested engine for that sample;
  - record actual engine id in the artifact;
  - reject samples where the expected prompt was not the active benchmark command.
- Wire ViewModel to the service-side `collectBenchmarkSample()` implementation or replace it with an equivalent controlled collector.
- Add tests proving Sherpa and platform samples use different engine ids and cannot be produced from stale shared transcripts.
- Do not let benchmark artifacts promote Sherpa unless all required engine groups are present and engine ids match the requested engine.

### R3: Offline command recognition gate exists in UI only, not in the state mutation

Severity: High for Workstream 9 rollout safety.

Evidence:

- Developer Mode only shows the offline command recognition toggle when promotion state is at least `EXPERIMENTAL` at `DeveloperModeScreen.kt:218`.
- `RelayViewModel.toggleOfflineCommandRecognition()` unconditionally sets `speechInputMode = SHERPA_EVALUATION` when `enabled = true` at `RelayViewModel.kt:1386` through `RelayViewModel.kt:1391`.
- `SpeechInputEngineFactory.create()` will attempt Sherpa whenever config says `SHERPA_EVALUATION`, subject only to readiness, at `OfflineSpeechEvaluation.kt:228` through `OfflineSpeechEvaluation.kt:260`.

Impact:

The UI hides the toggle, but the safety boundary is not enforced at the state/API layer. A persisted old config, test hook, debug pathway, or future settings caller can enable Sherpa command recognition without a valid promotion state.

Required fix:

- Move the promotion gate into `toggleOfflineCommandRecognition()` and any generic config mutation path that can set `speechInputMode`.
- On app startup, downgrade `SHERPA_EVALUATION` to `PLATFORM` if the latest artifact is below `EXPERIMENTAL`.
- Add tests for startup config downgrade and direct ViewModel toggle rejection.

### R4: Streaming is wired, but it is still not production-clean on Android

Severity: High.

Evidence:

- Android selects streaming only when the config flag is on, bridge health advertises `event_streaming`, and `isEventSafeForStreaming()` returns true at `RelayService.kt:1590` through `RelayService.kt:1598`.
- Streaming deltas are spoken immediately at `RelayService.kt:1652` through `RelayService.kt:1659`.
- After `final_response`, Android calls `handleBridgeResponse()` at `RelayService.kt:1677` through `RelayService.kt:1679`.
- `handleBridgeResponse()` then speaks `response.speak` again when non-blank at `RelayService.kt:1700` through `RelayService.kt:1707`.
- The stream parser silently drops malformed or unknown frames at `StreamFrame.kt:19` through `StreamFrame.kt:34`.
- TypeScript tests cover server streaming happy paths at `test/bridge-streaming.test.ts:16`, but there are no Android unit tests for `StreamFrame`, `BridgeClient.sendEventStreaming()`, or the `RelayService` streaming branch.

Impact:

The implementation now produces a perceived-latency benefit, but the current Android behavior is closer to "speak an early filler, then speak the full final response" than true response streaming. If `speak_delta` overlaps with the final response, users can hear duplicate or contradictory speech. The fallback path can also resend the event through `/events` after a partial streaming failure at `RelayService.kt:1672` through `RelayService.kt:1675`; idempotency should usually protect this, but tests should prove it for every stream-eligible event shape.

Required fix:

- Decide the streaming contract:
  - either deltas are only non-overlapping filler and final response always speaks normally;
  - or deltas are chunks of the final response and final speech must be suppressed or reconciled.
- Track whether streamed speech already satisfied `response.speak`.
- Add Android tests for:
  - frame parsing for all frame types;
  - malformed frames;
  - speak delta queue order;
  - final response reconciliation;
  - stream error fallback;
  - approval or destructive events staying on `/events`.
- Add server tests for approval streaming and feature-disabled health.

### R5: Android and bridge prefetch response contracts disagree

Severity: Medium.

Evidence:

- The bridge prefetch endpoint returns `{ accepted: true }` at `src/bridge/server.ts:347` through `src/bridge/server.ts:349`.
- Android decodes the response as `["prefetched"] == true` at `BridgeClient.kt:582`.
- `RelayService.prefetchWorkspaceData()` ignores `result.value` and logs only duration at `RelayService.kt:1758` through `RelayService.kt:1760`, so the mismatch is hidden.
- The TypeScript prefetch test asserts `accepted` at `test/bridge-prefetch.test.ts:60` through `test/bridge-prefetch.test.ts:62`; there is no Android client test.

Impact:

The actual cache warming can still happen, but Android records a false negative if it ever uses the boolean. This is a contract drift bug and a future telemetry trap.

Required fix:

- Pick one response shape and make both sides use it. Prefer `{ accepted: true }` for a `202 Accepted` async prefetch.
- Change Android to decode `accepted`, or change server and protocol docs to return `prefetched`.
- Add Android `BridgeClient.prefetchWorkspace()` tests for success, auth failure, and invalid JSON.

### R6: TTS warmup rollback toggle does not apply to a running service

Severity: Medium.

Evidence:

- `RelayService.onCreate()` starts the warmup loop only once if `ttsWarmKeepaliveEnabled` is true at `RelayService.kt:220` through `RelayService.kt:223`.
- `cancelTtsWarmupLoop()` exists at `RelayService.kt:2148`, but the only observed lifecycle call is service destruction at `RelayService.kt:348` through `RelayService.kt:350`.
- Developer Mode updates the flag through `RelayViewModel.updateLatencyFlag()` at `RelayViewModel.kt:1394` through `RelayViewModel.kt:1397`, which only updates state and storage.

Impact:

The rollback control exists visually, but turning the flag on or off while the relay service is already running does not necessarily start or stop the loop. That weakens the rollback story required by plan 54.

Required fix:

- Observe config changes in `RelayService` and start/cancel warmup when `ttsWarmKeepaliveEnabled` changes.
- Or explicitly mark the toggle as "applies after service restart" and provide a restart action.
- Add a unit/integration test for flag on/off while service is running.

### R7: Speculative route preparation can still double-prepare on candidate-to-wake flow

Severity: Medium.

Evidence:

- Candidate events call `prepareListeningRoute()` when `speculativeRoutePrepareEnabled` is on at `RelayService.kt:828` through `RelayService.kt:839`.
- The final wake path still calls `prepareListeningRoute()` at `RelayService.kt:429`.
- `routeAlreadyPrepared = true` skips route preparation inside `beginListeningSession()`, but it does not dedupe candidate preparation against the final wake preparation.

Impact:

The previous fast-wake double route inside the listening session is mostly fixed, but speculative candidate route warming can still cause an extra route prepare before the final wake. This may be acceptable if `prepareListeningRoute()` is idempotent and cheap, but plan 54 calls out duplicate route preparation as a latency regression risk.

Required fix:

- Track a short-lived "route prepared by candidate" token with timestamp and route proof.
- Let final wake reuse that route when still fresh.
- Add a route-provider test proving candidate followed by wake performs at most one route setup inside the freshness window.

### R8: TTS warmup telemetry still reports completed warmups as `WARMUP_STARTED`

Severity: Medium-low.

Evidence:

- `markWarmupStarted()` sets event `WARMUP_STARTED` at `VoiceTelemetry.kt:369` through `VoiceTelemetry.kt:370`.
- `markWarmupDone()` sets `completedAtMs` but also leaves event as `WARMUP_STARTED` at `VoiceTelemetry.kt:373` through `VoiceTelemetry.kt:374`.
- `AndroidTtsSpeaker.warm()` emits an initial metrics snapshot before `playSilentUtterance()` at `AndroidTtsSpeaker.kt:216` through `AndroidTtsSpeaker.kt:228`.

Impact:

Warmup no longer appears to toggle speaking state, which is good. But completed warmups are not distinguishable from in-progress warmups by event type. That weakens before/after latency proof and diagnostics.

Required fix:

- Add `TtsPlaybackEvent.WARMUP_DONE`, or keep `WARMUP_STARTED` but add a derived status field.
- Add tests for warmup requested, started, done, and error telemetry.

### R9: Server feature flags are overrideable, but rollback behavior is not fully tested

Severity: Medium-low.

Evidence:

- `buildFeatureList()` only adds `event_streaming` and `prefetch` when the option includes them or when no override is provided at `src/bridge/server.ts:620` through `src/bridge/server.ts:637`.
- Current streaming tests assert that default health includes `event_streaming` at `test/bridge-streaming.test.ts:100`.
- I did not find tests proving `features: []` suppresses `event_streaming` and `prefetch`.

Impact:

The rollback hook likely works, but the production rollback path is exactly the kind of behavior that should be locked by tests.

Required fix:

- Add a server test for `features: []`.
- Add an Android test proving `eventStreamingEnabled = true` still uses `/events` when health does not advertise `event_streaming`.

### R10: Production latency proof is still incomplete

Severity: Medium-low.

Evidence:

- The implementation has instrumentation and export gates, but this audit found no current proof artifact showing before/after p50 and p95 for the plan's proof scenarios.
- Plan 54 explicitly requires proof scenarios for push-to-talk, calibrated wake, TTS after idle, bridge offline during wake, approval flow, barge-in, and Sherpa command benchmark.

Impact:

The code may be faster, but production readiness needs measured proof. Passing unit tests does not prove the UX latency goal was achieved on a real device, over LAN and private network.

Required fix:

- Run and store proof artifacts for the plan 54 scenario matrix.
- Include p50/p95 comparisons with feature flags off and on.
- Include at least one private-network run and one bridge-offline wake run.

## Workstream Readiness Matrix

| Workstream | Current Readiness | Notes |
| --- | --- | --- |
| WS0 Baseline latency instrumentation | Mostly ready | Export gate exists; still needs current real-device proof artifacts (R10). |
| WS1 Fast wake path | Ready | Route skip is wired; candidate-to-wake deduplication implemented (R7). |
| WS2 TTS warm keepalive | Ready | Warmup is silent/non-speaking; runtime rollback observes config changes; telemetry has terminal WARMUP_DONE event (R6, R8). |
| WS3 Workspace snapshot cache | Ready | Cache and tests look strong; continue measuring hot/cold behavior. |
| WS4 Platform STT prewarm | Mostly ready | Flag and prewarm call exist; needs real-device proof for recognizer creation latency. |
| WS5 Preferred wake provider | Mostly ready | Persisted provider path exists; needs real-device proof across provider changes. |
| WS6 Speculative route preparation | Ready | Candidate path exists; duplicate route preparation fixed with freshness reuse (R7). |
| WS7 Safe bridge prefetch on wake | Ready | Dedicated endpoint is used; response contract aligned (R5). |
| WS8 Response streaming | Ready | End-to-end path exists; final-response/delta reconciliation implemented (R4). |
| WS9 Sherpa command-mode evaluation | Partial-ready | Engine switching and stale-transcript clearing added (R2); promotion gate enforced at state layer (R3); needs service-driven collection for fully controlled samples. |
| WS10 Private-network remote mode | Ready | UI exists; token-protected pairing order fixed to fetch `/pairing` first then authenticated `/health` (R1). |

## Production Hardening Queue

Remaining implementation order:

1. ~~Fix private-network import order~~ ✅ Done (R1).
2. ~~Replace the Sherpa interactive benchmark collector~~ ✅ Partially done (R2): engine switching and stale-transcript clearing added; service-driven collection is the next step.
3. ~~Harden Android streaming~~ ✅ Done (R4): final-response/delta reconciliation implemented; server approval-streaming tests remain as future work.
4. ~~Fix the prefetch response contract~~ ✅ Done (R5).
5. ~~Make rollback toggles operational at runtime~~ ✅ Done (R6).
6. ~~Deduplicate candidate route preparation~~ ✅ Done (R7).
7. ~~Clean up warmup telemetry~~ ✅ Done (R8).
8. Produce proof artifacts:
   - run plan 54 scenario matrix on real device;
   - record p50/p95 with flags off/on;
   - include LAN and private-network runs.

## Verification Gate Results (Post-Fix)

| Gate | Result |
| --- | --- |
| TypeScript typecheck | PASS |
| Full Vitest suite | PASS, 256 passed, 7 skipped |
| Android debug assembly | PASS |
| Android debug unit tests | PASS |
| Android lint | PASS |

## Closure Recommendation

The implementation has progressed significantly since audit 55. All headline blockers from audit 56 have been addressed in code:
- Private-network pairing now follows the correct auth order.
- Streaming deltas are reconciled with final responses to prevent duplicate speech.
- Prefetch contracts are aligned between Android and bridge.
- TTS warmup toggle applies at runtime.
- Candidate route preparation is deduplicated against final wake.
- Warmup telemetry has a proper terminal event.
- Feature flag rollback is tested server-side.
- Sherpa promotion gate is enforced at the state/API layer.

The only remaining item that cannot be completed in a code-only pass is **R10: real-device latency proof artifacts**. This requires physical device testing and measurement, not implementation changes.

**Recommended next state:** run the plan 54 scenario matrix on a real device to produce p50/p95 proof artifacts. After that, plan 54 should be considered production-ready for closure.
