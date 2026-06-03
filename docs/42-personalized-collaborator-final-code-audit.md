# Personalized Collaborator Final Code Audit

Date: 2026-06-01
Scope: final verification pass against `docs/36-personalized-collaborator-feature-implementation-plan.md`, including the latest post-soft-ping deferral fixes.

## Verdict

Plan 36 is not done perfectly as written.

The implementation is gate-clean and the latest P2-2 deferral path is implemented and tested, but three plan-level areas are still incomplete or explicitly deferred:

1. Release mDNS quick-start transport is unresolved.
2. Workspace nudge thresholds are still approximations.
3. Android personalization settings UI is incomplete.

If the bar is "all hard blockers fixed and automated gates green", plan 36 is ready to move forward. If the bar is "perfectly complete against the plan document", keep plan 36 open.

## Verification Gates

| Gate | Result |
| --- | --- |
| `npm run build` | Passed |
| `npm run typecheck` | Passed |
| `npm test` | Passed: 238 passed, 7 skipped |
| `npm run audit:allowlist` | Passed: 0 moderate-or-higher vulnerabilities |
| `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :wear:assembleDebug :wear:lintDebug` | Passed |

## P2-2 Deferral Verification

The post-soft-ping deferral path is implemented enough to satisfy the earlier audit finding:

- `EventRouter` stores completion context on completed/acknowledged responses and adds a "remind me later" follow-up hint when none exists: `src/bridge/event-router.ts:301-305`.
- `handleCreateReminder()` reuses recent completion context for bare deferral utterances such as "remind me later": `src/bridge/event-router.ts:317-324`.
- The parser treats "later", "defer", and "snooze" as deferral keywords instead of reminder subjects: `src/bridge/event-router.ts:614-628`.
- `SessionStore` stores completion context and expires it on lookup/prune: `src/bridge/session-store.ts:158-176`, `src/bridge/session-store.ts:213-216`.
- `test/reminder-voice-flow.test.ts:156-190` proves "remind me later" after a completion creates a reminder using the last completion text.

Notes:

- The latest summary says there is a `defer_reminder` intent, but the code does not contain that intent in `src/protocol/types.ts`. The working path is through `create_reminder`, not a separate `defer_reminder` intent.
- The summary also says the completion context has a 5-minute TTL. `getCompletionContext()` currently defaults to a 30-second lookup window in `src/bridge/session-store.ts:165`.

These mismatches do not break the tested "remind me later" flow, but they should be corrected in docs or code.

## Remaining Gaps Against Plan 36

### 1. Release mDNS quick-start transport is unresolved

Plan 36 says mDNS must align with the beta transport decision: if release Android blocks HTTP, mDNS must advertise HTTPS/tunnel URLs or release must explicitly allow trusted LAN bridge HTTP.

Current code:

- Release Android blocks cleartext: `android-relay/app/src/release/res/xml/network_security_config.xml:3`.
- Android still falls back to `http://${bridge.host}:${bridge.port}` for discovered bridges: `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt:261`.
- CLI mDNS still advertises HTTP fallback when no pairing base URL is supplied: `src/cli/jarvis-earbuds.ts:107`.
- Tests still accept HTTP pairing base URLs in `test/mdns-advertisement.test.ts`.

Conclusion: quick-start is not release-perfect yet.

### 2. Workspace nudge thresholds are still approximations

Plan 36 requires thresholds for changed file count, time since last commit, consecutive test failures, and CI red duration.

Current code:

- `stale_branch` explicitly lacks `lastCommitAtMs` and approximates with changed files plus tests-not-running: `src/personalization/workspace-awareness-service.ts:229-234`.
- `ci_red` does not use `ciRedHours`: `src/personalization/workspace-awareness-service.ts:247-249`.
- `ready_to_push` explicitly lacks ahead/behind metadata and approximates with clean working tree: `src/personalization/workspace-awareness-service.ts:251-255`.
- `WorkspaceStatus` has no `lastCommitAtMs`, `aheadBy`, or `behindBy`: `src/adapters/git.ts:4-10`.
- `LatestCiFailureSummary` has no failure timestamp/duration: `src/adapters/ci.ts:8-16`.

Conclusion: workspace nudges are functional, but not fully faithful to the plan.

### 3. Android personalization settings UI is incomplete

Plan 36 requires Android-side preferences for notification style, nudge mute settings, learned phrase visibility/edit/delete, quick-start status, and wearable approval preference.

Current code:

- `BridgeClient` exposes notification/reminder/learned-phrase client methods, but no Android UI call sites were found for saving notification preferences, managing reminders, editing/deleting learned phrases, or nudge policy management.
- Android has no visible nudge-policy client/UI path matching the bridge `/sessions/:sessionId/nudge-policy` endpoint.

Conclusion: bridge APIs exist, but the Android user-facing settings work is not complete.

## Closeout Recommendation

Do not mark plan 36 as perfectly done. Mark it as gate-clean with remaining tracked product gaps, or finish the three gaps above before closing the plan as complete.
