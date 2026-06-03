# Voice Pipeline Ticket Queue

Generated: 2026-05-18

Purpose: turn [docs/22-voice-audio-pipeline-external-source-blueprint.md](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/docs/22-voice-audio-pipeline-external-source-blueprint.md) into a queue of small tickets that a weaker local coding model can complete reliably.

## Queue Rules

Each ticket must satisfy all of the following:

- One narrow slice with a single dominant behavior change.
- At most 4 anchor files, plus 1 adjacent file only if compilation forces it.
- One explicit excluded-scope list.
- One validation command that can falsify the ticket quickly.
- One acceptance checklist written as concrete pass/fail statements.
- No Sherpa, JNI, Gradle-module, or UI work unless the ticket explicitly asks for it.

Execution order matters:

1. Finish production-baseline proof-matrix behavior first.
2. Harden exports and privacy after the proof data is stable.
3. Persist tunables only after the request path exists.
4. Delay Sherpa and offline scaffolding until the baseline path is measurable.

## Best First Benchmark Ticket

Use `VAP-01` as the first queue benchmark for Hermes.

Reason:

- It is pure Kotlin.
- It stays inside existing proof-summary logic.
- It has a nearby focused test.
- It is small enough to strongly evaluate whether the local model can finish a ticket cleanly.

Use `VAP-05` only after the queue loop proves it can complete `VAP-01`, because `VAP-05` crosses request contracts, Android wrapper code, and tests.

## Ticket Queue

### VAP-01: Flag missed barge-in target as a proof-run failure reason

Why bounded:
Pure summary logic over existing interruption metrics with nearby tests already in place.

Anchor files:

- [android-relay/app/src/main/java/com/openclaw/relay/VoiceProofRun.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/main/java/com/openclaw/relay/VoiceProofRun.kt)
- [android-relay/app/src/main/java/com/openclaw/relay/TtsInterruptionMetrics.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/main/java/com/openclaw/relay/TtsInterruptionMetrics.kt)
- [android-relay/app/src/test/java/com/openclaw/relay/TtsInterruptionMetricsTest.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/test/java/com/openclaw/relay/TtsInterruptionMetricsTest.kt)

Excluded scope:

- No TTS engine behavior changes
- No RelayService timing changes
- No UI changes

Acceptance checks:

- `VoiceProofRunSummary.failureReasons` includes `interruption_target_missed` when any recorded interruption misses the 250 ms target.
- The failure flag is absent when all interruptions meet the target.
- `interruptionTargetMetCount` keeps its current counting behavior.

Validation:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.TtsInterruptionMetricsTest"
```

### VAP-02: Export interruption target count in redacted diagnostics

Why bounded:
One export DTO field and one export builder path using already-computed proof data.

Anchor files:

- [android-relay/app/src/main/java/com/openclaw/relay/VoiceProofRun.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/main/java/com/openclaw/relay/VoiceProofRun.kt)
- [android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt)

Excluded scope:

- No share-intent changes
- No consent-toggle changes
- No transcript export changes

Acceptance checks:

- `RedactedVoiceProofRun` includes `interruptionTargetMetCount`.
- Proof-run export remains omitted when run status is `NOT_STARTED`.
- Default export remains transcript-free and raw-audio-free.

Validation:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.diagnostic.DiagnosticExportTest"
```

### VAP-03: Add rmsFramesAboveNoiseFloor to platform VAD observation

Why bounded:
Pure telemetry derivation inside the existing speech metrics layer.

Anchor files:

- [android-relay/app/src/main/java/com/openclaw/relay/VoiceTelemetry.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/main/java/com/openclaw/relay/VoiceTelemetry.kt)
- [android-relay/app/src/test/java/com/openclaw/relay/SpeechSessionMetricsTest.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/test/java/com/openclaw/relay/SpeechSessionMetricsTest.kt)

Excluded scope:

- No AudioRecord changes
- No UI surfacing
- No configurable thresholds

Acceptance checks:

- `PlatformVadObservation` exposes `rmsFramesAboveNoiseFloor`.
- The value is derived from existing RMS callbacks through one fixed private threshold.
- Existing wrong-mic suspicion behavior still passes its tests.

Validation:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.SpeechSessionMetricsTest"
```

### VAP-04: Treat low-signal or read-failed audio probes as proof-run failures

Why bounded:
Only changes proof-summary classification over fields the probe already records.

Anchor files:

- [android-relay/app/src/main/java/com/openclaw/relay/VoiceProofRun.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/main/java/com/openclaw/relay/VoiceProofRun.kt)
- [android-relay/app/src/main/java/com/openclaw/relay/AudioProbeMetrics.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/main/java/com/openclaw/relay/AudioProbeMetrics.kt)
- [android-relay/app/src/test/java/com/openclaw/relay/VoiceProofRunTest.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/test/java/com/openclaw/relay/VoiceProofRunTest.kt)

Excluded scope:

- No `AudioRecordRouteProbe` capture behavior changes
- No raw-audio persistence
- No route-policy changes

Acceptance checks:

- A started probe with `nonZeroFrameRatio == 0` adds `audio_probe_no_signal`.
- A started probe with `readErrorCount > 0` adds `audio_probe_read_failed`.
- A healthy started probe adds neither failure flag.

Validation:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.VoiceProofRunTest"
```

### VAP-05: Add possibleCompleteSilenceMs to the platform speech request path

Why bounded:
One request field plus straight-through plumbing into the existing Android recognizer wrapper.

Anchor files:

- [android-relay/app/src/main/java/com/openclaw/relay/SpeechEngineContracts.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/main/java/com/openclaw/relay/SpeechEngineContracts.kt)
- [android-relay/app/src/main/java/com/openclaw/relay/PlatformSpeechRecognizerEngine.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/main/java/com/openclaw/relay/PlatformSpeechRecognizerEngine.kt)
- [android-relay/app/src/main/java/com/openclaw/relay/AndroidSpeechRecognizer.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/main/java/com/openclaw/relay/AndroidSpeechRecognizer.kt)
- [android-relay/app/src/test/java/com/openclaw/relay/SpeechEngineContractsTest.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/test/java/com/openclaw/relay/SpeechEngineContractsTest.kt)

Excluded scope:

- No `RelayConfig` persistence
- No Compose settings UI
- No Sherpa endpoint tuning

Acceptance checks:

- `SpeechSessionRequest` gains `possibleCompleteSilenceMs` with an experimental default.
- `PlatformSpeechRecognizerEngine` passes the field through unchanged.
- `AndroidSpeechRecognizer` sets `RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS`.
- Existing `completeSilenceMs` and `minimumLengthMs` defaults remain unchanged.

Validation:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.SpeechEngineContractsTest"
```

### VAP-06: Add contract tests for diagnostic privacy toggles and raw-route opt-in

Why bounded:
Test-first hardening of an existing export surface.

Anchor files:

- [android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt)

Excluded scope:

- No share-sheet changes
- No UI toggle changes
- No new redaction categories

Acceptance checks:

- `selectedDeviceType` is only exported when raw-route export is enabled.
- Recent errors stay redacted.
- Transcript text never appears by default.
- Raw audio remains excluded.

Validation:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.diagnostic.DiagnosticExportTest"
```

### VAP-07: Persist endpoint-hint tuning only after request plumbing is stable

Why bounded:
Just config-model and storage round-trip after `VAP-05` exists.

Anchor files:

- [android-relay/app/src/main/java/com/openclaw/relay/RelayModels.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/main/java/com/openclaw/relay/RelayModels.kt)
- [android-relay/app/src/main/java/com/openclaw/relay/RelayConfigStorage.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/main/java/com/openclaw/relay/RelayConfigStorage.kt)
- [android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt)

Excluded scope:

- No settings UI
- No device-specific presets
- No Sherpa configuration

Acceptance checks:

- `RelayConfig` can carry `completeSilenceMs`, `possibleCompleteSilenceMs`, and `minimumLengthMs`.
- Save/load preserves the values.
- Users who never set them see unchanged behavior.

Validation:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.SpeechEngineContractsTest"
```

### VAP-08: Factory-select the Sherpa placeholder engine only when offline readiness allows it

Why bounded:
Engine-selection logic only; placeholder offline engine already exists.

Anchor files:

- [android-relay/app/src/main/java/com/openclaw/relay/OfflineSpeechEvaluation.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/main/java/com/openclaw/relay/OfflineSpeechEvaluation.kt)
- [android-relay/app/src/main/java/com/openclaw/relay/SherpaEvaluationEngines.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/main/java/com/openclaw/relay/SherpaEvaluationEngines.kt)
- [android-relay/app/src/test/java/com/openclaw/relay/OfflineSpeechEvaluationTest.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/test/java/com/openclaw/relay/OfflineSpeechEvaluationTest.kt)

Excluded scope:

- No JNI
- No Gradle module
- No model downloads

Acceptance checks:

- Factory returns `PlatformSpeechRecognizerEngine` for platform modes.
- Factory returns `SherpaSpeechInputEngine` only when requested and readiness allows it.
- Missing native/model state still falls back to platform.

Validation:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.OfflineSpeechEvaluationTest"
```

### VAP-09: Split Sherpa model inventory contracts for VAD and STT artifacts

Why bounded:
File-inventory and checksum logic only, with no runtime engine work.

Anchor files:

- [android-relay/app/src/main/java/com/openclaw/relay/OfflineSpeechEvaluation.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/main/java/com/openclaw/relay/OfflineSpeechEvaluation.kt)
- [android-relay/app/src/test/java/com/openclaw/relay/OfflineSpeechEvaluationTest.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/test/java/com/openclaw/relay/OfflineSpeechEvaluationTest.kt)

Excluded scope:

- No downloading
- No asset packaging
- No UI

Acceptance checks:

- Distinct required-file sets exist for Sherpa VAD and Sherpa STT.
- Missing-file reporting identifies which path is incomplete.
- Checksum behavior remains optional.

Validation:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.OfflineSpeechEvaluationTest"
```

### VAP-10: Persist and export the last offline benchmark summary

Why bounded:
Data-store and export seam around an existing benchmark summarizer.

Anchor files:

- [android-relay/app/src/main/java/com/openclaw/relay/OfflineSpeechBenchmark.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/main/java/com/openclaw/relay/OfflineSpeechBenchmark.kt)
- [android-relay/app/src/main/java/com/openclaw/relay/VoiceTelemetry.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/main/java/com/openclaw/relay/VoiceTelemetry.kt)
- [android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt)

Excluded scope:

- No benchmark-runner UI
- No live capture automation
- No native decoding

Acceptance checks:

- `VoiceDiagnosticsSnapshot` can carry the last offline benchmark summary.
- Diagnostics export includes summary counts and failure reasons when present.
- Export remains transcript-free.

Validation:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.diagnostic.DiagnosticExportTest"
```

### VAP-11: Normalize placeholder Sherpa error taxonomy against readiness failure reasons

Why bounded:
Deterministic placeholder behavior only; no actual decoding.

Anchor files:

- [android-relay/app/src/main/java/com/openclaw/relay/SherpaEvaluationEngines.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/main/java/com/openclaw/relay/SherpaEvaluationEngines.kt)
- [android-relay/app/src/main/java/com/openclaw/relay/OfflineSpeechEvaluation.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/main/java/com/openclaw/relay/OfflineSpeechEvaluation.kt)
- [android-relay/app/src/test/java/com/openclaw/relay/OfflineSpeechEvaluationTest.kt](c:/Users/ShreyasSuvarna/Desktop/its_mine/firmware_earphones/android-relay/app/src/test/java/com/openclaw/relay/OfflineSpeechEvaluationTest.kt)

Excluded scope:

- No JNI adapter
- No real VAD
- No real STT stream handling

Acceptance checks:

- Placeholder Sherpa engines emit stable, mode-specific failure messages.
- Failure reasons map cleanly to readiness failure reasons.
- Tests distinguish missing native dependency from incomplete model state.

Validation:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.openclaw.relay.OfflineSpeechEvaluationTest"
```