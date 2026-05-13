# DevPods Relay For Android

This module is the Android-first product surface for DevPods.

It is intentionally transcript-first and latency-first:

- Android owns wake triggers, STT, audio routing, and TTS.
- The DevPods Bridge still owns session state, policy, approvals, repo actions, and optional OpenClaw rewriting.
- The app talks to the bridge through `GET /pairing`, `GET /health`, and `POST /events`.

## Product Architecture

The app is organized around a five-tab shell:

| Tab | Purpose |
| --- | --- |
| **Home** | Primary state card (`Ready`, `Listening`, `Thinking`, `Speaking`, `Approval required`, `Attention needed`), push-to-talk, quick actions, and speaker self-test. |
| **Activity** | Live transcript/reply view, queued action status, timeline of recent wake and bridge events, and autonomy continuation state. |
| **Device** | Bridge pairing status, pairing-code import, QR scan, communication-route summary, selected and available audio devices, and supported-device capability matrix. |
| **Help** | Onboarding replay, notification controls, diagnostics consent toggles, diagnostic preview/share, and support guidance. |
| **Developer** | Debug automation hooks (debug builds only), raw state dump, and bridge event queue inspection. |

### Onboarding and Setup

The first-launch experience walks new users through:

1. **What DevPods is** — earbud gesture -> bridge -> developer action -> spoken response.
2. **How to pair** — pairing-code exchange, QR scan, or manual base-URL entry.
3. **How to use gestures** — tap to wake, long-press for assistant fallback, triple-press for approve/reject.
4. **Setup wizard** — event-driven proof of physical wake, STT capture, and audio routing.

The setup wizard does not mark a step proven unless the physical signal path was actually observed during the test window.

## Pairing

The current onboarding path is:

1. Start the desktop bridge with a LAN-reachable `--pairing-base-url`.
2. Open the printed bridge pairing page on the phone, or scan the QR code from the **Device** tab.
3. Tap the `Open DevPods Relay` button, or paste either the printed pairing page URL or the `devpods://pair` link into the app.
4. Review the staged pairing payload in the app and tap `Import Pairing`.

The relay keeps the staged-import behavior on purpose, so opening a `devpods://pair` link does not silently overwrite saved bridge configuration.

Pairing codes are short-lived (5-minute TTL, one-time use) and auto-rotate on the bridge. The **Device** tab shows the current pairing state and allows re-pairing if the bridge changes.

## Current Structure

- `app/` Android application module
- `MainActivity` hosts the five-tab shell and coordinates navigation
- `RelayService` foreground relay service with MediaSession integration
- `BridgeClient` low-overhead HTTP client for the bridge
- `SignalProviderRegistry` — 10 earbud providers with priority-based fallback, health tracking, and dynamic preferred-provider selection
- `BtClassicSerialTransport` — shared RFCOMM transport with queue + reconnect backoff
- `L2capAapTransport` — L2CAP socket for AirPods AACP stem press events
- `AndroidMediaSessionProvider` universal headset and media-button wake fallback
- `AndroidSpeechRecognizer` local speech-recognition wrapper with partial results
- `AndroidTtsSpeaker` on-device spoken response adapter
- `BluetoothAudioRouter` communication-device routing for headset use
- `SetupWizardScreen` event-driven capability proof flow
- `OnboardingScreen` first-time user education
- `DiagnosticExport` redacted diagnostics builder and share intent

## Readiness And Hardware Verification

The **Home** tab surfaces product-state and verification data:

- a primary state card for `Ready`, `Listening`, `Thinking`, `Speaking`, `Approval required`, and `Attention needed`
- explicit speech-recognition and text-to-speech readiness indicators
- a visible communication-route summary with selected and available audio devices
- a speaker self-test action
- a hardware-verification card that distinguishes:
  - physical headset media-button wake
  - manual push-to-talk wake
  - debug automation injection

The **Device** tab shows the supported-device capability matrix, which records only capabilities that have been physically observed during setup or real-device validation.

## Latency Approach

The Android relay keeps latency low by design:

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

This repository includes a checked-in Gradle wrapper.

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
