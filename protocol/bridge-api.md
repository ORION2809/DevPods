# Bridge API

This file documents the current local bridge HTTP surface.

## Base Address

The local bridge binds to loopback by default:

`http://127.0.0.1:4545`

For Android relay or other LAN-based clients, the host is now configurable at startup. Example:

```bash
npm run cli -- start --host 0.0.0.0 --relay-token relay-secret
```

When `--relay-token` or `JARVIS_RELAY_TOKEN` is set, clients must send:

```text
Authorization: Bearer <token>
```

## Endpoints

### `GET /health`

Health probe.

Response:

If relay auth is enabled, `GET /health` also requires the bearer token.

### `POST /events`

Accepts a normalized firmware event and returns a voice-optimized Jarvis response.

Request body: see [firmware-events.md](firmware-events.md)

Example request:

```json
{
  "source": "developer_earbuds_simulator",
  "sessionId": "sim-session",
  "workspace": "current_repo",
  "device": "left_bud",
  "event": "left_long_press",
  "timestamp": 1710000000,
  "battery": 71,
  "wearState": "in_ear",
  "profile": "coding_mode"
}
```

Android relay example request:

```json
{
  "source": "android_relay",
  "sessionId": "android_sess_001",
  "workspace": "current_repo",
  "device": "both_buds",
  "event": "android_push_to_talk",
  "timestamp": 1710000000,
  "utterance": "open file app.ts",
  "profile": "default"
}
```

Example response:

```json
{
  "speak": "Workspace ready. Git repository not initialized. No tests running.",
  "display": "Workspace firmware_earphones is allowlisted, but no git repository is initialized.",
  "requiresApproval": false,
  "approvalRequest": null,
  "actionId": null,
  "status": "completed",
  "nextState": "idle",
  "followUpHint": null
}
```

## Current Behavior

- request bodies are size-limited
- malformed JSON is rejected
- event payloads are validated through shared schemas
- responses are always voice-first and short
- audit records are written locally
- the same event-handling core is available directly through the local CLI without HTTP
- long-running actions can emit follow-up spoken notifications after the initial response
- local action intents currently include quick status, diff summary, CI lookup, commit-message generation, staged commit execution, branch push, deploy, test runs, workspace file opening, explicit file delete, and explicit file revert
- latest_ci_failure now performs a read-only GitHub Actions lookup when the workspace origin remote resolves to GitHub and the workflow-run data is accessible
- deploy uses the configured workspace command and reports completion asynchronously, matching the existing run_tests notification model
- `android_relay` requests reuse the same approval and cancel lifecycle through relay-native events such as `android_push_to_talk`, `android_status_shortcut`, `android_approve`, `android_reject`, and `android_cancel`
- desktop notifier output is suppressed for `android_relay` sessions, so Android becomes the only spoken sink for those flows
