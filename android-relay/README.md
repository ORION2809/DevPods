# DevPods Relay For Android

This module is the Android-first product validation surface for DevPods.

It is intentionally transcript-first and latency-first:

- Android owns wake triggers, STT, audio routing, and TTS.
- The DevPods Bridge still owns session state, policy, approvals, repo actions, and optional OpenClaw rewriting.
- The app talks to the bridge through the existing `GET /health` and `POST /events` contract.

## Current Structure

- `app/` Android application module
- `MainActivity` product-demo UI with readiness, approval, and hardware verification
- `RelayService` foreground relay service
- `BridgeClient` low-overhead HTTP client for the bridge
- `RelayMediaSessionController` headset and media-button wake handling
- `AndroidSpeechRecognizer` local speech-recognition wrapper with partial results
- `AndroidTtsSpeaker` on-device spoken response adapter
- `BluetoothAudioRouter` communication-device routing for headset use

## Readiness And Hardware Verification

The app now surfaces product-state and verification data directly in the main screen:

- a primary state card for `Ready`, `Listening`, `Thinking`, `Speaking`, `Approval required`, and `Attention needed`
- explicit speech-recognition and text-to-speech readiness indicators
- a visible communication-route summary with selected and available audio devices
- a speaker self-test action
- a hardware-verification card that distinguishes:
	- physical headset media-button wake
	- manual push-to-talk wake
	- debug automation injection

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

## Manual Hardware Verification

To validate the real product risk on Android hardware:

1. Pair real Bluetooth earbuds to an Android device.
2. Start DevPods Relay and confirm the primary state reaches `Ready`.
3. Press a supported physical media button on the earbuds.
4. Confirm the hardware-verification card reports `Physical headset media button`.
5. Speak a short command and confirm the UI transitions through `Listening`, `Thinking`, and `Speaking`.

If the app only shows `Push-to-talk button` or `Debug automation`, the physical wake path is still unproven on that device and earbud pair.

For laptop-side signal evidence on Windows, use `..\simulation\windows-relay\verify-media-buttons.ps1`. A manual run on the connected laptop has already observed `MEDIA_PLAY_PAUSE`, which proves one Windows media-key path from the paired earbuds. That does not replace the Android verification above.

Run the bridge on the host with the same relay token expected by the Android debug app, then execute the validation script from the repo root:

```powershell
npm run devpods -- start --host 127.0.0.1 --relay-token android-emulator-token
.\simulation\android-relay\validate-installed-app.ps1
```