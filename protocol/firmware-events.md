# Firmware Events Contract

This file describes the current firmware-to-bridge event contract used by the simulator and intended for the future BLE adapter.

## Event Shape

```json
{
  "source": "developer_earbuds_simulator",
  "sessionId": "sim-session",
  "workspace": "current_repo",
  "device": "right_bud",
  "event": "triple_tap_right",
  "timestamp": 1710000000,
  "battery": 74,
  "wearState": "in_ear",
  "profile": "coding_mode",
  "utterance": "summarize my current diff"
}
```

## Required Fields

| Field | Meaning |
| --- | --- |
| `source` | Event origin, currently the simulator or the Android relay |
| `sessionId` | Interaction session identifier |
| `workspace` | Target workspace identifier |
| `device` | `left_bud`, `right_bud`, or `both_buds` |
| `event` | Gesture or wear-state event name |
| `timestamp` | Unix-style event timestamp in milliseconds |

## Optional Fields

| Field | Meaning |
| --- | --- |
| `battery` | Battery percentage for the relevant bud or event source |
| `wearState` | `in_ear`, `out_of_ear`, or `unknown` |
| `profile` | Active device profile such as `coding_mode` |
| `utterance` | Simulated or Android-captured transcript for voice-command flows |
| `pendingActionId` | Optional reference for explicit approval events |

## Event Names

| Event | Meaning |
| --- | --- |
| `triple_tap_right` | Wake Jarvis or send a voice command if `utterance` is present |
| `left_long_press` | Request current developer status |
| `approve_right_double_tap` | Approve the pending action |
| `reject_left_double_tap` | Reject the pending action |
| `both_hold_cancel` | Emergency cancel |
| `remove_one_bud_pause` | Pause listening |
| `remove_both_buds_end_session` | End the active session |
| `put_both_in_resume` | Resume passive updates |
| `headset_button_single` | Android headset or media-button wake trigger |
| `android_push_to_talk` | Android manual push-to-talk wake and transcript event |
| `android_status_shortcut` | Android quick-status shortcut |
| `android_approve` | Android approval action |
| `android_reject` | Android rejection action |
| `android_cancel` | Android cancel action |

## Rules

- gesture names are strict and single-purpose
- `both_hold_cancel` is the physical kill switch
- `utterance` is optional and used by both the simulator and Android STT relay flows
- the bridge owns the translation from device events into OpenClaw-facing requests
