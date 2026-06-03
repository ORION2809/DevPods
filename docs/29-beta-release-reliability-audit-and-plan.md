# Beta Release Reliability Audit And Plan

Generated: 2026-05-20

Audited against:

- [27-production-grade-relay-voice-product-plan.md](27-production-grade-relay-voice-product-plan.md)
- [28-production-grade-relay-voice-implementation-audit.md](28-production-grade-relay-voice-implementation-audit.md)

Audit basis:

- Current repository source, tests, scripts, docs, and local verification commands.
- No live earbud or phone hardware proof run was available during this audit.
- No GitHub branch-protection settings were available from local repository contents.

## Verdict

All edge cases are not resolved yet.

The implementation is much closer than the earlier scaffold phase. The major remediation items from the prior audit are real in code: setup no longer blindly promotes to `COMPLETE_PROVEN`, Home readiness is no longer keyed only to bridge health, bridge idempotency exists server-side, TTS interruption proof requires 5 target hits, and proof-run schema/template docs exist.

However, the app is not ready for beta until the remaining release blockers are cleared:

1. The full Node test suite is failing.
2. The security allowlist audit is failing.
3. No real physical proof-run artifact exists.
4. The proof-run template is not validator-clean.
5. Idempotency is not attached to every Android event that can be retried or execute an action.
6. Home readiness can still overstate "speak now" if the saved proof is stale but the current listen route/device state is blocked.
7. Protocol version handling is documented but not enforced strictly enough.
8. TTS still lacks production audio-focus ownership.
9. Sherpa/Silero remains scaffolded and must stay hidden from beta users unless a real native runtime lands.

## Verification Results

| Gate | Result | Notes |
| --- | --- | --- |
| `npm run typecheck` | Pass | TypeScript typecheck completed. |
| `npm run build` | Pass | TypeScript build completed. |
| `npm test -- bridge-idempotency.test.ts` | Pass | 6/6 idempotency tests passed. |
| `npm test` | Fail | 124/129 tests passed; 5 failed in OpenClaw/gateway health paths. |
| `npm audit --audit-level=moderate` | Pass | 0 vulnerabilities reported. |
| `npm run audit:allowlist` | Fail | Could not determine installed version of `@mistralai/mistralai`; allowlist expects 2.2.1 but package is not installed. |
| `npx tsx scripts/license-scan.ts` | Pass | No forbidden licenses detected. |
| Secret pattern scan | Pass with test fixtures | Matches were redaction code and test placeholder tokens, not real secrets. |
| Android targeted proof tests | Pass | `SetupProofGatingTest` and `HomeScreenReadyStateTest` passed. |
| Android unit suite | Pass | `:app:testDebugUnitTest` passed. |
| Android assemble | Pass | `:app:assembleDebug` passed. |
| Android lint | Pass | `:app:lintDebug` passed. |
| `validate-proof-run.ts docs/proof-run-template.json` | Fail | Template has 1 session and 1 interruption test while status says `passed`. |

## Edge Case Status

### Resolved Or Mostly Resolved

| Edge case | Status | Evidence |
| --- | --- | --- |
| Manual setup completion could force `COMPLETE_PROVEN` | Mostly resolved | `completeSetup()` now checks proof readiness and current capability qualification before choosing Proven vs Degraded. |
| Home could show Ready from bridge health alone | Mostly resolved | Home now also checks `setupPhase == COMPLETE_PROVEN` and no proof blocking failures. |
| Bridge retries could duplicate completed idempotent events | Partially resolved | Server-side `IdempotencyStore` dedupes same-session keys and in-flight duplicate requests. |
| TTS interruption could be proven after one hit | Resolved | `RelayViewModel` now requires `interruptionTargetMetCount >= 5`. |
| Missing proof-run template/schema | Partially resolved | `docs/proof-run-template.json` and `docs/proof-run-schema.json` exist. |
| Diagnostics default redaction | Mostly resolved | Existing tests cover transcript-free and raw-audio-free default behavior. |

### Still Unresolved

| Priority | Edge case | Why it matters | Required fix |
| --- | --- | --- | --- |
| P0 | Full repo tests fail | A beta cannot ship with known failing OpenClaw/bridge tests. | Fix or explicitly gate OpenClaw runtime dependency tests behind installed dependency detection, and update stale health-payload expectations. |
| P0 | Security allowlist audit fails | CI/security gate is red even though `npm audit` is clean. | Remove the stale allowlist exception if the package is no longer installed, or restore the pinned package and make the path/version check deterministic. |
| P0 | No real hardware proof artifact exists | The product still cannot prove the 20-session physical loop on any phone/earbud combo. | Run and check in at least one redacted proof artifact for the first beta target combination. |
| P0 | Idempotency is optional and not emitted by every Android action | Queued/retried approval, status, assistant, transcript, autonomy, and debug events can still execute without replay protection. | Make idempotency keys mandatory for all non-read-only or retryable relay events; reject duplicate unsafe events server-side. |
| P0 | Current "Ready" can ignore current listen readiness | Saved proof can be stale after route loss, device disconnect, permission revocation, or STT/TTS readiness changes. | Home Ready should require `listenReadiness == READY`, `speechRecognitionAvailable`, `ttsReady`, bridge health, and either current route proof or an explicit fallback state. |
| P0 | Proof-run template is invalid under the validator | The canonical template looks like a passed artifact but fails validation. | Change template status to `template` or create a complete 20-session sample under a different example file; make validator skip only explicit templates. |
| P1 | Protocol version is not enforced | Android sends `"1.0"`, bridge health reports `1`, and server accepts any optional event protocol string. | Define one protocol version format, require it in event schema, reject unsupported major versions, and add Android/Node compatibility tests. |
| P1 | TTS does not own Android audio focus | Music, calls, navigation prompts, or other audio can make spoken responses unreliable. | Add `AudioAttributes`, request/abandon audio focus, handle focus loss, and test failure fallback. |
| P1 | OpenClaw gateway dependency handling is brittle | Missing `@mariozechner/pi-agent-core` breaks tests and would break managed/gateway modes. | Make the dependency explicit, vendor/mock it for tests, or disable gateway-client mode unless preflight proves the runtime is installed. |
| P1 | OpenClaw health tests are stale | Health payload gained `queueDepth`, `lastErrorCategory`, and `workspaceRegistryLoaded`, but one test still expects the older exact object. | Update tests to use `toMatchObject` or include the new fields deliberately. |
| P1 | Physical proof validation is too weak for beta | CI skips when no artifacts exist, and inline CI validation duplicates only part of `scripts/validate-proof-run.ts`. | Add a beta/release validation mode that requires at least one artifact and calls the shared validator. |
| P1 | Disconnect/reconnect and process-death proof is not automated | Unit tests cover logic, not real Android lifecycle recovery. | Add manual or instrumented proof steps for disconnect, reconnect, app kill, service restart, and bridge restart. |
| P1 | Sherpa/Silero remains placeholder-only | Offline speech cannot be advertised as reliability improvement. | Keep hidden for beta, or build a real native feature-flagged integration and benchmark it separately. |
| P2 | Diagnostics do not prove support usefulness in the field | Privacy is covered, but support triage quality needs real exports from failed physical runs. | Review 3 failed proof exports and verify each gives one correct repair action. |

## Beta Release Plan

### Phase 1: Make The Repository Green

Goal: no known red local or CI gates before physical beta work starts.

Tasks:

- Fix `npm test` failures:
  - Add/update the missing OpenClaw gateway runtime dependency path or make gateway-client tests skip only when a documented optional dependency is absent.
  - Update health endpoint tests to include `queueDepth`, `lastErrorCategory`, and `workspaceRegistryLoaded`, or use partial matching where exact object equality is not the contract.
  - Stabilize gateway health transition tests so they do not wait for a state the runtime no longer reaches.
- Fix `npm run audit:allowlist`:
  - Remove stale `@mistralai/mistralai` exception if the package is no longer installed.
  - Or make the dependency explicit and pinned if OpenClaw mode truly requires it.
- Make proof template behavior honest:
  - Template should not claim `"status": "passed"` unless it contains complete valid data.
  - Add a separate `docs/proof-run-example.valid.json` only if a full synthetic example is useful.
- Replace duplicated CI proof-run validation with `npx tsx scripts/validate-proof-run.ts` so local and CI behavior match.

Exit criteria:

```powershell
npm run typecheck
npm run build
npm test
npm audit --audit-level=moderate
npm run audit:allowlist
npx tsx scripts/license-scan.ts
cd android-relay
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
```

### Phase 2: Close Runtime Replay And Protocol Holes

Goal: every bridge event is safe under retry, reconnect, and duplicate delivery.

Tasks:

- Add an idempotency key to every Android `RelayBridgeEvent`, not only gesture wake events.
- Include event class in the key: session, event, pending action, transcript hash where needed, and monotonic client event sequence.
- Make `idempotencyKey` required in `earbudEventSchema` for Android relay sources.
- Keep duplicate read-only status events harmless, but reject duplicate unsafe approval/action events when payload differs.
- Add bridge tests for:
  - duplicate approval gesture
  - duplicate voice command retry
  - queued event retry after bridge outage
  - autonomy continue retry
  - same idempotency key with different payload
- Normalize protocol version:
  - Use either numeric major version or semantic string everywhere, not both.
  - Reject unsupported major versions at `/events`.
  - Surface protocol mismatch as a distinct Android repair action.

Exit criteria:

- A retried event cannot execute an action twice.
- Android receives a clear protocol mismatch error instead of a generic bridge failure.
- Tests cover duplicate safe, duplicate unsafe, and incompatible protocol cases.

### Phase 3: Make "Ready" Mean "Speak Now"

Goal: the primary UX never overstates current runtime readiness.

Tasks:

- Create a single `SpeakNowReadiness` or `VoiceLoopReadiness` model that combines:
  - setup proof state
  - current `listenReadiness`
  - STT availability
  - TTS readiness
  - bridge health
  - current device connection
  - route readiness or explicit phone mic fallback
  - latest proof age and current phone/earbud match
  - blocking proof failures
- Use that model in Home, Device, Setup, and diagnostic export.
- Show Ready only when all required current conditions pass.
- Show Degraded when the saved proof exists but current route/fallback is weaker.
- Show Blocked when permissions, route, bridge, or device state prevents a session.

Exit criteria:

- Disconnecting earbuds after setup removes Ready.
- Revoking microphone permission removes Ready.
- Bridge healthy but STT unavailable does not show Ready.
- Saved proof for one earbud model cannot make a different connected model Ready.
- Phone mic fallback is clearly labeled when active.

### Phase 4: Production TTS Ownership

Goal: spoken responses are reliable under normal phone audio conditions.

Tasks:

- Add Android `AudioAttributes` for spoken assistant output.
- Request audio focus before TTS starts.
- Abandon focus after done, error, or stop.
- Handle transient and permanent focus loss.
- Record audio-focus request result and focus-loss reason in TTS metrics.
- Ensure barge-in stops TTS and releases focus before starting listening.
- Add tests around focus granted, focus denied, focus lost during speech, and barge-in.

Exit criteria:

- TTS failures fall back to display text and retry.
- Focus denial is a clear failure reason.
- Barge-in still meets the 250 ms target on physical devices.

### Phase 5: First Physical Beta Proof

Goal: earn one real beta target instead of relying on scaffolding.

Recommended first beta matrix:

| Phone | Android | Earbuds | Why |
| --- | --- | --- | --- |
| Pixel or primary dev phone | Current stable Android | One owned generic headset or AirPods/Galaxy Buds pair | Fastest controlled proof of the full relay loop. |

Run:

- 20 tap-to-command sessions.
- 5 tap-during-TTS interruption tests.
- 3 disconnect/reconnect tests.
- 3 app process-death recovery tests.
- 1 bridge restart/reconnect test.
- 1 diagnostic privacy review.

Exit criteria:

- A redacted proof artifact exists under `artifacts/proof-runs/`.
- `docs/release-matrix.md` has at least one real row.
- The proof artifact passes `scripts/validate-proof-run.ts`.
- Default diagnostic export from that run contains no raw audio, transcript text, personal Bluetooth name, token, or local workspace secret.

### Phase 6: Beta Packaging And Recovery

Goal: beta users can install, pair, recover, and report failures without developer help.

Tasks:

- Build and test the Windows bridge package on a clean machine or clean VM.
- Add a "bridge repair" checklist in-app: same Wi-Fi, pairing, firewall, token, version mismatch, OpenClaw disabled fallback.
- Add Android app update/version mismatch copy in Help.
- Add one-click diagnostic preview/share with privacy toggles.
- Add a beta kill switch or local config flag for:
  - OpenClaw rewrite mode
  - offline speech experiments
  - vendor-rich providers
  - phone mic fallback
- Keep Sherpa/Silero hidden unless native runtime is real and benchmarked.

Exit criteria:

- A new beta user can pair the bridge and complete a proof run from a clean install.
- The app can recover from bridge restart, phone restart, earbud reconnect, and app process death.
- Experimental paths cannot silently become default.

## Extra Reliability Work For "Absolutely Reliable"

These are not all required for the first beta, but they are what moves the product from "works for beta" to "trustworthy daily driver."

1. Add a chaos test mode.
   - Randomly kill bridge, drop Wi-Fi, disconnect Bluetooth, reject permissions, and restart the Android service while proving recovery behavior.

2. Add SLO dashboards from local artifacts.
   - Wake success rate, route-settle p95, STT finalization p95, bridge p95, TTS start p95, barge-in p95, and failure category counts.

3. Add proof freshness rules.
   - A proof should expire or downgrade when Android version, app version, bridge version, earbud model, provider path, input path, or engine changes.

4. Add field-safe crash and failure collection.
   - Default to local-only, redacted exports. Add opt-in crash reporting only after privacy wording is explicit.

5. Add real route confidence.
   - Combine Android route state, AudioRecord probe summaries, VAD/RMS evidence, and user confirmation before claiming earbud mic.

6. Add model/plugin dependency preflight.
   - OpenClaw, Sherpa, and any optional runtime should have deterministic preflight checks and clear disabled states.

7. Add release channels.
   - Internal, alpha, beta, and stable should each have different proof requirements and feature flags.

8. Add support playbooks.
   - Every common failure category should map to one repair action and one escalation artifact.

9. Add battery/thermal runs.
   - Run repeated voice sessions for 30 minutes and record drain, thermal throttling, memory, and foreground service behavior.

10. Add accessibility and noisy-environment validation.
   - Verify readable UI states, voice response length, headphones volume expectations, and noisy room STT failure handling.

## Bottom Line

The implementation has moved past pure scaffolding. The core architecture is credible now.

It is still not beta-ready because the repo is not green, security allowlist validation is failing, there are no real proof-run artifacts, and several runtime edge cases can still overstate readiness or leave retries insufficiently protected.

The next release milestone should be: green gates, strict protocol/idempotency, "Speak Now" readiness, production TTS audio focus, and one real physical proof artifact. After that, the application can enter a controlled beta honestly.
