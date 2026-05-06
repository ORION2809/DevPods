Phase vision

The Android phone becomes the software relay.

Any Bluetooth earbuds
        ↓
Android Relay App
        ↓
Jarvis Bridge / OpenClaw Runtime
        ↓
Developer tools
        ↓
Spoken response back to earbuds

The objective is not to prove custom firmware yet. The objective is to prove:

Existing earbuds + Android app can trigger OpenClaw,
capture voice,
route commands,
receive useful developer updates,
and speak back through the earbuds.

This directly builds on your current software baseline, which already proves the fake event flow:

fake earbud event
→ bridge runtime
→ policy and approval
→ Jarvis response
→ optional OpenClaw rewrite

Your implementation summary confirms that this simulator-first software loop already exists, while Bluetooth transport, real earbud firmware, Android/mobile bridge, and primary STT/TTS integration are still not implemented.

Strategic shift

Earlier, the product thesis was:

Programmable earbud firmware → bridge → OpenClaw

For the next phase, the thesis should become:

Generic earbuds → Android relay → bridge → OpenClaw

This is a big deal. It changes your risk profile.

Track	Benefit	Risk
Firmware track	Deep control, custom gestures	Hardware-specific, slow, flashing/recovery risk
Android relay track	Works with normal earbuds, fast MVP	Limited to events Android can observe
Microcontroller relay	Brand-agnostic hardware product	Bluetooth audio proxy complexity

The Android relay is the best next move because it proves the user experience before you invest in hardware.

What the Android Relay should own

The Android Relay app should own only four things:

1. Earbud trigger capture
2. Mic/listening session
3. Network call to Jarvis bridge
4. TTS/audio response through earbuds

It should not own developer intelligence, repo actions, command policy, or OpenClaw orchestration. Your current architecture already says the bridge owns event ingestion/STT/TTS/routing/policy checks, while OpenClaw owns developer context, repo/CI inspection, command selection, and concise spoken summaries.

In the Android-first relay version, I would slightly adjust that:

Android Relay:
  capture media/assistant button events
  capture mic audio
  send transcript or audio to bridge
  play TTS response

Desktop Bridge:
  session state
  policy enforcement
  OpenClaw routing
  workspace context
  developer actions
MVP architecture
┌──────────────────────────────┐
│ Bluetooth Earbuds             │
│ mic, speaker, media controls  │
└───────────────┬──────────────┘
                │ Bluetooth
                ▼
┌──────────────────────────────┐
│ Android Relay App             │
│ Media buttons, STT, TTS       │
└───────────────┬──────────────┘
                │ HTTP/WebSocket over LAN
                ▼
┌──────────────────────────────┐
│ Desktop Jarvis Bridge         │
│ policy, session, OpenClaw     │
└───────────────┬──────────────┘
                │
                ▼
┌──────────────────────────────┐
│ Developer Tool Layer          │
│ git, tests, CI, VS Code       │
└──────────────────────────────┘
Why Android first is the right next phase

Android gives you the fastest path to the real product feel because it can receive Bluetooth headset/media-button interactions and route audio. Android MediaSession is designed to receive playback commands from external sources, including headset buttons, and Media3 sessions can handle media button events from Bluetooth headset controls.

Android also gives you built-in speech and TTS APIs. SpeechRecognizer is Android’s speech-recognition API, and TextToSpeech is the platform TTS API; Android’s newer docs also describe SpeechRecognizer and TTS as built-in capabilities, with RECORD_AUDIO permission required for speech recognition.

For routing the headset audio path during a communication-style session, Android’s audio routing docs point to setCommunicationDevice() for selecting the communication device in voice/video-call-like use cases, replacing older Bluetooth SCO-style APIs.

The Android Relay MVP should prove 5 things
1. Earbud trigger works

The app should detect at least one reliable trigger:

Bluetooth headset button
media play/pause
voice assistant trigger
volume key fallback
on-screen push-to-talk fallback

Do not demand triple tap in v1. Any reliable trigger is enough.

Success:

Earbud gesture → Android app receives event → bridge receives wake event
2. Mic capture works

The user speaks through the earbuds or phone mic.

Success:

User says: "summarize my current diff"
Android captures/transcribes it
Bridge receives utterance
3. Bridge routing works

Android sends your existing event/request contract to the desktop bridge.

Example:

{
  "source": "android_relay",
  "sessionId": "android_sess_001",
  "workspace": "current_repo",
  "event": "voice_command",
  "utterance": "summarize my current diff",
  "gesture": "headset_button",
  "riskPolicy": {
    "profile": "default"
  },
  "deviceState": {
    "activeBud": "unknown",
    "wearState": "unknown",
    "batteryPercent": null,
    "profile": "relay_mode"
  }
}

This aligns with your OpenClaw skill contract, where requests carry source, sessionId, workspace, event, utterance, gesture, riskPolicy, and optional deviceState.

4. Response is short and ear-safe

The bridge returns:

{
  "speak": "Four files changed. Main work is in the RAG preview flow.",
  "requiresApproval": false,
  "status": "completed",
  "nextState": "idle"
}

The Android app speaks only the speak field.

Your skill contract already says spoken output should be one short sentence, ideally 8–16 words, with a 24-word hard maximum.

5. Approval/cancel works

Map available Android/earbud controls like this:

Input	Meaning
Headset button once	wake/listen
On-screen approve	approve
On-screen reject	reject
On-screen cancel	cancel
Volume up/down fallback	approve/reject only in debug mode

Do not overfit to AirPods-style gestures in v1. The relay MVP is about proving the OpenClaw workflow, not perfect gesture semantics.

Your acceptance criteria already say approval and cancel flows are met in the simulator baseline, including pending approvals, queued task cancellation, and cancel behavior.

Phase name

Call this:

Phase 2 — Android Software Relay MVP

Or more productized:

OpenClaw Relay for Android
Product vision statement

Use this:

OpenClaw Relay for Android turns ordinary Bluetooth earbuds into a hands-free developer control surface by capturing headset/media-button events, routing voice commands to the existing Jarvis bridge, and speaking short OpenClaw responses back through the earbuds — without firmware changes or custom hardware.

That is tight and technically honest.

Scope boundaries
In scope
Android app
Bluetooth earbuds paired to Android
headset/media-button wake trigger
push-to-talk fallback
STT from Android
HTTP/WebSocket bridge call
TTS playback through earbuds
approval/reject/cancel UI
session status screen
local network pairing to desktop bridge
Out of scope
firmware modification
custom BLE service
microcontroller proxy
Bluetooth audio proxy
left/right raw tap counting
case battery telemetry
universal gesture support
production-grade mobile security hardening
Android Relay components
1. Relay Service

Background service that owns the session.

RelayService
  - starts/stops listening
  - handles headset/media button events
  - sends bridge requests
  - receives bridge responses
  - triggers TTS
2. Media Button Handler

Captures supported headset/media controls.

MediaButtonHandler
  - play/pause
  - headset hook
  - assistant-like action if available
3. STT Adapter

Starts voice capture after wake.

STTAdapter
  - startListening()
  - stopListening()
  - final transcript
  - confidence/error
4. Bridge Client

Talks to desktop bridge.

BridgeClient
  POST /events
  POST /voice-command
  POST /approval
  GET /health
5. TTS Adapter

Speaks bridge response.

TTSAdapter
  speak(response.speak)
  stop()
6. Debug Console

This is critical for R&D.

Debug screen:
  last headset event
  last transcript
  bridge status
  latest response
  current session state
  latency timings
  approval state
Target user flow
Flow 1 — Wake and ask
User taps earbud / presses headset button
→ Android detects media/headset event
→ Android says or beeps: "Listening"
→ user says: "What branch am I on?"
→ Android transcribes
→ sends to desktop bridge
→ bridge/OpenClaw answers
→ Android speaks response through earbuds
Flow 2 — Status shortcut
User taps shortcut / app button
→ Android sends quick_status
→ bridge checks repo
→ Android speaks: "Feature audio bridge. Four files changed."
Flow 3 — Approval
OpenClaw says: "Commit staged files? Approve in app."
→ Android shows Approve / Reject / Cancel
→ user taps Approve
→ bridge executes approved action
→ Android speaks result

Later, if your earbuds expose more button events, approval can move from screen to earbud gestures.

Success metrics

The phase should be judged by outcomes, not UI polish.

Metric	Target
Wake event detected	> 95% during manual test
Wake → listening start	< 500 ms
Speech capture success	> 90% in quiet room
Bridge roundtrip for quick status	< 1 sec local mode
Spoken response length	≤ 24 words
Approval flow correctness	100%
Cancel correctness	100%
No firmware required	100%

Your architecture latency budget targets sub-4-second total perceived response for simple tasks, with fast acknowledgment for longer operations.

Engineering milestone structure
Milestone A — Android relay shell
- app connects to bridge
- health check works
- manual text command works
- response appears on screen
Milestone B — STT/TTS
- Android records command
- transcript goes to bridge
- response is spoken through phone/earbuds
Milestone C — Earbud trigger
- headset/media button wakes relay
- wake event reaches bridge
- listening starts automatically
Milestone D — Approval loop
- bridge returns approval request
- Android displays approve/reject/cancel
- action ID is preserved
- bridge executes only approved action
Milestone E — Relay acceptance pack
- clone AirPods tested
- cheap earbuds tested
- normal wired headset tested if possible
- event matrix documented
- limitation matrix documented
What changes in your docs

Add a new doc:

09-android-software-relay-mvp.md

Sections:

1. Purpose
2. Vision
3. Non-goals
4. Target architecture
5. Android responsibilities
6. Bridge responsibilities
7. Event contracts
8. User flows
9. API endpoints
10. Acceptance criteria
11. Known Android limitations
12. Hardware compatibility test matrix
13. Exit criteria for firmware phase
Exit criteria before firmware/hardware

Do not return to firmware until Android Relay proves this:

1. At least two different earbuds can trigger wake.
2. Voice command reaches bridge reliably.
3. TTS response plays through earbuds.
4. Approval/cancel works from Android UI.
5. Quick status and diff summary feel useful.
6. Latency is acceptable.
7. The event compatibility matrix is documented.
Final recommendation

Make the next phase about universal compatibility, not firmware control.

The crisp direction:

Android Relay first.
Firmware later.
Hardware proxy much later.

This next phase proves the most important commercial question:

Can ordinary earbuds become an OpenClaw/Jarvis interface purely through software?

That is the highest-leverage validation before spending on custom hardware.