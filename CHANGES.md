# Changes

## Implementation contract assumptions

- The request template still contained `[LIST DIRS]`, `[FILE]`, and `[LANG + VERSION]` placeholders. I constrained the implementation to the existing Android relay voice/audio surface under `android-relay/app/src` plus this `CHANGES.md`, kept the service/activity entry points unchanged, and did not add dependencies or schema changes.

## Voice/audio engine boundary and route probe implementation

- `android-relay/app/src/main/java/com/openclaw/relay/SpeechEngineContracts.kt`
  - Added internal speech input, VAD probe, and speech output contracts so platform STT, callback VAD, future Sherpa VAD/STT, and TTS can be swapped without touching bridge or policy code.
  - Added endpointing defaults from the blueprint: 750 ms complete silence, 300 ms minimum length, offline disabled by default, and on-device recognition opt-in.

- `android-relay/app/src/main/java/com/openclaw/relay/PlatformSpeechRecognizerEngine.kt`
  - Added the platform speech recognizer adapter around the existing `AndroidSpeechRecognizer`.
  - Exposes capability metadata for availability, partial results, endpointing hints, on-device support, and raw-audio storage.

- `android-relay/app/src/main/java/com/openclaw/relay/AudioProbeMetrics.kt`
  - Added privacy-safe audio probe metrics and an accumulator for frames read, non-zero frame ratio, read errors, peak amplitude, noise floor estimate, route proof, and raw-audio persistence status.
  - Stores amplitude summaries only; no raw audio path or audio payload is retained.

- `android-relay/app/src/main/java/com/openclaw/relay/AudioRecordRouteProbe.kt`
  - Added a short diagnostic-only `AudioRecord` probe using `VOICE_RECOGNITION` first and `MIC` fallback, 16 kHz mono PCM, and 512-sample windows.
  - Handles missing microphone permission, unsupported format, busy mic, read failures, and route proof capture without running concurrently with `SpeechRecognizer`.

- `android-relay/app/src/main/java/com/openclaw/relay/AndroidSpeechRecognizer.kt`
  - Added configurable endpointing hints, prefer-offline flag, and on-device recognizer opt-in.
  - Added on-device availability reporting for the new engine capability boundary.

- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt`
  - Swapped direct service ownership of `AndroidSpeechRecognizer` for the new `SpeechInputEngine` interface through `PlatformSpeechRecognizerEngine`.
  - Kept foreground service/media-session behavior and app entry points unchanged.

- `android-relay/app/src/main/java/com/openclaw/relay/VoiceTelemetry.kt`
  - Added the latest audio probe metrics to the voice diagnostics snapshot.

- `android-relay/app/src/main/java/com/openclaw/relay/RelayStateStore.kt`
  - Added state-store support for recording audio probe metrics.

- `android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt`
  - Added redacted audio probe status, source, duration, non-zero frame ratio, peak amplitude, and read-error count to voice diagnostics export.

## Voice proof matrix implementation

- `android-relay/app/src/main/java/com/openclaw/relay/VoiceProofRun.kt`
  - Added a privacy-safe 20-session proof-run model for route reliability, STT success, wrong-mic suspicion, TTS interruption target tracking, audio-probe success, reliability percentage, and failure classes.
  - Replaces repeated updates for the same `sessionId` instead of double-counting partial/RMS/final callbacks.

- `android-relay/app/src/main/java/com/openclaw/relay/VoiceTelemetry.kt`
  - Added the current voice proof run to `VoiceDiagnosticsSnapshot`.

- `android-relay/app/src/main/java/com/openclaw/relay/RelayStateStore.kt`
  - Added start/reset support for voice proof runs.
  - Automatically feeds speech session metrics and audio probe metrics into the active proof run.

- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt`
  - Added a protected audio route probe service action.
  - Runs `AudioRecordRouteProbe` as a separate diagnostic mode and refuses to run it during an active listening session.

- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt`
  - Added view-model methods to start/reset proof runs and run the audio route probe.
  - Starts a one-session proof run during setup STT validation so onboarding proof feeds the same model.

- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt`
  - Wired voice proof run callbacks and state into the Help screen.

- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/HelpScreen.kt`
  - Added a user-visible "Voice proof matrix" card showing session progress, reliability, route proof, wrong-mic count, failure class, start/reset actions, and a mic probe action.

- `android-relay/app/src/main/java/com/openclaw/relay/RelayCommandAuth.kt`
  - Added the audio route probe action to the internal command-token allowlist.

- `android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt`
  - Added redacted voice proof run summary fields to support exports.

## TTS output engine and interruption proof implementation

- `android-relay/app/src/main/java/com/openclaw/relay/SpeechEngineContracts.kt`
  - Extended the speech output boundary with lifecycle cleanup so Android TTS remains swappable behind an engine contract.

- `android-relay/app/src/main/java/com/openclaw/relay/AndroidTtsOutputEngine.kt`
  - Added the platform TTS output-engine adapter around `AndroidTtsSpeaker`.
  - Preserves existing Android `TextToSpeech` behavior while moving service calls to the engine abstraction.

- `android-relay/app/src/main/java/com/openclaw/relay/TtsInterruptionMetrics.kt`
  - Added privacy-safe TTS interruption metrics for barge-in, stop requested, superseded speech, and service shutdown scenarios.
  - Tracks requested time, TTS stop latency, listening restart latency, and the 250 ms interruption target.

- `android-relay/app/src/main/java/com/openclaw/relay/AndroidTtsSpeaker.kt`
  - Added explicit utterance IDs so output-engine requests can correlate playback metrics to service actions.

- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt`
  - Replaced direct speech output calls with `SpeechOutputEngine`.
  - Records both halves of barge-in proof: when TTS is stopped and when listening restarts.

- `android-relay/app/src/main/java/com/openclaw/relay/VoiceTelemetry.kt`
  - Added the latest TTS interruption metrics to the voice diagnostics snapshot.

- `android-relay/app/src/main/java/com/openclaw/relay/RelayStateStore.kt`
  - Added state-store support for recording TTS interruption metrics and feeding them into active voice proof runs.

- `android-relay/app/src/main/java/com/openclaw/relay/VoiceProofRun.kt`
  - Added TTS interruption samples to the proof run and summary count for interruptions that meet the 250 ms target.

- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/HelpScreen.kt`
  - Shows the voice proof matrix barge-in target count.

- `android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt`
  - Added redacted TTS barge-in latency and target-met fields to support exports.

## Offline speech evaluation, Sherpa guardrails, and media-button proof implementation

- `android-relay/app/src/main/java/com/openclaw/relay/OfflineSpeechEvaluation.kt`
  - Added production-safe offline speech readiness and model inspection for Sherpa evaluation.
  - Keeps platform `SpeechRecognizer` as the default engine and records explicit reasons when Sherpa evaluation cannot run.
  - Validates required model files, footprint, optional checksum, and no-raw-audio behavior without linking native code yet.

- `android-relay/app/src/main/java/com/openclaw/relay/SherpaEvaluationEngines.kt`
  - Added non-default Sherpa STT and Sherpa/Silero VAD evaluation placeholders.
  - They fail gracefully with diagnostic errors until the native Sherpa runtime is intentionally linked.

- `android-relay/app/src/main/java/com/openclaw/relay/OfflineSpeechBenchmark.kt`
  - Added a pure benchmark summary and promotion gate for offline STT.
  - Enforces the blueprint thresholds: 20 samples, 95 percent command/route reliability, median final endpoint under 900 ms, no wrong-route detection, and barge-in target met.

- `android-relay/app/src/main/java/com/openclaw/relay/MediaButtonDiagnostics.kt`
  - Added media-button normalization, duplicate-event debounce, and foreground control proof models.
  - Normalizes play/pause, next, previous, stop, headset hook, action, repeat count, route state, and service-running state.
  - Added a foreground service recovery policy that restarts into safe idle instead of restoring active microphone capture, speech playback, or in-flight bridge waits after process death.

- `android-relay/app/src/main/java/com/openclaw/relay/PlatformSpeechRecognizerEngine.kt`
  - Added configurable engine ID and on-device defaults so platform and platform-on-device paths can share the same engine adapter.

- `android-relay/app/src/main/java/com/openclaw/relay/RelayModels.kt`
  - Added persisted config fields for speech input mode and offline speech model metadata.

- `android-relay/app/src/main/java/com/openclaw/relay/RelayConfigStorage.kt`
  - Persists speech input mode and offline model path/version/checksum.

- `android-relay/app/src/main/java/com/openclaw/relay/VoiceTelemetry.kt`
  - Added offline speech readiness, last media-button event, foreground controls, and foreground service recovery state to the voice diagnostics snapshot.

- `android-relay/app/src/main/java/com/openclaw/relay/RelayStateStore.kt`
  - Added state-store methods for offline speech readiness, media-button telemetry, foreground-control proof, foreground service snapshots, and safe recovery-plan application.

- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt`
  - Added update methods for speech input mode and offline model metadata.

- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt`
  - Creates the speech input engine through the new factory and records notification control availability whenever the foreground service is promoted.
  - Applies safe service recovery on startup and records active/inactive foreground snapshots on foreground promotion and service teardown.

- `android-relay/app/src/main/java/com/openclaw/relay/signal/AndroidMediaSessionProvider.kt`
  - Records redacted media-button telemetry for supported and ignored hardware events.
  - Debounces accidental duplicate down events while preserving intentional multi-tap timing.
  - Maps media stop to the authenticated relay stop action.

- `android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt`
  - Exports redacted offline speech readiness, model footprint, failure reasons, foreground controls, foreground service recovery state, and last media-button mapping.

## Voice/audio pipeline baseline implementation

- `android-relay/app/src/main/java/com/openclaw/relay/AudioRouteProof.kt`
  - Added privacy-safe route proof classification for active Bluetooth routes, suspect built-in fallback, failed routes, route timing, communication-device counts, and hashed device identity.

- `android-relay/app/src/main/java/com/openclaw/relay/VoiceTelemetry.kt`
  - Added speech session metrics, platform callback-VAD observations, TTS playback metrics, and mutable recorders used by the Android relay service.

- `android-relay/app/src/main/java/com/openclaw/relay/AndroidSpeechRecognizer.kt`
  - Added recognizer lifecycle callbacks for ready, speech start/end, RMS frames, recognizer creation, and listening start.
  - Added error code and endpoint-reason classification so diagnostics can distinguish timeout, no speech, busy, audio, permission, network, and unknown failures.

- `android-relay/app/src/main/java/com/openclaw/relay/AndroidTtsSpeaker.kt`
  - Added TTS playback metric emission for requested, started, completed, stopped, and error states.
  - Preserved existing `TextToSpeech.QUEUE_FLUSH` behavior and completion callbacks.

- `android-relay/app/src/main/java/com/openclaw/relay/BluetoothAudioRouter.kt`
  - Added route request/ready timing and route-proof generation to route snapshots.
  - Kept the existing Bluetooth/wired communication routing behavior unchanged.

- `android-relay/app/src/main/java/com/openclaw/relay/RelayModels.kt`
  - Added audio route proof to `RelayAudioRouteSnapshot`.
  - Added `VoiceDiagnosticsSnapshot` to `RelayUiState`.

- `android-relay/app/src/main/java/com/openclaw/relay/RelayStateStore.kt`
  - Added state-store methods for speech session metrics, derived VAD observations, and TTS playback metrics.

- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt`
  - Wired speech metrics into listening sessions, route preparation, recognizer callbacks, speech errors, final transcripts, TTS playback, and interruption handling.
  - Kept the service entry point and existing foreground/media-session behavior intact.

- `android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt`
  - Added privacy-safe voice diagnostics to diagnostic export: speech engine, endpoint reason, route state, route timing, RMS summary, callback-VAD result, wrong-mic suspicion, transcript length only, and TTS timing.
  - Kept raw route details hidden unless the existing raw-route option is enabled.

- `verify-product.ps1`
  - Runs the bridge Vitest suite serially with the existing Vitest dependency so OpenClaw local CLI/gateway integration tests do not contend with each other during the product gate.

## Bluetooth route fallback implementation

- `android-relay/app/src/main/java/com/openclaw/relay/AudioRouteFallbackPolicy.kt`
  - Added a pure route fallback policy that keeps ready headset routes, blocks unsafe headset failures when phone fallback is disabled, and creates an explicit phone-microphone fallback route when the user has enabled it.
  - Preserves the route-settle window for active-but-not-ready headset routes so slow earbuds are not prematurely downgraded.

- `android-relay/app/src/main/java/com/openclaw/relay/RelayModels.kt`
  - Added `isPhoneMicFallback` to `RelayAudioRouteSnapshot` so UI and diagnostics can distinguish intentional fallback from an unverified route.

- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt`
  - Wired route preparation and final route-settle failure through `AudioRouteFallbackPolicy`.
  - Clears failed headset routing before using phone-microphone fallback, while still allowing existing settle retries for delayed headset mic activation.

- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/HomeScreen.kt`
  - Updated the mic status chip to show "Phone mic fallback", "Using earbuds mic", or "Mic route unverified" instead of implying phone mic use for every unverified state.

- `android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt`
  - Added `phoneMicFallbackActive` to redacted voice diagnostics.

- `android-relay/app/src/main/java/com/openclaw/relay/RelayStateStore.kt`
  - Added a user-facing recovery message that specifically tells the user to enable Phone microphone fallback or reconnect earbuds after route failure.

## Tests

- `android-relay/app/src/test/java/com/openclaw/relay/AudioRouteProofTest.kt`
  - Added tests for active Bluetooth route proof, built-in fallback suspicion, failed routes, route timing, and device-name hashing.

- `android-relay/app/src/test/java/com/openclaw/relay/SpeechSessionMetricsTest.kt`
  - Added tests for speech timing metrics, transcript privacy, callback-VAD derivation, wrong-mic suspicion, and error endpoint classification.

- `android-relay/app/src/test/java/com/openclaw/relay/TtsPlaybackMetricsTest.kt`
  - Added tests for TTS start delay, playback duration, stop latency, and completed playback metrics.

- `android-relay/app/src/test/java/com/openclaw/relay/SpeechEngineContractsTest.kt`
  - Added tests for speech session defaults, engine capability shape, and platform callback-VAD derivation.

- `android-relay/app/src/test/java/com/openclaw/relay/AudioProbeMetricsTest.kt`
  - Added tests for amplitude-summary recording, privacy-safe no-raw-audio behavior, read-error counting, route proof correlation, and permission-denied metrics.

- `android-relay/app/src/test/java/com/openclaw/relay/VoiceProofRunTest.kt`
  - Added tests for proof-run scoring, duplicate session replacement, failed run classification, wrong-mic suspicion, route failures, audio-probe attachment, and no-raw-audio behavior.

- `android-relay/app/src/test/java/com/openclaw/relay/RelayCommandAuthTest.kt`
  - Added coverage for protecting the new audio route probe service action.

- `android-relay/app/src/test/java/com/openclaw/relay/TtsInterruptionMetricsTest.kt`
  - Added tests for TTS stop latency, listening restart latency, target pass/fail behavior, and proof-run interruption counting.

- `android-relay/app/src/test/java/com/openclaw/relay/OfflineSpeechEvaluationTest.kt`
  - Added tests for platform-default readiness, Sherpa model validation, graceful unavailable Sherpa STT/VAD behavior, and offline benchmark promotion thresholds.

- `android-relay/app/src/test/java/com/openclaw/relay/MediaButtonDiagnosticsTest.kt`
  - Added tests for media-button normalization, duplicate-event debouncing, required foreground notification controls, safe service restart recovery, and missing foreground control detection.

- `android-relay/app/src/test/java/com/openclaw/relay/AudioRouteFallbackPolicyTest.kt`
  - Added tests for keeping ready headset routes, blocking failed routes when fallback is disabled, converting failed/suspect routes into explicit phone-mic fallback when enabled, preserving the settle window, and showing actionable recovery copy.

## Verification

- Focused telemetry tests passed with `./gradlew.bat :app:testDebugUnitTest --tests com.openclaw.relay.SpeechSessionMetricsTest --tests com.openclaw.relay.AudioRouteProofTest --tests com.openclaw.relay.TtsPlaybackMetricsTest`.
- Full Android JVM unit tests passed with `./gradlew.bat :app:testDebugUnitTest`.
- Focused engine/probe tests passed with `./gradlew.bat :app:testDebugUnitTest --tests com.openclaw.relay.SpeechEngineContractsTest --tests com.openclaw.relay.AudioProbeMetricsTest`.
- Focused voice proof tests passed with `./gradlew.bat :app:testDebugUnitTest --tests com.openclaw.relay.VoiceProofRunTest`.
- Focused TTS interruption tests passed with `./gradlew.bat :app:testDebugUnitTest --tests com.openclaw.relay.TtsInterruptionMetricsTest --tests com.openclaw.relay.TtsPlaybackMetricsTest --tests com.openclaw.relay.VoiceProofRunTest --tests com.openclaw.relay.SpeechEngineContractsTest`.
- Focused offline/media reliability tests passed with `./gradlew.bat :app:testDebugUnitTest --tests com.openclaw.relay.OfflineSpeechEvaluationTest --tests com.openclaw.relay.MediaButtonDiagnosticsTest`.
- Focused route fallback tests passed with `./gradlew.bat :app:testDebugUnitTest --tests com.openclaw.relay.AudioRouteFallbackPolicyTest`.
- Emulator installed-app validation passed on `emulator-5554` with `simulation/android-relay/validate-installed-app.ps1`, covering bridge health, relay start, status event, headset wake event, approval prompt, cancel, approve, no-pending approval handling, and relay stop.
- Full product gate passed with `npm run verify:product`.
- Branch coverage percentage was not emitted because the existing Android Gradle project does not expose a Kover/Jacoco coverage task, and no new dependency was added under the request constraints.
