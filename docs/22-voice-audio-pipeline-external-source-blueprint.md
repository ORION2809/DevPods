# Voice and Audio Pipeline External Source Blueprint

Generated: 2026-05-14

Scope: DevPods Relay voice loop on Android: STT, endpointing, VAD, Bluetooth microphone routing, TTS, interruption, foreground controls, diagnostics, and the external open-source or licensed code that can make the physical earbud experience production-grade.

## Executive Decision

The best path is not to replace the existing Android `SpeechRecognizer` flow immediately. The best path is:

1. Keep Android `SpeechRecognizer` endpointing as the first production baseline.
2. Add detailed voice, route, endpoint, VAD-like, TTS, and interruption instrumentation around the existing implementation.
3. Add a separate VAD/audio capture probe that never competes with `SpeechRecognizer` in the same session.
4. Evaluate Sherpa-ONNX plus Silero VAD behind a feature flag using the exact same metrics.
5. Promote offline STT only after it beats the platform recognizer on measured latency, endpoint reliability, battery, route correctness, and physical earbud compatibility.

This order protects the product. The user does not care which engine is elegant; the user cares that tapping earbuds, speaking, hearing a response, interrupting, and retrying works every time.

## Current DevPods Baseline

DevPods already has a useful Android voice foundation:

| Area | Current implementation | What it means |
| --- | --- | --- |
| Platform STT | `AndroidSpeechRecognizer.kt` checks availability, creates `SpeechRecognizer`, receives beginning/end/partial/final callbacks, and sets partial results plus a 750 ms complete-silence hint. See `android-relay/app/src/main/java/com/openclaw/relay/AndroidSpeechRecognizer.kt:67`, `:85`, `:91`, `:93`, `:97`, `:108`, `:119`, `:135`, `:136`, `:137`. | Good baseline, but the callback timings are currently not turned into a proof matrix. |
| Bluetooth route | `BluetoothAudioRouter.kt` sets `MODE_IN_COMMUNICATION`, finds `TYPE_BLUETOOTH_SCO` or `TYPE_BLE_HEADSET`, calls `setCommunicationDevice`, snapshots route state, and clears the selected route. See `android-relay/app/src/main/java/com/openclaw/relay/BluetoothAudioRouter.kt:19`, `:20`, `:23`, `:24`, `:37`, `:46`, `:59`. | Correct modern Android routing direction. Needs route-settle timing and physical mic proof. |
| TTS | `AndroidTtsSpeaker.kt` initializes `TextToSpeech`, uses `UtteranceProgressListener`, calls `speak(..., QUEUE_FLUSH, ...)`, and exposes `stop()`. See `android-relay/app/src/main/java/com/openclaw/relay/AndroidTtsSpeaker.kt:26`, `:43`, `:44`, `:48`, `:83`, `:86`. | Good starting point for response and barge-in. Needs audio-focus and interruption latency metrics. |
| Relay lifecycle | `RelayService.kt` starts foreground service, begins listening sessions, starts/stops STT, stops TTS, and exposes retry/cancel/stop notification actions. See `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:137`, `:405`, `:436`, `:463`, `:515`, `:516`, `:593`, `:884`, `:935`, `:940`, `:945`. | The foreground/control backbone exists. Voice state proof should plug into this, not bypass it. |
| UI/state | `RelayModels.kt` and `RelayStateStore.kt` already track STT availability, TTS readiness, audio route, final transcript, partial transcript, speech start, speech errors, and TTS errors. See `android-relay/app/src/main/java/com/openclaw/relay/RelayModels.kt:72`, `:73`, `:74`, `:75`, `:76`; `android-relay/app/src/main/java/com/openclaw/relay/RelayStateStore.kt:156`, `:160`, `:164`, `:167`, `:171`, `:237`, `:256`, `:268`. | The product model can absorb richer telemetry without redesigning everything. |

## Platform Facts That Shape The Design

Android's `SpeechRecognizer` is a service API. Official docs say it must be created through factory methods and its methods must run on the main app thread. They also say callers must wait for `onResults` or `onError` before starting another session. Source: [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer).

The platform endpointing extras are only hints. `RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` can influence how long the recognizer waits after speech stops, but Android warns that these values may have no effect depending on the recognizer implementation. Source: [Android RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent).

For Bluetooth communications routing, modern Android wants `AudioManager.setCommunicationDevice()` with devices returned by `getAvailableCommunicationDevices()`. Android also notes that a sink output device is selected and the matching source device is selected by the platform. Source: [Android AudioManager](https://developer.android.com/reference/android/media/AudioManager).

For headset and foreground reliability, media-button intents should be routed into a service/media-session callback path. Android's legacy media-button guide shows `MediaButtonReceiver.handleIntent()` translating key codes into session callbacks, and Media3 documents notification/media-session behavior for background services. Sources: [Android media buttons](https://developer.android.com/media/legacy/media-buttons), [Media3 background playback](https://developer.android.com/media/media3/session/background-playback).

## Target Architecture

Create explicit interfaces so platform STT, offline STT, VAD, TTS, and routing can be swapped without touching the bridge or policy layers.

```kotlin
interface SpeechInputEngine {
    val id: String
    fun capabilities(): SpeechEngineCapabilities
    suspend fun start(request: SpeechSessionRequest, callbacks: SpeechCallbacks)
    suspend fun stop(reason: SpeechStopReason)
    fun destroy()
}

interface VadProbe {
    val id: String
    suspend fun start(request: VadProbeRequest, callbacks: VadCallbacks)
    suspend fun stop()
}

interface SpeechOutputEngine {
    val id: String
    suspend fun speak(request: TtsRequest, callbacks: TtsCallbacks)
    suspend fun stop(reason: TtsStopReason)
}
```

The first concrete engines should be:

| Engine | Role | Default? |
| --- | --- | --- |
| `PlatformSpeechRecognizerEngine` | Wrap existing `AndroidSpeechRecognizer` and Android endpointing. | Yes |
| `PlatformCallbackVadProbe` | Derive VAD-like signals from `onRmsChanged`, `onBeginningOfSpeech`, `onEndOfSpeech`, partials, finals, and errors. | Yes |
| `AudioRecordRouteProbe` | Separate diagnostic capture path for amplitude and route proof. No raw audio persistence by default. | Dev/diagnostic only |
| `SherpaVadProbe` | Sherpa-ONNX Silero VAD evaluation. | Feature flag |
| `SherpaSpeechInputEngine` | Offline STT evaluation with Sherpa-ONNX. | Feature flag |
| `AndroidTtsOutputEngine` | Wrap existing `AndroidTtsSpeaker`. | Yes |

## Phase 1: Instrument Android SpeechRecognizer Endpointing First

This phase should be implemented before any offline engine work lands in production.

### What To Build

Add `SpeechSessionMetrics` and emit it from every listening session:

| Field | Purpose |
| --- | --- |
| `sessionId` | Correlates wake signal, route, STT, bridge request, TTS, interruption, diagnostics. |
| `engineId` | `platform_speech_recognizer`, `platform_on_device_speech_recognizer`, `sherpa_streaming`, etc. |
| `wakeSignal` | Earbud/media-button/wake test source. |
| `routeRequestedAtMs` | Time route selection started. |
| `routeReadyAtMs` | Time `setCommunicationDevice` succeeded or failed. |
| `routeSnapshot` | Bluetooth type, selected route, available communication devices, hashed device identity. |
| `recognizerCreatedAtMs` | Time recognizer instance was available. |
| `listeningStartedAtMs` | Time `startListening` was called. |
| `readyForSpeechAtMs` | `onReadyForSpeech` callback. |
| `beginSpeechAtMs` | `onBeginningOfSpeech` callback. |
| `firstRmsAtMs` | First `onRmsChanged` callback. |
| `rmsFrameCount` | Count of RMS frames. |
| `rmsPeakDb` | Peak callback value, useful for no-input and wrong-mic detection. |
| `endSpeechAtMs` | `onEndOfSpeech` callback. |
| `firstPartialAtMs` | First non-empty partial. |
| `partialCount` | Number of partial result callbacks. |
| `finalAtMs` | Final result callback. |
| `errorAtMs` | Error callback time. |
| `errorCode` | Android recognizer error code. |
| `endpointReason` | final, no-speech, timeout, stop-requested, cancel, recognizer-busy, route-failed. |
| `ttsStartAtMs` | TTS utterance start. |
| `ttsDoneAtMs` | TTS utterance completion. |
| `interruptedAtMs` | User barge-in or stop action time. |
| `privacyLevel` | Whether transcript/audio details are redacted, summarized, or included with consent. |

### Endpointing Defaults

Keep the current 750 ms complete-silence hint as the baseline, but make it configurable:

| Setting | Initial value | Reason |
| --- | --- | --- |
| `completeSilenceMs` | `750` | Current code already uses it. |
| `possibleCompleteSilenceMs` | `500` to test only | Good experiment, but Android may ignore it. |
| `minimumLengthMs` | `300` to test only | Avoids accidental tiny sessions, but can delay fast commands. |
| `preferOffline` | `false` by default | Current code uses cloud-capable recognizer. Do not silently change behavior. |
| `onDeviceOnly` | opt-in | Use `SpeechRecognizer.isOnDeviceRecognitionAvailable()` and `createOnDeviceSpeechRecognizer()` only after measuring availability and quality. |

### Acceptance Criteria

Phase 1 is complete when a diagnostics run can prove:

| Test | Passing threshold |
| --- | --- |
| 20 tap-to-speak sessions | No stuck recognizer; no second `startListening` before final/error. |
| Route selection | `routeReadyAtMs - routeRequestedAtMs` captured for every session. |
| Speech callbacks | Ready, begin, RMS, end, partial, final, and error callbacks are counted and timestamped. |
| Wrong-mic suspicion | Low RMS or no begin-speech after successful Bluetooth route is flagged. |
| Stop/interruption | Stop action cancels listening and TTS within 250 ms target on measured devices. |
| Privacy | No raw audio is stored; transcripts are opt-in for exported diagnostics. |

## Phase 2: Add VAD Instrumentation Without Taking Over The Mic

The first VAD should not be a new audio engine. It should be an instrumentation layer over Android callbacks:

| Signal | VAD-like interpretation |
| --- | --- |
| `onReadyForSpeech` | Recognizer is ready; endpoint window opened. |
| `onRmsChanged` | Input energy available; useful for wrong-mic and silence suspicion. |
| `onBeginningOfSpeech` | Platform detected speech start. |
| `onPartialResults` | Decoder is producing live text. |
| `onEndOfSpeech` | Platform detected speech end. |
| `onResults` | Final endpoint. |
| `onError` | Endpoint failed, timed out, or recognizer rejected state. |

Add `PlatformVadObservation`:

| Field | Purpose |
| --- | --- |
| `speechDetected` | True if begin-speech or non-empty partial occurred. |
| `rmsPeakDb` | Peak input level. |
| `rmsFramesAboveNoiseFloor` | Helps separate silence from wrong route. |
| `speechStartDelayMs` | `beginSpeechAtMs - readyForSpeechAtMs`. |
| `partialDelayMs` | `firstPartialAtMs - beginSpeechAtMs`. |
| `speechEndDelayMs` | `endSpeechAtMs - beginSpeechAtMs`. |
| `finalizationDelayMs` | `finalAtMs - endSpeechAtMs`. |
| `endpointResult` | final, no-speech, timeout, busy, network, audio, permissions. |

Do not run `AudioRecord` at the same time as `SpeechRecognizer`. Both can compete for microphone ownership. If audio capture is needed, run a separate diagnostic mode called `AudioRecordRouteProbe`.

## Phase 3: Build AudioRecordRouteProbe As A Diagnostic Tool

This probe answers one question: "Is the app actually receiving usable microphone energy from the route we think it selected?"

### Probe Behavior

| Property | Value |
| --- | --- |
| Audio source candidates | `MediaRecorder.AudioSource.VOICE_RECOGNITION`, then `MIC` as fallback experiment. |
| Sample rate | 16000 Hz preferred. |
| Channels | Mono. |
| Format | Float or 16-bit PCM, depending on API and downstream engine. |
| Window | 512 samples for Silero-compatible probes. |
| Storage | No raw audio by default. Store amplitude summaries only. |
| Runtime | Short diagnostic bursts, not always-on background capture. |

### Metrics

| Metric | Why |
| --- | --- |
| `audioRecordInitStatus` | Detects mic busy, denied, unsupported format. |
| `readErrorCount` | Captures device/route instability. |
| `nonZeroFrameRatio` | Detects dead input. |
| `peakAmplitude` | Detects route and gain issues. |
| `noiseFloorEstimate` | Helps tune VAD thresholds. |
| `bluetoothRouteAtCapture` | Correlates with `AudioManager` route. |

## Phase 4: Evaluate Sherpa-ONNX And Silero VAD Behind A Flag

Sherpa-ONNX is the best offline candidate because it already provides Android examples, Kotlin APIs, VAD, streaming ASR, offline ASR, Android AAR structure, model choices, and cross-platform deployment patterns. Source: [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).

### Sherpa Code To Consume

| Source | Relevant code | How to use it |
| --- | --- | --- |
| Streaming ASR sample | Reads 100 ms buffers, feeds `acceptWaveform`, loops `isReady/decode`, checks `isEndpoint`, gets result, resets stream. See `external/audio-pipeline-references/sherpa-onnx/android/SherpaOnnx/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt:118`, `:123`, `:126`, `:127`, `:131`, `:132`, `:156`, `:157`. | Use as the baseline for `SherpaSpeechInputEngine`. |
| AudioRecord setup | Uses `AudioRecord.getMinBufferSize` and constructs `AudioRecord`. See `external/audio-pipeline-references/sherpa-onnx/android/SherpaOnnx/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt:182`, `:187`. | Reuse the structure in a DevPods-owned capture class with route metrics. |
| Endpoint config | `OnlineRecognizerConfig` supports `endpointConfig` and `enableEndpoint`. See `external/audio-pipeline-references/sherpa-onnx/android/SherpaOnnx/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt:223`, `:224`; `external/audio-pipeline-references/sherpa-onnx/sherpa-onnx/kotlin-api/OnlineRecognizer.kt:11`, `:65`, `:71`, `:72`. | Tune endpointing independently from Android recognizer hints. |
| VAD sample | Creates `Vad`, reads 512-sample buffers, calls `acceptWaveform`, reads `isSpeechDetected`, then clears. See `external/audio-pipeline-references/sherpa-onnx/android/SherpaOnnxVad/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt:123`, `:125`, `:159`, `:162`, `:166`, `:168`, `:169`. | Use first for VAD-only comparison against platform callback VAD. |
| Simulated streaming VAD plus ASR | Feeds VAD, detects speech start, keeps 0.4 s pre-roll, runs ASR periodically, consumes complete VAD segments. See `external/audio-pipeline-references/sherpa-onnx/android/SherpaOnnxSimulateStreamingAsr/app/src/main/java/com/k2fsa/sherpa/onnx/simulate/streaming/asr/screens/Home.kt:157`, `:164`, `:167`, `:181`; `external/audio-pipeline-references/sherpa-onnx/android/SherpaOnnxSimulateStreamingAsr/app/src/main/java/com/k2fsa/sherpa/onnx/simulate/streaming/asr/SimulateStreamingAsr.kt:107`, `:133`, `:137`, `:138`, `:248`, `:254`. | This is the best template for the future offline command engine. |
| Kotlin VAD API | Silero config defaults use threshold 0.5, silence 0.25 s, speech 0.25 s, window 512; API exposes `acceptWaveform`, `empty`, `front`, `isSpeechDetected`, `reset`, and `flush`. See `external/audio-pipeline-references/sherpa-onnx/sherpa-onnx/kotlin-api/Vad.kt:6`, `:8`, `:9`, `:10`, `:11`, `:61`, `:63`, `:66`, `:72`, `:74`, `:76`, `:102`, `:116`, `:122`, `:123`, `:124`, `:125`. | Prefer this over manually wiring ONNX Runtime for Silero in the first Android integration. |
| Kotlin recognizer APIs | Online recognizer exposes `createStream`, `reset`, `decode`, `isEndpoint`, `isReady`, and `getResult`; offline recognizer exposes stream, decode, result, and config mutation. See `external/audio-pipeline-references/sherpa-onnx/sherpa-onnx/kotlin-api/OnlineRecognizer.kt:113`, `:118`, `:119`, `:120`, `:121`, `:122`; `external/audio-pipeline-references/sherpa-onnx/sherpa-onnx/kotlin-api/OfflineRecognizer.kt:202`, `:212`, `:216`, `:218`. | Wrap these behind `SpeechInputEngine`. |
| AAR structure | Android library namespace, compile SDK, min SDK. See `external/audio-pipeline-references/sherpa-onnx/android/SherpaOnnxAar/sherpa_onnx/build.gradle.kts:7`, `:8`, `:11`. | Use as packaging reference, but keep DevPods build ownership. |

### Sherpa Evaluation Matrix

| Dimension | Required metric |
| --- | --- |
| Cold load | Time from app start to model ready. |
| Warm start | Time from wake to first audio accepted. |
| First partial | Wake to partial text. |
| Final endpoint | End of speech to final transcript. |
| Accuracy | Command success rate across DevPods intent phrases. |
| Wrong-route detection | Whether VAD/RMS catches phone mic vs earbud mic mismatch. |
| CPU | Average and peak CPU during 20 sessions. |
| Memory | Native heap and Java/Kotlin heap impact. |
| Battery | 10-minute active relay drain estimate. |
| APK/model size | Base APK delta and downloaded model footprint. |
| Crash isolation | JNI crash behavior, restart path, feature flag rollback. |

### Promotion Criteria

Do not make Sherpa default until it passes:

| Requirement | Threshold |
| --- | --- |
| Command latency | Median final transcript under 900 ms after speech end for short commands. |
| Reliability | 95 percent successful tap-to-command sessions on test matrix. |
| Barge-in | TTS stop and new listening start under 250 ms measured. |
| Battery | No unacceptable thermal or battery regression vs platform recognizer. |
| Recovery | Engine can unload/reload without killing RelayService. |
| Supportability | Diagnostics identify model version, engine, VAD, route, and failure class. |

## Phase 5: Silero VAD Path

Silero VAD is the right conceptual VAD model, but the first Android integration should consume it through Sherpa unless there is a measured reason not to.

### Evidence

Silero's repository documents ONNX Runtime usage, 30 ms chunk processing under 1 ms on a single CPU thread, 8 kHz and 16 kHz sampling, and an MIT license. See `external/audio-pipeline-references/silero-vad/README.md:47`, `:103`, `:115`, `:123`; source: [snakers4/silero-vad](https://github.com/snakers4/silero-vad).

The Python utility defaults are a useful threshold reference: threshold 0.5, 16 kHz, min speech 250 ms, min silence 100 ms, and 512-sample windows. See `external/audio-pipeline-references/silero-vad/src/silero_vad/utils_vad.py:212`, `:214`, `:215`, `:216`, `:218`, `:225`, `:309`, `:312`, `:458`, `:461`, `:462`, `:463`, `:492`, `:535`, `:547`.

### Recommended DevPods Use

| Use | Recommendation |
| --- | --- |
| Production VAD candidate | Use Sherpa `Vad` wrapping Silero first. |
| Hand-written ONNX Runtime VAD | Defer until Sherpa VAD cannot meet size or latency needs. |
| VAD threshold tuning | Start with 0.5, then tune per route/noise matrix. |
| Supported sample rate | Normalize to 16 kHz mono. |
| Raw audio logs | Never store by default. Export only short user-approved clips if a future support flow explicitly adds that consent. |

## Other External Sources And How To Consume Them

### speech-android

Source: [soniqo/speech-android](https://github.com/soniqo/speech-android). Local clone: `external/audio-pipeline-references/speech-android` at `6b0c70b`.

This is the most useful full-stack external architecture reference after Sherpa. It packages an Android SDK around ONNX Runtime and `speech-core`, covering STT, VAD, TTS, and noise cancellation. It is not the first DevPods production engine because its default model footprint is large, but it is excellent reference code for model management, system `RecognitionService`, JNI boundary, barge-in, latency display, and complete speech-pipeline lifecycle.

| Source | Relevant code | How to use it |
| --- | --- | --- |
| README model map | Parakeet STT 891 MB, Kokoro TTS 330 MB, Silero VAD 2 MB, DeepFilterNet3 around 8 MB. See `external/audio-pipeline-references/speech-android/README.md:19`, `:20`, `:21`, `:22`. | Use for model footprint realism and why DevPods should not bundle large offline models in base APK. |
| Model manager | Reliable download/resume and model file inventory. See `external/audio-pipeline-references/speech-android/sdk/src/main/kotlin/com/soniqo/speech/ModelManager.kt:18`, `:37`, `:41`, `:43`, `:47`, `:59`, `:111`, `:129`, `:180`, `:219`, `:246`, `:249`. | Reuse design for model download manager, checksum/version state, resume, and cache invalidation. |
| Kotlin pipeline | `SpeechPipeline` exposes `pushAudio`, native creation, native stop, native push. See `external/audio-pipeline-references/speech-android/sdk/src/main/kotlin/com/soniqo/speech/SpeechPipeline.kt:44`, `:54`, `:86`, `:111`, `:114`, `:115`. | Strong reference for a clean Android-to-native boundary. |
| Config | Model dir, NNAPI, enhancer, precision, partial transcription interval. See `external/audio-pipeline-references/speech-android/sdk/src/main/kotlin/com/soniqo/speech/SpeechConfig.kt:5`, `:7`, `:10`, `:13`, `:16`, `:19`, `:22`. | Copy the config shape concept, but adapt names to DevPods. |
| RecognitionService | Claims session synchronously, rejects busy sessions, handles partials/finals, pushes silence on stop, uses `AudioRecord` at 16 kHz. See `external/audio-pipeline-references/speech-android/sdk/src/main/kotlin/com/soniqo/speech/service/SpeechRecognitionService.kt:60`, `:67`, `:82`, `:89`, `:94`, `:152`, `:155`, `:180`, `:194`, `:210`, `:216`, `:219`, `:304`, `:306`, `:310`, `:311`, `:314`. | Useful if DevPods later exposes its own recognizer service or wants robust busy-state handling. |
| Barge-in | README says speaking during TTS interrupts and starts new transcription. See `external/audio-pipeline-references/speech-android/README.md:168`. | Use as behavior target and test case, not necessarily direct code. |

### Vosk

Source: [alphacep/vosk-api](https://github.com/alphacep/vosk-api). Local clone: `external/audio-pipeline-references/vosk-api` at `9adbd76`.

Vosk is a useful fallback/reference, especially for `AudioRecord` ownership, timeout handling, partial/final result shape, and mic-in-use failure handling. It is not the top DevPods choice because Sherpa has broader modern Android sample coverage and VAD/ASR integration for this blueprint.

| Source | Relevant code | How to use it |
| --- | --- | --- |
| Android speech service | Uses `AudioSource.VOICE_RECOGNITION`, checks `STATE_UNINITIALIZED`, starts recognition thread, reads audio, emits partial/final. See `external/audio-pipeline-references/vosk-api/kotlin/src/androidMain/kotlin/org/vosk/android/SpeechService.kt:53`, `:54`, `:58`, `:71`, `:87`, `:193`, `:217`, `:218`, `:230`, `:231`. | Reference for `AudioRecordRouteProbe` and offline engine error taxonomy. |
| Recognizer endpointing | `acceptWaveForm` returns true when silence creates a new utterance; endpointer delays are configurable. See `external/audio-pipeline-references/vosk-api/android/lib/src/main/java/org/vosk/Recognizer.java:157`, `:161`, `:165`, `:169`, `:259`, `:260`, `:261`, `:263`. | Good comparison point for offline endpoint APIs. |

### whisper.cpp

Source: [ggml-org/whisper.cpp](https://github.com/ggml-org/whisper.cpp). Local clone: `external/audio-pipeline-references/whisper.cpp` at `3e9b7d0`.

Whisper.cpp is valuable as a benchmark and JNI/native Android reference, not as the default low-latency command engine. It can be excellent for longer dictation or later offline fallback, but command/control needs faster endpointing and smaller footprint first.

| Source | Relevant code | How to use it |
| --- | --- | --- |
| Android JNI build | Uses external native build with CMake. See `external/audio-pipeline-references/whisper.cpp/examples/whisper.android.java/app/build.gradle:17`, `:34`, `:36`. | Reference if DevPods adds a native benchmark module. |
| Java context | Initializes from input stream/assets, transcribes, reads text segments, exposes benchmark methods, releases context. See `external/audio-pipeline-references/whisper.cpp/examples/whisper.android.java/app/src/main/java/com/whispercpp/java/whisper/WhisperContext.java:44`, `:45`, `:47`, `:70`, `:71`, `:73`, `:74`, `:75`, `:89`, `:93`, `:98`, `:118`, `:127`. | Use for benchmarking native inference and JNI lifecycle. |
| VAD segment example | Provides VAD model, threshold 0.50, min speech 250 ms, min silence 100 ms, speech pad 30 ms; initializes VAD and detects segments. See `external/audio-pipeline-references/whisper.cpp/examples/vad-speech-segments/README.md:18`, `:45`, `:46`, `:47`, `:49`; `external/audio-pipeline-references/whisper.cpp/examples/vad-speech-segments/speech.cpp:111`, `:120`. | Useful benchmark for segmentation behavior, not first integration. |

### Picovoice Cobra

Source: [Picovoice/cobra](https://github.com/Picovoice/cobra). Local clone: `external/audio-pipeline-references/picovoice-cobra` at `b419f01`.

Cobra is a licensed/commercial-quality VAD candidate. It is useful if DevPods wants a supported VAD with simpler Android packaging, but it requires an access key and introduces vendor dependency. It should be benchmarked only after the open Sherpa/Silero path is measured.

| Source | Relevant code | How to use it |
| --- | --- | --- |
| Android package | Available from Maven Central and Android SDK 21+. See `external/audio-pipeline-references/picovoice-cobra/binding/android/README.md:9`, `:13`. | Easy benchmark dependency. |
| Credentials | Requires `AccessKey`; docs say keep it secret. See `external/audio-pipeline-references/picovoice-cobra/binding/android/README.md:23`, `:25`, `:26`, `:27`. | Must never hardcode. Use secure config if evaluated. |
| VAD call | `Cobra(accessKey)` plus `process(getNextAudioFrame())` returns voice probability. See `external/audio-pipeline-references/picovoice-cobra/binding/android/README.md:45`, `:47`, `:65`. | Simple benchmark path for VAD probability quality. |
| Audio format | Cobra expects single-channel 16-bit PCM; sample rate/frame length are available from SDK APIs. See `external/audio-pipeline-references/picovoice-cobra/README.md:250`, `:251`, `:261`. | Aligns with the probe engine. |

### RNNoise

Source: [xiph/rnnoise](https://github.com/xiph/rnnoise). Local clone: `external/audio-pipeline-references/rnnoise` at `70f1d25`.

RNNoise is not STT and not primary VAD. It is an optional noise suppression stage after the route/STT loop is stable. Add it only if noisy-earbud measurements prove recognition is failing due to noise.

| Source | Relevant code | How to use it |
| --- | --- | --- |
| Library purpose | README describes RNNoise as RNN-based noise suppression. See `external/audio-pipeline-references/rnnoise/README:1`. | Optional preprocessor for harsh environments. |
| Frame API | Frame size and `rnnoise_process_frame`. See `external/audio-pipeline-references/rnnoise/include/rnnoise.h:60`, `:62`, `:90`, `:92`, `:94`; demo uses `FRAME_SIZE 480` and `rnnoise_process_frame`. See `external/audio-pipeline-references/rnnoise/examples/rnnoise_demo.c:31`, `:57`. | Benchmark only after raw route/VAD/STT metrics exist. |

## Bluetooth Mic Routing Blueprint

The route layer should become explicit and measurable.

### Route State Machine

| State | Meaning |
| --- | --- |
| `route_unknown` | No route snapshot yet. |
| `route_phone_mic` | Phone microphone is selected or inferred. |
| `route_bluetooth_requested` | App called `setCommunicationDevice`. |
| `route_bluetooth_active` | Call returned success and route snapshot shows Bluetooth communication device. |
| `route_bluetooth_suspect` | Route says Bluetooth, but RMS/non-zero audio is absent or device disconnect happened. |
| `route_failed` | No compatible Bluetooth route or API rejected route. |
| `route_released` | App cleared route on stop/destroy. |

### Required Changes

| Change | Why |
| --- | --- |
| Add route-settle timing | Some earbuds need a small settle window before STT starts. |
| Store device type and hashed identity | Allows compatibility matrix without leaking user device names. |
| Capture `availableCommunicationDevices` count/types | Helps diagnose phone vs Bluetooth routing. |
| Add fallback policy | If Bluetooth route fails, ask user before falling back to phone mic. |
| Add route proof to diagnostics | Product readiness depends on physical route proof, not just UI state. |

## TTS And Interruption Blueprint

TTS must be treated as part of the command loop, not just output.

### Behavior

| Scenario | Required behavior |
| --- | --- |
| New spoken answer while old answer plays | Use flush behavior, track old utterance as superseded. |
| User taps during TTS | Stop TTS, request route, start listening. |
| User speaks during TTS in offline VAD mode | Stop TTS immediately and open a new recognition session. |
| Bridge returns long response | Keep current ear-safe response trimming; TTS should speak only the optimized response. |
| TTS failure | Surface user-facing fallback and keep bridge/session state recoverable. |

### Metrics

| Metric | Target |
| --- | --- |
| `ttsQueueLatencyMs` | Time from bridge response to `speak`. |
| `ttsStartDelayMs` | Time from `speak` to `onStart`. |
| `ttsDurationMs` | Time from `onStart` to `onDone`. |
| `ttsStopLatencyMs` | Time from stop request to no active utterance. |
| `bargeInLatencyMs` | Time from tap/speech interruption to recognizer start. |

## Android Foreground Service And Media Button Reliability

This should proceed in parallel with Phase 1 instrumentation, because earbud gesture reliability depends on it.

| Component | Required behavior |
| --- | --- |
| Foreground notification | Always expose stop, retry queue, cancel current action, and optional push-to-talk. |
| Media button path | Normalize play/pause, next, previous, stop, and supported headset key events into `RelayWakeSignal`. |
| Service survival | Restart gracefully after process death; restore safe state, not active mic capture. |
| Duplicate events | Debounce accidental double media-button events while preserving intentional double/triple gestures where supported. |
| Diagnostics | Log key code, action mapping, foreground state, route state, and session ID. |

Do not make the media session a music player. Treat it as the Android-compatible control plane for headset/media-button behavior.

## Desktop Bridge Packaging, Update, And Onboarding

This is adjacent, not part of the voice engine. It should be sequenced after voice-loop instrumentation because pairing UX can only be trusted if the phone can prove route/STT readiness.

| Area | Required outcome |
| --- | --- |
| Installer | Desktop bridge starts reliably and exposes pairing QR/link. |
| Auto-update | Bridge protocol version and Android app version are compatibility-checked. |
| Tray app | Shows bridge health, pairing status, last relay connection, and update state. |
| QR pairing UX | Android app should distinguish invalid QR, expired pairing, incompatible bridge, and unreachable bridge. |
| Voice readiness gate | Onboarding should require route and speech proof after bridge pairing. |

## Diagnostics And Support Telemetry

Add a privacy-safe "voice proof matrix" export.

### Default Export

| Field | Include by default? |
| --- | --- |
| App version and bridge protocol version | Yes |
| Android version and device model | User toggle, default on for support export |
| Earbud route type and hashed device name | Yes |
| Exact Bluetooth device name | No |
| Raw audio | No |
| Transcript text | No, opt-in |
| Partial transcript text | No, opt-in |
| Error codes and timing | Yes |
| Engine/model versions | Yes |
| Route/STT/TTS timing summary | Yes |
| Recent bridge errors | Yes, redacted |

### Compatibility Matrix

Track at least:

| Dimension | Examples |
| --- | --- |
| Phone | Pixel, Samsung, OnePlus, Xiaomi. |
| Android version | 12, 13, 14, 15, 16 preview if relevant. |
| Earbud class | AirPods, Galaxy Buds, Sony, OnePlus Buds, generic Bluetooth SCO, BLE headset. |
| Input path | Platform STT, platform on-device STT, Sherpa VAD, Sherpa STT. |
| Output path | Android TTS route, speaker fallback, Bluetooth route. |
| Gesture path | Media play/pause, next/previous, volume, custom device events if available. |

## Agent Workflow And Plugin Ecosystem Gate

Do not expand agent/plugin capabilities until the physical and audio loop is stable. The plugin layer should receive normalized events, not raw uncertain voice state.

Promote plugin work only after:

| Gate | Threshold |
| --- | --- |
| Earbud event reliability | 95 percent sessions produce expected wake event on target devices. |
| Route reliability | 95 percent sessions route to intended mic or explicitly ask for fallback. |
| STT reliability | 95 percent short command capture success on measured devices. |
| TTS/interruption | Barge-in target met and measured. |
| Diagnostics | Support export explains failures without raw private audio. |

## Implementation Sequence

### Step 1: Platform Speech Metrics

Create:

| File/module | Responsibility |
| --- | --- |
| `SpeechSessionMetrics.kt` | Session timing data model. |
| `SpeechInputEngine.kt` | Interface for platform and offline engines. |
| `PlatformSpeechRecognizerEngine.kt` | Wrapper around current recognizer behavior. |
| `VoiceDiagnosticsStore.kt` | Short rolling history and export-ready summaries. |

Verification:

| Command/test | Expected result |
| --- | --- |
| Existing Android unit tests | No regressions. |
| New speech metrics unit tests | Start/final/error paths produce complete metrics. |
| Manual 20-session run | No stuck busy state; metrics populated. |

### Step 2: Route Proof

Create:

| File/module | Responsibility |
| --- | --- |
| `AudioRouteSession.kt` | Route request, settle, snapshot, release. |
| `AudioRouteProof.kt` | Captures route timing and suspicion flags. |

Verification:

| Test | Expected result |
| --- | --- |
| No Bluetooth device | User-facing fallback state, no crash. |
| Bluetooth route succeeds | Metrics show selected communication route. |
| Bluetooth disconnects mid-session | Session exits safely and UI explains route loss. |

### Step 3: Platform Callback VAD

Create:

| File/module | Responsibility |
| --- | --- |
| `PlatformVadObservation.kt` | RMS/begin/end/partial/final summary. |
| `VadTelemetry.kt` | Aggregates VAD-like observations. |

Verification:

| Test | Expected result |
| --- | --- |
| Silence/no speech | No transcript; no-speech classified. |
| Speech starts late | Delay visible in metrics. |
| Wrong mic suspicion | Low RMS plus expected Bluetooth route creates warning. |

### Step 4: AudioRecordRouteProbe

Create:

| File/module | Responsibility |
| --- | --- |
| `AudioRecordRouteProbe.kt` | Short diagnostic audio energy capture. |
| `AudioProbeMetrics.kt` | No raw audio, only energy and read stats. |

Verification:

| Test | Expected result |
| --- | --- |
| Mic permission denied | Clean error and support hint. |
| Mic already in use | Clean busy/error state. |
| Probe run | No raw audio file written. |

### Step 5: Sherpa VAD Evaluation

Create:

| File/module | Responsibility |
| --- | --- |
| `sherpa-eval` Gradle module or internal flavor | Keeps JNI/model risk isolated. |
| `SherpaVadProbe.kt` | Wraps Sherpa `Vad`. |
| `SherpaModelManager.kt` | Downloads or locates VAD model with checksum/version. |

Verification:

| Test | Expected result |
| --- | --- |
| Silero VAD on silence | No speech. |
| Silero VAD on command clips | Speech start/end within target tolerance. |
| Engine unload/reload | No service death. |

### Step 6: Sherpa STT Evaluation

Create:

| File/module | Responsibility |
| --- | --- |
| `SherpaSpeechInputEngine.kt` | Streaming or simulated streaming ASR. |
| `OfflineSpeechModelManager.kt` | Versioned model download/cache. |
| `OfflineSpeechBenchmark.kt` | Standard 20-session and clip-based benchmark. |

Verification:

| Test | Expected result |
| --- | --- |
| Offline engine disabled | Platform recognizer path unchanged. |
| Offline engine enabled | Same `SpeechInputEngine` callbacks as platform path. |
| Engine crash/failure | Falls back to platform recognizer with visible diagnostic. |

### Step 7: TTS Interruption Proof

Create:

| File/module | Responsibility |
| --- | --- |
| `SpeechOutputEngine.kt` | TTS abstraction. |
| `TtsInterruptionMetrics.kt` | Stop/flush/barge-in timing. |

Verification:

| Test | Expected result |
| --- | --- |
| Tap during TTS | TTS stops and listening begins. |
| New response while speaking | Old utterance superseded. |
| TTS engine failure | User sees fallback, relay remains usable. |

## What Not To Do

| Anti-pattern | Why it is dangerous |
| --- | --- |
| Replace `SpeechRecognizer` with Sherpa immediately | You lose a working baseline before knowing whether failures are route, endpoint, mic, TTS, or model related. |
| Run `AudioRecord` and platform `SpeechRecognizer` together | Mic contention can create false failures and device-specific bugs. |
| Ship huge offline STT models in base APK | Increases install friction before proof that users need it. |
| Store raw audio by default | Privacy and support risk. |
| Hide fallback from users | If phone mic fallback is used, user must know. |
| Let OpenClaw/agents decide audio policy | Audio routing and approval must remain deterministic and local. |
| Treat VAD as intent detection | VAD only decides speech boundaries; it must not infer commands or policy. |

## Recommended External Consumption Ranking

| Rank | Source | Consume now? | Reason |
| --- | --- | --- | --- |
| 1 | Android platform APIs | Yes | Already integrated; fastest path to measurable physical loop. |
| 2 | Sherpa-ONNX | Yes, behind flag | Best offline Android STT/VAD source with usable Kotlin examples. |
| 3 | Silero VAD | Yes, through Sherpa first | Strong VAD model and thresholds; avoid custom ONNX plumbing initially. |
| 4 | speech-android | Yes, as architecture/reference | Excellent model manager, JNI boundary, RecognitionService, barge-in examples; model footprint too large for default path. |
| 5 | Vosk | Reference only | Good endpointing and AudioRecord ownership examples. |
| 6 | whisper.cpp | Benchmark/reference only | Useful native Android benchmark and long-form fallback; not first low-latency command engine. |
| 7 | Picovoice Cobra | Benchmark if licensed | Simple VAD SDK, but access key/vendor dependency. |
| 8 | RNNoise | Later optional | Noise suppression only after route/STT metrics show need. |

## Source Inventory

External repos cloned under `external/audio-pipeline-references`:

| Repo | Local revision |
| --- | --- |
| `sherpa-onnx` | `c910efa` |
| `silero-vad` | `980b17e` |
| `whisper.cpp` | `3e9b7d0` |
| `vosk-api` | `9adbd76` |
| `rnnoise` | `70f1d25` |
| `picovoice-cobra` | `b419f01` |
| `speech-android` | `6b0c70b` |

Primary web sources:

| Source | URL |
| --- | --- |
| Android `SpeechRecognizer` | https://developer.android.com/reference/android/speech/SpeechRecognizer |
| Android `RecognizerIntent` | https://developer.android.com/reference/android/speech/RecognizerIntent |
| Android `RecognitionListener` | https://developer.android.com/reference/android/speech/RecognitionListener |
| Android `AudioManager` | https://developer.android.com/reference/android/media/AudioManager |
| Android `TextToSpeech` | https://developer.android.com/reference/android/speech/tts/TextToSpeech |
| Android media buttons | https://developer.android.com/media/legacy/media-buttons |
| Media3 background playback | https://developer.android.com/media/media3/session/background-playback |
| Sherpa-ONNX | https://github.com/k2-fsa/sherpa-onnx |
| Sherpa Android docs | https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html |
| Silero VAD | https://github.com/snakers4/silero-vad |
| Vosk API | https://github.com/alphacep/vosk-api |
| whisper.cpp | https://github.com/ggml-org/whisper.cpp |
| Picovoice Cobra | https://github.com/Picovoice/cobra |
| RNNoise | https://github.com/xiph/rnnoise |
| speech-android | https://github.com/soniqo/speech-android |

## Final Recommendation

The perfect product path is a measured, reversible voice stack:

1. Ship platform `SpeechRecognizer` with real endpoint and route telemetry.
2. Add VAD observations from existing callbacks.
3. Add a separate no-raw-audio route probe.
4. Evaluate Sherpa VAD and Sherpa STT behind a feature flag.
5. Use speech-android as a model-manager/JNI/RecognitionService reference, not a default dependency.
6. Keep all offline engines behind a rollback switch until physical earbud tests prove they beat the platform path.

This gives DevPods the best of both worlds: the current Android-native loop stays stable, while the offline stack becomes a serious, evidence-backed upgrade rather than a risky rewrite.
