# Reliability UX Implementation Audit

Date: 2026-05-19

Audited against:

- [25-reliability-ux-product-audit.md](25-reliability-ux-product-audit.md)
- Current Android relay implementation and changed files in this working tree

## Verdict

Status: **not product-ready yet**.

The implementation moves in the right direction: supported-device statuses are less overconfident, endpoint hint plumbing exists, VAD/RMS telemetry is richer, proof-run exports include more fields, diagnostic rolling summaries exist, and vendor protocol provenance was started.

The blockers are now semantic rather than structural. The app can collect more proof, but it still does not consistently enforce that proof before showing setup as complete or presenting fallback behavior as proven support.

## P0 Findings

### 1. Setup completes even when the proof run fails or never proves the full loop

`RelayViewModel.testStt()` starts a one-session proof run, computes `setupPassed`, then ignores it and always marks setup complete:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt:611`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt:676`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt:721`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt:722`

`RelayStateStore.setSetupPhase()` hides the setup wizard whenever phase is `COMPLETE`, and `DeviceScreen` shows "Setup complete" / "Ready with fallback":

- `android-relay/app/src/main/java/com/openclaw/relay/RelayStateStore.kt:484`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayStateStore.kt:488`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/DeviceScreen.kt:332`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/DeviceScreen.kt:334`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/DeviceScreen.kt:337`

Why this matters:

- The plan requires setup to prove pairing, wake, route, STT, TTS, and interruption or save a degraded profile.
- Current setup can become complete after only one STT proof target and no required TTS interruption proof.
- A failed or incomplete proof can still advance the user into a completed setup state.

Required fix:

- Separate `SETUP_COMPLETE_PROVEN` from `SETUP_COMPLETE_DEGRADED`, or keep `COMPLETE` only for full pass.
- Do not hide the wizard or show "Ready" language when `proofRun.status != PASSED`.
- Require the chosen release gate separately from the quick setup gate. A quick setup can be "usable", but the product-ready claim must still require the 20-session proof matrix.

### 2. Fallback-proven support is not represented in the in-app capability model

The plan adds `fallback_proven` to the JSON matrix, but `DeviceCapabilityEntry` still only supports `PROVEN`, `OBSERVED`, `UNSUPPORTED`, and `UNPROVEN`:

- `android-relay/app/src/main/java/com/openclaw/relay/device/DeviceCapabilityMatrix.kt:46`
- `android-relay/app/src/main/java/com/openclaw/relay/device/DeviceCapabilityMatrix.kt:47`
- `android-relay/app/src/main/java/com/openclaw/relay/device/DeviceCapabilityMatrix.kt:52`

`SetupCapabilityAssessment` treats Android MediaSession as a direct hardware wake provider, so fallback media controls can be stored as `PROVEN`:

- `android-relay/app/src/main/java/com/openclaw/relay/device/SetupCapabilityAssessment.kt:5`
- `android-relay/app/src/main/java/com/openclaw/relay/device/SetupCapabilityAssessment.kt:6`
- `android-relay/app/src/main/java/com/openclaw/relay/device/SetupCapabilityAssessment.kt:40`
- `android-relay/app/src/main/java/com/openclaw/relay/device/SetupCapabilityAssessment.kt:41`

The UI also collapses `PROVEN` and `OBSERVED` into the same "Observed" chip:

- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/DeviceScreen.kt:447`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/DeviceScreen.kt:448`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/DeviceScreen.kt:449`

Why this matters:

- The product cannot distinguish "vendor-rich direct integration worked" from "Android fallback worked."
- This reintroduces the claim-discipline issue the plan was meant to remove.

Required fix:

- Add `FALLBACK_PROVEN` or store a separate `proofPath`/`inputPath` per capability.
- Treat `android_media_session` as fallback-proven, not vendor/direct-proven.
- Display `PROVEN`, `FALLBACK_PROVEN`, `OBSERVED`, and `UNPROVEN` distinctly.

### 3. Proof-run status can pass while audio probe or barge-in failures exist

`VoiceProofRun.status` is resolved only from speech session success:

- `android-relay/app/src/main/java/com/openclaw/relay/VoiceProofRun.kt:83`
- `android-relay/app/src/main/java/com/openclaw/relay/VoiceProofRun.kt:148`
- `android-relay/app/src/main/java/com/openclaw/relay/VoiceProofRun.kt:157`

Audio probe failures and missed interruption targets are added only to `summary.failureReasons`:

- `android-relay/app/src/main/java/com/openclaw/relay/VoiceProofRun.kt:181`
- `android-relay/app/src/main/java/com/openclaw/relay/VoiceProofRun.kt:185`
- `android-relay/app/src/main/java/com/openclaw/relay/VoiceProofRun.kt:189`

Why this matters:

- A run can have `status == PASSED` while `failureReasons` contains `audio_probe_no_signal`, `audio_probe_read_failed`, or `interruption_target_missed`.
- Setup uses `proofRun.status` as the pass signal, so these failure reasons are not hard gates.

Required fix:

- Add a `proofRun.isReleaseReady` or `summary.hasBlockingFailures` gate.
- Use it anywhere the UI or setup decides "Ready".
- Add tests proving a successful STT session plus no-signal probe does not produce a ready profile.

### 4. Diagnostic export can still leak exact Bluetooth device names

Default export uses `currentDeviceState.displayName` and capability `deviceModel` after only MAC/hex-like redaction:

- `android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt:183`
- `android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt:186`
- `android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt:193`
- `android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt:201`
- `android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt:260`
- `android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt:265`

Why this matters:

- The plan says exact Bluetooth device name should not be included by default.
- Names like "Shreyas's AirPods Pro" can pass through as `modelFamily` or provider `deviceModel`.

Required fix:

- Export resolved model family only, not raw display name.
- Hash raw device name/address separately when needed.
- Add a test with a personal device name such as `Alice's AirPods Pro` and assert it is not present in default JSON.

## P1 Findings

### 5. The assistant fallback button is visible but not wired

The setup wizard shows "Use assistant fallback", but its click handler is a no-op:

- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/SetupWizardScreen.kt:324`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/SetupWizardScreen.kt:326`

Required fix:

- Wire it to the real assistant fallback path, or remove it until it works.
- A fallback action that appears clickable but does nothing is worse than not showing it.

### 6. Supported-device features are still too claim-like

The matrix model statuses were corrected from `code_complete` to `implemented_unverified` or `scaffolded`, which is good. But provider-level `features` still declare rich capabilities as booleans without per-capability proof status:

- `docs/supported-devices-matrix.json:35`
- `docs/supported-devices-matrix.json:39`
- `docs/supported-devices-matrix.json:56`
- `docs/supported-devices-matrix.json:60`
- `docs/supported-devices-matrix.json:76`
- `docs/supported-devices-matrix.json:79`

Required fix:

- Convert provider `features` into per-capability statuses, not booleans.
- Example: `ancControl: "implemented_unverified"` or `touchConfiguration: "scaffolded"`.

## Completed Or Partially Completed Plan Items

| Plan item | Status | Notes |
| --- | --- | --- |
| Replace broad `code_complete` statuses | Partial | Main model statuses improved, but feature booleans still overstate support. |
| Add `possibleCompleteSilenceMs` request path | Done | Contract, platform wrapper, and recognizer plumbing exist. |
| Add `rmsFramesAboveNoiseFloor` | Done | Metrics and VAD observation now carry it. |
| Add no-signal/read-failed proof summary flags | Partial | Flags exist, but do not affect proof-run pass/readiness. |
| Export `interruptionTargetMetCount` | Done | Included in redacted proof-run export. |
| Add diagnostic privacy tests | Partial | Raw-route toggle exists, but exact device-name redaction is still weak. |
| Add provenance report | Partial | Report exists, but release still blocked by `librepods_airpods` GPL boundary. |
| Keep Sherpa experimental | Done | Placeholder remains gated/fallback-only. |

## Verification

Android:

- `.\gradlew.bat :app:testDebugUnitTest` passed.
- `.\gradlew.bat :app:assembleDebug` passed.
- `.\gradlew.bat :app:lintDebug` passed.

Node/Vitest:

- `npm test` failed: 119 passed, 4 failed.
- Failures are in OpenClaw gateway/client tests and appear environment/dependency related:
  - missing `@mariozechner/pi-agent-core`
  - gateway health never reached expected state

These Node failures do not appear caused by the Android reliability-plan changes, but they still block a clean full-repo verification gate.

## Recommended Next Patch Order

1. Fix setup gating.
   - Do not set `SetupPhase.COMPLETE` unless the proof result satisfies the intended setup contract.
   - Preserve a degraded setup state with explicit fallback labels.

2. Add fallback-aware capability status.
   - Add `FALLBACK_PROVEN` or equivalent path metadata.
   - Update `SetupCapabilityAssessment`, `DeviceCapabilityMatrix`, and `DeviceScreen`.

3. Make proof-run readiness include blocking failure reasons.
   - No-signal audio probe, read failures, and missed barge-in target should block "Ready" claims.

4. Harden diagnostic name redaction.
   - Export family/model, not raw Bluetooth display names.

5. Wire or remove assistant fallback button.

6. Convert supported-device `features` booleans to proof statuses.

## Bottom Line

The implementation is a solid instrumentation pass, but not yet a reliable-product pass. The next milestone should be enforcing the proof contract in setup, capability storage, and UI language. Once fallback-proven and fully proven are distinct in the app, the rest of the plan becomes much easier to trust.

