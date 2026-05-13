# Product Readiness Implementation Gap Audit

## Purpose

This document audits the current implementation against:

- [11-end-user-product-readiness-audit.md](11-end-user-product-readiness-audit.md)
- [13-librepods-end-user-readiness-usage.md](13-librepods-end-user-readiness-usage.md)

It focuses on implementation gaps, not product strategy. The question is:

`which readiness claims now have code behind them, and where is the code still stubbed, mismatched, unproven, or product-incomplete?`

## Audit Verdict

The implementation has moved meaningfully beyond the earlier readiness docs:

- Pairing exists across bridge, QR page, Android import, and persisted relay config.
- The Android relay has a provider boundary for media-session, assistant-entry, and LibrePods-native lanes.
- A device capability matrix, setup wizard, device status card, listen-readiness model, and troubleshooting card now exist.
- Bridge requests can carry hardware context on the TypeScript side.

But the product is still not end-user ready. The main remaining gaps are not "no code exists"; they are:

1. Some new code is only a scaffold or stub.
2. Some Android-to-bridge contracts do not match.
3. Some setup and capability probes record optimistic or synthetic state rather than observed physical truth.
4. The real LibrePods/AACP path is not integrated yet.
5. The user-facing setup still depends on long-lived trusted-LAN credentials and manual recovery.

## Highest Priority Gaps

## P0.1 Android hardware context does not match the bridge schema

### Evidence

The Android hardware context model serializes the field as `providerId`:

- `android-relay/app/src/main/java/com/openclaw/relay/signal/HardwareContext.kt:6`
- `android-relay/app/src/main/java/com/openclaw/relay/signal/HardwareContext.kt:7`

The bridge schema requires the field to be named `provider`:

- `src/protocol/schemas.ts:77`
- `src/protocol/schemas.ts:78`

Android attaches this hardware context to relay events:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:281`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:503`

### Gap

Any Android request that includes hardware context can serialize as:

```json
{
  "hardwareContext": {
    "providerId": "android_media_session",
    "deviceConfidence": "proven"
  }
}
```

But the bridge accepts:

```json
{
  "hardwareContext": {
    "provider": "android_media_session",
    "deviceConfidence": "proven"
  }
}
```

That means hardware-context relay requests can fail schema validation before reaching runtime behavior.

### Product impact

This directly threatens the LibrePods/provider path from doc 13 because the bridge cannot reliably consume Android's hardware context as currently serialized.

### Required fix

Add Kotlin serialization mapping, for example `@SerialName("provider") val providerId: String`, or rename the Android field to `provider`. Add an Android serialization test that posts the exact Android JSON shape into the TypeScript bridge schema.

## P0.2 LibrePods provider is a stub, not a real BLE/AACP provider

### Evidence

`LibrePodsAirPodsProvider` explicitly says it is a stub:

- `android-relay/app/src/main/java/com/openclaw/relay/signal/LibrePodsAirPodsProvider.kt:17`
- `android-relay/app/src/main/java/com/openclaw/relay/signal/LibrePodsAirPodsProvider.kt:18`
- `android-relay/app/src/main/java/com/openclaw/relay/signal/LibrePodsAirPodsProvider.kt:54`

Its probe always reports no detected device:

- `android-relay/app/src/main/java/com/openclaw/relay/signal/LibrePodsAirPodsProvider.kt:64`
- `android-relay/app/src/main/java/com/openclaw/relay/signal/LibrePodsAirPodsProvider.kt:66`

The Android Gradle dependencies do not include a LibrePods module or package.

### Gap

Doc 13's most important recommendation is to use LibrePods as a high-fidelity earbud intelligence layer. The current implementation has the provider boundary, but no real BLE scanner, AACP packet parser, stem-press event stream, in-ear state, battery state, or device capability integration from LibrePods.

### Product impact

LibrePods is still effectively "reference material plus an empty adapter." It does not yet improve physical wake reliability, listen readiness, interrupt gestures, or the supported-device matrix.

### Required fix

Integrate or wrap the relevant LibrePods Android surfaces behind `LibrePodsAirPodsProvider`:

- BLE device discovery/state
- AACP stem press events
- battery and in-ear state
- device capability profile
- lifecycle and scan backoff

Keep the current provider boundary, but replace the stub internals.

## P0.3 Listen-readiness can block wake before the route is prepared

### Evidence

`RelayService.handleGestureSignal` computes listen readiness before calling `prepareListeningRoute`:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:236`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:249`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:256`

The readiness model blocks if no device state and no ready route are present:

- `android-relay/app/src/main/java/com/openclaw/relay/signal/ListenReadiness.kt:27`

Current device state is only updated when provider state-change events arrive:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:309`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:314`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:319`

### Gap

The service may reject a wake as blocked before it has attempted to route communication audio. This is especially risky for:

- manual push-to-talk
- media-session wake events
- assistant fallback wake events
- any provider that has a valid wake event but no current `RelayStateStore.currentDeviceState`

### Product impact

This is exactly the "wake works, then dead air" failure mode from doc 11. Even if the physical event arrives, the relay may not start STT because readiness was checked against stale pre-route state.

### Required fix

Reorder the flow:

1. Record wake.
2. For listen-window triggers, prepare/settle route.
3. Refresh audio route and provider/device state.
4. Compute readiness.
5. Block only if readiness is still blocked after route preparation.

Add tests for "no device state but route becomes ready after preparation."

## P0.4 There are two media-session input paths with overlapping behavior

### Evidence

The original `RelayMediaSessionController` owns a MediaSession and emits `RelayWakeSignal`:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayMediaSessionController.kt:84`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayMediaSessionController.kt:104`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayMediaSessionController.kt:118`

The new `AndroidMediaSessionProvider` also owns a MediaSession and emits `EarbudSignalEvent.WakeGesture`:

- `android-relay/app/src/main/java/com/openclaw/relay/signal/AndroidMediaSessionProvider.kt:82`
- `android-relay/app/src/main/java/com/openclaw/relay/signal/AndroidMediaSessionProvider.kt:104`

Both are started in `RelayService.onCreate`:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:83`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:95`

### Gap

The app now has two different Media3 sessions trying to capture the same class of physical media-button input. That can produce duplicate wake handling, inconsistent state summaries, or whichever session wins depending on Android routing behavior.

### Product impact

This undermines the P0 requirement to declare one dependable physical wake path. The code path itself is ambiguous.

### Required fix

Choose one media-session owner. Prefer routing everything through `AndroidMediaSessionProvider`, then let `RelayService` consume normalized provider events. Retire or adapt `RelayMediaSessionController` so it does not create a second competing session.

## P0.5 Device setup probes are not measuring real observed capability yet

### Evidence

`RelayViewModel.probeDevice` creates a registry and immediately calls `probeAll`:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt:315`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt:316`

But it does not start the registry before probing. The media provider probe reports success only if its media session exists:

- `android-relay/app/src/main/java/com/openclaw/relay/signal/AndroidMediaSessionProvider.kt:143`
- `android-relay/app/src/main/java/com/openclaw/relay/signal/AndroidMediaSessionProvider.kt:145`

The device model line treats `detectedDevice = false` as enough to use the LibrePods message as a device model:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt:320`

The matrix still records interrupt, approve/reject, STT, and TTS interruption as unproven:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt:336`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt:348`

### Gap

The setup wizard does not yet run a true user-observed gesture/STT/TTS validation. It records a profile from provider self-reports and defaults, not from actual physical event arrival and speech-capture success.

### Product impact

This can create a compatibility matrix that looks structured but is not evidence-grade. Doc 13 explicitly says support should be claimed only after the app observes the relevant signal on that phone and OS build.

### Required fix

Make setup event-driven:

- start the registry before probing
- wait for an actual wake event within a timeout
- record which provider emitted the wake
- run a real STT phrase check
- run a real interrupt test while speaking or executing
- write `PROVEN` only for events that were observed in that setup session

## P1 Gaps

## P1.1 Pairing still exposes long-lived relay credentials

### Evidence

The bridge pairing payload includes the relay token when auth is enabled:

- `src/bridge/server.ts:162`
- `src/bridge/server.ts:164`

The HTML pairing page displays the relay token:

- `src/bridge/server.ts:180`
- `src/bridge/server.ts:209`

The pairing endpoint is intentionally unauthenticated:

- `src/bridge/server.ts:254`
- `src/bridge/server.ts:255`

### Gap

Pairing is much better than raw manual entry, but it is still a trusted-LAN handoff of a long-lived bearer token. There is no one-time pairing code, no expiry, no token rotation, and no post-pairing trust management.

### Product impact

This remains a product setup and security gap before broader dogfooding.

### Required fix

Move to one-time or short-lived pairing credentials:

- generate a temporary pairing secret
- exchange it for a persisted relay credential
- expire the pairing secret
- hide long-lived tokens from the HTML page
- provide "reset/revoke pairing" UX

## P1.2 UI is improved but still mixes product flow and debug controls

### Evidence

The app has a setup wizard and product-state derivation:

- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt:239`
- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt:394`

But advanced raw settings remain in the main flow:

- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt:338`
- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt:349`

Troubleshooting exists:

- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt:363`

### Gap

The UX is no longer only a debug console, but it still exposes raw bridge URL/token/workspace fields and operational controls as first-class product UI. The setup wizard steps also do not yet verify real outcomes.

### Product impact

Friendly technical dogfood is closer. General end-user readiness is still blocked.

### Required fix

Demote raw fields behind a developer/debug screen, and make the primary flow:

1. Pair bridge.
2. Verify device.
3. Verify wake.
4. Verify listen.
5. Show ready/blocked with one clear recovery action.

## P1.3 Bridge accepts hardware context but does not yet use it for behavior

### Evidence

Bridge request building preserves hardware context:

- `src/bridge/request-builder.ts:23`

The schemas validate it:

- `src/protocol/schemas.ts:77`
- `src/protocol/schemas.ts:125`

### Gap

Hardware context is currently passed through as data, but the runtime/policy layer does not appear to use it to:

- adjust listen readiness
- validate physical approval confidence
- alter supported-device claims
- improve audit or user-facing response text

### Product impact

The bridge is hardware-context-ready, but not hardware-context-aware.

### Required fix

Use hardware context in audit records, response summaries, and approval policy only where it is safe. For example, allow a proven hardware interrupt to cancel active work, but never let hardware context bypass high-risk approval policy.

## P1.4 Device matrix exists only locally and is not yet a shareable support claim

### Evidence

The matrix model and storage exist:

- `android-relay/app/src/main/java/com/openclaw/relay/device/DeviceCapabilityMatrix.kt:6`
- `android-relay/app/src/main/java/com/openclaw/relay/device/DeviceProfileStorage.kt:11`

### Gap

The current matrix is local app state. There is no checked-in support matrix, diagnostic export, redaction story for device identifiers, or field-validation protocol that updates docs/README claims.

### Product impact

The repo still cannot make a narrow, evidence-backed supported-device claim from implementation data.

### Required fix

Add a redacted diagnostic export and a checked-in compatibility matrix seeded only by real validation sessions.

## P1.5 Approval and interrupt gestures are still mostly screen/assistant driven

### Evidence

Bridge approval controls exist in the Android UI:

- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt:365`

The provider capability plan includes approval gestures, but current setup stores approve/reject as unproven:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt:337`

### Gap

Doc 13's desired hardware approval/reject/interrupt gesture model is not implemented for real physical providers. The current dependable path remains assistant long press or screen controls.

### Product impact

This keeps the wearable control surface incomplete during active implementation.

### Required fix

After the provider path is cleaned up, map proven physical gestures to:

- interrupt/cancel
- approve
- reject

Only enable those mappings in states where they make sense.

## Verification Performed During This Audit

Focused TypeScript protocol/pairing tests passed:

```text
npm test -- --run test/protocol-schemas.test.ts test/bridge-request-builder.test.ts test/bridge-pairing-api.test.ts
3 test files passed, 19 tests passed
```

Android debug unit tests/build passed:

```text
.\gradlew.bat testDebugUnitTest
BUILD SUCCESSFUL
```

These checks confirm the repo compiles and the bridge-side expectations pass. They do not prove the Android-to-bridge serialized JSON shape, real-device gesture delivery, real STT-after-wake behavior, or LibrePods BLE/AACP integration.

## Recommended Fix Order

1. Fix the Android/bridge `hardwareContext.provider` contract mismatch.
2. Remove the duplicate media-session ownership and route all media-button input through one provider path.
3. Reorder listen-readiness so route preparation happens before blocking.
4. Replace setup self-reporting with event-driven physical wake/STT/interrupt probes.
5. Integrate real LibrePods BLE/AACP logic behind the existing provider stub.
6. Replace long-lived token pairing with one-time/rotating pairing credentials.
7. Add redacted diagnostic export and a checked-in supported-device matrix.
8. Move raw debug settings out of the primary product flow.

## Bottom Line

The implementation now has the right skeleton for end-user readiness:

- normalized signal providers
- hardware context
- capability matrix
- QR pairing
- setup and troubleshooting surfaces

But the skeleton is not yet a reliable product loop. The most urgent work is to make the new abstractions truthful at runtime: matching JSON contracts, one media-session path, route readiness after actual routing, real setup observations, and a real LibrePods provider instead of a stub.
