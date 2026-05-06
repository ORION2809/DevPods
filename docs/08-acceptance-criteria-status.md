# Acceptance Criteria Status

## Purpose

This document evaluates the current MVP against the acceptance criteria that matter most for the DevPods relay experience.

It is intentionally practical. Each criterion is marked against the current repository state, the current automated validation surface, and the latest real OpenClaw smoke run.

## Criteria Summary

| # | Criterion | Current status | Notes |
| --- | --- | --- | --- |
| 1 | fake triple tap wakes Jarvis | Met | Explicitly covered by acceptance test and event-router behavior |
| 2 | voice or text command routes correctly | Met | Covered by runtime integration tests and the router logic |
| 3 | OpenClaw responds cleanly | Met | Covered for HTTP and real local CLI paths |
| 4 | approval and cancel flows work | Met | Approval was already covered; cancel is now explicitly tested |
| 5 | repo actions are useful | Met for current MVP scope | Current surface is intentionally narrow but useful |
| 6 | policy blocks dangerous actions | Met | Deny-by-default and allowlist enforcement are covered in tests |
| 7 | output is short enough for ear-based UX | Met | Local and OpenClaw outputs are capped to a short spoken form |
| 8 | latency is acceptable | Met for shipped path | Budgeted adaptive rewriting keeps ear-facing latency in the low hundreds of milliseconds on both real transports |

## Criterion 1: Fake Triple Tap Wakes Jarvis

### Status

Met.

### What the implementation does

The normalized `triple_tap_right` event maps to `wake_and_listen` when no utterance is present.

That produces an acknowledged response, sets the session to `listening`, and returns the short prompt:

`Jarvis active. What should I check?`

### Evidence

- `src/bridge/request-builder.ts` maps `triple_tap_right` without an utterance to `wake_and_listen`
- `src/bridge/event-router.ts` turns `wake_and_listen` into a listening-state response
- `test/acceptance-criteria.test.ts` now covers the fake triple-tap wake path directly

### Outcome

The wake gesture contract for the simulator-first MVP is working.

## Criterion 2: Voice Or Text Command Routes Correctly

### Status

Met.

### What the implementation does

When `triple_tap_right` includes an utterance, the event maps to `voice_command` and flows through the local intent router.

Routing is currently based on deterministic utterance matching rather than LLM intent resolution. That is appropriate for the current deny-by-default MVP because it keeps routing behavior auditable and easy to test.

### Evidence

- `src/bridge/request-builder.ts` maps `triple_tap_right` with an utterance to `voice_command`
- `src/jarvis/router.ts` resolves voice commands into current supported intents such as `summarize_diff`, `run_tests`, `commit_staged`, `open_file`, `push`, `deploy`, `delete`, and `revert`
- `test/acceptance-criteria.test.ts` explicitly checks a `summarize my current diff` utterance against the repo-summary path
- `test/fake-event-to-response.e2e.test.ts` already covered the end-to-end event flow before the new acceptance slice was added

### Outcome

The simulator and local runtime are routing voice-style text commands into the correct bounded action surface.

## Criterion 3: OpenClaw Responds Cleanly

### Status

Met.

### What the implementation does

OpenClaw is currently integrated as a response rewrite layer, not as the policy owner.

The rewrite path activates only for responses that are already in `completed` or `running` states and never for approval prompts. This keeps output cleaner without giving OpenClaw control over permissions or tool execution.

### Evidence

- `src/openclaw/client.ts` rewrites only eligible responses and falls back safely to the local response if rewriting fails
- `test/openclaw-mode.test.ts` covers both:
  - HTTP-based OpenClaw rewriting through a mocked gateway
  - real local CLI rewriting through the OpenClaw sandbox and mock provider
- `simulation/openclaw-sandbox/smoke.ts` proves a real sandboxed local OpenClaw run against the bridge runtime

### Latest smoke proof

The latest smoke run returned:

- `quickStatusSpeak: OpenClaw sandbox completed the response.`
- `completionSpeak: OpenClaw sandbox completed the response.`

That confirms the rewrite path is active and stable in the real local OpenClaw sandbox path.

### Outcome

OpenClaw is producing clean, bounded response rewrites in the current MVP.

## Criterion 4: Approval And Cancel Flows Work

### Status

Met.

### What the implementation does

The system uses action-ID-bound approvals and explicit cancel flows rather than implicit yes or no behavior.

Supported behavior includes:

- approval-required prompts for bounded actions
- hard approval prompts for destructive or externally visible actions
- rejection of approvals with missing or mismatched action IDs
- cancel gestures that clear pending state and return the session to `idle`
- queued background tasks that can be cancelled before they start
- truthful blocking when a task is already running and can no longer be interrupted by gesture

### Evidence

- `src/bridge/event-router.ts` handles `approval_action` and `cancel` explicitly
- `src/jarvis/background-command-scheduler.ts` serializes overlapping background work per workspace and allows queued cancellations
- `test/fake-event-to-response.e2e.test.ts` covers approval-required `run_tests` flow
- `test/jarvis-local-actions.test.ts` already covered missing-action-ID blocking
- `test/acceptance-criteria.test.ts` now explicitly covers `both_hold_cancel` against a pending approval flow and verifies no task starts afterward
- `test/bridge-runtime.notifications.test.ts` now covers both queued background-task start sequencing and queued-task cancellation

### Outcome

Approval and cancel behavior are both working and are now covered across pending-approval, queued-task, and already-running-task boundaries.

## Criterion 5: Repo Actions Are Useful

### Status

Met for the current MVP scope.

### What the implementation does

The repo action surface is intentionally narrow but practically useful for a developer assistant.

Delivered actions include:

- quick repo status
- diff summary
- latest public GitHub Actions failure lookup
- commit message suggestion
- staged commit after hard approval
- open file after approval
- push current branch after hard approval
- deploy through the allowlisted workspace command after hard approval
- explicit file delete after hard approval
- explicit tracked-file revert after hard approval

### Evidence

- `test/jarvis-local-actions.test.ts` covers commit suggestion, staged commit, open file, push, revert, and related safety conditions
- `test/jarvis-ci-actions.test.ts` covers the latest CI failure adapter and graceful degradation

### Interpretation

This criterion should be read against the MVP boundary.

The system does not offer arbitrary shell access, agentic code generation, or broad tool-calling autonomy. That is a deliberate design choice, not a missing implementation bug. Within the current safety boundary, the repo actions are useful and credible.

### Outcome

The repo action layer is useful enough for the current local developer-assistant MVP.

## Criterion 6: Policy Blocks Dangerous Actions

### Status

Met.

### What the implementation does

Dangerous behavior is blocked in three layers:

- allowlist-based intent gating
- approval and hard-approval requirements
- workspace- and path-scoped adapter behavior

Actions that are not allowlisted for the workspace are denied before any tool execution begins.

### Evidence

- `src/policy/engine.ts` is deny-by-default for intents outside the workspace allowlist
- `src/bridge/event-router.ts` returns a blocked response before execution for denied intents
- `test/policy-engine.test.ts` covers allowlist denial behavior
- `test/acceptance-criteria.test.ts` now explicitly checks that a spoken `delete file` request is blocked when delete is not allowlisted

### Outcome

The current policy model is correctly preventing dangerous actions from leaking past the workspace boundary.

## Criterion 7: Output Is Short Enough For Ear-Based UX

### Status

Met.

### What the implementation does

The spoken response path is explicitly optimized for ear-first consumption.

The key enforcement point is `optimizeSpeak`, which normalizes whitespace and caps spoken output to 24 words. OpenClaw rewrites are passed through the same voice-optimization step before they become final user-facing speech.

### Evidence

- `src/jarvis/voice-optimize.ts` enforces the 24-word ceiling
- `test/fake-event-to-response.e2e.test.ts` already checked that the quick-status response stays under the 24-word threshold
- `test/acceptance-criteria.test.ts` now explicitly checks that an overlong OpenClaw response is trimmed before it reaches the final response surface

### Outcome

The repo is now enforcing the short-spoken-output requirement for both local and OpenClaw-assisted paths.

## Criterion 8: Latency Is Acceptable

### Status

Met for the shipped runtime path.

### What the implementation does well

The non-OpenClaw control path is already fast enough for the interaction model.

The default shipped OpenClaw runtime path is now explicitly latency-bounded: adaptive rewrite mode keeps already-short replies local, and slower OpenClaw rewrites time out back to the local response instead of blocking the spoken channel.

The latest real low-latency sandbox smoke runs show:

- `local-cli`: `quickStatus: 195 ms`, `approvalPrompt: 16 ms`, `approvalStart: 14 ms`, `completionWait: 252 ms`
- `gateway-client`: `quickStatus: 204 ms`, `approvalPrompt: 17 ms`, `approvalStart: 24 ms`, `completionWait: 266 ms`

That means the shipped ear-facing path is now operating in the low hundreds of milliseconds even when real OpenClaw transports are configured.

### What is still slow

The force-full-rewrite path is still much slower than the target ear-based UX budget when budgets are disabled or every eligible reply is forced through OpenClaw.

The latest real `local-cli` smoke run measured:

- `quickStatus: 25433 ms`
- `approvalStart: 23615 ms`
- `completionWait: 3618 ms`
- `total: 75470 ms`

The latest real `gateway-client` smoke run measured:

- `quickStatus: 69262 ms`
- `approvalPrompt: 3 ms`
- `approvalStart: 39990 ms`
- `completionWait: 265 ms`
- `total: 136975 ms`

The latest managed non-sandbox `local-cli` smoke run measured:

- `quickStatus: 31301 ms`
- `approvalPrompt: 10 ms`
- `approvalStart: 25029 ms`
- `completionWait: 4354 ms`
- `total: 92728 ms`

The latest managed non-sandbox `gateway-client` smoke run measured:

- `quickStatus: 50802 ms`
- `approvalPrompt: 4 ms`
- `approvalStart: 47245 ms`
- `completionWait: 265 ms`
- `total: 111949 ms`

### Interpretation

This means the system now has two different latency stories:

- shipped budgeted runtime path (`adaptive` + `250/750 ms` budgets): acceptable
- forced real OpenClaw rewrite paths (`local-cli` and `gateway-client` with budgets disabled): functionally correct, but not yet acceptable for a polished live earbud UX

The fast HTTP mock transport remains useful for development and deterministic test loops, but it is not the same thing as the real local OpenClaw execution path.

The resident `gateway-client` transport solved the tooling and packaging problem for a real gateway-backed integration. It is still slower than the local CLI path for full rewrites, but the shipped budgeted path now keeps it off the critical response path.

Adaptive rewrite plus explicit rewrite budgets improves the shipped experience for both short and slow replies because already-short responses skip OpenClaw and slower rewrites now fall back before they can stall the ear-facing UX. The remaining latency problem is concentrated in the flows that intentionally force a full real rewrite.

### Practical conclusion

If this criterion is interpreted for the actual shipped ear-based experience, it is now met.

If it is interpreted for the optional force-full-rewrite validation path, it is still only partially met.

## Validation Commands

The current most relevant validation commands for these criteria are:

```bash
npm run openclaw:latency:smoke
JARVIS_OPENCLAW_TRANSPORT=gateway-client npm run openclaw:latency:smoke
npx vitest run test/acceptance-criteria.test.ts
npx vitest run test/openclaw-mode.test.ts
npx vitest run test/openclaw-managed-mode.test.ts
npx vitest run test/openclaw-smoke-helpers.test.ts
npx vitest run test/fake-event-to-response.e2e.test.ts
npx vitest run test/jarvis-local-actions.test.ts
npx vitest run test/policy-engine.test.ts
npm run openclaw:smoke
npm run openclaw:managed:smoke
JARVIS_OPENCLAW_TRANSPORT=gateway-client npm run openclaw:managed:smoke
```

## Bottom Line

All eight criteria are now met for the shipped MVP path.

The remaining performance limitation is specific to the optional force-full-rewrite path: real OpenClaw rewrites are still too slow when operators disable budgets or benchmark every eligible response end to end.

That makes deeper OpenClaw transport optimization or a different full-fidelity execution surface the most important next engineering problem, not the shipped interactive path.