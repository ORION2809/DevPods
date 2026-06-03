# DevPods Release Matrix

This document tracks which phone + Android version + earbud combinations have passed the full physical proof contract.

## Proof Contract

A device combination is **Proven** only when:

1. **20 tap-to-command sessions** pass with:
   - Wake detected on every session
   - Bluetooth mic route settles within 2 seconds
   - STT produces a final transcript or clear user-actionable failure
   - Bridge receives the event and responds
   - TTS plays the response

2. **5 tap-during-TTS interruption tests** pass with:
   - TTS stops within 250ms of tap
   - New listening session starts within 250ms

3. **3 disconnect/reconnect runs** pass with:
   - App recovers and resumes listening after reconnection

4. **1 diagnostic export privacy review** passes with:
   - No raw audio, no transcript text, no personal Bluetooth names in default export

## Matrix

| Phone | Android | Earbuds | Provider | Wake Path | Input | Output | Engine | Bridge | Status | Proof Run ID |
|---|---|---|---|---|---|---|---|---|---|---|
| *Pending first proof* | | | | | | | | | | |

## Status Definitions

| Status | Meaning |
|---|---|
| `proven` | Full 20-session proof + 5 interruption tests passed on this exact combination |
| `fallback_proven` | Android MediaSession or assistant fallback works; direct hardware not proven |
| `observed` | Device detected and some signals observed, but full proof not completed |
| `implemented_unverified` | Code exists but no physical proof yet |
| `scaffolded` | Detection or transport scaffolding exists, runtime behavior not complete |
| `unsupported` | Tested and failed, or platform blocks required path |

## Per-Entry Validation Checklist

- [ ] 20 tap-to-command sessions
- [ ] 5 tap-during-TTS interruptions
- [ ] 3 disconnect/reconnect runs
- [ ] 3 app process-death recovery runs
- [ ] 1 diagnostic export privacy review
- [ ] 1 bridge restart/reconnect run

## How to Add an Entry

1. Run the full validation checklist on the target combination.
2. Export the redacted diagnostic after the run.
3. Compute the SHA-256 of the diagnostic export.
4. Fill in `docs/proof-run-template.json` with the session data, then save the completed proof artifact under `artifacts/proof-runs/`.
5. Open a PR updating this file with the new row and the proof run artifact.
