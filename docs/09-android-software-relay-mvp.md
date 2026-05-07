# Android Software Relay MVP

## Purpose

This document defines the next practical product phase after the simulator-first desktop MVP.

The goal is to prove that ordinary Bluetooth earbuds plus an Android phone can act as the software relay for DevPods without requiring firmware changes or custom hardware.

This is now the primary validation path for the product.

## Vision

The Android phone becomes the relay layer:

`Bluetooth earbuds -> DevPods Relay -> DevPods Bridge -> Developer tools -> spoken response back through earbuds`

The relay phase is intentionally software-first and latency-first.

## Non-Goals

This phase does not attempt to prove:

- custom earbud firmware
- raw BLE tap counting or vendor-specific gesture parity
- battery or case telemetry
- a Bluetooth audio proxy accessory
- production-grade mobile hardening, discovery, or pairing UX

## Current Implementation In This Repository

This repo now includes the bridge-side and mobile-side foundations for the Android relay phase:

- the bridge accepts relay-native events such as `android_push_to_talk`, `android_status_shortcut`, `android_approve`, `android_reject`, `android_cancel`, and `headset_button_single`
- the bridge can bind to a LAN-facing host via `--host`
- the bridge can require a relay bearer token via `--relay-token` or `JARVIS_RELAY_TOKEN`
- desktop notifier output is suppressed for `android_relay` sessions so Android becomes the only spoken sink
- a first-class relay smoke harness exists at `npm run relay:smoke`
- a source-complete Android prototype exists under `android-relay/`
- the Android project includes a checked-in Gradle wrapper and has passed both `assembleDebug` and `assembleRelease`
- a checked-in emulator validation harness exists at `simulation/android-relay/validate-installed-app.ps1`
- the app now surfaces product-state readiness, communication-route status, a speaker self-test, and a hardware-verification card for wake-source evidence

## Target Architecture

```text
Bluetooth Earbuds
  -> Android Relay foreground service
  -> BridgeClient over HTTP on local network
  -> DevPods Bridge policy and routing
  -> Optional OpenClaw rewrite path
  -> Short response returned to Android
  -> Android TTS back to earbuds
```

## Android Responsibilities

The Android relay app owns:

- headset and media-button wake capture
- manual push-to-talk fallback
- Android speech recognition
- Android TTS playback
- Bluetooth communication-device routing
- approval, reject, and cancel UI
- bridge health checks and session status display

## Bridge Responsibilities

The existing desktop bridge still owns:

- session state
- policy enforcement
- approval gating
- workspace context
- repo and CI actions
- optional OpenClaw rewriting
- short, ear-safe response shaping

## Event Contract

The Android relay uses the same `POST /events` endpoint and the same response contract as the simulator path.

Example voice-command request:

```json
{
  "source": "android_relay",
  "sessionId": "android_sess_001",
  "workspace": "current_repo",
  "device": "both_buds",
  "event": "android_push_to_talk",
  "timestamp": 1710000000,
  "utterance": "summarize my current diff",
  "profile": "default"
}
```

Example approval request response:

```json
{
  "speak": "Open the target file? Right double tap to approve.",
  "display": "Open the target file in workspace firmware_earphones.",
  "requiresApproval": true,
  "approvalRequest": {
    "actionType": "open_file",
    "summary": "Open the target file",
    "riskClass": "approval_required",
    "expiresInMs": 12000
  },
  "actionId": "act_123",
  "status": "blocked",
  "nextState": "approval_pending",
  "followUpHint": "Right double tap approve, left double tap reject"
}
```

## API Endpoints

Required endpoints for the relay phase:

- `GET /health`
- `POST /events`

No second control plane was introduced for Android. Approval, reject, cancel, and quick status all ride the same `/events` contract.

The relay contract is now extended in one additive way for bounded autonomy. Bridge responses may include an optional `autonomy` object describing the current report or plan, the safe next intent, and the delay before automatic continuation.

When the bridge binds to a non-loopback host, a relay bearer token is now mandatory. The transport is still plain HTTP in the current MVP, so deployments should stay on a trusted LAN or add a TLS boundary externally.

## Latency Strategy

The relay phase keeps latency low with three explicit choices:

1. Android performs STT locally and sends transcript text, not raw audio, to the bridge.
2. The bridge returns a short `speak` field immediately and Android speaks only that field.
3. OpenClaw remains behind the existing adaptive, budgeted rewrite boundary when enabled.

The current relay smoke on this machine measured:

- `health`: `32 ms`
- `quickStatus`: `62 ms`
- `voiceCommand`: `46 ms`
- `approvalPrompt`: `3 ms`
- `cancel`: `2 ms`
- `approvalStart`: `3 ms`

These numbers are bridge and relay HTTP timings on the current machine. They do not include real Android STT or Bluetooth transport overhead.

## Installed-App Emulator Validation

The current implementation has now been validated on the installed debug app running in the `Aqua_API35` Android emulator against the host bridge at `http://10.0.2.2:4545`.

The checked-in validation harness confirms all of these paths end to end:

- `START_RELAY`
- `CHECK_HEALTH`
- `DEBUG_EVENT android_status_shortcut`
- `DEBUG_EVENT headset_button_single` for the synthetic wake-event path
- `DEBUG_EVENT android_push_to_talk` with an approval-required `open file docs/vision.md` utterance
- `CANCEL`
- a second approval prompt
- `APPROVE`
- `STOP_RELAY`

Android 15 foreground-service restrictions required the automation path to stay activity-driven. `MainActivity` now runs as `singleTop`, and repeated adb-driven actions are delivered through `onNewIntent` instead of relying on a fresh activity launch.

These automation hooks are debug-only surfaces. The installed-app harness targets the debug APK, and production builds ignore relay automation extras.

Debug builds should still be treated as test-only for this path: a debuggable app intentionally exposes these hooks for emulator and developer-device automation.

Because this is emulator automation, `DEBUG_EVENT headset_button_single` validates the service-side wake path rather than a physical MediaSession button press from real headset hardware.

The app now makes that distinction explicit in the UI: physical headset media-button wake, manual push-to-talk, and debug injection each appear as separate wake sources.

The app now also exposes the active autonomy state in the UI so a device test can confirm whether the relay is waiting to continue, waiting for a user objection, or idle.

## Manual Hardware Verification Path

The next make-or-break check is real headset wake on Android hardware.

Use this validation path on a physical Android device:

1. Pair the earbuds to the phone.
2. Start DevPods Relay and confirm the bridge health is healthy.
3. Press the earbud media button.
4. Confirm the hardware-verification card reports `Physical headset media button`.
5. Speak a short command and confirm the UI moves through `Listening`, `Thinking`, and `Speaking`.

If that wake source never appears, the product risk remains open for that device or earbud pair even if the emulator path is green.

The current real-device field state is now more precise than that general rule:

- RMX3990 + realme Buds Air7 now produces partial real-device recognition through the relay path.
- Physical tap delivery is still intermittent, so the wake trigger is not yet reliable enough for a production claim.
- Assistant long press remains the most reliable fallback trigger for status and interruption on this stack.
- The operational notes for this exact device pair now live in `docs/10-rmx3990-buds-air7-field-notes.md`.

## User Flows

### Wake And Ask

- headset button or manual push-to-talk starts listening immediately on Android
- Android captures speech locally
- bridge returns a short response
- Android speaks the response back through the earbuds

### Status Shortcut

- Android sends `android_status_shortcut`
- bridge returns quick status
- Android speaks the returned `speak` string

### Approval

- Android sends a voice command that requires approval, such as `open file app.ts`
- bridge returns `requiresApproval: true` and an `actionId`
- Android surfaces approve, reject, and cancel
- Android sends `android_approve`, `android_reject`, or `android_cancel` with `pendingActionId`

### Bounded Autonomy

- Android receives a background-work completion response with an optional `autonomy` object
- the relay speaks the report and displays the next step in the autonomy card
- if the user stays silent, the relay sends `android_autonomy_continue`
- if the user taps or uses assistant long press during active implementation, the relay interrupts the current flow, captures speech, and sends `android_autonomy_interrupt`
- the bridge answers with an updated bounded plan instead of opening arbitrary tool autonomy

## Acceptance Criteria

The relay phase is considered successful when all of the following are true:

- at least one headset or media-button wake path works on Android
- manual push-to-talk works reliably as the fallback
- voice command requests reach the bridge over the existing `/events` contract
- Android speaks only the `speak` field from the bridge response
- approval and cancel preserve the existing `actionId` lifecycle
- bounded autonomy preserves the same safety envelope as the existing allowlisted bridge intents
- the bridge stays silent for `android_relay` sessions
- the relay path remains low-latency enough to feel immediate for quick status and approval prompts

## Known Android Limitations

- headset and media-button compatibility varies by OEM and earbud vendor
- Android microphone access requires runtime permission and a foreground-service flow
- Bluetooth routing behavior differs between classic SCO, BLE headset, and wired headset devices
- local relay traffic currently relies on explicit cleartext HTTP allowance in the debug Android source set because the bridge is LAN-hosted and does not terminate TLS itself
- adb-driven validation for approval prompts must quote multi-word utterances correctly; the checked-in emulator script handles this for `am start`

## Hardware Compatibility Matrix

Use this matrix when validating the relay app on real hardware:

| Device class | Wake event | STT path | TTS route | Approval UI | Notes |
| --- | --- | --- | --- | --- | --- |
| Cheap Bluetooth earbuds | Pending manual test | Pending manual test | Pending manual test | Implemented | Focus on media-button variability |
| Clone AirPods | Pending manual test | Pending manual test | Pending manual test | Implemented | Expect vendor-specific button quirks |
| Wired headset | Pending manual test | Pending manual test | Pending manual test | Implemented | Useful fallback reference device |
| Phone only | Implemented by manual PTT | Implemented in app scaffold | Implemented in app scaffold | Implemented | Baseline validation path |

## Exit Criteria For Firmware Phase

Do not move back to custom firmware until the Android relay phase proves all of the following:

1. At least two different headset classes can trigger a wake path.
2. Voice commands reach the bridge reliably.
3. TTS responses play through the headset path.
4. Approval and cancel work from Android UI.
5. Quick status and diff-summary style interactions feel useful.
6. Relay latency is acceptable.
7. The hardware compatibility matrix is documented with real-device results.