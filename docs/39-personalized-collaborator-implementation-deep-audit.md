# Personalized Collaborator Implementation Deep Audit

Date: 2026-05-30
Scope: audit of the implementation for `docs/36-personalized-collaborator-feature-implementation-plan.md`, including the bridge, Android app, Wear module, tests, and proof/reporting hooks.

## Executive Verdict

Do not mark plan 36 done yet.

The implementation has a real foundation: bridge outbox storage, preference/reminder/habit/nudge stores, Android outbox polling, mDNS discovery, quick-start policy plumbing, approval notifications, and a Wear tile module all exist. However, the feature is not end-to-end complete and the current tree does not pass the full build gates.

The most important blockers are:

- TypeScript typecheck fails in the current tree.
- Full `npm test` fails with CLI OpenClaw timeout failures.
- Full Android plus Wear verification fails because `:wear:lintDebug` reports a missing manifest class.
- Android emits relay protocol version `"1.0"` while the bridge accepts only `"1"`, so real Android `/events` traffic is rejected.
- Learning prompt confirmation is not wired from Android gestures to the bridge.
- Android acks outbox events even when they were suppressed or could not be presented, which drops reminders, nudges, and learning prompts.
- Wear approval UI is display-only and currently fails lint.

## Verification Snapshot

Commands run:

| Gate | Result | Notes |
| --- | --- | --- |
| `npm run typecheck` | Failed | `NotificationPreference` defaults/tests omit required `showSensitiveInNotifications`; `intent-resolution-engine.test.ts` uses stale `BridgeRequest` fields and `deviceState: null`. |
| `npm test` | Failed | 222 passed, 7 skipped, 3 failed. The failures are timeouts in `test/cli-openclaw-http.test.ts` at lines 163, 169, and 212. |
| `npm run audit:allowlist` | Passed | 0 packages with moderate-or-higher vulnerabilities. |
| `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` | Passed | App-only debug unit, assemble, and lint gates pass. |
| `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :wear:assembleDebug :wear:lintDebug` | Failed | `:wear:lintDebug` fails on missing `WearDataLayerService` and missing Wear standalone metadata. |

## Plan Coverage Summary

| Plan area | Status | Audit note |
| --- | --- | --- |
| Bridge outbox | Partial | Poll/enqueue/ack exist, but ack ignores session ownership and Android acks events before confirmed presentation. |
| Notification preferences | Partial | Bridge computes style-aware policy; Android still uses booleans and ignores `style`/tone policy. |
| Deferred reminders | Partial | Store/API/timer exist; voice creation and post-soft-ping "remind me later" flow are not wired. |
| Voice habit learning | Partial | Store/API/intent engine exist; Android cannot confirm/reject learning prompts end to end. |
| Workspace nudges | Partial | Service/store exist; active-hours logic is inverted, breakpoints are Android-side best-effort only, and several thresholds are approximations. |
| mDNS quick start | Partial | Bridge advertises and Android discovers; setup state/quick-start UI is not reliably entered, and release cleartext transport is unresolved. |
| Approval notification | Partial | Phone notification exists; Wear approval action path is not actionable. |
| Wear tile | Not complete | Module builds, but lint fails and action chips are plain text. |
| Proof artifact extensions | Partial | Optional fields exist, but there is no clear production path proving the new fields from actual mDNS/quick-start/Wear flows. |

## Findings

### P0-1: Full build gates fail

Evidence:

- `npm run typecheck` fails because `src/personalization/notification-preference-store.ts:17` builds a `NotificationPreference` without `showSensitiveInNotifications`, while `src/protocol/schemas.ts:263-272` makes that field part of the inferred TypeScript type.
- `test/intent-resolution-engine.test.ts:14-23` constructs a stale `BridgeRequest`: it uses removed policy fields (`commandTimeoutMs`, `backgroundTimeoutMs`, `maxRetries`) and sets `deviceState: null`.
- `npm test` fails three tests in `test/cli-openclaw-http.test.ts:163`, `:169`, and `:212` due 5 second timeouts.
- `:wear:lintDebug` fails because `android-relay/wear/src/main/AndroidManifest.xml:24` references `.WearDataLayerService`, but the only Wear source file found is `DevPodsTileService.kt`.

Impact:

The implementation cannot be considered complete while the primary TypeScript and full Android/Wear gates are red.

Recommended fix:

Update the notification preference default/test fixtures, repair the stale intent-resolution test request shape, investigate the CLI timeout regression, and add/remove the missing Wear data layer service declaration.

### P0-2: Android relay protocol version mismatch rejects real app events

Evidence:

- Bridge accepts only `SUPPORTED_PROTOCOL_VERSIONS = ['1']` in `src/protocol/schemas.ts:111`.
- Bridge rejects Android relay events with unsupported protocol versions in `src/bridge/server.ts:384-390`.
- Android sets `RELAY_PROTOCOL_VERSION` to `"1.0"` in `android-relay/app/src/main/java/com/openclaw/relay/RelayModels.kt:224-225`.
- Android gesture events explicitly use that constant in `RelayService.kt:1131-1145`, and approval events rely on the same `RelayBridgeEvent` default at `RelayModels.kt:241`.
- Server tests only prove `"1"` is accepted and `"99"` is rejected (`test/bridge-idempotency.test.ts:17-43`, `:102-128`); they do not cover the Android constant.

Impact:

Real Android `/events` requests are rejected with HTTP 400 before any personalized collaborator behavior can run. This blocks push-to-talk, approval gestures, quick-start commands, and any future learning prompt action sent through the same event channel.

Recommended fix:

Make the bridge and Android agree on one wire version, preferably by changing Android to `"1"` or allowing both `"1"` and `"1.0"` during a migration. Add a cross-platform test that asserts the Android constant is accepted by the bridge.

### P0-3: Learning prompt confirmation is not end-to-end wired

Evidence:

- Bridge schemas define `learning_prompt_confirm` and `learning_prompt_reject` request events in `src/protocol/schemas.ts:24-36`.
- Android event names do not include `learning_prompt_confirm` or `learning_prompt_reject`; `earbudEventNameSchema` only allows physical/android events at `src/protocol/schemas.ts:4-22`.
- `src/bridge/request-builder.ts:32-64` maps `android_approve` and `android_reject` to `approval_action`, with no branch for learning prompt confirmation/rejection.
- `EventRouter` enqueues a `learning_prompt` outbox event with JSON detail `{ phrase, intent }` at `src/bridge/event-router.ts:131-141`.
- The confirm handler expects `request.utterance` to contain the phrase and `pendingActionId` to contain an `intent:<name>` hint at `src/bridge/event-router.ts:157-178`; Android never sends that payload.
- Android displays/speaks learning prompts in `RelayService.kt:528-535`, but clears `activeLearningPrompt` after TTS and acks the event at `RelayService.kt:541-543`.
- Approval gestures only send `android_approve`/`android_reject` when `pendingApprovalRequest != null` (`RelayService.kt:604-625`), not when `activeLearningPrompt` exists.

Impact:

The voice habit learning loop cannot be completed by earbuds. Low-confidence phrase learning can prompt the user, but confirm/reject gestures do not update `VoiceHabitStore`.

Recommended fix:

Keep active learning prompt state until confirmed/rejected or expired. In approval/reject gesture routing, check `activeLearningPrompt` before approval state, parse its `detail`, and send a bridge event or dedicated endpoint that carries the phrase and intent. Add Android tests for confirm/reject gesture routing and bridge tests for the exact payload.

### P1-1: Android outbox delivery drops events that were not presented

Evidence:

- `RelayService.processOutboxEvents()` checks `canSpeakOutboxEvent()` before speaking soft pings, reminders, nudges, and learning prompts (`RelayService.kt:503-535`).
- Regardless of whether speech happened, every non-muted event is acked at `RelayService.kt:541-543`.
- Muted events are also immediately acked at `RelayService.kt:497-500`.
- `canSpeakOutboxEvent()` blocks delivery during listening, speaking, and pending approval (`RelayService.kt:547-550`).

Impact:

If a reminder, workspace nudge, or learning prompt arrives during active listening/TTS/approval, Android silently acks it and removes it from the bridge. This violates the plan requirements that reminders must not speak during active listening/TTS and nudges should wait for breakpoints. The current behavior avoids interruption by losing the event.

Recommended fix:

Separate "seen/polled" from "presented/acked". Keep deferred events in local state until a valid delivery breakpoint occurs, then ack only after notification, badge, tone, or speech presentation succeeds. Muted events should either be converted to badge-only delivery according to policy or deliberately acked with explicit audit/history.

### P1-2: Outbox ack endpoint ignores session ownership

Evidence:

- `matchOutboxAck()` parses both `sessionId` and `eventId` at `src/bridge/server.ts:635-640`.
- The handler discards `sessionId` and calls `runtime.outboxStore.ack(eventId)` at `src/bridge/server.ts:208-213`.
- `OutboxStore.ack()` only checks event ID existence and does not validate the owning session at `src/personalization/outbox-store.ts:68-76`.

Impact:

Any authorized relay session that learns or guesses an event ID can ack another session's outbox event. This is a local authenticated integrity issue and also makes tests weaker than the plan's per-session outbox contract.

Recommended fix:

Change ack to `ack(sessionId, eventId)`, validate ownership, and add an API test where session B cannot ack session A's event.

### P1-3: Android notification delivery ignores bridge style policy

Evidence:

- Bridge computes a style-aware `DeliveryPolicy`, including `silent_with_badge` with `speak: false` and `tone: false`, in `src/personalization/notification-preference-store.ts:77-109`.
- Android `NotificationPreference` includes `style` in `RelayModels.kt:270-279`.
- Android `processOutboxEvents()` never checks `prefs.style`; it checks only `softPingTtsEnabled`, `reminderTtsEnabled`, `nudgeTtsEnabled`, and `mutedKinds` (`RelayService.kt:492-535`).

Impact:

`silent_with_badge` can still speak if the per-kind TTS booleans are true. Bridge tone policy is also not implemented on Android. This breaks the "aggressive/soft/silent_with_badge" acceptance criteria.

Recommended fix:

Either have Android reproduce the bridge `DeliveryPolicy` exactly, or have the outbox event include a resolved delivery policy from the bridge. Add Android unit tests covering all three styles.

### P1-4: Workspace nudge timing is incorrect and not breakpoint-gated

Evidence:

- Default config comments say no nudges before 09:00 or after 18:00 (`workspace-awareness-service.ts:23-26`, `:31-36`).
- `isInActiveHours()` returns `hour < quietStartHour || hour >= quietEndHour` for `9 < 18` (`workspace-awareness-service.ts:189-195`), which is outside the intended 09:00-18:00 active window.
- Nudges are enqueued directly on each poll at `workspace-awareness-service.ts:93-105` and `:297-311`.
- Android polls every 10 seconds and immediately speaks a workspace nudge when `canSpeakOutboxEvent()` is true (`RelayService.kt:471-483`, `:523-526`).

Impact:

With the default config, nudges happen outside the intended workday. They also are not truly gated to the plan's breakpoints such as earbuds inserted, background task finished, IDE idle, or user asks "what did I miss?" They are only gated by the coarse Android "not listening/not speaking/no approval" condition.

Recommended fix:

Rename or correct the quiet/active-hour logic, add time-injected tests for default 09:00-18:00 behavior, and add a local Android delivery queue that releases nudges only on explicit breakpoint events.

### P1-5: Workspace nudge thresholds are still approximations

Evidence:

- `WorkspaceStatus` has only `repoDetected`, `branch`, `changedFiles`, `mainChangedFile`, and `testsRunning` at `src/adapters/git.ts:4-10`.
- `getWorkspaceStatus()` always returns `testsRunning: false` and no `lastCommitAtMs`/ahead/behind metadata (`src/adapters/git.ts:52-87`).
- `LatestCiFailureSummary` has no failure timestamp (`src/adapters/ci.ts:8-16`), and `getLatestCiFailure()` returns no `created_at`/`updated_at` field (`src/adapters/ci.ts:128-136`).
- `stale_branch` explicitly ignores `staleBranchHours` because there is no `lastCommitAtMs` (`workspace-awareness-service.ts:221-227`).
- `ci_red` ignores `ciRedHours` (`workspace-awareness-service.ts:239-242`).
- `ready_to_push` approximates readiness by a clean working tree and does not inspect ahead/behind (`workspace-awareness-service.ts:243-249`).

Impact:

Several plan thresholds exist in schema/defaults but are not enforced accurately. This can create noisy or misleading nudges.

Recommended fix:

Extend the git adapter with `lastCommitAtMs`, `aheadBy`, and `behindBy`; extend the CI adapter with failed-run timestamp; use these fields in threshold checks and tests.

### P1-6: Quick-start and mDNS onboarding are partial

Evidence:

- mDNS advertisement exists in `src/bridge/mdns.ts:16-32` and starts on non-loopback binds in `src/cli/jarvis-earbuds.ts:104-110`.
- Android discovery exists in `BridgeDiscoveryManager.kt:37-100`, and `RelayService` only starts discovery from service startup when the app is unpaired (`RelayService.kt:191-196`).
- Setup's `startSetup()` only sets `SetupPhase.PAIRING` and loads the capability matrix (`RelayViewModel.kt:570-574`); it does not start discovery.
- `SetupPhase.QUICK_START` UI exists (`SetupWizardScreen.kt:140-157`), but `selectDiscoveredBridge()` does not set that phase or `quickStartEnabled` (`RelayViewModel.kt:260-317`).
- `RelayStateStore.setQuickStartEnabled()` exists but is not called anywhere in the quick-start path (`RelayStateStore.kt:745-749`).
- Release manifest deliberately has no cleartext allowance (`android-relay/app/src/main/AndroidManifest.xml:17-19`), while mDNS fallback uses `http://host:port` (`RelayViewModel.kt:260-262`) and CLI advertises `http://host:port` if no pairing base URL is provided (`jarvis-earbuds.ts:104-108`).

Impact:

The first-run "discover bridge, tap to pair, use quick-start immediately" flow is unreliable. In release builds, discovered HTTP bridges may be blocked unless an HTTPS/tunnel/trusted-LAN strategy is finalized.

Recommended fix:

Start discovery from setup/onboarding, set `SetupPhase.QUICK_START` and `quickStartEnabled` after successful quick-start enablement, and decide the release transport story for mDNS-discovered bridges.

### P1-7: Wear approval path is not complete

Evidence:

- Wear manifest declares `.WearDataLayerService` at `android-relay/wear/src/main/AndroidManifest.xml:23-30`, but no matching source file exists.
- Wear lint reports that missing class and a missing `com.google.android.wearable.standalone` metadata entry.
- `DevPodsTileService.actionChip()` returns plain text only (`DevPodsTileService.kt:149-153`).
- The tile renders "Approve" and "Reject" labels (`DevPodsTileService.kt:116-123`) but does not attach clickable actions or send approve/reject commands through the data layer.
- Phone sync sends tile state based on `pendingApprovalRequest` only (`WearDataSync.kt:30-44`); outbox approval notifications do not populate that state unless they also came from the immediate bridge response path.

Impact:

The plan's Wear approval tile is not actionable and the Wear module currently fails lint. It cannot be counted as implemented.

Recommended fix:

Either remove the undeclared service from the manifest or implement it. Add tile click actions that send approve/reject/cancel to the phone, and add phone-side handling that dispatches `ACTION_APPROVE`/`ACTION_REJECT` with the action ID.

### P1-8: Reminder creation is API-only, not voice-flow complete

Evidence:

- `ReminderStore` schedules and fires `reminder_due` events (`src/personalization/reminder-store.ts:86-118`).
- Bridge server exposes reminder list/create/delete endpoints (`src/bridge/server.ts:249-289`).
- Android `BridgeClient` exposes reminder list/create/cancel methods (`BridgeClient.kt:275-360`).
- There is no intent/router path found for "remind me later" or "remind me in 5 minutes" (`rg` found only store/API/test references).
- Android `processOutboxEvents()` speaks reminders if possible, then acks regardless of whether speech happened (`RelayService.kt:514-517`, `:541-543`).

Impact:

The plan's voice reminder flow after soft pings is not implemented. Deferred reminders can be created by API, but not by the natural earbud interaction described in the plan.

Recommended fix:

Add a reminder intent parser and a short post-soft-ping listening window. Store reminder context from the last completion event, then schedule via bridge when the user says "remind me later/in X".

### P2-1: Personalization settings UI is incomplete

Evidence:

- Android has `BridgeClient` methods for notification preferences, reminders, and habits (`BridgeClient.kt:217-360`, `:362-430`), but `rg` finds no UI call sites under `android-relay/app/src/main/java/com/openclaw/relay/ui`.
- Android has no `NudgePolicy` model or `BridgeClient` methods for `/sessions/:sessionId/nudge-policy`; only the TypeScript bridge side exists (`src/bridge/server.ts:295-320`, `:667-672`).
- Plan 36 called for settings/help surfaces for learned phrases, notification style, nudges, and reminder history.

Impact:

The bridge can store preferences, but users cannot manage most personalized collaborator settings from the Android app.

Recommended fix:

Add Android models/client methods for nudge policy, then build a settings surface for notification style, muted nudge types, learned phrases, reminders, and wearable approval preference.

### P2-2: Health and proof evidence are incomplete

Evidence:

- `BridgeRuntime.getHealthStatus()` reports `outboxPendingCount` using `getPendingCount('_global')` (`src/bridge/runtime.ts:86-94`), but outbox events are per relay session.
- `scripts/validate-proof-run.ts` accepts optional feature booleans for mDNS/quick-start/approval notification/Wear evidence per the plan notes, but the audit did not find a production path that populates those fields from actual observed flows.

Impact:

Diagnostics/proof can under-report outbox backlog and can still rely on manual booleans instead of measured evidence for the newly added plan-36 flows.

Recommended fix:

Report pending outbox counts per active session or as a sum across sessions. Populate proof fields from actual run state and reject claimed evidence that was not observed.

### P2-3: Outbox ack tombstones can grow without bound

Evidence:

- `OutboxStore.ack()` adds every acked event ID to `acked` and deletes the event (`src/personalization/outbox-store.ts:68-76`).
- `saveToDisk()` persists the full `acked` set (`src/personalization/outbox-store.ts:148-156`).
- No retention or pruning exists for acked tombstones.

Impact:

Long-running local bridge sessions can accumulate stale ack IDs indefinitely.

Recommended fix:

Persist ack tombstones with timestamps and prune after a short retention window, or avoid persisting tombstones once events are deleted.

## Recommended Remediation Order

1. Restore green gates: TypeScript typecheck, full Vitest, and Wear lint.
2. Fix the Android/bridge protocol version mismatch and add a cross-platform regression test.
3. Fix outbox ack semantics: session-bound ack on bridge, presentation-bound ack on Android.
4. Wire learning prompt confirm/reject end to end.
5. Make Android respect notification style and tone policy.
6. Finish Wear action plumbing or remove Wear from "done" scope until it is actionable and lint-clean.
7. Complete quick-start UI state/discovery startup and release transport decision.
8. Finish reminders and workspace nudges as real voice/breakpoint flows.
9. Add Android settings surfaces for preferences, nudges, habits, and reminders.
10. Tighten diagnostics/proof evidence so the proof artifact reflects observed flows.

## Final Recommendation

Do not move on as if plan 36 is done. The foundation is valuable, but plan 36 still has multiple P0/P1 blockers and the full verification gates are red. The next work item should be a hardening pass focused on the remediation order above.
