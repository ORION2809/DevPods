# Reliability And User Experience Product Audit

Date: 2026-05-19

Scope:

- [19-earbud-compatibility-integration-source-map.md](19-earbud-compatibility-integration-source-map.md)
- [22-voice-audio-pipeline-external-source-blueprint.md](22-voice-audio-pipeline-external-source-blueprint.md)
- Current `android-relay` implementation in this working tree

## Executive Verdict

DevPods has the right architecture for a reliable earbud-first voice product: provider-based earbud compatibility, Android MediaSession fallback, explicit route proof, speech metrics, no-raw-audio diagnostics, and a guided setup surface. The missing piece is no longer mostly architecture. It is proof, claim discipline, and hard failure handling.

The product should not yet claim "works reliably with AirPods, Galaxy Buds, Sony, Nothing, Oppo, Realme, and generic earbuds." The honest current claim is:

> DevPods has a strong Android baseline and multiple provider implementations, but broad reliability is still unproven until the hardware matrix, voice proof run, diagnostic export, and onboarding gates are completed and validated on real devices.

The best product path is to make the app prove the user's actual earbuds on the user's actual phone, store that proof locally, and shape the UI around what is proven instead of what the code theoretically supports.

## Current Strengths

| Area | Current state | Product value |
| --- | --- | --- |
| Provider architecture | `SignalProviderRegistry` now wires Apple, Samsung, Sony, Nothing, Oppo/Realme, LibrePods, Android MediaSession, Assistant, Generic Bluetooth, and Generic GATT providers. | The app can layer rich providers over universal Android controls. |
| Universal wake path | Android MediaSession and assistant fallback exist as normalized signal providers. | Most Bluetooth earbuds can have a fallback path even when vendor protocols fail. |
| Route proof | `AudioRouteProof`, `AudioRouteSession`, `AudioRouteFallbackPolicy`, and `AudioRecordRouteProbe` exist. | The product can distinguish "Bluetooth connected" from "the mic is actually usable." |
| Voice metrics | `SpeechSessionMetrics`, `VoiceDiagnosticsStore`, `VoiceProofRun`, TTS metrics, and VAD observation scaffolding exist. | Reliability can be measured per session instead of guessed. |
| Diagnostic privacy | Default diagnostic logic avoids raw audio and transcript text. | Good foundation for support without collecting sensitive content. |
| UX foundation | Home, Activity, Device, Help, and Setup Wizard screens exist with fallbacks and diagnostic actions. | The app is moving from debug console toward guided product experience. |
| Offline path discipline | Sherpa/Silero is scaffolded behind readiness checks instead of being promoted by default. | Keeps the stable platform recognizer as the production baseline. |

## Top Product Risks

| Priority | Risk | Why it blocks a reliable product | Required outcome |
| --- | --- | --- | --- |
| P0 | Supported-device claims are ahead of physical proof. | A model-name match is not the same as a working wake, route, STT, TTS, and interruption loop. | Replace broad "code complete" claims with per-phone, per-earbud, per-Android-version proof states. |
| P0 | The 20-session proof run is not yet a hard release gate. | Reliability must be measured under repeated real use, not inferred from unit tests. | Require proof-run pass before a device is labeled Ready. |
| P0 | Voice proof summaries still miss key failure classes. | A user can experience no mic signal, read failures, or missed barge-in while the summary under-explains the failure. | Finish the VAP queue items that harden proof summary and exports. |
| P0 | Vendor providers are partly scaffolded and partly reverse-engineering-derived. | Shipping unsupported protocol claims creates reliability and license risk. | Add provenance review, license boundaries, and "fallback only" labels where rich protocol behavior is not proven. |
| P0 | Onboarding can complete without enough physical proof. | The first-run experience is where trust is won or lost. | Setup must prove pairing, wake, route, STT, TTS, and interruption, or clearly save a Degraded profile. |
| P1 | Offline Sherpa/Silero engines are placeholders. | Exposing offline STT as a real product feature would disappoint users. | Keep hidden or experimental until native adapter, models, benchmark, rollback, and battery tests are complete. |
| P1 | UX still exposes some operational/debug concepts. | End users need clear readiness, repair actions, and confidence labels, not implementation details. | Move advanced fields behind Developer Mode and make primary screens outcome-led. |

## What Is Missing From The Earbud Compatibility Plan

### 1. Truthful Compatibility Taxonomy

`docs/supported-devices-matrix.json` currently lists many models as `code_complete`. That is too optimistic for a customer-facing product. A reliable matrix needs these separate statuses:

| Status | Meaning | Customer wording |
| --- | --- | --- |
| `proven` | 20-session physical proof passed on a named phone and Android version. | Ready |
| `observed` | Device detected and at least one real signal or state was observed, but full proof did not pass. | Partially supported |
| `fallback_proven` | Vendor protocol is not proven, but Android MediaSession or assistant path passed. | Works through Android controls |
| `implemented_unverified` | Code exists but no physical device proof. | Lab build only |
| `scaffolded` | Detection or transport exists, but rich behavior is not implemented or validated. | Not advertised |
| `unsupported` | Tested and failed, or platform blocks required path. | Not supported |

Required change:

- Store compatibility as `deviceModel + phoneModel + androidVersion + providerId + inputPath + outputPath + proofRunId`.
- Never mark a model Ready from static model matching alone.
- Show "last proven" date and path in the Device screen.

### 2. Physical Device Matrix Ownership

The docs name the right device families, but the product needs a small owned test fleet and a repeatable field protocol.

Minimum release matrix:

| Phone class | Android versions | Earbuds |
| --- | --- | --- |
| Pixel | 14, 15, current preview only if intentionally supported | AirPods Pro 2, AirPods 4, Galaxy Buds2 Pro, Sony WF-1000XM5, generic headset |
| Samsung Galaxy | 14, 15 | Galaxy Buds2 Pro, Galaxy Buds3 Pro, AirPods Pro 2, Sony WF-1000XM4 |
| OnePlus/Oppo/Realme | 14, 15 | Oppo/Realme/OnePlus Buds, AirPods Pro 2, generic headset |
| Xiaomi/other OEM | 14 or 15 | One fallback-focused generic headset plus one Samsung/Sony pair |

Each matrix entry needs:

- 20 tap-to-command sessions.
- 5 tap-during-TTS interruptions.
- 3 disconnect/reconnect runs.
- 3 app process-death recovery runs.
- 1 diagnostic export checked for privacy and useful failure explanation.

### 3. Provider Claim Hardening

Several vendor providers are good scaffolds, but they should not be marketed as rich integrations until real protocol behavior is observed:

- Apple provider: BLE proximity and AACP parsing are present, but AirPods stem events remain physically unproven in the current audit trail.
- Samsung provider: detects bonded Galaxy Buds and parses some battery packets, but wake still depends on Android media-button fallback.
- Sony provider: detects Sony devices and opens classic transport, but gesture reliability is described as MediaSession fallback.
- Nothing/Oppo/Realme providers: useful detection/fallback layer, but should remain unadvertised as rich integrations until packet/state coverage is proven.
- Generic GATT battery: useful fallback, but should not imply gesture support.

Required change:

- Add a provider conformance score that separates `detects_device`, `reads_battery`, `observes_wake`, `observes_interrupt`, `observes_approval`, `routes_mic`, and `survives_disconnect`.
- Surface the score in Developer Mode and export it in redacted diagnostics.

### 4. Licensing And Provenance Gate

The source-map correctly identifies GPL/AGPL sources such as LibrePods, CAPod, OpenPods, Gadgetbridge, and GalaxyBudsClient. The implementation comments also describe provider behavior as derived from external projects.

Before any release candidate:

- Create `docs/vendor-protocol-provenance.md`.
- For every protocol implementation, record source project, license, commit, what was copied, what was reimplemented, and who reviewed it.
- Do not ship GPL/AGPL-derived code in a proprietary distribution unless the product license and notices are compatible or explicit permission is obtained.
- Keep external source references out of production code comments when they could imply direct copying without a license decision.

## What Is Missing From The Voice And Audio Plan

### 1. Finish The Baseline Proof Metrics

The platform recognizer path is the right default, but several blueprint metrics are still incomplete or only partially exported.

Gaps to close:

- `SpeechSessionRequest` does not yet carry `possibleCompleteSilenceMs`.
- `PlatformVadObservation` does not yet expose `rmsFramesAboveNoiseFloor`.
- `VoiceProofRun` flags route, STT, wrong mic, probe init failure, and missed interruption target, but should also fail on no-signal probes and read failures.
- `DiagnosticExport.RedactedVoiceProofRun` does not include `interruptionTargetMetCount`.
- Offline benchmark summaries are not yet persisted and exported.
- Sherpa VAD/STT classes still return "native adapter not linked" errors by design.

Best execution order:

1. Complete `VAP-02`, `VAP-03`, `VAP-04`, `VAP-05`, and `VAP-06` from [23-voice-pipeline-ticket-queue.md](23-voice-pipeline-ticket-queue.md).
2. Run the full Android unit test suite.
3. Only then continue to Sherpa inventory, benchmarks, and native integration.

### 2. Make Route Failure A User Decision

The code has `AudioRouteFallbackPolicy` and phone mic fallback is off by default, which is the right privacy stance. The UX still needs to make the route decision feel calm and explicit.

Required behavior:

- If Bluetooth mic route fails, do not silently listen through the phone mic.
- Show: "Earbud mic did not connect. Use phone microphone for this session?"
- Remember user preference only after explicit confirmation.
- In the transcript timeline and diagnostics, mark whether the input path was earbud mic or phone mic.
- If the route is suspect because Bluetooth is active but RMS is absent, explain "Earbuds are connected, but no microphone signal was detected."

### 3. Barge-In Must Become A Product Contract

TTS interruption metrics exist, but the product needs a user-visible contract:

- Tap during speech stops TTS within 250 ms target.
- New listening starts only after TTS stop is requested and route policy is satisfied.
- Long bridge responses remain trimmed for ear-safe listening.
- If TTS fails, the user sees the display response and can retry listening without restarting the service.

Release gate:

- 5 consecutive tap-during-TTS interruptions pass on every supported matrix device.
- Median barge-in latency is under 250 ms.
- Worst-case latency is recorded and exported.

### 4. Offline Speech Needs A Separate Beta Gate

Sherpa/Silero is a good direction, but the current implementation is not a real offline speech engine yet.

Do not expose offline speech as a normal setting until:

- Native adapter is linked in a separate flavor or module.
- VAD and STT model inventory are separated.
- Model download/install has checksum, version, size, low-storage handling, and rollback.
- 20-session benchmark beats or clearly complements Android `SpeechRecognizer`.
- CPU, memory, thermal, and battery numbers are captured.
- A remote or local kill switch can fall back to platform STT.

## User Experience Audit

### First-Run Onboarding

The ideal onboarding is not "configure settings." It is a short proof flow:

1. Pair desktop bridge.
2. Detect earbuds.
3. Press the earbud control.
4. Speak a command.
5. Hear the response.
6. Tap again to interrupt.
7. Save the proven profile.

Missing UX:

- Setup should show pass/fail per step, not just a final completion state.
- "Use assistant fallback" needs to execute a real fallback path or be removed until wired.
- Setup should not complete as Ready if wake or STT proof failed. It should complete as Degraded with a clear saved fallback.
- Each failure needs one primary repair action: reconnect earbuds, grant permission, enable phone mic fallback, use assistant fallback, or retry bridge pairing.

### Device Screen

Missing UX:

- Show capability confidence per gesture: Proven, Observed, Fallback, Unproven, Unsupported.
- Show the active input path: earbud mic, phone mic fallback, assistant, or push-to-talk.
- Show "last proof run" and "run proof again."
- Do not expose protocol/provider language as the main label unless Developer Mode is active.

### Home And Activity

Missing UX:

- The main state should answer one question: "Can I speak from my earbuds right now?"
- Activity should make failures readable as a timeline: wake received, route ready, speech heard, bridge response, TTS started, interruption.
- If the bridge is unreachable, keep earbud/STT diagnostics separate from bridge/network diagnostics so users are not sent down the wrong path.

### Help And Diagnostics

Missing UX:

- Diagnostic export should have a preview explaining exactly what is included and excluded.
- Default export must remain raw-audio-free and transcript-free.
- Raw route details, phone model, and transcript text should be separate toggles with plain-language consent.
- Support export should include provider health for all providers, not only the current device profile.

### Accessibility

Required before product release:

- TalkBack labels for all icon-only actions.
- Large touch targets for wake, retry, cancel, stop, and proof-run actions.
- Haptic or audio confirmation for wake received, listening started, and TTS interrupted.
- Notification actions that work without opening the app.
- Error text that does not rely on color alone.

## Reliability Release Gates

DevPods should not be called reliable until these pass.

| Gate | Minimum threshold |
| --- | --- |
| Wake reliability | >= 95 percent expected wake events on target devices. |
| Route reliability | >= 95 percent sessions use intended mic, or ask before fallback. |
| STT capture | >= 95 percent short-command sessions produce final transcript. |
| Wrong mic detection | Suspect route is flagged when Bluetooth route is active but no useful signal appears. |
| TTS interruption | Median barge-in under 250 ms, with failures exported. |
| Service survival | Process death restores safe idle state, not active mic capture. |
| Disconnect handling | Mid-session Bluetooth disconnect exits cleanly and explains route loss. |
| Privacy | Default export contains no raw audio, no transcript text, no token, no bridge URL, no MAC address. |
| Claims | Device matrix has no Ready status without physical proof. |
| Battery | 10-minute active relay test has acceptable drain and no thermal warning on matrix phones. |

## P0 Execution Plan

1. Fix compatibility claim taxonomy.
   - Change `supported-devices-matrix.json` statuses from broad `code_complete` to proof-based states.
   - Add `fallback_proven` and `implemented_unverified`.

2. Finish voice proof hardening.
   - Complete `VAP-02` through `VAP-06`.
   - Add no-signal and read-failure proof-run failure reasons.
   - Export interruption target counts.

3. Make setup proof mandatory.
   - Save a `DeviceCapabilityEntry` only from actual proof results.
   - Ready requires wake, route, STT, TTS, and interruption proof.
   - Failed proof saves Degraded with the selected fallback path.

4. Run real hardware validation.
   - Start with RMX3990 plus Buds Air7 because there is already field history.
   - Add one AirPods, one Galaxy Buds, and one Sony device next.
   - Attach proof-run summaries to the matrix.

5. Complete diagnostic privacy tests.
   - Verify default export redacts transcript, raw audio, route identifiers, bridge URL, token, workspace, and MAC addresses.
   - Verify opt-in toggles are independent.

6. Add provenance report.
   - Document every external protocol source and license decision.
   - Block release on unresolved GPL/AGPL reuse risk.

## P1 Product Polish

- Move raw bridge URL, token, workspace, model path, and checksum fields behind Developer Mode unless the user is explicitly pairing or configuring beta offline speech.
- Add a "Run proof again" action on Device and Help.
- Add last proven time, phone, Android version, input path, and fallback path to the Device screen.
- Add friendly route repair messages and one-tap retry.
- Add notification push-to-talk, retry, cancel, and stop verification on physical devices.
- Persist offline benchmark summaries only after baseline proof is stable.

## P2 Advanced Work

- Implement the real Sherpa native adapter and benchmark harness.
- Add per-device endpoint tuning profiles after enough metrics exist.
- Evaluate RNNoise only if noisy-route proof shows STT failures caused by noise.
- Pursue licenses or partnerships for LibrePods, CAPod, Gadgetbridge, GalaxyBudsClient, MagicPods, or Jabra if the product needs richer vendor controls.

## Definition Of "Best Possible User Experience"

For this product, best possible UX means the user never has to understand Bluetooth protocols, STT engines, route APIs, or provider priorities.

The app should feel like this:

- "Your earbuds are ready."
- "Tap once, speak, hear the answer."
- "Tap again to stop."
- "If something fails, the app says exactly which part failed and gives one useful next action."
- "The app never claims support until it proves support on my actual device."
- "The app never records private audio or transcript text for support unless I clearly opt in."

That is the bar. The current architecture can reach it, but the remaining work must be driven by proof runs, honest compatibility claims, and quiet, repair-focused UX.

