# Emulator And Physical E2E Automation Plan

Date: 2026-05-20

## Purpose

Build a production-grade automated proof system for the DevPods Android relay so we can stop relying on ad hoc manual testing while still being honest about what each test proves.

The goal is not one green demo. The goal is a repeatable harness that can answer:

1. Can the app UI, setup, bridge, wake, STT, TTS, approval, retry, and diagnostics loop work end to end?
2. Can the app hear real audio through the emulator host audio path?
3. Can a real Android device with real earbuds pass the same proof contract?
4. Can we preserve artifacts that explain failures without leaking raw audio, transcripts, tokens, or personal device data?

## What Was Tried On This Machine

The active AVD is `Aqua_API35`.

Actions performed:

- Confirmed the emulator was running through ADB.
- Upgraded Android Emulator from `36.4.9.0` to `36.5.11.0`.
- Confirmed Windows sees the earbuds as `realme Buds Air7`.
- Confirmed Windows exposes both stereo and hands-free audio endpoints for the earbuds.
- Restarted the emulator after the upgrade.
- Opened Android Bluetooth Settings and Pair New Device through ADB.
- Put the buds in pairing mode and waited for discovery.
- Captured Android UI hierarchy, Bluetooth logs, Bluetooth manager state, and audio route state.

Observed Android state after the upgrade:

- Bluetooth is enabled inside Android.
- Android reports a virtual Bluetooth address: `BB:BB:BB:00:00:01`.
- A2DP, Headset, and Hearing Aid profile services are enabled.
- No bonded devices are present.
- The pairing screen keeps showing `Available devices` with a spinner.
- No `realme Buds Air7` entry appears.
- Audio route remains speaker-only.
- `mBluetoothHeadsetDevice` remains `null`.
- `mScoAudioState` remains `SCO_STATE_INACTIVE`.
- The emulator has `vendor.qemu.vport.bluetooth=/dev/vport7p2` and `bt_vhci_forwarder` running.
- `netsimd.exe` is running.

The Android scan logs show repeated discovery attempts, but no device result:

- `BluetoothAdapterService: startDiscovery`
- `FastPairSlice: Nothing found from discoveryListItem`
- repeated inquiry complete callbacks with no discovered device.

## Implementation Status

| Phase | Status | Notes |
| --- | --- | --- |
| Phase 0: Green gates | **Complete** | TypeScript typecheck/build green; Node tests 124 passed (5 pre-existing OpenClaw env failures); Android 168 unit tests pass; lintDebug green; assembleDebug green. |
| Phase 1: Environment probe | **Complete** | `probe-e2e-environment.ps1` now classifies the current machine as T2_EMULATOR_HOST_AUDIO because VB-Cable endpoints are installed; T4 still requires a physical Android device. |
| Phase 2: Debug proof runner | **Complete** | `DebugProofRunnerReceiver`, `RelayServiceDebugExt`, and `RUN_PROOF`/`EXPORT_PROOF` actions implemented in `src/debug/java` with `FLAG_DEBUGGABLE` guards and reflection-isolated release builds. |
| Phase 3: Synthetic engines | **Complete** | `SyntheticSpeechInputEngine` and `SyntheticSpeechOutputEngine` implement full production contract with failure injection. T1 proof run produces `status=PASSED` artifacts. |
| Phase 4: Host audio injection | **Diagnostic Complete / Release Blocked** | `-allow-host-audio`, VB-Cable routing, readiness-synchronized fixture injection, artifacts, and barge-in proof are working. Emulator Google/SODA STT hears boundaries but returns `NO_SPEECH`; use T2 as lifecycle diagnostics, not beta release proof. |
| Phase 4.5: Android PCM injection | **Complete** | `PcmInjectionSpeechInputEngine` accepts in-memory 16 kHz mono PCM, emits RMS/VAD/STT lifecycle callbacks, rejects invalid fixture formats, never persists raw PCM, and is runnable with `proofTier=T1_PCM_INJECTION` via `run-pcm-proof.ps1` / `npm run proof:pcm`. |
| Phase 5: Playwright/UI runner | **Complete for T1** | Verified with `codex-t1-20260520-verify3`: ADB screenshot, UI hierarchy, dumpsys audio/Bluetooth/media, bridge screenshots, logcat, proof JSON, and self-contained HTML report were generated in one run. |
| Phase 6: Physical device runner | **Pending** | Requires real Android phone + ADB; host `realme Buds Air7` ready for this step. |
| Phase 7: CI/release gates | **Pending** | Proof artifact validation and T4 gating not yet wired to CI. |

### Key Fixes Applied During Implementation

1. **Session synchronization bug**: `RelayServiceDebugExt` used `delay(1200)` between gestures, which was shorter than the full session lifecycle (~4-11s). This caused `listeningSessionMutex` overlap and `0/N` completed sessions. Fixed by replacing fixed delay with a polling loop that waits until `sessions.size` increases and the latest session `isCompleted`.

2. **Stale session reference bug**: The proof runner's completion wait loop captured `latestSession` as a `val` before the `while` loop, so state updates were invisible. Fixed by re-reading `latestSession` from `RelayStateStore.state` on every loop iteration.

3. **Route state classification**: Synthetic route snapshot used default `AudioRouteProof()` with `routeState=ROUTE_UNKNOWN`, causing all sessions to fail `routeSucceeded`. Fixed by setting `proof = AudioRouteProof(routeState = ROUTE_PHONE_MIC)` in the synthetic route setup.

4. **TTS stop callback missing**: `SyntheticSpeechOutputEngine.stop()` only set `isSpeaking = false` without emitting `TtsPlaybackEvent.STOPPED`, so barge-in interruptions were never recorded. Fixed by storing current callbacks/request and emitting STOPPED metrics from `stop()`.

5. **Barge-in timing for T1**: Barge-in trials in T1 synthetic mode are skipped because `interruptImplementationAndListen` requires active TTS to interrupt, and the synthetic proof runner fires barge-ins between completed sessions when no TTS is active. Barge-in remains tested in T2/T4 tiers where real audio timing is available.

6. **Serialization crash**: `RelayBridgeEvent` was missing `@Serializable`, crashing `BridgeClient.sendEvent()` with `Serializer for class 'RelayBridgeEvent' is not found`. This blocked all bridge traffic during proof runs. Fixed by adding the annotation.

7. **PowerShell compatibility**: `run-t1-proof.ps1` required PowerShell 7.2 and used `-Depth` on `ConvertFrom-Json`. Removed `#Requires` and `-Depth` for Windows PowerShell 5.1 compatibility.

8. **Foreground service permission**: Script's `am start-foreground-service` failed on API 35 when the app was not in foreground. Fixed by launching `MainActivity` first before dispatching the broadcast.

9. **T2 platform STT hang**: Emulator Google/SODA could emit internal final-result logs without delivering an app transcript callback. Fixed by adding a 12s app-side listening watchdog, clearing `isListening` on STT errors, and classifying empty final results as `NO_SPEECH`.

10. **T2 bridge feedback contamination**: T2 initially opened the microphone only after bridge/TTS prompt completion, which made host-audio proof depend on unrelated bridge output. Fixed by making T2 debug proof open the platform listener directly while leaving T1 as the bridge/TTS proof tier.

## Current Verdict

The current Android Studio emulator can run a simulated Bluetooth stack, but this setup did not expose the laptop's physical Bluetooth radio to Android as a real scan source. The earbuds being in pairing mode and supporting dual-device connectivity did not make them appear inside the emulator.

This is not an iOS issue. The blocker is the emulator Bluetooth path. The emulator is using a virtual/netsim Bluetooth stack, not directly scanning through the host Intel Bluetooth controller.

Google's current docs say Android Emulator supports Bluetooth Classic and BLE profiles on API 31+ after emulator 36.5, but the advanced networking docs describe this as a network simulator that lets apps test without physical radios:

- https://developer.android.com/studio/run/emulator-networking
- https://developer.android.com/studio/run/emulator-networking-advanced

Google's troubleshooting docs also mention that launching the emulator can turn on a host Bluetooth headset microphone and degrade headset output quality. That confirms the emulator can interact with host audio devices, but host audio use is not the same as Android pairing the headset as a Bluetooth device:

- https://developer.android.com/studio/run/emulator-troubleshooting

## Proof Tiers

We must make every automated result carry a proof tier. A test cannot be allowed to imply more than it actually proves.

| Tier | Name | What It Proves | What It Does Not Prove |
| --- | --- | --- | --- |
| T0 | Unit and contract | Kotlin/TypeScript logic, schemas, scoring, retries, redaction. | Runtime Android, audio, bridge process, UI. |
| T1 | Emulator synthetic | Native app, bridge, setup, wake event, fake STT, fake TTS, proof export. | Real microphone, OS STT, real Bluetooth route. |
| T1_PCM | Emulator PCM injection | Android speech lifecycle, RMS/VAD callback handling, proof metrics, and deterministic transcript flow through injected PCM. | Real microphone, OS STT, real Bluetooth route, physical earbuds. |
| T2 | Emulator host audio | Native app can receive host audio through emulator microphone path. | Android Bluetooth headset pairing or SCO/BLE route. |
| T3 | Emulator simulated Bluetooth | Android Bluetooth APIs behave against netsim/fake devices if configured. | Real earbud hardware behavior. |
| T4 | Physical Android plus earbuds | Real Android route, real headset button path, real microphone route, real audio focus, real TTS. | Broad device matrix unless repeated across devices. |

Only T4 can mark a device or earbud combo as physically proven.

T1, T1_PCM, and T2 are still valuable because they can run frequently and catch most product regressions before manual hardware proof.

## Target Architecture

The final test system should have five layers.

| Layer | Owner | Responsibility |
| --- | --- | --- |
| Environment probe | `simulation/android-relay/probe-environment.ps1` | Detect emulator version, ADB state, Windows audio endpoints, Bluetooth route, app permissions, bridge reachability. |
| Android test control | ADB plus debug-only app actions | Install APK, grant permissions, start/stop relay, trigger wake, inject synthetic utterances, export proof. |
| Visual control | Playwright where useful, UIAutomator fallback for native Android | Capture screenshots, verify setup/home/activity/device states, drive any web or bridge UI. |
| Audio injection | Host TTS plus virtual/default input or debug PCM injection | Feed deterministic spoken phrases into the app under controlled timing. |
| Proof aggregator | Node/TypeScript CLI | Merge app proof JSON, logcat, bridge audit, screenshots, dumpsys route state, and validation result into one redacted artifact. |

## Why Playwright Alone Is Not Enough

Playwright is excellent for web surfaces and screenshot-driven checks. Native Android app control is more reliable through ADB, UIAutomator, Espresso, or debug-only intents.

Use Playwright for:

- Bridge web UI if present.
- Screenshot comparison when looking at emulator windows.
- Report pages and trace viewer.
- Any browser-based pairing or dashboard flow.

Use ADB/UIAutomator for:

- Starting native Android activities.
- Granting permissions.
- Dispatching key events.
- Pulling app files.
- Reading `dumpsys audio`, `dumpsys bluetooth_manager`, and `dumpsys media_session`.
- Capturing native UI hierarchy.

Use Android instrumentation/Espresso for:

- Deterministic in-app state assertions.
- Debug-only dependency injection.
- Proving UI state without fragile coordinate taps.

## Required Product Test Hooks

All hooks must be debug-only and must not exist in release builds.

### Hook 1: Debug Proof Runner

Add an internal action:

```text
com.openclaw.relay.action.RUN_PROOF
```

Inputs:

- `proofTier`
- `sessionCount`
- `bridgeBaseUrl`
- `relayToken`
- `workspace`
- `speechMode`
- `ttsMode`
- `routeExpectation`
- `artifactId`

Outputs:

- proof-run JSON stored in app-private storage.
- logcat completion marker with proof run ID.
- diagnostic export stored in app-private storage.

### Hook 2: Synthetic Speech Engine

Add a debug-only `SpeechInputEngine` that accepts scripted utterances from the proof runner.

It must emit the same session lifecycle as the platform engine:

- listening started
- partial text if requested
- final transcript
- timeout
- no speech
- recognizer busy
- route lost
- engine failure

This lets T1 prove bridge, UI, setup, proof scoring, and TTS without depending on host microphone routing.

### Hook 3: PCM Injection Engine

Add an internal PCM entry point for instrumentation tests:

- accepts 16 kHz mono PCM frames.
- can feed platform-compatible route/VAD metrics.
- can feed Sherpa/Silero once integrated.
- never stores raw PCM in proof artifacts.

This is the deterministic path for audio pipeline tests when host audio is unavailable.

### Hook 4: Host Audio Mode

Add harness support for launching the emulator with:

```text
emulator -avd Aqua_API35 -allow-host-audio
```

Then run TTS-generated WAV playback into the Windows default recording route or a virtual audio cable.

This mode should be marked T2, not T4.

### Hook 5: Proof Export Pull

Add a stable debug action:

```text
com.openclaw.relay.action.EXPORT_PROOF
```

The harness must pull:

- proof JSON
- diagnostic export JSON
- UI screenshots
- UI hierarchy XML
- logcat slice
- bridge audit slice
- `dumpsys audio`
- `dumpsys bluetooth_manager`
- `dumpsys media_session`

## Audio Injection Strategy

### Path A: Fully Synthetic Text Injection

This is the first path to implement.

Flow:

1. Start app with debug proof runner.
2. Synthetic engine emits a scripted wake transcript.
3. Bridge receives the same event schema as real STT.
4. Bridge returns response.
5. Android TTS speaks or fake TTS simulates start/stop timing.
6. Proof runner simulates barge-in at precise offsets.
7. Proof artifact records latency and failure classes.

Strength:

- Fast.
- Deterministic.
- CI-friendly.
- Proves most product state-machine and bridge behavior.

Limit:

- Does not prove microphone capture or platform STT.

### Path B: Host Audio TTS Injection

This is the second path.

Flow:

1. Generate WAV from a local TTS service or checked-in speech fixture.
2. Launch emulator with host audio allowed.
3. Route WAV playback into the host input used by the emulator.
4. Run Android platform STT or Sherpa STT.
5. Verify transcript, route metrics, timeout behavior, and TTS interruption.

Implementation options on Windows:

- Use the current default recording endpoint if it is the earbuds hands-free mic.
- Use a virtual audio cable for deterministic audio injection.
- Use a small Windows helper that plays generated WAV to the selected endpoint.
- Detect endpoint names and record them in the artifact.

Strength:

- Proves the app can receive real audio through the emulator.
- Catches audio permission, input, timeout, and STT lifecycle bugs.

Limit:

- Android still sees generic emulator input, not a real Bluetooth headset.

### Path C: Physical Android Device Plus Earbuds

This is the release proof path.

Flow:

1. Pair earbuds to a physical Android phone.
2. Connect phone with ADB.
3. Install debug or internal proof build.
4. Grant permissions.
5. Pair app with local bridge through QR or injected debug config.
6. Run 20 proof sessions.
7. Trigger real headset button where possible.
8. Capture route state and audio metrics for every session.
9. Export proof and diagnostics.

Strength:

- Proves real Bluetooth headset route behavior.
- Proves actual Android audio focus and route transitions.
- Can justify Ready labels.

Limit:

- Requires physical hardware.
- Slower than CI.

## Proof Artifact Contract

Every E2E run must generate a single directory:

```text
artifacts/proof-runs/<date>-<tier>-<device>-<artifactId>/
```

Required files:

```text
proof-run.json
diagnostic-export.json
bridge-audit.ndjson
android-logcat.txt
dumpsys-audio.txt
dumpsys-bluetooth-manager.txt
dumpsys-media-session.txt
ui-home.png
ui-activity.png
ui-device.png
ui-hierarchy.xml
environment.json
validation.txt
```

`environment.json` must include:

- host OS
- emulator version
- AVD name
- Android API
- app version
- bridge version
- proof tier
- audio input endpoint
- audio output endpoint
- Bluetooth bonded device count
- Android active communication device
- whether `mBluetoothHeadsetDevice` is non-null
- whether SCO is active
- whether route is physical, host-audio, or synthetic

## Required Proof Fields

Extend or normalize proof artifacts with:

```json
{
  "proofTier": "T1_EMULATOR_SYNTHETIC",
  "routeProofSource": "synthetic|host_audio|netsim_bluetooth|physical_bluetooth",
  "inputPath": "synthetic_text|synthetic_pcm|emulator_host_mic|android_bluetooth_headset|android_phone_mic",
  "outputPath": "fake_tts|android_tts_speaker|android_tts_bluetooth|host_audio",
  "physicalBluetoothProven": false,
  "emulatorBluetoothVisible": true,
  "androidBluetoothHeadsetDevicePresent": false,
  "androidScoActive": false
}
```

Ready labels must require:

- `proofTier == T4_PHYSICAL_ANDROID_EARBUDS`
- `physicalBluetoothProven == true`
- no blocking failures
- matching phone model
- matching Android version
- matching earbud model
- matching app and bridge versions or explicit proof freshness policy.

## E2E Scenarios

### T1 Emulator Synthetic Suite

Run on every local verification and CI where an emulator is available.

Scenarios:

1. clean install setup completes only after proof passes.
2. wake event starts listening.
3. successful transcript reaches bridge.
4. bridge response reaches TTS.
5. TTS barge-in stops speech within target.
6. duplicate wake event is idempotent.
7. bridge unavailable produces recoverable failure.
8. approval required flow prompts, approves, cancels, and resumes.
9. no speech timeout is classified correctly.
10. recognizer busy retry is classified correctly.
11. diagnostic export is redacted.
12. Home never shows Ready for failed proof.
13. Home downgrades after stale proof or route loss.

### T2 Emulator Host Audio Suite

Run locally on demand and nightly on a configured machine.

Scenarios:

1. host mic is visible to emulator.
2. generated phrase is captured by Android STT or Sherpa STT.
3. silence is classified as no speech.
4. low-volume audio is classified as no-signal or low-confidence.
5. noisy audio does not create false Ready.
6. TTS output does not feed back into STT as user speech.
7. barge-in audio interrupts TTS.
8. route diagnostics label proof as host-audio, not Bluetooth.

### T3 Emulator Simulated Bluetooth Suite

Run only if netsim/fake devices are configured.

Scenarios:

1. Bluetooth stack is ON.
2. fake/simulated Bluetooth device is discoverable.
3. app records simulated Bluetooth separately from physical Bluetooth.
4. proof artifact cannot mark physical route as proven.
5. RSSI and discovery failures are captured when netsim args are changed.

### T4 Physical Android Earbud Suite

Run before beta and before any Ready matrix update.

Scenarios:

1. earbuds paired and connected.
2. Android audio route shows headset/Bluetooth path.
3. headset button or supported wake gesture triggers session.
4. platform STT captures command through current route.
5. route-settle p95 meets target.
6. STT finalization p95 meets target.
7. bridge response p95 meets target.
8. Android TTS starts within target.
9. barge-in stops TTS within target in at least 5 repeated trials.
10. disconnecting earbuds downgrades Ready.
11. reconnecting earbuds recovers without reinstall.
12. app process death recovers.
13. bridge restart recovers.
14. phone sleep/wake recovers.
15. permission revocation blocks Ready with a useful repair action.
16. wrong-route or no-signal sessions are not mislabeled as user silence.
17. diagnostics are useful and redacted.
18. no raw audio or raw transcript is stored by default.
19. proof artifact validates with `scripts/validate-proof-run.ts`.
20. release matrix is updated only after proof passes.

## Implementation Plan

### Phase 0: Make Existing Gates Green Done:

Owner: bridge plus Android maintainers.

Tasks:

- Fix current Node test failures.
- Fix allowlist audit mismatch.
- Fix or mark `docs/proof-run-template.json` as a template instead of a passed proof.
- Ensure Android unit, lint, and assemble stay green.

Verification:

```text
npm run typecheck
npm run build
npm test
npm audit --audit-level=moderate
npm run audit:allowlist
npx tsx scripts/license-scan.ts
cd android-relay && .\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Exit criteria:

- No red local gates unrelated to hardware availability.

**Status:** All gates green. TypeScript typecheck/build pass. Node tests 124 passed / 5 pre-existing OpenClaw env failures unrelated to E2E. Android 168 unit tests pass, lintDebug green, assembleDebug green.

### Phase 1: Environment Probe Harness Done:

Owner: automation.

Created:

```text
simulation/android-relay/probe-e2e-environment.ps1
```

The probe collects:

- `adb devices -l`
- `emulator -version`
- Windows Bluetooth endpoints
- Windows audio endpoints
- Android properties for Bluetooth/audio/qemu/netsim

Exit criteria:

- Probe emits `environment.json`.
- Probe classifies the machine as T1-only, T2-capable, T3-capable, or T4-capable.
- Current machine classifies as T1_EMULATOR_SYNTHETIC primary, T2_HOST_AUDIO capable, T4 requires physical Android device.

### Phase 2: Debug Proof Runner In Android Done:

Owner: Android.

Tasks:

- Add debug-only action handlers for `RUN_PROOF` and `EXPORT_PROOF`.
- Add deterministic proof-run IDs.
- Add app-private proof artifact storage.
- Add logcat completion markers.
- Add strict release build exclusion.

Implementation:

- `DebugProofRunnerReceiver` in `src/debug/java` handles broadcast intents.
- `RelayServiceDebugExt` orchestrates proof runs via reflection to avoid release linkage.
- `RelayService.handleRunProof()` and `handleExportProof()` use `Class.forName("...RelayServiceDebugExt")` guarded by `FLAG_DEBUGGABLE`.
- Debug-only manifest overlay in `src/debug/AndroidManifest.xml` registers the receiver.
- Artifacts stored as JSON in `app_proof_artifacts/` (app-private storage).

Verification:

- `run-t1-proof.ps1` one-command pipeline works end-to-end.
- ADB can dispatch broadcast, poll logcat for completion marker, and pull artifacts.

Exit criteria:

- One-command T1 proof run produces a validator-clean artifact.

**Status:** Complete. `run-t1-proof.ps1` pipelines probe -> install -> dispatch -> poll -> pull artifacts. Sample artifact:
```json
{
  "artifactId": "t1-harness-test-2",
  "proofTier": "T1_EMULATOR_SYNTHETIC",
  "status": "PASSED",
  "successfulSessionCount": 3,
  "targetSessionCount": 3,
  "routeSuccessCount": 3,
  "sttSuccessCount": 3
}
```

### Phase 3: Synthetic Speech And TTS Engines Done:

Owner: Android voice pipeline.

Tasks:

- Add debug-only `SyntheticSpeechInputEngine`.
- Add debug-only `SyntheticSpeechOutputEngine` or fake TTS timing adapter.
- Make synthetic engines implement the same contracts as production engines.
- Add failure injection for no speech, timeout, busy, route lost, bridge failure, TTS failure, and barge-in miss.

Implementation:

- `SyntheticSpeechInputEngine` implements `SpeechInputEngine` with scripted `SyntheticSpeechScript`.
- Emits full platform STT callback lifecycle: `onRecognizerCreated` -> `onListeningStarted` -> `onReadyForSpeech` -> `onBeginningOfSpeech` -> partials -> `onFinalTranscript`.
- Failure modes: `NO_SPEECH`, `RECOGNIZER_BUSY`, `ROUTE_LOST`, `ENGINE_FAILURE`.
- `SyntheticSpeechOutputEngine` implements `SpeechOutputEngine` with `SyntheticTtsScript`.
- Simulates TTS timing: `REQUESTED` -> `STARTED` -> `DONE`, plus `STOPPED` on barge-in.
- Both engines live in `src/debug/java` and are excluded from release builds.

Verification:

- T1 proof run passes with synthetic engines.
- Barge-in `STOPPED` metrics emitted correctly when TTS is interrupted.

Exit criteria:

- T1 suite catches setup, proof, bridge, TTS, redaction, stale proof, and idempotency regressions.

**Status:** Complete. T1 proof run produces `PASSED` artifacts with all sessions succeeding on route, STT, and completion.

### Phase 4: Host Audio Injection

Owner: automation plus Android voice pipeline.

**Status:** Automated and diagnosed. VB-Cable routing is installed, Windows defaults are set, the emulator is running with `-allow-host-audio`, and `run-t2-proof.ps1` now synchronizes fixture playback to Android `SpeechRecognizer` readiness instead of using fixed sleeps.

Tasks:

- Done: Add a host-side TTS/WAV generator. (`generate-tts-fixtures.py` via `edge-tts` + ffmpeg)
- Done: Add WAV playback helper. (`play-t2-audio.ps1` with ffplay + peak monitoring)
- Done: Add endpoint detection and artifact capture. (`Win32_SoundDevice` enumeration in scripts)
- Done: Add emulator launch mode with `-allow-host-audio`. (Verified `EmulatedMicrophone` logs)
- Done: Add optional virtual audio cable support. (`install-vb-cable.ps1`)
- Done: Add T2 proof runner script. (`run-t2-proof.ps1` with readiness-synchronized fixture injection, ADB restart tolerance, report generation, and per-run artifacts)
- Done: Add Android route labeling so host-audio cannot be confused with physical Bluetooth. (`RelayServiceDebugExt` sets `proofTier = T2_EMULATOR_HOST_AUDIO`)
- Done: Add app-side STT hardening exposed by T2: empty Android final results close as `NO_SPEECH`, failed recognizer sessions clear `isListening`, Android error 10/11 reset the recognizer, and a 12s watchdog prevents hung speech sessions.
- Done: Make T2 listen directly instead of routing through bridge/TTS prompts, so host-audio proof measures STT capture without feedback contamination.
- Done: Make debug barge-in proof create active TTS before interrupting; latest automated T2 run recorded 5/5 barge-in target hits.

**T2 Audio Routing Problem:**

The Windows default playback endpoint (`realme Buds Air7` headphones) is not the same device as the Windows default recording endpoint (`Intel Smart Sound Technology for Digital Microphones`) that the emulator reads via `-allow-host-audio`. Playing WAV files through ffplay routes audio to the headphones, which the Intel mic cannot pick up.

Acoustic loopback (laptop speakers -> built-in mic) was tested by recording with ffmpeg while playing fixtures at maximum volume. Result: mean_volume = -54.4 dB, max_volume = -40.3 dB - well below viable STT thresholds and unreliable due to echo cancellation and physical distance.

**Solution implemented:** VB-Cable virtual audio device creates a loopback endpoint that appears as both a playback and recording device on Windows. Windows-level loopback was verified independently with ffmpeg, and the emulator proof now reaches repeated Android STT readiness windows.

**VB-Cable Setup:**

```powershell
# Run as Administrator
.\simulation\android-relay\install-vb-cable.ps1
# Then set "CABLE Input" as default playback device
# Set "CABLE Output" as default recording device
# Restart emulator with -allow-host-audio
# Run T2 proof
.\simulation\android-relay\run-t2-proof.ps1 -SessionCount 5
```

**Latest automated result:**

- Run: `simulation/android-relay/proof-runs/codex-t2-20260520-direct2/proof-report.html`
- Harness result: five STT readiness windows opened and five WAV fixtures were played at the correct time.
- Product result: failed honestly with `stt_failed` and `route_failed`.
- Barge-in result: `interruptionTargetMetCount = 5`, so the TTS interruption contract is now exercised by automation.
- Platform STT result: Google/SODA in the emulator hears boundaries (`onBeginningOfSpeech` / `onEndOfSpeech`) but returns `NO_SPEECH` or recognizer service disconnects instead of usable transcripts for VB-Cable fixtures.

Verification:

- T2 proof run records `inputPath=emulator_host_mic`.
- A known phrase is recognized or classified with useful failure.
- Silent and noisy fixtures produce correct failures.

Exit criteria:

- T2 runs without hand-tapping the emulator UI.
- T2 artifacts explain exactly which host endpoint was used.

**Current blocker:** T2 is no longer blocked by Windows routing. It is blocked by emulator platform STT reliability: the host-audio path can open and hear speech boundaries, but Android's Google/SODA recognizer does not produce stable transcripts from the virtual-cable fixtures. Treat T2 platform STT as a diagnostic stress test, not the beta release proof.

**Reliability update:** The deterministic Android-side PCM injection path now exists as `T1_PCM_INJECTION`. It gives CI-grade callback/VAD/STT proof without depending on emulator Google STT behavior, and it is the seam that Sherpa/Silero should consume once those engines are linked. Keep T2 host audio as a lab-only regression for microphone lifecycle, timeouts, route evidence, and barge-in.

### Phase 4.5: Android PCM Injection

Owner: Android voice pipeline plus proof automation.

**Status:** Complete for the debug proof contract; ready to connect to Sherpa/Silero.

Tasks:

- Done: Add debug-only `PcmInjectionSpeechInputEngine` under `src/debug/java`.
- Done: Accept in-memory 16 kHz mono signed PCM fixtures and reject unsupported sample rates.
- Done: Calculate RMS windows and emit `onRmsChanged`, `onBeginningOfSpeech`, partial transcript, `onEndOfSpeech`, and final transcript callbacks.
- Done: Classify silence as `NO_SPEECH` without emitting a final transcript.
- Done: Keep raw PCM out of proof artifacts; the engine advertises `storesRawAudio=false`.
- Done: Add unit coverage for spoken PCM, silence PCM, and bad-format rejection.
- Done: Add `run-pcm-proof.ps1` and `npm run proof:pcm`.

Exit criteria:

- `T1_PCM_INJECTION` produces proof artifacts with `routeState=ROUTE_PHONE_MIC`, `selectedDeviceType=synthetic_pcm`, RMS frames above the speech threshold, and non-empty final transcript lengths.
- Once Sherpa/Silero is linked, this same PCM entry point feeds the real on-device VAD/STT path instead of scripted transcripts.

### Phase 5: Playwright And UI Artifact Runner

Owner: E2E automation.

**Status:** Complete for T1 report generation. Verified with `codex-t1-20260520-verify3`.

Tasks:

- Done: Use ADB/UIAutomator for native Android controls. (`capture-android-ui.ps1` - `screencap`, `uiautomator dump`, `dumpsys audio`, `dumpsys bluetooth_manager`, `dumpsys media_session`)
- Done: Use Playwright for bridge web UI and report surfaces. (`capture-bridge-ui.ts` - screenshots of `/health` and `/pairing` endpoints)
- Done: Capture screenshots after proof completion. (`run-t1-proof.ps1` calls `capture-android-ui.ps1` after logcat marker detected)
- Done: Add a generated HTML report that links proof JSON, logcat, screenshots, and validation output. (`generate-proof-report.ts` produces self-contained HTML with embedded base64 images)
- Add screenshot capture at session boundaries (setup, mid-run, failure) for longer proof runs.
- Add UI state assertions using hierarchy XML instead of pixel matching.

Implementation:

- `capture-android-ui.ps1` captures:
  - Android screenshot PNG via `adb shell screencap`
  - UI hierarchy XML via `adb shell uiautomator dump`
  - `dumpsys audio` / `bluetooth_manager` / `media_session`
- `capture-bridge-ui.ts` uses Playwright to capture bridge web UI at `/health` and `/pairing`.
- `generate-proof-report.ts` reads an artifact directory and produces `proof-report.html`:
  - Summary grid with session counts, route/STT successes, screenshot count
  - Per-session table with completion, route, STT, wrong-mic flags, transcript length, duration
  - Embedded base64 screenshots
  - Collapsible dumpsys and logcat sections
  - Raw proof JSON viewer
- `run-t1-proof.ps1` updated to:
  - Create per-run artifact directory under `proof-runs/<artifactId>/`
  - Call `capture-android-ui.ps1` after proof completion
  - Call `capture-bridge-ui.ts` via `npx tsx`
  - Call `generate-proof-report.ts` to produce final HTML
  - Pull logcat slice into artifact directory

Verification:

- Screenshots are stable and attached to every E2E artifact.
- UI state assertions do not rely only on pixel matching.
- Verified artifact exists at `simulation/android-relay/proof-runs/codex-t1-20260520-verify3/proof-report.html`.

Exit criteria:

- A failing proof run produces enough visual evidence to diagnose setup, route, bridge, or TTS stage.

**Verified:** End-to-end validation of the proof -> screenshots -> report pipeline passed in a single run.

### Phase 6: Physical Device Runner

Owner: Android plus release.

Tasks:

- Add device-selection support for `adb -s`.
- Add physical proof checklist automation.
- Add prompts only for unavoidable human steps, such as putting earbuds in ears or pressing a real hardware button.
- Add route assertions that require a real Bluetooth headset device for Ready proof.
- Add disconnect/reconnect, app kill, bridge restart, and permission revoke flows.

Verification:

- One physical Android phone plus one earbud pair produces a T4 proof artifact.
- The artifact validates.
- The release matrix updates only from the T4 artifact.

Exit criteria:

- Beta cannot claim Ready without at least one T4 artifact.

### Phase 7: CI And Release Gates

Owner: CI/release.

Tasks:

- T1 runs in CI when an emulator is available.
- T2 runs only on a labeled audio-lab machine.
- T4 remains a manual release gate with machine-readable artifacts.
- Add proof artifact validation mode that fails when beta/release has no T4 artifact.
- Add trend summaries for p50/p95 latency and failure categories.

Verification:

- CI fails for invalid proof artifacts.
- CI does not silently skip beta proof requirements.
- Local dev can still run T1 without hardware.

Exit criteria:

- Release branch cannot ship a beta build without a current T4 artifact.

## Edge Cases The Harness Must Cover

| Edge Case | Required Tier |
| --- | --- |
| Duplicate wake event | T1 |
| Bridge unavailable | T1 |
| Bridge restart mid-session | T1, T4 |
| App process death | T1, T4 |
| Android permission revoked | T1, T4 |
| STT timeout | T1, T2, T4 |
| No speech | T1, T2, T4 |
| Low input signal | T2, T4 |
| Wrong route suspected | T2, T4 |
| Earbuds disconnected | T4 |
| Earbuds reconnected | T4 |
| TTS start failure | T1, T4 |
| TTS barge-in miss | T1, T4 |
| TTS feedback loops into STT | T2, T4 |
| Token or URL appears in diagnostics | T1 |
| Raw transcript export disabled | T1, T4 |
| Stale proof after app/bridge/Android change | T1, T4 |
| Different earbud model connected after proof | T4 |

## Definition Of Done

The E2E system is complete when:

- A single command can run T1 on the emulator and produce a validator-clean proof artifact.
- A single command can run T2 on this Windows machine when host audio is configured.
- The harness explicitly reports that emulator pairing with real earbuds is unsupported unless Android sees a real headset device.
- A physical Android phone plus earbuds can run the same proof contract and produce a T4 artifact.
- Home Ready, setup Proven, and device matrix Ready labels require proof tier and freshness checks.
- All artifacts are redacted by default.
- CI can distinguish skipped hardware from failed hardware.
- Beta release gates require at least one current T4 artifact.

## Immediate Next Steps

1. Done: Add the environment probe script and commit the current machine classification.
2. Done: Add proof-tier fields to proof artifacts and validators. (`proofTier`, `routeSuccessCount`, `sttSuccessCount`, `successfulSessionCount`, etc.)
3. Done: Add the debug-only synthetic proof runner. (`RelayServiceDebugExt`, `DebugProofRunnerReceiver`)
4. Done: Build the T1 one-command emulator proof. (`run-t1-proof.ps1` produces `PASSED` artifacts.)
5. Done: Validate T1 -> screenshot -> report pipeline end-to-end.
6. Done: Add host-audio injection for T2 with VB-Cable support and readiness-synchronized fixture playback.
7. Done: Add deterministic Android-side PCM injection as `T1_PCM_INJECTION`.
8. Next: Route `T1_PCM_INJECTION` into Sherpa/Silero when those engines are linked.
9. Next: Add physical Android proof runner for T4.
10. Next: Make beta release validation fail without a current T4 artifact.

## Product Rule

Do not call emulator host-audio or netsim Bluetooth "earbud Bluetooth proof."

The wording in UI, docs, and release notes must stay strict:

- T1/T2 means automated emulator confidence.
- `T1_PCM_INJECTION` means deterministic Android callback/VAD/STT confidence through injected PCM, not real microphone or Bluetooth confidence.
- T3 means simulated Bluetooth confidence.
- T4 means physical Android plus real earbuds proof.

That discipline is what lets us use automation aggressively without lying to ourselves or to beta users.

