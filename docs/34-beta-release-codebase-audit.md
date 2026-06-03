# Beta Release Codebase Audit

Generated: 2026-05-21

## Scope

This audit reviews the current codebase for release to beta users. It covers:

- Android release build and runtime behavior
- Windows bridge packaging
- Bridge API security
- Pairing and token handling
- Proof-run and physical-device release gates
- CI/release automation
- User-facing reliability and supportability

This is a current-state audit. It supersedes older assumptions where the codebase has moved forward.

## Executive Verdict

Do not release this build to beta users yet.

The codebase is much closer to beta than before: the local product gate passes, Android debug and release APKs build, Windows bridge packaging works, dependency and license checks are green, Android relay events now require idempotency keys in the bridge schema, debug proof receivers are excluded from release, backups are disabled, and a T4 runner now exists.

But the release path still has hard blockers:

1. Android sends relay events with protocol version `"1.0"` while the bridge only accepts `"1"`.
2. The Android release build blocks cleartext HTTP, while the packaged Windows bridge still defaults to local HTTP pairing and event traffic.
3. The produced Android release artifact is unsigned.
4. There is no validated T4 physical proof artifact in `artifacts/proof-runs/`.
5. The current T4 proof artifact generation can overclaim physical Bluetooth because it does not strictly prove physical wake, Bluetooth mic route, or Bluetooth TTS output.
6. CI has a beta proof step, but the workflow triggers only `main` and `master`, so the beta/release proof gate may not run for actual beta branches.

The right label is: buildable internal dogfood, not beta-user release.

## Verification Performed

| Gate | Result | Notes |
| --- | --- | --- |
| `.\verify-product.ps1` | Pass | Bridge typecheck/build/tests/audit, Windows bridge package, Android debug APK, Android release APK, Android lint, Android unit tests, emulator install all passed. |
| `npx tsx scripts/validate-proof-run.ts artifacts/proof-runs/ --require-tier T4_PHYSICAL_ANDROID_EARBUDS --max-age-days 7` | Fail | `artifacts/proof-runs/` contains zero proof-run JSON files. |
| `Invoke-Pester -Script .\simulation\android-relay\run-t4-proof.Tests.ps1 -EnableExit` | Pass | 4 T4 runner contract tests passed. |
| Secret scan for common token patterns | Pass | No `ghp_`, OpenAI-style `sk-...`, or Google API key pattern found in source/config files scanned. |
| Direct Android-shaped bridge event with `protocolVersion: "1.0"` | Fail as expected | Bridge returned HTTP 400: `Unsupported protocol version: 1.0. Supported: 1`. |
| Android release APK size check | Observed | `app-release-unsigned.apk` is about 82 MB and includes both `arm64-v8a` and `x86_64` Sherpa native libraries. |

## What Is Good Enough To Keep

- `verify-product.ps1` is useful and passed locally.
- Android release build succeeds.
- Windows portable bridge packaging succeeds.
- `npm audit` is clean.
- License scan is clean.
- Debug proof receivers live under `src/debug`.
- Release network config blocks cleartext traffic.
- Android app backup and data extraction rules exclude app data.
- Relay service internal commands use an app-local caller token.
- Bridge requires a relay token when bound to a non-loopback host.
- Android relay events now require `protocolVersion` and `idempotencyKey` at the bridge schema boundary.
- The T4 runner rejects emulator serials and does not synthesize fake local T4 proof artifacts.

These are real improvements. They are not enough for beta release.

## P0 Blockers

### P0-1: Android Event Protocol Version Does Not Match The Bridge

Evidence:

- Android sets `RELAY_PROTOCOL_VERSION` to `"1.0"` in `android-relay/app/src/main/java/com/openclaw/relay/RelayModels.kt`.
- Bridge accepts only `SUPPORTED_PROTOCOL_VERSIONS = ['1']` in `src/protocol/schemas.ts`.
- A direct Android-shaped `/events` request with `protocolVersion: "1.0"` returns HTTP 400.

Impact:

The app can pass pairing and health checks, then fail every Android relay event at the bridge. This is a beta-stopping runtime break.

Required fix:

- Align both sides on one event protocol value.
- Prefer either `"1"` everywhere or `"1.0"` everywhere.
- Add a cross-stack test using Android's serialized `RelayBridgeEvent` shape against the TypeScript bridge.
- Add a health check warning when bridge health protocol and event protocol would disagree.

### P0-2: Release Android Blocks The Packaged Bridge Transport

Evidence:

- `android-relay/app/src/release/res/xml/network_security_config.xml` sets `cleartextTrafficPermitted="false"`.
- `packaging/windows/start-devpods-bridge.ps1` defaults the phone pairing base URL to `http://<lan-ip>:4545`.
- `BridgeClient` sends health and event traffic to the configured `bridgeBaseUrl`.

Impact:

A real release APK will reject the default HTTP bridge flow that the Windows beta package creates. Debug builds work; release builds can fail for beta users.

Required fix:

- Decide the beta transport policy.
- Option A: ship HTTPS or a secure local tunnel for the desktop bridge.
- Option B: allow cleartext only for trusted LAN bridge hosts in release with explicit UX warnings.
- Add an installed release-APK pairing test against the packaged bridge transport.

### P0-3: Android Release APK Is Unsigned

Evidence:

- `verify-product.ps1` produced `android-relay/app/build/outputs/apk/release/app-release-unsigned.apk`.
- `android-relay/app/build.gradle.kts` has no beta/release signing config.

Impact:

There is no releasable Android artifact for beta users. An unsigned release APK is not a beta distribution pipeline.

Required fix:

- Add signing config through environment variables or CI secrets.
- Produce a signed beta artifact.
- Record SHA-256, versionCode, versionName, build commit, and release notes.
- Test upgrade from previous beta version.

### P0-4: No Validated Physical T4 Artifact Exists

Evidence:

- `artifacts/proof-runs/` exists but contains no proof-run JSON files.
- The explicit T4 validator command fails with `No proof-run JSON files found`.
- `docs/release-matrix.md` still has `Pending first proof`.

Impact:

No beta claim can be made for real earbuds, real Bluetooth routing, real STT, real TTS, or real barge-in behavior.

Required fix:

- Run `simulation/android-relay/run-t4-proof.ps1` against a physical Android device and real earbuds.
- Commit a redacted, validator-clean T4 artifact under `artifacts/proof-runs/`.
- Update `docs/release-matrix.md` from that artifact only.

### P0-5: T4 Proof Can Still Overclaim Physical Bluetooth

Evidence:

- `VoiceProofRunSession.routeSucceeded` treats both `ROUTE_BLUETOOTH_ACTIVE` and `ROUTE_PHONE_MIC` as success.
- `RelayServiceDebugExt.storeProofArtifact()` sets `physicalBluetoothProven = !summary.hasBlockingFailures` for T4.
- T4 artifact generation hardcodes `inputPath = "android_bluetooth_headset"` and `outputPath = "android_tts_bluetooth"` based on tier, not on every recorded session route.
- `wakePath` is hardcoded to `"android_push_to_talk"`, while the schema's physical wake values are `direct_hardware`, `android_media_session`, `assistant_entry`, and `push_to_talk`.
- `wakeDetected` is derived from speech/STT success, not from a physical media-button or earbud signal.

Impact:

A T4 artifact can appear physically proven even if the actual sessions used phone mic fallback, push-to-talk, or unverified wake routing. That would break user trust immediately.

Required fix:

- For T4, require every counted successful session to have:
  - physical wake source observed,
  - `routeState == ROUTE_BLUETOOTH_ACTIVE`,
  - selected input device type matching a Bluetooth headset route,
  - TTS output path verified as Bluetooth or explicitly marked phone-speaker fallback,
  - no phone mic fallback unless the artifact is labeled fallback, not physically proven.
- Make `physicalBluetoothProven` derived from those checks, not from `!hasBlockingFailures`.
- Make the validator enforce `wakePath`, `status == PASSED`, `blockingFailures.length == 0`, route details, and non-placeholder `diagnosticExportHash`.

### P0-6: T4 Runner Proves A Debug APK, Not The Release APK

Evidence:

- `run-t4-proof.ps1` builds and installs `app-debug.apk`.
- The T4 automation depends on `DebugProofRunnerReceiver` and `RelayServiceDebugExt`, both debug-only.
- Release builds have different network-security behavior.

Impact:

The current T4 proof lane can validate development behavior, but not the exact release artifact beta users will install.

Required fix:

- Keep debug T4 for fast lab proof.
- Add release-candidate T4:
  - install signed beta APK,
  - run proof through user-visible controls or a protected internal test hook,
  - validate the same artifact contract,
  - prove release network config and bridge transport.

## P1 High Priority

### P1-1: CI Beta Gate May Not Run On Beta Branches

Evidence:

- `.github/workflows/ci.yml` triggers only on `main` and `master`.
- `BETA_MODE` checks whether `github.ref` contains `beta` or `release`.

Impact:

The beta proof gate can be present but never execute for the branch/tag pattern used to cut beta releases.

Required fix:

- Add beta/release branches or tags to workflow triggers.
- For pull requests, use `github.head_ref` and `github.base_ref`, not only `github.ref`.
- Add a manual `workflow_dispatch` beta-release validation workflow that always requires T4 proof.

### P1-2: Pairing Verify Has No Pre-Auth Rate Limit And Can Throw On Length Mismatch

Evidence:

- `/pairing/verify` is intentionally unauthenticated.
- It uses `timingSafeEqual(Buffer.from(activePairingCode), Buffer.from(verifyRequest.data.pairingCode))`.
- `timingSafeEqual` throws if buffer lengths differ.
- The pairing rate limiter only applies after `/events` parsing by session ID.

Impact:

A LAN attacker or broken client can spam pairing verify attempts and can cause 500-class errors with wrong-length codes.

Required fix:

- Add IP or socket-level rate limiting for `/pairing`, `/pairing/verify`, and `/pairing/regenerate`.
- Check buffer length before `timingSafeEqual`.
- Limit `pairingCode` length in the schema.
- Return 401 for all invalid code lengths.

### P1-3: Long-Lived Relay Tokens Are Stored In Plaintext

Evidence:

- Android stores `relayToken` in regular `SharedPreferences`.
- Windows bridge writes `relayToken` to `bridge-config.json`.
- Backup/data extraction is disabled on Android, which helps, but at-rest token protection is still basic.

Impact:

For a beta, this may be acceptable only if clearly scoped to trusted local machines. It is not strong enough for broader release.

Required fix:

- Android: use EncryptedSharedPreferences or equivalent.
- Windows: use DPAPI/Credential Manager or at least file ACL hardening.
- Add token rotation and "forget this bridge" UX.

### P1-4: Release APK Carries Unproven Sherpa Native Runtime

Evidence:

- `app-release-unsigned.apk` is about 82 MB.
- It includes both `arm64-v8a` and `x86_64` Sherpa/ONNX native libraries.
- Sherpa remains experimental and feature-flagged off.

Impact:

Beta users carry size and native-library risk for a feature that is not beta-ready.

Required fix:

- Add `platformOnlyBeta` and `sherpaInternal` build variants, or split by ABI/flavor.
- Ship beta without Sherpa native libraries unless the beta explicitly tests offline STT.
- Add APK size budget and ABI checks in CI.

### P1-5: Exported Assistant Entry Can Be Triggered By Other Apps

Evidence:

- `AssistantEntryActivity` is exported and starts relay service action `ACTION_ASSIST_LONG_PRESS` for assistant intents.
- It has no caller verification because it is intended as an assistant fallback entry.

Impact:

Another app on the phone may be able to trigger assistant-style relay wake events. Risky bridge actions still require approval, but unsolicited wake/listen behavior is a beta UX and security concern.

Required fix:

- Add user setting to enable/disable assistant fallback.
- Rate-limit assistant fallback starts.
- Add telemetry for external assistant launches.
- Investigate whether caller/package validation can be applied without breaking the assistant integration.

### P1-6: Windows Bridge Package Is Not A Beta Installer

Evidence:

- Packaging copies Node, `node_modules`, config, and scripts to a portable directory.
- There is no code signing, installer, auto-update, checksum manifest, firewall guidance, or uninstall path.

Impact:

It can support internal dogfood. It is not polished enough for ordinary beta users.

Required fix:

- Add signed installer or signed portable ZIP.
- Publish checksums.
- Add first-run firewall and LAN guidance.
- Add logs/support export location.
- Add versioned update path.

## P2 Reliability And UX Gaps

- `verify-product.ps1` reports the first regex match from Vitest output as "19 tests passed", which appears to be test files, not actual test count.
- `docs/BRANCH-PROTECTION.md` appears stale in places and should match the current test counts and beta proof requirements.
- `docs/supported-devices-matrix.json` remains mostly `implemented_unverified`, `scaffolded`, or `fallback_proven`.
- `run-t4-proof.ps1` has Pester tests locally, but CI does not run them.
- The release proof validator does not yet enforce every contract described in the JSON Schema.
- `diagnosticExportHash` can still be a placeholder in example/simulation artifacts.

## Beta Release Readiness Table

| Area | Status |
| --- | --- |
| Bridge build/test/audit | Ready |
| Android debug build/test/lint | Ready |
| Android release build | Builds, but unsigned and transport-mismatched |
| Windows package | Internal dogfood ready, beta packaging incomplete |
| Pairing | Improved, but release HTTP mismatch and verify hardening remain |
| Bridge auth/idempotency | Improved, but event protocol mismatch blocks Android |
| Physical earbuds proof | Not ready |
| Release CI gate | Partial, branch/tag trigger gap |
| Sherpa/Silero | Not beta-ready |
| Device matrix | Not beta-ready |
| User support diagnostics | Good foundation, still needs release proof/hash discipline |

## Minimum Fix List Before Beta

1. Fix Android and bridge event protocol version mismatch.
2. Decide and implement release transport: HTTPS/tunnel or explicit release cleartext LAN policy.
3. Produce a signed Android beta APK.
4. Fix T4 physical proof semantics so physical Bluetooth cannot be overclaimed.
5. Run a real T4 proof on the signed beta candidate or add a release-candidate proof lane.
6. Commit one validator-clean T4 artifact and update `docs/release-matrix.md`.
7. Fix CI triggers so beta/release proof validation actually runs.
8. Harden pairing verify rate limiting and timing-safe comparison.
9. Remove Sherpa native libraries from the default beta APK or create a deliberate Sherpa beta flavor.
10. Publish a signed/checksummed Windows bridge package with clear setup and support instructions.

## Release Call

Current codebase: not ready for beta users.

Allowed next step: internal dogfood with debug/lab builds, clearly marked as unproven, while collecting the first real T4 artifact.

Beta release should be blocked until the P0 list is closed.

