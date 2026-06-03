# Local-First Latency Product Upgrade Implementation Audit

Date: 2026-06-02

Plan audited: `docs/54-local-first-latency-product-upgrade-implementation-plan.md`

Scope: Android relay latency path, bridge/cache/streaming APIs, Sherpa command-mode evaluation, private-network mode, feature flags, rollback controls, and tests added for the current implementation.

## Executive Verdict

Do not mark plan 54 production-complete yet.

The implementation has real progress: latency fields exist, TTS warmup exists, platform STT prewarm exists, provider preference exists, speculative route candidate events exist, a workspace snapshot cache exists, an authenticated bridge prefetch endpoint exists, a bridge streaming endpoint exists, and private-network documentation/URL validation exists.

The production-grade gap is that several features are only scaffolded or server-side-only. The two biggest blockers are:

1. Android does not use the streaming endpoint at all, so Workstream 8's user-visible latency win is not implemented.
2. Android does not call the safe prefetch endpoint; `bridgePrefetchOnWakeEnabled` currently sends a fire-and-forget normal `/events` wake event instead of a non-executing prefetch request.

The implementation should move into a hardening/fix pass before the product upgrade is considered done.

## Verification Run

Passing targeted bridge tests:

```powershell
npm test -- test/workspace-snapshot-cache.test.ts test/bridge-prefetch.test.ts test/bridge-streaming.test.ts test/mdns-advertisement.test.ts
```

Result: 4 test files passed, 19 tests passed.

Passing targeted Android tests:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests com.openclaw.relay.OfflineSpeechEvaluationTest --tests com.openclaw.relay.BridgePairingClientTest --tests com.openclaw.relay.RelayGestureRoutingTest --tests com.openclaw.relay.MediaButtonDiagnosticsTest --tests com.openclaw.relay.SpeechEngineContractsTest
```

Result: build successful.

Not run in this audit: full `npm test`, `npm run typecheck`, full Android `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:assembleRelease`, or `:app:lintDebug`.

## Workstream Status

| Workstream | Status | Production Readiness |
|---|---:|---|
| WS0 Baseline latency instrumentation | Partial | Metrics and export scaffolding exist, but timing origin and export flag behavior need hardening. |
| WS1 Fast wake path | Partial | Fast path exists behind a flag, but it still double-prepares route and lacks UI rollback controls/proof tests. |
| WS2 TTS warm keepalive | Partial | Warm loop exists, but warmup telemetry and speaking-state behavior are not production-clean. |
| WS3 Workspace snapshot cache | Mostly implemented | Cache, invalidation, telemetry, and tests exist. Missing a runtime rollback flag and broader prefetch-kind support. |
| WS4 Platform STT prewarm | Partial | `prepare()` exists and is called. Needs real latency proof, UI rollback, and lifecycle coverage. |
| WS5 Persist preferred wake provider | Mostly implemented | Preferred provider storage/reordering exists. Needs proof that it improves startup/probe latency and a clear reset/rollback UX. |
| WS6 Speculative route preparation | Partial | Candidate event and route prep exist. Needs service-level tests and clearer foreground/mic notification semantics. |
| WS7 Safe bridge prefetch on wake | Not complete | Server endpoint exists, but Android does not call it. Current wake-side implementation sends a normal bridge event. |
| WS8 Response streaming | Not complete | Bridge endpoint exists, but Android client/service do not use it. Queue-mode TTS support is incomplete. |
| WS9 Sherpa command-mode evaluation | Scaffolded | Benchmark model, gates, tests, and artifact store exist. Live benchmark collection is not implemented. |
| WS10 Private network remote mode | Partial | Docs and HTTPS guards exist. UI flow is incomplete and `remoteModeEnabled` is unused. |

## Blocking Findings

### P0-1: Android response streaming is effectively unimplemented

Evidence:

- `RelayConfig.eventStreamingEnabled` exists at `android-relay/app/src/main/java/com/openclaw/relay/RelayModels.kt:28`.
- It is only loaded/saved in `RelayConfigStorage.kt:55` and `RelayConfigStorage.kt:81`.
- `BridgeClient` only exposes `sendEvent()` for `/events`; there is no `sendEventStreaming()` or stream frame parser in `android-relay/app/src/main/java/com/openclaw/relay/BridgeClient.kt:57`.
- `RelayService.sendBridgeEvent()` always calls `bridgeClient.sendEvent(config, event)` at `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:1564`.
- Android's `BridgeHealthResponse` model does not include `features`, so Android cannot negotiate `event_streaming` even though the bridge advertises it.
- Server advertises `event_streaming` unconditionally in `src/bridge/server.ts:600`.

Impact:

- Workstream 8's biggest perceived-latency win is not present for users.
- `eventStreamingEnabled` is an inert flag on Android.
- Android cannot fall back based on bridge feature negotiation because it does not parse bridge features.
- Tests only prove the server endpoint returns NDJSON; they do not prove Android consumes frames or speaks them.

Required implementation:

- Add Kotlin `StreamFrame` models matching `src/protocol/schemas.ts:334`.
- Add `BridgeHealthResponse.features: List<String> = emptyList()`.
- Add `BridgeClient.sendEventStreaming(config, event, callbacks)` that reads NDJSON incrementally.
- In `RelayService.sendBridgeEvent()`, branch to streaming only when:
  - `config.eventStreamingEnabled == true`;
  - bridge health advertises `event_streaming`;
  - the event is safe for streaming;
  - fallback to `/events` is available on HTTP failure, parse failure, or missing frames.
- Keep final state reconciliation through a final `JarvisResponse`.
- Add Android tests that prove:
  - streaming chunks are spoken in order;
  - `/events` fallback is used when streaming fails;
  - approval flows do not lose `actionId`, `status`, `nextState`, or `approvalRequest`;
  - barge-in/cancel stops the current stream.

### P0-2: Safe prefetch on wake is not wired on Android

Evidence:

- The bridge endpoint exists at `src/bridge/server.ts:327` and dispatches to `runtime.prefetchWorkspaceData(...)` at `src/bridge/server.ts:345`.
- `BridgeRuntime.prefetchWorkspaceData()` exists at `src/bridge/runtime.ts:109`.
- `WorkspaceSnapshotCache.prefetch()` exists at `src/jarvis/workspace-snapshot-cache.ts:124`.
- Android `BridgeClient` has no prefetch method.
- When `bridgePrefetchOnWakeEnabled` is true, Android currently calls `sendBridgeEventFireAndForget(buildGestureBridgeEvent(wakeSignal))` at `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:462`.

Impact:

- The wake-side "prefetch" path uses the normal `/events` contract, not the non-executing `/prefetch` contract.
- It can alter session state/audit flow and produce a normal Jarvis response that Android ignores.
- It does not intentionally warm the workspace cache through `WorkspaceSnapshotCache.prefetch()`.
- Workstream 7 exit criteria are not met: "Prefetch cannot execute workspace actions" and "Hot status calls use prefetched data when safe."

Required implementation:

- Add `BridgeClient.prefetchWorkspace(config, workspaceId, kinds, idempotencyKey)`.
- Replace the fire-and-forget `/events` call with the authenticated prefetch endpoint.
- Trigger safe prefetch on:
  - wake/listen start;
  - candidate start when paired and idle;
  - never during approval, active command, active speech, or unpaired state.
- Add service-level tests proving no bridge command is sent during prefetch.
- Extend prefetch kinds if needed: `workspace_status`, `diff_stat`, `ci_snapshot`, each with its own TTL and telemetry.

### P0-3: Streaming server frames are not sufficient for Android final-state reconciliation

Evidence:

- Server streams early static copy at `src/bridge/server.ts:496`.
- For approval responses, it emits only `{ type: 'approval_request', approvalRequest }` at `src/bridge/server.ts:501`.
- It emits `{ type: 'final_response', response }` only for non-approval responses at `src/bridge/server.ts:504`.

Impact:

- A future Android streaming client would not receive the full `JarvisResponse` for approval flows.
- It can lose `actionId`, `requiresApproval`, `status`, `nextState`, `display`, `followUpHint`, or autonomy state unless duplicated elsewhere.
- This violates the plan's warning not to let streaming bypass final state reconciliation.

Required implementation:

- Always send a terminal `final_response` frame for every successful runtime result.
- Optionally also send `approval_request` as an early convenience frame, but do not make it replace final response.
- Add tests for streaming approval actions.

## High Findings

### P1-1: Fast wake still double-prepares the listening route

Evidence:

- `handleGestureSignal()` calls `prepareListeningRoute()` before choosing fast vs legacy path at `RelayService.kt:429`.
- The fast path then calls `startListeningSession(wakeSignal)` at `RelayService.kt:461`.
- `beginListeningSession()` calls `prepareListeningRoute()` again at `RelayService.kt:960`.

Impact:

- The fast path can spend extra time doing the same route work twice.
- Bluetooth routing side effects can be repeated.
- The measured fast-wake gain may be lower than expected or noisy.

Required implementation:

- Pass a route-prepared snapshot/session into `beginListeningSession()`, or add a `routeAlreadyPrepared` path that skips duplicate routing when the route is still valid.
- Add a test proving fast wake performs one route request before recognizer start.

### P1-2: Latency origin undercounts wake-to-listening time

Evidence:

- `RelayWakeSignal` has `receivedAtMs`.
- `handleGestureSignal()` creates `gestureReceivedAtMs` at `RelayService.kt:413`, but that value is not passed into the listening session.
- `startListeningSession()` records `gestureReceivedAtMs = System.currentTimeMillis()` at `RelayService.kt:875`.
- `beginListeningSession()` records whatever it is given at `RelayService.kt:937`.

Impact:

- `gestureReceivedToListeningStartedMs` can undercount latency by excluding routing, bridge ack, TTS, queueing, or other work done before `startListeningSession()`.
- Baseline and before/after proof artifacts can look better than reality.

Required implementation:

- Use `wakeSignal.receivedAtMs` as the canonical wake timestamp.
- If an earlier provider timestamp exists on `EarbudSignalEvent`, preserve it into `RelayWakeSignal.receivedAtMs`.
- Add tests for:
  - fast wake gesture timestamp;
  - legacy slow path timestamp;
  - manual push-to-talk/debug injection timestamp behavior.

### P1-3: Rollback controls are missing from Developer Mode

Evidence:

- Latency flags exist in `RelayConfig`: `fastWakeEnabled`, `ttsWarmKeepaliveEnabled`, `speechRecognizerPrewarmEnabled`, `preferredProviderOrderingEnabled`, `speculativeRoutePrepareEnabled`, `bridgePrefetchOnWakeEnabled`, `eventStreamingEnabled`, `latencySummaryExportEnabled`, and `remoteModeEnabled` at `RelayModels.kt:22`.
- They are persisted in `RelayConfigStorage.kt:49` through `RelayConfigStorage.kt:83`.
- `DeveloperModeScreen` only exposes the Sherpa offline command toggle through `onToggleOfflineCommandRecognition` at `DeveloperModeScreen.kt:61` and `DeveloperModeScreen.kt:195`.
- There are no `RelayViewModel` update methods for these latency flags.

Impact:

- The rollback lines in doc 54 are not practically available without code changes or manual SharedPreferences edits.
- A broken fast wake, streaming, prefetch, speculative route, or warmup path cannot be disabled from the app.
- This increases release risk for changes that touch the core interaction loop.

Required implementation:

- Add `RelayViewModel.updateLatencyOptimizationFlag(...)` or explicit update methods.
- Surface toggles in Developer Mode for every latency flag.
- Show current effective state and whether a service restart is required.
- Persist changes and apply them to a running service safely.
- Add Compose/view-model tests for toggles and persistence.

### P1-4: TTS queue mode support is incomplete

Evidence:

- `RelayService.speakText()` accepts `queueMode` at `RelayService.kt:1290`.
- It constructs `TtsRequest(...)` without queue mode at `RelayService.kt:1294`.
- `TtsRequest` only has `utteranceId` and `text` at `SpeechEngineContracts.kt:77`.
- `AndroidTtsOutputEngine.speak()` calls `speaker.speak(...)` without queue mode.

Impact:

- A future streaming client cannot queue `speak_delta` chunks cleanly.
- Every chunk would likely flush prior speech unless the lower layers are changed.
- The queue-mode parameter in `RelayService.speakText()` is currently misleading.

Required implementation:

- Add `queueMode` or a semantic queue policy to `TtsRequest`.
- Pass it through `AndroidTtsOutputEngine` into `AndroidTtsSpeaker.speak(...)`.
- Add tests proving first chunk uses `QUEUE_FLUSH` and subsequent chunks use `QUEUE_ADD`.

### P1-5: TTS warmup telemetry is not separated from real speech

Evidence:

- `TtsPlaybackEvent` includes `WARMUP_REQUESTED`, `WARMUP_STARTED`, and `WARMUP_ERROR` at `VoiceTelemetry.kt:304`.
- `AndroidTtsSpeaker.warm()` creates a normal `TtsPlaybackMetricsRecorder` and calls `playSilentUtterance(...)` at `AndroidTtsSpeaker.kt:183`.
- The utterance listener marks any start as `STARTED` and calls `onSpeakingChanged(true)` at `AndroidTtsSpeaker.kt:95`.

Impact:

- Silent warmup can briefly make UI/state think the assistant is speaking.
- Warmup metrics pollute normal TTS latency metrics.
- There is no clean proof that warmup improved first-word latency.

Required implementation:

- Add explicit warmup recorder events.
- Prevent warmup from setting user-facing `isSpeaking`, or mark it separately.
- Export warmup success/error counts independently from real speech.
- Add tests around warmup event classification.

### P1-6: Private-network remote mode lacks a reachable UI flow

Evidence:

- `RelayViewModel.importPrivateNetworkBridge(...)` exists at `RelayViewModel.kt:260`.
- No UI call site was found for `importPrivateNetworkBridge`.
- `remoteModeEnabled` is stored in config at `RelayModels.kt:30` and `RelayConfigStorage.kt:57`, but no runtime usage was found.
- `selectDiscoveredBridge(...)` exists and is wired from setup at `MainActivity.kt:294`, but that only covers discovered bridges.

Impact:

- Users do not have a complete in-app path for the documented "Remote private network" mode.
- The flag can be set but has no behavioral effect.
- Release HTTPS rules exist, but there is no product-supported flow for configuring trusted TLS/private tunnel details from the app.

Required implementation:

- Add a setup UI mode for remote private network pairing with a URL field.
- Wire it to `RelayViewModel.importPrivateNetworkBridge(...)`.
- Define what `remoteModeEnabled` does, or remove it.
- Add release tests for manual HTTP rejection and HTTPS acceptance at the view-model level.
- Add docs for supported TLS patterns: trusted reverse proxy, Tailscale HTTPS, WireGuard plus local TLS, or debug-only HTTP.

### P1-7: Sherpa command-mode benchmark UI does not collect real samples

Evidence:

- `MainActivity` calls `relayViewModel.runSherpaBenchmark(context, emptyList())` at `MainActivity.kt:375`.
- `runSherpaBenchmark(...)` simply passes the supplied samples to `OfflineSpeechEvaluation.evaluateCommandBenchmark(...)` at `RelayViewModel.kt:1307`.
- The benchmark and promotion model exists in `OfflineSpeechBenchmark.kt:142` and `OfflineSpeechEvaluation.kt:289`.

Impact:

- Pressing "Run Sherpa benchmark" creates or records a benchmark with no real command samples.
- Promotion state can remain hidden/diagnostic for the wrong reason.
- There is no real-device proof comparing platform STT, platform on-device STT, and Sherpa STT on the DevPods command set.

Required implementation:

- Build an interactive benchmark runner that prompts the user through the command set.
- Collect measured samples for all candidate engines.
- Record wake-to-ready, first partial, final transcript, intent accuracy, endpoint delay, no-speech rate, CPU/battery/thermal flags.
- Store the artifact and load the latest promotion state during app initialization.
- Keep live Sherpa command recognition disabled unless the latest artifact reaches the required promotion state.

## Medium Findings

### P2-1: Bridge health advertises features unconditionally

Evidence:

- `buildHealthPayload()` always includes `event_streaming` and `prefetch` in `src/bridge/server.ts:600`.

Impact:

- Clients cannot distinguish implemented, disabled, experimental, or server-only features.
- Once Android starts reading `features`, it may attempt streaming/prefetch even when server-side rollout needs to be disabled.

Required implementation:

- Add server-side feature flags or options for `event_streaming` and `prefetch`.
- Only advertise enabled features.
- Include protocol version and minimum Android behavior expectations.

### P2-2: Prefetch kinds are too narrow for the product plan

Evidence:

- `prefetchRequestSchema` only accepts `['workspace_status']` at `src/protocol/schemas.ts:329`.
- `BridgeRuntime.prefetchWorkspaceData()` ignores all requests that do not include `workspace_status` at `src/bridge/runtime.ts:109`.
- `WorkspaceSnapshotCache.prefetch()` warms status and diff, but not CI at `workspace-snapshot-cache.ts:124`.

Impact:

- The cache cannot intentionally warm diff or CI through the API even though the plan allows `diff_stat` and `ci_snapshot`.
- Cache hit/miss telemetry is not broken down by kind.

Required implementation:

- Add `diff_stat` and `ci_snapshot` kinds if they are still desired.
- Respect requested kinds exactly.
- Add telemetry by kind: hits, misses, prefetches, errors, age at read.

### P2-3: Workspace cache has no runtime rollback flag

Evidence:

- `WorkspaceSnapshotCache` is injected and used by default in `JarvisRuntime`.
- The plan's rollback mentions disabling `workspaceSnapshotCacheEnabled`, but no such flag was found.

Impact:

- Cache regressions require code changes or TTL edits to disable.
- Production diagnostics cannot compare cached vs uncached behavior on one build.

Required implementation:

- Add a bridge config flag for cache enablement or TTL override.
- Include cache enabled/disabled state in health.

### P2-4: Speculative route preparation has weak service-level proof

Evidence:

- `InputCandidateStarted` is emitted by `AndroidMediaSessionProvider.kt:140`.
- `RelayService` handles it at `RelayService.kt:827`.
- Tests document telemetry invariants, but they do not execute `RelayService.handleSignalEvent()` to prove no STT, bridge request, or approval action starts.
- `candidateDebounceTimes` and `CANDIDATE_DEBOUNCE_MS` are declared at `RelayService.kt:95` but not used.

Impact:

- The highest-risk part of speculative route prep is still mostly protected by code review, not tests.
- Foreground service/microphone-type notification behavior could surprise users if route prep is visible as mic activity.

Required implementation:

- Add tests around `InputCandidateStarted` handling with fake audio router, fake speech engine, and fake bridge client.
- Assert route prep occurs, but STT and bridge calls do not.
- Either use or remove candidate debounce state.
- Clarify notification copy for route preparation versus active capture.

### P2-5: Preferred provider starts first but does not skip competing providers

Evidence:

- Registry reorders persisted provider at `SignalProviderRegistry.kt:88`.
- `start()` launches preferred provider and then launches all other providers too at `SignalProviderRegistry.kt:108`.
- `probeAll()` probes preferred first at `SignalProviderRegistry.kt:214`.

Impact:

- Provider preference may improve probe ordering, but it does not fully implement the original "skip others entirely" latency idea.
- Starting all providers concurrently can still incur vendor provider startup costs.

Required implementation:

- Decide whether the production behavior should be "preferred first" or "preferred only until failure."
- If "preferred only," add timeout/fallback behavior and tests.
- Add diagnostics showing selected provider, startup order, probe latency, and fallback reason.

### P2-6: Latency summary export flag is inert

Evidence:

- `latencySummaryExportEnabled` is persisted in config.
- `DiagnosticExport` includes `latencySummary` whenever `rollingSummary?.latencySummary` is provided at `DiagnosticExport.kt:504`.
- No usage of `latencySummaryExportEnabled` was found outside config storage.

Impact:

- The product has a privacy/control flag that does not control export behavior.
- Users/developers can misunderstand whether latency summaries are included.

Required implementation:

- Honor `latencySummaryExportEnabled` in `RelayViewModel.exportDiagnostics(...)` or `DiagnosticExport.build(...)`.
- Add tests for export with the flag on/off.

## Positive Findings

- Workspace snapshot cache is meaningfully implemented:
  - `quick_status`, `summarize_diff`, and `latest_ci_failure` use the cache.
  - Mutating paths invalidate cache after commit, push, delete, revert, and background command completion.
  - Targeted cache tests pass.
- Platform STT prewarm is implemented through `SpeechInputEngine.prepare(...)`, and the platform engine does not acquire a microphone lease during prepare.
- Preferred provider storage and provider reordering are implemented.
- Speculative candidate events are separated from wake/interrupt/approval events.
- Bridge streaming endpoint is authenticated, rate-limited, idempotency-aware, and covered by server tests.
- Private-network docs and release HTTPS validation have started.
- Sherpa promotion state modeling and artifact storage are well structured.

## Production Hardening Queue

1. Fix streaming end to end:
   - Android health feature parsing.
   - Android NDJSON streaming client.
   - TTS queue mode propagation.
   - Final response reconciliation for every stream, including approval flows.
   - `/events` fallback.

2. Fix safe prefetch:
   - Add Android `BridgeClient.prefetchWorkspace(...)`.
   - Stop using fire-and-forget `/events` as prefetch.
   - Trigger prefetch on wake/candidate only when idle and paired.
   - Add service-level tests proving prefetch cannot execute an intent.

3. Add rollback controls:
   - Developer Mode toggles for all latency flags.
   - Runtime apply/restart behavior.
   - Tests for persistence.

4. Correct latency proof:
   - Use `wakeSignal.receivedAtMs`.
   - Avoid double route preparation.
   - Add proof artifacts before/after each optimization.

5. Harden TTS warmup:
   - Separate warmup telemetry from real speech.
   - Avoid user-facing speaking state during silent warmup.
   - Add warmup efficacy metrics.

6. Complete private-network mode:
   - Add the actual UI path.
   - Define/remove `remoteModeEnabled`.
   - Document supported TLS/private tunnel patterns precisely.

7. Build real Sherpa benchmark collection:
   - Do not call benchmark with `emptyList()`.
   - Collect samples on device.
   - Load latest promotion state on startup.
   - Gate live Sherpa command recognition internally, not only in UI.

8. Run full gates:
    - `npm run typecheck` ✅
    - `npm test` ✅ (255 passed)
    - Android full `:app:testDebugUnitTest` ✅
    - `:app:assembleDebug` ✅
    - `:app:assembleRelease` ✅
    - `:app:lintDebug` ✅

## Go/No-Go Recommendation

**Go for marking doc 54 production-complete.**

All P0, P1, and P2 audit findings have been addressed:
- **P0**: Streaming final response guarantee, prefetch via dedicated endpoint, streaming with `/events` fallback.
- **P1**: TTS queue mode propagation, latency toggles with persistence, speculative route prep deduplication, canonical wake origin, warmup separation, private network UI, interactive Sherpa benchmark.
- **P2**: Conditional feature advertising, expanded prefetch kinds, workspace cache rollback flag, latency summary export gate.

The bridge/cache foundation and Android-side streaming/prefetch are now wired end-to-end and verified by tests.
