# Unknown Earbud Calibration Implementation Status Audit

Generated: 2026-05-27

Source plan: [`docs/35-unknown-earbud-calibration-implementation-plan.md`](35-unknown-earbud-calibration-implementation-plan.md)

## Executive Summary

The calibration feature is partially scaffolded, but it is not yet beta-ready against the plan.

The codebase now contains calibration models, a calibration engine, a router, basic setup UI, profile persistence hooks, device-screen status, diagnostics output, and unit tests. Those pieces are useful foundations.

However, the implementation still falls short of the product contract in several important ways:

- Route proof is stubbed as successful when saving a calibration profile.
- Runtime still allows raw wake/interrupt provider routing when no calibration profile exists.
- Users cannot assign actions to proven gestures.
- MediaSession keycode/action telemetry is not part of calibration fingerprints.
- Profiles are keyed by device model plus phone model, not a hashed earbud identity plus phone.
- Capability-matrix proof still comes from provider/setup assumptions, not the calibration profile.
- Diagnostics do not include recent unmatched signals or expected/observed/missed detail.
- T4 proof schema/validation does not prove calibrated gestures on the same profile.

## Status Legend

| Status | Meaning |
|---|---|
| Implemented | The code appears to satisfy the plan item in production flow. |
| Partial | Some code exists, but important plan requirements are missing. |
| Stub | Shape exists, but the behavior is fake, hardcoded, or not connected to real proof. |
| Missing | No meaningful implementation found. |

## Requirement Matrix

| Plan Area | Status | Evidence | Gap |
|---|---|---|---|
| Calibration domain model | Partial | `CalibrationModels.kt` defines `EarbudCalibrationProfile`, `CalibratedGesture`, `SignalFingerprint`, `GestureActionMap`, `RouteProofResult`. | `confidence` is derived, not a persisted field. `observed provider event` is not stored as a distinct event. MediaSession keycode/action fields exist but are not populated by calibration. Bud side is nullable, not explicitly `unknown`. |
| Calibration engine | Partial | `EarbudCalibrationEngine.kt` opens per-gesture windows, collects provider events, supports timeout, repeatability, and collision helpers. | Uses a new `SignalProviderRegistry` from `RelayViewModel.startCalibration()` instead of the existing service registry. No keycode/timing debounce beyond provider-level debouncer. Collision detection still depends on normalized `gestureType`, so same physical signal across different requested gestures can be missed. |
| Guided setup UX | Partial | `SetupWizardScreen.kt` has a calibration phase with waiting/detected/retry/timeout/ambiguous labels. | No real device-check card for connected Bluetooth headset and primed MediaSession. No per-gesture result summary. No fallback-only labeling. No left/right variants. Triple tap is not in the default calibration gesture list. Skip calibration is still exposed in beta flow. |
| Route proof after wake gesture | Stub | `RelayViewModel.buildCalibrationProfile()` saves `RouteProofResult(isSuccess = true)`. | STT, TTS, and barge-in are not actually proven before the calibration profile becomes runtime-ready. The `sttSuccess`, `ttsSuccess`, and `bargeInSuccess` fields default to false. |
| User gesture mapping screen | Missing | `RelayViewModel.buildCalibrationProfile()` hardcodes `SINGLE_PRESS -> WAKE_AND_LISTEN`, `DOUBLE_PRESS -> INTERRUPT`, `LONG_PRESS -> APPROVE`. | No UI for assigning actions only to proven gestures. No reject/cancel/no-action choices. Unsupported gestures are not hidden from a mapping screen because no mapping screen exists. |
| Runtime calibrated router | Partial | `CalibratedGestureRouter.kt` matches events against a ready profile and returns a mapped action only for exactly one match. | `RelayService.handleSignalEvent()` still falls back to raw wake/interrupt routing when no profile exists. The plan says unknown earbuds must be treated as unknown until calibrated and only saved calibrated mappings should route runtime gestures. |
| Approval/reject ambiguity block | Partial | `RelayService` now rejects approval gestures when no profile exists or `canRouteApproval()` is false. | This covers gesture-originated approvals, but approval mapping itself is hardcoded and route proof is stubbed, so high-risk approval can still be enabled by a fake-ready profile. |
| Persistence: active profile per earbud plus phone | Partial | `DeviceProfileStorage.saveDeviceCalibrationProfile()` stores by `calibration_profile_${deviceModel}_${phoneModel}`. | This is not a hashed earbud identity. Two same-model earbuds on the same phone can share/overwrite calibration. `deviceAddressHash` is populated from device model, not a redacted address hash. |
| Persistence: calibration history | Partial | `DeviceProfileStorage.recordCalibrationHistory()` exists. | No production call path writes history when saving a profile. |
| Profile invalidation | Missing | No app upgrade, Android upgrade, provider change, or runtime-miss invalidation path found. | Profiles can remain trusted after conditions that the plan says must invalidate them. |
| Runtime misses degrade readiness | Missing | No recent unmatched-signal or miss counter state found. | Repeated runtime misses do not degrade readiness. |
| Device screen calibration status | Partial | `DeviceScreen.kt` shows calibrated/incomplete/not-calibrated states and a recalibrate action. | It does not show the active mapping details beyond a count. |
| Home screen calibration-required state | Partial | `SpeakNowReadiness` blocks when `calibrationRequired` is true or a profile is not ready. | If no profile exists and `calibrationRequired` has not been set, Home can still depend on other readiness signals. Runtime routing also still accepts raw wake/interrupt without a profile. |
| Capability matrix linked to calibration | Partial | `DeviceCapabilityEntry` has `calibrationProfileId`; `RelayViewModel.linkCalibrationProfileToCapabilityEntry()` writes it. | `wakeGesture`, `interruptGesture`, and `approveRejectGesture` still come from setup/provider observations. Proven status does not derive from the calibration profile. |
| Setup proof/readiness gates | Partial | `RelayViewModel.completeSetup()` checks `calibrationReady` and proof-run status. | Calibration readiness can be satisfied by the stubbed route proof. Setup can also be skipped/degraded, which is not the beta-user contract. |
| Diagnostics export | Partial | `DiagnosticExport.kt` includes a redacted calibration profile, gesture mappings, confidence, and route-proof pass flag. | No recent unmatched signal history. No expected/observed/missed/ambiguous per-step diagnostics. Route-proof pass can reflect the stubbed success. |
| T1/T1_PCM calibration tests | Partial | Unit tests exist for `EarbudCalibrationEngine` and `CalibratedGestureRouter`. | No evidence of T1/T1_PCM proof-level tests that exercise the real setup/router path end to end. |
| T4 physical proof requirement | Missing | `docs/proof-run-schema.json` and `scripts/validate-proof-run.ts` enforce physical Bluetooth session counts and barge-in counts. | They do not require `calibrationProfileId`, calibrated gesture used, or proof that STT/TTS/barge-in happened on the same calibration profile. |
| MediaButtonTapDetector configurable windows | Missing | `MediaButtonTapDetector.kt` hardcodes 400 ms multi-tap window and 700 ms long-press threshold. | Tap windows are not configurable from calibration data. |

## Product Contract Status

| Contract Step | Status | Notes |
|---|---|---|
| 1. Connect earbuds | Partial | Provider registry detects device state, but calibration flow does not enforce a clear Bluetooth-headset-connected precheck. |
| 2. Run guided calibration | Partial | Calibration phase exists in setup wizard. Skip buttons still exist. |
| 3. Follow timed instructions for single/double/triple/long and sides | Partial | Single/double/long are attempted by default. Triple only has UI label support, not default calibration. Side variants are not implemented. |
| 4. Record actual Android/provider signals | Partial | Provider events are recorded. MediaButton telemetry details are not attached to fingerprints. |
| 5. Show detected/missed/ambiguous/fallback-only | Partial | Live status label exists. No summary, no fallback-only labeling, and ambiguous status is not surfaced from final collision results. |
| 6. User assigns actions to proven gestures | Missing | Actions are hardcoded. |
| 7. Save device/phone-specific profile and use at runtime | Partial | Profiles save and router can use them, but keying is not hashed earbud identity and runtime bypass exists when no profile is active. |
| No unobserved signal advertised as reliable | Partial | Calibration uses repeat count, but route proof is stubbed and capability status can still be provider-assumption-based. |

## Detailed Findings

### P0: Calibration Route Proof Is Stubbed

`RelayViewModel.buildCalibrationProfile()` creates:

```kotlin
routeProof = com.openclaw.relay.calibration.RouteProofResult(isSuccess = true)
```

This marks the profile ready without proving STT, TTS, or barge-in on the calibrated wake gesture. The profile can pass `EarbudCalibrationProfile.isReadyForRuntime()` because it only checks `routeProof?.isSuccess == true`.

Impact:

- Setup can mark calibration-ready without real speech proof.
- Runtime can trust a profile that never proved the audio route.
- Diagnostics can report route proof passed even when no route proof ran.

Expected:

- Build `RouteProofResult` from real setup/proof telemetry.
- Require `sttSuccess`, `ttsSuccess`, and `bargeInSuccess` for full success.
- Tie the proof to the selected calibrated wake gesture and profile id.

### P0: Runtime Still Routes Uncalibrated Wake/Interrupt When No Profile Exists

`RelayService.handleSignalEvent()` only ignores unmatched wake/interrupt events when `currentState.calibrationProfile != null`. If there is no profile, it still creates a legacy `RelayWakeSignal` and calls `handleGestureSignal()`.

Impact:

- Unknown earbuds can trigger wake/interrupt before calibration.
- The product can behave as if fallback observations are reliable.
- This violates the plan's runtime rule: no mapping means ignore or diagnostics.

Expected:

- If calibration is required or no ready profile exists, provider wake/interrupt should not dispatch actions.
- Manual push-to-talk may remain separate, but earbud/provider events should require calibrated routing.

### P1: Fingerprints Do Not Include MediaButton Keycode/Action Data

`SignalFingerprint` has `keyCode` and `keyAction`, but `EarbudCalibrationEngine.normalizeEvent()` only fills provider id, gesture type, bud side, and latency.

Impact:

- Calibration cannot distinguish different MediaSession keys that normalize into similar gesture events.
- Collision detection is weaker than the plan requires.
- Diagnostics cannot explain actual keycode/action/timing for MediaSession paths.

Expected:

- Feed `MediaButtonEventTelemetry` into calibration, either through provider events or a parallel event source.
- Persist redacted key label/action/timing in `SignalFingerprint`.
- Use that data for duplicate rejection and collision detection.

### P1: Collision Detection Is Too Gesture-Type-Centric

The collision check requires matching `providerId` and `gestureType`. During calibration, requested gestures are also filtered by `gestureType`, which can hide the exact class of collision the plan calls out: single and double tap producing the same underlying signal.

Impact:

- Ambiguous gestures can be treated as proven.
- Approval/reject can be mapped to an ambiguous physical signal if the normalized gesture type masks the collision.

Expected:

- Compare the underlying observed fingerprint, not the requested or already-interpreted gesture type.
- Treat same provider/key/action/timing pattern as a collision even when requested gestures differ.

### P1: Profile Identity Is Not Per Earbud

`DeviceProfileStorage` keys device calibration as `calibration_profile_${deviceModel}_${phoneModel}`. `deviceAddressHash` is set from `currentDevice.first`, which is the model label.

Impact:

- Same-model earbuds on one phone can share a profile incorrectly.
- Replaced earbuds can inherit stale gesture mappings.
- The stored `deviceAddressHash` is not actually a hash.

Expected:

- Store profiles by a redacted stable earbud identity plus phone model.
- If a stable address is unavailable, mark the profile as model-scoped/fallback and do not claim device-specific certainty.

### P1: Capability Matrix Still Proves From Provider Assumptions

`DeviceCapabilityEntry` has `calibrationProfileId`, but capability statuses are still computed from provider/setup observations. `buildCapabilityEntryFromSetup()` can mark direct providers as `PROVEN` and fallback providers as `FALLBACK_PROVEN` without using calibration profile results.

Impact:

- Capability status can disagree with calibration status.
- Setup/device screens may imply readiness from provider observations instead of calibrated mappings.

Expected:

- Wake/interrupt/approval proven status should derive from calibrated gestures plus route proof.
- Provider observations should be shown as observed/fallback until calibration promotes them.

### P1: T4 Proof Does Not Include Calibration Evidence

The proof-run schema requires route/session fields, but not:

- `calibrationProfileId`
- calibrated gesture used
- matched calibrated action
- proof that STT/TTS/barge-in occurred on the same calibration profile

Impact:

- A T4 artifact can pass without proving the calibration system.
- The acceptance criterion from the plan is not enforceable.

Expected:

- Extend `docs/proof-run-schema.json`.
- Extend `scripts/validate-proof-run.ts`.
- Include calibration fields in generated proof artifacts.

### P2: Mapping UX Is Missing

The mapping screen is not implemented. The app auto-maps gestures in `RelayViewModel.buildCalibrationProfile()`:

- `SINGLE_PRESS -> WAKE_AND_LISTEN`
- `DOUBLE_PRESS -> INTERRUPT`
- `LONG_PRESS -> APPROVE`

Impact:

- Users cannot assign actions.
- Reject/cancel/no-action choices are not available.
- Unsupported gestures cannot be hidden from a non-existent mapping UI.

Expected:

- Add a mapping phase after capture and before profile save.
- Only proven, non-ambiguous gestures should be selectable.
- Approval/reject should require explicit user choice and should be blocked for ambiguous/fallback-only signals.

### P2: Diagnostics Are Incomplete

Diagnostic export includes redacted profile metadata and mappings, but not recent unmatched signals or per-step expected/observed/missed detail.

Impact:

- Support cannot explain why a runtime gesture did nothing.
- Users cannot distinguish unsupported, missed, ambiguous, or fallback-only signals after the fact.

Expected:

- Add a bounded recent unmatched signal history.
- Export expected fingerprint, observed fingerprint, route/mapping result, and reason.

## Stubs And Hardcoded Behavior

| Stub / Hardcode | Location | Why It Matters |
|---|---|---|
| `RouteProofResult(isSuccess = true)` | `RelayViewModel.buildCalibrationProfile()` | Makes unproven profiles runtime-ready. |
| Default gesture-to-action map | `RelayViewModel.buildCalibrationProfile()` | Replaces required user mapping screen. |
| Model string used as `deviceAddressHash` | `RelayViewModel.buildCalibrationProfile()` | Does not identify the actual earbud unit. |
| Profile key uses model plus phone | `DeviceProfileStorage.calibrationProfileKey()` | Not per earbud plus phone. |
| Tap detector windows hardcoded to 400/700 ms | `MediaButtonTapDetector.kt` | Not configurable from calibration. |
| Proof schema lacks calibration fields | `docs/proof-run-schema.json` | T4 cannot enforce calibration acceptance criterion. |

## Missing Items Checklist

- User gesture mapping screen.
- Reject/cancel/no-action mapping flow.
- Triple-tap default capture when useful for approvals.
- Left/right variant calibration when detectable.
- Real route proof bound to selected wake gesture.
- `SignalFingerprint` normalization from `MediaButtonEventTelemetry`.
- Keycode/action/timing duplicate rejection in calibration engine.
- Runtime unmatched-signal history.
- Runtime miss counters and readiness degradation.
- Profile invalidation after Android upgrade.
- Profile invalidation after app upgrade.
- Profile invalidation after provider change.
- Active profile keyed by hashed earbud identity plus phone model.
- Calibration history write path.
- Capability statuses derived from calibration profile.
- T4 proof fields for `calibrationProfileId` and calibrated gesture used.
- T4 validator checks for same-profile STT/TTS/barge-in proof.

## Partially Implemented Items Checklist

- Calibration data classes.
- Calibration engine retry/repeatability/timeout skeleton.
- Calibration phase in setup wizard.
- Runtime router for ready profiles.
- Approval gesture block when no profile exists.
- Device screen calibration status and recalibrate button.
- Diagnostics export of redacted profile.
- Unit tests for calibration engine and router.
- Device capability matrix link to calibration profile id.

## Tests Run

Command:

```powershell
cd android-relay
.\gradlew.bat testDebugUnitTest
```

Result:

- Passed.

Interpretation:

- The current unit tests verify parts of the scaffold.
- They do not prove the full plan contract, especially route proof, user mapping, no-profile runtime blocking, MediaSession fingerprinting, profile invalidation, diagnostics history, or T4 calibration evidence.

## Recommended Implementation Order

1. Replace stubbed `RouteProofResult(isSuccess = true)` with real route proof collected after selecting the wake gesture.
2. Block earbud/provider wake and interrupt routing unless a ready calibration profile exists.
3. Add user mapping UI and persist only user-selected actions for proven gestures.
4. Feed `MediaButtonEventTelemetry` into `SignalFingerprint` and collision detection.
5. Rework profile identity to use hashed earbud identity plus phone model.
6. Move capability proven status to derive from calibration results.
7. Add unmatched-signal history, miss counters, and readiness degradation.
8. Extend T4 proof schema, artifact generation, and validator with calibration profile/gesture evidence.
9. Add integration tests covering setup -> calibration -> mapping -> route proof -> runtime dispatch.

## Bottom Line

The implementation is no longer empty, but it is still best described as a calibration scaffold. The highest-risk gap is that the current code can create a ready-looking profile using a fake route proof, while the broader product contract still requires real user mapping, device-specific profile identity, strict calibrated runtime routing, and T4 proof tied to the same calibration profile.
