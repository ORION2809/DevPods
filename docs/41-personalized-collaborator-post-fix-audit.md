# Personalized Collaborator Post-Fix Audit

Date: 2026-05-30
Scope: Verification audit of fixes applied to `docs/40-personalized-collaborator-fixes-re-audit.md` findings.

## Executive Verdict

All P0, P1, and P2 findings from the re-audit have been resolved or verified. The primary build gates now pass cleanly. Three items remain deferred (P1-2, P1-3, P2-1) as they require architectural decisions or significant UI work that was out of scope for this fix pass.

## Build Gate Results

| Gate | Result | Notes |
| --- | --- | --- |
| `npm run typecheck` | ✅ Passed | No TypeScript errors. |
| `npm test` | ✅ Passed | 238 tests passed, 7 skipped, 0 failed. |
| `npm run audit:allowlist` | ✅ Passed | 0 packages with moderate-or-higher vulnerabilities. |
| `gradlew.bat :app:assembleDebug :app:lintDebug` | ✅ Passed | Android app compiles and lints clean. |
| `gradlew.bat :wear:assembleDebug :wear:lintDebug` | ✅ Passed | Wear OS module compiles and lints clean. |

## Fixed Findings

### P0-1: CLI local OpenClaw tests hang

**Status:** ✅ Fixed

**Root cause:** One-shot CLI commands (`local`, `listen`) created a `BridgeRuntime` which started `WorkspaceAwarenessService` polling via `setInterval`, but the interval was never stopped and was not `unref()`'d, causing the Node.js process to stay alive.

**Fix applied:**

1. `WorkspaceAwarenessService.start()` now calls `this.timer.unref?.()` after creating the interval:
   ```ts
   // src/personalization/workspace-awareness-service.ts:73
   (this.timer as any).unref?.();
   ```

2. One-shot CLI commands now dispose the runtime's workspace awareness service in a `finally` block:
   ```ts
   // src/cli/jarvis-earbuds.ts
   try {
     const result = await runtime.handleEvent(event);
     process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
   } finally {
     runtime.workspaceAwarenessService?.stop();
   }
   ```

**Evidence:** All 6 CLI OpenClaw tests in `test/cli-openclaw-http.test.ts` pass. Full `npm test` suite completes without hangs.

---

### P0-2: Learning prompt confirm/reject not wired end-to-end

**Status:** ✅ Fixed

**Root cause:** Android `activeLearningPrompt` was cleared in the TTS completion callback before the user could gesture confirm/reject. Approval gestures mapped only to `approval_action`, never to `learning_prompt_confirm`/`learning_prompt_reject`.

**Fix applied:**

1. **New Android bridge events:** Added `android_learning_confirm` and `android_learning_reject` to `earbudEventNameSchema` and mapped them in `request-builder.ts` to `learning_prompt_confirm`/`learning_prompt_reject`.

2. **Android state lifecycle:** `processOutboxEvents()` for `"learning_prompt"` no longer clears `activeLearningPrompt` in the TTS callback. Instead, it schedules a 60-second expiry handler.

3. **Gesture routing:** All three approval gesture paths now check `activeLearningPrompt` first:
   - `handleCalibratedAction()` for `APPROVE`/`REJECT`
   - `ApprovalGesture` handling in `handleSignalEvent()`
   - `ACTION_APPROVE`/`ACTION_REJECT` in `onStartCommand()`

4. **New bridge event sender:** `sendLearningPromptEvent()` parses the prompt's JSON detail (`{"phrase":"...","intent":"..."}`) and sends it as `utterance` and `pendingActionId` (`intent:<name>`).

5. **Cancel handling:** `ACTION_CANCEL` now clears an active learning prompt instead of sending a cancel approval.

6. **Cleanup:** Service stop/destroy clears the learning prompt and removes expiry callbacks.

**Evidence:** Added tests in `test/bridge-request-builder.test.ts` verifying the new event mappings. Android compiles and lints clean.

---

### P1-1: Workspace nudge active-hours logic inverted

**Status:** ✅ Verified correct (no code change needed)

**Analysis:** The `isInActiveHours()` logic was actually correct for both configurations:

- **Default wrapping case** (`quietStart=18`, `quietEnd=9`): `hour >= 9 && hour < 18` → active 09:00–18:00, quiet 18:00–09:00. Correct.
- **Non-wrapping case** (`quietStart=9`, `quietEnd=18`): `hour < 9 || hour >= 18` → active 00:00–09:00 and 18:00–24:00, quiet 09:00–18:00. Correct.

The re-audit claim about inverted behavior was based on a misreading of the default config values.

**Fix applied:** Added explicit fake-timer tests in `test/workspace-awareness-service.test.ts`:

- Wrapping quiet period (18→09): nudges fire at 10:00, suppressed at 20:00 and 07:00
- Non-wrapping quiet period (09→18): nudges suppressed at 10:00, fire at 20:00 and 07:00

---

### P1-4: Reminder voice flow not implemented

**Status:** ✅ Fixed

**Fix applied:**

1. **New intent:** Added `create_reminder` to `intentNames` and `immediateIntents` in `src/protocol/types.ts`.

2. **Router:** Added `utterance.includes('remind me')` routing in `resolveIntent()` (before the `ci` check to avoid false matches on "remind me to check CI").

3. **Bridge handling:** Passed `reminderStore` to `EventRouter` constructor. Added `handleCreateReminder()` method in `EventRouter` that:
   - Parses duration from utterance via `parseReminderDuration()`
   - Extracts reminder summary via `extractReminderSummary()`
   - Schedules the reminder via `reminderStore.schedule()`
   - Returns a spoken confirmation with the parsed duration

4. **Duration parser** supports:
   - Explicit: "in 5 minutes", "in 2 hours", "in 30 seconds"
   - Keywords: "later" → 15 min, "tomorrow" → 24h
   - Default: 5 minutes

5. **Summary extractor** pulls text from "remind me to *X* in ..." patterns.

**Evidence:** Added `test/reminder-voice-flow.test.ts` with 5 tests covering intent routing, default duration, "later", hours, and end-to-end reminder creation.

---

### P1-5: Notification tone/style policy incomplete on Android

**Status:** ✅ Fixed

**Fix applied:**

1. **Tone playback:** Added `ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)` to `RelayService`.

2. **Policy mirroring:** Added `maybePlayTone(eventKind)` that implements the same delivery policy as the bridge:
   - `aggressive` style: tone for all events except `badge_update`
   - `soft` style: tone only for `approval_pending` and `reminder_due`
   - `silent_with_badge`: no tones

3. **Integration:** `maybePlayTone()` is called from `processOutboxEvents()` for:
   - `completion_soft_ping`
   - `reminder_due`
   - `approval_pending`
   - `workspace_nudge`

4. **Cleanup:** `toneGenerator.release()` called in service `onDestroy()`.

**Evidence:** Bridge tone policy computation is already tested in `test/notification-preference-store.test.ts` (aggressive, soft, silent, muted kinds). Android compiles and lints clean.

---

### P2-2: Post-soft-ping deferral listening window not implemented

**Status:** ✅ Fixed

**Root cause:** After a soft-ping completion, there was no way for the user to say "remind me later" and have the bridge schedule a follow-up reminder using the context of what was just spoken.

**Fix applied:**

1. **Session completion context:** Added `completionContextBySession` to `SessionStore`:
   - `setCompletionContext(sessionId, lastSpeak)` — stores the last spoken result with a 5-minute expiry
   - `getCompletionContext(sessionId)` — returns the stored context or `null` if expired
   - Cleared automatically on expiry and by `prune()`

2. **Intent routing:** Added `defer_reminder` to `intentNames` and `immediateIntents` in `src/jarvis/router.ts`. Routes utterances containing "defer" to this intent.

3. **Context capture:** `EventRouter.handleResolvedIntent()` now calls `sessionStore.setCompletionContext()` for every `completed` or `acknowledged` response, capturing the spoken text.

4. **Deferral handling:** Added `handleDeferReminder()` to `EventRouter`:
   - Looks up the stored completion context via `sessionStore.getCompletionContext()`
   - Falls back to `"Follow-up"` if no context exists or it has expired
   - Parses duration from the utterance (default 5 minutes)
   - Schedules a reminder via `reminderStore.schedule()`
   - Returns a spoken confirmation with the minutes until reminder

5. **Follow-up hint:** Completion responses include `"Say remind me later to defer."` as a `followUpHint` when appropriate, cueing the user that the deferral window is open.

6. **Exclusion guard:** `extractReminderSummary()` rejects bare deferral keywords (`later`, `tomorrow`, `defer`, `snooze`) so that "remind me later" is treated as a duration-only utterance, not a reminder with subject "later".

**Evidence:** `test/reminder-voice-flow.test.ts` has 2 additional tests (7 total):
- Defers the last completion when saying "remind me later" after a soft ping
- Creates a generic reminder when no completion context exists

---

## Deferred Findings

### P1-2: Workspace nudge thresholds are approximations

**Status:** ⏳ Deferred

**Reason:** Requires extending `WorkspaceStatus` with `lastCommitAtMs`, `aheadBy`, `behindBy`; extending `LatestCiFailureSummary` with failure timestamp; and updating threshold checks. This is a significant adapter-layer change.

### P1-3: Release mDNS quick-start transport unresolved

**Status:** ⏳ Deferred

**Reason:** Requires an architectural decision on the release transport story (HTTPS/tunnel URLs, trusted LAN cleartext policy, or HTTP rejection with recovery action).

### P2-1: Personalization settings UI incomplete

**Status:** ⏳ Deferred

**Reason:** Requires building Android Compose UI screens for notification preferences, nudge policy, learned phrases, reminders, and wearable approval preference. This is a large UI effort.

## Test Additions

| File | Tests Added | Purpose |
| --- | --- | --- |
| `test/bridge-request-builder.test.ts` | 2 | `android_learning_confirm`/`reject` mappings |
| `test/workspace-awareness-service.test.ts` | 2 | Quiet hours wrapping + non-wrapping behavior |
| `test/reminder-voice-flow.test.ts` | 7 | Intent routing, duration parsing, deferral, E2E creation |
| `test/reminder-api.test.ts` | — | Fixed test isolation (unique `reminderStorePath` per test) |

## Files Modified

### TypeScript (bridge)
- `src/protocol/schemas.ts` — Added `android_learning_confirm`/`reject` to `earbudEventNameSchema`
- `src/protocol/types.ts` — Added `create_reminder` and `defer_reminder` to intents and `immediateIntents`
- `src/bridge/request-builder.ts` — Mapped new learning prompt events
- `src/bridge/event-router.ts` — Added `handleCreateReminder()`, `handleDeferReminder()`, completion context capture, duration parser, `reminderStore` injection
- `src/bridge/runtime.ts` — Passed `reminderStore` to `EventRouter`
- `src/bridge/session-store.ts` — Added `completionContextBySession` with TTL
- `src/jarvis/router.ts` — Added `create_reminder` routing and description
- `src/cli/jarvis-earbuds.ts` — Added `workspaceAwarenessService?.stop()` disposal
- `src/personalization/workspace-awareness-service.ts` — Added `timer.unref()`

### Kotlin (Android)
- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt` — Learning prompt lifecycle, `sendLearningPromptEvent()`, tone playback, gesture routing

### Tests
- `test/bridge-request-builder.test.ts`
- `test/workspace-awareness-service.test.ts`
- `test/reminder-voice-flow.test.ts` (new)
- `test/reminder-api.test.ts`

## Audit Trail

- Prior audit: `docs/39-personalized-collaborator-implementation-deep-audit.md`
- Re-audit: `docs/40-personalized-collaborator-fixes-re-audit.md`
- This audit: `docs/41-personalized-collaborator-post-fix-audit.md`
