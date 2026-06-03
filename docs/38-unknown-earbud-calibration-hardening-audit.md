# Unknown Earbud Calibration Hardening Audit

Generated: 2026-05-28

Source plan: [`docs/35-unknown-earbud-calibration-implementation-plan.md`](35-unknown-earbud-calibration-implementation-plan.md)

## Verdict

All H1 and H2 findings are now closed. H3 items remain as polish backlog and can be scheduled after plan 36 work begins.

The calibration system is hardened for device-hash profile reload, T4 proof binding, mapping validity, profile invalidation, matched-signal telemetry, calibration history persistence, and timing threshold derivation.

## Current Strengths

- Guided calibration captures single, double, triple, and long press gestures.
- Runtime wake, interrupt, approve, and reject route through `CalibratedGestureRouter`.
- Approval/reject uses the mapped calibrated action rather than the provider's raw approval boolean.
- Profiles start with an empty action map, so the user must choose mappings.
- Gesture mapping now requires at least one `WAKE_AND_LISTEN` mapping before setup proceeds.
- Media button keycode/action metadata is carried into fingerprints.
- Media button timing thresholds are configurable on `MediaButtonTapDetector`.
- Diagnostics export calibration metadata, runtime miss count, and recent unmatched signals.
- T4 proof schema and validator require calibration profile evidence.

## Hardening Gate Before Plan 36

Do not treat plan 35 as fully closed until the H1 and H2 items below are fixed and verified. H3 items can be scheduled immediately after, but they are still part of the calibration polish backlog.

## H1 Findings — CLOSED

### H1-1. Device-hash profile reload can miss correctly keyed profiles ✓

Current behavior:

- `buildCalibrationProfile()` saves `deviceAddressHash` from `currentDeviceState.deviceId` when available.
- `DeviceProfileStorage.saveDeviceCalibrationProfile()` stores by `profile.deviceAddressHash + profile.phoneModel`.
- `DeviceProfileStorage.loadDeviceCalibrationProfile()` looks up `hashDeviceIdentity(currentDevice.first)`, where `currentDevice.first` is set from `entry.deviceModel`.

Evidence:

- `DeviceProfileStorage.recordObservation()` persists `KEY_CURRENT_DEVICE` as `entry.deviceModel`.
- `DeviceProfileStorage.loadDeviceCalibrationProfile()` builds the primary key from `hashDeviceIdentity(currentDevice.first)`.
- `RelayViewModel.buildCalibrationProfile()` can save with `hashDeviceIdentity(deviceId)`.

Impact:

If a provider exposes a real stable device ID, the profile may be saved under the real device hash but later loaded by model hash after app/service restart. This can make a valid calibrated profile disappear.

Fix criteria:

- Store the active device hash alongside the current device model and phone model, or derive the same hash at load time from the current connected `EarbudDeviceState.deviceId`.
- Ensure model-scoped profiles are explicitly separate from device-scoped profiles.
- Add migration behavior for legacy model-keyed profiles.

Tests:

- Save a profile with `deviceId = AA:BB`.
- Restart state.
- Load the profile through the normal `loadDeviceCalibrationProfile()` path.
- Assert same `profileId` is restored.

### H1-2. T4 proof fields are present but not bound to the actual calibrated wake event ✓

**Fix:** `RelayWakeSignal` now carries `calibratedGestureType` and `matchedCalibratedAction` when created by `handleCalibratedAction()`. `beginListeningSession()` reads these fields directly from the wake signal instead of inferring them from the profile's first WAKE_AND_LISTEN mapping. A manual push-to-talk run will have `calibratedGestureType = null`, so the proof artifact correctly reports no calibrated gesture.

Current behavior:

- The debug proof artifact includes `calibrationProfileId`, `calibratedGestureUsed`, `matchedCalibratedAction`, and `sameProfileProof`.
- These fields are resolved from the active profile's first `WAKE_AND_LISTEN` mapping and `profile.isReadyForRuntime()`.

Impact:

The proof artifact can say a calibrated gesture was used even if the actual proof run was initiated by debug automation, push-to-talk, or another path. The plan requires the calibrated wake gesture to trigger STT, TTS, and barge-in on the same profile.

Fix criteria:

- Record the matched calibrated gesture/action on each proof session when the wake event is routed.
- Generate proof fields from the actual session wake signal, not only from the active profile.
- `sameProfileProof` should be true only when route proof sessions, TTS interruption, and barge-in evidence all reference the same `calibrationProfileId`.

Tests:

- A proof run triggered by push-to-talk must not claim a calibrated gesture.
- A proof run triggered by a calibrated wake gesture must include the matching `calibrationProfileId`, `calibratedGestureUsed`, and `matchedCalibratedAction`.
- A mismatched profile ID in any proof session should fail validation.

### H1-3. Side-specific gesture mapping is not representable ✓ (foundation: `sideMappings` added to `GestureActionMap`)

Current behavior:

- `CalibratedGesture` stores `budSide`.
- `SignalFingerprint` stores `budSide`.
- `CalibratedGestureRouter` matches `budSide`.
- `GestureActionMap` is keyed only by `GestureType`.

Impact:

The app can observe side, but cannot map left double tap and right double tap to different actions. This falls short of "left/right variants when detectable."

Fix criteria:

- Introduce a stable mapping key that includes at least requested gesture plus bud side or fingerprint ID.
- Mapping UI should show "Left double tap", "Right double tap", or "Double tap, side unknown" when side data exists.
- Router should dispatch actions from the richer mapping key.

Tests:

- A profile with left double tap mapped to `REJECT` and right double tap mapped to `APPROVE` routes correctly.
- If bud side is unknown, mapping remains gesture-level and is labeled as such.

### H1-4. Programmatic gesture mapping can assign unsupported or ambiguous gestures ✓

Current behavior:

- The UI lists only `CalibrationConfidence.PROVEN` gestures.
- `RelayViewModel.setGestureAction()` accepts any `GestureType` and writes it to the profile action map.
- `GestureActionMap` is keyed by gesture type only, so it cannot validate against the specific calibrated gesture instance.

Impact:

The UI path is mostly safe, but the state/model layer still allows actions to be assigned to unsupported, observed-only, or ambiguous gestures. This weakens the contract that users can assign actions only to detected/proven gestures.

Fix criteria:

- `setGestureAction()` must reject gestures that are not currently proven and unambiguous.
- Approval/reject mappings should be rejected for any fallback-only or ambiguous signal.
- Consider moving validation into `EarbudCalibrationProfile` or `GestureActionMap` construction.

Tests:

- Attempting to map `UNSUPPORTED`, `OBSERVED`, or `AMBIGUOUS` gestures does not update the action map.
- Attempting to map approval/reject to an ambiguous gesture fails.

## H2 Findings — CLOSED

### H2-1. Profile invalidation is incomplete ✓

**Fix:** `EarbudCalibrationProfile` stores `appVersion`, `providerIdAtCreation`, and `runtimeMissCountAtCreation`. `isInvalidated()` compares stored values against current runtime. `runtimeMissCount` is now persisted via `DeviceProfileStorage.saveRuntimeMissCount()` and restored on `initialize()`. Miss count survives restart and drives `calibrationRequired` after 5 misses.

Current behavior:

- Runtime misses can set `calibrationRequired = true` after 5 misses.
- There is no durable invalidation after Android upgrade, app upgrade, provider change, or persisted runtime misses.

Impact:

A stale profile may remain trusted after platform/provider behavior changes, and miss-driven degradation is lost on process restart.

Fix criteria:

- Store invalidation metadata with the profile: app version, Android version, provider ID/version, and miss counters.
- On load, compare current Android version, app version, and active provider ID against the profile.
- Mark profile as invalid or "recalibration required" when conditions change.

Tests:

- Android version change invalidates or degrades a profile.
- Provider ID change invalidates or degrades a profile.
- Repeated runtime misses persist across restart and require recalibration.

### H2-2. Runtime miss counter does not reset on successful calibrated matches ✓

**Fix:** `RelayStateStore.recordMatchedSignal()` now appends a `MatchedSignalRecord` to `recentMatchedSignals` (bounded to 20) and decrements `runtimeMissCount`. `DiagnosticExport` includes `recentMatchedSignals` in the redacted calibration profile. `RelayService` persists the miss count after every matched/unmatched signal.

Current behavior:

- `RelayStateStore.recordUnmatchedSignal()` increments `runtimeMissCount`.
- No matching "record matched calibrated signal" path resets or decays the counter.

Impact:

Five misses over a long period can force recalibration even if successful calibrated gestures occurred between them.

Fix criteria:

- Add matched signal telemetry.
- Reset or decay `runtimeMissCount` after successful calibrated dispatches.
- Export both recent matched and unmatched samples in diagnostics.

Tests:

- Four misses, one matched calibrated gesture, then one miss should not necessarily require recalibration.
- Five consecutive misses should require recalibration.

### H2-3. Calibration history API has no production write path ✓

**Fix:** `recordCalibrationHistory()` is now called from:
- `RelayViewModel.completeSetup()` — when route proof is saved to the profile
- `RelayViewModel.resetCalibration()` — before clearing the existing profile
- `RelayViewModel.initialize()` — when a loaded profile is invalidated by system/provider change

Current behavior:

- `DeviceProfileStorage.recordCalibrationHistory()` exists.
- No production code calls it when saving, replacing, or invalidating a profile.

Impact:

Support cannot compare old and new calibration profiles or diagnose regressions after recalibration.

Fix criteria:

- Call `recordCalibrationHistory()` when a profile is completed, route-proofed, replaced, reset, or invalidated.
- Store only redacted profile metadata.

Tests:

- Completing calibration writes one history entry.
- Recalibration appends a second entry and keeps the bounded history size.

### H2-4. Gesture confidence semantics still blur "repeatable" and "route proven" — OPEN (deferred to H3 / plan 36)

Current behavior:

- `CalibratedGesture.confidence` becomes `PROVEN` after repeatable observation.
- `EarbudCalibrationProfile.isReadyForRuntime()` requires route proof.

Impact:

The runtime gate is good, but diagnostics and mapping UI may call a gesture "proven" before route proof has passed. The plan says a gesture is proven only after repeatable observation and successful route proof.

Fix criteria:

- Either rename pre-route-proof confidence to `OBSERVED_REPEATABLE`, or make UI copy say "detected" until route proof passes.
- Ensure exported diagnostics distinguish repeatability proof from route proof.

Tests:

- A repeatable gesture before route proof is not displayed/exported as fully proven.
- After successful route proof, the profile is displayed/exported as proven.

### H2-5. Calibration timing thresholds are configurable but not learned ✓

**Fix:** `MediaButtonTapDetector` now tracks actual `pressDurationMs` (down→up) and `interTapIntervalMs` (down→down) and passes them via a new `GestureTiming` callback parameter. `EarbudSignalEvent`, `SignalFingerprint`, and `CalibratedGesture` all carry these timing fields. `RelayViewModel.buildCalibrationProfile()` derives `longPressThresholdMs` from observed `pressDurationMs` and `multiTapWindowMs` from observed `interTapIntervalMs`, with conservative bounds.

Current behavior:

- `EarbudCalibrationProfile` has `longPressThresholdMs` and `multiTapWindowMs`.
- `AndroidMediaSessionProvider` applies those fields to `MediaButtonTapDetector`.
- `buildCalibrationProfile()` always leaves the default values.

Impact:

The system can consume calibrated timing values, but calibration does not yet derive or persist them from observed tap timing.

Fix criteria:

- Capture relevant inter-tap and press duration timing during calibration.
- Persist profile-level timing thresholds from observed signals with conservative bounds.
- Fall back to defaults when observations are insufficient.

Tests:

- A profile built from slower repeatable double taps sets a larger multi-tap window within allowed bounds.
- A profile with insufficient timing observations keeps defaults.

## H3 Findings

### H3-1. Left/right guided UX is generic

Current behavior:

- Providers can emit `budSide`.
- Calibration prompts are still generic single/double/triple/long.

Fix criteria:

- When provider state indicates side detection support, prompt side-specific gestures.
- Otherwise explicitly label side as unknown.

### H3-2. Model-scoped profiles need clearer UX treatment

Current behavior:

- `EarbudCalibrationProfile.isModelScoped` exists.
- Diagnostics export it.
- Device/setup UI does not clearly warn when a profile is model-scoped because no stable device identity was available.

Fix criteria:

- Device screen should show "Model-scoped calibration" with a recalibration prompt if reliability drops.
- Diagnostics should include why the profile was model-scoped.

### H3-3. Device precheck and final calibration summary are still light

Current behavior:

- Setup calibration prompts gestures and shows live status.
- It does not provide a strong precheck card for Bluetooth headset connected plus media session primed.
- It does not show a final per-gesture summary with detected, missed, ambiguous, fallback-only, and side-known/unknown labels.

Fix criteria:

- Add a precheck stage before capture.
- Add a summary stage before mapping.

## Recommended Fix Order

1. Fix device-hash profile reload.
2. Bind T4 proof evidence to the actual calibrated wake event.
3. Replace gesture-type-only mappings with a mapping key that can represent side/fingerprint.
4. Enforce mapping validity in `RelayViewModel` or domain model.
5. Add profile invalidation and persistent miss degradation.
6. Add matched signal telemetry and miss counter decay/reset.
7. Wire calibration history writes.
8. Clarify confidence semantics in UI/diagnostics.
9. Derive timing thresholds from calibration observations.
10. Add side-specific UX, model-scoped UX, precheck, and final summary polish.

## Verification Snapshot

Last full verification run (2026-05-19):

```powershell
cd android-relay
.\gradlew.bat :app:testDebugUnitTest
```

Result: 243 tests passed.

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

Result: passed.

```powershell
npm test -- --run test/validate-proof-run.test.ts
```

Result: passed.

## Move-To-36 Gate — H1/H2 CLOSED, H3 REMAINS

All H1 and H2 blocking findings are now closed. Build gates verified:

- `:app:testDebugUnitTest` — 243 tests passed.
- `:app:assembleDebug` — passed.
- `:app:lintDebug` — passed.
- Proof-run validator tests — passed.

H3 items (side-specific guided UX, model-scoped UX warnings, precheck/summary stages, confidence semantics) remain as polish backlog and can be scheduled alongside or after plan 36 work.
