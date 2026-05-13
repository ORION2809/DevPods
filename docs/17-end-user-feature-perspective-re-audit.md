# End-User Feature Perspective Re-Audit

Date: 2026-05-12

## Scope

This pass audits DevPods from an end-user feature perspective after the latest fixes. I am deliberately not counting "actual earbud/device proof is missing" as a finding here, because that was explicitly excluded for this pass.

This is not a claim that the hardware path is proven. It is a product-readiness check for what still feels missing, fragile, confusing, or under-explained for a user installing the Android app, pairing it to the desktop bridge, and trying to use it.

## Verification Snapshot

Commands run during this pass:

| Gate | Result |
| --- | --- |
| `npm run build` | Pass |
| `npm run typecheck` | Pass |
| `npm test` | Pass: 19 files, 123 tests |
| `android-relay\gradlew.bat :app:lintDebug` | Pass |
| `android-relay\gradlew.bat testDebugUnitTest` | Pass |
| `android-relay\gradlew.bat :app:assembleRelease` | Pass, with non-blocking Kotlin warnings |
| `npm audit --audit-level=moderate` | Fails on documented `openclaw -> @mistralai/mistralai` critical advisory |

Important nuance: the implementation is much healthier than the previous re-audit. The bridge builds, Android builds, pairing-code flow exists, onboarding exists, setup wizard exists, diagnostic redaction exists, internal service-command auth exists, and the main test suites pass. The remaining concern is not "nothing works"; it is that the user journey still feels more like an engineering console than a finished product.

## Overall Opinion

Ignoring real-device proof, I would rate the current product state as:

| Audience | Readiness |
| --- | --- |
| Internal engineering dogfood | 75/100 |
| Friendly technical beta | 60/100 |
| Public end-user beta | 40/100 |

The code is now strong enough for controlled dogfooding, but it is not yet a clean end-user experience. The biggest gaps are guided setup continuity, visible recovery state, settings/fallback controls, approval/autonomy clarity, and support docs matching the current implementation.

## P0 Product Gaps

### P0.1 Guided setup is not yet the primary first-run path

Evidence:

- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt:89` shows onboarding on first run.
- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt:90` dismisses onboarding by marking it seen and hiding it.
- `android-relay/app/src/main/java/com/openclaw/relay/UserOnboarding.kt:7` defines `hasCompletedSetup`.
- `android-relay/app/src/main/java/com/openclaw/relay/UserOnboarding.kt:35` defines `markSetupCompleted`.
- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt:508` completes setup without calling `markSetupCompleted`.

Impact:

After onboarding, the app can still drop the user into the full dashboard instead of automatically continuing into the guided setup. The code has the concept of setup completion, but it is not driving the product flow. That means the app cannot confidently say "this user completed setup", "resume setup", or "do not show setup again".

What to fix:

- Make onboarding dismissal start the setup wizard unless setup was already completed or intentionally skipped.
- Persist setup completion and skip state through `UserOnboardingManager`.
- Use that state to decide whether the main screen should show setup, resume setup, or show the normal operating dashboard.
- Add a visible "Reset setup / run setup again" path.

### P0.2 The main screen still reads like a technical console

Evidence:

- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt:375` exposes a broad "Primary actions" card.
- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt:410` exposes "Advanced settings".
- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt:421` exposes the relay bearer token field.
- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt:447` shows raw pending approval state as `"Pending: ..."` rather than a guided approval experience.

Impact:

A normal user has to infer the correct sequence: permissions, pair, start relay, health, wake/listen, tap test, speaker test, approval. That is fine for us, but loose for a product. The UI should make the next correct action obvious and hide everything else until needed.

What to fix:

- Replace the default dashboard with a state-driven primary CTA: "Pair bridge", "Grant permissions", "Start relay", "Test earbuds", "Speak now", "Approve action", "Reconnect bridge".
- Move debug controls, raw URL/token fields, and tap/speaker tests behind a diagnostics/developer mode.
- Keep the "current state" card, but make it actionable rather than descriptive only.

### P0.3 Pairing and desktop bootstrap are still too manual

Evidence:

- `packaging/windows/start-devpods-bridge.ps1` starts the bridge and opens a local pairing page, which is useful, but the user still has to understand desktop bridge startup, LAN reachability, QR/page handoff, and phone import.
- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt:480` still tells users to use Advanced settings to manually enter the bridge URL and token.
- `protocol/bridge-api.md:42` and `protocol/bridge-api.md:44` still document direct `relayToken` pairing payloads, while the implementation has moved toward pairing-code exchange.

Impact:

Pairing is technically improved, but still fragile from a product perspective. If pairing fails, the user has to know whether the issue is desktop bridge not running, wrong IP, wrong Wi-Fi, firewall, expired pairing code, stale docs, or manual token entry.

What to fix:

- Add a "Desktop bridge checklist" in-app: bridge running, same network, pairing page reachable, token verified.
- Add "Forget bridge", "Re-pair", and "Regenerate pairing" UX.
- Update `protocol/bridge-api.md` so support docs match the current pairing-code implementation.
- Add bridge/app compatibility info to the health response and UI.

### P0.4 Recovery tells users to change settings that do not exist in the UI

Evidence:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayModels.kt:10` has `useBluetoothRouting`.
- `android-relay/app/src/main/java/com/openclaw/relay/RelayConfigStorage.kt:10` persists `useBluetoothRouting`.
- `android-relay/app/src/main/java/com/openclaw/relay/ui/TroubleshootingScreen.kt:112` suggests enabling phone-mic fallback in settings.
- `android-relay/app/src/main/java/com/openclaw/relay/ui/TroubleshootingScreen.kt:115` suggests disabling Bluetooth routing in advanced settings.
- The current main advanced settings UI does not expose a Bluetooth routing or phone-mic fallback toggle.

Impact:

This is a real end-user dead end. The app gives a recovery instruction, but the user cannot perform it. That makes troubleshooting feel broken even if the underlying route policy is sound.

What to fix:

- Add Settings toggles for Bluetooth microphone routing, phone-mic fallback, assistant fallback, and TTS/speaker test preferences.
- Ensure troubleshooting suggestions deep-link or scroll to the exact setting being recommended.
- Rename fallback language so users understand the tradeoff: "Use phone microphone when earbud microphone is unavailable."

### P0.5 Approval UX is missing risk, expiry, and consequence clarity

Evidence:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayModels.kt` defines `BridgeApprovalRequest` with `actionType`, `summary`, `riskClass`, and `expiresInMs`.
- `android-relay/app/src/main/java/com/openclaw/relay/RelayStateStore.kt:178` stores only the approval summary in `pendingApprovalSummary`.
- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt:447` displays only `"Pending: ..."`.

Impact:

The bridge has a serious approval model, but the Android UX flattens it. Users need to know whether they are approving a read-only action, test run, file open, push, delete, deploy, or revert, and how long the approval remains valid.

What to fix:

- Store the full `BridgeApprovalRequest` in UI state.
- Show risk class, action type, expiry countdown, and exact consequence.
- Auto-clear expired approvals in the UI.
- Show the earbud gesture and on-screen fallback for approve/reject.

## P1 Important Loose Edges

### P1.1 Bridge retry/offline queue is implemented but invisible

Evidence:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:71` has a private `pendingEventQueue`.
- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:704` queues failed bridge events.
- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:801` schedules retries.
- UI state does not expose queue count, retry ETA, or "discard queued command".

Impact:

If the bridge is unreachable, the app may queue and retry, but the user mostly sees an error. They may repeat commands, assume the command was lost, or later get a delayed action they no longer expect.

What to fix:

- Publish queue count, retry attempt, and next retry time into `RelayUiState`.
- Show "Bridge unreachable. 2 commands queued. Retrying in 5s."
- Add "Retry now" and "Discard queued commands".

### P1.2 Autonomy/background continuation is not visible enough

Evidence:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayModels.kt:65` stores `activeAutonomy`.
- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:711` schedules autonomy continuation.
- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:732` posts the delayed continuation.
- The main UI does not show a dedicated autonomy card with countdown, next step, or stop control.

Impact:

If DevPods is going to continue work after silence, the user must see that it is about to continue and have an obvious way to stop it. Otherwise the product can feel surprising, especially for developer actions.

What to fix:

- Add an "Assistant working" card with current phase, next step, countdown, and "Stop".
- Speak a short interrupt hint only when safe.
- Make autonomy cancellation visible and logged in the latest interaction card.

### P1.3 Error handling has friendly mapping, but raw errors still dominate key surfaces

Evidence:

- `android-relay/app/src/main/java/com/openclaw/relay/RelayStateStore.kt:287` maps raw errors to user-facing messages.
- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt:467` still displays `state.errorMessage`.
- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt:477` uses raw `state.errorMessage` in the primary state.
- `android-relay/app/src/main/java/com/openclaw/relay/ui/TroubleshootingScreen.kt:39` displays raw `state.errorMessage`.
- `SetupWizardScreen` uses `userFacingErrorMessage`, which is the better pattern.

Impact:

The user-facing error layer exists, but it is not consistently used. That can expose implementation detail, bridge URLs, HTTP bodies, or confusing internal phrasing.

What to fix:

- Use `userFacingErrorMessage ?: errorMessage` only in diagnostics/developer contexts.
- Keep raw errors in diagnostic export after redaction, not in primary UI.
- Add tests for public error surfaces.

### P1.4 Setup wizard actions are semantically confusing

Evidence:

- `android-relay/app/src/main/java/com/openclaw/relay/ui/SetupWizardScreen.kt:156` says "Tap your earbuds now".
- `android-relay/app/src/main/java/com/openclaw/relay/ui/SetupWizardScreen.kt:164` then offers the button "I tapped my earbuds".
- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt:406` starts the actual wake-test observation after the button is pressed.
- `android-relay/app/src/main/java/com/openclaw/relay/ui/SetupWizardScreen.kt:170` tells users to wake the app and speak, while the `Test speech` button actually opens the listening flow.

Impact:

The wizard may work internally, but the user can easily do the physical action before the app starts observing. This creates false negatives and frustration.

What to fix:

- Change "I tapped my earbuds" to "Start 10-second wake test".
- Show a visible countdown and live "event seen" indicator.
- In speech test, make the sequence explicit: "Tap Start, wait for the tone, say hello DevPods."

### P1.5 Diagnostics export is safer, but lacks user preview and consent granularity

Evidence:

- `android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt` redacts URLs, tokens, workspace names, and device identifiers.
- `android-relay/app/src/main/java/com/openclaw/relay/diagnostic/DiagnosticExport.kt:174` immediately opens a share intent with the diagnostic payload.

Impact:

The payload is much safer now, but it still includes phone model, Android version, capability matrix, readiness, and error categories. Users should see what they are about to share.

What to fix:

- Add a preview screen before sharing.
- Add toggles for "include capability matrix", "include recent error categories", and "include phone model".
- Add a copy/save option for support workflows.

### P1.6 Trust posture is documented, but the default audit gate is still red

Evidence:

- `npm audit --audit-level=moderate` still fails.
- `.github/workflows/ci.yml:34` runs audit as informational with `npm audit || true`.
- `SECURITY.md:11` documents the critical `@mistralai/mistralai` advisory.
- `SECURITY.md:30` explains why the repo currently treats it as a false positive/reachability issue.

Impact:

This may be acceptable for internal development, but it is not clean for a public release. A user, customer, or security reviewer will see a critical advisory and ask why the product ships that way.

What to fix:

- Keep the documented exception, but make it machine-checked.
- Add an audit allowlist with package, version, advisory, rationale, owner, and expiry date.
- Add a CI step that fails if the package version changes, the exception expires, or new advisories appear outside the allowlist.

## P2 Polish And Support Gaps

### P2.1 Notification UX is thin

The foreground notification says DevPods Relay is active, but it does not expose useful user actions such as Stop, Speak, Retry bridge, or Cancel active action. For a background voice product, the notification should be a safe control surface.

### P2.2 No visible app/bridge compatibility contract

The Android `BridgeHealthResponse` tracks health and OpenClaw metadata, but not a clear bridge version, protocol version, supported features, or minimum app version. That makes future upgrade failures harder to explain.

### P2.3 Support docs still contradict the implementation

`protocol/bridge-api.md` still shows direct `relayToken` pairing examples while the implementation now supports pairing codes and `/pairing/verify`. This is not just docs polish; stale pairing docs can cause users or support to follow an older, less safe path.

### P2.4 Android release warnings should be cleaned up before beta

Release assemble passes, but Kotlin reports unused parameters and ignored `@OptIn` annotations:

- `MainActivity.kt`: `onDismissOnboarding` parameter is unused inside `RelayScreen`.
- `SetupWizardScreen.kt`: `errorMessage` parameter is unused.
- Media3 `@OptIn` annotations are ignored because the annotation is not an opt-in requirement marker in this usage.

These are not blockers, but they are classic "loose product edge" signals.

### P2.5 Accessibility/localization is still minimal

Most Android strings are hard-coded in Compose files rather than `strings.xml`. There is no clear accessibility pass for screen-reader labels, state announcements, or localized setup/troubleshooting copy.

## Recommended Execution Order

1. Make onboarding lead directly into setup, persist setup completion, and make the main dashboard state-driven.
2. Add real Settings for Bluetooth routing, phone-mic fallback, assistant fallback, TTS, and diagnostics.
3. Upgrade approval/autonomy/queue UI so users can see risk, countdowns, pending work, retries, and cancellation.
4. Fix stale pairing docs and add app/bridge version/capability handshake.
5. Convert the `npm audit` exception from a human note into a CI-enforced exception with an expiry.
6. Polish notifications, warnings, accessibility, and localization before calling this public beta.

## Bottom Line

The repo is no longer in a "missing implementation" state for the core bridge/app loop. Excluding real-device proof, the remaining problem is product shape: the app needs to guide, recover, explain, and constrain better.

My opinion: ship this to yourself and a tiny technical dogfood group, but do not call it end-user ready until the first-run setup, recovery settings, approval/autonomy visibility, stale docs, and trust gates are tightened.
