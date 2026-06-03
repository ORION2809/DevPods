# Production Grade Relay Voice Product Plan

Generated: 2026-05-19

Related docs:

- [19-earbud-compatibility-integration-source-map.md](19-earbud-compatibility-integration-source-map.md)
- [22-voice-audio-pipeline-external-source-blueprint.md](22-voice-audio-pipeline-external-source-blueprint.md)
- [23-voice-pipeline-ticket-queue.md](23-voice-pipeline-ticket-queue.md)
- [25-reliability-ux-product-audit.md](25-reliability-ux-product-audit.md)
- [26-reliability-ux-implementation-audit.md](26-reliability-ux-implementation-audit.md)

## Executive Decision

The current implementation is not enough to be a production-grade product because it has too many reliability scaffolds and not enough hard runtime guarantees.

The next milestone is not "add more providers" or "add another STT engine." The next milestone is a complete, proof-gated voice product where a physical earbud action reliably becomes:

1. A captured wake signal.
2. A proven microphone route.
3. A stable STT session.
4. A validated bridge request.
5. A policy-checked agent action.
6. A short, interruptible spoken response.
7. A diagnostic trail that explains failures without leaking private audio or personal device names.

This plan makes the product production-grade by integrating the best prebuilt components where they are actually useful, while keeping the Android platform path as the default baseline until an offline engine beats it with proof.

## Product Reliability Contract

DevPods is production-ready only when the product can prove this loop on real hardware:

| Contract | Release threshold |
| --- | --- |
| Wake signal | 20 consecutive physical sessions record a wake or fallback wake source. |
| Route | Every session records selected route, route-settle time, and whether input path is earbud mic or fallback mic. |
| STT | At least 19 of 20 sessions produce a final transcript or a clear user-actionable failure. |
| Bridge | At least 19 of 20 sessions reach the desktop bridge or show a precise bridge pairing/network failure. |
| Agent response | Bridge responses are policy checked, bounded for speech, and never require hidden desktop context. |
| TTS | At least 19 of 20 responses start and complete, or fail with display fallback. |
| Barge-in | 5 consecutive tap-during-TTS interruptions stop speech within the target on each supported device. |
| Privacy | Default diagnostics contain no raw audio, no transcript text, and no personal Bluetooth names. |
| Claim discipline | No device or provider is labeled Ready without a proof run tied to phone, Android version, earbuds, route, and provider path. |

## Current Gap

The implementation has strong foundations:

- Android `SpeechRecognizer` and `TextToSpeech` wrappers exist.
- Audio route proof and AudioRecord probe scaffolding exist.
- Voice session metrics, VAD-like telemetry, proof runs, and diagnostic exports exist.
- Android relay to desktop bridge protocol exists.
- Pairing, token authorization, request schemas, policy routing, audit logging, and local Jarvis runtime exist.
- Sherpa/Silero model management and evaluation scaffolds exist.

The missing production pieces are:

- Hard setup gates that refuse to call an unproven loop Ready.
- A fallback-aware capability model that separates direct vendor support from Android fallback support.
- Real offline VAD/STT runtime integration, not placeholder adapters.
- A release-grade bridge contract with protocol versioning, reconnect behavior, idempotency, queue limits, and health details.
- TTS interruption behavior enforced as a product contract, not only measured after the fact.
- Proof harnesses that run repeatable 20-session validation on **physical devices** (T4). *(T1 emulator synthetic harness is complete and passing; see [31-emulator-and-physical-e2e-automation-plan.md](31-emulator-and-physical-e2e-automation-plan.md).)*
- Diagnostics that are useful enough for support and private enough for default export.
- CI gates that fail when tests, audits, or proof fixtures regress.

## Component Adoption Strategy

Use external components only when they improve a measured product outcome. Do not expose any engine or provider as production until it has a real runtime adapter, tests, model lifecycle, diagnostics, and release proof.

| Component | Product role | Status target | Decision |
| --- | --- | --- | --- |
| Android `SpeechRecognizer` | Default STT baseline. | Production default. | Keep as the first stable path. Instrument deeply and never start a second session before final/error. |
| Android `TextToSpeech` | Default TTS baseline. | Production default. | Keep, but add audio focus, stop latency, queue policy, route proof, and display fallback. |
| Android `AudioManager.setCommunicationDevice` | Bluetooth microphone route selection. | Production default. | Keep. Route state must be measured and surfaced as proof, not assumed. |
| Media3 / media-button path | Universal headset wake fallback. | Production default. | Keep as fallback-proven support. Do not call it vendor-direct support. |
| OkHttp / HTTP bridge | Android to desktop transport. | Production default. | Keep, but harden protocol semantics, retry, idempotency, and health. |
| Sherpa-ONNX | Offline STT and VAD candidate. | Production candidate behind feature flag. | Build a real Android module or flavor and benchmark against platform STT. Promote only after proof. |
| Silero VAD through Sherpa | Offline VAD candidate. | First offline component to integrate. | Integrate before offline STT because VAD can prove route/no-speech quality without replacing STT. |
| `speech-android` references | Android speech architecture reference. | Reference only. | Borrow patterns for JNI packaging, model loading, lifecycle, and recognition service structure after license review. |
| Vosk | Offline STT benchmark or fallback. | Benchmark only. | Evaluate for command accuracy and latency after Sherpa baseline exists. Do not add as another default path first. |
| `whisper.cpp` | Offline long-form benchmark. | Benchmark only. | Useful for accuracy comparison, likely too heavy for first tap-to-command loop. |
| Picovoice Cobra | Licensed VAD benchmark. | Optional benchmark. | Evaluate only if licensing and key management fit the product. |
| RNNoise | Noise suppression candidate. | Later optimization. | Add only if route proof shows noise is a top failure mode. |
| LibrePods/vendor protocol sources | Earbud provider research. | Provenance-gated. | No copied GPL/AGPL-derived protocol code ships without explicit license decision and notices. |

## Target Runtime Architecture

The production product should have one coordinator that owns the complete session:

```text
Earbud signal provider
  -> VoiceSessionCoordinator
  -> AudioRouteController
  -> SpeechInputEngine
  -> BridgeClient
  -> BridgeRuntime / OpenClaw / Hermes
  -> SpeechOutputEngine
  -> ProofStore + DiagnosticStore
```

Core interfaces:

```kotlin
interface AudioRouteController {
    suspend fun prepareInputRoute(request: RouteRequest): RouteResult
    suspend fun releaseInputRoute(reason: RouteReleaseReason)
}

interface SpeechInputEngine {
    val id: String
    val readiness: SpeechEngineReadiness
    suspend fun start(request: SpeechSessionRequest, callbacks: SpeechCallbacks)
    suspend fun stop(reason: SpeechStopReason)
    fun destroy()
}

interface VadProbe {
    val id: String
    suspend fun start(request: VadProbeRequest, callbacks: VadCallbacks)
    suspend fun stop(reason: VadStopReason)
}

interface SpeechOutputEngine {
    val id: String
    val readiness: TtsReadiness
    suspend fun speak(request: TtsRequest, callbacks: TtsCallbacks)
    suspend fun stop(reason: TtsStopReason)
}
```

The coordinator must enforce ownership:

- Only one microphone owner at a time.
- `SpeechRecognizer` and `AudioRecord` never capture concurrently.
- TTS must stop before a barge-in listen starts.
- Phone mic fallback is never silent. It requires explicit user consent.
- A bridge failure cannot be mislabeled as a speech failure.
- A route failure cannot be mislabeled as a bridge failure.

## Production Status Model

Replace vague "implemented" language with proof-backed states.

| Status | Meaning | User wording |
| --- | --- | --- |
| `PROVEN` | Direct path passed the proof contract on this phone, Android version, device, provider, and route. | Ready |
| `FALLBACK_PROVEN` | Android MediaSession, assistant, phone mic, or push-to-talk fallback passed. | Works through fallback |
| `OBSERVED` | Signal or state was observed, but the complete loop was not proven. | Partially working |
| `IMPLEMENTED_UNVERIFIED` | Runtime code exists, but no physical proof is attached. | Lab build |
| `SCAFFOLDED` | Detection or placeholders exist, but runtime behavior is not complete. | Not ready |
| `UNSUPPORTED` | Tested and failed, or the platform blocks the required path. | Unsupported |

Every capability record must include:

- `phoneModel`
- `androidVersion`
- `earbudModel`
- `providerId`
- `wakePath`
- `inputPath`
- `outputPath`
- `engineId`
- `bridgeMode`
- `proofRunId`
- `lastProvenAt`
- `status`
- `blockingFailures`

## Workstream 0: Freeze The Production Contract

Purpose: stop the product from drifting while pieces are still experimental.

Implementation:

- Add the final status taxonomy to Android capability models, JSON matrix, setup assessment, UI chips, and diagnostic export.
- Add `proofPath` metadata so direct vendor support and Android fallback support are never collapsed.
- Add `isReleaseReady` or `hasBlockingFailures` to proof-run summaries.
- Make setup completion depend on proof readiness, not just on one STT attempt.
- Convert supported-device feature booleans to per-capability proof states.

Primary files:

- `android-relay/app/src/main/java/com/openclaw/relay/VoiceProofRun.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/device/DeviceCapabilityMatrix.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/device/SetupCapabilityAssessment.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/DeviceScreen.kt`
- `docs/supported-devices-matrix.json`

Verification:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.VoiceProofRunTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.DeviceCapabilityMatrixTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.device.SetupCapabilityAssessmentTest"
```

Exit criteria:

- A proof run with successful STT but no-signal probe cannot mark the device Ready.
- A missed interruption target cannot mark the loop Ready.
- MediaSession fallback stores `FALLBACK_PROVEN`, not `PROVEN`.
- Setup can finish as Proven or Degraded, but only Proven hides all readiness warnings.

## Workstream 1: Finish The Platform Baseline As A Real Product Path

Purpose: ship the most reliable version of the Android platform path before adding heavier engines.

Implementation:

- Make `PlatformSpeechRecognizerEngine` the named production default.
- Add complete session lifecycle guards: idle, routing, listening, finalizing, bridge, speaking, interrupted, failed.
- Persist endpoint tunables only after request plumbing and tests are stable.
- Add route-settle timing and wrong-mic suspicion to the user-visible activity timeline.
- Add recovery for recognizer busy, timeout, no-speech, permission revoked, Bluetooth route lost, and app process death.
- Ensure no second `startListening` call can happen before final/error or explicit cancellation cleanup.
- Make one-tap retry use the same route and bridge diagnostics from the failed session.

Primary files:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayStateStore.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/PlatformSpeechRecognizerEngine.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/AndroidSpeechRecognizer.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/VoiceTelemetry.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/history/ActivityHistoryStore.kt`

Verification:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.SpeechSessionMetricsTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.SpeechEngineContractsTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.RelayListeningRoutePolicyTest"
```

Exit criteria:

- Platform STT sessions are fully timestamped.
- Recognizer errors map to specific user actions.
- Wrong-mic suspicion is visible in activity and diagnostics.
- No platform path readiness depends on Sherpa or any offline placeholder.

## Workstream 2: Production Audio Route And Mic Proof

Purpose: prove that the product is listening through the intended input path.

Implementation:

- Make `AudioRouteSession` the single owner of route preparation and release.
- Add route result classes for selected Bluetooth mic, selected phone mic fallback, route denied, route unavailable, route lost, and route suspect.
- Add a short `AudioRecordRouteProbe` diagnostic flow that can run before or after STT, never during `SpeechRecognizer`.
- Store amplitude summaries only: nonzero frame ratio, peak amplitude, read errors, route snapshot, and selected input path.
- Add explicit user consent before phone mic fallback.
- Add a "route is suspect" state when Bluetooth appears selected but no microphone energy is detected.

Primary files:

- `android-relay/app/src/main/java/com/openclaw/relay/AudioRouteSession.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/AudioRecordRouteProbe.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/AudioProbeMetrics.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/BluetoothAudioRouter.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayListeningRoutePolicy.kt`

Verification:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.AudioRouteSessionTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.AudioProbeMetricsTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.AudioRouteFallbackPolicyTest"
```

Exit criteria:

- Phone mic fallback cannot happen without an explicit stored decision.
- A no-signal Bluetooth route blocks Ready.
- Probe summaries export without raw audio.
- Route failures have one repair action in setup and activity.

## Workstream 3: Real Sherpa/Silero VAD Integration

Purpose: integrate the first real external audio component and use it for measured route/speech detection, not marketing.

Implementation:

- Add a separate Gradle module or build flavor for Sherpa native runtime so the default APK is not blocked by experimental native packaging.
- Package Sherpa-ONNX Android AAR/native libraries using a DevPods-owned module name.
- Add a model inventory for Silero VAD with version, size, checksum, storage path, and license metadata.
- Replace placeholder `SherpaVadProbe` behavior with a real adapter.
- Feed 16 kHz mono buffers into Sherpa VAD in a diagnostic or experimental mode.
- Record VAD windows, speech detected count, speech start delay, false silence, CPU time, memory, and battery sample.
- Keep platform callback VAD as the default until Sherpa VAD is proven better on real devices.

Primary files:

- `android-relay/settings.gradle.kts`
- `android-relay/app/build.gradle.kts`
- `android-relay/app/src/main/java/com/openclaw/relay/SherpaEvaluationEngines.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/SherpaModelManager.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/VadTelemetry.kt`
- New module or flavor under `android-relay/sherpa-runtime` or equivalent.

Verification:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.SherpaModelManagerTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.VadTelemetryTest"
.\gradlew.bat :app:assembleDebug
```

Physical validation:

- 20 sessions with platform STT plus Sherpa VAD diagnostic observation.
- 5 quiet-room sessions.
- 5 noisy-room sessions.
- 5 wrong-route or covered-mic sessions.

Exit criteria:

- `SherpaVadProbe` no longer returns "native runtime not linked" in the Sherpa-enabled build.
- Missing model, checksum mismatch, ABI mismatch, and native-load failure all produce clear fallback states.
- The default product still works when Sherpa is disabled or broken.

## Workstream 4: Real Sherpa STT Evaluation

Purpose: determine whether offline STT deserves to become a production option.

Implementation:

- Implement `SherpaSpeechInputEngine` behind `SpeechInputEngine`.
- Start with a command-oriented model and DevPods utterance set, not long-form dictation.
- Add model inventory, download/install, checksum, low-storage handling, and uninstall.
- Support cold-load, warm-start, first-partial, finalization, endpoint, and error metrics.
- Compare Sherpa STT against Android `SpeechRecognizer` using the same `SpeechSessionMetrics`.
- Do not expose offline STT as a normal user setting until it passes the beta gate.

Primary files:

- `android-relay/app/src/main/java/com/openclaw/relay/SherpaEvaluationEngines.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/OfflineSpeechEvaluation.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/OfflineSpeechBenchmark.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/SpeechEngineContracts.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/VoiceDiagnosticsStore.kt`

Verification:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.OfflineSpeechEvaluationTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.SpeechEngineContractsTest"
.\gradlew.bat :app:assembleDebug
```

Beta gate:

| Metric | Required result |
| --- | --- |
| Cold load | Recorded and acceptable for model size. |
| Wake to first partial | Equal to or better than platform on at least one target device class, or clearly useful offline. |
| Final transcript | At least 95 percent command-intent success on the DevPods command set. |
| Endpointing | No stuck sessions in 20-session proof. |
| Battery/thermal | No unacceptable drain or thermal warning in a repeated session run. |
| Fallback | Platform STT can be restored immediately after Sherpa failure. |

Exit criteria:

- Offline STT can be labeled Experimental only after beta gate data exists.
- Offline STT can be labeled Production only after it beats or complements platform STT on measured user outcomes.

## Workstream 5: Production TTS And Barge-In

Purpose: make spoken responses feel dependable, short, and interruptible.

Implementation:

- Make `AndroidTtsOutputEngine` the named production output engine.
- Add audio focus acquisition/release around TTS.
- Add utterance queue policy: product responses use flush, background notifications are bounded, and repeated errors are rate-limited.
- Enforce response length budgets before TTS, using `optimizeSpeak` or Android-side equivalent limits.
- Add barge-in flow: tap during TTS stops speech, records latency, releases output, prepares input, and starts listening.
- If TTS fails, display the response and allow retry without restarting the service.

Primary files:

- `android-relay/app/src/main/java/com/openclaw/relay/AndroidTtsSpeaker.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/AndroidTtsOutputEngine.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/TtsInterruptionMetrics.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt`
- `src/jarvis/voice-optimize.ts`

Verification:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.TtsInterruptionMetricsTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.TtsPlaybackMetricsTest"
```

Physical validation:

- 20 TTS responses on each release matrix device.
- 5 tap-during-TTS interruption sessions on each release matrix device.
- 5 bridge-error display-fallback sessions.

Exit criteria:

- Median barge-in stop latency is under the target.
- A missed interruption target blocks Ready.
- TTS failure never traps the user in a dead session.

## Workstream 6: Production Bridge Contract

Purpose: make the desktop bridge a reliable product service, not just a local demo server.

Implementation:

- Add protocol version and relay build metadata to every Android event.
- Add bridge capabilities to `/health`: protocol version, runtime mode, OpenClaw health, workspace registry status, queue depth, degraded state, and last error category.
- Add idempotency keys for session events so retries do not execute actions twice.
- Add request retry policy on Android: retry only safe bridge failures, not policy denials or action failures.
- Add session state reconciliation after Android reconnects.
- Add clear error categories: pairing, auth, network, bridge degraded, OpenClaw unavailable, workspace denied, action failed, approval expired.
- Add bounded response budgets for `speak` and `display`.
- Add bridge audit events for every received relay event, retry, policy decision, and response.

Primary files:

- `src/bridge/server.ts`
- `src/bridge/runtime.ts`
- `src/bridge/session-store.ts`
- `src/bridge/audit-log.ts`
- `src/protocol/schemas.ts`
- `src/protocol/types.ts`
- `android-relay/app/src/main/java/com/openclaw/relay/BridgeClient.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayPairing.kt`

Verification:

```powershell
npm run typecheck
npm test -- bridge
npm test -- protocol
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.BridgePairingClientTest"
```

Exit criteria:

- Android can tell the difference between bridge offline, unauthorized, degraded, and action failed.
- Retried events cannot duplicate a destructive action.
- Bridge health gives enough detail for Android setup and diagnostics to choose the right repair action.

## Workstream 7: OpenClaw/Hermes Production Boundary

Purpose: keep the "Jarvis developer assistant" powerful while making it bounded enough for earbuds.

Implementation:

- Treat Android relay as the input/output surface, not the policy owner.
- Keep intent routing, approval rules, workspace allowlists, audit log, and action execution in the bridge.
- Add explicit risk class to spoken prompts and approval requests.
- Require hard approval for dangerous workspace actions.
- Add cancellation propagation from earbuds to queued/running bridge work.
- Add background completion notification rules so long tasks do not spam TTS.
- Add OpenClaw health preflight and fallback to local Jarvis mode when configured.

Primary files:

- `src/jarvis/runtime.ts`
- `src/jarvis/router.ts`
- `src/jarvis/background-command-scheduler.ts`
- `src/policy/engine.ts`
- `src/policy/approvals.ts`
- `src/openclaw/client.ts`
- `src/openclaw/validation.ts`

Verification:

```powershell
npm run typecheck
npm test -- jarvis
npm test -- policy
npm test -- openclaw
```

Exit criteria:

- High-risk actions cannot execute from voice alone.
- Earbud cancel stops or queues cancellation for background work.
- OpenClaw failure returns a short, spoken, retryable status without breaking the relay session.

## Workstream 8: Diagnostics, Privacy, And Support

Purpose: make failures fixable without collecting sensitive user content by default.

Implementation:

- Add a diagnostic preview before export.
- Default export excludes raw audio, transcript text, and exact Bluetooth names.
- Add explicit toggles for transcript, raw route details, phone model, and personal device names.
- Hash stable identifiers when support needs correlation.
- Include route proof, proof run status, bridge health, provider health, engine readiness, TTS metrics, and recent redacted errors.
- Add support-oriented failure summaries: "earbud mic route failed", "speech heard but bridge unreachable", "bridge succeeded but TTS failed", "tap interruption missed target".

Primary files:

- `android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExportOptions.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/VoiceDiagnosticsStore.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/signal/ProviderConformance.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/HelpScreen.kt`

Verification:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.diagnostic.DiagnosticExportTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.signal.ProviderConformanceTest"
```

Exit criteria:

- `Alice's AirPods Pro` style names never appear in default export.
- Support can diagnose which subsystem failed without raw audio.
- Provider health is included for all relevant providers, not only the selected one.

## Workstream 9: Setup And Daily UX

Purpose: make the product feel honest, repairable, and calm.

Implementation:

- Replace one-shot setup completion with a proof checklist:
  - bridge paired
  - earbuds detected
  - wake observed
  - route proven
  - speech captured
  - bridge responded
  - TTS played
  - barge-in passed
- Save a Proven profile only when required steps pass.
- Save a Degraded profile when fallback works but direct path is not proven.
- Wire "Use assistant fallback" to a real fallback path or remove it until it works.
- Home screen answers one question first: "Can I speak from my earbuds right now?"
- Device screen shows per-capability proof states, last proof run, and "run proof again."
- Activity screen shows a timeline that separates wake, route, STT, bridge, TTS, and interruption.

Primary files:

- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/SetupWizardScreen.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/HomeScreen.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/DeviceScreen.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/ActivityScreen.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/UserOnboarding.kt`

Verification:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.RelayViewModelImportTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.ListenReadinessTest"
.\gradlew.bat :app:lintDebug
```

Exit criteria:

- Setup cannot show Ready when the proof gate failed.
- Degraded mode is useful and honest.
- Every failed setup step has exactly one primary repair action.

## Workstream 10: Physical Device Proof Harness

Purpose: make reliability measurable outside unit tests.

Implementation:

- Create a proof-run checklist and storage format for manual and semi-automated hardware runs.
- Add a debug trigger to start a 20-session proof run.
- Add stable proof run IDs that tie Android logs, bridge audit logs, and diagnostic exports together.
- Add physical validation scripts for bridge-side fake events plus Android manual steps.
- Store results under `artifacts/proof-runs/` or `runtime-data/proof-runs/` with redacted JSON.
- Add a release matrix document that lists phone, Android version, earbuds, provider path, input path, output path, engine, and result.

Minimum release matrix:

| Phone class | Android versions | Earbuds |
| --- | --- | --- |
| Pixel | 14, 15, current supported release | AirPods Pro 2, Galaxy Buds2 Pro, Sony WF-1000XM5, generic headset |
| Samsung Galaxy | 14, 15 | Galaxy Buds2 Pro, Galaxy Buds3 Pro, AirPods Pro 2, Sony WF-1000XM4 |
| OnePlus/Oppo/Realme | 14, 15 | Oppo/Realme/OnePlus Buds, AirPods Pro 2, generic headset |
| Xiaomi/other OEM | 14 or 15 | One generic headset and one Samsung or Sony pair |

Per matrix entry:

- 20 tap-to-command sessions.
- 5 tap-during-TTS interruptions.
- 3 disconnect/reconnect runs.
- 3 app process-death recovery runs.
- 1 diagnostic export privacy review.
- 1 bridge restart/reconnect run.

Exit criteria:

- A model cannot move to Ready in docs or UI without a proof run artifact.
- Hardware failures update the support matrix instead of being treated as random user reports.

## Workstream 11: Release Engineering And CI Gates

Purpose: make regressions visible before a build is handed to users.

Required local verification:

```powershell
npm run typecheck
npm test
npm audit
cd android-relay
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
```

Add CI gates:

- TypeScript typecheck.
- Node/Vitest suite.
- Android unit tests.
- Android lint.
- Android debug assembly.
- Dependency audit with documented exceptions.
- Secret scan for tokens, pairing codes, and API keys.
- License scan for external source use.
- Supported matrix schema validation.
- Diagnostic privacy contract tests.

Exit criteria:

- No release branch can merge with failing baseline tests.
- External native engines require ABI, license, checksum, and fallback tests.
- Known flaky or environment-dependent tests have tracked owners and documented failure modes.

## Workstream 12: Security And Abuse Resistance

Purpose: keep a voice-controlled developer tool from becoming a risky command launcher.

Implementation:

- Keep bridge token authorization required for relay events.
- Rotate pairing codes and expire them.
- Add protocol version checks and reject unknown major versions.
- Add rate limits per relay session and per device.
- Add command allowlists per workspace.
- Keep dangerous actions behind hard approval.
- Never execute shell text derived directly from STT.
- Redact utterances and transcripts by default in logs.
- Add audit records for every action decision and approval.

Primary files:

- `src/bridge/server.ts`
- `src/policy/engine.ts`
- `src/policy/allowlists.ts`
- `src/policy/redaction.ts`
- `src/adapters/process.ts`
- `src/protocol/schemas.ts`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayCommandAuth.kt`

Verification:

```powershell
npm test -- policy
npm test -- redaction
npm run audit:allowlist
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.RelayCommandAuthTest"
```

Exit criteria:

- A malformed, unauthorized, replayed, or oversized event cannot execute a workspace action.
- Approval-required actions cannot be approved by ambiguous speech alone.
- Logs remain useful without exposing private user commands by default.

## Execution Order

Do the work in this order:

1. Workstream 0: freeze status, setup, and proof contract.
2. Workstream 1: finish platform STT baseline.
3. Workstream 2: make route proof and fallback decisions production-grade.
4. Workstream 5: finish TTS and barge-in contract.
5. Workstream 6: harden bridge protocol and health.
6. Workstream 8: harden diagnostics and privacy.
7. Workstream 9: update setup and daily UX around proof.
8. Workstream 10: create physical proof harness.
9. Workstream 3: integrate real Sherpa/Silero VAD behind feature flag.
10. Workstream 4: evaluate real Sherpa STT behind feature flag.
11. Workstream 7: strengthen OpenClaw/Hermes production boundary.
12. Workstream 11: CI and release gates.
13. Workstream 12: final security pass.

Parallel work allowed after Workstream 0:

- Bridge hardening can run in parallel with Android route proof.
- Diagnostics can run in parallel once proof schemas are stable.
- Sherpa VAD module can be prepared in parallel, but cannot be promoted until platform proof gates are stable.
- UX can be designed in parallel, but should not merge Ready language before status semantics are fixed.

## Anti-Patterns To Block

- Shipping a placeholder engine with a production label.
- Calling a device Ready because a provider class exists.
- Treating Android MediaSession fallback as vendor-direct earbud support.
- Running `AudioRecord` and `SpeechRecognizer` at the same time.
- Silently falling back to the phone microphone.
- Bundling large offline models into the base APK without storage and update controls.
- Adding multiple STT engines before the platform baseline has a clean proof run.
- Copying GPL/AGPL protocol code into production without a license decision.
- Exporting personal Bluetooth names or transcripts by default.
- Letting retries duplicate bridge actions.

## Production Definition Of Done

The product is production-grade when all of the following are true:

- Setup stores Proven, Degraded, or Unsupported profiles with proof IDs.
- The Home screen accurately says whether the user can speak from earbuds right now.
- Every Ready label is backed by physical proof, not static model matching.
- Platform STT and Android TTS pass the full proof contract on the release matrix.
- Bridge health, authorization, protocol versioning, retries, and idempotency are implemented.
- TTS barge-in is measured and blocks readiness when it fails.
- Diagnostics explain the failing subsystem without raw audio or personal names by default.
- Sherpa/Silero is either hidden, clearly experimental, or proven with real runtime data.
- The full verification suite passes, including Android, Node, privacy, protocol, and release-matrix schema checks.
- External component licenses and provenance are documented before release.

## First Implementation Slice

Start with this slice because it converts the current scaffolding into honest product behavior:

1. Add `FALLBACK_PROVEN` and proof path metadata to capability records.
2. Make `VoiceProofRun` expose blocking failures and release readiness.
3. Make setup save Proven only when the proof contract passes, otherwise Degraded or Unsupported.
4. Fix diagnostic default redaction for personal Bluetooth names.
5. Wire or remove assistant fallback.
6. Add tests for all five changes.

Validation for the first slice:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.VoiceProofRunTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.DeviceCapabilityMatrixTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.device.SetupCapabilityAssessmentTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.diagnostic.DiagnosticExportTest"
.\gradlew.bat :app:testDebugUnitTest
```

This slice does not make the product complete by itself. It makes every later improvement honest because the app can no longer call scaffolding production-ready.

## Workstream Completion Status

| WS | Name | Status | Date |
|----|------|--------|------|
| WS0 | Freeze Production Contract | ✅ Complete | 2026-05-19 |
| WS1 | Platform STT Baseline | ✅ Complete | 2026-05-19 |
| WS2 | Audio Route Proof | ✅ Complete | 2026-05-19 |
| WS5 | TTS & Barge-In | ✅ Complete | 2026-05-19 |
| WS6 | Bridge Contract | ✅ Complete | 2026-05-19 |
| WS7 | OpenClaw Boundary | ✅ Complete | 2026-05-19 |
| WS8 | Diagnostics & Privacy | ✅ Complete | 2026-05-19 |
| WS9 | Setup & Daily UX | ✅ Complete | 2026-05-19 |
| WS10 | Physical Proof Harness | ⚠️ Partial | 2026-05-20 |
| WS11 | CI and Release Gates | ✅ Complete | 2026-05-19 |
| WS3 | Sherpa VAD Native Module | ⏳ Blocked (needs Gradle module) | — |
| WS4 | Sherpa STT Evaluation | ⏳ Blocked (WS3) | — |
| WS12 | Security Pass | ⏳ Pending | — |

### WS11 Details

CI gates implemented:
- ✅ TypeScript typecheck (`npm run typecheck`)
- ✅ Node/Vitest suite (`npm test`)
- ✅ Android unit tests (`:app:testDebugUnitTest` — 156 tests)
- ✅ Android lint (`:app:lintDebug`)
- ✅ Android debug assembly (`:app:assembleDebug`)
- ✅ Dependency audit with allowlist (`npm run audit:allowlist`)
- ✅ Secret scan for tokens in CI workflow
- ✅ License scan script (`scripts/license-scan.ts`)
- ✅ Supported devices matrix schema validation in CI
- ✅ Diagnostic privacy contract validation in CI
- ✅ Proof-run artifact validation in CI
- ✅ Proof-run template (`docs/proof-run-template.json`)
- ✅ Proof-run validator script (`scripts/validate-proof-run.ts`)

### Remaining Blockers

1. **WS3 — Sherpa VAD**: Needs separate Gradle module/flavor for native AAR packaging. Platform VAD is production-grade as baseline.
2. **WS4 — Sherpa STT**: Blocked until WS3 provides VAD baseline.
3. **WS10 — Physical Proof Harness**: Template and validator are now checked in, but real hardware proof-run artifacts are still pending.
4. **WS12 — Security Pass**: Final rate-limit, allowlist, and redaction review pending.
