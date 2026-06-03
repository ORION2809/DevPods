# Personalized Collaborator Feature Implementation Plan

Generated: 2026-05-27

## Verdict

Build these features as an additive intelligence and UX layer on top of the current relay architecture. Do not replace the bridge runtime, calibrated gesture router, approval gates, provider registry, setup proof, or Android relay service.

The missing foundation is a bridge-to-Android event outbox plus local personalization stores. Once that exists, voice habit learning, soft notifications, reminders, proactive nudges, fast onboarding, and wearable approvals all become small features instead of separate architectures.

## Current Architecture To Preserve

- Bridge HTTP server: `src/bridge/server.ts`
- Bridge event flow: `BridgeRuntime -> EventRouter -> JarvisRuntime`
- Intent policy and approvals: `src/policy/*`, `SessionStore`
- Background tasks: `BackgroundCommandScheduler`
- Android relay request path: `RelayService -> BridgeClient -> /events`
- Android approval state: `pendingApprovalRequest`, `pendingActionId`
- Android notifications: foreground service notification actions
- Earbud input: `SignalProviderRegistry`, `CalibratedGestureRouter`
- Device truth: calibration profile, capability matrix, proof runs

## Additive Foundation

### 1. Bridge Outbox

Add `BridgeOutbox` for async events that Android can poll or long-poll.

Event types:

- `completion_soft_ping`
- `completion_full_report`
- `reminder_due`
- `workspace_nudge`
- `approval_pending`
- `learning_prompt`
- `badge_update`

Endpoints:

- `GET /sessions/:sessionId/outbox?after=<cursor>`
- `POST /sessions/:sessionId/outbox/:eventId/ack`

Rules:

- Every outbox event has `id`, `sessionId`, `createdAtMs`, `expiresAtMs`, `priority`, `kind`, `summary`, `detail`, and optional `actionId`.
- Android acks events after presentation.
- Bridge keeps events local under `runtime-data/`.
- No cloud sync.

### 2. Local Preferences And Personalization

Add bridge-side stores:

- `VoiceHabitStore`: learned phrase -> intent mapping.
- `NotificationPreferenceStore`: aggressive, soft, silent-with-badge.
- `ReminderStore`: deferred reminder timers.
- `WorkspaceAwarenessStore`: latest git/test/CI snapshots and muted nudge types.

Add Android-side preferences:

- notification style
- nudge mute settings
- learned phrase visibility/edit/delete
- quick-start setup status
- wearable approval preference

## Feature Plans

### 1. Voice Habit Learning

Current fit:

- `src/jarvis/router.ts` currently maps utterances with fixed rules and defaults to `quick_status`.

Implementation:

1. Replace `resolveIntent()` with `IntentResolutionEngine`.
2. Return `{ intent, confidence, source, needsConfirmation, alternatives }`.
3. Check `VoiceHabitStore` before fixed rules.
4. If confidence is low, create `learning_prompt` outbox event: "Did you mean summarize diff?"
5. Android shows/speaks prompt and maps calibrated right tap to yes, left tap/no to re-listen.
6. Bridge records confirmed phrase -> intent.
7. Promote mapping after 2 or 3 confirmations.
8. Add Settings screen for learned phrases: view, edit intent, delete, reset all.

Acceptance:

- "what did I break" can become `summarize_diff` locally.
- Wrong guesses do not execute actions.
- Learned destructive intents still pass normal approval policy.
- Diagnostics redact raw phrase unless user opts into including it.

### 2. Interruptible Notifications And Deferred Reminders

Current fit:

- Background completions already exist in `JarvisRuntime`.
- Current bridge notifier skips Android-originated sessions, so Android misses async completions.

Implementation:

1. Route background completion callbacks into `BridgeOutbox`.
2. Add `NotificationPolicyEngine` to choose soft/full/silent behavior.
3. Android `RelayService` long-polls outbox while service is running.
4. Soft ping: play short tone plus one word through existing TTS path.
5. Tap once after soft ping expands to full report.
6. During post-ping listening window, parse "remind me in 5" / "remind me later".
7. Store reminder in `ReminderStore`; timer emits `reminder_due`.
8. Add settings: aggressive, soft, silent-with-badge.

Acceptance:

- Test completion defaults to soft ping, not full interruption.
- User can expand, ignore, or defer.
- Deferred reminder survives bridge restart.
- No reminder speaks during active listening or active TTS.

### 3. Ambient Awareness And Proactive Nudges

Current fit:

- `getWorkspaceStatus()` and `getLatestCiFailure()` already exist.
- Background task results already know success/failure.

Implementation:

1. Add `WorkspaceAwarenessService` in the bridge.
2. Poll allowlisted workspaces for git status, recent test result, and CI state.
3. Track thresholds: changed file count, time since last commit, consecutive test failures, CI red duration.
4. Queue nudges into `BridgeOutbox`, never direct TTS.
5. Android delivers nudges only at breakpoints:
   - earbuds inserted/connected
   - background task completed
   - app idle for configured window
   - user explicitly asks status
6. Add voice mute commands: "stop telling me about uncommitted files".
7. Add settings for threshold and mute behavior.

Acceptance:

- Nudges never interrupt active speech/listening/approval.
- Repeated failed tests can trigger "Want me to pull the last error?"
- Muted nudge types stay muted.
- All workspace reads stay inside allowlisted workspace roots.

### 4. Streamlined Onboarding

Current fit:

- Pairing exists via QR/link.
- Setup wizard and calibration exist, but first-use path is still heavy.

Implementation:

1. Add bridge mDNS advertisement for `_devpods._tcp`.
2. Add Android `NsdManager` discovery.
3. App shows discovered bridge: "Found DevPods on this computer."
4. Use one-tap confirm on Android plus pairing page confirm/code on bridge.
5. Issue relay token through existing `/pairing/verify`.
6. Enter limited quick-start mode after pairing:
   - read-only intents only
   - push-to-talk allowed
   - calibration marked required
7. After first successful spoken response, offer "Fine-tune earbuds" calibration.
8. Keep full setup wizard as Health Check, not the first wall.

Acceptance:

- First spoken response target: under 3 minutes.
- No QR/IP required on the happy path.
- Quick-start never claims full Ready until calibration/proof passes.
- Failure recovery gives exactly one next action.

Important dependency:

- This must align with the beta transport decision. If release Android blocks HTTP, mDNS must advertise HTTPS/tunnel URLs or release must explicitly allow trusted LAN bridge HTTP.

### 5. Wear OS Tile, Lock Screen, And Approval Notifications

Current fit:

- Android already has `ACTION_APPROVE`, `ACTION_REJECT`, and `ACTION_CANCEL`.
- Pending approvals already include summary, risk class, action ID, and expiry.

Implementation order:

1. Add high-priority Android approval notification first.
2. Notification actions call existing service actions with pending `actionId`.
3. Show lock-screen-safe summary, risk class, countdown, approve/reject/cancel.
4. Add distinct haptic patterns for standard vs hard approval.
5. Add Wear OS notification mirroring support through the notification.
6. Add optional `:wear` module later with a TileService:
   - idle/listening/running/approval pending
   - approve/reject buttons
   - countdown
7. Sync tile state from Android relay via Wear Data Layer.

Acceptance:

- Approval can be handled without opening phone app.
- Expired approvals cannot execute.
- Ambiguous calibration gestures cannot approve/reject.
- Lock-screen display does not leak sensitive command detail unless user enables it.

## Implementation Sequence

1. Fix existing beta blockers first: protocol version, release transport, signing, T4 proof semantics.
2. Add bridge outbox and Android outbox polling. ✅
3. Add notification preferences and soft completion pings. ✅
4. Add deferred reminders. ✅
5. Add voice habit learning.
6. Add workspace awareness/nudges.
7. Add streamlined mDNS onboarding and quick-start mode. ✅
8. Add approval notification and lock-screen actions. ✅
9. Add Wear OS tile as a separate module. ✅
10. Extend proof artifacts and diagnostics for all new behavior. ✅

## Code Touchpoints

Bridge:

- `src/protocol/schemas.ts`: outbox, learning prompt, notification preference, reminder, nudge schemas.
- `src/bridge/server.ts`: outbox endpoints, discovery metadata, preference endpoints.
- `src/bridge/runtime.ts`: enqueue async Android notifications.
- `src/bridge/session-store.ts`: cursor and pending outbox state.
- `src/jarvis/router.ts`: replace direct resolver with confidence-based resolver.
- `src/jarvis/runtime.ts`: completion events, reminder parsing, nudge hooks.
- `src/adapters/git.ts`, `src/adapters/ci.ts`: workspace awareness snapshots.
- new `src/personalization/*`: habit, notification, reminder, nudge stores.

Android:

- `BridgeClient.kt`: outbox long-poll, ack, preferences.
- `RelayService.kt`: outbox loop, notification presenter, reminder listen window, approval notification.
- `RelayModels.kt`: outbox events, learned phrases, notification settings, nudge settings.
- `RelayStateStore.kt`: store active prompt, notification badge, nudge state.
- `RelayConfigStorage.kt` or new storage: local Android preferences.
- `HomeScreen.kt`: badges, soft notification state.
- `ActivityScreen.kt`: approval and reminder history.
- `DeviceScreen.kt`: calibration remains the device truth.
- `HelpScreen.kt` or new Settings screen: learned phrases, notification style, nudges.
- `android-relay/settings.gradle.kts`: add `:wear` only when approval notification path is stable.

## Non-Breaking Rules

- Do not bypass policy approvals with learned phrases.
- Do not mark Ready from quick-start alone.
- Do not let nudges interrupt active listening, TTS, or approvals.
- Do not send personalization data to cloud services.
- Do not duplicate calibration logic inside these features.
- Do not route approval from ambiguous gestures.
- Do not make Wear OS required for core approval.

## Proof And Tests

Bridge tests:

- intent resolver confidence and habit promotion
- wrong guess does not execute
- outbox cursor and ack behavior
- reminder scheduling survives restart
- notification policy soft/full/silent behavior
- nudge threshold and mute behavior
- approval notification payload redaction

Android tests:

- outbox polling lifecycle
- soft ping -> expand -> defer
- learned phrase settings edit/delete
- quick-start does not mark Ready
- approval PendingIntent includes action ID and respects expiry
- calibration router still gates approval

E2E proof additions:

- T1: learned phrase confirmation loop.
- T1: background completion produces soft ping event.
- T1: deferred reminder fires.
- T1: nudge queues but waits for idle.
- T4: calibrated earbud tap expands soft ping and handles approval.

## Beta Definition

These features are beta-ready when a new user can:

1. Discover the bridge without typing an IP.
2. Get one safe spoken response quickly.
3. Calibrate earbuds afterward for reliable control.
4. Teach one natural phrase locally.
5. Receive a soft completion notification and defer it.
6. Hear at least one useful workspace nudge at a natural break.
7. Approve/reject from notification or watch without opening the app.

All of that must happen without weakening the existing proof, approval, calibration, and release gates.

## Implementation Notes

### Step 7 — mDNS Onboarding + Quick-Start (Completed 2026-05-29)

- Bridge: `src/bridge/mdns.ts` advertises `_devpods._tcp` via `bonjour-service`. Auto-starts on non-loopback bind.
- Bridge: `POST/DELETE/GET /sessions/:sessionId/quick-start` endpoints in `server.ts`.
- Bridge: `SessionStore` tracks `quickStartSessions` set; `evaluateIntentPolicy` blocks non-read-only intents when quick-start enabled.
- Android: `BridgeDiscoveryManager.kt` uses `NsdManager` to discover `_devpods._tcp` and populates `RelayUiState.discoveredBridges`.
- Android: `SetupWizardScreen.kt` shows discovered bridges; `RelayViewModel.selectDiscoveredBridge()` pairs from discovery.
- Tests: `test/mdns-advertisement.test.ts` (3 pass), `test/quick-start-policy.test.ts` (3 pass).

### Step 8 — Approval Notifications (Completed 2026-05-29)

- Android: `RelayService.postApprovalNotification()` creates high-importance channel with lock-screen visibility, distinct vibration patterns, approve/reject/cancel actions with `EXTRA_PENDING_ACTION_ID`.
- Android: `NotificationPreference` extended with `lockScreenDetailEnabled`.
- Android: `dismissApprovalNotification()` clears on action/expiry; auto-dismiss timer respects expiry.
- Bridge: Approval outbox events include `actionId` and detail JSON with `riskClass`, `expiresInMs`.
- Tests: `test/approval-outbox.test.ts` (5 pass).

### Step 9 — Wear OS Tile (Completed 2026-05-29)

- Module: `:wear` created with `DevPodsTileService` extending `TileService`.
- Tile: Displays `approval_pending` (with countdown + Approve/Reject chips), `listening`, `responding`, or `idle` state.
- Tile: Uses `CallbackToFutureAdapter` for coroutine-safe `ListenableFuture` return.
- Phone sync: `WearDataSync.kt` observes `RelayStateStore.state`, maps to `WearTileState`, pushes to Wearable Data Layer at `/devpods/state` via `PutDataMapRequest`.
- `RelayService` starts `WearDataSync` on create.
- Android build: both `:app` and `:wear` `assembleDebug` succeed.

### Step 10 — Proof Artifacts Extension (Completed 2026-05-29)

- `scripts/validate-proof-run.ts`: Added optional boolean fields `mdnsDiscovered`, `quickStartUsed`, `approvalNotificationHandled`, `wearTileSynced`, `lockScreenDetailEnabled` to `ProofRun` interface with type validation.
- `test/validate-proof-run.test.ts`: Added 2 new tests for feature evidence fields (accept valid, reject non-boolean).
- Template: `simulation/android-relay/proof-runs/TEMPLATE-personalized-collaborator-features.json` created with all new fields populated.
- Tests: 14 proof-run validator tests pass; 25 total feature tests pass.
