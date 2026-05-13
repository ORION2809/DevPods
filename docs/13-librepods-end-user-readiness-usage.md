# LibrePods Usage Plan For End-User Product Readiness

This document maps how LibrePods should be used across the complete end-user product readiness ladder from [11-end-user-product-readiness-audit.md](11-end-user-product-readiness-audit.md). It is not limited to the P0 execution plan.

## Short Verdict

LibrePods should become DevPods/OpenClaw's high-fidelity earbud intelligence layer for compatible devices, not the whole product and not the only input path.

The right architecture is:

```text
Generic Android media-button path
Assistant-entry fallback path
LibrePods AirPods/AACP/BLE path
Future custom firmware BLE path
        |
        v
Normalized earbud signal provider interface
        |
        v
Android relay state machine
        |
        v
Bridge API / OpenClaw runtime
```

With company/licensing approval in place, LibrePods can be used more directly than the earlier salvage assessment assumed. The important constraint is no longer "reference only"; it is "reuse inside a clean provider boundary so the product does not become AirPods-only."

## Updated Premise

The earlier [01-librepods-salvage-assessment.md](01-librepods-salvage-assessment.md) was conservative because it treated LibrePods as a third-party GPL/AGPL risk. If LibrePods is owned or cleared by the company, the usable surface changes materially.

Current posture:

| Area | Previous posture | New posture |
| --- | --- | --- |
| Protocol knowledge | Reference only | Can reuse or adapt directly |
| Android BLE/AACP implementation | Study patterns | Can wrap or port into provider module |
| UI components | Reference UX ideas | Can selectively reuse if design/product fit |
| Device matrix | Manual docs | Can derive from LibrePods capability model plus field testing |
| Production dependency | Avoid by default | Allowed, but must stay isolated behind adapters |

The product still needs a generic Android relay path because the end-user promise is "use earbuds to control OpenClaw," not "use AirPods only." LibrePods gives us a much stronger compatible-device path, especially for AirPods-class hardware.

## What LibrePods Gives Us

LibrePods already contains several pieces that are directly relevant to end-user readiness:

| LibrePods area | Relevant source | Product value |
| --- | --- | --- |
| AACP protocol manager | [`AACPManager.kt`](../librepods/android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt) | Parses device/control packets, stem presses, ear detection, battery and device information |
| BLE scanner/status tracker | [`BLEManager.kt`](../librepods/android/app/src/main/java/me/kavishdevar/librepods/bluetooth/BLEManager.kt) | Detects nearby/paired AirPods status, battery, in-ear state, lid state, connection state |
| Android service lifecycle | [`AirPodsService.kt`](../librepods/android/app/src/main/java/me/kavishdevar/librepods/services/AirPodsService.kt) | Shows how to run long-lived device monitoring on Android |
| Command repository | [`ControlCommandRepository.kt`](../librepods/android/app/src/main/java/me/kavishdevar/librepods/data/ControlCommandRepository.kt) | Clean pattern for get/set/observe of device control values |
| Capability model | [`AirPods.kt`](../librepods/android/app/src/main/java/me/kavishdevar/librepods/data/AirPods.kt) | Device model and capability mapping for supported features |
| Control command docs | [`control_commands.md`](../librepods/docs/control_commands.md) | Packet-level reference for AACP control commands |
| Opcode docs | [`opcodes.md`](../librepods/docs/opcodes.md) | Protocol vocabulary for packet parsing and diagnostics |
| Linux packet patterns | [`BasicControlCommand.hpp`](../librepods/linux/BasicControlCommand.hpp), [`airpods_packets.h`](../librepods/linux/airpods_packets.h) | Useful for desktop diagnostics and future test harnesses |
| CLI/server pattern | [`librepods-ctl.cpp`](../librepods/linux/librepods-ctl.cpp) | Pattern for a local control utility or diagnostic relay |
| Product UX references | LibrePods presentation components/screens/widgets | Battery, device info, troubleshooting, overlays, and settings UX patterns |

The most valuable surfaces are not the cosmetic UI pieces. The most valuable surfaces are the protocol parser, BLE state tracking, device capability model, and real-device lifecycle behavior.

## Core Design Principle

LibrePods should be treated as one implementation of an `EarbudSignalProvider`.

It should not define the entire relay contract. The core relay should consume normalized events:

```text
WakeGesture
InterruptGesture
ApproveGesture
RejectGesture
EarStateChanged
BatteryStateChanged
DeviceConnectionChanged
AudioRouteChanged
RawDiagnosticFrame
```

Each event should carry:

```text
provider: android_media_session | assistant_entry | librepods_airpods | custom_firmware_ble
deviceId: redacted stable local identifier
deviceModel: optional user-visible model
capabilityProfile: supported / observed / unsupported
confidence: proven | observed | inferred | unproven
timestamp: relay-local time
rawDebugRef: optional local-only diagnostic reference
```

This lets the product use LibrePods deeply without letting AirPods-specific assumptions leak into OpenClaw, the Bridge API, or the generic Android path.

## End-User Readiness Mapping

### P0.1 Reliable Physical Wake

Problem in the audit:

The product currently cannot claim end-user readiness unless a physical earbud action reliably starts the wake/listen flow.

LibrePods usage:

| Need | LibrePods contribution |
| --- | --- |
| Detect real stem/touch gestures on supported devices | Use AACP `STEM_PRESS` handling from `AACPManager.kt` |
| Distinguish single/double/triple/long press where available | Reuse/adapt `StemPressType` and bud-side mapping |
| Know whether the device is connected enough to trust a gesture | Use `BLEManager.kt` connection and last-seen state |
| Compare OS media-button path with device-native path | Emit both as normalized events with provider labels |
| Build wake reliability data | Log gesture source, gesture type, latency, and whether STT started |

Recommended product behavior:

| Device condition | Wake strategy |
| --- | --- |
| LibrePods-compatible device with proven stem events | Prefer native LibrePods gesture events |
| Generic Bluetooth earbuds with media key support | Use Android `MediaSession` media-button path |
| Earbuds only invoke phone assistant | Use assistant-entry fallback path |
| No reliable physical input | Mark device unsupported for hands-free wake |

This gives us a clean way to avoid overclaiming. The app can say: "This device supports physical wake through native earbud events," or "This device supports wake only through Android media controls," or "This device does not support reliable wake."

### P0.2 Reliable Listen-After-Wake STT

Problem in the audit:

Wake detection alone is not enough. The system must prove that after the wake event, speech is captured from the intended microphone and converted into text.

LibrePods usage:

| Need | LibrePods contribution |
| --- | --- |
| Know if earbuds are in-ear before starting STT | Use BLE in-ear state when available |
| Know if case/lid state makes capture unlikely | Use BLE lid/open/device status |
| Know if device is connected/recently seen | Use BLE connection and stale-device cleanup |
| Know model/capabilities before claiming support | Use `AirPods.kt` capability profile |
| Investigate microphone mode or audio route problems | Use AACP control state where available, plus Android audio route |

Recommended product behavior:

Before starting STT, the relay should compute a `ListenReadiness` state:

```text
ready:
  device connected
  at least one bud in-ear if ear state is available
  Android audio route usable
  previous wake source is supported

degraded:
  wake detected but mic route not confirmed
  device status unavailable
  using phone mic fallback

blocked:
  device disconnected
  buds out of ear
  lid/case state indicates unusable
  STT engine unavailable
```

LibrePods should not replace Android audio routing. It should enrich the relay with enough device facts to prevent false starts and explain failures to the user.

### P0.3 Dependable Interrupt Path

Problem in the audit:

End users need a dependable way to stop, reject, cancel, or interrupt an agent action.

LibrePods usage:

| User action | Potential mapping |
| --- | --- |
| Single press | Wake or pause/resume, depending on active state |
| Double press | Approve or next candidate action |
| Triple press | Reject or cancel |
| Long press | Emergency stop / agent interrupt |
| Remove bud from ear | Pause listening or pause speaking |
| Close case/lid | Hard stop current interaction |

Recommended stateful mapping:

| Relay state | Gesture | Action |
| --- | --- | --- |
| Idle | Supported wake gesture | Start listen |
| Listening | Supported interrupt gesture | Cancel listen |
| Agent proposing action | Approve gesture | Approve action |
| Agent proposing action | Reject gesture | Reject action |
| Agent executing | Long press / hard interrupt | Cancel or interrupt OpenClaw |
| Speaking | Ear removal | Pause TTS if enabled |

LibrePods gives us richer gesture and ear-state signals than generic media buttons, but the product should keep a user-visible mapping and not hide this behind magic.

### P0.4 Product Setup

Problem in the audit:

Users need setup that explains what is supported, what is not, and how to recover.

LibrePods usage:

| Setup need | LibrePods contribution |
| --- | --- |
| Device discovery | BLE scanner/status model |
| Device identification | AirPods model/device info |
| Capability explanation | `AirPods.kt` capability mapping |
| Gesture support testing | AACP stem press and Android media-button comparison |
| Recovery guidance | LibrePods troubleshooting UX patterns |
| Debug export | Packet/event logs from service pattern |

Recommended setup flow:

1. Detect connected Bluetooth audio devices through Android.
2. Run generic media-button probe.
3. If Apple/AirPods-compatible signal appears, run LibrePods capability probe.
4. Show detected capabilities as plain user-facing facts.
5. Ask user to test wake, interrupt, and approve/reject gestures.
6. Save a local device profile with observed capabilities.
7. Use only proven gestures in the default mapping.

Setup should never imply support because a model is theoretically supported. It should claim support only after the app observes the relevant signal on that phone and OS build.

### P0.5 Supported-Device Claim And Matrix

Problem in the audit:

The product must not claim broad support without evidence.

LibrePods usage:

LibrePods can seed the device matrix with known capability profiles, then field testing can mark each capability as observed.

Suggested matrix fields:

| Field | Source |
| --- | --- |
| Device model | LibrePods `AirPods.kt` and Android Bluetooth metadata |
| Android phone model | Android system |
| Android version | Android system |
| Wake via media button | Generic relay probe |
| Wake via native LibrePods event | AACP stem press probe |
| Interrupt gesture | Media/AACP probe |
| In-ear state | BLE/AACP where available |
| Battery state | BLE/AACP where available |
| STT route verified | Relay STT smoke test |
| TTS interruption verified | Relay TTS smoke test |
| Confidence | observed/proven/unproven |

LibrePods should be used to make the compatibility matrix more precise, not more optimistic.

## P1 Product Readiness Usage

### P1.1 Product-Grade State And Feedback UX

Problem in the audit:

Users need to understand whether the relay is idle, listening, thinking, speaking, blocked, or disconnected.

LibrePods usage:

| UX state | Device facts from LibrePods |
| --- | --- |
| Connected | BLE/AACP connection state |
| Ready to listen | connected plus in-ear plus route OK |
| Not ready | disconnected, out of ear, stale, case/lid signal |
| Low confidence | device status unavailable or generic path only |
| Low battery | battery status |
| Unsupported gesture | capability profile says unavailable |

Reusable/reference UI areas:

| LibrePods UI area | DevPods use |
| --- | --- |
| Battery components/widgets | Device readiness and status cards |
| Device info card | Setup and diagnostics page |
| Troubleshooting screen | Recovery UX when wake/listen fails |
| Popup/island overlay ideas | Lightweight "listening/thinking/speaking" state |
| Press-and-hold settings | Gesture mapping settings |

The UX should be DevPods-specific, but the implementation should borrow the proven device-status patterns.

### P1.2 Connection, Pairing, And Recovery UX

Problem in the audit:

A user will blame the app if the earbuds are paired but the relay cannot use them.

LibrePods usage:

| Recovery need | LibrePods contribution |
| --- | --- |
| Detect stale device | BLE last-seen and cleanup logic |
| Detect device disappearance | BLE listener callbacks |
| Detect lid/ear/battery changes | BLE status callbacks |
| Explain why input failed | Map raw state to user-safe reason codes |
| Provide diagnostics | Packet/event log pattern from service |

Recommended product states:

| Internal state | User-facing copy |
| --- | --- |
| `device_disconnected` | "Earbuds are disconnected." |
| `device_seen_no_audio_route` | "Earbuds are connected, but audio input is not routed through them." |
| `gesture_not_observed` | "This tap did not reach Android on this device." |
| `native_signal_available` | "This device supports deeper earbud signals." |
| `low_battery_degraded` | "Earbud battery is low; wake may be unreliable." |

LibrePods helps the product move from mysterious failure to explainable failure.

### P1.3 Real-Device Compatibility Expansion

Problem in the audit:

The product needs evidence across multiple devices, not only one phone and one earbud model.

LibrePods usage:

| Compatibility track | Role of LibrePods |
| --- | --- |
| AirPods-class devices | Primary deep-control provider |
| Generic Bluetooth earbuds | Baseline comparison path |
| Realme Buds / similar Android earbuds | Likely generic/assistant path unless protocol support is added |
| Future custom earbuds | Separate firmware BLE provider |

LibrePods should define one high-confidence lane, not the only lane.

The practical compatibility target becomes:

| Device class | Expected provider |
| --- | --- |
| AirPods / compatible Apple protocol devices | `librepods_airpods` plus generic fallback |
| Basic Bluetooth media earbuds | `android_media_session` |
| Earbuds that only trigger assistant | `assistant_entry` |
| DevPods-owned firmware | `custom_firmware_ble` |

### P1.4 Repeated-Use Conversation Quality

Problem in the audit:

The app must survive repeated wake/listen/respond cycles.

LibrePods usage:

| Repeated-use risk | LibrePods mitigation |
| --- | --- |
| Starting STT when earbuds are removed | Ear-state check |
| Continuing TTS after user removes bud | Ear removal can pause/stop speaking |
| Silent failure after disconnect | Device disappearance callbacks |
| Battery-driven unreliability | Battery state |
| Conflicting device/source state | AACP audio source and connected-device information where available |

LibrePods does not make conversation intelligence agentic by itself. It gives the relay better physical-world context so the agent loop behaves less stupidly.

### P1.5 Clear User Contract

Problem in the audit:

Users need to know what the app can and cannot do with their earbuds.

LibrePods usage:

Capability profiles should drive the user contract:

| Capability status | Product claim |
| --- | --- |
| Proven in setup | Show as enabled |
| Supported by model but not observed | Show as available to test |
| Unsupported | Hide or disable |
| Unknown | Show as generic Bluetooth only |

Example:

```text
Your earbuds support:
- Wake by stem press
- Cancel by long press
- Battery status
- In-ear detection

Not verified on this phone:
- Triple press approval
- Native microphone mode control
```

This avoids the common trap of making protocol support sound like product support.

## P2 Product Readiness Usage

### P2.1 Safe Autonomy Surface

Problem in the audit:

The product needs safe ways for users to approve, reject, pause, and interrupt more capable OpenClaw actions.

LibrePods usage:

| Safety control | Device signal |
| --- | --- |
| User is present | In-ear state |
| User intentionally approves | Explicit gesture |
| User rejects/cancels | Explicit gesture |
| User disengages | Ear removal, disconnect, case/lid |
| User needs emergency stop | Long press or configured gesture |

Recommended safety policy:

| Action risk | Required input |
| --- | --- |
| Low-risk local read-only action | Wake plus voice confirmation may be enough |
| Medium-risk local change | Explicit approval gesture or spoken approval |
| High-risk external action | Phone UI approval required |
| Any running action | Long-press interrupt must be available if provider supports it |

LibrePods can strengthen the approval channel, but it should not be the only approval mechanism for high-risk actions.

### P2.2 Faster OpenClaw Rewrite Paths

Problem in the audit:

OpenClaw itself needs to become faster and more reliable.

LibrePods usage:

LibrePods does not directly rewrite OpenClaw, but it reduces wasted agent cycles by preventing bad input sessions.

Examples:

| OpenClaw failure mode | LibrePods-assisted prevention |
| --- | --- |
| Agent starts with no real user speech | Block STT when device route is not ready |
| User cannot interrupt a long action | Provide hardware interrupt events |
| Agent misreads user absence as silence | Ear-state and disconnect awareness |
| User thinks app ignored them | Relay can explain device signal status |

This is indirect but important. Better hardware context makes the agentic layer easier to trust.

### P2.3 Broader Developer Action Coverage

Problem in the audit:

The product eventually needs more than "ask the agent a question." It needs practical developer workflows.

LibrePods usage:

The hardware layer can become a small command surface:

| Gesture/device state | Developer workflow |
| --- | --- |
| Wake gesture | Start capture for command |
| Double press | Approve proposed patch/test/action |
| Triple press | Reject or ask for alternate plan |
| Long press | Stop current run |
| Ear removal | Pause speech output |
| Reinsert bud | Resume summary or ask next question |

This must stay configurable because users have different earbuds and accessibility needs.

### P2.4 BLE-Native / Firmware Direction

Problem in the audit:

The product may eventually need firmware or BLE-native support for a stronger hardware path.

LibrePods usage:

LibrePods is valuable as a pattern library for protocol work:

| Pattern | LibrePods example |
| --- | --- |
| Packet parser boundary | `AACPManager.kt` |
| Control command abstraction | `ControlCommandRepository.kt` |
| Device capability model | `AirPods.kt` |
| Long-lived Android service | `AirPodsService.kt` |
| Desktop diagnostics | Linux CLI/server pattern |
| BLE status stream | `BLEManager.kt` |

The future custom-firmware provider should mimic the architecture, not the Apple protocol.

Recommended abstraction:

```text
EarbudDeviceProvider
  AndroidMediaSessionProvider
  AssistantEntryProvider
  LibrePodsAirPodsProvider
  CustomFirmwareBleProvider
```

The future firmware path should be protocol-independent but learn from LibrePods' lifecycle, capability, and diagnostics design.

## P3 Product Readiness Usage

### P3.1 Consumer Polish

Problem in the audit:

The product eventually needs to feel reliable and understandable to non-developer users.

LibrePods usage:

| Polish area | LibrePods contribution |
| --- | --- |
| Battery UI | Battery widgets/components |
| Device illustrations | Device model/icon patterns |
| Connection overlay | Popup/island concepts |
| Settings | Press/hold, mic, noise-control setting patterns |
| Troubleshooting | Existing troubleshooting screens |
| Diagnostics | Debug screen and packet logs |

Recommended approach:

Borrow the interaction ideas, not necessarily the exact screens. DevPods has a different product job: agent control, not AirPods management.

### P3.2 Broad Platform Support

Problem in the audit:

The product should not be Android-only forever if the vision expands.

LibrePods usage:

LibrePods can inform Android and Linux support:

| Platform | LibrePods value |
| --- | --- |
| Android | Strongest direct reuse |
| Linux desktop | Useful CLI, packet, and media-control patterns |
| Windows | Less direct, but diagnostics concepts transfer |
| iOS | Not directly useful for app-store constraints |

For cross-platform support, keep the normalized bridge event schema platform-neutral.

### P3.3 Hardware Product Path

Problem in the audit:

If DevPods becomes a hardware product, the team needs evidence for which hardware features matter.

LibrePods usage:

LibrePods can teach what a premium earbud control surface looks like:

| Hardware capability | Product lesson |
| --- | --- |
| Stem press variants | Users benefit from multiple intentional controls |
| Ear detection | Presence matters for safe agent behavior |
| Battery/case state | Hardware state needs to be visible |
| Connected-device info | Multi-device ambiguity hurts reliability |
| Audio source state | Agent input/output depends on route truth |
| Configurable gestures | Defaults are not enough |

The custom hardware spec should be informed by LibrePods, but it should define a simpler DevPods-native BLE protocol purpose-built for agent control.

## Proposed Implementation Shape

### 1. Device Provider Interface

Create a provider boundary in the Android relay:

```kotlin
interface EarbudSignalProvider {
    val providerId: String
    val capabilityProfile: Flow<EarbudCapabilityProfile>
    val deviceState: Flow<EarbudDeviceState>
    val events: Flow<EarbudSignalEvent>

    suspend fun start()
    suspend fun stop()
}
```

Suggested normalized models:

```kotlin
data class EarbudDeviceState(
    val providerId: String,
    val deviceId: String?,
    val displayName: String?,
    val connectionState: ConnectionState,
    val battery: EarbudBatteryState?,
    val earState: EarState?,
    val audioRouteState: AudioRouteState?,
    val capabilityProfile: EarbudCapabilityProfile,
    val confidence: SignalConfidence
)

sealed interface EarbudSignalEvent {
    data class WakeGesture(...) : EarbudSignalEvent
    data class InterruptGesture(...) : EarbudSignalEvent
    data class ApprovalGesture(...) : EarbudSignalEvent
    data class EarStateChanged(...) : EarbudSignalEvent
    data class BatteryChanged(...) : EarbudSignalEvent
    data class ConnectionChanged(...) : EarbudSignalEvent
    data class RawDiagnosticFrame(...) : EarbudSignalEvent
}
```

The exact Kotlin shape can change, but the boundary should be explicit.

### 2. LibrePods Provider

Create `LibrePodsAirPodsProvider` as a wrapper around selected LibrePods components:

| Wrapped component | Provider responsibility |
| --- | --- |
| `BLEManager` | Device discovery, battery, ear state, lid/case state |
| `AACPManager` | Stem press, control commands, protocol packet parsing |
| `ControlCommandRepository` | Controlled get/set/observe of supported settings |
| `AirPods.kt` | Device capability profile |
| `AirPodsService` concepts | Long-running lifecycle, notification/service behavior |

Do not expose LibrePods types directly to the rest of the app. Convert them to DevPods relay models at the provider boundary.

### 3. Capability Matrix Integration

Add a local compatibility record for each tested device:

```json
{
  "deviceModel": "AirPods Pro 2",
  "phoneModel": "Example Android Phone",
  "androidVersion": "15",
  "providersObserved": ["android_media_session", "librepods_airpods"],
  "wakeGesture": "proven",
  "interruptGesture": "proven",
  "approveRejectGesture": "observed",
  "earDetection": "proven",
  "batteryStatus": "proven",
  "sttAfterWake": "proven",
  "notes": "Long press reliable; triple press not observed."
}
```

This can begin as a checked-in Markdown/JSON matrix and later become an in-app diagnostic export.

### 4. Relay State Machine Integration

The relay should consume normalized events, not provider-specific details:

```text
Idle
  WakeGesture -> Listening

Listening
  STT final text -> AgentThinking
  InterruptGesture -> Idle
  DeviceDisconnected -> Blocked

AgentThinking
  ProposalReady -> AwaitingApproval
  InterruptGesture -> Idle

AwaitingApproval
  ApprovalGesture -> Executing
  RejectGesture -> Idle

Executing
  InterruptGesture -> CancelRequested

Speaking
  EarRemoved -> Paused
  InterruptGesture -> Idle
```

LibrePods enriches the transitions, but the state machine remains product-owned.

### 5. Bridge API Integration

The Bridge API should receive optional hardware context:

```json
{
  "source": "android_relay",
  "input": {
    "kind": "voice_transcript",
    "text": "run the tests and tell me what failed"
  },
  "hardwareContext": {
    "provider": "librepods_airpods",
    "wakeSource": "right_stem_double_press",
    "deviceConfidence": "proven",
    "earState": "in_ear",
    "batteryState": "ok"
  }
}
```

OpenClaw should treat this as context, not as authority. Approval policy should still decide which actions require confirmation.

## Direct Reuse Map

| LibrePods asset | Recommended use | Notes |
| --- | --- | --- |
| `AACPManager.kt` | Direct adaptation/wrapping | High value for stem press and control events |
| `BLEManager.kt` | Direct adaptation/wrapping | High value for readiness state |
| `AirPodsService.kt` | Partial reuse of lifecycle concepts | Avoid importing as one large monolith |
| `ControlCommandRepository.kt` | Pattern or direct reuse | Good abstraction for command state |
| `AirPods.kt` | Adapt into DevPods capability profiles | Keep product labels user-safe |
| `BluetoothCryptography.kt` | Use only where required | Keep key handling isolated and reviewed |
| `control_commands.md` | Protocol reference | Useful for tests and diagnostics |
| Linux packet code | Desktop diagnostics/reference | Not core Android runtime |
| Widgets/components | Selective UX reuse | Redesign around DevPods states |
| Troubleshooting/debug screens | Strong reference | Adapt copy and diagnostics to OpenClaw |

## What Not To Do

| Anti-pattern | Why it is wrong |
| --- | --- |
| Make LibrePods the only input path | Breaks generic earbuds and future hardware |
| Expose AACP packet details in Bridge API | Couples OpenClaw to AirPods internals |
| Claim all AirPods features are supported | Product readiness requires observed behavior |
| Depend on root/Xposed as the default user path | Too much friction for end users |
| Import all LibrePods UI wholesale | Different product job and UX hierarchy |
| Treat noise-control/hearing-aid features as core | Interesting later, not essential to OpenClaw control |
| Skip generic media-button testing | Many earbuds will never expose LibrePods-level data |

## Suggested Roadmap

### Phase A: Provider Boundary

Build the normalized `EarbudSignalProvider` interface and implement the existing Android media-button path behind it.

Acceptance:

| Check | Required result |
| --- | --- |
| Generic wake event | Emits normalized `WakeGesture` |
| Provider label | Shows `android_media_session` |
| Relay state machine | Does not care which provider emitted the event |

### Phase B: LibrePods Capability Prototype

Wrap LibrePods BLE/AACP logic behind `LibrePodsAirPodsProvider`.

Acceptance:

| Check | Required result |
| --- | --- |
| Device detection | App can identify supported device profile |
| Battery/ear state | State appears in relay diagnostics |
| Stem press | Emits normalized gesture event if observed |
| Feature flag | Provider can be disabled without breaking generic path |

### Phase C: Setup And Compatibility

Add setup probes that compare generic and LibrePods providers.

Acceptance:

| Check | Required result |
| --- | --- |
| User taps earbud | App records whether event arrived |
| User speaks after wake | App verifies STT route |
| Device matrix | Saved with observed capability confidence |
| Unsupported device | User sees clear limitation, not silent failure |

### Phase D: Interrupt And Approval

Map supported gestures to OpenClaw approval policy.

Acceptance:

| Check | Required result |
| --- | --- |
| Long press during execution | Sends interrupt/cancel |
| Approval gesture | Only accepted in approval state |
| Reject gesture | Rejects proposed action |
| Ear removal | Pauses/cancels according to configured policy |

### Phase E: Product UX

Use LibrePods-derived device facts in the app UI.

Acceptance:

| Check | Required result |
| --- | --- |
| Main status | Shows ready/listening/thinking/speaking/blocked |
| Device card | Shows model, battery, connection, provider confidence |
| Troubleshooting | Explains wake/STT failures |
| Diagnostics export | Includes redacted event log |

## Acceptance Criteria For "Using LibrePods Enough"

We are using LibrePods enough when all of these are true:

| Criterion | Meaning |
| --- | --- |
| Deep provider exists | LibrePods powers a real runtime provider, not just docs |
| Generic path still works | App works without LibrePods-compatible hardware |
| Capability matrix is evidence-based | Claims are based on observed phone/earbud behavior |
| STT readiness uses device facts | Ear/connection/battery/route affect listen decisions |
| Interrupt path uses hardware events | Supported gestures can stop/cancel/approve actions |
| UI explains device state | Users can see why something is ready or blocked |
| Bridge remains hardware-neutral | OpenClaw receives optional context, not AACP internals |

We are not using LibrePods enough if it remains only a reference folder. We are using it too much if AirPods protocol details become the product architecture.

## Key Risks

| Risk | Mitigation |
| --- | --- |
| Android permission/root/vendor constraints | Keep LibrePods provider optional and capability-probed |
| Battery drain from scanning | Use foreground-service discipline, scan modes, and backoff |
| Overclaiming support | Store observed/proven capability status per device |
| App complexity | Keep provider boundary strict |
| Privacy leakage | Redact MAC/device identifiers in logs and Bridge payloads |
| Fragile protocol behavior | Add device-specific compatibility tests and diagnostics |
| User confusion | Surface plain-language readiness states |

## Final Recommendation

LibrePods should be promoted from "reference material" to "controlled implementation source" for the compatible-device lane.

The product should use LibrePods most heavily in:

1. Native earbud signal detection.
2. Device readiness state.
3. Capability and compatibility matrix.
4. Setup/testing diagnostics.
5. Interrupt and approval gestures.
6. Future firmware/provider architecture patterns.

It should not replace:

1. Generic Android media-button support.
2. Assistant-entry fallback.
3. Bridge API hardware neutrality.
4. OpenClaw approval policy.
5. Future DevPods-native firmware protocol.

In plain terms: LibrePods is the strongest shortcut we have for making the Android relay feel like a real earbud product, but only if we wrap it as a provider and let the rest of DevPods stay device-agnostic.
