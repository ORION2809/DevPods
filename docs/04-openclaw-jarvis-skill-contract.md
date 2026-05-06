# OpenClaw Jarvis Skill Contract

## Purpose

Define the Jarvis and OpenClaw skill as a voice-first product module that turns earbud events into safe developer actions and returns short spoken replies.

Core rule:

OpenClaw can think in detail. Jarvis must speak briefly.

## Module Responsibilities

The skill is responsible for:

- accepting normalized bridge requests
- maintaining session state for the current interaction
- resolving intent against workspace context
- classifying requested work against the incoming risk policy
- triggering approval flows for risky actions
- returning concise spoken output plus optional display text
- emitting action IDs for auditability and follow-up approval events

The skill is not responsible for:

- BLE transport and pairing
- STT or TTS implementation
- firmware update logic
- desktop UI rendering
- raw tool execution policy outside the approved contract

## Voice Rule

The spoken channel is the primary UX surface.

Rules:

- `speak` is always the primary output
- target `speak` length is one short sentence
- ideal spoken length is 8 to 16 words
- hard maximum is 24 words
- put detail in `display`, not in `speak`
- lead with the result, then the next step if needed
- for long tasks, acknowledge once and stay quiet until something useful changes
- for approvals, say the action and the required gesture

## Request Schema

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `source` | string | yes | Usually `developer_earbuds` or `bridge_desktop` |
| `sessionId` | string | yes | Stable across one live interaction |
| `workspace` | string | yes | Current repo or selected workspace |
| `event` | string | yes | `wake_and_listen`, `voice_command`, `quick_status`, `approval_action`, `cancel`, `pause`, `resume` |
| `utterance` | string or null | no | Final transcript for voice commands |
| `gesture` | string or null | no | Example: `triple_tap_right` |
| `riskPolicy` | object | yes | Policy input controlling approvals |
| `pendingActionId` | string or null | no | Present for approval follow-up |
| `approvalAction` | string or null | no | `approve`, `reject`, `cancel`, `expire` |
| `deviceState` | object | no | Wear state, battery, active bud, profile |

### Canonical Request JSON

```json
{
  "source": "developer_earbuds",
  "sessionId": "sess_123",
  "workspace": "current_repo",
  "event": "voice_command",
  "utterance": "summarize my current diff",
  "gesture": "triple_tap_right",
  "riskPolicy": {
    "profile": "default",
    "allowReadOnly": true,
    "allowSafeWithoutApproval": true,
    "requireApprovalFor": ["commit", "push", "open_file"],
    "requireHardApprovalFor": ["delete", "deploy", "revert"],
    "approvalTimeoutMs": 12000
  },
  "pendingActionId": null,
  "approvalAction": null,
  "deviceState": {
    "activeBud": "right",
    "wearState": "in_ear",
    "batteryPercent": 74,
    "profile": "coding_mode"
  }
}
```

## Response Schema

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `speak` | string | yes | Short voice response |
| `display` | string or null | no | Longer desktop text |
| `requiresApproval` | boolean | yes | Whether the next step is blocked on approval |
| `approvalRequest` | object or null | no | Present when approval is required |
| `actionId` | string or null | no | Action or task identifier |
| `status` | string | yes | `acknowledged`, `running`, `completed`, `blocked`, `cancelled`, `error` |
| `nextState` | string | yes | `idle`, `listening`, `thinking`, `approval_pending`, `running`, `paused` |
| `followUpHint` | string or null | no | Optional short UI hint, not spoken |

### Canonical Response JSON

```json
{
  "speak": "Four files changed. Main work is in the RAG preview flow.",
  "display": "Four files changed in the current repo. Most edits are in the RAG preview flow.",
  "requiresApproval": false,
  "approvalRequest": null,
  "actionId": null,
  "status": "completed",
  "nextState": "idle",
  "followUpHint": null
}
```

## Risk Policy Input

The skill treats `riskPolicy` as a first-class input, not a side note.

```json
{
  "profile": "default",
  "allowReadOnly": true,
  "allowSafeWithoutApproval": true,
  "requireApprovalFor": ["commit", "push", "open_file"],
  "requireHardApprovalFor": ["delete", "deploy", "revert"],
  "approvalTimeoutMs": 12000
}
```

Policy behavior:

- read-only actions can run immediately
- safe actions can run without approval if explicitly allowed
- approval-required actions must return `requiresApproval: true`
- hard-approval actions must never run on transcript confidence alone
- if approval times out, the request expires and the session returns to `idle`

## Approval Actions

| `approvalAction` | Meaning | Recommended gesture |
| --- | --- | --- |
| `approve` | Execute the pending action | `double_tap_right` |
| `reject` | Decline the pending action | `double_tap_left` |
| `cancel` | Stop a pending or running action | `both_hold` |
| `expire` | No response within timeout window | none |

### Approval Request JSON

```json
{
  "actionType": "commit",
  "summary": "Commit staged files with generated message",
  "riskClass": "approval_required",
  "expiresInMs": 12000
}
```

## Session State Transitions

| From | Event | To | Rule |
| --- | --- | --- | --- |
| `idle` | `wake_and_listen` | `listening` | Open a short listen window |
| `listening` | final transcript | `thinking` | Intent resolution starts |
| `thinking` | quick answer ready | `responding` | Short answer only |
| `thinking` | long task started | `running` | Acknowledge once |
| `thinking` | approval needed | `approval_pending` | Block execution |
| `approval_pending` | `approve` | `running` | Execute pending action |
| `approval_pending` | `reject` or `expire` | `idle` | Speak short refusal or expiry |
| `running` | task complete | `responding` | Return result summary |
| `running` | `cancel` | `cancelled` | Stop work, then return to `idle` |
| `responding` | speech finished | `idle` | Session closes cleanly |
| `idle` or `running` | `pause` | `paused` | Ear removed or assistant paused |
| `paused` | `resume` | `idle` | Ready for the next wake event |

## Output Patterns

### Quick status

```json
{
  "speak": "Feature audio bridge. Four files changed. No tests running.",
  "display": "Branch: feature/audio-bridge. Four files changed. No tests are running.",
  "requiresApproval": false,
  "approvalRequest": null,
  "actionId": null,
  "status": "completed",
  "nextState": "idle",
  "followUpHint": null
}
```

### Long-running task acknowledgment

```json
{
  "speak": "Running tests. I will notify you when they finish.",
  "display": "Starting the workspace test suite now.",
  "requiresApproval": false,
  "approvalRequest": null,
  "actionId": "task_902",
  "status": "running",
  "nextState": "running",
  "followUpHint": "Background task started"
}
```

### Approval-required action

```json
{
  "speak": "Commit staged files? Right double tap to approve.",
  "display": "Commit staged files with the generated message.",
  "requiresApproval": true,
  "approvalRequest": {
    "actionType": "commit",
    "summary": "Commit staged files with generated message",
    "riskClass": "approval_required",
    "expiresInMs": 12000
  },
  "actionId": "act_123",
  "status": "blocked",
  "nextState": "approval_pending",
  "followUpHint": "Right double tap approve, left double tap reject"
}
```

### Blocked by policy

```json
{
  "speak": "That action is blocked in this workspace.",
  "display": "Policy denied the request because deploy is not allowed from the current workspace.",
  "requiresApproval": false,
  "approvalRequest": null,
  "actionId": null,
  "status": "blocked",
  "nextState": "idle",
  "followUpHint": null
}
```

## Design Consequences

This contract forces the product into the right shape:

- the bridge owns transport, capture, playback, and policy enforcement
- the skill owns intent, context, and concise spoken summaries
- approvals are explicit, time-bounded, and auditable
- long tasks acknowledge fast and report only when useful

That is the right contract for a Jarvis-style developer interface.