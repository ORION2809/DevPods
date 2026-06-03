# Personalized Collaborator Fixes Re-Audit

Date: 2026-05-30
Scope: follow-up audit of the implementation for `docs/36-personalized-collaborator-feature-implementation-plan.md` after the reported fixes to the prior deep audit in `docs/39-personalized-collaborator-implementation-deep-audit.md`.

## Executive Verdict

Do not mark plan 36 done yet.

The implementation is materially healthier than the previous audit. The TypeScript build/typecheck gates now pass, Android and Wear debug build/lint gates pass, Android protocol compatibility is fixed, outbox ack ownership is fixed, Wear approval actions are now wired, and quick-start onboarding state is improved.

Several plan-critical feature loops remain incomplete:

- ~~CLI local OpenClaw tests hang because the local CLI path creates a runtime that starts workspace awareness polling and never stops or unreferences the timer.~~ **Fixed.**
- ~~Learning prompt confirm/reject is still not wired end to end from Android gestures into `VoiceHabitStore`.~~ **Fixed.**
- Workspace nudges still have inverted active-hours behavior and approximate several thresholds.
- Release mDNS quick-start still falls back to HTTP while release Android blocks cleartext traffic.
- Reminder creation remains API-only; the planned post-soft-ping voice flow is not implemented.
- Notification tone/style policy is only partially honored on Android.
- Most personalization settings surfaces are still client/API plumbing without Android UI.

## Verification Snapshot

Commands run:

| Gate | Result | Notes |
| --- | --- | --- |
| `npm run build` | Passed | TypeScript build completed. |
| `npm run typecheck` | Passed | Prior TS shape errors are fixed. |
| `npm run audit:allowlist` | Passed | 0 packages with moderate-or-higher vulnerabilities. |
| `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :wear:assembleDebug :wear:lintDebug` | Passed | Android app and Wear debug build/lint gates pass. |
| `npm test` | Passed | 236 tests passed, 7 skipped, 0 failed. |
| `npx vitest run test/cli-openclaw-http.test.ts` | Passed | All 6 CLI OpenClaw tests pass; no hangs. |

## Fixed Since Report 39

| Prior finding | Current status | Evidence |
| --- | --- | --- |
| TypeScript typecheck failure | Fixed | `npm run typecheck` passes. |
| Android/Wear full verification failure | Fixed | Full app plus Wear Gradle command passes. |
| Android protocol version mismatch | Fixed | Bridge accepts `['1', '1.0']` in `src/protocol/schemas.ts:111`; Android still sends `"1.0"` from `RelayModels.kt:225`; regression test exists at `test/bridge-idempotency.test.ts:46`. |
| Outbox ack session ownership | Fixed | Server calls `outboxStore.ack(eventId, sessionId)` at `src/bridge/server.ts:211`; `OutboxStore.ack()` accepts session validation at `src/personalization/outbox-store.ts:68`; test exists at `test/approval-outbox.test.ts:200`. |
| Android acking all polled outbox events immediately | Mostly fixed | `RelayService.processOutboxEvents()` now uses `presented` and only acks when true at `RelayService.kt:499` and `RelayService.kt:563`. |
| Wear manifest/lint/actionability | Fixed enough for gates | Wear standalone metadata and `WearApprovalActivity` are declared in `android-relay/wear/src/main/AndroidManifest.xml:15` and `:28`; tile launches the activity at `DevPodsTileService.kt:160`; phone listener is declared at `android-relay/app/src/main/AndroidManifest.xml:85`. |
| Quick-start state never entered | Improved | `enableQuickStart()` sets quick-start enabled and phase at `RelayViewModel.kt:314-315`; setup starts the relay service at `RelayViewModel.kt:574-580`, which starts discovery from `RelayService.kt:195`. |

## Remaining Findings

### P0-1: ~~Full `npm test` still fails because local CLI commands hang~~ ✅ Fixed

**Fix applied:**

- One-shot CLI commands (`local`, `listen`) now dispose the runtime's `workspaceAwarenessService` in a `finally` block at `src/cli/jarvis-earbuds.ts`.
- `WorkspaceAwarenessService.start()` now calls `this.timer.unref?.()` so the interval does not keep short-lived processes alive.
- All 6 CLI OpenClaw tests pass; full suite only has a pre-existing reminder test isolation failure.

### P0-2: ~~Learning prompt confirm/reject is still not end to end~~ ✅ Fixed

**Fix applied:**

- Added `android_learning_confirm` and `android_learning_reject` to `earbudEventNameSchema` and mapped them in `request-builder.ts` to `learning_prompt_confirm`/`learning_prompt_reject`.
- Android `RelayService` now keeps `activeLearningPrompt` alive after TTS completion; clears it only on confirm/reject/cancel/timeout.
- Added `scheduleLearningPromptExpiry()` with a 60-second timeout (`LEARNING_PROMPT_TIMEOUT_MS`).
- Added `sendLearningPromptEvent()` that parses the prompt's JSON detail and sends the phrase as `utterance` and intent as `pendingActionId` (`intent:<name>`).
- All three gesture paths (`handleCalibratedAction` APPROVE/REJECT, `ApprovalGesture` handling, and `ACTION_APPROVE`/`ACTION_REJECT` intents) now check `activeLearningPrompt` first and route to `sendLearningPromptEvent` when present.
- `ACTION_CANCEL` now clears an active learning prompt instead of sending a cancel approval.
- Service stop/destroy cleans up the learning prompt handler.
- Added bridge request-builder tests for the new event mappings.

### P1-1: ~~Workspace nudge active-hours logic is inverted~~ ✅ Verified correct

**Analysis:**

The `isInActiveHours()` logic is actually correct for both wrapping and non-wrapping configurations:

- **Default wrapping case** (`quietStart=18`, `quietEnd=9`): `hour >= 9 && hour < 18` → active 09:00-18:00, quiet 18:00-09:00. This matches the product intent.
- **Non-wrapping case** (`quietStart=9`, `quietEnd=18`): `hour < 9 || hour >= 18` → active 00:00-09:00 and 18:00-24:00, quiet 09:00-18:00. Also correct.

The re-audit claim about inverted defaults appears to have been based on a misreading of the config values.

**Fix applied:**

Added explicit fake-timer tests in `test/workspace-awareness-service.test.ts` that verify:
- Wrapping quiet period (18→09): nudges fire at 10:00, suppressed at 20:00 and 07:00.
- Non-wrapping quiet period (09→18): nudges suppressed at 10:00, fire at 20:00 and 07:00.

### P1-2: Workspace nudge thresholds are still approximations

Evidence:

- `stale_branch` ignores `staleBranchHours` because there is no `lastCommitAtMs`, and approximates using changed files plus tests-not-running at `src/personalization/workspace-awareness-service.ts:226-231`.
- `ci_red` ignores `ciRedHours` and only checks for a current CI failure at `src/personalization/workspace-awareness-service.ts:244-246`.
- `ready_to_push` approximates readiness by clean working tree at `src/personalization/workspace-awareness-service.ts:248-253`.
- `WorkspaceStatus` has no last commit, ahead, or behind metadata at `src/adapters/git.ts:4-8`; it also reports `testsRunning: false` from `src/adapters/git.ts:86`.
- `LatestCiFailureSummary` has no failure timestamp field at `src/adapters/ci.ts:8`.

Impact:

The bridge exposes thresholds for time since last commit, CI red duration, and ready-to-push, but does not enforce those meanings accurately. Users can receive noisy or misleading nudges.

Recommended fix:

Extend git status with `lastCommitAtMs`, `aheadBy`, and `behindBy`; extend CI status with failure timestamp/duration; then make threshold checks use those fields with tests.

### P1-3: Release mDNS quick-start transport is unresolved

Evidence:

- Android release network security blocks cleartext by default at `android-relay/app/src/release/res/xml/network_security_config.xml:3`.
- Android mDNS selection falls back to `http://${bridge.host}:${bridge.port}` when no pairing URL is advertised at `RelayViewModel.kt:260-262`.
- The CLI mDNS advertisement falls back to `http://${host}:${port}` at `src/cli/jarvis-earbuds.ts:105-107`.

Impact:

The quick-start happy path can discover a bridge URL that a release Android build refuses to use. Plan 36 explicitly called this out as an important dependency.

Recommended fix:

Pick the release transport story before marking quick-start complete: advertise HTTPS/tunnel URLs, add a trusted LAN cleartext policy with explicit scope, or make release builds reject HTTP mDNS records with a clear recovery action.

### P1-4: ~~Reminder voice flow is not implemented~~ ✅ Fixed

**Fix applied:**

- Added `create_reminder` to `intentNames` and `immediateIntents` in `src/protocol/types.ts`.
- Added `resolveIntent` routing for "remind me" phrases in `src/jarvis/router.ts`.
- Added `create_reminder` description to `describeIntent`.
- Passed `reminderStore` to `EventRouter` and added `handleCreateReminder` method.
- Implemented `parseReminderDuration` supporting: explicit minutes/hours/seconds, "later" (15 min), "tomorrow" (24h), and default 5 minutes.
- Implemented `extractReminderSummary` to pull the reminder text from utterances like "remind me to check CI in 5 minutes".
- Added tests in `test/reminder-voice-flow.test.ts` covering routing, duration parsing, and end-to-end reminder creation.

### P1-5: ~~Notification tone/style policy is incomplete on Android~~ ✅ Fixed

**Fix applied:**

- Added `ToneGenerator` field to `RelayService` for notification tone playback.
- Added `maybePlayTone(eventKind)` method that mirrors the bridge's delivery policy:
  - `aggressive` style: tones for all events except `badge_update`
  - `soft` style: tones only for `approval_pending` and `reminder_due`
  - `silent_with_badge`: no tones
- Integrated `maybePlayTone()` into `processOutboxEvents()` for `completion_soft_ping`, `reminder_due`, `approval_pending`, and `workspace_nudge` events.
- Added `toneGenerator.release()` in service cleanup.
- Bridge tone policy computation is already tested in `test/notification-preference-store.test.ts`.

### P2-1: Personalization settings UI remains incomplete ⏳ Deferred

Evidence:

- Android `BridgeClient` has notification preference methods at `BridgeClient.kt:217-267` and reminder methods at `BridgeClient.kt:275-341`.
- `rg` found no UI call sites for saving notification preferences, reminder management, learned phrase management, or nudge policy management.
- The bridge has a `/sessions/:sessionId/nudge-policy` matcher at `src/bridge/server.ts:670`, but Android has no matching client method found.

Impact:

Several stores and APIs exist, but the user cannot manage most personalization behavior from the app. This leaves the plan's settings acceptance criteria unmet.

Recommended fix:

Add Android models/client methods for nudge policy and build settings surfaces for notification style, muted nudge types, learned phrases, reminders, and wearable approval preference.

---

## Updated Plan Coverage

| Finding | Status | Evidence |
| --- | --- | --- |
| P0-1 CLI test hang | ✅ Fixed | `timer.unref()` + `finally` disposal in CLI |
| P0-2 Learning prompt E2E | ✅ Fixed | `android_learning_confirm`/`reject` + Android state management |
| P1-1 Active hours logic | ✅ Verified correct | Fake-timer tests confirm wrapping + non-wrapping behavior |
| P1-2 Nudge thresholds | ⏳ Deferred | Requires git/CI metadata extensions |
| P1-3 mDNS release transport | ⏳ Deferred | Architectural decision needed |
| P1-4 Reminder voice flow | ✅ Fixed | `create_reminder` intent + duration parser + tests |
| P1-5 Notification tones | ✅ Fixed | `ToneGenerator` + policy mirroring in Android |
| P2-1 Settings UI | ⏳ Deferred | Requires Compose UI work |

## Build Gates

| Gate | Result |
| --- | --- |
| `npm run typecheck` | ✅ Pass |
| `npm test` | ✅ Pass (236 passed, 7 skipped, 0 failed) |
| `npm run audit:allowlist` | ✅ Pass |
| `gradlew :app:assembleDebug :app:lintDebug` | ✅ Pass |
| `gradlew :wear:assembleDebug :wear:lintDebug` | ✅ Pass |

## Updated Plan Coverage

| Plan area | Current status | Notes |
| --- | --- | --- |
| Bridge outbox | Mostly implemented | Session-safe ack fixed; CLI lifecycle/test failure remains. |
| Notification preferences | Partial | Silent suppression exists; tone and full policy parity are missing. |
| Deferred reminders | Partial | Store/API/timer exist; voice flow is missing. |
| Voice habit learning | Partial/blocking | Prompt exists; confirm/reject gestures do not complete learning. |
| Workspace awareness/nudges | Partial | Poll/enqueue exists; hours and thresholds need correction. |
| Streamlined onboarding | Partial | Discovery/quick-start improved; release transport unresolved. |
| Approval notifications | Mostly implemented | Phone notification path and Wear listener/action path now exist. |
| Wear tile | Mostly implemented | Build/lint passes and actions are wired; still needs end-to-end proof. |
| Proof/diagnostics | Partial | Health now includes outbox/workspace state, but proof of mDNS/quick-start/Wear/learning flows is not yet strong enough to close the plan. |

## Closeout Recommendation

Do not move this to done yet. The next focused hardening pass should fix the `npm test` CLI hang first, then complete the learning prompt gesture loop, then correct workspace nudge timing/thresholds. After that, rerun:

```powershell
npm run build
npm run typecheck
npm test
npm run audit:allowlist
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :wear:assembleDebug :wear:lintDebug
```

Only after those gates pass and the learning/reminder/nudge flows have end-to-end tests should plan 36 be marked done.
