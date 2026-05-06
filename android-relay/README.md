# OpenClaw Relay For Android

This module is the Android Software Relay MVP for the developer-earbuds project.

It is intentionally transcript-first and latency-first:

- Android owns wake triggers, STT, audio routing, and TTS.
- The existing desktop bridge still owns session state, policy, approvals, repo actions, and optional OpenClaw rewriting.
- The app talks to the bridge through the existing `GET /health` and `POST /events` contract.

## Current Structure

- `app/` Android application module
- `MainActivity` debug and operator UI
- `RelayService` foreground relay service
- `BridgeClient` low-overhead HTTP client for the bridge
- `RelayMediaSessionController` headset and media-button wake handling
- `AndroidSpeechRecognizer` local speech-recognition wrapper with partial results
- `AndroidTtsSpeaker` on-device spoken response adapter
- `BluetoothAudioRouter` communication-device routing for headset use

## Latency Approach

The Android relay MVP keeps latency low by design:

- manual push-to-talk is always available and starts listening immediately
- headset wake events reuse the same low-overhead service path
- STT stays on Android instead of streaming raw audio to the desktop
- the bridge returns one short `speak` string and Android speaks only that field
- the desktop bridge no longer emits duplicate local speech for `android_relay` sessions

## Permissions

The app declares the permissions needed for the MVP:

- `INTERNET`
- `RECORD_AUDIO`
- `BLUETOOTH_CONNECT`
- `MODIFY_AUDIO_SETTINGS`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MICROPHONE`
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
- `POST_NOTIFICATIONS`

## Build

This repository now includes a checked-in Gradle wrapper.

From this directory on Windows:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

Debug builds keep the emulator and LAN relay path usable by applying cleartext network allowance from the debug source set only. Release builds do not inherit that cleartext allowance.

## Emulator Validation

The repository includes a repeatable installed-app validation harness at `..\simulation\android-relay\validate-installed-app.ps1`.

The automation entrypoints used by that harness are intended for debug builds only. Production builds still expose the launcher activity, but automation extras are ignored unless the app is debuggable.

On a debuggable build, treat these hooks as developer-only surfaces, not as a trusted control plane. They should stay on emulator or private test devices.

It validates the emulator-installed debug APK against the host bridge by checking all of these actions end to end:

- relay start
- explicit health check
- quick status shortcut
- synthetic headset wake event path
- approval prompt for `open file docs/vision.md`
- cancel
- second approval prompt
- approve
- relay stop

The emulator harness validates the service-side wake path by injecting `headset_button_single`. It does not prove physical media-button delivery from real headset hardware.

Run the bridge on the host with the same relay token expected by the Android debug app, then execute the validation script from the repo root:

```powershell
npm run cli -- start --host 127.0.0.1 --relay-token android-emulator-token
.\simulation\android-relay\validate-installed-app.ps1
```