# Developer Earbuds Architecture And Roadmap

## Goal

Build developer-focused earbuds where the firmware emits programmable gestures and device state, a bridge routes those signals into OpenClaw, and OpenClaw speaks short, safe, useful answers back into the earbuds.

## Non-Goals

- Running an LLM inside the earbuds
- Supporting arbitrary cheap earbuds in v1
- Depending on root hacks or vendor spoofing as a core product assumption
- Giving the agent unrestricted destructive powers

## Product Boundary

The clean system split is:

- Earbud firmware = body
- Bridge app or daemon = nervous system
- OpenClaw skill = brain
- Developer tools and OS automation = hands
- Safety and approval rules = conscience

## First Hardware Target Policy

Do not start with random low-cost OEM earbuds.

Start with one device that has all of the following:

- open or modifiable firmware path
- recoverable flashing story
- exposed gesture and battery state surfaces
- enough community knowledge to debug failures

That means your first target should look more like PineBuds Pro or another open-firmware-capable platform than an unknown retail earbud.

The actual hard-gate and weighted scoring process lives in [03-hardware-target-selection.md](03-hardware-target-selection.md).

## Firmware Strategy Levels

Split firmware work into three levels:

| Level | Name | Scope |
| --- | --- | --- |
| 1 | Firmware configuration | Use existing firmware features and expose better mappings without changing the underlying control surface |
| 2 | Firmware extension | Add a custom BLE service, gesture event emission, wear-state, battery-state, and profile-state reporting, basic profile switching, and prompt hooks while keeping the audio stack and recovery story intact |
| 3 | Firmware replacement | Replace or heavily modify the firmware control layer and own the full update, recovery, and device-behavior surface |

The MVP target is `Level 2 - Firmware extension`.

Level 2 is the right first milestone because it makes the earbuds feel programmable without taking on the cost and recovery risk of Level 3 replacement.

Minimum Level 2 deliverables:

- custom BLE event service
- gesture event emission
- battery-state, wear-state, and profile-state reporting
- basic profile switching
- safe OTA and recovery path

## Recommended System Architecture

```text
Programmable Earbud Firmware
  -> gesture events, wear state, battery, profile, ota state

Desktop Bridge / Companion App
  -> BLE or transport handling
  -> STT session control
  -> TTS playback
  -> policy checks
  -> local API for OpenClaw

OpenClaw Jarvis Skill
  -> intent routing
  -> workspace awareness
  -> developer tool actions
  -> approval enforcement
  -> short spoken responses

Developer Tool Layer
  -> git
  -> terminal
  -> CI
  -> editor integration
  -> issue tracker and notifications later
```

## Responsibilities By Layer

| Layer | Responsibilities | Must not own |
| --- | --- | --- |
| Firmware | Gestures, wear detection, battery, profile switching, audio prompts, OTA and recovery | STT, TTS, LLM logic, repo tooling |
| Bridge | Pairing, event ingestion, command routing, STT, TTS, local IPC, approval gating, logs | Repo-specific task reasoning |
| OpenClaw skill | Intent handling, developer context, repo and CI inspection, command selection, spoken summaries | Bluetooth transport, firmware update logic |
| Tool adapters | Git, terminal, VS Code, CI, notifications | Gesture interpretation |

## Interface Contracts

### Firmware -> Bridge event contract

```json
{
  "device": "right_bud",
  "event": "triple_tap",
  "timestamp": 1710000000,
  "battery": 74,
  "wearState": "in_ear",
  "profile": "coding_mode"
}
```

Minimum v1 event types:

- `single_tap`
- `double_tap`
- `triple_tap`
- `long_press`
- `both_hold`
- `wear_state_changed`
- `battery_changed`
- `profile_changed`

### Bridge -> OpenClaw request contract

```json
{
  "source": "developer_earbuds",
  "sessionId": "sess_123",
  "workspace": "current_repo",
  "event": "voice_command",
  "utterance": "Check the latest test failure",
  "gesture": "triple_tap_right",
  "riskPolicy": {
    "profile": "default"
  }
}
```

### OpenClaw -> Bridge response contract

```json
{
  "speak": "Three frontend tests failed. Main issue is in ResearchEngine.test.tsx.",
  "display": "Optional longer text for desktop UI",
  "requiresApproval": false,
  "actionId": null,
  "status": "completed",
  "nextState": "idle"
}
```

The full skill contract lives in [04-openclaw-jarvis-skill-contract.md](04-openclaw-jarvis-skill-contract.md).

### Approval contract

```json
{
  "actionId": "act_123",
  "approval": "approve",
  "method": "double_tap_right"
}
```

### Gesture semantics

Treat gesture and wear-state signals as strict control inputs with one meaning each.

| Gesture or behavior | Meaning |
| --- | --- |
| Right triple tap | Wake Jarvis and open the listen window |
| Left long press | Read current developer status |
| Right double tap | Approve the pending action |
| Left double tap | Reject the pending action |
| Both hold | Emergency cancel of the active listen or action flow |
| Remove one bud | Pause listening immediately |
| Remove both buds | End the active session |
| Put both buds back in | Resume passive updates |

Rules:

- `both_hold` is a hard cancel path and must interrupt STT, queued tool execution, and spoken output as quickly as possible
- `remove_one_bud`, `remove_both_buds`, and resume behavior are derived from wear-state changes and should be debounced before changing session state
- wear-state changes are control signals, not only telemetry

## Core User Flows

### Flow 1: Wake and ask

1. Right triple tap wakes the bridge.
2. Bridge opens a short listening window.
3. User says a developer command.
4. Bridge transcribes and sends to OpenClaw.
5. OpenClaw answers with a short spoken summary.

### Flow 2: Read current dev status

1. Left long press requests workspace status.
2. OpenClaw checks branch, local diff, running tasks, and latest CI state.
3. Bridge speaks a compact summary.

### Flow 3: Approve safe-but-important action

1. OpenClaw proposes an action such as creating a commit.
2. Bridge speaks a summary and asks for approval.
3. Right double tap approves, left double tap rejects, and both hold performs an emergency cancel.

## Latency Budget

| Step | Target |
| --- | --- |
| Gesture detection -> bridge event | < 150 ms |
| Listen window starts | < 300 ms |
| Speech-to-text final transcript | < 1.5 sec after speech |
| OpenClaw first response | < 2 sec for simple tasks |
| TTS starts speaking | < 700 ms |
| Total perceived response | ideally < 4 sec |

For longer operations such as tests or CI checks, the system should acknowledge quickly, then complete asynchronously. Example spoken pattern: "Running tests. I'll notify you when it finishes."

## Safety Model

The bridge and the OpenClaw skill both enforce policy. Do not put all safety in one layer.

The full permission and privacy rules live in [05-security-and-permission-model.md](05-security-and-permission-model.md).

### Immediate actions

- repo status
- summarize diff
- read logs
- read latest CI failure
- battery and connection status

### Approval-required actions

- create commit message
- commit staged files
- open a file in the editor
- run non-destructive project commands if they are slow or noisy

### Hard-approval actions

- push
- delete files
- revert changes
- deploy
- any command outside the allowed project command list

## MVP Definition

### MVP 1: Simulated hardware loop

Use fake event JSON files and a CLI sender before BLE integration or firmware work.

Implementation detail:

```text
simulation/
  fake-earbud-events/
    send-event.ts
    events/
      triple_tap_right.json
      left_long_press.json
      approve_right_double_tap.json
      reject_left_double_tap.json
      both_hold_cancel.json
      remove_one_bud_pause.json
      remove_both_buds_end_session.json
      put_both_in_resume.json
```

Example CLI:

```bash
jarvis-earbuds send triple_tap_right
jarvis-earbuds send left_long_press
jarvis-earbuds send approve_right_double_tap
jarvis-earbuds send reject_left_double_tap
jarvis-earbuds send both_hold_cancel
jarvis-earbuds send remove_one_bud_pause
jarvis-earbuds send remove_both_buds_end_session
jarvis-earbuds send put_both_in_resume
```

Required outcome:

- fake wake, status, approval, cancel, and wear-state events reach the bridge
- bridge can open the listen and respond cycle without Bluetooth
- OpenClaw returns spoken responses against the simulated event feed
- session pause, session end, and resume behaviors work end to end

### MVP 2: Real earbud event input

Required outcome:

- the target hardware reaches Level 2 firmware extension, not Level 3 replacement
- a custom BLE event service delivers real gesture events into the bridge reliably
- wear-state, battery-state, and profile-state changes are visible in logs
- basic profile switching works without breaking OTA or recovery

### MVP 3: Developer action loop

Required outcome:

- branch status
- diff summary
- test run
- CI failure summary
- open-file request

### MVP 4: Approval loop

Required outcome:

- approve, reject, and cancel gestures work end to end
- risky actions cannot bypass the approval model

## Recommended Repository Shape

```text
firmware_earphones/
  docs/
    01-librepods-salvage-assessment.md
    02-developer-earbuds-architecture-roadmap.md
    03-hardware-target-selection.md
    04-openclaw-jarvis-skill-contract.md
    05-security-and-permission-model.md
  protocol/
    firmware-events.md
    bridge-api.md
    approval-policy.md
  bridge-desktop/
    daemon/
    stt/
    tts/
    transport/
    cli/
  bridge-android/
    app/
  firmware/
    target-open-earbuds/
  openclaw-skill-jarvis-earbuds/
  simulation/
    fake-earbud-events/
      send-event.ts
      events/
        triple_tap_right.json
        left_long_press.json
        approve_right_double_tap.json
        reject_left_double_tap.json
        both_hold_cancel.json
        remove_one_bud_pause.json
        remove_both_buds_end_session.json
        put_both_in_resume.json
```

Desktop first is the right default for this product because the primary workflow is repo and tool control. Android support can follow after the bridge contract is stable.

## How LibrePods Fits Into This Plan

Use LibrePods in four ways:

1. As a protocol-research reference for how to document and parse earbud control surfaces.
2. As a host-service architecture reference for scanner, parser, state store, and command repository separation.
3. As a desktop bridge reference for local command surfaces such as a daemon and CLI.
4. As a warning about what not to anchor on: vendor-specific workarounds, hardcoded device assumptions, and app-first product shaping.

Do not use LibrePods as your firmware base.

## Delivery Roadmap

### Phase 0: Freeze constraints

Exit criteria:

- licensing posture chosen
- first hardware target chosen
- desktop-first or mobile-first decision made

### Phase 1: Define contracts

Exit criteria:

- firmware event schema written
- bridge API written
- approval rules written
- spoken response format written

### Phase 2: Simulated bridge MVP

Exit criteria:

- CLI can inject fake gestures
- bridge can open listen/respond cycle
- OpenClaw can answer a fixed set of developer commands

### Phase 3: OpenClaw developer loop

Exit criteria:

- branch status works
- diff summary works
- test command works
- CI failure summary works
- output is short enough for spoken playback

### Phase 4: Hardware integration

Exit criteria:

- target earbuds emit real events into the bridge
- wear state and battery events are reliable
- reconnect behavior is acceptable

### Phase 5: Approval and policy hardening

Exit criteria:

- explicit approval gestures work
- cancel path is reliable
- destructive commands are blocked without approval
- logs record every action request and decision

### Phase 6: Product hardening

Exit criteria:

- OTA and recovery story defined
- latency budget measured
- failure modes documented
- second hardware target decision made

## Main Risks

| Risk | Why it matters | Mitigation |
| --- | --- | --- |
| Wrong first hardware target | Can waste months on signed firmware or unrecoverable devices | Start with open-firmware-capable earbuds only |
| License contamination | Can constrain commercialization or distribution later | Use clean-room rewrites unless GPL reuse is intentional |
| Voice latency | Breaks the Jarvis experience | Keep spoken responses short and local routing simple in v1 |
| Gesture ambiguity | Can trigger wrong actions | Require approval gestures for anything meaningful |
| Bridge fragility | Bluetooth and audio pipelines fail in edge cases | Build a strong simulator before hardware integration |
| Over-scoping | Too many features delay first proof | Ship wake, ask, answer, approve first |

## Recommended Next Build Step

Do not start with firmware.

Start by building the desktop bridge and OpenClaw skill with simulated earbud events. Once the agent loop feels useful from the keyboard, connect it to real gesture input from the chosen hardware target.