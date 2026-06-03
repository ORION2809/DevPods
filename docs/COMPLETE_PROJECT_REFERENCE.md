# DevPods Complete Reference

**Version:** 0.1.0  
**Date:** 2026-05-27  
**Scope:** This document covers every feature, subsystem, UX/UI enhancement, protocol detail, configuration option, and validation surface in the DevPods repository.  
**How to read this document:** Sections are numbered hierarchically. Use the table of contents to jump to any area. Every feature includes both a technical description and a user-facing description.

---

## 0. Title and Meta

| Field | Value |
|-------|-------|
| **Project** | DevPods |
| **Package name** | `devpods` |
| **Version** | 0.1.0 |
| **CLI binary** | `devpods` (primary), `jarvis-earbuds` (compatibility alias) |
| **Runtime** | Node.js 22+, TypeScript 5.9 |
| **Android** | Kotlin, Jetpack Compose, Media3, minSdk 33 |
| **Test framework** | Vitest (TypeScript), JUnit (Android) |
| **Test count** | 123 tests across 21 TypeScript test files + Android unit tests |
| **Repository root** | `C:\Users\ShreyasSuvarna\Desktop\its_mine\firmware_earphones` |

---

## 1. Product Vision and Core Loop

### 1.1 What DevPods Is

DevPods turns ordinary Bluetooth earbuds into a **hands-free developer control surface**. It is not "an AI inside earbuds" -- the earbuds serve as the input/output channel for developer actions running on the local machine.

**Product model:**

```
Earbud gesture or Android relay event
  -> DevPods Bridge (TypeScript, local HTTP)
  -> Workspace policy + approval gates
  -> Developer action (git, npm, editor, CI lookup)
  -> Optional OpenClaw rewrite (response shaping for voice)
  -> Short spoken response back to the user
```

The system is intentionally conservative: every workspace action is allowlisted, risky actions require explicit approval, and the bridge never exposes arbitrary shell execution from spoken text.

### 1.2 The User Journey End-to-End

1. **Install the desktop bridge** -- `npm install` then `devpods start --host 0.0.0.0 --relay-token <token> --pairing-base-url http://<LAN-IP>:4545`
2. **Install DevPods Relay on Android** -- build the APK from `android-relay/` or use a pre-built debug build
3. **Pair** -- scan the QR code from the bridge pairing page or paste the `devpods://pair` deep link
4. **Run guided setup** -- 4-step wizard: bridge pairing, device probe, wake gesture test, speech capture test
5. **Wake and speak** -- tap earbuds or press push-to-talk, speak a command like "summarize my diff"
6. **Review and approve** -- if the action is risky, an approval prompt appears with a 12-second countdown
7. **Receive spoken response** -- the bridge returns a short, ear-safe response via Android TTS

### 1.3 Current Product Status

- **Bridge:** production-ready local HTTP server with pairing, rate limiting, idempotency, circuit breaker, audit logging
- **Android Relay:** working MVP with 5-tab UI, 10-provider earbud mesh, STT/TTS, setup wizard, bounded autonomy
- **OpenClaw:** integrated with 3 transport modes (http, local-cli, gateway-client), adaptive rewrite policy
- **Validation:** 123 TypeScript tests, Android debug/release builds, CI pipeline, smoke harness, proof runs
- **Real-device status:** MediaSession path confirmed on realme RMX3990 (Android 16); physical earbud tap workflow partially recognized but not yet fully reliable

---

## 2. Setup and Onboarding

### 2.1 Desktop Bridge Installation

```bash
# Install dependencies
npm install

# Start the bridge on LAN-accessible address
devpods start --host 0.0.0.0 --port 4545 --relay-token relay-secret --pairing-base-url http://192.168.1.10:4545
```

The bridge prints:
- `DevPods Bridge listening on http://0.0.0.0:4545 (brain=local)`
- `Bridge pairing page: http://192.168.1.10:4545/pairing`
- `Relay pairing URI: devpods://pair?bridgeBaseUrl=...`

A warning is printed if the bridge is exposed beyond localhost: *"bridge traffic is cleartext over HTTP. Use only on a trusted LAN."*

**Portable Windows package:**

```powershell
npm run package:bridge:windows
# Creates: artifacts/windows-bridge/DevPodsBridgePortable/
# Launch: start-devpods-bridge.cmd
```

### 2.2 Pairing Flow

Three pairing methods are supported:

| Method | Flow | TTL |
|--------|------|-----|
| **QR scan** | Bridge renders QR on `/pairing` page -> Android scans -> verifies code | 5 minutes |
| **Deep link** | User opens `devpods://pair?bridgeBaseUrl=...&workspace=...&pairingCode=...` | 5 minutes |
| **Manual paste** | User copies pairing page URL into Android relay pairing field | N/A |

**Pairing code lifecycle:**
- Generated as 6-character base64url string (e.g., `A3B7KQ`)
- 5-minute TTL (`PAIRING_CODE_TTL_MS = 5 * 60 * 1000`)
- One-time use -- consumed on successful verification
- Timing-safe comparison via `crypto.timingSafeEqual`
- Auto-regeneration on expiry or `/pairing/regenerate` POST

**Android-side pairing (`RelayPairing.kt`, `BridgeClient.kt`):**
1. Fetch pairing page JSON: `GET /pairing` with `Accept: application/json`
2. Extract `bridgeBaseUrl`, `pairingCode`, `workspace`
3. Verify code: `POST /pairing/verify` with `{"pairingCode": "..."}`
4. Receive `relayToken`, save to `RelayConfig`
5. Health check to confirm connectivity

**Staged import:** Pairing data is never silently overwritten. The Android relay shows "Pairing saved checking bridge reachability" and then verifies connectivity before committing.

### 2.3 Android Relay First Launch (Onboarding)

**OnboardingScreen.kt** -- first-launch education surface:

| Element | Description |
|---------|-------------|
| **Hero visual** | Two earbud shapes flanking an animated `Waveform` component |
| **Title** | "DevPods" |
| **Product promise** | "Hands-free developer controls through your earbuds." |
| **Hero subtitle** | "Talk to your workspace through ordinary earbuds." |
| **Feature cards** | Three cards: "Pair" (teal), "Verify" (amber), "Approve" (blue) |
| **Primary CTA** | "Pair your bridge" button (primary style) |

The onboarding screen is shown when `RelayUiState.showOnboarding == true` and can be dismissed to proceed to the Home tab.

### 2.4 Sending Simulated Events (Desktop-Only Path)

For development without Android hardware:

```bash
# Quick status via gesture fixture
devpods send left_long_press

# Voice command via utterance
devpods say "summarize my current diff"

# Local one-shot (no HTTP server)
devpods local left_long_press

# Interactive listen mode
devpods listen
# Prompts: "What should Jarvis check?"
```

The simulator loads event fixtures from `simulation/fake-earbud-events/fixtures/` and sends them to the bridge via `POST /events`.

---

## 3. Feature Catalog: What the User Sees and Does

### 3.1 Developer Actions (Voice-First Intents)

All intents are defined in src/protocol/types.ts as the IntentName union type. The intent router (src/jarvis/router.ts) maps spoken utterances to intent names using keyword matching.

| Intent | Utterance triggers | User benefit | Technical implementation |
|--------|-------------------|--------------|-------------------------|
| quick_status | " status\, \branch\, or default fallback | Instant repo overview | git status -> branch name + changed file count |
| summarize_diff | \diff\, \changes\ | Know what you have changed | git diff --stat -> file count + main file |
| latest_ci_failure | \ci\, \build failed\, \github actions\ | CI failure context | Reads .github/workflows failure artifacts |
| un_tests | \test\ | Run tests hands-free | Background 
pm test command, notifies on completion |
| create_commit_message | \commit message\ | Get commit suggestion | git diff --staged -> suggested message |
| commit_staged | \commit staged\, \commit the staged\ | Commit without keyboard | git commit -- **hard approval required** |
| open_file | \open file\, \open the main file\, \open *.ext\ | Open file in VS Code | Resolves path, opens via code command |
| push | \push\ | Push branch | git push -- **hard approval required** |
| deploy | \deploy\ | Deploy build | Background 
pm run build -- **hard approval required** |
| delete | \delete file X\, \delete X\ | Delete workspace file | s.rmSync with path validation -- **hard approval required** |
| evert | \revert file X\, \revert X\ | Undo local changes | git checkout -- X -- **hard approval required** |

**Immediate intents** (no approval needed): quick_status, summarize_diff, latest_ci_failure, create_commit_message

**Approval-required intents:** un_tests, open_file

**Hard-approval intents:** commit_staged, push, deploy, delete, evert

#### 3.1.1 Quick Status

- **User experience:** Tap earbuds once or say \what is the status\ -> hears \main. 3 files changed. No tests running.\
- **Technical:** getWorkspaceStatus() reads git branch, counts changed files, identifies main changed file. Returns JarvisResponse with status: 'completed', 
extState: 'idle'.

#### 3.1.2 Summarize Diff

- **User experience:** Say \summarize my diff\ -> hears \5 files changed. Main work is in src/bridge/server.ts.\
- **Technical:** getDiffSummary() runs git diff --stat, parses output for file count and primary file.

#### 3.1.3 Latest CI Failure

- **User experience:** Say \what is the CI failure\ -> hears \Latest CI failure: CI on main branch.\
- **Technical:** getLatestCiFailure() reads GitHub Actions workflow run data from .github/ directory, extracts workflow name, branch, SHA, URL.

#### 3.1.4 Run Tests

- **User experience:** Say \run tests\ -> hears \Running tests. I will notify you when they finish.\ -> later receives spoken completion or failure notification.
- **Technical:** Background command via BackgroundCommandScheduler. Uses workspace-configured un_tests command (
pm test, 120s timeout). On completion, if successful, includes utonomy object for silence-driven continuation to quick_status.

#### 3.1.5 Create Commit Message

- **User experience:** Say \create commit message\ -> hears \Suggested commit message ready.\ with display showing the full message.
- **Technical:** getCommitMessageSuggestion() analyzes staged/working-tree changes, generates summary.

#### 3.1.6 Commit Staged

- **User experience:** Say \commit staged\ -> approval prompt appears -> approve -> hears \Committed staged files successfully.\
- **Technical:** commitStagedChanges() runs git commit -m \...\. Requires hard approval.

#### 3.1.7 Open File

- **User experience:** Say \open file src/bridge/server.ts\ -> approval prompt -> approve -> hears \Opened src/bridge/server.ts.\
- **Technical:** esolveOpenFileTarget() matches utterance against workspace files. openFileInEditor() launches VS Code. Requires standard approval.

#### 3.1.8 Push

- **User experience:** Say \push\ -> hard approval prompt -> approve -> hears \Pushed main to origin.\
- **Technical:** pushCurrentBranch() runs git push. Requires hard approval.

#### 3.1.9 Deploy

- **User experience:** Say \deploy\ -> hard approval -> hears \Deployment started. I will notify you when it finishes.\
- **Technical:** Background command via workspace-configured deploy command (
pm run build, 120s timeout).

#### 3.1.10 Delete File

- **User experience:** Say \delete file temp.txt\ -> hard approval -> approve -> hears \Deleted temp.txt.\
- **Technical:** extractExplicitPathFromUtterance() parses path from speech. esolveWorkspacePath() validates within workspace root. s.rmSync() with retry. Path traversal blocked.

#### 3.1.11 Revert File

- **User experience:** Say \revert file src/example.ts\ -> hard approval -> approve -> hears \Reverted src/example.ts.\
- **Technical:** evertTrackedFile() runs git checkout -- <path>. Requires hard approval.

### 3.2 Approval System (User-Facing Approval Flow)

**Approval lifecycle:**

1. Intent resolved -> policy evaluation -> pproval_required or hard_approval
2. createActionId() generates action ID (e.g., ct_a1b2c3d4e5f6)
3. PendingAction stored in SessionStore with 12-second timeout
4. Bridge returns JarvisResponse with equiresApproval: true, pprovalRequest object
5. **Android UI:** ApprovalPendingSection card appears with:
 - Risk chip: \Hard approval\ (red) or \Approval required\ (amber)
 - Action summary (e.g., \Commit staged files\)
 - Live countdown timer showing remaining seconds
 - \Approve\ (primary) and \Reject\ (danger) buttons
 - Expiry message when timer reaches zero
6. **Gesture approval:** Right double tap = approve, left double tap = reject, both hold = cancel
7. **Android approval buttons:** ndroid_approve, ndroid_reject, ndroid_cancel events
8. On approval: EventRouter.handleApproval() validates action ID, checks expiry, executes intent
9. On reject/cancel/expiry: session returns to idle, response indicates cancellation

**Approval detail view (Activity tab):** Full-screen sheet with risk grid showing action type, risk class, expiry time, consequence description, and gesture instructions.

### 3.3 Bounded Autonomy (Background Work)

**Concept:** After a background command completes successfully, the bridge can automatically continue to the next step (e.g., refresh status) if the user stays silent.

**Technical implementation:**
- Bridge response includes utonomy object:
 `json
 {
 \phase\: \report\,
 \mode\: \continue_on_silence\,
 \summary\: \Tests finished successfully.\,
 \nextStep\: \Refresh the repo status.\,
 \continueAfterMs\: 4000,
 \nextIntent\: \quick_status\
 }
 `
- SessionStore.setAutonomy() stores the instruction with expiry
- Android shows AutonomySection card with:
 - \Assistant working\ chip
 - Summary and next step text
 - CountdownRing showing remaining time
 - \Stop\ button to cancel
- **Silence-driven continuation:** If no interrupt gesture arrives before continueAfterMs, the relay sends utonomy_continue event
- **Interrupt to replan:** Wake gesture during autonomy sends utonomy_replan with new utterance, bridge resolves new intent and sets new autonomy plan

### 3.4 Session State Machine

The session state machine is defined by sessionStateSchema in src/protocol/schemas.ts:

`
idle -> listening -> thinking -> approval_pending -> running -> responding -> idle
idle -> queued -> running -> responding -> idle
idle -> paused -> idle
any -> cancelled -> idle
any -> error -> idle
`

| State | Triggered by | User-facing meaning |
|-------|-------------|---------------------|
| idle | Default, after completion | Ready for next command |
| listening | Wake gesture | Listening window open |
| hinking | Intent resolved, executing | Processing request |
| pproval_pending | Approval-required intent | Waiting for user confirmation |
| queued | Background command queued | Waiting for other tasks to finish |
| unning | Background command started | Task in progress |
| esponding | Response being delivered | Speaking back to user |
| paused | Bud removed | Session paused |
| cancelled | Cancel gesture | Action cancelled |

### 3.5 Wake and Listen Triggers

| Gesture | Bridge event name | Effect |
|---------|------------------|--------|
| Triple tap right | riple_tap_right | Wake + listen (voice command) |
| Left long press | left_long_press | Quick status (no listen) |
| Android push-to-talk | ndroid_push_to_talk | Wake + listen |
| Android status shortcut | ndroid_status_shortcut | Quick status |
| Headset button single | headset_button_single | Quick status |
| Both hold | oth_hold_cancel | Cancel current action |
| Remove one bud | emove_one_bud_pause | Pause session |
| Remove both buds | emove_both_buds_end_session | End session |
| Put both in | put_both_in_resume | Resume session |

---

## 4. Android Relay: The Mobile Product Surface

### 4.1 Five-Tab Shell Architecture

The Android relay uses a bottom navigation shell with 5 tabs:

| Tab | Screen | Purpose |
|-----|--------|---------|
| **Home** | `HomeScreen.kt` | Primary state card, push-to-talk, quick actions, approval UI, autonomy card, error banners |
| **Activity** | `ActivityScreen.kt` | Transcript/reply, approval detail, event timeline, activity history |
| **Device** | `DeviceScreen.kt` | Bridge pairing, provider health, capability matrix, setup wizard, fallback toggles |
| **Help** | `HelpScreen.kt` | Recovery actions, permissions, diagnostics export, voice proof run, accessibility |
| **Developer** | `DeveloperModeScreen.kt` | Debug automation, raw state dump, bridge event queue, relay controls |

Navigation is implemented via `BottomNav.kt` with icons and labels.

### 4.2 Primary State Card

The Home screen renders different cards based on `RelayUiState`:

| Condition | Card shown | Key elements |
|-----------|-----------|--------------|
| `userFacingErrorMessage` not null | `ErrorSection` | Red card, error message, dismiss button |
| `pendingApprovalRequest` not null | `ApprovalPendingSection` | Risk chip, summary, countdown, approve/reject buttons |
| `isListening` | `ListeningSection` | Hero card, "Listening..." chip, animated waveform, partial transcript |
| `activeAutonomy` not null | `AutonomySection` | "Assistant working" chip, summary, countdown ring, stop button |
| `bridgeQueueState.queuedCount > 0` | `BridgeQueueSection` | "Bridge reconnecting" card, queue meter, retry countdown, retry/discard buttons |
| `speakNowReadiness != BLOCKED` | `ReadySection` | Hero card, readiness chip, waveform, "Listen now" + "Check bridge" buttons, status chips |
| Default | `HomeOnboardingSection` | Welcome text, feature cards, "Pair your bridge" button |

### 4.3 Speech Recognition and TTS

**Speech Input:**
- `AndroidSpeechRecognizer` -- Android platform `SpeechRecognizer` with partial results streaming
- `SherpaSpeechInputEngine` -- offline/on-device STT via Sherpa-ONNX (experimental, `sherpaSttExperimentalEnabled`)
- `PcmInjectionSpeechInputEngine` -- debug-only PCM injection for testing
- `SyntheticSpeechInputEngine` -- debug-only synthetic input for automation

**Speech Output:**
- `AndroidTtsSpeaker` -- Android `TextToSpeech` engine with error callbacks, playback metrics
- `AndroidTtsOutputEngine` -- wrapper with `speak()` and `stop()` methods
- `SyntheticSpeechOutputEngine` -- debug-only synthetic output

**Speech session lifecycle:**
1. Wake signal received -> `prepareListeningRoute()` -> audio route verification
2. `SpeechInputEngine.start()` with `SpeechCallbacks`
3. Partial transcripts streamed via `onPartialTranscript` -> `RelayStateStore.setPartialTranscript()`
4. Final transcript via `onFinalTranscript` -> sent to bridge
5. Error handling via `onError` -> error notification, recovery attempts

### 4.4 Audio Routing

**Components:**
- `BluetoothAudioRouter` -- manages Bluetooth communication audio routing
- `AudioRouteSession` -- tracks route state transitions
- `AudioRouteFallbackPolicy` -- resolves fallback decisions (phone mic, block, retry)
- `AudioProbeMetrics` -- measures route success/failure
- `AudioRouteProof` -- proof-of-correctness for audio routing

**Route verification flow:**
1. `prepareListeningRoute()` called before listening
2. `audioRouter.routeCommunicationAudio()` attempts Bluetooth route
3. `AudioRouteFallbackPolicy.resolve()` evaluates route snapshot
4. If route fails and `phoneMicFallback` enabled -> use phone mic
5. If route fails and fallback disabled -> block listening with error message
6. Multiple settle attempts with delays before giving up

### 4.5 Diagnostic Export

**Export flow (Help tab -> "Share diagnostics"):**
1. User selects what to include via checkboxes:
   - Phone model (default: on)
   - Capability matrix (default: on)
   - Error categories (default: on)
   - Raw route detail (default: off -- privacy-sensitive)
2. "Preview" button shows redacted JSON before sending
3. "Share" button triggers Android `Intent.ACTION_SEND` with redacted payload
4. Redaction removes: URLs, tokens, workspace names, identifiers

**Diagnostic types:**
- `VoiceDiagnosticsExportSummary` -- speech sessions, TTS playback, audio probes, VAD observations
- `VoiceProofRun` -- 20-session proof matrix with reliability metrics
- `MediaButtonEventTelemetry` -- physical button event classification

### 4.6 User-Facing Error Messages

`RelayStateStore.resolveUserFacingError()` maps internal errors to actionable guidance:

| Internal error pattern | User-facing message |
|----------------------|---------------------|
| "unreachable", "could not reach", "bridge unavailable", "health check failed" | "The desktop bridge is unreachable. Make sure your computer and phone are on the same network, and the bridge is running. Tap Health to retry." |
| "pairing" + "expired"/"invalid"/"verify"/"could not import" | "Pairing expired or is invalid. Re-scan the QR code from the desktop bridge, or paste the pairing page URL again." |
| "speech recognition" + "unavailable", "stt not available" | "Speech-to-text is not available on this device. Install a speech recognition engine from the Play Store, or enable it in system settings." |
| "microphone permission", "record_audio", "insufficient_permissions" | "Microphone permission is denied. Go to Settings - Apps - DevPods Relay - Permissions, and allow Microphone." |
| "enable phone microphone fallback" | "Earbud microphone routing failed. Enable Phone microphone fallback in Device settings, or reconnect your earbuds and try again." |
| "no earbud", "headset disconnect", "bluetooth routing failed" | "No headset is connected. Pair your earbuds via Bluetooth, place them in your ears, and try again." |

---

## 5. Earbud Provider Mesh

### 5.1 Provider Architecture

The Android relay implements a **10-provider mesh** managed by `SignalProviderRegistry`:

```
SignalProviderRegistry
+-- EarbudSignalProvider (interface)
|   +-- providerId: String
|   +-- providerLabel: String
|   +-- isPhysicalInput: Boolean
|   +-- deviceState: StateFlow<EarbudDeviceState?>
|   +-- events: Flow<EarbudSignalEvent>
|   +-- start() / stop()
|   +-- probe(): ProbeResult
+-- Vendor providers (priority 1-5)
+-- Universal providers (priority 6-10)
+-- Shared transports
    +-- BtClassicSerialTransport
    +-- L2capAapTransport
```

Each provider implements the `EarbudSignalProvider` contract: identity, capability profile, device state, event flow, probe, start/stop lifecycle.

### 5.2 Provider Catalog (All 10)

| Priority | Provider ID | Label | Brands | Key mechanism |
|----------|------------|-------|--------|---------------|
| 1 | `apple_airpods` | Apple AirPods | AirPods, Beats | BLE proximity scanner + L2CAP/AACP stem press |
| 2 | `samsung_galaxy_buds` | Samsung Galaxy Buds | Galaxy Buds 2/2 Pro/3 Pro/Live/FE/Pro | RFCOMM protocol + Samsung packet codec + battery decode |
| 3 | `sony_headphones` | Sony Headphones | WF-1000XM4/5, WH-1000XM4/5, LinkBuds | RFCOMM serial + capability detection |
| 4 | `nothing_ear` | Nothing Ear | Nothing Ear 1/2/a, CMF Buds | RFCOMM serial |
| 5 | `oppo_realme` | Oppo/Realme | Oppo Enco, Realme Buds, OnePlus Buds | RFCOMM serial |
| 6 | `librepods_airpods` | LibrePods AirPods | AirPods (legacy) | BLE proximity + AACP |
| 7 | `android_media_session` | Android Media Session | ALL Bluetooth audio | Media3 session -- universal media-button wake/interrupt/approval |
| 8 | `assistant_entry` | Assistant Entry | ALL devices | Long-press assistant fallback |
| 9 | `generic_bluetooth_headset` | Generic Bluetooth | ALL Bluetooth headsets | Connection + audio route awareness |
| 10 | `generic_gatt_battery` | Generic GATT Battery | BLE devices with BAS | Standard GATT battery service |

### 5.3 Shared Transports

| Transport | File | Purpose |
|-----------|------|---------|
| `BtClassicSerialTransport` | `signal/transport/btclassic/BtClassicSerialTransport.kt` | RFCOMM serial communication for Samsung, Sony, Nothing, Oppo providers |
| `L2capAapTransport` | `signal/transport/ble/L2capAapTransport.kt` | L2CAP/AACP for Apple AirPods provider |

### 5.4 Capability and Evidence Model

**EarbudCapabilityProfile** -- per-provider capability declaration:
- Wake gesture support
- Interrupt gesture support
- Approve/reject gesture support
- In-ear detection
- Battery reporting

**HardwareContext** -- evidence attached to each event:
```kotlin
data class HardwareContext(
    val providerId: String,
    val wakeSource: String?,
    val deviceConfidence: String,  // "proven", "observed", "inferred", "unproven"
)
```

**DeviceCapabilityEntry** -- setup wizard output:
- `wakeGesture`: CapabilityStatus (PROVEN, OBSERVED, FALLBACK_PROVEN, UNPROVEN, UNSUPPORTED)
- `interruptGesture`: CapabilityStatus
- `approveRejectGesture`: CapabilityStatus
- `earDetection`: CapabilityStatus
- `batteryStatus`: CapabilityStatus
- `sttAfterWake`: CapabilityStatus

### 5.5 Media Button Diagnostics

`MediaButtonDiagnostics.kt` classifies physical media button events:
- `MEDIA_PLAY_PAUSE` -- mapped to wake or quick status
- `MEDIA_NEXT` / `MEDIA_PREVIOUS` -- mapped to approval or navigation
- Distinguishes physical hardware events from software emulation
- Records `MediaButtonEventTelemetry` with keycode, source package, event time

---

## 6. Voice Pipeline

### 6.1 Speech Recognition

**Platform STT (`AndroidSpeechRecognizer` / `PlatformSpeechRecognizerEngine`):**
- Uses Android `SpeechRecognizer` API
- Supports partial results streaming (`onPartialTranscript`)
- Error codes mapped to `SpeechEndpointReason` (TIMEOUT, RECOGNIZER_BUSY, ROUTE_FAILED, etc.)
- 12-second listening session timeout (`LISTENING_SESSION_TIMEOUT_MS`)
- Recognizer busy recovery: 500ms delay then retry

**Offline STT (Sherpa-ONNX):**
- `SherpaSpeechInputEngine` -- experimental offline STT
- Configured via `RelayConfig.sherpaSttExperimentalEnabled`
- Model management via `SherpaModelManager`
- VAD integration via `SherpaVadProbe`
- Native library loading via `SherpaNativeLoader`

### 6.2 Offline/On-Device Speech

**Sherpa-ONNX integration:**
- `sherpa-runtime` Gradle module with native bindings
- `SherpaModelSpec` -- model configuration (path, version, SHA256)
- `SherpaModelInspector` -- validates model files before loading
- `SherpaReadiness` -- checks if offline STT is available
- `SherpaFeatureFlags` -- feature toggles for Sherpa runtime
- `OfflineSpeechEvaluation` -- evaluates offline STT quality
- `OfflineSpeechBenchmark` -- benchmarks offline STT performance

**Configuration:**
```kotlin
RelayConfig(
    offlineSpeechModelPath = "/path/to/model",
    offlineSpeechModelVersion = "1.0.0",
    offlineSpeechModelSha256 = "abc123...",
    sherpaRuntimeEnabled = false,
    sherpaVadDiagnosticsEnabled = false,
    sherpaSttExperimentalEnabled = false,
    sherpaModelDownloadsEnabled = false,
)
```

### 6.3 Text-to-Speech

**Android TTS (`AndroidTtsSpeaker`):**
- Android `TextToSpeech` engine
- `onReadyChanged` callback for TTS initialization
- `onSpeakingChanged` callback for speaking state
- `onPlaybackMetrics` for TTS performance tracking
- Error handling via `onError` callback

**Desktop TTS (`WindowsSpeechNotifier`):**
- Windows `System.Speech.Synthesis.SpeechSynthesizer` via PowerShell
- Activated when `process.platform === 'win32'` and `JARVIS_DISABLE_TTS !== '1'`
- Text passed via environment variable `JARVIS_SPEAK_TEXT` (not interpolated into command)
- 15-second timeout
- Falls back to console output on failure

**Speaker self-test:**
- Home tab -> "Test speaker" button -> speaks "DevPods Relay is ready."
- Validates TTS engine initialization before test

### 6.4 Voice Optimization

**`optimizeSpeak()` function:**
- Normalizes whitespace
- Truncates to 24 words maximum for ear-safe responses
- Appends period if truncated

**OpenClaw rewrite optimization:**
- Adaptive rewrite policy triggers when:
  - Response > 24 words, OR
  - Response contains newlines, OR
  - Response > 16 words, OR
  - Response has follow-up hint AND > 12 words
- Rewrite prompt: "Keep speak to one short sentence with at most 24 words"

---

## 7. Safety and Trust Model

### 7.1 Deny-by-Default Policy

- Every workspace action must be explicitly allowlisted in `config/workspaces.json`
- Intents not in `allowedIntents` are denied with reason: `"Intent 'X' is not allowlisted for workspace 'Y'."`
- The bridge does not expose arbitrary shell execution from spoken text

### 7.2 Risk Classification and Approval Gates

| Risk class | Behavior | Example intents |
|-----------|----------|-----------------|
| `immediate` | Executes without approval | `quick_status`, `summarize_diff`, `latest_ci_failure`, `create_commit_message` |
| `approval_required` | Requires user approval (12s timeout) | `run_tests`, `open_file` |
| `hard_approval` | Requires explicit approval with red accent UI | `commit_staged`, `push`, `deploy`, `delete`, `revert` |
| `denied` | Blocked entirely | Any intent not in allowlist |

**Physical interrupt override:** If `hardwareContext.deviceConfidence === 'proven'` AND gesture is a physical interrupt (`both_hold_cancel`, `remove_one_bud_pause`, `remove_both_buds_end_session`), approval-required intents are downgraded to `immediate`.

### 7.3 Command Safety

- **Allowed commands:** Only `npm`, `node`, `git` are permitted
- **Dangerous argument pattern:** `[;&|`$<>\n\r]` -- shell metacharacters rejected
- **Argument length cap:** 1000 characters per argument
- **Protected paths:** Path traversal blocked, workspace-bounded file operations
- **Delete/revert:** Require explicit file path in utterance, validated against workspace root

### 7.4 Redaction

`redactText()` removes sensitive patterns from all output:

| Pattern | Replacement |
|---------|-------------|
| `Authorization: Bearer <token>` | `Authorization: Bearer [REDACTED]` |
| `token=...`, `secret=...`, `password=...`, `api_key=...` | `[REDACTED]` |
| GitHub tokens (`ghp_...`) | `[REDACTED]` |
| Google API keys (`AIza...`) | `[REDACTED]` |

Applied to: audit log details, error messages, diagnostic exports, background command output.

### 7.5 Audit Logging

- **File:** `runtime-data/audit.log` (configurable path)
- **Rotation:** 10 MB max size (`MAX_AUDIT_LOG_SIZE`), 5 backup files (`MAX_AUDIT_LOG_FILES`)
- **Schema:** `auditRecordSchema` -- id, timestamp, sessionId, workspace, event, decision, status, actionId, detail, hardwareContext
- **Decisions:** `received`, `allowed`, `blocked`, `approval_requested`, `approved`, `queued`, `rejected`, `cancelled`, `running`, `completed`, `failed`
- **Fallback:** On write error, logs to stderr

### 7.6 Pairing Security

- Pairing codes: 6-character base64url, 5-minute TTL, one-time use
- Verification: `crypto.timingSafeEqual` for timing-safe comparison
- Authorization: `Bearer <relay-token>` header on all protected endpoints
- Pairing page (`/pairing`) and verify (`/pairing/verify`) do not require authorization
- All other endpoints require valid relay token when configured

### 7.7 Session Management

- **Session TTL:** 1 hour (`60 * 60 * 1000 ms`)
- **Idempotency:** 5-minute dedup window (`IDEMPOTENCY_TTL_MS = 5 * 60 * 1000`)
- **Pending actions:** Stored per session with expiry
- **Autonomy records:** Stored per session with `continueAfterMs` expiry
- **Pruning:** Automatic cleanup of expired records on every store access

### 7.8 Rate Limiting

- **Limit:** 100 requests per minute per session (`RATE_LIMIT_MAX_REQUESTS = 100`)
- **Window:** 60 seconds (`RATE_LIMIT_WINDOW_MS = 60_000`)
- **Response:** HTTP 429 with `{"error": "Rate limit exceeded"}`
- **Per-session:** Tracked by `sessionId` in `SessionRateLimiter`

### 7.9 Circuit Breaker

- **Threshold:** 5 consecutive failures (`CIRCUIT_BREAKER_THRESHOLD = 5`)
- **Degraded mode:** Returns error response: "Bridge is currently in degraded mode. Please try again shortly."
- **Auto-recovery:** After 30 seconds (`CIRCUIT_BREAKER_RESET_MS = 30_000`)
- **Health tracking:** `lastErrorCategory` exposed via `/health` endpoint

### 7.10 Network Security

- **Debug builds:** Allow cleartext HTTP (for local development)
- **Release builds:** Require HTTPS
- **No raw audio streaming to cloud:** All STT/TTS is local (Android platform or Sherpa-ONNX)
- **Bridge binding:** Default `127.0.0.1` -- must explicitly set `--host 0.0.0.0` for LAN access
- **Warning printed** when bridge is exposed beyond localhost

---

## 8. OpenClaw Integration

### 8.1 What OpenClaw Does

OpenClaw is a **rewrite-only integration**. It never has policy or execution authority. Its sole purpose is to reshape local runtime responses for voice-first ear-level delivery:

- Shortens long responses to 24 words or fewer
- Removes multi-line formatting
- Preserves original meaning
- Never invents approvals, commands, or status changes

### 8.2 Transport Modes

| Mode | CLI flags | Purpose |
|------|-----------|---------|
| `local` | `--brain local` (default) | Built-in Jarvis runtime only, no OpenClaw |
| `http` | `--brain openclaw --openclaw-transport http --openclaw-base-url <url> --openclaw-token <token>` | HTTP POST to OpenAI-compatible `/v1/chat/completions` endpoint |
| `local-cli` | `--brain openclaw --openclaw-transport local-cli --openclaw-model <model> --openclaw-config-path <path>` | Spawns `openclaw` CLI as subprocess for rewrite |
| `gateway-client` | `--brain openclaw --openclaw-transport gateway-client --openclaw-base-url <url> --openclaw-agent-id <id>` | Resident WebSocket connection to OpenClaw gateway via Worker thread |

### 8.3 Adaptive Rewrite Policy

**Default policy:** `adaptive` (when budgets configured) or `always` (manual construction)

**Budget values:**
- Foreground budget: **250 ms** (`--openclaw-foreground-budget-ms 250`)
- Background budget: **750 ms** (`--openclaw-background-budget-ms 750`)

**Adaptive trigger conditions:**
- Response > 24 words -> rewrite
- Response contains newlines -> rewrite
- Response > 16 words -> rewrite
- Response has follow-up hint AND > 12 words -> rewrite

**Timeout resolution:** `min(configuredTimeoutMs, budgetMs, 120000)`

**Fallback:** If rewrite times out or fails, the original local response is used unchanged.

### 8.4 Health Reporting

`OpenClawRewriteHealthSnapshot` exposed via `/health` endpoint:

```json
{
  "connectionState": "connected",
  "lastConnectionError": null,
  "foregroundBudgetMs": 250,
  "backgroundBudgetMs": 750,
  "counters": {
    "rewritten": 42,
    "skipped": 18,
    "timedOut": 2,
    "failed": 1
  },
  "lastOutcome": {
    "source": "foreground",
    "outcome": "rewritten",
    "durationMs": 180,
    "at": "2026-05-27T10:30:00.000Z"
  }
}
```

**Connection states:** `not_applicable`, `idle`, `connecting`, `connected`, `failed`

**Health check:** If recent failures exceed successes, `isOpenClawHealthy()` returns false and rewrites are skipped.

### 8.5 Sandboxed Managed Gateway

**Sandbox mode (`startOpenClawSandbox`):**
- Isolated temporary directory with workspace subdirectory
- Loopback-only HTTP binding
- Auto-generated token
- Dependency seeding for `json5` and `@mariozechner/*` packages
- Automatic cleanup on stop

**Managed gateway mode (`startOpenClawManagedGateway`):**
- Similar to sandbox but with `jarvis-openclaw-managed-` prefix
- Persistent state directory option
- Same isolation and cleanup guarantees

**Sandbox configuration:**
- Agent ID: `jarvis_rewrite`
- Default timeout: 180 seconds
- Model context window: 128,000 tokens
- Max tokens: 8,192
- Control UI disabled
- Only allowed provider plugin loaded

---

## 9. Technical Architecture

### 9.1 TypeScript Bridge (Desktop)

#### 9.1.1 HTTP Server

**File:** `src/bridge/server.ts`

| Endpoint | Method | Auth | Purpose |
|----------|--------|------|---------|
| `/health` | GET | Required | Health check with brain mode, OpenClaw status, degraded state |
| `/pairing` | GET | None | Pairing page (HTML with QR) or JSON payload |
| `/pairing/verify` | POST | None | Verify pairing code, return relay token |
| `/pairing/regenerate` | POST | Required | Generate new pairing code |
| `/events` | POST | Required | Submit earbud/relay event, receive Jarvis response |

**Server constants:**
- `MAX_REQUEST_BYTES = 8192` (8 KB body limit)
- `DEFAULT_SERVER_TIMEOUT_MS = 30_000`
- `PAIRING_CODE_TTL_MS = 300_000` (5 minutes)
- `RATE_LIMIT_WINDOW_MS = 60_000`
- `RATE_LIMIT_MAX_REQUESTS = 100`

#### 9.1.2 Bridge Runtime

**File:** `src/bridge/runtime.ts`

`BridgeRuntime` orchestrates the full event processing pipeline:
1. Check circuit breaker state
2. Set event source in session store
3. Dispatch through `EventRouter`
4. Optionally rewrite response via OpenClaw (if healthy)
5. Sync session state and autonomy
6. Notify via TTS (if not android_relay source)
7. Track consecutive failures for circuit breaker

#### 9.1.3 Event Router

**File:** `src/bridge/event-router.ts`

`EventRouter.dispatch()` handles all event types:
- `wake_and_listen` -> set state to `listening`, respond with "Jarvis active"
- `pause` -> set state to `paused`
- `resume` -> set state to `idle`
- `cancel` -> cancel background work, clear pending/autonomy
- `approval_action` -> handle approve/reject/cancel/expire
- `autonomy_continue` -> execute next autonomy step
- `autonomy_replan` -> resolve new intent from utterance, set new autonomy
- `quick_status` / `voice_command` -> resolve intent, evaluate policy, execute

#### 9.1.4 Policy Engine

**File:** `src/policy/engine.ts`

`evaluateIntentPolicy()` decision flow:
1. Check if intent is in workspace `allowedIntents` -> denied if not
2. Check if intent is in `hardApprovalIntents` -> `hard_approval`
3. Check if intent is in `approvalRequiredIntents` -> `approval_required` (or `immediate` if proven physical interrupt)
4. Check if intent is in `immediateIntents` -> `immediate`
5. Default -> `approval_required` (or `immediate` if proven physical interrupt)

#### 9.1.5 Session and Idempotency

**SessionStore:** In-memory maps for states, sources, pending actions, autonomy records. 1-hour TTL with automatic pruning.

**IdempotencyStore:** Deduplication by `sessionId:idempotencyKey` composite key. 5-minute TTL. Supports concurrent requests (pending promises shared).

#### 9.1.6 Intent Router

**File:** `src/jarvis/router.ts`

Keyword-based utterance to intent mapping:
- "push" -> `push`
- "deploy" -> `deploy`
- "delete file" / "delete " -> `delete`
- "revert file" / "revert " -> `revert`
- "diff" / "changes" -> `summarize_diff`
- "ci" / "build failed" / "github actions" -> `latest_ci_failure`
- "test" -> `run_tests`
- "commit message" -> `create_commit_message`
- "commit staged" / "commit the staged" -> `commit_staged`
- "open file" / "open *.ext" -> `open_file`
- "status" / "branch" -> `quick_status`
- Default -> `quick_status`

#### 9.1.7 Jarvis Runtime

**File:** `src/jarvis/runtime.ts`

`JarvisRuntime.executeIntent()` dispatches to intent handlers:
- Each handler calls workspace adapters (git, editor, CI, process)
- Background commands use `BackgroundCommandScheduler` for per-workspace queuing
- All responses pass through `optimizeSpeak()` for voice safety
- Redaction applied to error messages and command output

#### 9.1.8 Background Command Scheduler

**File:** `src/jarvis/background-command-scheduler.ts`

- Per-workspace lanes with active task + queue
- `schedule()` starts immediately if lane free, otherwise queues
- `cancel()` cancels running task and removes from queue
- Deferred start notification for queued tasks
- Completion/error callbacks with audit logging

#### 9.1.9 Adapters

| Adapter | File | Purpose |
|---------|------|---------|
| `git` | `src/adapters/git.ts` | Git operations: status, diff, commit, push, revert |
| `editor` | `src/adapters/editor.ts` | File opening in VS Code |
| `ci` | `src/adapters/ci.ts` | CI failure lookup from GitHub Actions |
| `process` | `src/adapters/process.ts` | Background command execution with timeout, cancellation |
| `workspace` | `src/adapters/workspace.ts` | Path resolution within workspace root |

#### 9.1.10 Protocol

**Files:** `src/protocol/schemas.ts`, `src/protocol/types.ts`

Zod-validated schemas for all data structures:
- `earbudEventSchema` / `androidRelayEventSchema` -- event payloads
- `bridgeRequestSchema` -- internal request format
- `jarvisResponseSchema` -- response format
- `workspaceConfigSchema` / `workspaceRegistrySchema` -- workspace configuration
- `auditRecordSchema` -- audit log entries
- `hardwareContextSchema` -- device evidence
- `autonomyInstructionSchema` -- bounded autonomy instructions

#### 9.1.11 Pairing

**Files:** `src/pairing/qr.ts`, `src/pairing/uri.ts`

- QR code generation via `qrcode` library, rendered as data URL
- Pairing URI builder: `devpods://pair?bridgeBaseUrl=...&workspace=...&pairingCode=...`
- Pairing page URL builder: `<base>/pairing`
- Base URL resolution from host/port with LAN-reachable detection

#### 9.1.12 Rate Limiter and Safety

`SessionRateLimiter` -- per-session sliding window:
- Tracks request count per session ID
- Resets window after 60 seconds
- Returns `false` when count exceeds 100

Request validation:
- 8 KB body limit
- JSON parse validation
- Zod schema validation for events
- Protocol version check for Android relay events

### 9.2 Android Relay

#### 9.2.1 Core Service Layer

**File:** `RelayService.kt`

`RelayService` extends `MediaSessionService` with foreground service types:
- `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`
- `FOREGROUND_SERVICE_TYPE_MICROPHONE` (when listening)

**Service actions:**
| Action constant | Purpose |
|----------------|---------|
| `ACTION_START_RELAY` | Start the relay service |
| `ACTION_STOP_RELAY` | Stop the relay service |
| `ACTION_CHECK_HEALTH` | Check bridge health |
| `ACTION_WAKE_AND_LISTEN` | Push-to-talk wake |
| `ACTION_QUICK_STATUS` | Quick status shortcut |
| `ACTION_ASSIST_LONG_PRESS` | Assistant long-press fallback |
| `ACTION_TEST_SPEAKER` | Speaker self-test |
| `ACTION_TAP_TEST` | Tap test for setup |
| `ACTION_APPROVE` | Approve pending action |
| `ACTION_REJECT` | Reject pending action |
| `ACTION_CANCEL` | Cancel pending action |
| `ACTION_RETRY_QUEUE` | Retry queued bridge events |
| `ACTION_DISCARD_QUEUE` | Discard queued bridge events |
| `ACTION_AUDIO_ROUTE_PROBE` | Probe audio route |
| `ACTION_DEBUG_EVENT` | Debug event injection |
| `ACTION_RUN_PROOF` | Run voice proof |
| `ACTION_EXPORT_PROOF` | Export proof results |

#### 9.2.2 Gesture Routing and Signal Processing

**CalibratedGestureRouter:** Routes raw provider events to calibrated actions:
- `WAKE_AND_LISTEN` -> start listening session
- `INTERRUPT` -> interrupt implementation and listen
- `APPROVE` -> send approval event
- `REJECT` -> send rejection event
- `NONE` -> ignore gesture

**Gesture-to-bridge mapping:**
- Wake gestures -> `triple_tap_right` or provider-specific trigger
- Interrupt gestures -> `both_hold_cancel` or provider-specific trigger
- Approval gestures -> `android_approve` / `android_reject`

#### 9.2.3 Configuration and Persistence

**RelayConfigStorage:** Persists `RelayConfig` to SharedPreferences:
- `bridgeBaseUrl`, `relayToken`, `workspace`, `sessionId`
- `useBluetoothRouting`, `phoneMicFallback`, `assistantFallback`
- `speechInputMode`, offline speech model settings
- Sherpa runtime flags

**DeviceProfileStorage:** Persists calibration profiles for earbud gesture mapping.

#### 9.2.4 Audio Pipeline

**BluetoothAudioRouter:**
- Routes communication audio to Bluetooth headset
- Tracks route state: active, ready for speech, phone mic fallback
- Provides snapshots for UI display

**AudioRouteFallbackPolicy:**
- Evaluates route snapshots against policy
- Decisions: `USE_REQUESTED_ROUTE`, `USE_PHONE_MIC_FALLBACK`, `BLOCK_LISTENING`
- Supports route settle retries

**AudioProbeMetrics:**
- Measures route success/failure rates
- Tracks route settle time
- Records wrong mic suspicions

#### 9.2.5 Voice and Diagnostics

**VoiceDiagnosticsStore:**
- Records speech sessions, TTS playback, audio probes, VAD observations
- Export summary for diagnostic sharing

**VadTelemetry:**
- Voice activity detection observations
- Wrong mic suspicion detection

**VoiceProofRun:**
- 20-session proof matrix
- Tracks: session count, success rate, route proof, wrong mic count, barge-in targets
- Status: `NOT_STARTED`, `RUNNING`, `PASSED`, `FAILED`

**SpeechSessionMetrics:**
- Full lifecycle tracking: route request, route ready, recognizer created, listening started, ready for speech, beginning of speech, end of speech, partial transcript, final transcript, error
- Timing measurements for each phase

#### 9.2.6 Screens

| Screen | File | Key components |
|--------|------|----------------|
| Home | `HomeScreen.kt` | Hero cards, Waveform, CountdownRing, QueueMeter, approval section, autonomy section, bridge queue section, error section, status chips |
| Activity | `ActivityScreen.kt` | Approval detail sheet, conversation card, timeline card, activity history, queued actions confirmation |
| Device | `DeviceScreen.kt` | Provider health list, QR pairing, capability summary, setup lifecycle, bridge management, fallback toggles, speech test |
| SetupWizard | `SetupWizardScreen.kt` | QueueMeter progress, step indicators, pairing card, device probe card, wake test card with countdown, STT test card, completion states |
| Help | `HelpScreen.kt` | Recovery actions, permissions, diagnostics checkboxes, voice proof run card, accessibility, version mismatch |
| Onboarding | `OnboardingScreen.kt` | Hero visual with earbuds + waveform, feature cards, primary pairing CTA |
| DeveloperMode | `DeveloperModeScreen.kt` | Bridge config display, relay controls grid, raw state summary |

#### 9.2.7 Bridge Communication

**BridgeClient.kt:**
- OkHttpClient with timeouts: connect 2s, write 10s, read 20s, call 20s
- Methods: `health()`, `sendEvent()`, `pairing()`, `pairingVerify()`
- Authorization via `Bearer <relayToken>` header
- JSON serialization via kotlinx.serialization

**Bridge event queue:**
- When bridge unreachable, events queued with exponential backoff retry
- UI shows `BridgeQueueSection` with queue meter, retry countdown, attempt count (max 5)
- Manual "Retry Now" and "Discard" buttons

#### 9.2.8 Diagnostics

**MediaButtonDiagnostics:**
- Classifies physical media button events
- Distinguishes hardware vs software events
- Records keycode, source package, event time

**DiagnosticExport:**
- Redacted JSON payload
- User-controlled inclusion toggles
- Shared via Android `Intent.ACTION_SEND`

### 9.3 OpenClaw Client

**File:** `src/openclaw/client.ts`

`OpenClawGatewayClient` class:
- Three transport implementations: HTTP, local CLI, gateway-client
- Resident gateway connection via `Worker` thread with WebSocket
- Health snapshot with counters and last outcome
- Rewrite request with budget-aware timeout
- Automatic connection reset on failure

**Gateway worker:**
- Inline JavaScript via `data:text/javascript` URL
- Manages WebSocket connection to OpenClaw gateway
- Request/response correlation with UUID
- Connection timeout: 15 seconds max

### 9.4 OpenClaw Sandbox and Managed Gateway

**File:** `src/openclaw/sandbox.ts`

**Sandbox features:**
- Temporary directory isolation
- Loopback-only binding
- Auto-generated token
- Dependency seeding with export patching for `require` compatibility
- Health polling with 500ms interval
- Graceful shutdown with process tree kill (Windows: `taskkill /T /F`)

**Managed gateway:**
- Same as sandbox but with persistent directory option
- Separate prefix: `jarvis-openclaw-managed-`

---

## 10. Simulation and Validation Tooling

### 10.1 Fake Earbud Events

**Directory:** `simulation/fake-earbud-events/`

- `send-event.ts` -- sends fixture events to bridge via HTTP
- `say.ts` -- sends voice command utterances
- Fixtures: `left_long_press`, `triple_tap_right`, `approve_right_double_tap`, `reject_left_double_tap`, `both_hold_cancel`, etc.

### 10.2 Android Relay Smoke Harness

**File:** `simulation/android-relay/smoke.ts`

Automated smoke test that:
1. Starts the bridge
2. Sends health check
3. Sends simulated events
4. Validates responses

### 10.3 Proof Run System

**Scripts:**
- `simulation/android-relay/run-t1-proof.ps1` -- T1 proof run
- `simulation/android-relay/run-t2-proof.ps1` -- T2 proof run
- `simulation/android-relay/run-pcm-proof.ps1` -- PCM injection proof run
- `simulation/android-relay/generate-proof-report.ts` -- generates proof report

**Artifacts:** `artifacts/proof-runs/` -- JSON proof run results

### 10.4 Windows Media Button Verification

**Script:** `simulation/windows-relay/verify-media-buttons.ps1`

- Duration parameter: `-DurationSeconds 30`
- Captures and classifies media button events from connected earbuds
- Proves earbuds can emit `MEDIA_PLAY_PAUSE` events on Windows

### 10.5 Supported Devices Matrix

**File:** `docs/supported-devices-matrix.json`

Schema validated in CI:
- `version` field required
- `providers` array with `providerId`, `providerLabel`, `features`
- `fallbackChain` array
- Valid statuses: `proven`, `observed`, `fallback_proven`, `implemented_unverified`, `scaffolded`, `unsupported`

### 10.6 License Scanning

**Script:** `scripts/license-scan.ts`

Scans dependencies for license compliance.

### 10.7 Dependency Audit

```bash
npm audit                    # Standard npm audit
npm run audit:allowlist      # Allowlist enforcement at moderate level
npm run audit:allowlist:strict  # Allowlist enforcement at low level
```

---

## 11. CI/CD Pipeline

### 11.1 GitHub Actions CI

**File:** `.github/workflows/ci.yml`

Three jobs:

**Bridge (TypeScript) -- `ubuntu-latest`:**
1. Checkout
2. Setup Node.js 22
3. `npm ci`
4. `npm run typecheck` -- TypeScript type checking
5. `npm run build` -- TypeScript compilation
6. `npm test` -- Vitest test suite
7. `npm audit` -- dependency audit
8. `npm run audit:allowlist` -- allowlist enforcement
9. Secret scan -- grep for `ghp_`, `sk-`, `AIza` patterns
10. Supported devices matrix schema validation
11. Proof-run artifact validation (dev mode: skip if no artifacts; beta/release: require T4 tier, max 7 days old)
12. License scan

**Android Relay -- `ubuntu-latest`:**
1. Checkout
2. Setup JDK 17 (Temurin)
3. Setup Android SDK
4. `chmod +x android-relay/gradlew`
5. `./gradlew :app:assembleDebug` -- debug build
6. `./gradlew :app:testDebugUnitTest` -- unit tests
7. `./gradlew :app:lintDebug` -- lint check
8. `./gradlew :sherpa-runtime:verifySherpaNativeLibs` -- native lib verification

**Windows Bridge Package -- `windows-latest`:**
1. Checkout
2. Setup Node.js 22
3. `npm ci`
4. `npm run build`
5. `package-bridge.ps1` -- Windows packaging
6. Upload artifact: `devpods-bridge-windows`

### 11.2 Local Validation Commands

```bash
# TypeScript
npm run typecheck        # tsc --noEmit
npm run build            # tsc -p tsconfig.json
npm test                 # vitest run (123 tests)
npm run test:watch       # vitest (watch mode)

# Android
cd android-relay
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest

# Packaging
npm run package:bridge:windows

# Smoke test
npm run relay:smoke

# Proof runs
npm run proof:t1
npm run proof:t2
npm run proof:pcm
npm run proof:report

# Verification
npm run verify:product   # verify-product.ps1
```

---

## 12. Protocol Reference

### 12.1 Event Contract

**Earbud event (simulator):**
```json
{
  "source": "developer_earbuds_simulator",
  "sessionId": "sim-session",
  "workspace": "current_repo",
  "device": "right_bud",
  "event": "left_long_press",
  "timestamp": 1716800000000,
  "battery": 85,
  "wearState": "in_ear",
  "profile": "default",
  "utterance": "summarize my diff",
  "pendingActionId": "act_a1b2c3d4e5f6",
  "hardwareContext": { "provider": "apple_airpods", "wakeSource": "right_triple_tap", "deviceConfidence": "proven" },
  "idempotencyKey": "unique-key-123"
}
```

**Android relay event:**
```json
{
  "source": "android_relay",
  "sessionId": "android-relay",
  "workspace": "current_repo",
  "device": "both_buds",
  "event": "android_push_to_talk",
  "timestamp": 1716800000000,
  "utterance": "run tests",
  "profile": "default",
  "protocolVersion": "1",
  "idempotencyKey": "android-relay-android_push_to_talk-voice-1716800000000"
}
```

**Required for Android relay events:** `protocolVersion` (must be "1"), `idempotencyKey`

### 12.2 Bridge API

| Endpoint | Method | Request | Response |
|----------|--------|---------|----------|
| `/health` | GET | -- | `{ ok, bridgeVersion, protocolVersion, brainMode, openclawTransport, openclawRewritePolicy, openclawRewriteHealth, degraded, queueDepth, ... }` |
| `/pairing` | GET | -- | HTML page with QR code OR JSON `{ bridgeBaseUrl, pairingCode, workspace, pairingUri, pairingPageUrl }` |
| `/pairing/verify` | POST | `{ pairingCode }` | `{ relayToken }` |
| `/pairing/regenerate` | POST | -- | `{ pairingCode, expiresAt }` |
| `/events` | POST | EarbudEvent or AndroidRelayEvent | JarvisResponse |

### 12.3 Approval Policy

```json
{
  "profile": "default",
  "allowReadOnly": true,
  "allowSafeWithoutApproval": true,
  "requireApprovalFor": ["run_tests", "open_file"],
  "requireHardApprovalFor": ["commit_staged", "push", "deploy", "delete", "revert"],
  "approvalTimeoutMs": 12000
}
```

### 12.4 Response Contract

```json
{
  "speak": "Branch main. 3 files changed. No tests running.",
  "display": "Branch main. 3 files changed. Main file: src/bridge/server.ts.",
  "requiresApproval": false,
  "approvalRequest": null,
  "actionId": null,
  "status": "completed",
  "nextState": "idle",
  "followUpHint": null,
  "autonomy": {
    "phase": "report",
    "mode": "continue_on_silence",
    "summary": "Tests finished successfully.",
    "nextStep": "Refresh the repo status.",
    "continueAfterMs": 4000,
    "nextIntent": "quick_status"
  }
}
```

**Status values:** `acknowledged`, `running`, `completed`, `blocked`, `cancelled`, `error`

**Next state values:** `idle`, `listening`, `thinking`, `approval_pending`, `queued`, `running`, `responding`, `paused`, `cancelled`

---

## 13. Error Handling and Resilience

### 13.1 Error Classification

**File:** `src/bridge/error-handler.ts`

| Category | Trigger patterns | User message | Retryable |
|----------|-----------------|--------------|-----------|
| `network` | `ECONNREFUSED`, `ENOTFOUND`, `network`, `fetch failed`, `EAI_AGAIN` | "Network connection failed. Please check your connection and try again." | Yes |
| `timeout` | `ETIMEDOUT`, `timeout`, `aborted` | "The request timed out. Please try again." | Yes |
| `auth` | `unauthorized`, `auth`, `forbidden`, `EACCES` | "Authentication failed. Please check your credentials." | No |
| `unknown` | Default | "Something went wrong. Please try again." | No |

### 13.2 Graceful Degradation Paths

| Failure mode | Degradation behavior |
|-------------|---------------------|
| Circuit breaker tripped | Returns degraded mode message, auto-recovers after 30s |
| OpenClaw unhealthy | Falls back to local response without rewrite |
| Bridge unreachable (Android) | Queues events with exponential backoff, shows retry UI |
| Speech recognizer busy | 500ms delay then retry |
| Audio route fails | Phone mic fallback if enabled, otherwise block with error |
| TTS fails | Fail closed -- console output only (desktop), error recorded (Android) |
| Audit log write fails | Fallback to stderr |

### 13.3 Request Validation

| Validation | Behavior |
|-----------|----------|
| Body > 8 KB | HTTP 413, "Request body too large" |
| Invalid JSON | HTTP 400, "Invalid JSON" |
| Schema validation failure | HTTP 400, "Invalid event payload" with detail |
| Unsupported protocol version | HTTP 400, "Unsupported protocol version: X. Supported: 1" |
| Rate limit exceeded | HTTP 429, "Rate limit exceeded" |
| Unauthorized | HTTP 401, "Unauthorized" |

---

## 14. Configuration Reference

### 14.1 CLI Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `start` | command | -- | Start the bridge HTTP server |
| `local` | command | -- | One-shot local execution (no HTTP) |
| `send` | command | -- | Send fixture event to running bridge |
| `listen` | command | -- | Interactive listen mode |
| `say` | command | -- | Send voice utterance to running bridge |
| `health` | command | -- | Check bridge health |
| `--port` | string | `4545` | Bridge HTTP port |
| `--host` | string | `127.0.0.1` | Bridge HTTP host |
| `--relay-token` | string | -- | Authorization token for relay |
| `--pairing-base-url` | string | -- | Base URL for pairing page |
| `--brain` | string | `local` | `local` or `openclaw` |
| `--workspaces-config` | string | `config/workspaces.json` | Path to workspace config |
| `--openclaw-transport` | string | `http` | `http`, `local-cli`, `gateway-client` |
| `--openclaw-base-url` | string | -- | OpenClaw HTTP/gateway base URL |
| `--openclaw-token` | string | -- | OpenClaw API token |
| `--openclaw-model` | string | -- | OpenClaw model identifier |
| `--openclaw-agent-id` | string | -- | OpenClaw gateway agent ID |
| `--openclaw-rewrite-policy` | string | -- | `always` or `adaptive` |
| `--openclaw-foreground-budget-ms` | string | -- | Foreground rewrite budget |
| `--openclaw-background-budget-ms` | string | -- | Background rewrite budget |
| `--openclaw-config-path` | string | -- | OpenClaw config file path |
| `--openclaw-state-dir` | string | -- | OpenClaw state directory |
| `--openclaw-workspace-dir` | string | -- | OpenClaw workspace directory |
| `--openclaw-provider-plugin-ids` | string | -- | OpenClaw provider plugin IDs |
| `--openclaw-timeout-ms` | string | -- | OpenClaw request timeout |

### 14.2 Environment Variables

| Variable | Purpose |
|----------|---------|
| `JARVIS_DISABLE_TTS` | Set to `1` to disable Windows TTS |
| `JARVIS_SPEAK_TEXT` | Spoken text passed to PowerShell TTS (internal) |
| `NVIDIA_API_KEY` / `NVIDIA_NIM_API_KEY` | NVIDIA API key for OpenClaw sandbox |
| `NODE_ENV` | Set to `production` to suppress TTS error logs |
| `VITEST` | Set by Vitest -- disables TTS during tests |

### 14.3 Workspace Configuration

**File:** `config/workspaces.json`

```json
{
  "defaultWorkspaceId": "current_repo",
  "workspaces": [
    {
      "id": "current_repo",
      "label": "firmware_earphones",
      "rootPath": "..",
      "allowedIntents": ["quick_status", "summarize_diff", "latest_ci_failure", "run_tests", "create_commit_message", "open_file", "commit_staged", "push", "deploy", "delete", "revert"],
      "approvalRequiredIntents": ["run_tests", "open_file"],
      "hardApprovalIntents": ["commit_staged", "push", "deploy", "delete", "revert"],
      "commands": {
        "run_tests": { "description": "Run workspace tests", "command": "npm", "args": ["test"], "timeoutMs": 120000 },
        "deploy": { "description": "Deploy current build", "command": "npm", "args": ["run", "build"], "timeoutMs": 120000 }
      }
    }
  ]
}
```

### 14.4 Android Permissions

| Permission | Purpose |
|-----------|---------|
| `RECORD_AUDIO` | Speech recognition (microphone input) |
| `BLUETOOTH` / `BLUETOOTH_CONNECT` | Bluetooth headset communication |
| `FOREGROUND_SERVICE` | Relay service foreground notification |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Media playback foreground type |
| `FOREGROUND_SERVICE_MICROPHONE` | Microphone foreground type |
| `INTERNET` | Bridge HTTP communication |
| `ACCESS_NETWORK_STATE` | Network connectivity checks |
| `POST_NOTIFICATIONS` | Foreground service notification |

---

## 15. Test Coverage Summary

### 15.1 TypeScript Bridge Tests

**Framework:** Vitest  
**Test files:** 21  
**Total tests:** 123

| Test file | Area |
|-----------|------|
| `acceptance-criteria.test.ts` | Acceptance criteria validation |
| `background-command-scheduler.test.ts` | Background command queuing and cancellation |
| `bridge-autonomy-workflow.test.ts` | Bounded autonomy lifecycle |
| `bridge-idempotency.test.ts` | Idempotency deduplication |
| `bridge-pairing-api.test.ts` | Pairing endpoint behavior |
| `bridge-request-builder.test.ts` | Request construction from events |
| `bridge-runtime.notifications.test.ts` | Notification delivery |
| `cli-openclaw-http.test.ts` | CLI OpenClaw HTTP mode |
| `cli-runtime-options.test.ts` | CLI option resolution |
| `fake-event-to-response.e2e.test.ts` | End-to-end event processing |
| `jarvis-ci-actions.test.ts` | CI adapter tests |
| `jarvis-local-actions.test.ts` | Local intent execution tests |
| `openclaw-managed-mode.test.ts` | Managed gateway mode |
| `openclaw-mode.test.ts` | OpenClaw integration modes |
| `openclaw-smoke-helpers.test.ts` | OpenClaw smoke test helpers |
| `pairing-uri.test.ts` | Pairing URI construction |
| `policy-engine.test.ts` | Policy evaluation logic |
| `process-adapter.test.ts` | Process execution adapter |
| `protocol-schemas.test.ts` | Zod schema validation |
| `redaction.test.ts` | Secret redaction patterns |
| `validate-proof-run.test.ts` | Proof run validation |

### 15.2 Android Relay Unit Tests

**Framework:** JUnit (via Gradle)

Key test classes:
- `HomeScreenReadyStateTest` -- Home screen state rendering
- `SpeakNowReadinessTest` -- Speak-now readiness computation
- `OfflineSpeechEvaluationTest` -- Offline STT evaluation
- `SherpaModelManagerTest` -- Sherpa model management
- `SetupProofGatingTest` -- Setup wizard proof gating
- `TtsPlaybackMetricsTest` -- TTS playback tracking
- `AudioCaptureOwnerTest` -- Audio capture coordination
- `VoiceProofRunTest` -- Voice proof run lifecycle
- `SpeechRecognitionErrorPolicyTest` -- STT error handling
- `ActivityHistoryEntryTest` -- Activity history entries
- `SpeechSessionMetricsTest` -- Speech session tracking
- `DiagnosticExportTest` -- Diagnostic export redaction
- `DeviceCapabilityMatrixTest` -- Capability assessment
- `SpeechEngineContractsTest` -- Speech engine contracts
- `VadTelemetryTest` -- VAD telemetry
- `VoiceDiagnosticsStoreTest` -- Voice diagnostics
- `TtsInterruptionMetricsTest` -- TTS interruption tracking
- `AudioRouteSessionTest` -- Audio route sessions
- `AudioRouteFallbackPolicyTest` -- Route fallback decisions
- `MediaButtonDiagnosticsTest` -- Media button classification
- `AudioProbeMetricsTest` -- Audio probe metrics
- `AudioRouteProofTest` -- Audio route proof
- `ProviderConformanceTest` -- Provider contract conformance
- `RelayGestureRoutingTest` -- Gesture routing
- `RelayViewModelImportTest` -- ViewModel import logic
- `BridgePairingClientTest` -- Bridge pairing client
- `RelayPairingTest` -- Relay pairing logic
- `ListenReadinessTest` -- Listen readiness computation
- `RelayTapTestFactoryTest` -- Tap test factory
- `RelaySignalProviderSummaryTest` -- Signal provider summary
- `RelaySignalMessagingTest` -- Signal messaging
- `RelayListeningRoutePolicyTest` -- Listening route policy
- `RelayAudioDeviceCatalogTest` -- Audio device catalog
- `AssistantEntryActivityTest` -- Assistant entry activity
- `HardwareContextSerializationTest` -- Hardware context serialization
- `RelayCommandAuthTest` -- Relay command authorization

---

## 16. Repository Layout

| Path | Purpose |
|------|---------|
| `src/protocol/` | Shared Zod schemas and intent types |
| `src/policy/` | Allowlists, approvals, redaction, workspace boundaries |
| `src/bridge/` | HTTP server, runtime, event router, session store, audit log, error handler, speaker |
| `src/jarvis/` | Intent router, local runtime, background scheduler, voice optimization |
| `src/openclaw/` | OpenClaw client, sandbox, validation |
| `src/adapters/` | Git, editor, CI, process, workspace adapters |
| `src/cli/` | CLI entry point (`jarvis-earbuds.ts`), runtime options |
| `src/simulator/` | Event simulator client |
| `simulation/fake-earbud-events/` | Event fixtures and CLI helpers |
| `simulation/android-relay/` | Android relay validation scripts, smoke harness, proof runs |
| `simulation/windows-relay/` | Windows media button verification |
| `android-relay/` | Android software relay MVP (Kotlin, Compose, Media3) |
| `android-relay/app/src/main/java/com/openclaw/relay/` | Core relay service, state store, ViewModel, screens |
| `android-relay/app/src/main/java/com/openclaw/relay/signal/` | 10-provider earbud mesh |
| `android-relay/app/src/main/java/com/openclaw/relay/ui/` | Compose UI screens and components |
| `android-relay/sherpa-runtime/` | Sherpa-ONNX native bindings |
| `config/workspaces.json` | Workspace allowlist and command configuration |
| `docs/` | Architecture, security, operations, implementation docs |
| `protocol/` | Human-readable protocol references |
| `test/` | TypeScript test suite (21 files, 123 tests) |
| `scripts/` | Utility scripts (license scan, proof validation, allowlist audit) |
| `packaging/windows/` | Windows portable bridge packaging |
| `.github/workflows/ci.yml` | GitHub Actions CI pipeline |
| `runtime-data/` | Runtime data (audit logs, OpenClaw state) |
| `artifacts/` | Build artifacts (Windows bridge package, proof runs) |

---

## 17. Glossary

| Term | Definition |
|------|-----------|
| **Bridge** | Local TypeScript HTTP server that receives earbud events and routes them through policy to developer actions |
| **Relay** | Android app that captures earbud signals, sends events to the bridge, and plays spoken responses |
| **Intent** | A developer action resolved from a spoken utterance (e.g., `quick_status`, `run_tests`) |
| **Workspace** | A configured project directory with allowlisted intents and commands |
| **Approval** | User confirmation required before executing risky actions |
| **Hard approval** | Elevated approval with red accent UI for destructive actions |
| **Autonomy** | Silence-driven continuation of background work without further user input |
| **Provider** | A signal source in the Android earbud mesh (e.g., `apple_airpods`, `android_media_session`) |
| **Wake gesture** | Physical earbud input that triggers the listening window |
| **Hardware context** | Evidence attached to events about the physical device that generated them |
| **OpenClaw** | External AI service used only for response rewriting, never for policy or execution |
| **Rewrite** | OpenClaw's reshaping of a local response for voice-safe delivery |
| **Adaptive policy** | Rewrite strategy that only triggers for responses exceeding word/line thresholds |
| **Budget** | Time limit for OpenClaw rewrite (250ms foreground, 750ms background) |
| **Circuit breaker** | Safety mechanism that enters degraded mode after 5 consecutive failures |
| **Idempotency** | Deduplication of repeated events within a 5-minute window |
| **Setup wizard** | 4-step guided verification: pairing, device probe, wake test, speech test |
| **Proof run** | 20-session validation matrix measuring route, STT, VAD, TTS reliability |
| **Calibration** | Mapping of raw provider events to calibrated gesture actions |
| **Barge-in** | User interruption of TTS playback during active speaking |
| **Phone mic fallback** | Using the device microphone when Bluetooth earbud routing fails |
| **Degraded mode** | Bridge state where all requests return an error message until auto-recovery |
