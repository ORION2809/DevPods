# Personalized Collaborator Plan 36 Closure Audit

Date: 2026-06-01
Scope: Code verification against `docs/36-personalized-collaborator-feature-implementation-plan.md` and supersession check for `docs/42-personalized-collaborator-final-code-audit.md`.

## Verdict

Plan 36 is ready to mark complete.

The previous final audit (`docs/42-personalized-collaborator-final-code-audit.md`) is now stale. Its three remaining gaps have been implemented, and this pass found and fixed the last strict-plan edges around release mDNS safety, ready-to-push threshold semantics, quick-start settings visibility, and wearable approval preference control.

No blocking plan-level findings remain.

## Verification Gates

| Gate | Result |
| --- | --- |
| `npm run build` | Passed |
| `npm run typecheck` | Passed |
| `npm test` | Passed: 240 passed, 7 skipped |
| `npm run audit:allowlist` | Passed: 0 moderate-or-higher vulnerabilities |
| `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :wear:assembleDebug :wear:lintDebug :app:compileReleaseKotlin` | Passed |

Android still reports a small set of pre-existing Kotlin warnings in `RelayService.kt` around coroutine job nullability/labels. They do not block lint, debug build, Wear build, tests, or release Kotlin compilation.

## Closure Of Doc 42 Gaps

### P1-2: Workspace nudge thresholds

Status: Closed.

Evidence:

- `WorkspaceStatus` now includes `lastCommitAtMs`, `aheadBy`, and `behindBy`: `src/adapters/git.ts:10-12`.
- `getWorkspaceStatus()` reads last commit time from `git log -1 --format=%ct` and ahead/behind from `git rev-list --count --left-right @{u}...HEAD`: `src/adapters/git.ts:99-121`.
- `LatestCiFailureSummary` now includes `failedAtMs`: `src/adapters/ci.ts:16`.
- `getLatestCiFailure()` parses `updated_at`/`created_at` into `failedAtMs`: `src/adapters/ci.ts:137-151`.
- `WorkspaceAwarenessService` now uses real elapsed time for stale-branch and CI-red thresholds: `src/personalization/workspace-awareness-service.ts:234-257`.
- `ready_to_push` now requires a clean repo, `aheadBy > 0`, and `behindBy === 0`: `src/personalization/workspace-awareness-service.ts:259-264`.
- Regression coverage verifies a branch that is both ahead and behind does not trigger ready-to-push: `test/workspace-awareness-service.test.ts:265`.

### P1-3: Release mDNS quick-start transport

Status: Closed.

Evidence:

- Release Android blocks HTTP-discovered bridge URLs with an explicit error: `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt:272`.
- Release Android falls back to `https://host:port` when no TXT pairing URL is provided: `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt:268`.
- CLI mDNS advertisement now requires a release-safe HTTPS pairing URL: `src/cli/jarvis-earbuds.ts:105-114`.
- `isReleaseSafeMdnsPairingBaseUrl()` rejects HTTP, missing, and malformed pairing URLs: `src/bridge/mdns.ts:16`.
- mDNS regression coverage asserts only HTTPS pairing URLs are release-safe: `test/mdns-advertisement.test.ts:40-43`.

### P2-1: Android personalization settings UI

Status: Closed.

Evidence:

- Settings tab is part of bottom navigation and routed through `RelayAppShell`: `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt:598-617`.
- Settings data loads when the Settings tab opens: `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt:243-246`.
- Notification preferences are visible and writable, including style, badge, soft-ping TTS, nudge TTS, reminder TTS, sensitive notification detail, and watch approval toggle: `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/SettingsScreen.kt:86-122`.
- Quick-start status and setup phase are visible in Settings: `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/SettingsScreen.kt:66-79`.
- Nudge mute settings and threshold editors are exposed: `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/SettingsScreen.kt:126-166`.
- Reminders can be listed and cancelled: `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/SettingsScreen.kt:170-200`.
- Learned phrases can be viewed, changed, deleted, and reset: `android-relay/app/src/main/java/com/openclaw/relay/ui/screens/SettingsScreen.kt:204-243`.
- `RelayViewModel` wires the bridge APIs for preferences, reminders, learned phrases, and nudge policy: `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt:1186-1288`.
- Wear sync honors the watch approval preference and hides approval action IDs when disabled: `android-relay/app/src/main/java/com/openclaw/relay/wear/WearDataSync.kt:32-43`.

## Rechecked Plan 36 Feature Areas

### Bridge outbox

Implemented. Outbox event kinds, cursor/ack schemas, and API tests cover polling, paging, ack, auth, and session ownership.

Evidence: `src/protocol/schemas.ts:227-254`, `test/bridge-outbox-api.test.ts`, `test/approval-outbox.test.ts`.

### Local personalization stores

Implemented. Stores exist for habits, notification preferences, reminders, outbox, and nudge policy. Notification preferences now include `wearApprovalEnabled` with default persistence.

Evidence: `src/personalization/`, `src/protocol/schemas.ts:274`, `src/personalization/notification-preference-store.ts:29`, `test/notification-preference-store.test.ts:37`.

### Voice habit learning

Implemented. Habit resolution, confirmation/rejection events, prompt outbox emission, promotion semantics, and settings management are present.

Evidence: `src/jarvis/intent-resolution-engine.ts`, `src/bridge/request-builder.ts:45-48`, `src/bridge/event-router.ts:120-139`, `test/intent-resolution-engine.test.ts`, `test/voice-habit-api.test.ts`.

### Interruptible notifications and deferred reminders

Implemented. Background completion soft pings go to outbox, Android delivery respects notification preferences, and "remind me later" defers the recent completion context with a five-minute context window.

Evidence: `src/bridge/runtime.ts:210-217`, `src/bridge/session-store.ts:158-176`, `src/bridge/event-router.ts:302-324`, `test/reminder-voice-flow.test.ts:156-200`.

### Ambient awareness and proactive nudges

Implemented. Workspace reads stay behind configured workspaces, nudges queue through outbox, active-hour/cooldown/mute behavior is tested, and real git/CI metadata now drives thresholds.

Evidence: `src/personalization/workspace-awareness-service.ts`, `test/workspace-awareness-service.test.ts`.

### Streamlined onboarding

Implemented with release-safe transport rules. Bridge mDNS discovery is present, Android NSD discovery is present, quick-start mode is enabled after discovered pairing, and quick-start policy keeps approval-required intents blocked.

Evidence: `src/bridge/mdns.ts`, `src/cli/jarvis-earbuds.ts:105-114`, `android-relay/app/src/main/java/com/openclaw/relay/BridgeDiscoveryManager.kt`, `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt:260-328`, `test/quick-start-policy.test.ts`.

### Approval notifications and Wear OS

Implemented. Android approval notifications carry action IDs, Wear Data Layer sync exists, Wear approval messages feed back into the service, and the Settings watch-approval preference can disable approval sync.

Evidence: `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt:1736-1807`, `android-relay/app/src/main/java/com/openclaw/relay/wear/WearDataSync.kt:32-43`, `android-relay/app/src/main/java/com/openclaw/relay/wear/WearApprovalListenerService.kt`, `android-relay/wear/src/main/java/com/openclaw/relay/wear/DevPodsTileService.kt`.

### Proof and diagnostics

Implemented. Proof-run validation supports personalized-collaborator feature booleans, and the template includes the new evidence fields.

Evidence: `scripts/validate-proof-run.ts:239`, `test/validate-proof-run.test.ts:223-247`, `simulation/android-relay/proof-runs/TEMPLATE-personalized-collaborator-features.json`.

## Audit Fixes Applied In This Pass

1. Tightened `ready_to_push` to require `behindBy === 0` and added regression coverage.
2. Prevented release mDNS from advertising HTTP pairing URLs; Android release rejects explicit HTTP discovered bridges.
3. Completed Settings UI coverage for nudge thresholds, learned phrase edit/reset, quick-start status, and watch approval preference.
4. Added `wearApprovalEnabled` to bridge and Android notification preferences, and made Wear sync honor it.
5. Kept notification preference writes backward-friendly by allowing schema input defaults in `NotificationPreferenceStore.setPreferences()`.

## Recommendation

Mark `docs/36-personalized-collaborator-feature-implementation-plan.md` complete and treat `docs/42-personalized-collaborator-final-code-audit.md` as superseded by this closure audit.
