> **Status: Partially superseded.** This document reflects the state of the codebase at the time of its writing. Many findings have since been resolved, and the canonical current readiness assessment is in [`docs/18-perfect-product-completion-audit-and-roadmap.md`](18-perfect-product-completion-audit-and-roadmap.md). Refer to that document for the latest verdict and recommended next steps.

    # End-User Product Readiness Re-Audit

Date: 2026-05-12

## Purpose

This document re-audits the repository after the latest implementation pass against:

- [11-end-user-product-readiness-audit.md](11-end-user-product-readiness-audit.md)
- [13-librepods-end-user-readiness-usage.md](13-librepods-end-user-readiness-usage.md)
- [14-product-readiness-implementation-gap-audit.md](14-product-readiness-implementation-gap-audit.md)

The question is not whether the architecture is moving in the right direction. It is.

The question is:

`is end-user product readiness now perfectly implemented?`

## Verdict

No. DevPods is improved, but it is not perfectly implemented and not end-user ready yet.

The latest code now has much more of the right shape:

- normalized Android signal providers
- a real `librepods_airpods` provider scaffold with BLE and AACP pieces
- setup wizard and capability matrix flow
- hardware context serialization on Android
- route-before-readiness behavior
- pairing-code exchange instead of directly putting the long-lived relay token in the pairing URI/page
- diagnostic export

But the current implementation still has hard blockers:

1. The TypeScript release build fails.
2. Android lint fails with release-quality errors.
3. The new physical provider event names do not match the bridge event schema.
4. LibrePods/AACP stem-press parsing appears byte-index incompatible with the LibrePods reference.
5. Setup still does not prove the exact physical wake -> STT loop.
6. Security audit currently reports critical/high dependency advisories.

## Verification Run

| Check | Result | Notes |
| --- | --- | --- |
| Code graph build | PASS | Full graph parsed 80 files, 734 nodes, 7065 edges |
| `npm test` | PASS | 19 files, 123 tests passed |
| `npm run build` | FAIL | TypeScript compile errors block release build |
| `android-relay\gradlew.bat testDebugUnitTest` | PASS | Command returned success |
| `android-relay\gradlew.bat :app:assembleDebug` | PASS | Command returned success |
| `android-relay\gradlew.bat :app:assembleRelease` | PASS | Release APK assembled |
| `android-relay\gradlew.bat :app:lintDebug` | FAIL | 15 errors, 20 warnings |
| `npm audit --audit-level=moderate` | FAIL | 11 advisories, including 2 critical and 2 high |

Important nuance: green tests do not mean product readiness here. The test suite passes while the TypeScript build fails and while the physical provider path has contract mismatches not covered by tests.

## What Was Actually Fixed Since The Previous Audit

| Previous gap | Current status | Evidence |
| --- | --- | --- |
| Android hardware context used `providerId` instead of bridge `provider` | Fixed | `HardwareContext.kt` now has `@SerialName("provider")` on `providerId` |
| Route readiness was checked before route preparation | Mostly fixed | `RelayService.handleGestureSignal` now calls `prepareListeningRoute()` before `updateListenReadiness()` |
| Duplicate runtime MediaSession ownership | Mostly fixed at runtime | `RelayService` now uses `SignalProviderRegistry.getMediaSessionProvider()`; old `RelayMediaSessionController` remains in source/tests |
| Setup probe did not start provider registry | Fixed | `RelayViewModel.probeDevice` now calls `registry.start()` before `probeAll()` |
| Pairing exposed long-lived relay token directly in page/deep link | Improved | Server now emits `pairingCode` and exchanges it via `/pairing/verify` |
| LibrePods provider was only a stub | Partially improved | `LibrePodsAirPodsProvider`, `AirPodsBleScanner`, and `AacpConnectionManager` now exist |

This is real progress. The remaining issues are more about correctness and proof than missing skeletons.

## P0 Findings

## P0.1 TypeScript release build is broken

### Evidence

`npm run build` fails with these compile errors:

- `src/cli/jarvis-earbuds.ts:92`: `relayToken` is still passed to `buildRelayPairingUri`, but `RelayPairingPayload` no longer accepts it.
- `test/pairing-uri.test.ts:21`: the same stale `relayToken` field exists in the test fixture.
- `src/policy/engine.ts:2`: imports `HardwareContext`, but `src/protocol/schemas.ts` does not export that type.
- `src/jarvis/runtime.ts:511`, `527`, `552`, `574`: `auditLog.append` calls omit `hardwareContext`, but `AuditRecord` now requires it.

Related code:

- `src/pairing/uri.ts:1-5`
- `src/cli/jarvis-earbuds.ts:87-95`
- `src/protocol/schemas.ts:214-222`
- `src/jarvis/runtime.ts:511-582`
- `src/bridge/audit-log.ts:10`

### Product impact

This is a hard release blocker. The repo cannot honestly claim the bridge build/typecheck path is ready while `tsc` fails.

### Required fix

- Update CLI/test pairing call sites to use `pairingCode`, not `relayToken`.
- Export `HardwareContext` from `src/protocol/schemas.ts`.
- Either make `hardwareContext` optional in the `AuditLog.append` input type or pass `hardwareContext: null` in background-task audit records.

## P0.2 Physical provider wake events do not match the bridge contract

### Evidence

The bridge only accepts a fixed set of event names:

- `src/protocol/schemas.ts:4-22`

The Android listening-window router only opens listening for:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayGestureRouting.kt:3-10`

```text
headset_button_single
android_push_to_talk
```

But the new provider path builds wake triggers from the raw enum name:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:263-287`

For a real media-button single press, this produces a trigger like:

```text
single press 
```

Then `buildGestureBridgeEvent` sends that trigger directly as the bridge event:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:528-539`

That event is neither a listening-window trigger nor a valid bridge schema event.

### Product impact

This can break the exact P0 loop:

1. Earbud/media event is observed.
2. UI records a wake provider.
3. Relay does not open the listening window.
4. Bridge rejects or fails the event because the name is not in the schema.

This is worse than a missing feature because it can look like wake was detected while the product flow is still broken.

### Required fix

Normalize provider events before creating `RelayWakeSignal`.

Suggested mapping:

| Provider event | Bridge event |
| --- | --- |
| Media single press wake | `headset_button_single` |
| LibrePods single/long wake | `headset_button_single` or a newly schema-approved event |
| Provider interrupt | `android_autonomy_interrupt` when active work exists, otherwise `android_cancel` if cancelling |
| Provider approve | `android_approve` only in approval state |
| Provider reject | `android_reject` only in approval state |

Add tests that feed `AndroidMediaSessionProvider` and `LibrePodsAirPodsProvider` events into `RelayService` routing and assert the emitted bridge event is schema-valid.

## P0.3 LibrePods/AACP stem-press parser is likely wrong

### Evidence

LibrePods reference parses stem press like this:

- `librepods/android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:129-143`
- `librepods/android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt:241-253`

LibrePods says:

- stem press type is at `data[6]`
- bud side is at `data[7]`
- press type values are `0x05`, `0x06`, `0x07`, `0x08`
- bud side values are `0x01`, `0x02`

Current implementation checks `data[7]` for press type:

- `android-relay/app/src/main/java/com/openclaw/relay/signal/AacpConnectionManager.kt:150-159`

That means it is reading the bud-side byte as the gesture type. A valid right-bud double press can be interpreted as a single press or fail to classify as intended.

### Product impact

The LibrePods path cannot be considered reliable for wake, interrupt, approve, or reject gestures until this is fixed and tested against the reference packet shape.

### Required fix

- Parse type from `data[6]`.
- Parse bud side from `data[7]`.
- Map `0x05 -> SINGLE_PRESS`, `0x06 -> DOUBLE_PRESS`, `0x07 -> TRIPLE_PRESS`, `0x08 -> LONG_PRESS`.
- Preserve left/right bud side in the emitted `EarbudSignalEvent`.
- Add packet-level unit tests using sample 8-byte stem-press packets.

## P0.4 Setup wake/STT tests still do not prove the real physical loop

### Evidence

The setup UI tells the user:

- `android-relay/app/src/main/java/com/openclaw/relay/ui/SetupWizardScreen.kt:93-99`

```text
Tap your earbuds now. The app will record whether the signal arrived.
Button: I tapped my earbuds
```

But `RelayViewModel.testWake` snapshots `previousWake` only after the user presses the button:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt:406-443`

It then calls `wakeAndListen(context)`, which creates a manual push-to-talk wake:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt:244-252`

So if the user tapped before pressing the button, the test can miss the real physical event. If the manual push-to-talk path succeeds, the test can move forward without proving the physical tap.

The STT test also calls `wakeAndListen(context)` and observes any new transcript:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt:447-482`

It does not require the transcript to follow a proven physical wake provider.

### Product impact

This is directly tied to P0.1 and P0.2 from the readiness audit. Setup can still fail to prove:

```text
real earbud gesture -> relay wake -> route prepared -> STT transcript
```

### Required fix

Change setup to an event-driven capture model:

1. User taps `Start wake test`.
2. App begins a capture window and clears/marks previous wake state.
3. UI says `Tap your earbuds now`.
4. App records the next provider event.
5. App only marks wake `PROVEN` if that next event is from a direct physical provider and maps to a schema-valid wake.
6. STT test must begin from that same physical wake path, not manual push-to-talk.

## P0.5 Android lint fails with release-quality errors

### Evidence

`android-relay\gradlew.bat :app:lintDebug` fails with:

```text
15 errors, 20 warnings
```

Notable errors:

- `RelayService.kt:112`: missing `super.onStartCommand`
- `RelayPairing.kt:176`: `URLDecoder.decode(value, StandardCharsets.UTF_8)` requires API 33 while minSdk is 31
- `AndroidMediaSessionProvider.kt:123`, `131`, `141`, `144`: Media3 unstable API opt-in errors
- `RelayMediaSessionController.kt:78`, `86`, `98`, `101`: same unstable API opt-in errors in old controller
- `RelayService.kt:105`, `793`: Media3 unstable API opt-in errors
- `AndroidManifest.xml:6`: camera permission lacks `uses-feature android.hardware.camera required=false`

Warnings also include:

- exported debug receiver without permission
- exported service without permission
- debug cleartext base config
- obsolete SDK checks

### Product impact

The Android app can assemble, but it is not lint-clean. For end-user readiness, that means the Android surface still has correctness, compatibility, and security hygiene debt.

### Required fix

- Call `super.onStartCommand` or document/suppress only if safe for `MediaSessionService`.
- Replace the API 33 URL decoder call with a minSdk-safe overload or compatibility wrapper.
- Add explicit Media3 `@OptIn` annotations where the unstable APIs are intentional.
- Add `<uses-feature android:name="android.hardware.camera" android:required="false" />`.
- Review exported service/debug receiver warnings rather than suppressing them blindly.

## P0.6 Real-device supported matrix still says unproven

### Evidence

`docs/supported-devices-matrix.json` contains only one entry:

- device: `Generic Bluetooth`
- phone: `RMX3990`
- provider: `android_media_session`
- wake: `UNPROVEN`
- interrupt: `UNPROVEN`
- approve/reject: `UNPROVEN`
- STT after wake: `UNPROVEN`

The notes explicitly say the real earbud-origin wake remains unproven.

### Product impact

The repo still cannot make an end-user support claim. It can claim internal prototype progress, but not supported-device readiness.

### Required fix

Run a real setup validation session and update the matrix only for capabilities actually observed on the phone/earbud pair.

## P1 Findings

## P1.1 Security audit currently fails

### Evidence

`npm audit --audit-level=moderate` reports:

- 11 vulnerabilities
- 2 critical
- 2 high
- critical advisory for `@mistralai/mistralai` through `openclaw`
- moderate advisory for `@anthropic-ai/sdk` through `openclaw`
- high advisories for `fast-uri` and `fast-xml-builder`

### Product impact

Even if some are transitive and not reachable in this product path, critical/high advisories are not compatible with a "perfectly implemented" readiness claim.

### Required fix

- Run `npm audit fix` if it produces a safe lockfile update.
- If `openclaw` needs a patched release, upgrade it or document a temporary mitigation.
- Record reachability for any advisory that cannot be immediately fixed.

## P1.2 Final transcript events drop hardware context

### Evidence

The wake acknowledgement event includes hardware context through `buildGestureBridgeEvent`:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:528-539`

But the final transcript event sent after speech recognition does not carry the original wake hardware context:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:374-385`

The autonomy interrupt transcript also drops it:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:388-399`

### Product impact

The bridge receives hardware context for the wake prompt but not for the actual voice command. That weakens audit trails, policy experiments, and compatibility diagnostics.

### Required fix

Thread the original `RelayWakeSignal.hardwareContext` into:

- `startListeningSession`
- `startAutonomyInterruptListeningSession`
- final transcript `RelayBridgeEvent`

## P1.3 Diagnostic export can leak bridge URLs through error messages

### Evidence

The diagnostic export comments say it strips bridge URLs:

- `android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt:15-19`

But it includes raw recent error prefixes:

- `android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt:118-122`

The state store can create an error containing the bridge base URL:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayStateStore.kt:56-62`

### Product impact

A shared diagnostic export can leak LAN hostnames/IPs even though the docstring says it does not. This is a trust and privacy gap.

### Required fix

Apply URL/token/workspace redaction to all diagnostic error fields, not only device model strings.

## P1.4 Provider capability profiles are still optimistic

### Evidence

`AndroidMediaSessionProvider` marks media-session gestures as `PROVEN` based on session availability:

- `android-relay/app/src/main/java/com/openclaw/relay/signal/AndroidMediaSessionProvider.kt:30-51`
- `android-relay/app/src/main/java/com/openclaw/relay/signal/AndroidMediaSessionProvider.kt:219-246`

`GenericBluetoothHeadsetProvider` marks generic headset gestures as `PROVEN` when a headset is connected:

- `android-relay/app/src/main/java/com/openclaw/relay/signal/GenericBluetoothHeadsetProvider.kt:37-54`
- `android-relay/app/src/main/java/com/openclaw/relay/signal/GenericBluetoothHeadsetProvider.kt:154-187`

### Product impact

This can overstate support before the user physically verifies gesture delivery. It also conflicts with the evidence-based posture in the readiness docs.

### Required fix

Use `SUPPORTED_BUT_NOT_OBSERVED` or `OBSERVED` for provider self-reported capabilities until the setup flow sees a real event.

## P1.5 Pairing-code flow is better but still incomplete

### Evidence

The bridge now generates a pairing code:

- `src/bridge/server.ts:28-38`

The code is consumed once:

- `src/bridge/server.ts:96-100`

But there is no explicit expiry time, rotation window, or revoke/reset UI. The CLI and tests also still contain stale direct-token pairing call sites that break the TypeScript build.

### Product impact

This is much better than the previous long-lived-token page, but it is not a complete production trust model.

### Required fix

- Add pairing-code expiry.
- Add regeneration/revoke behavior.
- Add tests for expired and reused codes.
- Fix stale TypeScript call sites.

## P1.6 Docs and README now overclaim some validation

### Evidence

`README.md` lists these as validation commands:

- `npm run typecheck`
- `npm run build`
- Android debug/release build commands

But `npm run build` currently fails.

`android-relay/README.md` still lists `RelayMediaSessionController` as the active headset/media-button wake component even though the service now consumes `AndroidMediaSessionProvider` through `SignalProviderRegistry`.

### Product impact

The docs are slightly ahead of verified reality. For end-user readiness, docs must be conservative because they become the support contract.

### Required fix

After code fixes, update docs to reflect:

- active provider architecture
- current build/lint truth
- exact supported-device matrix status

## Risk Summary

| Area | Status | Why |
| --- | --- | --- |
| Architecture shape | Improved | Provider boundary and setup flow now exist |
| Bridge tests | Strong | 123 Vitest tests pass |
| TypeScript release build | Blocked | `npm run build` fails |
| Android compile/package | Mostly good | debug/release assemble succeeds |
| Android lint | Blocked | 15 errors |
| Physical wake | Not ready | Provider events are not normalized to bridge contract |
| LibrePods usage | Partial | BLE/AACP code exists, but stem parser is likely wrong and real validation is absent |
| Setup truthfulness | Improved but not enough | Still does not prove exact physical wake -> STT loop |
| Security posture | Blocked | npm audit critical/high advisories |
| Supported-device claim | Not ready | checked-in matrix remains unproven |

## Recommended Fix Order

1. Fix TypeScript build failures.
2. Normalize provider gestures into schema-valid bridge events.
3. Fix AACP stem-press byte parsing and add packet tests.
4. Fix setup wake/STT verification so it captures events after the test window starts.
5. Fix Android lint errors.
6. Address npm audit critical/high advisories or document reachability mitigations.
7. Preserve hardware context on final transcript events.
8. Redact URLs/errors in diagnostic export.
9. Downgrade provider capability confidence until observed.
10. Run a fresh real-device validation session and update `docs/supported-devices-matrix.json`.

## Bottom Line

You did fix meaningful parts of the previous audit. The implementation is no longer just a plan; it has the provider skeleton, pairing improvements, setup flow, and LibrePods lane beginning to take shape.

But it is not perfectly implemented.

The current truth is:

`better architecture, stronger skeleton, still not end-user ready`

The most urgent correction is not more UI polish. It is making the physical signal path truthful end to end:

```text
real earbud event
-> normalized provider event
-> schema-valid bridge event
-> route prepared
-> STT transcript
-> interrupt/approval still works
-> matrix records only what was actually proven
```

Until that loop is passing on real hardware and the build/lint/security gates are clean, DevPods should remain an internal prototype / assisted dogfood build rather than an end-user-ready product.
