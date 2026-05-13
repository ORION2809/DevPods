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

The pairing endpoints (`GET /pairing` and `POST /pairing/verify`) are intentionally readable without the relay bearer token so a new phone can fetch or open the pairing contract before it is configured locally. Only expose them on a trusted LAN.

## Endpoints

### `GET /pairing`

Bootstrap endpoint for first-time Android relay pairing.

Behavior:

- returns `409` when the bridge does not have a safe pairing base URL
- returns JSON when the request prefers `application/json`
- returns a small HTML page by default so a phone browser can open `devpods://pair`
- generates a new 6-character pairing code if none exists or the current one has expired

JSON response shape:

```json
{
  "bridgeBaseUrl": "http://192.168.1.10:4545",
  "pairingCode": "A1B2C3",
  "workspace": "current_repo",
  "pairingUri": "devpods://pair?bridgeBaseUrl=http%3A%2F%2F192.168.1.10%3A4545&workspace=current_repo",
  "pairingPageUrl": "http://192.168.1.10:4545/pairing"
}
```

Start the bridge with a LAN-reachable pairing base URL when the bind host itself is not safe to advertise:

```bash
npm run cli -- start --host 0.0.0.0 --relay-token relay-secret --pairing-base-url http://192.168.1.10:4545
```

### `POST /pairing/verify`

Exchanges a pairing code for the relay token.

Request body:

```json
{
  "pairingCode": "A1B2C3"
}
```

Response on success (`200`):

```json
{
  "relayToken": "relay-secret"
}
```

Response on invalid or expired code (`401`):

```json
{
  "error": "Invalid or expired pairing code"
}
```

Response when no relay token is configured (`200`):

```json
{
  "relayToken": ""
}
```

Pairing codes are one-time use and automatically expire after 5 minutes.

### `POST /pairing/regenerate`

Forces rotation of the current pairing code. Returns the active (or newly generated) code and its expiry timestamp.

Response (`200`):

```json
{
  "pairingCode": "X7Y8Z9",
  "expiresAt": 1710000300000
}
```

Returns `null` for both fields if no relay token is configured.

### `GET /health`

Health probe.

If relay auth is enabled, `GET /health` also requires the bearer token.

Response (`200`):

```json
{
  "ok": true,
  "bridgeVersion": "1.0.0",
  "protocolVersion": 1,
  "minAppVersion": "1.0.0",
  "features": [
    "pairing_code",
    "health_check",
    "event_routing",
    "approval_gates",
    "autonomy",
    "openclaw_rewrite"
  ],
  "brainMode": "openclaw",
  "openclawTransport": "http",
  "openclawRewritePolicy": "adaptive",
  "openclawRewriteHealth": {
    "connectionState": "not_applicable",
    "lastConnectionError": null,
    "foregroundBudgetMs": null,
    "backgroundBudgetMs": null,
    "counters": {
      "rewritten": 0,
      "skipped": 0,
      "timedOut": 0,
      "failed": 0
    },
    "lastOutcome": null
  },
  "openclawReady": true,
  "degraded": false
}
```

Health response fields:

| Field | Type | Description |
|-------|------|-------------|
| `ok` | `boolean` | Always `true` when the bridge is responding. |
| `bridgeVersion` | `string` | Semantic version of the bridge runtime. |
| `protocolVersion` | `number` | Incremented when the wire format changes incompatibly. |
| `minAppVersion` | `string` | Minimum Android relay app version required to use all features. |
| `features` | `string[]` | Capability flags advertised to the client. |
| `brainMode` | `"local" \| "openclaw"` | Current brain mode. |
| `openclawTransport` | `"http" \| "local-cli" \| "gateway-client" \| null` | Active OpenClaw transport, or `null` when not in `openclaw` mode. |
| `openclawRewritePolicy` | `"always" \| "adaptive" \| null` | Current rewrite policy, or `null` when not in `openclaw` mode. |
| `openclawRewriteHealth` | `object \| null` | Transport-specific health snapshot, or `null` when not in `openclaw` mode. |
| `openclawReady` | `boolean` | `true` when `brainMode` is `openclaw` and OpenClaw options are present. |
| `degraded` | `boolean` | `true` when the bridge has entered degraded mode due to consecutive failures. |

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
- the bridge can now publish a browser-usable pairing page and JSON pairing payload through `GET /pairing`
- local action intents currently include quick status, diff summary, CI lookup, commit-message generation, staged commit execution, branch push, deploy, test runs, workspace file opening, explicit file delete, and explicit file revert
- latest_ci_failure now performs a read-only GitHub Actions lookup when the workspace origin remote resolves to GitHub and the workflow-run data is accessible
- deploy uses the configured workspace command and reports completion asynchronously, matching the existing run_tests notification model
- `android_relay` requests reuse the same approval and cancel lifecycle through relay-native events such as `android_push_to_talk`, `android_status_shortcut`, `android_approve`, `android_reject`, and `android_cancel`
- desktop notifier output is suppressed for `android_relay` sessions, so Android becomes the only spoken sink for those flows
