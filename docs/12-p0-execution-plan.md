# DevPods P0 Execution Plan

## Purpose

This document turns the end-user readiness audit into an implementation plan.

It focuses on the highest-impact gaps first and separates:

- what can be fixed in software now
- what is blocked on Android OEM behavior or headset firmware behavior

The goal is not to reopen product debate.

The goal is to execute the next slices in the right order.

## Current Delivery Principle

The repo already has a strong bridge, relay, approval, bounded-autonomy, and testing foundation.

The immediate problem is not architecture.

The immediate problem is that the product contract is still broader than what the real RMX3990 plus realme Buds Air7 testing has actually proven.

So the first rule for the next phase is:

`narrow the shipped contract to proven behavior before trying to widen support again`

## Delivery Order

## Phase 1: Lock the proven wake and interrupt contract

### Goal

Make the Android relay honest about which physical controls are currently supported on the real tested hardware.

### Why first

This is the smallest high-impact slice that is fully under software control.

It also removes ambiguity before debugging speech capture.

If the app still treats unproven gestures as product gestures, every STT or autonomy failure becomes harder to interpret.

### Current field reality

On RMX3990 plus realme Buds Air7:

- one media-button wake path is partially proven
- assistant long press is the most reliable interrupt fallback
- latest user-reported behavior says triple tap and long press produced responses while double tap did not on the newest pass, but that mapping still needs matching traces before it becomes the declared contract

### Files to change

- `android-relay/app/src/main/java/com/openclaw/relay/RelayGestureRouting.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayMediaSessionController.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt`

### Tests to change or add

- `android-relay/app/src/test/java/com/openclaw/relay/RelayGestureRoutingTest.kt`
- `android-relay/app/src/test/java/com/openclaw/relay/RelayMediaSessionControllerTest.kt`

### Expected result

- only proven wake paths open listening
- unproven Android media keys stop being mapped to product gestures
- the UI clearly presents assistant long press as the dependable interrupt fallback on this stack

### Software or hardware

Software-fixable now.

### Exit criteria

1. Android unit tests reflect only the proven wake contract.
2. The relay UI copy no longer implies support for unproven gestures.
3. Existing approval/autonomy interrupt flows still work through the supported paths.

## Phase 2: Harden listen-after-wake speech capture on the supported path

### Goal

Improve the success rate of speech recognition after a real wake event on the already-supported path.

### Why second

This only becomes tractable once the wake contract is narrowed.

### Main implementation targets

- explicit route-settle behavior before STT starts
- clearer route diagnostics that distinguish selected communication route from named Bluetooth output visibility
- recognizer reset or restart on common route or busy errors
- better state reporting when wake succeeds but listening cannot start cleanly

### Files to change

- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/BluetoothAudioRouter.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/AndroidSpeechRecognizer.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayAudioDeviceCatalog.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayModels.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayStateStore.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt`

### Tests to change or add

- `android-relay/app/src/test/java/com/openclaw/relay/RelayAudioDeviceCatalogTest.kt`
- new focused unit tests for route verification and recognizer restart behavior where practical

### Expected result

- wake success and STT start are more tightly coupled
- route and microphone failures surface clearly instead of looking like silent dead air
- the app becomes honest about when it is ready to listen versus when it only sees the earbuds as output devices

### Software or hardware

Mostly software-fixable now.

### Known external risk

If the OEM Bluetooth stack cannot keep a stable communication route for bud-mic capture, this phase may end with a narrower supported-device claim rather than a universal fix.

### Exit criteria

1. A successful wake leads to a clean listening attempt on the supported path.
2. Route failures are visible and actionable.
3. The relay no longer reports a misleading ready state when the communication route is not actually usable for STT.

## Phase 3: Replace raw relay config with a pairing flow

### Goal

Move setup from developer procedure to product onboarding.

### Current status

The first pairing pass is already implemented in the current repo:

- the desktop bridge exposes `GET /pairing` as either a browser-usable HTML page or a JSON pairing payload
- the CLI prints both the bridge pairing page URL and the underlying `devpods://pair` link
- the Android relay can import either the pairing page URL or the deep link and persist the paired bridge config
- the app now has an explicit paired versus not-paired state instead of relying on raw manual fields alone
- the pairing page now renders a QR code for the pairing page URL
- the Android relay now has a QR scan entrypoint that feeds the existing pairing import path
- the repo can now produce a portable Windows bridge bundle with a launcher and default bridge config

### Why third

This is the biggest visible UX upgrade, but it only matters once the core physical loop is honest and more reliable.

### Product target

The clean onboarding model should be:

1. The desktop bridge starts.
2. It generates a pairing payload.
3. The phone imports that payload.
4. The relay stores the paired bridge configuration.
5. The user no longer needs to manually type a base URL and token.

### Remaining implementation target

The raw pairing contract is now in place, so the next pairing work should focus on product polish rather than contract invention.

The next pass should support:

- one-time or rotating pairing credentials instead of a long-lived trusted-LAN secret
- friendlier reconnection and bridge-unavailable recovery flows
- less debug-oriented onboarding copy and state transitions
- desktop package polish and safer bridge-start defaults

### Files to change

- `src/bridge/server.ts`
- `src/cli/jarvis-earbuds.ts`
- `src/cli/runtime-options.ts`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayModels.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayStateStore.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/BridgeClient.kt`

### Tests to change or add

- focused bridge HTTP tests for pairing payload generation and authorization behavior
- Android unit tests for pairing import parsing and stored-config updates

### Expected result

- default setup no longer depends on the user editing raw URL and token fields
- the bridge can advertise a safe pairing payload for the relay
- onboarding is materially closer to QR-based pairing instead of adb-based wiring

### Software or hardware

Software-fixable now.

### Exit criteria

1. The bridge can emit a pairing payload.
2. The relay can import and store it.
3. The app can start from paired state without raw manual token entry.

These first-pass exit criteria are now met. The remaining work is productizing the pairing flow.

## Phase 4: Narrow the supported-device matrix and publish it

### Goal

Make the product claim match the tested reality.

### Work

- explicitly name the currently supported device pair and supported gestures
- document known partial-support and unsupported gesture cases
- add at least one more phone class and one more headset class to the validation plan

### Files to change

- `docs/10-rmx3990-buds-air7-field-notes.md`
- `docs/11-end-user-product-readiness-audit.md`
- `README.md`
- new compatibility-matrix doc if needed

### Software or hardware

Documentation plus validation work.

### Exit criteria

1. Public product wording no longer overclaims support.
2. The tested matrix is explicit.

## What Is Not Directly Software-Fixable

These items can be improved around the edges, but code alone may not solve them:

- OEM-specific Bluetooth routing behavior on RMX3990
- whether realme Buds Air7 exposes triple tap or long press through standard Android media-button delivery
- whether the device stack supports stable bud-mic capture after the specific physical wake path in all states

The correct response to these cases is not wishful implementation.

It is:

- declare the proven contract
- provide fallback paths
- validate more device pairs

## Immediate Work Sequence For This Turn

1. Re-run real-device validation on RMX3990 plus realme Buds Air7 against the honest wake contract, STT hardening, and the new pairing flow.
2. Finish the next layer of onboarding polish on top of the now-working QR flow: token lifecycle, recovery UX, and package polish.
3. Tighten the supported-device matrix and public support claims around the proven physical paths.
4. Expand physical validation to at least one more phone class and one more headset class.

## Success Definition For The Next Closeout

The next closeout should be able to say all of the following:

1. Fresh real-device validation confirms whether the hardened wake and listen path is dependable enough on the declared supported stack.
2. Onboarding no longer depends on sharing raw manual fields and is materially closer to scan-and-pair product setup.
3. The remaining setup gaps are clearly narrowed to token lifecycle, recovery UX, and support-matrix expansion rather than the pairing contract itself.