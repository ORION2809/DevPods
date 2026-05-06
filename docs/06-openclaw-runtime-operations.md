# OpenClaw Runtime Operations

This document describes the supported OpenClaw runtime surfaces for the Jarvis earbuds bridge.

## Supported Modes

The bridge supports two brain modes:

| Brain mode | Purpose |
| --- | --- |
| `local` | Use only the built-in Jarvis runtime |
| `openclaw` | Keep local intent and policy handling, then rewrite voice output through OpenClaw |

OpenClaw mode supports three transports:

| Transport | Status | Use it for |
| --- | --- | --- |
| `http` | supported and fast | deterministic mocks, gateway-compatible integration tests, local development against an existing endpoint |
| `local-cli` | supported and validated with real OpenClaw | the real local OpenClaw execution path for this repository |
| `gateway-client` | supported and validated with the real sandbox gateway | a resident real OpenClaw path that avoids per-request CLI spawning, but is still slower than the local-cli baseline in current smoke measurements |

Important scope note:

- OpenClaw currently rewrites completed or running spoken responses.
- The CLI/runtime surface now defaults to `adaptive` rewriting with a `250 ms` foreground budget and a `750 ms` background budget.
- Short local replies and most short hint-carrying replies now stay on the fast local path; slower OpenClaw rewrites time out back to the local response instead of blocking the spoken UX.
- Approval prompts stay local and do not go through OpenClaw before approval.
- Policy, intent routing, and action gating still live in the local Jarvis runtime.

## CLI Flags

Shared runtime flags:

| Flag | Meaning |
| --- | --- |
| `--brain` | `local` or `openclaw` |
| `--workspaces-config` | path to the workspace allowlist JSON |
| `--openclaw-transport` | `http`, `local-cli`, or `gateway-client` |
| `--openclaw-rewrite-policy` | `adaptive` or `always` |
| `--openclaw-timeout-ms` | rewrite timeout budget |
| `--openclaw-foreground-budget-ms` | max synchronous wait for foreground rewrites, defaults to `250`, accepts `off` |
| `--openclaw-background-budget-ms` | max synchronous wait for background rewrites, defaults to `750`, accepts `off` |

HTTP transport flags:

| Flag | Meaning |
| --- | --- |
| `--openclaw-base-url` | base URL for the OpenClaw-compatible endpoint |
| `--openclaw-token` | optional bearer token |
| `--openclaw-model` | optional model override, defaults to `openclaw/default` |

Gateway-client transport flags:

| Flag | Meaning |
| --- | --- |
| `--openclaw-base-url` | OpenClaw gateway base URL |
| `--openclaw-token` | optional bearer token |
| `--openclaw-model` | model passed to the gateway agent run |
| `--openclaw-agent-id` | OpenClaw agent ID used for the resident rewrite session |

Local CLI transport flags:

| Flag | Meaning |
| --- | --- |
| `--openclaw-model` | provider/model reference such as `openai/mock-rewrite-model` |
| `--openclaw-config-path` | OpenClaw config JSON path |
| `--openclaw-state-dir` | OpenClaw state directory |
| `--openclaw-workspace-dir` | workspace directory handed to OpenClaw |
| `--openclaw-provider-plugin-ids` | comma-separated provider plugin IDs when explicit plugin scoping is needed |

## Environment Variables

CLI flags take precedence over environment variables.

| Environment variable | Meaning |
| --- | --- |
| `JARVIS_BRAIN_MODE` | default brain mode |
| `JARVIS_WORKSPACES_CONFIG` | default workspace allowlist path |
| `JARVIS_OPENCLAW_TRANSPORT` | default transport |
| `JARVIS_OPENCLAW_REWRITE_POLICY` | default rewrite policy |
| `JARVIS_OPENCLAW_BASE_URL` | default HTTP base URL |
| `JARVIS_OPENCLAW_TOKEN` | default HTTP bearer token |
| `JARVIS_OPENCLAW_MODEL` | default model |
| `JARVIS_OPENCLAW_AGENT_ID` | default gateway-client agent ID |
| `JARVIS_OPENCLAW_CONFIG_PATH` | default local CLI config path |
| `JARVIS_OPENCLAW_STATE_DIR` | default local CLI state directory |
| `JARVIS_OPENCLAW_WORKSPACE_DIR` | default local CLI workspace directory |
| `JARVIS_OPENCLAW_PROVIDER_PLUGIN_IDS` | default comma-separated provider plugins |
| `JARVIS_OPENCLAW_TIMEOUT_MS` | default timeout budget |
| `JARVIS_OPENCLAW_FOREGROUND_BUDGET_MS` | default foreground rewrite budget, or `off` |
| `JARVIS_OPENCLAW_BACKGROUND_BUDGET_MS` | default background rewrite budget, or `off` |

## Startup Examples

Start the bridge in local mode:

```bash
npm run cli -- start
```

Start the bridge in OpenClaw HTTP mode:

```bash
npm run cli -- start --brain openclaw --openclaw-transport http --openclaw-base-url http://127.0.0.1:8080 --openclaw-token dev-token
```

Start the bridge in real local OpenClaw mode:

```bash
npm run cli -- start --brain openclaw --openclaw-transport local-cli --openclaw-model openai/mock-rewrite-model --openclaw-config-path ./runtime-data/openclaw.json --openclaw-state-dir ./runtime-data/openclaw-state --openclaw-workspace-dir .
```

Start the bridge in resident gateway-client mode:

```bash
npm run cli -- start --brain openclaw --openclaw-transport gateway-client --openclaw-base-url http://127.0.0.1:8080 --openclaw-model openai/mock-rewrite-model --openclaw-agent-id jarvis_rewrite --openclaw-token dev-token
```

Force a one-shot local quick-status request through OpenClaw:

```bash
npm run cli -- local left_long_press --brain openclaw --openclaw-transport http --openclaw-base-url http://127.0.0.1:8080 --openclaw-rewrite-policy always
```

Force the same request through the resident gateway-client transport:

```bash
npm run cli -- local left_long_press --brain openclaw --openclaw-transport gateway-client --openclaw-base-url http://127.0.0.1:8080 --openclaw-model openai/mock-rewrite-model --openclaw-agent-id jarvis_rewrite --openclaw-rewrite-policy always
```

## Health Endpoint

The bridge exposes `GET /health`.

Example response in OpenClaw mode:

```json
{
  "ok": true,
  "brainMode": "openclaw",
  "openclawTransport": "http",
  "openclawRewritePolicy": "adaptive",
  "openclawRewriteHealth": {
    "connectionState": "connected",
    "lastConnectionError": null,
    "foregroundBudgetMs": 250,
    "backgroundBudgetMs": 750,
    "counters": {
      "rewritten": 0,
      "skipped": 0,
      "timedOut": 0,
      "failed": 0
    },
    "lastOutcome": null
  },
  "openclawReady": true
}
```

`openclawTransport` now reports `http`, `local-cli`, or `gateway-client`, `openclawRewritePolicy` reports the effective `adaptive` or `always` mode, and `openclawRewriteHealth` reports the gateway-client connection state, the active budgets, plus recent rewrite outcomes.

Example CLI query:

```bash
npm run cli -- health --port 4545
```

## Validation Baseline

Fast runtime checks:

```bash
npm run openclaw:latency:smoke
JARVIS_OPENCLAW_TRANSPORT=gateway-client npm run openclaw:latency:smoke
npx vitest run test/cli-runtime-options.test.ts
npx vitest run test/cli-openclaw-http.test.ts
npx vitest run test/openclaw-mode.test.ts -t "resident OpenClaw gateway-client transport"
```

Real OpenClaw baseline:

```bash
npm run openclaw:smoke
npm run openclaw:managed:smoke
JARVIS_OPENCLAW_TRANSPORT=gateway-client npm run openclaw:managed:smoke
npx vitest run test/openclaw-managed-mode.test.ts
npx vitest run test/openclaw-mode.test.ts
```

Full repo validation:

```bash
npm test
npm run typecheck
npm run build
```

## Operational Notes

- `local-cli` remains the simplest real OpenClaw execution path when you want the CLI itself to own each rewrite invocation.
- `adaptive` plus the default `250/750 ms` budgets is now the shipped low-latency mode, so common short responses and slow rewrite attempts both stay off the critical ear path.
- `gateway-client` keeps a resident worker-backed gateway connection alive and avoids the Vitest ESM-loader issue. It is still slower than `local-cli` for forced full rewrites, but the shipped budgeted path now returns in comparable low hundreds of milliseconds on both transports.
- Use `--openclaw-rewrite-policy always --openclaw-foreground-budget-ms off --openclaw-background-budget-ms off` when you want validation, smoke runs, or manual probes to force every eligible response through OpenClaw and wait for the full rewrite.
- `npm run openclaw:managed:smoke` validates the same end-to-end bridge workflow against an actual OpenClaw gateway outside the sandbox; the default transport is `local-cli`, and `JARVIS_OPENCLAW_TRANSPORT=gateway-client` switches it to the resident gateway-client path.
- The latest shipped latency smokes measured `quickStatus: 195 ms`, `approvalPrompt: 16 ms`, `approvalStart: 14 ms`, and `completionWait: 252 ms` for sandbox `local-cli`, versus `quickStatus: 204 ms`, `approvalPrompt: 17 ms`, `approvalStart: 24 ms`, and `completionWait: 266 ms` for sandbox `gateway-client`.
- The forced full-rewrite baselines are still much slower: the latest managed smoke runs measured `quickStatus: 31301 ms` and `approvalStart: 25029 ms` for `local-cli`, versus `quickStatus: 50802 ms` and `approvalStart: 47245 ms` for `gateway-client`.
- HTTP mode is still useful for deterministic local integration tests and gateway-compatible development.
- If `--brain openclaw` is selected, the CLI now fails early on incomplete transport configuration.