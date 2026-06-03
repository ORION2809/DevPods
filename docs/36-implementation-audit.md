# Plan 36 Implementation Audit

**Date:** 2026-05-30  
**Auditor:** Kimi Code CLI  
**Scope:** Full implementation of `docs/36-personalized-collaborator-feature-implementation-plan.md` (Steps 1–10)  
**Baseline:** 210 tests passed, 7 skipped, 2 pre-existing failures; Android debug build green; TypeScript typecheck clean.

---

## Executive Summary

The **additive foundation** (bridge outbox, local stores, HTTP endpoints) is **solid and well-tested**. Steps 1–6 are largely implemented with good test coverage.  

**Critical gaps** exist in:
1. **Android-side feature completion** — policy styles ignored, learning prompt gestures broken, nudge delivery not breakpoint-gated.
2. **Approval notification flow** — `approval_pending` outbox event is defined but **never enqueued** by the bridge, making lock-screen approval notifications impossible.
3. **Unimplemented steps 7–10** — mDNS onboarding, quick-start mode, Wear OS tile, and extended diagnostics are entirely absent.

**Production-readiness verdict:** The bridge-side personalization layer is beta-ready. The Android side is **partial** — core outbox polling and TTS delivery work, but several user-facing features (learning gesture loop, approval notifications, settings screens) are incomplete. Steps 7–10 are not started.

---

## Audit Methodology

For each feature, we checked:
- **Schema compliance** — does the Zod schema match the plan spec?
- **Endpoint coverage** — does the bridge expose the required HTTP surface?
- **Android client coverage** — does `BridgeClient.kt` call every endpoint?
- **Android UI/behavior** — does `RelayService.kt` process events correctly?
- **Test coverage** — are there tests for success, failure, and edge cases?
- **Persistence** — does state survive bridge/Android restart?
- **Security/safety** — are approvals preserved? Is data local-only?

---

## 1. Bridge Outbox (Step 2) — ✅ Solid with Minor Gaps

| Checkpoint | Status | Notes |
|---|---|---|
| `OutboxStore` enqueue/poll/ack | ✅ | `obx_` IDs, cursor pagination (50/page), TTL purge |
| `GET /sessions/:id/outbox?after=` | ✅ | Returns validated `outboxPollResponseSchema` |
| `POST /sessions/:id/outbox/:eventId/ack` | ⚠️ | **Ignores `sessionId` path param** — allows cross-session acking (Gap B) |
| File persistence under `runtime-data/` | ✅ | `outbox.json` with snapshot save/load |
| No cloud sync | ✅ | Explicitly local-only |
| `badge_update` kind | ❌ | Defined in schema, **never produced or handled** |
| `approval_pending` kind | ❌ | Defined in schema, **never enqueued by bridge** |
| Health endpoint outbox count | ❌ | Hardcoded `getPendingCount('_global')` — **always returns 0** |
| `acked` set pruning | ❌ | Acked IDs accumulate indefinitely in snapshot file |
| `detail` field optional vs required | ⚠️ | Schema allows `optional()`; plan says required |

### Gap Details

**Gap B — Cross-session ack vulnerability**
```typescript
// server.ts
const { eventId } = outboxAckMatch;  // sessionId extracted but unused
const acked = runtime.outboxStore.ack(eventId);
```
`OutboxStore.ack()` does not verify `event.sessionId === sessionId`. Any valid event ID can be acked from any session path.  
**Fix:** Validate session ownership before acking, or remove `sessionId` from the ack URL if not needed.

**Gap E — Unbounded `acked` set growth**
`loadFromDisk()` repopulates `this.acked` from the snapshot, but acked IDs are never pruned. Over months the JSON file grows with stale acked IDs.  
**Fix:** Periodically compact the snapshot by dropping acked IDs older than a retention window.

---

## 2. Notification Preferences (Step 3) — ✅ Bridge-Solid, Android-Partial

| Checkpoint | Status | Notes |
|---|---|---|
| `NotificationPreferenceStore` | ✅ | File-backed, Zod-validated, debounced save |
| Three styles (`aggressive`/`soft`/`silent_with_badge`) | ✅ | Bridge computes `DeliveryPolicy` correctly |
| `GET/POST /sessions/:id/preferences/notifications` | ✅ | Both endpoints present and tested |
| `getPolicy()` correctness | ✅ | `mutedKinds` early return; style-based derivation correct |
| Android fetches preferences after health check | ✅ | `checkBridgeHealth()` → `fetchNotificationPreferences()` |
| Android **uses** `style` field | ❌ | `processOutboxEvents()` reads `softPingTtsEnabled`/`nudgeTtsEnabled`/`reminderTtsEnabled` but **never `style`** |
| `NotificationPolicyEngine` | ❌ | Plan calls for dedicated engine; logic is inlined into `NotificationPreferenceStore.getPolicy()` |
| Android badge count | ❌ | `outboxBadgeCount` tracked in `RelayStateStore` but **never pushed to launcher badge** |
| Tone/soft-ping sound | ❌ | `DeliveryPolicy.tone` computed on bridge; Android **never plays tones** |

### Gap Details

**Android ignores `style` field**
```kotlin
// RelayService.kt processOutboxEvents()
"completion_soft_ping" -> {
    if (!prefs.softPingTtsEnabled) { ... }  // ignores prefs.style!
}
```
With `style = "silent_with_badge"`, the bridge policy says `speak=false`, but Android still speaks if `softPingTtsEnabled=true`.  
**Fix:** Use the bridge-computed `DeliveryPolicy` (add a `/policy` endpoint) or replicate style logic on Android.

---

## 3. Deferred Reminders (Step 4) — ✅ Core Solid, Voice Trigger Missing

| Checkpoint | Status | Notes |
|---|---|---|
| `ReminderStore` schedule/cancel/ack/list | ✅ | File-backed, recurring daily/weekly, timer-based firing |
| `reminder_due` outbox event | ✅ | Fires into outbox; Android speaks "Reminder: …" |
| Survives bridge restart | ✅ | Loads from `runtime-data/reminders.json` |
| Server endpoints | ✅ | `GET/POST /sessions/:id/reminders`, `DELETE /sessions/:id/reminders/:id` |
| Android handles `reminder_due` | ✅ | Respects `reminderTtsEnabled` and `canSpeakOutboxEvent()` |
| **Voice parsing "remind me in 5"** | ❌ | No utterance parsing for reminder creation exists |
| **Post-soft-ping listen window** | ❌ | No follow-up mic-open or transcript parsing after soft ping |
| Recurring reschedule | ✅ | Daily (+86.4M ms) and weekly (+604.8M ms) |

### Gap Details

**Voice-triggered reminder creation** and **post-ping listen window** are both called out in the plan acceptance criteria but are unimplemented. These require:
1. A new intent rule in `router.ts` for reminder utterances.
2. A timed listening window on Android after soft ping TTS completes.
3. Bridge parsing to extract duration and enqueue into `ReminderStore`.

---

## 4. Voice Habit Learning (Step 5) — ✅ Bridge Solid, Android Gesture Loop Broken

| Checkpoint | Status | Notes |
|---|---|---|
| `VoiceHabitStore` resolve/confirm/set/delete | ✅ | File-backed, promotion threshold = 2, normalization |
| `IntentResolutionEngine` | ✅ | 3-layer: habits → fixed rules → fallback |
| `learning_prompt` outbox event | ✅ | Enqueued with "Did you mean …?" when confidence is low |
| Wrong guesses do not execute | ✅ | `handleIntentRequest` returns prompt without calling `handleResolvedIntent` |
| Destructive intents still require approval | ✅ | Promoted habits route through normal policy evaluation |
| Promotion threshold (2) | ✅ | `PROMOTION_THRESHOLD` in store; tests verify |
| Android speaks learning prompt | ✅ | `processOutboxEvents()` stores prompt, speaks it, clears after TTS |
| **Android gestures confirm/reject prompt** | ❌ | `handleCalibratedAction` has **no branch** for learning prompt confirm/reject |
| **Android sends `learning_prompt_confirm` event** | ❌ | `APPROVE`/`REJECT` gestures send `android_approve`/`android_reject`, not learning prompt events |
| **Settings screen for learned phrases** | ❌ | No Compose screen exists |
| **Diagnostic opt-in for raw phrases** | ❌ | No opt-in flag; raw phrases are always redacted |
| `event-router.ts` hardcodes threshold "2" | ⚠️ | Line 179 checks `entry.confirmationCount >= 2` inline instead of using `PROMOTION_THRESHOLD` |

### Gap Details

**Broken learning gesture loop (Critical)**
The Android side speaks the learning prompt but provides **no gesture path** to confirm or reject it. The user cannot complete the learning loop from earbuds alone.  
**Fix:** Add `learning_prompt_confirm` and `learning_prompt_reject` handling to `handleCalibratedAction()` and the gesture router. Populate `pendingActionId` from the outbox event's `detail` field.

---

## 5. Workspace Awareness & Proactive Nudges (Step 6) — ✅ Core Works, Thresholds Partial

| Checkpoint | Status | Notes |
|---|---|---|
| `WorkspaceAwarenessService` polling | ✅ | Every 60s, fetches git + CI status |
| `NudgePolicyStore` | ✅ | File-backed, Zod-validated |
| Server endpoints `GET/POST /nudge-policy` | ✅ | Both present and tested |
| Nudges queued to outbox | ✅ | `kind: 'workspace_nudge'` with `priority: 'normal'` |
| Android handles `workspace_nudge` | ✅ | Speaks summary if `nudgeTtsEnabled` and `canSpeakOutboxEvent()` |
| Quiet hours | ✅ | Configurable; wrap-aware logic |
| Per-type cooldown | ✅ | Prevents same-type spam |
| Per-cycle cap | ✅ | `maxNudgesPerCycle` default = 3 |
| `uncommitted_files` threshold | ✅ | Uses `changedFilesMin` |
| `tests_failing` threshold | ✅ | Tracks consecutive failures per workspace |
| **`stale_branch` threshold** | ❌ | Ignores `staleBranchHours`; git adapter lacks `lastCommitAtMs` |
| **`ci_red` duration threshold** | ❌ | Ignores `ciRedHours`; CI adapter lacks failure timestamp |
| **Breakpoint delivery** | ❌ | Android polls every 10s and speaks immediately; no breakpoint gating |
| **Voice mute commands** | ❌ | No intent to mute/unmute nudge types |
| `ready_to_push` | ⚠️ | Approximates by clean working tree; no ahead/behind check |
| Workspace reads stay allowlisted | ✅ | Paths validated by `loadWorkspaceRegistry()` |

### Gap Details

**Breakpoint delivery (High)**
The plan requires nudges at 4 breakpoints: earbuds inserted, background task completed, app idle, user asks status. Currently, nudges are delivered immediately on the 10s poll loop.  
**Fix:** Add a `nudge_buffer` to `RelayStateStore` that queues nudges until a breakpoint occurs, then flushes them.

**Stale branch / CI red duration**
The `workspaceSnapshotSchema` defines `lastCommitAtMs` and `lastCiFailureAtMs`, but neither the git nor CI adapters return these fields. The thresholds in `nudgeThresholdSchema` (`staleBranchHours`, `ciRedHours`) are therefore ignored.  
**Fix:** Extend `getWorkspaceStatus()` to return `lastCommitAtMs` (via `git log -1 --format=%ct`) and `getLatestCiFailure()` to return `lastCiFailureAtMs` (from the workflow run `created_at`).

---

## 6. Android BridgeClient Coverage

| Endpoint | BridgeClient Method | Status |
|---|---|---|
| `GET /sessions/:id/outbox` | `pollOutbox` | ✅ |
| `POST /sessions/:id/outbox/:eventId/ack` | `ackOutboxEvent` | ✅ |
| `GET /sessions/:id/preferences/notifications` | `getNotificationPreferences` | ✅ |
| `POST /sessions/:id/preferences/notifications` | `setNotificationPreferences` | ✅ |
| `GET /sessions/:id/reminders` | `listReminders` | ✅ |
| `POST /sessions/:id/reminders` | `createReminder` | ✅ |
| `DELETE /sessions/:id/reminders/:id` | `cancelReminder` | ✅ |
| `GET /habits` | `listHabits` | ✅ |
| `POST /habits` | `createHabit` | ✅ |
| `DELETE /habits/:phrase` | `deleteHabit` | ✅ |
| `GET /sessions/:id/nudge-policy` | **MISSING** | ❌ |
| `POST /sessions/:id/nudge-policy` | **MISSING** | ❌ |

**Fix:** Add `NudgePolicy` Kotlin data class and `getNudgePolicy`/`setNudgePolicy` methods to `BridgeClient.kt`.

---

## 7. Schema Audit

| Schema | Exists | Used | Type Exported | Android Model |
|---|---|---|---|---|
| `outboxEventSchema` | ✅ | ✅ | ✅ | `BridgeOutboxEvent` |
| `notificationPreferenceSchema` | ✅ | ✅ | ✅ | `NotificationPreference` |
| `reminderSchema` | ✅ | ✅ | ✅ | `Reminder` |
| `learnedPhraseSchema` | ✅ | ✅ | ✅ | `LearnedPhrase` |
| `nudgePolicySchema` | ✅ | ✅ | ✅ | **Missing** |
| `workspaceSnapshotSchema` | ✅ | ❌ | ❌ | **Missing** |
| `voiceHabitSnapshotSchema` | ✅ | ❌ | ✅ | `VoiceHabitSnapshot` (unused) |
| `confirmLearningRequestSchema` | ✅ | ❌ | ✅ | **Missing** |

**Orphaned schemas:** `workspaceSnapshotSchema`, `voiceHabitSnapshotSchema`, `confirmLearningRequestSchema` are defined but never imported outside `schemas.ts`.

---

## 8. Unimplemented Steps 7–10

### Step 7: Streamlined Onboarding

| Feature | Status | Notes |
|---|---|---|
| mDNS/Bonjour advertisement | ❌ | No code anywhere |
| Android `NsdManager` discovery | ❌ | No code anywhere |
| One-tap pairing confirm | ❌ | No code anywhere |
| Quick-start mode | ❌ | No `QUICK_START` phase; `UserOnboardingManager` lacks quick-start flag |
| Read-only intents in quick-start | ❌ | No gating logic |
| Calibration offered after first spoken response | ❌ | No trigger logic |

### Step 8: Approval Notifications & Lock Screen

| Feature | Status | Notes |
|---|---|---|
| `approval_pending` enqueued by bridge | ❌ | Event kind exists but **never produced** |
| High-priority Android notification | ❌ | Foreground service uses `CATEGORY_TRANSPORT` / `IMPORTANCE_LOW` |
| Lock-screen-safe summary | ❌ | No `setVisibility()` call |
| Countdown timer in notification | ❌ | No UI |
| Approve/reject/cancel actions in notification | ❌ | Service notification has Talk/Retry/Cancel/Stop only |
| Distinct haptic patterns | ❌ | No haptic differentiation |
| Wear OS notification mirroring | ❌ | No code |

### Step 9: Wear OS Tile

| Feature | Status | Notes |
|---|---|---|
| `:wear` module | ❌ | Not in `settings.gradle.kts` |
| `TileService` | ❌ | No code |
| Wear Data Layer sync | ❌ | No code |

### Step 10: Proof & Diagnostics Extensions

| Feature | Status | Notes |
|---|---|---|
| Outbox event history in diagnostics | ❌ | Not in `DiagnosticExport` |
| Learning prompt state in diagnostics | ❌ | Not in `DiagnosticExport` |
| Notification preference state in diagnostics | ❌ | Not in `DiagnosticExport` |
| Reminder list in diagnostics | ❌ | Not in `DiagnosticExport` |
| Workspace nudge history in diagnostics | ❌ | Not in `DiagnosticExport` |
| Voice habit snapshot in diagnostics | ❌ | Not in `DiagnosticExport` |
| Voice proof run extensions | ✅ | `interruptionTargetMetCount`, `audioProbeSuccessCount` added |

---

## 9. Safety & Non-Breaking Rules Compliance

| Rule | Status | Notes |
|---|---|---|
| Do not bypass policy approvals with learned phrases | ✅ | Promoted habits still route through `evaluateIntentPolicy` |
| Do not mark Ready from quick-start alone | ✅ | Quick-start does not exist yet |
| Do not let nudges interrupt listening/TTS/approvals | ⚠️ | `canSpeakOutboxEvent()` guards this, but nudges are not breakpoint-gated |
| Do not send personalization data to cloud | ✅ | All stores are file-backed local only |
| Do not duplicate calibration logic | ✅ | No calibration logic duplicated |
| Do not route approval from ambiguous gestures | ✅ | Gesture router unchanged |
| Do not make Wear OS required | ✅ | Wear OS does not exist yet |

---

## 10. Test Coverage Audit

| Feature Area | Tests | Coverage Quality |
|---|---|---|
| Outbox store | ✅ | Cursor, ack, TTL, persistence |
| Notification preferences | ✅ | All 3 styles, muted kinds, get/set |
| Reminder store | ✅ | Schedule, cancel, recurring, persistence |
| Voice habit store | ✅ | Resolve, confirm, promotion, persistence |
| Intent resolution engine | ✅ | Habit layer, fixed rules, fallback |
| Workspace awareness service | ✅ | 7 tests: thresholds, quiet hours, cooldown, CI-red |
| Nudge policy store | ✅ | 4 tests: defaults, set, reset, persistence |
| Nudge policy API | ✅ | 3 tests: GET, POST, invalid payload |
| Learning prompt API | ❌ | **Zero tests** for `handleLearningPromptConfirm`/`Reject` |
| `approval_pending` enqueue | ❌ | **Zero tests** — feature not implemented |
| Android outbox polling | ❌ | **No unit tests** for `processOutboxEvents()` logic |
| Android preference application | ❌ | **No tests** for style-based behavior |

---

## Priority-Ranked Remediation List

### P0 — Critical (Beta Blocker)

1. **Fix learning prompt gesture loop on Android** — `handleCalibratedAction` must handle learning prompt confirm/reject and send the correct events to the bridge. Without this, voice habit learning is non-functional end-to-end.
2. **Enqueue `approval_pending` outbox events** — When an intent requires approval, the bridge should enqueue an `approval_pending` event so Android can surface a notification. Without this, wearable/lock-screen approval is impossible.
3. **Fix ack endpoint session validation** — `POST /sessions/:id/outbox/:eventId/ack` must verify event ownership.

### P1 — High (Major Feature Gap)

4. **Android `BridgeClient` nudge policy methods** — Add `getNudgePolicy`/`setNudgePolicy` to consume the server endpoints.
5. **Breakpoint-gated nudge delivery** — Buffer nudges on Android and flush at breakpoints (earbuds inserted, task completed, idle, status request).
6. **Android respects `style` field** — `processOutboxEvents()` should use `style` (`aggressive`/`soft`/`silent_with_badge`) instead of only boolean flags.
7. **Add `lastCommitAtMs` to git adapter** — Enable `staleBranchHours` threshold.
8. **Add `lastCiFailureAtMs` to CI adapter** — Enable `ciRedHours` threshold.

### P2 — Medium (Polish & Completeness)

9. **Compact `acked` set in OutboxStore** — Prevent unbounded file growth.
10. **Fix health endpoint outbox count** — Count across all sessions instead of `_global`.
11. **Voice-triggered reminder creation** — Parse "remind me in 5" utterances.
12. **Post-soft-ping listen window** — Allow deferred reminder commands after soft ping.
13. **Add Kotlin models for `NudgePolicy`, `WorkspaceSnapshot`** — Type safety on Android.
14. **Settings screen for learned phrases** — Android Compose UI.
15. **Diagnostic opt-in for raw phrases** — Allow users to include raw transcripts in exports.
16. **Add `badge_update` production/handling** — Or remove the kind from schema if unused.

### P3 — Low (Nice to Have / Future Work)

17. **mDNS onboarding** — Step 7 from plan.
18. **Quick-start mode** — Step 7 from plan.
19. **Approval notifications with lock-screen actions** — Step 8 from plan.
20. **Wear OS tile** — Step 9 from plan.
21. **Extended diagnostics** — Step 10 from plan.
22. **Tone/soft-ping sound on Android** — Audio notification chime.
23. **Notification badge dot on Android launcher** — Visual badge.

---

## Appendix: Files Touched by Implementation

### New Files (Bridge)
- `src/personalization/outbox-store.ts`
- `src/personalization/notification-preference-store.ts`
- `src/personalization/reminder-store.ts`
- `src/personalization/voice-habit-store.ts`
- `src/personalization/nudge-policy-store.ts`
- `src/personalization/workspace-awareness-service.ts`
- `src/jarvis/intent-resolution-engine.ts`

### New Files (Tests)
- `test/workspace-awareness-service.test.ts`
- `test/nudge-policy-store.test.ts`
- `test/nudge-policy-api.test.ts`

### Modified Files (Bridge)
- `src/protocol/schemas.ts` — Added outbox, preference, reminder, habit, nudge schemas
- `src/bridge/server.ts` — Added outbox, preference, reminder, habit, nudge endpoints
- `src/bridge/runtime.ts` — Wired in all personalization stores and services
- `src/bridge/event-router.ts` — Added learning prompt handling, intent resolution engine
- `src/personalization/outbox-store.ts` — Added `getAllSessionIds()`

### Modified Files (Android)
- `RelayService.kt` — Outbox polling loop, event processing
- `RelayModels.kt` — Outbox, preference, reminder, habit data classes
- `RelayStateStore.kt` — State management for personalization
- `BridgeClient.kt` — HTTP client for new endpoints
