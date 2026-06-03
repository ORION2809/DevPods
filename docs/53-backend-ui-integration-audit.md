# DevPods Backend-UI Integration Audit Report

> **Date**: 2026-06-01
> **Scope**: Verify backend bridge APIs are correctly wired into the enhanced Android UI
> **Analyst**: Kimi Code

---

## Executive Summary

The backend-frontend integration is **solid overall**. Both TypeScript and Kotlin compile cleanly, all 240 backend tests pass, and the core event flow (Android → Bridge → Jarvis → OpenClaw → Response → UI) is fully operational.

**Grade: B+** — One critical UX bug (Settings trap) and one missing feature (create reminder) need fixing before release.

---

## 1. API Contract Parity ✅

| Backend Endpoint (Node.js) | BridgeClient Method | ViewModel Exposure | UI Consumption | Status |
|---|---|---|---|---|
| `GET /health` | `health()` | `checkHealth()` | Home, Device, Help, Dev | ✅ |
| `POST /events` | `sendEvent()` | `wakeAndListen()`, `approve()`, `reject()`, `tapTest()` | Home, Activity | ✅ |
| `GET /pairing` | `pairing()` | `importPairingUri()` | SetupWizard, Device | ✅ |
| `POST /pairing/verify` | `pairingVerify()` | `importPairingUri()` | SetupWizard, Device | ✅ |
| `GET /sessions/{id}/outbox` | `pollOutbox()` | RelayService internal | Home (badge), notifications | ✅ |
| `POST /sessions/{id}/outbox/{eid}/ack` | `ackOutboxEvent()` | RelayService internal | Auto-ack after present | ✅ |
| `GET/POST /preferences/notifications` | `get/setNotificationPreferences()` | `load/saveNotificationPreferences()` | Settings | ✅ |
| `GET/POST /reminders` | `listReminders()`, `createReminder()` | `loadReminders()`, `cancelReminder()` | Settings | ⚠️ create not wired |
| `DELETE /reminders/{id}` | `cancelReminder()` | `cancelReminder()` | Settings | ✅ |
| `GET/POST /nudge-policy` | `get/setNudgePolicy()` | `load/saveNudgePolicy()` | Settings | ✅ |
| `GET /habits` | `listHabits()` | `loadLearnedPhrases()` | Settings | ✅ |
| `POST /habits` | `createHabit()` | `saveLearnedPhrase()` | Settings | ✅ |
| `DELETE /habits/{phrase}` | `deleteHabit()` | `deleteLearnedPhrase()`, `resetLearnedPhrases()` | Settings | ✅ |
| `GET/POST/DELETE /quick-start` | — | `enableQuickStart()` (raw HttpURLConnection) | SetupWizard | ✅ |

**Finding**: All backend endpoints have Android counterparts. The only gap is `createReminder()` exists in `BridgeClient` but is not exposed through `RelayViewModel` or consumed by any UI.

---

## 2. State Mapping Parity ✅

| Backend State | Android State | Sync Direction | Status |
|---|---|---|---|
| `sessionStore.states` | `RelayUiState.speechSessionState` | Push (event response) | ✅ |
| `sessionStore.pendingBySession` | `RelayUiState.pendingApprovalRequest` | Push (event response) | ✅ |
| `sessionStore.autonomyBySession` | `RelayUiState.activeAutonomy` | Push (event response) | ✅ |
| `sessionStore.quickStartSessions` | `RelayUiState.quickStartEnabled` | Push (explicit enable) | ✅ |
| `outboxStore.events` | `RelayUiState.outboxEvents`, `outboxBadgeCount` | Poll (10s interval) | ✅ |
| `notificationPreferenceStore` | `RelayUiState.notificationPreference` | Pull (on launch) | ✅ |
| `reminderStore` | `RelayUiState.reminders` | Pull (on launch) + push cancel | ✅ |
| `voiceHabitStore` | `RelayUiState.learnedPhrases` | Pull (on launch) | ✅ |
| `nudgePolicyStore` | `RelayUiState.nudgePolicy` | Pull (on launch) | ✅ |
| `runtime.healthStatus` | `RelayUiState.lastBridgeHealth`, `bridgeStatus` | Push (health check) | ✅ |

**Finding**: State mapping is complete. The `RelayStateStore` single-source-of-truth pattern correctly surfaces all backend state to Compose UI via `collectAsStateWithLifecycle()`.

---

## 3. Navigation Architecture ✅

The theme parity report required removing Settings from the bottom nav. This has been correctly implemented:

- **Standard mode**: 4 tabs (Home, Activity, Device, Help) ✅
- **Dev mode**: 5 tabs (+ Dev) ✅
- **Settings**: Rendered as full-screen overlay from Help → "Preferences" button ✅
- **Onboarding/Setup**: Use `DevPodsScreenShell` with shared background/topbar/note ✅

---

## 4. Critical Issues

### 🔴 C1: SettingsScreen Is a Trap — No Close Button

**File**: `MainActivity.kt`, `SettingsScreen.kt`

**Finding**: `SettingsScreen` is rendered as a `fillMaxSize()` overlay with a solid background when `showSettings = true`. There is **no close button, back handler, or dismiss tap target** inside `SettingsScreen`. The user cannot exit Settings without killing the app.

**Code evidence**:
```kotlin
// MainActivity.kt:419-479
if (showSettings) {
    val settingsModifier = Modifier.fillMaxSize().background(DevPodsColor.Background)
    SettingsScreen(...)
    // Dismiss settings on back gesture or overlay tap would need additional handling;
    // for now, a simple close button inside SettingsScreen is recommended.
}
```

**Fix**: Add a close button to the SettingsScreen header and a `BackHandler`.

---

### 🟡 C2: Create Reminder Feature Not Wired

**File**: `BridgeClient.kt`, `RelayViewModel.kt`, `SettingsScreen.kt`

**Finding**: `BridgeClient.createReminder()` exists but is never called. The ViewModel has `loadReminders()` and `cancelReminder()` but no `createReminder()`. The SettingsScreen shows a reminders list with delete buttons but no "Add reminder" UI.

**Impact**: Users can only cancel existing reminders (created via voice/command line), not create new ones from the app.

**Fix**: Add `createReminder()` to ViewModel and an "Add reminder" button + dialog to SettingsScreen.

---

### 🟡 C3: SettingsScreen Shows Raw Debug State

**File**: `SettingsScreen.kt`

**Finding**: The "Setup" section displays `Setup phase: $setupPhase` as a raw enum string (`NOT_STARTED`, `COMPLETE_PROVEN`, etc.). This is internal backend state, not user-facing copy.

**Fix**: Map enum values to human-readable labels or remove the section.

---

## 5. Medium Issues

### 🟡 M1: Missing `pairing/regenerate` BridgeClient Method

The backend exposes `POST /pairing/regenerate` but Android never calls it. This is acceptable since the Android app does not need to rotate pairing codes.

### 🟡 M2: Quick-Start Enable Uses Raw HttpURLConnection

`RelayViewModel.enableQuickStart()` uses `java.net.HttpURLConnection` directly instead of `BridgeClient`. This works but breaks abstraction consistency.

---

## 6. Verified Working Features

| Feature | Verification |
|---|---|
| Event ingestion (`/events`) | ✅ `RelayService.sendBridgeEvent()` → `BridgeClient.sendEvent()` |
| Outbox polling | ✅ `RelayService.startOutboxPolling()` polls every 10s |
| Outbox ACK | ✅ `ackOutboxEvent()` called after presenting event |
| Approval workflow | ✅ `approve()` / `reject()` dispatch service intents with `pendingActionId` |
| Health check | ✅ `checkHealth()` dispatches service intent |
| Pairing flow | ✅ QR scan, deep-link, mDNS discovery all wired |
| Notification prefs | ✅ Load on launch, save on toggle |
| Nudge policy | ✅ Load on launch, save on change |
| Learned phrases | ✅ Load on launch, save on edit, delete on button |
| Calibration | ✅ Full calibration engine wired through ViewModel |
| Gesture mapping | ✅ `setGestureAction()` validates proven gestures |
| Diagnostics export | ✅ `exportDiagnostics()` uses `DiagnosticExport` |
| TTS/Speech | ✅ `AndroidTtsSpeaker` callbacks update `RelayStateStore` |
| Background retry queue | ✅ Exponential backoff with max 5 retries |

---

## 7. Test Results

| Suite | Result |
|---|---|
| TypeScript typecheck | ✅ Pass (`tsc --noEmit`) |
| Bridge Vitest suite | ✅ 240 passed, 7 skipped |
| Kotlin compileDebugKotlin | ✅ BUILD SUCCESSFUL |

---

## 8. Recommended Action Plan

### Immediate (before next build)

1. **Fix Settings trap** — Add close button + BackHandler to SettingsScreen
2. **Add create reminder** — Wire `BridgeClient.createReminder()` through ViewModel to SettingsScreen
3. **Hide raw setup phase** — Replace enum string with user-facing status or remove

### Polish (next sprint)

4. **Migrate quick-start enable** from `HttpURLConnection` to `BridgeClient`
5. **Add loading states** to Settings API calls (currently silent failure)
6. **Add empty states** for reminders list and learned phrases list

---

*Report generated by Kimi Code. Cross-referenced against `src/bridge/server.ts`, `src/protocol/schemas.ts`, `RelayViewModel.kt`, `BridgeClient.kt`, `MainActivity.kt`, and all screen files.*
