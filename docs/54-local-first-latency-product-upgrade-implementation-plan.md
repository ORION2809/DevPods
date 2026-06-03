# DevPods Local-First Latency And Product Upgrade Implementation Plan

Generated: 2026-06-01

## Executive Verdict

Your product direction is valid, and it matches the strongest architectural property in the current codebase: DevPods is a local-first Android relay plus desktop bridge. The right upgrade path is not to add a cloud backend. The right path is to make the local loop feel instant, prove it with latency metrics, and keep every privacy and approval boundary intact.

One important codebase correction: the current Android wake path does more work than the mental latency model assumes. In `RelayService.handleGestureSignal()`, a wake gesture currently sends a bridge `wake_and_listen` event, waits for the bridge response, speaks "Jarvis active", and only then starts STT via `startListeningSession()`. That means the first optimization should be a fast wake path that opens STT immediately after local readiness, while any bridge-side prefetch happens in parallel.

## Non-Negotiable Product Rules

- Keep the bridge intentionally local. Do not add a cloud command relay.
- Do not send code diffs, git state, file paths, CI failures, raw audio, or transcripts to a third-party backend by default.
- Do not pre-execute destructive or approval-gated intents.
- Do not weaken policy approvals, hard approvals, approval expiry, idempotency, schema validation, rate limiting, or audit logging.
- Do not shorten accessibility-sensitive timeouts only to improve latency.
- Do not make Sherpa STT the default until real-device proof shows it is better for DevPods commands.
- Treat mesh VPN support as private network reachability, not as a cloud backend.

## Current Architecture Confirmed

The codebase matches the local-only architecture:

| Area | Current evidence |
| --- | --- |
| Desktop bridge | `package.json` exposes `dev:bridge` and CLI start commands for a TypeScript bridge. |
| Bridge server | `src/bridge/server.ts` owns `/health`, `/pairing`, `/events`, outbox, preferences, reminders, habits, nudges, and quick-start. |
| Local workspace access | `src/jarvis/runtime.ts`, `src/adapters/git.ts`, and `src/adapters/ci.ts` read local git, CI metadata, files, and workspace commands. |
| Android transport | `BridgeClient.kt` uses OkHttp request/response calls to the paired bridge URL. |
| Pairing/discovery | `src/bridge/mdns.ts`, `BridgeDiscoveryManager.kt`, and `RelayViewModel.selectDiscoveredBridge()` implement LAN discovery and pairing. |
| Release transport guard | Release Android rejects cleartext discovered bridge URLs in `RelayViewModel.selectDiscoveredBridge()`. |
| Earbud input | `SignalProviderRegistry.kt` starts vendor providers plus Android MediaSession and assistant fallback providers. |
| Calibration | `CalibratedGestureRouter.kt` routes proven gestures from persisted profiles. |
| STT | `SpeechInputEngine`, `PlatformSpeechRecognizerEngine`, `AndroidSpeechRecognizer`, and `SherpaSpeechInputEngine` already exist. |
| TTS | `AndroidTtsSpeaker` and `AndroidTtsOutputEngine` already exist, with playback metrics. |
| Metrics | `VoiceTelemetry.kt` already tracks route, recognizer creation, partial/final STT, TTS start, and TTS completion. |

## Idea Validation Against Current Code

| Idea | Verdict | Codebase reality | Required adjustment |
| --- | --- | --- | --- |
| Local-only bridge, no cloud backend | Valid and already true | Bridge and workspace reads are local. | Preserve this as a product invariant. |
| Mesh VPN for remote access | Valid with caveats | Manual bridge URL pairing can work over a private network; mDNS discovery is LAN-specific and release builds require HTTPS for discovered bridges. | Add a documented "private network remote mode"; do not assume mDNS or cleartext HTTP works in release. |
| Provider detection: persist winning provider | Valid, but not exactly as described | `SignalProviderRegistry` starts all providers and maintains a preferred provider, but does not persist the last successful wake provider. | Persist the proven wake provider and start/order it first. Do not remove universal fallbacks or health providers. |
| STT pre-warm | Valid and high impact | `AndroidSpeechRecognizer` lazily creates recognizers, but `PlatformSpeechRecognizerEngine.start()` currently calls `resetSession()` before every start, destroying prewarm value. | Add an explicit `prepare()`/`prewarm()` path and stop destroying the recognizer on every healthy session start. |
| Start audio routing before gesture completes | Valid with privacy caveat | `MediaButtonTapDetector` only emits final gestures after tap timeout/long-press threshold. | Add a non-recording gesture-candidate signal on first down; prepare route only, do not capture audio or send commands. |
| Sherpa for short commands | Valid as an experimental target | Real Sherpa STT engine exists behind `SpeechInputMode.SHERPA_EVALUATION`, but command length is unknown before recognition. | Benchmark Sherpa as a command-mode engine; promote only after proof. Do not dynamically choose by word count before STT. |
| Cache git status | Valid and straightforward | `JarvisRuntime.quickStatus()` calls `getWorkspaceStatus()` every time; `getWorkspaceStatus()` spawns multiple git commands. | Add a workspace snapshot cache with short TTL and explicit invalidation after mutations. |
| Prefetch git on wake | Valid after wake path change | There is no prefetch endpoint or cache warmer today. | Add a safe read-only bridge prefetch endpoint or event, triggered on wake/candidate, never for mutating actions. |
| TTS keep-warm | Valid | `AndroidTtsSpeaker` initializes TTS in service creation, but has no keepalive and ignores blank text. | Add `warm()` using `TextToSpeech.playSilentUtterance()` where available, plus metrics. |
| Response streaming | Valid, but a protocol change | `/events` returns one full JSON response; `BridgeClient.sendEvent()` reads the whole body; TTS uses queue flush. | Add `/events/stream` with feature negotiation, Android streaming client, and TTS queue modes. Keep `/events` fallback. |
| Do not touch approvals/schema/circuit breaker | Valid | Policy, schema, idempotency, and approval flows are safety boundaries. | Keep these out of the latency trade space. |

## Updated Priority Order

The original order is good, but the code audit changes the first move:

1. Fast wake path: stop waiting for bridge TTS before STT.
2. TTS warm keepalive.
3. Workspace status cache.
4. Platform STT prewarm.
5. Persist preferred wake provider.
6. Speculative route preparation on gesture candidate.
7. Safe bridge prefetch on wake.
8. Response streaming.
9. Sherpa short-command evaluation and promotion gate.
10. Private-network remote mode documentation and UX.

This order protects the local-first architecture and attacks the latency that the current code actually adds.

## Target Runtime Flow

Current slow path:

```text
earbud gesture
  -> RelayService.handleGestureSignal()
  -> prepare route
  -> POST /events wake_and_listen
  -> bridge returns "Jarvis active"
  -> Android TTS speaks the prompt
  -> startListeningSession()
  -> STT
  -> POST /events voice_command
  -> bridge action
  -> TTS response
```

Target fast path:

```text
earbud gesture
  -> RelayService.handleGestureSignal()
  -> prepare route locally
  -> startListeningSession() immediately
  -> bridge prefetch runs in parallel, read-only
  -> STT final transcript
  -> POST /events voice_command
  -> bridge action uses hot cache where safe
  -> TTS response starts from warm engine
```

The bridge can still receive an optional wake/prefetch signal, but Android must not wait for it before opening the microphone.

## Workstream 0: Baseline Latency Instrumentation

Purpose: prove which stage improved and prevent fake wins.

Current fit:

- `SpeechSessionMetrics` already tracks route requested/ready, recognizer created, listening started, ready for speech, first RMS, beginning of speech, first partial, final transcript, and TTS start/done.
- `TtsPlaybackMetrics` already tracks request, focus, start, done, stop, and errors.
- `BridgeClient` already records request duration.

Tasks:

- Add an end-to-end latency summary in diagnostics:
  - `gestureReceivedToRouteRequestedMs`
  - `routeRequestedToRouteReadyMs`
  - `gestureReceivedToListeningStartedMs`
  - `recognizerCreateMs`
  - `listeningStartedToReadyMs`
  - `readyToFirstRmsMs`
  - `readyToSpeechStartMs`
  - `speechStartToPartialMs`
  - `finalizationDelayMs`
  - `finalTranscriptToBridgeRequestMs`
  - `bridgeRequestDurationMs`
  - `bridgeResponseToTtsRequestedMs`
  - `ttsRequestToStartMs`
- Add p50/p95 aggregates to `VoiceDiagnosticsStore`.
- Add a debug proof mode that runs 10 synthetic push-to-talk sessions and exports stage timings.
- Do not persist transcript text by default.

Primary files:

- `android-relay/app/src/main/java/com/openclaw/relay/VoiceTelemetry.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/VoiceDiagnosticsStore.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt`

Verification:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.SpeechSessionMetricsTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.TtsPlaybackMetricsTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.diagnostic.DiagnosticExportTest"
```

Exit criteria:

- Every voice session export can explain where time was spent.
- The export still contains no raw audio and no transcript text by default.

Rollback: disable the latency summary export flag and keep recording only the existing `SpeechSessionMetrics`, `TtsPlaybackMetrics`, and bridge duration fields.

## Workstream 1: Fast Wake Path

Purpose: remove the bridge round trip and TTS prompt from the critical path before STT.

Current fit:

- `RelayService.handleGestureSignal()` already owns wake routing.
- `beginListeningSession()` can send the final utterance to `/events`.
- `src/bridge/request-builder.ts` maps the same event to `voice_command` when `utterance` is present.

Tasks:

- Add a `fastWakeEnabled` config flag in `RelayConfig`, persisted through `RelayConfigStorage`, and surfaced in `DeveloperModeScreen` so it can be toggled without a rebuild; keep it off until local A/B proof passes.
- Change `handleGestureSignal()` for wake-and-listen actions:
  - set wake state locally;
  - prepare and validate listening route locally;
  - start STT immediately;
  - skip speaking "Jarvis active" before listening.
- Preserve old behavior behind a debug fallback flag until proof passes.
- Add optional fire-and-forget bridge wake/prefetch call that does not block STT.
- Keep non-listening gestures, approvals, rejects, cancel, and status shortcut behavior unchanged.
- Keep route-readiness errors spoken/displayed locally when listening cannot start.

Primary files:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayModels.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayConfigStorage.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/DeveloperModeScreen.kt`
- `android-relay/app/src/test/java/com/openclaw/relay/RelayListeningRoutePolicyTest.kt`

Verification:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.RelayListeningRoutePolicyTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.SpeechSessionMetricsTest"
.\gradlew.bat :app:assembleDebug
```

Exit criteria:

- Wake-and-listen starts STT without waiting for bridge acknowledgement TTS.
- Bridge errors before STT no longer prevent the user from speaking.
- Voice command policy and approval handling remain bridge-owned.

Rollback: set `fastWakeEnabled = false` in `RelayConfig` to restore the current slow path where Android waits for bridge acknowledgement TTS before opening STT.

## Workstream 2: TTS Warm Keepalive

Purpose: reduce first-response TTS startup delay after idle.

Current fit:

- `AndroidTtsSpeaker` creates the `TextToSpeech` engine in `init`.
- `AndroidTtsSpeaker.speak()` ignores blank text, so a blank keepalive would currently do nothing.
- TTS metrics already expose `startDelayMs`.

Tasks:

- Extend `SpeechOutputEngine` with `warm()` or add an optional `WarmableSpeechOutputEngine`.
- Add `AndroidTtsSpeaker.warm()`:
  - use `TextToSpeech.playSilentUtterance(durationMs, QUEUE_ADD, utteranceId)` on API 21+;
  - avoid requesting disruptive audio focus for silent warmups if possible;
  - never call `speak("")`.
- Add a service keepalive loop while relay service is running:
  - pause while listening;
  - pause while speaking;
  - pause during approval TTS;
  - run every 3-5 minutes after TTS is ready.
- Record warmup request/start/error metrics.

Primary files:

- `android-relay/app/src/main/java/com/openclaw/relay/SpeechEngineContracts.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/AndroidTtsSpeaker.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/AndroidTtsOutputEngine.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt`
- `android-relay/app/src/test/java/com/openclaw/relay/TtsPlaybackMetricsTest.kt`

Verification:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.TtsPlaybackMetricsTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.TtsInterruptionMetricsTest"
```

Exit criteria:

- TTS warmup is silent and non-disruptive.
- Warmup never speaks user-visible text.
- TTS start delay is visible in metrics before and after the change.

Rollback: set `ttsWarmKeepaliveEnabled = false` or disable the warmup loop from Developer Mode; expected behavior returns to normal lazy TTS startup with no silent keepalive.

## Workstream 3: Workspace Snapshot Cache

Purpose: make the most common bridge intent, `quick_status`, hot-path fast.

Current fit:

- `JarvisRuntime.quickStatus()` calls `getWorkspaceStatus()` every time.
- `getWorkspaceStatus()` spawns git commands for repo detection, branch, status, last commit, and ahead/behind.
- `WorkspaceAwarenessService` already reads workspace status periodically for nudges.

Tasks:

- Add `WorkspaceSnapshotCache` bridge-side:
  - key by workspace root;
  - cache `WorkspaceStatus`;
  - default TTL: 2 seconds for git status;
  - separate longer TTL for CI failure if reused later;
  - expose `getStatus(workspace, { forceRefresh })`.
- Use the cache in:
  - `quick_status`;
  - workspace nudge polling, if compatible;
  - safe prefetch endpoint.
- Invalidate after:
  - commit;
  - push;
  - delete;
  - revert;
  - background command completion;
  - explicit health or debug refresh if requested.
- Do not cache destructive action results.

Primary files:

- `src/jarvis/runtime.ts`
- `src/adapters/git.ts`
- `src/personalization/workspace-awareness-service.ts`
- new `src/jarvis/workspace-snapshot-cache.ts` or `src/adapters/workspace-snapshot-cache.ts`
- `test/workspace-awareness-service.test.ts`
- new `test/workspace-snapshot-cache.test.ts`

Verification:

```powershell
npm run typecheck
npm test -- workspace
npm test -- jarvis
```

Exit criteria:

- Hot `quick_status` avoids repeated git process spawning inside the TTL.
- Mutating actions invalidate affected workspace snapshots.
- Tests prove stale data does not survive mutations.

Rollback: set the workspace snapshot TTL to `0` or disable `workspaceSnapshotCacheEnabled`; all intents fall back to direct `getWorkspaceStatus()` reads.

## Workstream 4: Platform STT Prewarm

Purpose: remove recognizer creation from the perceived wake-to-listen path.

Current fit:

- `AndroidSpeechRecognizer` stores `speechRecognizer`.
- `PlatformSpeechRecognizerEngine.start()` currently calls `recognizer.resetSession()` before every start, destroying any warmed recognizer.
- `SpeechCallbacks.onRecognizerCreated` already marks recognizer creation time.

Tasks:

- Add `AndroidSpeechRecognizer.prepare(onDeviceOnly: Boolean)`:
  - create the recognizer on main thread;
  - set a no-op listener or prepared listener safely;
  - do not start audio capture.
- Add `SpeechInputEngine.prepare()` or optional `WarmableSpeechInputEngine`.
- Remove unconditional `resetSession()` from healthy `PlatformSpeechRecognizerEngine.start()`.
- Reset only when:
  - error policy says `shouldResetSession`;
  - recognizer mode changes between platform and on-device;
  - service destroy;
  - explicit engine reset.
- Prewarm:
  - on relay service create after permissions are available;
  - after successful STT session completes;
  - after recognizer reset recovery.

Primary files:

- `android-relay/app/src/main/java/com/openclaw/relay/AndroidSpeechRecognizer.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/PlatformSpeechRecognizerEngine.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/SpeechEngineContracts.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt`
- `android-relay/app/src/test/java/com/openclaw/relay/SpeechEngineContractsTest.kt`

Verification:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.SpeechEngineContractsTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.SpeechRecognitionErrorPolicyTest"
.\gradlew.bat :app:assembleDebug
```

Exit criteria:

- Prewarm does not start microphone capture.
- Healthy sessions reuse the recognizer.
- Error recovery still destroys/recreates recognizer when required.

Rollback: set `speechRecognizerPrewarmEnabled = false`; `PlatformSpeechRecognizerEngine.start()` reverts to creating/resetting the recognizer on session start.

## Workstream 5: Persist Preferred Wake Provider

Purpose: reduce provider path uncertainty after the first proven physical wake.

Current fit:

- `SignalProviderRegistry` has priority order and a `preferredWakeProvider` state flow.
- Provider health records last event time.
- Calibration profiles include `providerId` and `providerIdAtCreation`.
- There is no persisted "last successful wake provider" preference.

Tasks:

- Add persisted provider preference:
  - provider id;
  - source: calibrated route, observed physical event, manual override;
  - device hash/model where available;
  - app version and Android version;
  - last success timestamp.
- Store it in `DeviceProfileStorage` or a new `PreferredProviderStorage`.
- On service startup:
  - start the preferred provider first;
  - keep universal fallbacks available;
  - do not block service startup if preferred provider fails.
- When a calibrated wake event routes successfully:
  - update preferred provider;
  - record success in provider health.
- In setup/probe flows:
  - probe preferred provider first;
  - then probe the rest for capability truth.

Primary files:

- `android-relay/app/src/main/java/com/openclaw/relay/signal/SignalProviderRegistry.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/device/DeviceProfileStorage.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt`
- `android-relay/app/src/test/java/com/openclaw/relay/signal/ProviderConformanceTest.kt`
- new provider preference tests

Verification:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.signal.ProviderConformanceTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.RelayGestureRoutingTest"
```

Exit criteria:

- A proven provider is tried first after restart.
- Fallback providers remain available.
- A failed preferred provider does not strand the user.

Rollback: clear the preferred provider storage or set `preferredProviderOrderingEnabled = false`; `SignalProviderRegistry` returns to static priority order.

## Workstream 6: Speculative Route Preparation On Gesture Candidate

Purpose: start Bluetooth communication routing before the final multi-tap gesture is emitted.

Current fit:

- `MediaButtonTapDetector` receives `ACTION_DOWN` and `ACTION_UP`.
- It emits only final `GestureType` values.
- `prepareListeningRoute()` can prepare audio route without starting STT capture.

Tasks:

- Add a gesture-candidate event/callback from `MediaButtonTapDetector.onButtonDown()`.
- Emit a new non-command event from `AndroidMediaSessionProvider`, for example `EarbudSignalEvent.InputCandidateStarted`.
- Handle the candidate in `RelayService`:
  - record telemetry;
  - optionally call `prepareListeningRoute()`;
  - do not start STT;
  - do not send a bridge command;
  - do not trigger approvals.
- Add debouncing so accidental media keys do not repeatedly churn audio routing.
- Add privacy copy in diagnostics: route preparation is not audio capture.

Primary files:

- `android-relay/app/src/main/java/com/openclaw/relay/signal/MediaButtonTapDetector.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/signal/AndroidMediaSessionProvider.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/signal/EarbudSignalEvent.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt`
- `android-relay/app/src/test/java/com/openclaw/relay/MediaButtonDiagnosticsTest.kt`

Verification:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.MediaButtonDiagnosticsTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.AudioRouteSessionTest"
```

Exit criteria:

- First tap can warm the route.
- No audio capture starts before confirmed wake/listen.
- Approval gestures cannot be triggered by candidate events.

Rollback: set `speculativeRoutePrepareEnabled = false`; candidate events are ignored and route preparation happens only after the final calibrated wake action.

## Workstream 7: Safe Bridge Prefetch On Wake

Purpose: warm local read-only data while STT is running.

Current fit:

- Bridge has no prefetch endpoint.
- Workspace status reads are safe and local.
- Android already has paired config and bearer token.

Tasks:

- Add an authenticated prefetch endpoint, for example:
  - `POST /sessions/:sessionId/workspaces/:workspaceId/prefetch`
  - body: `{ "kinds": ["workspace_status"], "idempotencyKey": "..." }`
- Or add a specific event that maps to prefetch but cannot execute policy intents.
- Only prefetch read-only data:
  - workspace status;
  - maybe diff stat;
  - maybe CI snapshot with longer TTL.
- Android triggers prefetch when:
  - wake/listen starts;
  - gesture candidate starts, if paired and idle;
  - not during active approval or destructive command.
- Bridge returns immediately or 202, and fills cache asynchronously.
- Add cache hit/miss telemetry to bridge diagnostics.

Primary files:

- `src/bridge/server.ts`
- `src/bridge/runtime.ts`
- `src/jarvis/workspace-snapshot-cache.ts`
- `android-relay/app/src/main/java/com/openclaw/relay/BridgeClient.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt`
- new `test/bridge-prefetch.test.ts`

Verification:

```powershell
npm run typecheck
npm test -- bridge-prefetch
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.BridgePairingClientTest"
```

Exit criteria:

- Prefetch cannot execute workspace actions.
- Prefetch is authenticated.
- Hot status calls use prefetched data when safe.

Rollback: set `bridgePrefetchOnWakeEnabled = false` and leave the prefetch endpoint unused; normal `/events` handling continues without cache warming.

## Workstream 8: Response Streaming

Purpose: reduce perceived latency after STT final by letting Android speak early progress/final chunks.

Current fit:

- Current `/events` endpoint returns a single `JarvisResponse`.
- Android `BridgeClient.sendEvent()` waits for the full response body.
- `AndroidTtsSpeaker.speak()` always uses `TextToSpeech.QUEUE_FLUSH`, which would break chunk playback.
- Bridge background actions already return quickly and complete through outbox.

Tasks:

- Add health feature flag:
  - `event_streaming`
- Add `/events/stream` as a new endpoint; keep `/events` unchanged.
- Use NDJSON or SSE-style frames:
  - `started`
  - `speak_delta`
  - `display_delta`
  - `approval_request`
  - `final_response`
  - `error`
  - `done`
- Add Android `BridgeClient.sendEventStreaming()` that reads frames incrementally.
- Extend TTS request model:
  - `queueMode = FLUSH` for first chunk;
  - `queueMode = ADD` for follow-up chunks;
  - `interruptGroupId` to stop a stream on barge-in/cancel.
- Server should stream safe early copy only:
  - "Checking." for status/diff/CI.
  - "Starting tests." for background task start.
  - Never imply approval or success before policy/action result exists.
- Keep final `JarvisResponse` schema for state reconciliation.

Primary files:

- `src/bridge/server.ts`
- `src/bridge/event-router.ts`
- `src/protocol/schemas.ts`
- `android-relay/app/src/main/java/com/openclaw/relay/BridgeClient.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/AndroidTtsSpeaker.kt`
- new streaming tests in `test/`

Verification:

```powershell
npm run typecheck
npm test -- streaming
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.TtsPlaybackMetricsTest"
.\gradlew.bat :app:assembleDebug
```

Exit criteria:

- Android falls back to `/events` if streaming is unavailable.
- Stream chunks do not trample each other in TTS.
- Policy and approval decisions are never guessed early.

Rollback: remove `event_streaming` from `/health` or set `eventStreamingEnabled = false`; Android must use the existing `/events` request/response path.

## Workstream 9: Sherpa Command-Mode Evaluation

Purpose: decide whether offline STT should become an experimental or production option for DevPods commands.

Current fit:

- `sherpa-runtime` module exists.
- `SherpaSpeechInputEngine` is a real capture/recognition engine behind readiness checks.
- `SpeechInputMode.SHERPA_EVALUATION` exists.
- `sherpaSttExperimentalEnabled` exists in config but is not a complete product promotion gate.

Caveat:

The app cannot know a command has fewer than four words until after recognition. The practical choice is not "use Sherpa for commands under four words" at runtime. The practical choice is "benchmark Sherpa command mode on the DevPods command set, then decide whether to expose it as opt-in or default."

Tasks:

- Build a command benchmark set:
  - "status"
  - "what changed"
  - "run tests"
  - "open the main file"
  - "latest CI failure"
  - "write a commit message"
  - "push"
  - "cancel"
- Compare:
  - platform STT;
  - platform on-device STT;
  - Sherpa STT.
- Measure:
  - wake to ready;
  - first partial;
  - final transcript;
  - intent accuracy;
  - endpoint delay;
  - no-speech behavior;
  - CPU/battery/thermal;
  - native failure recovery.
- Keep Sherpa hidden or developer-only until benchmark gates pass.
- If promoted, expose a clear "Offline command recognition" experimental toggle, not a vague speed toggle.

Primary files:

- `android-relay/app/src/main/java/com/openclaw/relay/speech/SherpaSpeechInputEngine.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/OfflineSpeechBenchmark.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/OfflineSpeechEvaluation.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/DeveloperModeScreen.kt`
- `docs/30-silero-vad-sherpa-onnx-perfect-implementation-plan.md`

Verification:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.OfflineSpeechEvaluationTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.SherpaModelManagerTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.SpeechEngineContractsTest"
```

Exit criteria:

- Written benchmark artifact exists.
- Sherpa promotion state is explicit: hidden, developer diagnostic, experimental, or production.
- Platform STT fallback remains immediate.

Rollback: set `sherpaSttExperimentalEnabled = false` and `speechInputMode = PLATFORM`; Sherpa remains installed/diagnostic-only but cannot own live command recognition.

## Workstream 10: Private Network Remote Mode

Purpose: support "phone away from laptop LAN" without changing the local-first privacy model.

Current fit:

- The Android app accepts manual `http://` or `https://` bridge URLs.
- mDNS discovery is LAN-specific.
- Release builds block cleartext discovered bridge URLs and have no release cleartext allowance.
- Pairing page URLs can be opened manually.

Tasks:

- Add docs for private-network operation:
  - same-machine LAN mode;
  - private mesh/VPN mode;
  - USB reverse/debug mode;
  - release HTTPS requirements.
- Add UI copy in pairing/setup:
  - "Discovery works on local Wi-Fi. For private VPN, paste the bridge URL."
  - "Release builds require HTTPS for discovered bridges."
- Add a "Remote private network" pairing profile:
  - no cloud backend;
  - user supplies bridge URL;
  - health check validates reachability;
  - warns when mDNS discovery is unavailable.
- Decide release HTTPS story before shipping this as a polished feature:
  - local TLS support;
  - trusted certificate instructions;
  - explicit product-supported private-network tunnel mode.

Primary files:

- `protocol/bridge-api.md`
- `docs/release-matrix.md`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/SetupWizardScreen.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/DeviceScreen.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt`

Verification:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.BridgePairingClientTest"
.\gradlew.bat :app:assembleRelease
```

Exit criteria:

- Remote private-network setup is documented honestly.
- No cloud command relay is introduced.
- Release transport constraints are explicit.

Rollback: hide the private-network setup copy/profile and keep only existing LAN discovery plus manual bridge URL pairing.

## Proof And Acceptance Gates

Add a latency proof artifact that can be compared before/after each workstream.

Minimum proof scenarios:

| Scenario | Sessions | Required evidence |
| --- | --- | --- |
| Push-to-talk platform STT | 10 | wake-to-listen, STT final, bridge, TTS metrics |
| Calibrated earbud wake | 20 | provider, calibrated action, route, STT, bridge, TTS |
| TTS after idle | 10 | TTS start delay before/after warmup |
| Quick status hot cache | 20 | cache hit/miss, bridge duration |
| Bridge offline during wake | 5 | STT still opens, final command queues/fails clearly |
| Approval flow | 5 | no early execution, no streaming policy bypass |
| Barge-in during TTS | 5 | stop latency, input route recovery |
| Private network pairing | 3 | manual URL health and pairing behavior |

Latency targets should be measured against the current device, not invented as universal numbers. Initial goals:

- Remove the pre-STT bridge/TTS wait entirely from wake/listen.
- Hot `quick_status` bridge work should usually complete from cache without new git process spawn.
- TTS warmup should reduce first response `ttsRequestToStartMs` after idle.
- Provider preference should reduce provider uncertainty without reducing fallback reliability.

## Anti-Patterns To Block

- Adding Firebase, Render, or any other cloud service to relay commands.
- Pre-running `commit`, `push`, `deploy`, `delete`, `revert`, or tests because the user might ask.
- Treating a prefetch as user intent.
- Speaking a success phrase before the action has actually succeeded.
- Letting streaming bypass `JarvisResponse` final state reconciliation.
- Starting `AudioRecord`, Sherpa STT, or platform `SpeechRecognizer` during a gesture candidate.
- Running Sherpa and platform STT at the same time.
- Persisting raw transcript text in latency metrics.
- Disabling schema validation because the phone is "trusted."
- Hiding release HTTPS or private-network limitations from setup UI.

## Recommended First Implementation Slice

Implement these together because they create the cleanest, safest first win:

1. Add latency summary fields and diagnostic export support.
2. Add fast wake path behind a feature flag.
3. Stop speaking "Jarvis active" before STT in fast wake mode.
4. Add TTS warmup with silent utterance.
5. Add tests for wake path state transitions and TTS warmup behavior.

Validation:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.SpeechSessionMetricsTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.TtsPlaybackMetricsTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.RelayListeningRoutePolicyTest"
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
```

Then run:

```powershell
npm run typecheck
npm test
```

## Definition Of Done

This product upgrade is done when:

- DevPods remains local-first, with no cloud command relay.
- Wake/listen opens STT without waiting for bridge acknowledgement TTS.
- TTS remains warm without audible keepalive artifacts.
- Quick status and workspace nudges reuse safe hot snapshots.
- Provider preference improves startup without removing fallbacks.
- Speculative route preparation never captures audio or executes commands.
- Streaming, if enabled, is feature-negotiated and has `/events` fallback.
- Sherpa STT has a benchmark-backed product status.
- Private-network remote mode is documented and honest about mDNS/HTTPS constraints.
- Diagnostics can show before/after latency by stage without exposing private content.
- Full TypeScript and Android build gates pass.
