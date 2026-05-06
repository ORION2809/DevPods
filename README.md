<p align="center">
	<img src="https://img.shields.io/badge/TypeScript-5.9-3178C6?style=for-the-badge&logo=typescript&logoColor=white" alt="TypeScript 5.9" />
	<img src="https://img.shields.io/badge/Node.js-Local_Runtime-5FA04E?style=for-the-badge&logo=node.js&logoColor=white" alt="Node.js runtime" />
	<img src="https://img.shields.io/badge/OpenClaw-Integrated-111827?style=for-the-badge" alt="OpenClaw integrated" />
	<img src="https://img.shields.io/badge/Android-Relay_MVP-34A853?style=for-the-badge&logo=android&logoColor=white" alt="Android relay MVP" />
	<img src="https://img.shields.io/badge/Vitest-92_tests-729B1B?style=for-the-badge&logo=vitest&logoColor=white" alt="92 tests" />
</p>

# DevPods

DevPods turns earbuds into a developer control surface.

The system captures earbud-style events, routes them through a local bridge with explicit policy and approval rules, optionally rewrites spoken responses with OpenClaw, and returns short, ear-safe responses back to the user. Today that loop is proven through a desktop-first bridge and an Android software relay MVP.

> [!IMPORTANT]
> Current status: DevPods is a validated software loop, not a finished hardware product. The current primary product surface is DevPods Relay on Android, backed by DevPods Bridge on desktop. Real BLE transport, custom firmware, and broader real-earbud Android validation are the next stages.

> [!NOTE]
> The primary CLI name is `devpods`. The legacy `jarvis-earbuds` binary is retained as a compatibility alias while internal filenames catch up.

## What DevPods Is

DevPods is not "an AI inside earbuds." The earbuds are the control surface.

The product model is:

```text
Earbud gesture or Android relay event
	-> local bridge runtime
	-> workspace policy and approval gates
	-> developer action or status lookup
	-> optional OpenClaw rewrite
	-> short spoken response back to the user
```

That gives you a hands-free interface for developer tasks like quick repo status, CI summaries, approval-gated actions, and short spoken updates without giving away local execution safety.

## Primary Validation Path

The current product path is:

```text
Bluetooth earbuds
	-> DevPods Relay on Android
	-> DevPods Bridge on desktop
	-> repo, CI, editor, and policy-backed actions
	-> short spoken response back to the earbuds
```

The desktop simulator and CLI still matter, but they now support the Android-first product path rather than define it.

## Shipped Today

### Core bridge and runtime

- Local HTTP bridge with `GET /health` and `POST /events`
- Shared bridge runtime used by both server and one-shot CLI commands
- Deny-by-default workspace allowlist and approval model
- Session state, audit logging, and output redaction
- Local Jarvis runtime for repo status, diff, CI, file open, test runs, commit messaging, push, deploy, delete, and revert flows

### OpenClaw integration

- `local`, `http`, and `gateway-client` runtime modes
- Adaptive rewrite policy with foreground and background budgets
- Health reporting for rewrite policy, gateway state, and readiness
- Real sandboxed and managed OpenClaw validation surfaces

### Android relay MVP

- Foreground relay service
- Compose product-demo UI with readiness, approval, and hardware-verification cards
- Speech recognition and TTS adapters
- Headset/media-session wake path support
- Explicit approval, reject, and cancel actions
- Installed-app emulator validation script for the debug APK

### Validation

- `npm run typecheck`
- `npm test`
- `npm run build`
- `android-relay\\gradlew.bat assembleDebug`
- `android-relay\\gradlew.bat assembleRelease`
- `simulation\\android-relay\\validate-installed-app.ps1`

## Key Capabilities

| Area | Delivered state |
| --- | --- |
| Safety model | Deny-by-default intents, approval-required actions, hard-approval actions, explicit workspace config |
| Developer actions | Quick status, diff summary, CI failure lookup, run tests, open file, commit message, commit staged, push, deploy, delete, revert |
| Background work | Queueing by workspace, deferred notifications, queued cancellation, truthful running-task cancellation responses |
| OpenClaw | Rewrite-only integration boundary with `http`, `local-cli`, and `gateway-client` transports |
| Android | Debuggable relay app with validated emulator-installed flow and service-side automation path |
| Observability | Health endpoint, audit log, rewrite metadata, action ids, and relay service logs |

## Architecture

```text
Simulator / Android Relay / Future Earbud Transport
	-> Bridge Runtime
	-> Event Router
	-> Policy + Session Store + Audit Log
	-> Local Jarvis Runtime
	-> Optional OpenClaw Rewrite Layer
	-> Console / JSON / Android TTS Response
```

### Layer ownership

| Layer | Responsibility |
| --- | --- |
| Event source | Simulated earbuds today, Android relay today, real firmware/BLE later |
| Bridge | Validation, session state, health, audit logging, routing |
| Policy | Allowlists, approval classes, workspace boundaries, redaction |
| Jarvis runtime | Intent resolution and actual local dev-tool actions |
| OpenClaw | Response rewriting only, never policy or execution authority |
| Android relay | Capture wake flows, send events, play spoken responses |

## Quick Start

### 1. Install

```bash
npm install
```

### 2. Start the bridge

```bash
npm run devpods -- start --port 4545
```

Or run the compiled CLI directly:

```bash
npm run build
node dist/src/cli/jarvis-earbuds.js start --port 4545
```

### 3. Send a simulated event

Quick status:

```bash
npm run send:event -- left_long_press
```

Voice command:

```bash
npm run say -- "summarize my current diff"
```

Local one-shot execution without the HTTP server:

```bash
npm run cli -- local left_long_press
```

## OpenClaw Modes

| Mode | Purpose |
| --- | --- |
| `local` | Built-in Jarvis runtime only |
| `openclaw` + `http` | Fast deterministic integration and mock validation |
| `openclaw` + `local-cli` | Simplest real full OpenClaw execution baseline |
| `openclaw` + `gateway-client` | Resident OpenClaw connection for real gateway contract validation |

Examples:

```bash
npm run cli -- start --brain openclaw --openclaw-transport http --openclaw-base-url http://127.0.0.1:8080 --openclaw-token dev-token
```

```bash
npm run cli -- start --brain openclaw --openclaw-transport local-cli --openclaw-model openai/mock-rewrite-model --openclaw-config-path ./runtime-data/openclaw.json --openclaw-state-dir ./runtime-data/openclaw-state --openclaw-workspace-dir .
```

```bash
npm run cli -- start --brain openclaw --openclaw-transport gateway-client --openclaw-base-url http://127.0.0.1:8080 --openclaw-model openai/mock-rewrite-model --openclaw-agent-id jarvis_rewrite --openclaw-token dev-token
```

The shipped default is adaptive rewriting with a `250 ms` foreground budget and a `750 ms` background budget so slow rewrites fall back to the local reply instead of blocking ear-level interactions.

## Android Relay

The repository includes a working Android software relay prototype under [android-relay/README.md](android-relay/README.md).

What is validated today:

- debug and release builds from the checked-in Gradle wrapper
- installed debug APK on the Android emulator
- relay start and explicit health check
- quick status shortcut
- synthetic headset wake event path
- product-state UI for `Ready`, `Listening`, `Thinking`, `Speaking`, `Approval required`, and `Attention needed`
- hardware-verification UI showing whether the last wake came from a physical media button, push-to-talk, or debug injection
- approval prompt for `open file docs/vision.md`
- cancel, second prompt, approve, and stop
- explicit `pendingActionId` forwarding and stale-state cleanup checks

Run the Android relay smoke harness against the bridge:

```bash
npm run relay:smoke
```

Run the installed-app emulator validation harness:

```powershell
.\simulation\android-relay\validate-installed-app.ps1
```

Debug builds allow the automation hooks used by that harness. Treat those hooks as test-only surfaces on emulator or private developer devices.

For laptop-side signal evidence on Windows, the repo now includes:

```powershell
.\simulation\windows-relay\verify-media-buttons.ps1 -DurationSeconds 30
```

Manual verification on the connected laptop has already observed a real `MEDIA_PLAY_PAUSE` event from the paired earbuds. That proves the earbuds can emit at least one standard Windows media-key signal, but it does not replace the remaining Android hardware validation path.

## Safety Model

DevPods is intentionally conservative.

- Every workspace action is allowlisted in `config/workspaces.json`
- Approval-required and hard-approval actions are explicit
- The bridge does not expose arbitrary shell execution from spoken text
- Destructive file operations are workspace-bounded
- OpenClaw is a rewrite layer, not the source of truth for permissions or execution
- Audit records and redaction are part of the normal runtime path

## Validation Snapshot

The final repo-wide verification reran successfully with:

```bash
npm run typecheck
npm test
npm run build
```

Android verification also passed with:

```powershell
cd android-relay
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
cd ..
.\simulation\android-relay\validate-installed-app.ps1
```

The current automated test suite covers 15 test files and 95 tests.

## Repository Layout

| Path | Purpose |
| --- | --- |
| `src/protocol` | Shared schemas and intent types |
| `src/policy` | Allowlist, approvals, redaction, workspace boundaries |
| `src/bridge` | HTTP bridge, runtime, event router, session store, audit log |
| `src/jarvis` | Intent routing, local runtime, background scheduling |
| `src/openclaw` | OpenClaw transports, sandboxing, validation, health reporting |
| `simulation/fake-earbud-events` | Local event fixtures and CLI helpers |
| `simulation/android-relay` | Android relay validation scripts and smoke harness |
| `android-relay` | Android software relay MVP |
| `docs` | Architecture, security, operations, implementation, and Android phase docs |
| `protocol` | Human-readable protocol references |

## Clean-Room Note

LibrePods was used as a local architecture and protocol reference during discovery. The shipped DevPods code in this repository remains a clean-room implementation. The local `librepods/` clone is treated as a reference workspace and is excluded from the repo content prepared for publication.

## Documentation Map

- [android-relay/README.md](android-relay/README.md): DevPods Relay setup, UI model, and validation entry points
- [docs/09-android-software-relay-mvp.md](docs/09-android-software-relay-mvp.md): Android relay architecture, current evidence, and manual hardware-verification path
- [docs/android_relay_vision.md](docs/android_relay_vision.md): Android-first product framing
- [docs/06-openclaw-runtime-operations.md](docs/06-openclaw-runtime-operations.md): runtime flags, env vars, and OpenClaw transport modes
- [docs/07-implementation-summary.md](docs/07-implementation-summary.md): detailed implementation state and engineering summary
- [docs/08-acceptance-criteria-status.md](docs/08-acceptance-criteria-status.md): acceptance-criteria tracking
- [docs/vision.md](docs/vision.md): original product framing and firmware-first architecture direction

## What Is Next

The next highest-value work is not more simulator polish. It is moving the validated software loop onto real hardware surfaces:

1. Add a real BLE or firmware-backed transport adapter behind the existing event contract.
2. Validate the Android relay on real headset hardware across multiple device classes.
3. Add production-grade STT and TTS paths.
4. Continue improving the low-latency full-fidelity OpenClaw path when budgets are disabled.
5. Preserve the current safety model while widening transport and hardware support.

DevPods already proves the critical systems problem: safe event intake, workspace-bounded action routing, approval gating, concise spoken response shaping, and optional OpenClaw integration. That is the foundation the hardware layer can now build on.

