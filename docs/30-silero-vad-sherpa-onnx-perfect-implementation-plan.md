# Silero VAD And Sherpa-ONNX Perfect Implementation Plan

Generated: 2026-05-20

Scope:

- Real Silero VAD integration through Sherpa-ONNX.
- Real Sherpa-ONNX speech-to-text integration behind `SpeechInputEngine`.
- Android relay only. This is not an earbud vendor protocol integration.
- Default beta behavior remains Android platform STT until Sherpa proves better on real devices.

References:

- [27-production-grade-relay-voice-product-plan.md](27-production-grade-relay-voice-product-plan.md)
- [29-beta-release-reliability-audit-and-plan.md](29-beta-release-reliability-audit-and-plan.md)
- [22-voice-audio-pipeline-external-source-blueprint.md](22-voice-audio-pipeline-external-source-blueprint.md)
- Sherpa-ONNX Android AAR docs: <https://github.com/k2-fsa/sherpa-onnx/blob/master/android/SherpaOnnxAar/README.md>
- Sherpa-ONNX VAD Kotlin API reference in this repo: `external/audio-pipeline-references/sherpa-onnx/sherpa-onnx/kotlin-api/Vad.kt`
- Sherpa-ONNX online recognizer Kotlin API reference in this repo: `external/audio-pipeline-references/sherpa-onnx/sherpa-onnx/kotlin-api/OnlineRecognizer.kt`

## Executive Decision

Implement Silero VAD first, then Sherpa-ONNX STT.

Silero VAD gives immediate reliability value without replacing the known-good platform recognizer. It can prove no-signal, wrong-mic suspicion, speech start, silence, and noisy-route behavior. Sherpa STT should come second because it owns the microphone and changes the user-facing recognition path.

The first production-quality goal is:

> Run Silero VAD as a real, feature-flagged diagnostic engine using the same Bluetooth route and privacy rules as the rest of DevPods, while Android `SpeechRecognizer` remains the default STT path.

The second production-quality goal is:

> Add Sherpa streaming STT as an experimental `SpeechInputEngine` that can be benchmarked against platform STT using identical proof metrics.

## Non-Negotiable Product Rules

- No placeholder engine can be user-visible.
- No raw audio is stored by default.
- `SpeechRecognizer` and Sherpa `AudioRecord` capture never run at the same time.
- Android `SpeechRecognizer` remains the beta default until Sherpa passes proof.
- Phone mic fallback remains explicit and labeled.
- Model files are checksum-verified before use.
- Native load failure, ABI mismatch, missing model, low storage, and checksum mismatch all fall back cleanly.
- Sherpa/Silero must be disabled by default in beta unless a real proof artifact exists.
- Every metric emitted by Sherpa must fit the existing proof and diagnostic model.

## Target Architecture

```text
RelayService
  -> VoiceSessionCoordinator behavior
  -> AudioRouteSession / BluetoothAudioRouter
  -> AudioCaptureOwner
      -> PlatformSpeechRecognizerEngine
      -> SherpaVadProbe
      -> SherpaSpeechInputEngine
  -> VoiceTelemetry / VoiceProofRun / VoiceDiagnosticsStore
```

New runtime boundaries:

| Boundary | Responsibility |
| --- | --- |
| `sherpa-runtime` module | Owns Sherpa-ONNX Kotlin API, native library loading, ABI availability, and low-level wrappers. |
| App `SherpaVadProbe` | Converts DevPods route/audio/proof requests into Silero VAD observations. |
| App `SherpaSpeechInputEngine` | Converts DevPods speech session requests into Sherpa streaming ASR sessions. |
| `SherpaModelCatalog` | Defines VAD and STT model specs, files, versions, URLs, sizes, checksums, and licenses. |
| `SherpaModelManager` | Downloads, verifies, installs, rolls back, lists, and clears models. |
| `AudioCaptureOwner` | Ensures only one capture path owns the microphone. |

## Current Starting Point

Already present:

- `SpeechInputEngine`, `VadProbe`, and `SpeechOutputEngine` contracts.
- `SherpaSpeechInputEngine` placeholder.
- `SherpaVadProbe` placeholder.
- `OfflineSpeechReadiness`.
- `SherpaModelManager`.
- `AudioRecordRouteProbe`.
- `VoiceTelemetry`, `VoiceProofRun`, `VadTelemetry`, and diagnostic export scaffolding.

Missing:

- Sherpa Android native runtime dependency.
- Real `sherpa-onnx-jni` native libraries.
- Build flag or flavor that can prove the native runtime is linked.
- Real Silero VAD model spec and install path.
- Real STT model catalog.
- Real VAD capture loop using Sherpa `Vad`.
- Real STT capture loop using Sherpa `OnlineRecognizer`.
- Audio capture ownership guard.
- Model download UI and failure UX.
- Physical proof artifacts.

## Implementation Strategy

Use a staged merge plan. Each step should leave the platform STT path green and should be revertible without breaking beta.

Preferred packaging:

- Create `android-relay/sherpa-runtime` as an Android library module.
- Vendor only the required Sherpa Kotlin API wrappers and native libraries into that module.
- Do not place large speech models in the base app.
- Include `arm64-v8a` first. Add `x86_64` only for emulator/dev support if needed.
- Keep all Sherpa-facing code isolated so the app can still build and run without the module in an emergency.

Alternative packaging:

- Use a prebuilt `sherpa-onnx-<version>.aar`.
- This is faster, but harder to audit and patch. Use only if module packaging blocks progress.

## Step 0: Make Existing Release Gates Green

Context:

Sherpa integration should not land on top of unrelated red gates. The current beta audit shows full Node tests and allowlist audit failing.

Tasks:

- Fix full `npm test`.
- Fix `npm run audit:allowlist`.
- Fix or mark `docs/proof-run-template.json` as a non-passed template.
- Confirm Android tests, assemble, and lint are green before adding native code.

Verification:

```powershell
npm run typecheck
npm run build
npm test
npm audit --audit-level=moderate
npm run audit:allowlist
cd android-relay
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
```

Exit criteria:

- No known unrelated red gate exists before native Sherpa work begins.

## Step 1: Add The Sherpa Runtime Module

Context:

Sherpa-ONNX Android integration requires Kotlin API wrappers plus native `sherpa-onnx-jni` libraries. The official Android AAR docs build a `sherpa_onnx` library after copying Android JNI libs into `src/main/jniLibs`.

Tasks:

- Add `include(":sherpa-runtime")` to `android-relay/settings.gradle.kts`.
- Create `android-relay/sherpa-runtime/build.gradle.kts`.
- Namespace the module clearly, for example `com.openclaw.relay.sherpa`.
- Add `jniLibs/arm64-v8a` with Sherpa-ONNX Android shared libraries.
- Copy or wrap only the needed Kotlin APIs:
  - `Vad`
  - `VadModelConfig`
  - `SileroVadModelConfig`
  - `OnlineRecognizer`
  - `OnlineRecognizerConfig`
  - `OnlineStream`
  - model config classes required by the chosen STT model.
- Add a `SherpaRuntimeAvailability` class:
  - `isNativeLibraryLoadable`
  - `availableAbis`
  - `runtimeVersion`
  - `failureReason`
- Add ProGuard/R8 keep rules for native-bound classes.
- Add `implementation(project(":sherpa-runtime"))` to `:app` only in the Sherpa-enabled build path.

Recommended build shape:

```text
android-relay/
  settings.gradle.kts
  sherpa-runtime/
    build.gradle.kts
    src/main/java/com/openclaw/relay/sherpa/...
    src/main/jniLibs/arm64-v8a/...
    consumer-rules.pro
  app/
```

Verification:

```powershell
cd android-relay
.\gradlew.bat :sherpa-runtime:assembleDebug
.\gradlew.bat :app:assembleDebug
```

Exit criteria:

- App builds with Sherpa runtime linked.
- App still builds if Sherpa is disabled by build flag or flavor.
- Native load failure is represented as data, not a crash.

Rollback:

- Remove the app dependency and keep placeholder engines.
- Platform STT must continue to work.

## Step 2: Split VAD And STT Model Catalogs

Context:

Current `OfflineSpeechModelSpec` assumes STT transducer files: `tokens.txt`, `encoder.onnx`, `decoder.onnx`, `joiner.onnx`. Silero VAD is a separate model and should not share the same readiness contract.

Tasks:

- Add `SherpaModelType`:
  - `SILERO_VAD`
  - `STREAMING_STT`
- Add `SherpaModelSpec`:
  - `id`
  - `type`
  - `version`
  - `requiredFiles`
  - `downloadBaseUrl`
  - `expectedSha256ByFile`
  - `expectedCombinedSha256`
  - `totalBytes`
  - `license`
  - `sourceUrl`
  - `installRoot`
- Add built-in catalog entries:
  - `silero_vad.onnx`
  - one selected English streaming STT model using `tokens.txt`, `encoder.onnx`, `decoder.onnx`, and `joiner.onnx`.
- Keep exact model URL and checksum in the catalog, not in UI code.
- Update `SherpaModelManager` to support:
  - per-file checksum
  - atomic temp download directory
  - install marker
  - rollback on failed checksum
  - low-storage preflight
  - partial download cleanup
  - installed model list by type.
- Keep the old `OfflineSpeechModelSpec` as a compatibility adapter or migrate tests in one patch.

Verification:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.SherpaModelManagerTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.OfflineSpeechEvaluationTest"
```

Exit criteria:

- VAD readiness can be true while STT readiness is false.
- STT readiness can be false without blocking VAD diagnostics.
- Checksum mismatch never marks a model ready.
- Low storage produces a user-actionable failure.

## Step 3: Add Audio Capture Ownership

Context:

The biggest reliability bug to avoid is microphone contention. Android `SpeechRecognizer`, `AudioRecordRouteProbe`, Silero VAD, and Sherpa STT cannot all grab the mic casually.

Tasks:

- Add `AudioCaptureOwner` or `MicCaptureCoordinator`.
- Owners:
  - `PLATFORM_SPEECH_RECOGNIZER`
  - `AUDIO_RECORD_ROUTE_PROBE`
  - `SHERPA_VAD`
  - `SHERPA_STT`
- API:

```kotlin
interface AudioCaptureOwner {
    suspend fun acquire(owner: CaptureOwner, sessionId: String): CaptureLease
    fun currentOwner(): CaptureOwner?
}

interface CaptureLease {
    val owner: CaptureOwner
    fun release()
}
```

- Integrate with:
  - `PlatformSpeechRecognizerEngine.start`
  - `AudioRecordRouteProbe.runProbe`
  - `SherpaVadProbe.start`
  - `SherpaSpeechInputEngine.start`
- If the mic is busy, emit a typed failure:
  - `mic_capture_busy`
  - `platform_recognizer_active`
  - `sherpa_capture_active`

Verification:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.AudioCaptureOwnerTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.OfflineSpeechEvaluationTest"
```

Exit criteria:

- Unit tests prove two capture owners cannot run concurrently.
- A VAD probe cannot start during platform STT.
- Sherpa STT cannot start during route probe.
- Failures are diagnostic, not crashes.

## Step 4: Implement Real Silero VAD Probe

Context:

Sherpa-ONNX VAD uses `Vad` with a `SileroVadModelConfig`. The local reference API supports `acceptWaveform(samples)`, `isSpeechDetected()`, `clear()`, `reset()`, and `release()`. The Android sample uses 16 kHz mono PCM and 512-sample windows.

Tasks:

- Replace placeholder `SherpaVadProbe` with a real implementation when runtime and model readiness pass.
- Keep the same public `VadProbe` interface.
- Add `SherpaVadConfig`:
  - `sampleRateHz = 16000`
  - `windowSize = 512`
  - `threshold = 0.5f`
  - `minSilenceDuration = 0.25f`
  - `minSpeechDuration = 0.25f`
  - `maxSpeechDuration = 5.0f`
  - `numThreads = 1`
  - `provider = "cpu"`
- Use `AudioRecord` only after `AudioCaptureOwner` grants a lease.
- Convert PCM 16-bit `ShortArray` to `FloatArray` in `[-1.0, 1.0]`.
- Feed every window to `Vad.acceptWaveform(samples)`.
- Record:
  - total windows
  - speech windows
  - first speech detected at
  - last speech detected at
  - nonzero frame ratio
  - peak amplitude
  - read error count
  - route snapshot
  - model version
  - native runtime version
  - CPU time if practical.
- Emit a DevPods observation compatible with `PlatformVadObservation` or add `SherpaVadObservation` and a common export DTO.
- Do not store raw PCM.

Implementation notes:

- Run as diagnostic mode first, not continuous background mode.
- Default duration should stay short, for example 1200 ms.
- Use the current route proof path before opening `AudioRecord`.
- On `UnsatisfiedLinkError`, return `sherpa_native_load_failed`.
- On missing model, return `sherpa_vad_model_missing`.

Verification:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.SherpaVadProbeTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.VadTelemetryTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.diagnostic.DiagnosticExportTest"
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
```

Physical validation:

- Quiet room, no speech.
- Quiet room, speech.
- Noisy room, speech.
- Earbuds connected but mic route blocked.
- Phone mic fallback explicitly enabled.

Exit criteria:

- VAD detects speech in a short diagnostic run on a real phone.
- No-signal route is flagged.
- Wrong-route suspicion improves over platform RMS-only logic.
- Route probe, VAD probe, and platform STT never overlap mic ownership.

## Step 5: Integrate VAD Into Proof Runs And Diagnostics

Context:

VAD only helps product reliability if its findings can block false Ready states and help support explain failures.

Tasks:

- Add VAD observation fields to proof run summary:
  - `sherpaVadRunCount`
  - `sherpaVadSpeechDetectedCount`
  - `sherpaVadNoSignalCount`
  - `sherpaVadReadErrorCount`
  - `sherpaVadWrongRouteSuspectedCount`
  - `sherpaVadModelVersion`
- Add blocking failure reasons:
  - `sherpa_vad_no_signal`
  - `sherpa_vad_read_failed`
  - `sherpa_vad_route_mismatch`
  - `sherpa_vad_model_unavailable`
- Diagnostic export:
  - include summary metrics by default
  - exclude raw audio always by default
  - include model version and checksum status
  - include native runtime availability.
- UI:
  - Developer Mode: show VAD model installed, native status, last VAD result.
  - Help: if VAD fails, show one repair action.
  - Setup: do not require Sherpa VAD for beta unless feature flag is enabled.

Verification:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.VoiceProofRunTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.diagnostic.DiagnosticExportTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.HomeScreenReadyStateTest"
```

Exit criteria:

- VAD failures can block Sherpa-enabled readiness.
- VAD diagnostics explain route/no-signal failures without raw audio.
- Platform-only beta remains unaffected when VAD is disabled.

## Step 6: Implement Real Sherpa Streaming STT

Context:

Sherpa online recognition uses `OnlineRecognizer.createStream()`, `OnlineStream.acceptWaveform(...)`, `recognizer.isReady(stream)`, `recognizer.decode(stream)`, `recognizer.getResult(stream)`, `recognizer.isEndpoint(stream)`, and `recognizer.reset(stream)`.

Tasks:

- Replace placeholder `SherpaSpeechInputEngine` with a real implementation when runtime and STT model readiness pass.
- Keep it behind `SpeechInputMode.SHERPA_EVALUATION` or rename to `SHERPA_EXPERIMENTAL`.
- Build `OnlineRecognizerConfig` from the selected model catalog entry.
- Use one `AudioRecord` capture loop per session.
- Sample rate: 16 kHz mono.
- Buffer size: start with 100 ms windows for STT.
- Emit callbacks:
  - `onRecognizerCreated`
  - `onListeningStarted`
  - `onReadyForSpeech`
  - `onBeginningOfSpeech`
  - `onPartialTranscript`
  - `onEndOfSpeech`
  - `onFinalTranscript`
  - `onError`
- Map endpoint and errors into existing `SpeechEndpointReason`.
- On stop/cancel:
  - stop capture
  - release stream
  - release recognizer if session-scoped
  - release mic lease
  - emit final metrics.
- Preserve fallback:
  - if Sherpa cannot start, route to platform STT only before capture starts
  - do not switch mid-utterance.

Verification:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.SherpaSpeechInputEngineTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.SpeechSessionMetricsTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.OfflineSpeechEvaluationTest"
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
```

Physical validation:

- 20 command sessions with platform STT.
- 20 command sessions with Sherpa STT.
- 5 no-speech sessions.
- 5 noisy sessions.
- 5 interrupted sessions.
- 3 route-loss sessions.

Exit criteria:

- Sherpa STT produces final transcript on real phone audio.
- Stuck sessions are impossible or timed out cleanly.
- Platform fallback remains available.
- Sherpa STT is not shown as production until benchmark gates pass.

## Step 7: Benchmark And Compare Against Platform STT

Context:

Sherpa should be promoted only if it improves real user outcomes.

Tasks:

- Extend `OfflineSpeechBenchmark` to compare:
  - platform STT
  - platform on-device STT
  - Sherpa STT
  - platform STT plus Sherpa VAD diagnostic.
- Metrics:
  - wake to listening started
  - first audio accepted
  - first partial
  - final transcript
  - endpoint delay
  - command success
  - no-speech false positive
  - wrong-mic detection
  - CPU and memory
  - battery drain
  - thermal warning
  - crash/native failure.
- Add benchmark export under diagnostics with transcript redacted by default.

Promotion thresholds:

| Dimension | Minimum threshold |
| --- | --- |
| Stability | 0 crashes/native aborts in repeated proof run. |
| Session reliability | At least 19/20 command sessions complete. |
| Command success | At least equal to platform STT on target command set. |
| Endpointing | No worse than platform p95 finalization unless offline mode is the explicit reason. |
| Battery/thermal | No unacceptable drain or thermal throttling in 30-minute repeated run. |
| Recovery | Platform fallback works immediately after Sherpa failure. |

Verification:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.OfflineSpeechEvaluationTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.VoiceProofRunTest"
```

Exit criteria:

- A written benchmark artifact exists.
- Product decision is explicit:
  - keep hidden
  - expose as experimental
  - promote VAD only
  - promote STT as opt-in.

## Step 8: Add Feature Flags And UX

Context:

Experimental audio engines can damage trust if users can enable them casually.

Tasks:

- Add feature flags:
  - `sherpaRuntimeEnabled`
  - `sherpaVadDiagnosticsEnabled`
  - `sherpaSttExperimentalEnabled`
  - `sherpaModelDownloadsEnabled`
- Default all flags off outside developer/internal builds.
- Add Developer Mode controls:
  - install VAD model
  - run VAD probe
  - install STT model
  - run benchmark
  - clear models
  - export Sherpa diagnostics.
- Add clear status labels:
  - Native runtime missing
  - Model missing
  - Checksum mismatch
  - Ready for VAD diagnostics
  - Ready for experimental STT
  - Failed, falling back to platform.
- Do not add a normal Settings toggle for Sherpa STT until benchmark gates pass.

Verification:

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.OfflineSpeechEvaluationTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.diagnostic.DiagnosticExportTest"
.\gradlew.bat :app:lintDebug
```

Exit criteria:

- Users cannot accidentally enable Sherpa STT in beta.
- Developers can install models, run VAD, run STT benchmarks, and export diagnostics.
- All disabled states are clear and actionable.

## Step 9: CI, Artifacts, And Supply Chain

Context:

Native audio dependencies need stronger provenance than pure Kotlin code.

Tasks:

- Add `docs/sherpa-onnx-provenance.md`:
  - Sherpa version
  - source URL
  - license
  - release artifact checksums
  - included ABIs
  - included Kotlin API files
  - local modifications
  - reviewer.
- Add checksum validation for native artifacts in CI.
- Add license scan exception only if reviewed.
- Add build matrix:
  - default app without Sherpa
  - Sherpa-enabled internal build.
- Add CI artifact-size check so models are not accidentally committed to base APK.
- Add smoke test that loads `SherpaRuntimeAvailability` without crashing.

Verification:

```powershell
npx tsx scripts/license-scan.ts
cd android-relay
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :sherpa-runtime:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

Exit criteria:

- Native provenance is documented.
- CI catches missing or changed native binaries.
- Base beta APK does not accidentally include large STT models.

## Step 10: Physical Proof And Beta Promotion

Context:

Real audio engines are only valuable if real devices prove them.

Required proof runs:

1. Platform STT baseline.
2. Platform STT plus Sherpa VAD diagnostic.
3. Sherpa STT experimental.

Minimum device matrix for promotion:

| Phone | Android | Earbuds | Required for |
| --- | --- | --- | --- |
| Primary dev phone | Current stable Android | Owned daily earbuds | First integration proof |
| Pixel class | Current stable Android | Generic Bluetooth headset | Route sanity |
| Samsung class | Current stable Android | Galaxy Buds or generic headset | OEM route variation |

Proof commands:

- 20 tap-to-command sessions.
- 5 tap-during-TTS interruption tests.
- 5 VAD no-speech tests.
- 5 noisy speech tests.
- 3 disconnect/reconnect tests.
- 3 app process-death recovery tests.
- 1 diagnostic export privacy review.

Promotion decisions:

| State | Condition |
| --- | --- |
| Hidden | Runtime missing, model missing, failing proof, or unstable. |
| Developer diagnostic | VAD works but not enough proof for users. |
| Beta diagnostic | VAD improves route/no-signal proof and has no crash risk. |
| Experimental STT | Sherpa STT passes one device proof and clear fallback exists. |
| Production STT option | Sherpa STT passes matrix proof and beats or complements platform STT. |

Exit criteria:

- Real proof artifacts under `artifacts/proof-runs/`.
- `docs/release-matrix.md` updated with Sherpa-specific rows.
- Diagnostics show model/runtime status and no raw audio.
- Product wording is truthful.

## Dependency Graph

```text
Step 0
  -> Step 1
      -> Step 2
          -> Step 3
              -> Step 4
                  -> Step 5
              -> Step 6
                  -> Step 7
Step 8 depends on Steps 2, 4, and 6
Step 9 depends on Step 1
Step 10 depends on Steps 4, 5, 6, 7, 8, and 9
```

Parallel work:

- Step 2 model catalog can run while Step 1 native module is being assembled.
- Step 8 UX flags can start after model/status enums are stable.
- Step 9 provenance can start as soon as the Sherpa version is chosen.
- Step 7 benchmark DTOs can start before Sherpa STT is fully real.

## Anti-Patterns To Block

- Committing large STT model files directly into the base app.
- Making Sherpa STT the default before proof.
- Running VAD and platform STT at the same time.
- Treating VAD speech detection as transcript confidence.
- Treating no VAD speech as user silence when the route may be broken.
- Swallowing `UnsatisfiedLinkError` without diagnostics.
- Letting model checksum mismatch fall back silently.
- Exporting audio samples for debugging by default.
- Shipping arm64-only runtime without a clear unsupported-ABI message.
- Mixing vendor-earbud support claims with Sherpa audio-engine claims.

## Definition Of Perfect Done

The implementation is complete when:

- `SherpaVadProbe` uses real Silero VAD through Sherpa-ONNX.
- `SherpaSpeechInputEngine` uses real Sherpa streaming STT.
- Native library load status is deterministic and diagnostic.
- VAD and STT model lifecycles are separate and checksum-verified.
- Mic ownership prevents capture conflicts.
- No raw audio is stored by default.
- Platform STT remains available as fallback.
- Diagnostics explain native, model, route, VAD, STT, and fallback failures.
- CI validates native provenance and builds both default and Sherpa-enabled paths.
- Physical proof artifacts show whether Sherpa improves reliability.
- Product UI only exposes Sherpa at the level justified by proof.

## First PR Slice

Start here:

1. Add `:sherpa-runtime` empty module with runtime availability class.
2. Add app-side feature flags, all off by default.
3. Split VAD and STT model specs.
4. Add tests proving VAD readiness is independent from STT readiness.
5. Add `docs/sherpa-onnx-provenance.md` skeleton.

Validation:

```powershell
cd android-relay
.\gradlew.bat :sherpa-runtime:assembleDebug
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.OfflineSpeechEvaluationTest"
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.SherpaModelManagerTest"
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
```

This first slice should not change user-facing beta behavior. It only creates the safe runway for real Silero and Sherpa work.
