# Changelog

## [Unreleased] - Product Readiness Pass

### Bridge (TypeScript)

- **Fixed** OpenClaw `gateway-client` transport to dynamically discover the internal client module, making it resilient to openclaw package file renames.
- **Added** centralized error classification (`src/bridge/error-handler.ts`) with user-friendly messages and retryability flags.
- **Added** request logging to `server.ts` (method, URL, status, duration).
- **Added** per-session rate limiting for `/events` (100 req/min).
- **Added** circuit breaker in runtime: enters degraded mode after 5 consecutive failures, auto-recovers after 30s.
- **Added** graceful error handling in `handleEvent` — all errors return a user-friendly `JarvisResponse`.
- **Added** audit log resilience: rotation at 10MB (max 5 backups), fallback to stderr on write failure.
- **Added** `degraded` field to health payload.
- **Fixed** pairing code system with 5-minute expiry, one-time use, and `/pairing/regenerate` endpoint.
- **Pinned** `@mistralai/mistralai` to `2.2.1` (pre-compromise version) via npm override.

### Android Relay

- **Added** `normalizeProviderEventToBridgeTrigger()` in `RelayGestureRouting.kt` — single source of truth for mapping provider gestures to schema-valid bridge events.
- **Fixed** `RelayService.handleSignalEvent` to use normalized triggers for Wake, Interrupt, and Approval gestures.
- **Fixed** `interruptImplementationAndListen()` to accept and propagate `RelayWakeSignal` hardware context.
- **Fixed** setup `testWake` to be truly event-driven: no longer calls `wakeAndListen()` first; waits for a real physical provider event.
- **Fixed** setup `testStt` to require BOTH a transcript AND a physical wake during the test window.
- **Added** first-time onboarding flow (`OnboardingScreen.kt`, `UserOnboarding.kt`).
- **Added** user-facing error messages in `RelayStateStore` for common failures (bridge unreachable, pairing expired, STT unavailable, mic denied, no headset).
- **Added** setup wizard error retry UI with actionable guidance.
- **Added** bridge event queue with exponential backoff retry when bridge is unreachable.
- **Added** STT error fallback notification.
- **Added** headset disconnect pause/resume during listening sessions.
- **Fixed** AACP stem-press parser to read type from `data[6]` and bud side from `data[7]`.
- **Fixed** Android lint errors (MediaSessionService `super.onStartCommand`, API-33 URLDecoder, Media3 opt-in, camera uses-feature).

### Documentation

- **Added** `SECURITY.md` with dependency audit analysis, false-positive explanation for `@mistralai/mistralai`, and reachability matrix.
- **Added** `.github/workflows/ci.yml` for GitHub Actions CI (bridge + Android + Windows package).
- **Updated** `docs/supported-devices-matrix.json` with validated capability statuses.
- **Updated** `README.md` with accurate test counts, validation commands, and current status.
- **Updated** `android-relay/README.md` to reference `SignalProviderRegistry` / `AndroidMediaSessionProvider`.

### Protocol

- **Fixed** `HardwareContext` type exported from `src/protocol/schemas.ts`.
- **Fixed** all `auditLog.append()` calls to include `hardwareContext: null` for background records.

### Security

- **Documented** `GHSA-3q49-cfcf-g5fm` false positive: installed `@mistralai/mistralai@2.2.1` predates the May 11 compromise by 3 weeks.
- **Added** npm override to pin `@mistralai/mistralai` to `2.2.1`.
- **Added** diagnostic export redaction for URLs, tokens, and workspace names.

## Verification

| Check | Status |
|---|---|
| `npm run build` | ✅ Pass |
| `npm test` | ✅ 123 tests pass |
| `npm audit` | ⚠️ 5 critical (upstream false positive, see SECURITY.md) |
| Android `assembleDebug` | ✅ Pass |
| Android `lintDebug` | ✅ Pass (0 errors) |
| Android `testDebugUnitTest` | ✅ Pass |
