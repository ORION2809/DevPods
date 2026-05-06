# DevPods Implementation Summary

## Purpose

This document is the engineering-side summary of what DevPods currently ships, how the runtime is structured, what has been validated, and what remains intentionally out of scope.

It is the accurate implementation record for the repository as it exists today, not a speculative roadmap document.

Use the related docs for upstream context:

- [vision.md](vision.md) for the original product thesis
- [android_relay_vision.md](android_relay_vision.md) for the Android relay phase framing
- [01-librepods-salvage-assessment.md](01-librepods-salvage-assessment.md) for the clean-room reuse position
- [06-openclaw-runtime-operations.md](06-openclaw-runtime-operations.md) for runtime flags and transport setup
- [09-android-software-relay-mvp.md](09-android-software-relay-mvp.md) for Android relay architecture and validation scope

## Naming Note

The product-facing repository name is DevPods.

The primary CLI name is `devpods`. The legacy `jarvis-earbuds` binary remains available as a compatibility alias while internal file names and historical test fixtures catch up.

## Executive Summary

DevPods now delivers a validated software control loop for developer-first earbuds interaction:

```text
event source
  -> bridge runtime
  -> policy and approval gates
  -> local developer action or status lookup
  -> optional OpenClaw rewrite
  -> short spoken response
```

That loop is currently proven through:

- simulator and CLI event sources
- a local bridge HTTP server
- a deny-by-default workspace policy layer
- explicit approval and hard-approval flows
- local developer-oriented actions
- real OpenClaw integration through multiple transports
- an Android software relay MVP with installed-app emulator validation

This repository is no longer just a source scaffold. It has been validated through repo-wide typecheck, tests, build, Android debug and release builds, and a full installed-app emulator sweep.

## What Is Implemented

### Core bridge runtime

- `GET /health` and `POST /events`
- shared runtime reused by the HTTP server and one-shot CLI commands
- event validation, session tracking, audit logging, and redaction
- explicit workspace registry loading from `config/workspaces.json`

### Policy and action model

- deny-by-default intent enforcement
- approval-required actions
- hard-approval actions
- workspace-bounded destructive file operations
- explicit command declarations for background work

### Local developer actions

- quick status
- diff summary
- latest public GitHub Actions failure lookup
- run tests
- open file
- create commit message
- commit staged files
- push current branch
- deploy through configured command
- delete explicit file
- revert explicit tracked file

### Background task orchestration

- workspace-scoped queueing
- deferred start notifications
- queued cancellation
- truthful running-task cancel responses
- completion notifications for long-running work

### OpenClaw integration

- `http` transport for deterministic integration tests
- `local-cli` transport for real local full OpenClaw execution
- `gateway-client` transport for resident real gateway validation
- adaptive rewrite policy with foreground and background budgets
- health metadata for transport, readiness, and gateway state

### Android relay MVP

- foreground relay service
- Compose product-demo UI with readiness, route, approval, and hardware-verification state
- Android speech recognition adapter
- Android TTS adapter
- Bluetooth communication-device routing
- activity-driven Android 15-safe automation path for debug builds
- explicit approval, reject, cancel, and stop flows
- installed-app emulator harness with action-id lifecycle checks
- Windows laptop media-button probe for non-Android signal evidence

## What Is Not Implemented

- real BLE transport into the bridge
- custom earbud firmware integration
- broad real-hardware validation across multiple headset classes
- production-grade STT/TTS provider strategy beyond the current Android and Windows paths
- authenticated CI writes or broader cloud-side action surfaces
- low-latency force-full-rewrite OpenClaw behavior when operators disable budgets and insist on rewriting every eligible response

## Clean-Room Boundary

LibrePods informed the discovery and salvage analysis, but DevPods remains a clean-room implementation.

The local `librepods/` clone is treated as a reference workspace, not as shipped DevPods code. The publish boundary excludes it.

## System Architecture

```text
Simulator / Android Relay / Future BLE or Firmware Transport
  -> Bridge Runtime
  -> Event Router
  -> Session Store + Audit Log + Policy Engine
  -> Local Jarvis Runtime
  -> Optional OpenClaw Rewrite Layer
  -> Console / JSON / Android TTS Response
```

### Layer ownership

| Layer | Responsibility |
| --- | --- |
| Event source | Simulator today, Android relay today, real earbuds later |
| Bridge | Health, routing, state, validation, response coordination |
| Policy | Allowlists, approval classes, workspace safety, redaction |
| Local runtime | Developer actions and repo-facing side effects |
| OpenClaw | Response rewriting only |
| Android relay | Wake/listen/send/respond surfaces on device |

The important architectural rule is unchanged: OpenClaw is not the authority for permissions or tool execution. The bridge remains the source of truth for safety-critical decisions.

## Runtime Model

### Brain modes

| Brain mode | Meaning |
| --- | --- |
| `local` | Local Jarvis runtime only |
| `openclaw` | Local Jarvis runtime plus OpenClaw rewrite layer |

### OpenClaw transports

| Transport | Current role |
| --- | --- |
| `http` | Fast deterministic integration surface |
| `local-cli` | Simplest real full OpenClaw execution baseline |
| `gateway-client` | Resident real gateway connection path |

### Shipped OpenClaw behavior

The shipped runtime now defaults to:

- adaptive rewrite policy
- `250 ms` foreground budget
- `750 ms` background budget
- local fallback when the rewrite budget expires

That keeps the ear-facing path responsive while still allowing OpenClaw to improve spoken output whenever the latency budget permits.

## Android Relay Final State

The Android relay implementation now includes the following verified behavior:

- `MainActivity` runs as `singleTop` for repeated automation delivery
- follow-up debug actions are handled through `onNewIntent`
- automation extras are ignored outside debuggable builds
- approval actions honor explicit `pendingActionId` values
- local pending approval state is cleared after terminal actions and stop
- cancel remains available for queued or running work without incorrectly re-enabling approve or reject
- `STOP_RELAY` is validated through service-side confirmation and service disappearance checks
- the UI exposes a hardware-verification card that distinguishes physical media-button wake, manual push-to-talk, and debug injection
- the UI exposes speech-recognition readiness, TTS readiness, communication-route visibility, and a speaker self-test

### Installed-app emulator coverage

The checked-in script `simulation/android-relay/validate-installed-app.ps1` validates the debug APK with these steps:

- relay start
- explicit health check
- quick status shortcut
- synthetic headset wake event path
- approval prompt for `open file docs/vision.md`
- cancel with explicit `pendingActionId`
- second approval prompt with a fresh `actionId`
- approve with explicit `pendingActionId`
- negative checks proving no stale pending approval remains after cancel or approve
- relay stop with service shutdown confirmation

This emulator coverage validates the service-side wake path through a synthetic `headset_button_single` event. It does not yet prove a physical media-button press from real headset hardware.

### Current signal evidence

- emulator-installed validation proves the Android service-side wake and approval lifecycle paths
- manual Windows probe validation on the connected laptop observed a real `MEDIA_PLAY_PAUSE` event from the paired earbuds

That means one physical earbud media-key path is now proven on Windows, but the decisive Android hardware validation still needs to be captured on a real device.

## Safety Model

The implementation keeps safety local and explicit.

Current enforcement includes:

- deny-by-default intents
- approval-required and hard-approval classes
- workspace-bounded file resolution
- command allowlists for background work
- output redaction before display or logging
- audit logging for allowed, blocked, queued, cancelled, and completed actions

The bridge does not expose arbitrary shell execution from spoken text.

## Repository Surfaces

### Primary runtime files

- `src/bridge/server.ts`
- `src/bridge/runtime.ts`
- `src/bridge/event-router.ts`
- `src/bridge/session-store.ts`
- `src/jarvis/runtime.ts`
- `src/jarvis/background-command-scheduler.ts`
- `src/openclaw/client.ts`
- `src/openclaw/sandbox.ts`

### Android relay files

- `android-relay/app/src/main/java/com/openclaw/relay/MainActivity.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayViewModel.kt`
- `android-relay/app/src/main/java/com/openclaw/relay/RelayStateStore.kt`
- `simulation/android-relay/validate-installed-app.ps1`

### Core package surfaces

- `npm run dev:bridge`
- `npm run cli`
- `npm run send:event`
- `npm run say`
- `npm run relay:smoke`
- `npm run openclaw:smoke`
- `npm run openclaw:latency:smoke`
- `npm run openclaw:managed:smoke`

## Validation Snapshot

### Final closeout validations rerun successfully

```bash
npm run typecheck
npm test
npm run build
npm run openclaw:smoke
```

```powershell
cd android-relay
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
cd ..
.\simulation\android-relay\validate-installed-app.ps1
```

### Current automated coverage

- 15 test files
- 95 tests

The final closeout also included a focused revalidation of the process adapter timeout path after tightening Windows timeout completion semantics so background queues do not advance before timed-out process trees are actually gone.

## Operational Notes

- Non-loopback bridge binding requires a relay token.
- Android debug automation is intentionally a debug-only surface.
- Debug Android builds allow local cleartext HTTP for emulator and trusted-LAN relay use.
- Release Android builds do not carry that cleartext allowance.
- The `http` OpenClaw transport remains the fastest deterministic test surface.
- The `local-cli` and `gateway-client` transports remain the real rewrite paths.

## Current Limitations

### Hardware and product gaps

- no real BLE or firmware transport
- no real earbud battery, wear, or profile telemetry feeding the bridge
- no real hardware media-button compatibility matrix yet

### Runtime gaps

- no multi-workspace active session model per bridge instance
- no richer operator control plane beyond the current health and audit surfaces
- no universally fast force-full-rewrite OpenClaw mode when rewrite budgets are disabled

### Validation gaps

- no real-device Android headset matrix
- no physical-earbud button validation across multiple OEMs
- emulator validation covers the synthetic wake path, not the full hardware button stack

## What Is Ready To Build On

The important reusable pieces are already stable:

- the event and response contract
- the workspace safety and approval model
- the bridge runtime and session model
- the local developer-action surface
- the OpenClaw rewrite boundary
- the Android relay scaffolding and validation harnesses
- the background queue and notification model

That means the next implementation phase can focus on real transport and hardware surfaces instead of redesigning the safety, routing, or response model.

## Recommended Next Steps

1. Add a real BLE or firmware-backed transport adapter behind the current event contract.
2. Validate the Android relay on real earbuds across multiple headset classes.
3. Add production-grade STT and TTS pathways for non-debug deployments.
4. Continue improving the force-full-rewrite OpenClaw path for low-latency operation without relying on fallback budgets.
5. Preserve the current approval and workspace-safety model while widening hardware support.

## Closing Summary

DevPods now has a real, validated software foundation.

It already solves the hard systems questions that needed to come first:

- how events enter the system
- how local workspace safety is enforced
- how approval and hard-approval flows behave
- how spoken responses are shaped for an ear-level interface
- how OpenClaw can be integrated without taking over policy or execution authority

What remains is the transport and hardware layer. The core runtime, Android relay, validation harnesses, and safety model are ready to build on.