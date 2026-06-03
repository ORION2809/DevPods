# Unknown Earbud Calibration Implementation Plan

Generated: 2026-05-27

## Verdict

We should not try to predict every earbud model. The product should treat every user's earbuds as unknown until the app observes their real signals on that phone, saves a calibrated gesture profile, and routes only proven gestures.

The current app already has the right base: `SignalProviderRegistry`, `AndroidMediaSessionProvider`, `GenericBluetoothHeadsetProvider`, vendor providers, setup wizard, device capability matrix, diagnostics, and proof gates. What is missing is a first-class calibration and gesture-mapping layer that sits on top of those pieces.

Calibration must complement the current provider system, not replace it. Vendor providers, Android MediaSession, Generic Bluetooth, Assistant fallback, route proof, diagnostics, and the capability matrix remain the source of raw signals and device context. The new layer learns how those existing signals behave for this user's earbuds and converts them into reliable user-selected actions.

## Product Contract

Every beta user must pass this flow:

1. Connect earbuds.
2. Run guided calibration.
3. Follow timed instructions: single tap, double tap, triple tap, long press, left/right variants when detectable.
4. App records the actual Android/provider signals produced by those taps.
5. App shows which gestures were detected, missed, ambiguous, or fallback-only.
6. User assigns actions to proven gestures.
7. App saves a device/phone-specific profile and uses it at runtime.

No signal observed during calibration can be advertised as reliable.

## Architecture To Add

This is an additive layer:

- Keep `SignalProviderRegistry` as the source of earbud/provider events.
- Keep vendor providers for richer AirPods, Samsung, Sony, Nothing, Oppo/Realme, and future device-specific signals.
- Keep Android MediaSession and Generic Bluetooth as the universal fallback path.
- Keep the existing setup/proof/readiness gates.
- Add calibration between provider events and product actions.

New flow:

`Providers -> Raw Events -> Calibration Fingerprints -> User Gesture Map -> Relay Actions -> Proof/Diagnostics`

### 1. Calibration Domain Model

Add `EarbudCalibrationProfile` stored next to `DeviceCapabilityMatrix`.

Required fields:

- `profileId`
- `deviceModel`
- `deviceAddressHash`
- `phoneModel`
- `androidVersion`
- `providerId`
- `calibratedGestures`
- `gestureActionMap`
- `routeProof`
- `createdAtMs`
- `updatedAtMs`
- `confidence`

Each calibrated gesture stores:

- requested gesture: `single_tap`, `double_tap`, `triple_tap`, `long_press`
- observed provider event
- observed keycode/action/timing when MediaSession is used
- bud side if known, otherwise `unknown`
- detection latency
- repeatability count
- failure count
- confidence: `proven`, `observed`, `ambiguous`, `unsupported`

### 2. Calibration Engine

Add `EarbudCalibrationEngine` under Android relay.

Responsibilities:

- Open a short listening window for one requested gesture.
- Collect events from all active providers.
- Normalize raw events into a `SignalFingerprint`.
- Reject noisy/duplicate events using timing and keycode debouncing.
- Require repeatability: same gesture must be detected at least 2 of 3 times.
- Mark collisions: if single tap and double tap produce the same signal, only one can be mapped.
- Save only redacted signal metadata, never raw audio.

This engine should use the existing provider registry instead of adding another hardware path. It listens to provider output, scores it, and saves mappings; it does not scan Bluetooth, own MediaSession, or duplicate vendor protocol code.

### 3. Guided Setup UX

Replace the current one-shot wake test with a calibration wizard:

1. Device check: confirm Bluetooth headset connected and media session primed.
2. Gesture capture cards:
   - "Tap once now"
   - "Tap twice now"
   - "Press and hold now"
   - "Try triple tap" only if useful for approvals.
3. For each card show: waiting, detected, retry, unsupported, ambiguous.
4. Route proof: after wake gesture is chosen, ask user to speak a phrase and verify STT/TTS route.
5. Mapping screen: assign actions to proven gestures.

Default actions:

- Wake and listen
- Interrupt current speech/work
- Approve action
- Reject/cancel action
- No action

### 4. Runtime Gesture Router

Add `CalibratedGestureRouter` as a thin layer before the existing relay gesture handling.

Runtime rule:

- Existing provider emits event.
- Convert to `SignalFingerprint`.
- Match against active `EarbudCalibrationProfile`.
- If exactly one mapping matches, dispatch mapped action.
- If no mapping matches, ignore or show diagnostics.
- If ambiguous, do not dispatch high-risk actions.

Approval/reject must never be routed from an ambiguous signal.

### 5. Persistence And Recovery

Extend `DeviceProfileStorage` with calibration storage:

- active profile per hashed earbud device plus phone model
- calibration history
- profile invalidation after Android upgrade, app upgrade, provider change, or repeated runtime misses
- export redacted calibration diagnostics

If earbuds reconnect with no profile, Home/Device screen should show `Calibration required`, not `Ready`.

## Implementation Steps

1. Add calibration data classes and tests.
2. Add `SignalFingerprint` normalization from `EarbudSignalEvent` plus `MediaButtonEventTelemetry`.
3. Build `EarbudCalibrationEngine` with repeatability, collision, timeout, and retry logic.
4. Extend setup wizard into multi-gesture calibration.
5. Add gesture mapping screen and persist `gestureActionMap`.
6. Add `CalibratedGestureRouter` before `RelayService.handleGestureSignal`.
7. Update capability matrix so proven status comes from calibration profile, not provider assumptions.
8. Add diagnostics export for calibration profile and recent unmatched signals.
9. Add T1/T1_PCM tests for calibration logic and router behavior.
10. Add T4 physical proof requirement: calibrated wake gesture must trigger STT, TTS, and barge-in on the same profile.

## Code Touchpoints

- `RelayModels.kt`: add calibration state to `RelayUiState`.
- `DeviceCapabilityMatrix.kt`: link capability status to calibration result.
- `DeviceProfileStorage.kt`: persist calibration profiles.
- `SetupCapabilityAssessment.kt`: stop treating generic fallback observations as enough for full proof.
- `SignalProviderRegistry.kt`: expose all provider events to calibration engine.
- `MediaButtonTapDetector.kt`: make tap windows configurable from calibration data.
- `RelayGestureRouting.kt`: route through calibrated mappings.
- `RelayService.kt`: dispatch calibrated actions, reject ambiguous approvals.
- `SetupWizardScreen.kt`: replace single wake test with guided multi-gesture capture.
- `DeviceScreen.kt`: show active mapping, confidence, and rerun calibration.
- `DiagnosticExport.kt`: include redacted calibration profile and unmatched signal history.

## Reliability Rules

- A gesture is `proven` only after repeatable observation and a successful route proof.
- A fallback path is allowed, but it must be labeled fallback.
- Unsupported gestures must be hidden from the mapping screen.
- Ambiguous gestures must be blocked for approval/reject.
- Runtime misses should degrade readiness after repeated failures.
- Recalibration must be one tap away from Device screen.
- Profiles are per earbud plus phone combination, not global.

## Acceptance Criteria

Beta-ready means:

- Unknown earbuds can complete guided calibration without model-specific code.
- User can assign actions only to detected gestures.
- App routes runtime gestures through the saved profile.
- Setup cannot mark Ready unless calibration plus speech proof passes.
- Diagnostics explain which signal was expected, observed, missed, or ambiguous.
- T4 proof includes `calibrationProfileId`, calibrated gesture used, route state, STT result, TTS result, and barge-in result.

## Non-Goals

- Do not remove vendor providers.
- Do not replace Android MediaSession.
- Do not replace Generic Bluetooth headset detection.
- Do not replace physical T4 proof.
- Do not claim universal support from model names alone.

## Release Priority

Build this before expanding more vendor providers. Vendor-specific integrations improve richness, but calibration is what makes the product usable with unknown earbuds.
