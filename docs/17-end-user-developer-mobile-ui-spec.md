# End-User And Developer Mobile UI Spec

## Purpose

This document defines how the Android Relay app should evolve from the current single long developer screen into a combined product UI that works for both of these audiences in the same app build:

- an end user who wants a reliable earbud-driven assistant experience
- a developer or QA operator who still needs direct access to pairing, bridge controls, diagnostics, and verification tools during the build-out phase

The goal is not to split the app into two separate products. The goal is to make the default experience feel like a user product, while keeping the existing developer capabilities available behind deliberate progressive disclosure.

This spec is grounded in the code that already exists today, especially:

- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayModels.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayStateStore.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/OnboardingScreen.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/SetupWizardScreen.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/DeviceStatusCard.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/ui/TroubleshootingScreen.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/device/DeviceCapabilityMatrix.kt`
- `docs/10-rmx3990-buds-air7-field-notes.md`
- `docs/supported-devices-matrix.json`

## Current Baseline In Code

The current app already contains almost every important feature surface, but it presents them as a single scrolling operations page.

Current order in `RelayScreen(...)`:

1. App title and subtitle
2. Setup wizard when `state.showSetupWizard` is true
3. Device status card
4. Current state card
5. Pairing card
6. Primary actions card
7. Advanced settings card when toggled open
8. Troubleshooting card
9. Approval controls card
10. Latest interaction card
11. Error card

This is good for development because everything is visible, but it is not yet good product UI because:

- pairing, setup, diagnostics, approvals, and raw bridge controls compete for attention at the same hierarchy level
- the app defaults to an operator console instead of a user task flow
- raw internal concepts like bridge URL, token, workspace, provider IDs, and relay status language appear too early
- errors currently live in-line instead of being elevated into clear recovery flows
- setup feels like verification tooling instead of guided product onboarding

## Product Positioning For The UI

The app should present itself as:

"DevPods Relay lets you talk to your development workspace through ordinary earbuds."

That framing matters. The user is not opening a transport debugger. They are opening an earbud assistant that can:

- pair to a desktop bridge
- verify whether their earbud path is usable
- listen when they tap or use push-to-talk
- speak short replies back through the selected audio route
- ask for approval before risky actions
- explain what is broken and how to recover

The developer view still exists, but it is a layer on top of the product experience, not the primary shell.

## Design Principles

### 1. User-first by default

The first-launch experience and the steady-state home screen must use user language, not implementation language.

Use:

- "Pair your bridge"
- "Earbuds connected"
- "Ready to listen"
- "Bridge unreachable"
- "Try push-to-talk"

Avoid in default mode:

- raw provider IDs
- relay bearer token
- workspace identifiers unless the user deliberately opens developer tools
- transport and schema terms

### 2. Developer tools remain in the same build

The app must keep the current operational capabilities because the project is still in active build and validation mode. Developer surfaces stay inside the app, but are gated behind an explicit Developer Mode.

### 3. Capability claims must be evidence-based

The UI must not imply that wake, STT-after-wake, interruption, or battery telemetry are supported unless the device capability matrix or observed runtime state proves them.

This requirement comes directly from the field validation work and support matrix.

### 4. One primary state at a time

The user should always be able to answer two questions immediately:

- "What state is the relay in right now?"
- "What should I do next?"

The existing `derivePrimaryState(...)` logic is the right seed for this behavior and should become the main hero state model.

### 5. Hands-free first, fallback always visible

The product promise is earbud control, but the UI must never strand the user when the hardware path is limited. Push-to-talk, permission repair, rerun setup, and diagnostics export should always be reachable.

### 6. Troubleshooting is part of the product

Troubleshooting is not a separate engineering concern. Because OEM Bluetooth behavior varies, the help and recovery layer is part of the core end-user UX.

## Audience Model

### Audience A: End User

This person wants the app to work with minimal setup knowledge. They care about:

- whether the bridge is paired
- whether the earbuds are connected
- whether listening works
- what the assistant heard
- whether an action needs approval
- how to fix problems quickly

### Audience B: Developer Or Power User

This person needs all end-user functionality plus:

- raw pairing import
- QR scan
- bridge base URL override
- token visibility and workspace override
- relay lifecycle controls
- health, quick status, speaker test, tap test
- visibility into recent transcript, status, approval queue, and route details

### Audience C: QA Or Hardware Validation Operator

This person needs:

- setup wizard replay
- capability matrix visibility
- diagnostics export
- confirmation that support claims remain conservative
- distinction between proven, observed, blocked, and unproven hardware behaviors

## App Mode Model

The app should have one codebase and one APK, but two presentation layers.

### Standard Mode

Standard Mode is the default for first launch and for normal daily usage.

Visible destinations:

- Home
- Activity
- Device
- Help

### Developer Mode

Developer Mode is a local UI preference. It does not change backend behavior. It only reveals extra navigation and more detailed controls.

Visible destinations:

- Home
- Activity
- Device
- Help
- Dev

Developer Mode enablement should use both of these paths:

- a clear toggle in Help or About so the feature is discoverable during development
- the existing debug-friendly affordance pattern if the team wants a hidden shortcut later

Developer Mode must persist locally and default to off in release-oriented user builds.

## Information Architecture

The current single long page should be refactored into a five-surface architecture.

### 1. Home

Purpose:

- show the main product state
- present the next best action
- expose the minimum controls needed for daily use

### 2. Activity

Purpose:

- show the latest interaction history
- expose approval flows
- explain what the assistant heard, said, and is waiting on

### 3. Device

Purpose:

- manage pairing and setup
- show hardware readiness and compatibility status
- rerun hardware validation

### 4. Help

Purpose:

- show human-readable recovery guidance
- export diagnostics
- manage permissions and support information

### 5. Dev

Purpose:

- preserve the current operator console
- expose advanced settings and manual controls
- keep engineering and QA unblocked without polluting the default end-user shell

## Navigation Model

Use a bottom navigation bar for the main destinations. The app already behaves like a single-task tool, so bottom navigation is a better fit than a drawer.

Recommended destination order:

1. Home
2. Activity
3. Device
4. Help
5. Dev when Developer Mode is enabled

Global UI elements that sit above destination content:

- top app bar with product title and a small mode badge
- transient alert banner for critical issues
- approval bottom sheet when `pendingApprovalSummary` is present
- QR scanner as a modal flow
- setup wizard as a full-screen guided flow, not an in-page card

## Visual Direction

The UI should stay within Material 3, but it should feel more intentional than a plain engineering utility.

### Tone

- calm and technical
- voice-first, not dashboard-first
- compact, but not dense
- more "audio control center" than "settings form"

### Color semantics

- Ready: green or teal-tinted success surface
- Degraded or limited: amber surface
- Blocked or error: red surface
- Listening: animated active listening accent
- Thinking: neutral warm pulse
- Speaking: blue output accent

Do not overuse bright colors across the whole app. Reserve strong color blocks for stateful hero modules, readiness banners, and approval risk states.

### Typography and spacing

- use strong hierarchy in the hero state and section headers
- prefer short cards with one clear action over long dense forms
- maintain generous spacing around the primary state and current action CTA
- use chips and rows for secondary technical details instead of paragraphs

## End-To-End User Flows

### Flow 1: First Launch

1. User opens the app.
2. If `showOnboarding` is true, show onboarding as a full-screen introduction.
3. Onboarding ends with "Pair your bridge" as the main CTA.
4. The user lands in the Device surface and chooses one of these:
   - scan QR
   - import pairing link
   - enter manual pairing details in Developer Mode
5. After successful pairing, the app offers a guided setup run.
6. Setup verifies device probe, wake path, and STT path in sequence.
7. After setup, the user lands on Home in a ready or partially-ready state.

### Flow 2: Returning Healthy User

1. User opens the app.
2. Home immediately shows current state from `derivePrimaryState(...)`.
3. Main CTA is either "Listen now" or "Start relay" depending on service state.
4. Activity shows last transcript and last spoken response.
5. Device shows whether the current earbud path is proven, limited, or unverified.

### Flow 3: Blocked Hardware Path

1. Device or Home shows a blocked or degraded readiness state.
2. Help explains the issue in user language.
3. The user sees the next fallback path, for example push-to-talk or assistant long-press.
4. Diagnostics export is available without entering Developer Mode.

### Flow 4: Approval-Required Action

1. User wakes the assistant and speaks a command.
2. Activity and Home both surface the approval-needed state.
3. Approval sheet shows the action summary and risk framing.
4. User can Approve, Reject, or Cancel.
5. If approved, Activity updates with the running or completed state.

### Flow 5: Developer Debugging Session

1. Developer enables Developer Mode.
2. Dev destination becomes visible.
3. They can change bridge URL, token, workspace, and run relay actions directly.
4. Device still holds the capability and pairing story.
5. Help still owns diagnostics and recovery.

## Screen Specification

## Home

Home is the steady-state product surface.

### Home layout

1. Top app bar
2. Primary state hero
3. Secondary status row
4. Main action cluster
5. Compact current session summary
6. Contextual recovery banner when needed

### Home content mapping

Primary state hero should be driven by `derivePrimaryState(state)` and display:

- title
- supporting explanation
- current severity or mode tint
- one primary CTA
- one secondary CTA when appropriate

Example CTA mapping:

| State | Primary CTA | Secondary CTA |
| --- | --- | --- |
| Not paired | Pair bridge | Open help |
| Bridge paired, relay stopped | Start relay | Check health |
| Speech unavailable | Fix speech | Open help |
| Ready | Listen now | Device status |
| Listening | Stop listening if needed | View activity |
| Thinking | View activity | Cancel |
| Speaking | View activity | Cancel |
| Approval required | Review action | Cancel |
| Attention needed | Fix issue | Export diagnostics |

### Secondary status row

Show compact chips for:

- Bridge status
- Earbud readiness
- Audio route
- Speech availability

In Standard Mode, use friendly labels.

Examples:

- "Bridge reachable"
- "Earbud wake unverified"
- "Using earbuds microphone"
- "Speech engine unavailable"

In Developer Mode, each chip can reveal deeper labels on tap.

### Main action cluster

Standard Mode should keep only actions that match the product promise:

- Start relay or Stop relay
- Listen now
- Check bridge
- Run setup when readiness is blocked or incomplete

Do not show Speaker Test, Tap Test, raw Permissions, or manual token editing in Standard Mode.

### Compact current session summary

Show a simplified version of the most recent interaction:

- last transcript preview
- last spoken reply preview
- current approval or action status

This is a preview card that links into Activity for the full detail.

## Activity

Activity turns the current "Latest Interaction" and "Approval controls" cards into a coherent conversation and action timeline.

### Activity layout

1. Pending approval banner or sheet trigger
2. Latest conversation card
3. Action state timeline
4. Optional recent session history

### Activity must show

- transcript actually captured
- live partial transcript while speech capture is in progress when available
- response actually spoken
- display text when it differs from the spoken response
- status returned by bridge
- whether autonomy is active
- whether an approval is pending

### Approval UX

The current separate Approval controls card should become:

- a sticky top card inside Activity
- a global bottom sheet entry point from Home when approval is pending

Approval copy should avoid pure engineering labels. Prefer:

- "Review requested action"
- "This action may change code or run tools"
- "Approve to continue"

Developer Mode may show `pendingActionId`, raw status, or risk class.

### Timeline entries

Each interaction should be represented as small chronological blocks:

- Wake received
- Listening started
- Transcript captured
- Bridge processing
- Approval requested if applicable
- Reply spoken

This does not require a backend redesign to start. The first version can be derived from current state and the most recent response fields.

## Device

Device is the control center for pairing, setup, and hardware truth.

### Device layout

1. Earbud readiness card
2. Pairing card
3. Capability matrix summary
4. Setup entry point
5. Audio route detail

### Earbud readiness card

This is the evolved form of `DeviceStatusCard(...)`.

It should show:

- device display name
- connection state
- battery when available
- in-ear status when available
- readiness summary in user language
- a single "Run setup" or "Retest" action

In Standard Mode, do not show raw `providerId` by default. Show product labels such as:

- "Direct earbud controls"
- "Phone assistant fallback"
- "Media button relay"

Developer Mode can reveal provider IDs.

### Pairing card

Pairing belongs in Device, not Home.

Standard Mode pairing options:

- Scan QR
- Import pairing link
- View current paired bridge host

Developer Mode adds:

- manual bridge URL field
- manual relay token field
- workspace override

### Capability matrix summary

The capability matrix should become visible to the user in a simplified form.

Show categories such as:

- Wake from earbuds
- Interrupt during reply
- Approve or reject by gesture
- In-ear detection
- Battery reporting
- Speech after wake

Each item should use one of these public labels:

- Proven
- Observed
- Not yet proven
- Unsupported

This is the place where the app can remain honest on devices like RMX3990 where direct BLE or direct wake is blocked.

### Setup entry point

The existing setup wizard should become a full-screen flow launched from Device and from Home when the app needs repair.

## Help

Help owns recovery, permissions, and support.

### Help layout

1. Current issue summary
2. Recovery actions
3. Permissions checklist
4. Diagnostics export
5. About and Developer Mode toggle

### Current issue summary

Use `userFacingErrorMessage` first when available.

If no user-facing error is active, use readiness-driven suggestions from `TroubleshootingCard(...)`.

### Recovery actions

This section should present the next best action for common problems:

- bridge unreachable
- pairing expired or invalid
- microphone permission denied
- speech engine unavailable
- no headset connected
- route not ready for capture

### Permissions checklist

Show the current required permission or capability state in plain language.

Examples:

- Microphone allowed
- Notifications allowed if needed for service behavior
- Speech recognition available
- Audio route ready

### Diagnostics export

Diagnostics export must remain visible in Standard Mode because it is part of the support loop, not just engineering tooling.

## Dev

Dev is the dedicated developer and QA workspace inside the app.

This screen should absorb the current "Primary actions" and "Advanced settings" cards.

### Dev layout

1. Bridge configuration card
2. Relay controls grid
3. Manual verification tools
4. Raw state summary

### Bridge configuration card

Move these existing controls here:

- bridge base URL
- relay bearer token, hidden by default
- workspace
- manual pairing import if needed

### Relay controls grid

Preserve the existing operational buttons:

- Permissions
- Start
- Stop
- Health
- Status
- Push To Talk
- Speaker
- Tap Test

### Manual verification tools

Developer Mode should also expose:

- rerun setup
- capability matrix detail
- raw audio route values
- last wake trigger and provider

### Raw state summary

This is where the app can show implementation-facing data such as:

- `bridgeStatus`
- `audioRoute.status`
- latency snapshots for health, bridge command, and speech start
- `pendingActionId`
- `lastHeadsetEvent`
- `partialTranscript`
- `lastResponseDisplay`
- signal provider summary
- speech and TTS readiness flags

## Onboarding Specification

The current `OnboardingScreen(...)` already contains the right conceptual content, but it should be reframed as a product onboarding flow rather than a static educational page.

Recommended onboarding sequence:

1. Welcome
2. What DevPods does
3. Pair your desktop bridge
4. How earbud gestures work on this phone
5. Where to get help

The final onboarding CTA should be state-aware:

- if not paired: "Pair your bridge"
- if paired but not verified: "Run setup"
- if ready: "Go to Home"

## Setup Wizard Specification

The existing `SetupWizardScreen(...)` is the correct core flow, but it should change presentation from "verification wizard embedded in a debug page" to "guided setup journey".

### Setup phases

Keep the current phase model:

- `PAIRING`
- `DEVICE_PROBE`
- `GESTURE_TEST`
- `STT_TEST`
- `COMPLETE`

### Setup UX rules

- each phase should occupy the full content area
- each phase should explain what the user is proving and why it matters
- each phase should say what happens next
- if a phase cannot prove a capability, the app should still finish setup and mark the capability honestly

### Setup completion output

When setup completes, show:

- what is proven
- what fallback path the user should use if something is unproven
- a direct CTA back to Home
- a secondary CTA to view Device details

## Global State-To-Surface Mapping

The UI should map directly from the existing state model instead of inventing a separate presentation store.

| `RelayUiState` field | Primary surface | Secondary surface | Notes |
| --- | --- | --- | --- |
| `config.isPaired()` | Home hero | Device pairing | Drives not-paired vs paired entry states |
| `bridgeStatus` | Home chip | Dev raw state | User text in Standard Mode, raw detail in Dev |
| `isServiceRunning` | Home CTA | Dev controls | Determines Start vs Stop emphasis |
| `isListening` | Home hero | Activity timeline | Listening should feel live and obvious |
| `isAwaitingBridgeResponse` | Home hero | Activity timeline | Maps to Thinking |
| `isSpeaking` | Home hero | Activity timeline | Maps to Speaking |
| `pendingApprovalSummary` | Home CTA | Activity approval surface | Approval is global, not buried |
| `pendingActionId` | Activity detail | Dev raw state | Keep mostly hidden outside Dev |
| `lastTranscript` | Activity | Home preview | Show transcript preview in Home |
| `partialTranscript` | Activity live capture | Dev raw state | Useful while listening before final transcript lands |
| `lastResponseSpeak` | Activity | Home preview | Spoken reply preview |
| `lastResponseDisplay` | Activity detail | Dev raw state | Show when display copy differs from spoken output |
| `lastResponseStatus` | Activity | Dev raw state | Human label in standard mode |
| `listenReadiness` | Home chip | Device status | Color and wording must change by readiness |
| `listenReadinessMessage` | Device | Help | Core explanation for readiness |
| `currentDeviceState` | Device | Dev detail | Product labels in Standard Mode |
| `audioRoute` | Device | Dev raw state | Summarize route health in user language |
| `latency` | Dev raw state | none | Useful for tuning and validation, not standard user UI |
| `capabilityMatrix` | Device | Help | Convert raw capability statuses into support truth |
| `userFacingErrorMessage` | Help | Home alert banner | Prefer this over raw `errorMessage` |
| `showOnboarding` | Onboarding flow | none | First-run or reintroduced by version |
| `showSetupWizard` and `setupPhase` | Setup flow | Device entry | Full-screen guided experience |

## Content And Copy Rules

### Standard Mode copy

- short
- reassuring but technically honest
- focused on action
- never claim unsupported hardware behavior

### Developer Mode copy

- can include implementation terms
- should still avoid unnecessary jargon when a clearer term exists
- may reveal provider IDs, route details, and raw status strings

### Spoken output relationship

The UI should visually mirror the speech contract:

- short response summaries
- no paragraph walls
- one clear action or status per card

## Error And Recovery Rules

### Rule 1: Promote critical recovery to the top of the relevant surface

If the bridge is unreachable or pairing is invalid, the user should not have to hunt inside a long card stack.

### Rule 2: Keep technical specifics available, but not primary

Example:

- Standard Mode: "The desktop bridge is unreachable. Make sure your computer and phone are on the same network."
- Developer Mode detail: show bridge base URL and last health status beneath it

### Rule 3: Never make diagnostics export the only recovery path

The app should always provide at least one immediate retry or repair action.

## Implementation Mapping Back To Current Code

This UI refactor can be done without changing the core bridge contract.

### Existing building blocks that should be preserved

- `derivePrimaryState(...)` becomes the Home hero state engine
- `OnboardingScreen(...)` becomes the first-run introduction flow
- `SetupWizardScreen(...)` becomes the guided setup route
- `DeviceStatusCard(...)` evolves into the Device readiness section
- `TroubleshootingCard(...)` becomes the Help recovery surface
- `RelayViewModel` actions remain the command layer for all button taps
- `RelayStateStore` remains the single source of truth for UI state

### New composables that should be introduced

- `RelayAppShell`
- `RelayHomeScreen`
- `RelayActivityScreen`
- `RelayDeviceScreen`
- `RelayHelpScreen`
- `RelayDeveloperScreen`
- `PrimaryStateHero`
- `ApprovalBottomSheet`
- `CapabilitySummaryCard`
- `BridgePairingCard`
- `SessionPreviewCard`

### Local UI state that may be added

- `developerModeEnabled`
- `selectedDestination`
- `showApprovalSheet`
- `onboardingVersionSeen`

These are local presentation concerns and should not pollute bridge or protocol contracts.

## Migration Plan

### Phase 1: Reframe without behavior change

- keep all current actions and state wiring
- split the single long screen into destination composables
- move advanced settings and operator controls into Dev
- move troubleshooting into Help
- move pairing and device status into Device
- move latest interaction and approvals into Activity

### Phase 2: Productize Home

- create a proper hero state card from `derivePrimaryState(...)`
- reduce visible actions in Standard Mode
- add session preview and clearer next-step CTAs

### Phase 3: Productize setup and device truth

- convert setup into a full-screen guided flow
- add capability summary cards driven by the matrix
- make support claims conservative and explicit

### Phase 4: Add developer polish

- improve raw state summaries
- expose advanced diagnostic detail cleanly
- keep tokens masked by default

### Phase 5: UX polish and release hardening

- improve motion for listening, thinking, and speaking states
- tune copy for short, confident instructions
- confirm accessibility, landscape behavior, and small-screen behavior

## Acceptance Criteria For The UI Refactor

The combined UI should be considered successful when all of the following are true:

- a first-time user can pair, run setup, and reach a clear ready state without opening Developer Mode
- a returning user can understand current readiness from Home in under five seconds
- approval-required actions are visible from both Home and Activity
- diagnostics export remains available in Standard Mode
- raw bridge configuration and manual verification tools are available in Developer Mode only
- device compatibility truth is visible and conservative
- the existing `RelayViewModel` action surface still maps cleanly into the UI
- the app feels like a product with developer tooling, not a developer tool with product copy pasted on top

## Final Direction

The right combined UI is not a cosmetic cleanup of the current scrolling page. It is a clear separation of concerns inside the same app:

- Home for the product state
- Activity for live conversation and approvals
- Device for pairing, setup, and hardware truth
- Help for recovery and support
- Dev for the operator console

That structure preserves everything valuable in the current implementation while finally giving the end user a surface that matches the real product promise.