# DevPods Tiered Local-First Agent Intelligence Plan

Generated: 2026-06-03

## Executive Verdict

DevPods should become a tiered local-first desktop product.

The product is not "GitNexus inside DevPods" and it is not "Android runs an agent." The product shape is:

```text
DevPods gives developers a voice presence layer.
OpenClaw/Hermes gives them an agent brain.
GitNexus-style intelligence gives the agent codebase understanding.
The installer lets the user choose how much power and complexity they want.
```

The architecture must preserve a lightweight Core tier that works without any agent runtime or indexing. Agent power and code intelligence should be optional upgrades, locally installed, clearly explained, and reversible.

Plan 60 remains useful as the GitNexus donor-harvest reference. This plan replaces it as the product architecture.

## Product Vision

DevPods is remote developer presence through earbuds.

The developer should be able to step away from the screen and still remain present with their work:

- ask what changed
- hear whether tests are still running
- approve or reject risky actions
- give an agent direction
- receive short progress updates
- ask for impact before touching a risky area
- stay in control without keeping the laptop in focus

The earbuds are not the brain. They are the presence surface.

The Android app is not the agent. It is the relay, pairing, voice, approval, status, and notification surface.

The desktop bridge is the boundary. It owns pairing, policy, approvals, audit, workspace allowlists, and routing.

OpenClaw/Hermes is the agent brain when the user chooses the Agent tier.

The Intelligence Layer is the agent's local codebase perception when the user chooses the Intelligence tier.

## Installation Tier Model

The installer should present DevPods as three clear choices.

| Tier | Name | What It Installs | Who It Is For |
| --- | --- | --- | --- |
| Tier 1 | DevPods Core | Android relay support, desktop bridge, local pairing, voice loop, approvals, basic git/status/diff commands | Developers who want a lightweight voice presence layer |
| Tier 2 | DevPods + OpenClaw/Hermes | Everything in Core plus an agent runtime integration | Developers who want to talk to a local agent through earbuds |
| Tier 3 | DevPods + Agent + Intelligence Layer | Everything in Tier 2 plus local codegraph/indexing/context/impact/detect_changes | Developers who want the agent to understand the codebase structurally |

### Tier 1: DevPods Core

Tier 1 must remain useful by itself.

Capabilities:

- Android relay
- desktop bridge
- voice loop
- local pairing
- approvals
- audit log
- workspace allowlist
- basic git/status/diff commands
- basic CI status where configured
- basic background task notifications

Does not require:

- OpenClaw
- Hermes
- GitNexus
- codegraph indexing
- embeddings
- persistent agent runtime

Tradeoffs:

- lowest CPU and storage footprint
- fastest setup
- least moving parts
- no deep codebase understanding
- no autonomous planning

### Tier 2: DevPods + OpenClaw/Hermes

Tier 2 adds an agent brain.

Capabilities:

- everything in Tier 1
- user can talk to OpenClaw/Hermes through earbuds
- agent can plan
- agent can report progress
- agent can ask for direction
- agent can execute approved work
- bridge remains the policy, approval, and audit boundary

Does not require:

- local codegraph indexing
- embeddings
- GitNexus-style impact analysis

Tradeoffs:

- more capable than Core
- higher CPU and memory use while agent is active
- agent quality depends on runtime configuration
- agent can reason, but without Tier 3 it has weaker codebase perception

### Tier 3: DevPods + OpenClaw/Hermes + Intelligence Layer

Tier 3 gives the agent local codebase understanding.

Capabilities:

- everything in Tier 2
- local code indexing
- codegraph search
- `query`
- `context`
- `impact`
- `detect_changes`
- `route_map`
- `tool_map`
- route and API consumer understanding
- richer planning and safer implementation recommendations

Tradeoffs:

- highest capability
- more CPU during indexing
- more disk usage for graph/index storage
- indexing can take time on large repos
- requires explicit user consent per workspace

Tier 3 should make the agent smarter. It should not make spoken responses longer or bypass any DevPods policy.

## Installer UX

The desktop installer must make the tier choice explicit.

### First Install Screen

The first install flow should offer:

| Choice | User-Facing Copy | Default |
| --- | --- | --- |
| Core | "Lightweight voice bridge. Pair Android, speak status commands, approve actions." | Recommended default |
| Agent | "Add OpenClaw/Hermes so you can talk to a local agent through DevPods." | Optional |
| Intelligence | "Add local code indexing so the agent understands impact, call paths, and changes." | Optional advanced |

Core should be the default recommendation.

Agent and Intelligence should be opt-in.

### Tradeoff Explanation

The installer should explain differences in plain terms:

| Area | Core | Agent | Intelligence |
| --- | --- | --- | --- |
| CPU | Low | Medium while agent is active | Higher during indexing |
| Storage | Small | Runtime-dependent | Adds local graph/index storage |
| Privacy | Local bridge only | Local agent runtime by default | Local code index by default |
| Setup time | Fastest | Requires runtime setup | Requires workspace indexing |
| Capability | Status, diff, approvals | Planning and approved work | Codebase-aware planning and impact |

Important copy:

- "Code indexing is local."
- "Indexing is optional."
- "You can add or remove this later."
- "DevPods Core keeps working even if agent or indexing is disabled."
- "The bridge remains the approval and audit boundary."

### Upgrade And Downgrade

The installer and desktop settings must support:

- Core -> Agent
- Agent -> Intelligence
- Intelligence -> Agent
- Agent -> Core
- disabling intelligence for one workspace
- removing local indexes
- pausing indexing
- changing the agent runtime later

Downgrades must never break Android pairing or Core voice commands.

## Component Ownership Boundaries

### Android DevPods App

Owns:

- relay service
- onboarding and pairing UI
- voice capture trigger
- STT/TTS integration
- approval notifications
- activity/status display
- health and capability display
- Wear/notification surfaces where applicable

Does not own:

- agent planning
- code indexing
- codegraph search
- policy decisions beyond local UI safeguards
- workspace execution

Android sees normal bridge responses:

- `speak`
- `display`
- `requiresApproval`
- `approvalRequest`
- `actionId`
- `status`
- `nextState`
- `followUpHint`

Android should not know whether a response came from Core, OpenClaw/Hermes, or the Intelligence Layer except through capability/status metadata.

### Desktop Bridge

Owns:

- pairing
- session state
- workspace allowlists
- request validation
- risk policy
- approvals
- audit logging
- redaction
- capability detection
- dispatch to Core, Agent, or Intelligence-backed flows

The bridge is the boundary. Nothing behind it gets to bypass policy.

The bridge should expose health that says:

- installed tier
- agent runtime availability
- intelligence availability
- index status by workspace
- degraded states
- last known capability errors

### OpenClaw/Hermes Runtime

Owns:

- planning
- progress reporting
- conversational reasoning
- implementation proposals
- approved task execution through allowed tools
- asking the user for direction

Does not own:

- final policy authority
- approval bypass
- audit bypass
- Android transport
- raw workspace access outside DevPods allowlists

OpenClaw/Hermes can consume intelligence when available, but it must degrade gracefully when it is not.

### Intelligence Layer

Owns:

- codegraph/indexing
- local code search
- context retrieval
- impact analysis
- `detect_changes`
- route/tool maps
- staleness checks
- parse cache
- ignore rules
- worker-based ingestion

Does not own:

- command execution
- approval decisions
- agent planning authority
- Android transport
- spoken response policy

The Intelligence Layer is read-only perception.

## Agent Runtime Contract

Before Workstream 3 begins, the bridge and every agent runtime must share one contract.

Canonical TypeScript location:

```text
src/agent/agent-runtime-contract.ts
```

OpenClaw and Hermes must both implement this shape. The bridge must not grow one integration path for OpenClaw and a different integration path for Hermes.

### What The Bridge Sends

The bridge sends an `AgentRuntimeRequest`.

Required contents:

- `requestId`
- `sessionId`
- `workspaceId`
- resolved `WorkspaceConfig`
- original `BridgeRequest`
- normalized user `utterance`
- request `mode`
- optional `intentHint`
- optional `approvedActionId`
- bridge-owned constraints

The constraints are the important part:

```ts
interface AgentRuntimeConstraints {
  spokenWordBudget: number;
  requiresPlanConfirmation: boolean;
  allowedIntents: readonly IntentName[];
  approvalRequiredIntents: readonly IntentName[];
  hardApprovalIntents: readonly IntentName[];
  intelligenceAvailable: boolean;
  redactionRequired: boolean;
}
```

This means the agent receives the operating envelope. It does not decide the envelope.

### What The Agent Runtime Returns

The runtime returns an `AgentRuntimeResponse`.

Required contents:

- `requestId`
- lifecycle `status`
- normal `JarvisResponse`
- optional `AgentAcknowledgement`
- optional `AgentPlanConfirmation`
- optional `AgentPlanResponse`
- optional `AgentCompletionReport`
- progress events emitted during the turn
- optional `actionId`
- optional structured error

The bridge still owns the final response validation before Android sees anything.

### Thinking Versus Done

Agent lifecycle status must be explicit:

```ts
type AgentRuntimeResponseStatus =
  | 'thinking'
  | 'awaiting_plan_confirmation'
  | 'running'
  | 'done'
  | 'blocked'
  | 'error'
  | 'cancelled';
```

Rules:

- `thinking` means the runtime accepted the request and is still reasoning.
- `awaiting_plan_confirmation` means no implementation may start yet.
- `running` means approved work is in progress.
- `done` means the agent turn is complete.
- `blocked` means policy, missing capability, missing workspace consent, or user direction is needed.
- `error` means the runtime failed and Core fallback should remain available.

### Conversation Data Structures

The gap to close is not only plan confirmation. Workstream 3 must define the full agent voice conversation as deterministic data.

The six required structures are:

- `AgentAcknowledgement`
- `AgentPlanConfirmation`
- `AgentPlanResponse`
- `AgentProgressEvent`
- existing `ApprovalRequest`
- `AgentCompletionReport`

These are product-level contract shapes. They should be reconciled into `src/agent/agent-runtime-contract.ts` during Workstream 3, but this document does not implement that code.

#### AgentAcknowledgement

```ts
interface AgentAcknowledgement {
  planId: string;
  requestId: string;
  sessionId: string;
  intentUnderstood: string;
  planningEstimateMs: number;
}
```

Purpose:

- confirms the agent heard the developer
- gives the developer confidence that planning has started
- prevents dead air while the runtime thinks

Voice example:

```text
Got it. Analysing approval flow. Plan ready in about 10 seconds.
```

#### AgentPlanConfirmation

```ts
interface AgentPlanConfirmation {
  summary: string;
  planId: string;
  stepCount: number;
  riskLevel: 'low' | 'medium' | 'high';
  affectedAreas: string[];
  requiresHardApproval: boolean;
  estimatedDurationMs: number;
  canProceedImmediately: boolean;
}
```

Purpose:

- summarizes the plan for voice
- gives Android enough metadata to show the full plan detail
- lets the bridge know whether implementation can proceed after confirmation

Low-risk voice example:

```text
3 steps. Updates session store and approval gate. Low risk. Start?
```

High-risk voice example:

```text
4 steps. Touches approval flow, relay, and policy engine. High risk. Confirm to proceed.
```

The bridge stores `planId`, maps it to `actionId` when approved, audits the decision, and enforces any approval or hard-approval rules before execution starts.

#### AgentPlanResponse

```ts
interface AgentPlanResponse {
  planId: string;
  decision: 'confirmed' | 'redirected' | 'cancelled';
  redirectUtterance?: string;
}
```

Purpose:

- captures whether the developer approved, redirected, or cancelled the plan
- keeps voice gestures and spoken redirection in one explicit bridge event
- prevents the agent from treating silence as permission

Decision mapping:

| Developer Response | Input Path | Voice | Bridge Event |
| --- | --- | --- | --- |
| Confirm | Right double tap | "Starting now." | `agent_plan_confirmed` |
| Redirect | Wake + speak | "Heard. Replanning." | `agent_plan_redirect` |
| Cancel | Both hold | "Cancelled." | `agent_plan_cancelled` |

#### AgentProgressEvent

```ts
interface AgentProgressEvent {
  planId: string;
  planningStepCompleted: number;
  totalSteps: number;
  currentStepSummary: string;
  nextStepSummary: string;
  blockingIssue?: string;
  progressPercent: number;
}
```

Purpose:

- lets the bridge report active work without noisy interruptions
- supports tap-to-check status while the developer is away
- creates a structured path for blocking questions

Default behavior:

- silent while work is progressing normally
- speak only when the user taps during active work
- interrupt only for a blocking issue, approval, failure, or completion

Tap status voice example:

```text
Step 2 of 4. Updating the session store. All good.
```

Blocking issue voice example:

```text
Stuck on step 3. Two ways to handle this. Want to hear them?
```

#### Existing ApprovalRequest

Risky execution actions keep using the existing approval system.

No new agent-only approval structure should be introduced for:

- commit
- push
- deploy
- delete
- revert
- any hard-approval action

Voice example:

```text
Commit staged files. Hard approval. 12 seconds. Approve?
```

This is the most important safety moment in the agentic loop. The agent triggers the normal policy path, the bridge creates the normal `ApprovalRequest`, Android shows the normal countdown, the earbud gesture approves or rejects, and the audit log records it exactly like a non-agent command.

#### AgentCompletionReport

```ts
interface AgentCompletionReport {
  planId: string;
  outcome: 'completed' | 'failed' | 'partial';
  completedSteps: number;
  totalSteps: number;
  summary: string;
  failureReason?: string;
  nextSuggestion?: string;
  requiresReview: boolean;
}
```

Purpose:

- closes the loop for the away-from-laptop developer
- distinguishes completed, failed, and partial outcomes
- gives Android the richer report while voice stays short

Success voice example:

```text
Done. Refactored approval flow across 4 files. Tests still passing.
```

Failure voice example:

```text
Stopped at step 3. Couldn't resolve the session store conflict. Want to try differently?
```

Partial voice example:

```text
Completed 3 of 4 steps. Push requires your approval. Ready when you are.
```

### Partial Progress Through JarvisResponse

The runtime reports progress with `AgentProgressEvent`.

Progress events are not sent directly to Android as a new protocol.

The bridge converts them into existing surfaces:

- immediate voice/display update when the user is actively waiting
- outbox event for background progress
- `JarvisResponse.autonomy` when the agent should continue, wait, or ask for direction
- desktop audit entry for execution-relevant events

This preserves the Android transport and keeps Android seeing normal `JarvisResponse` objects.

### Authority Boundary

The `AgentRuntime` contract is not an execution permission slip.

The agent may propose, explain, plan, and execute only through approved bridge paths. Policy, approvals, audit, workspace allowlists, redaction, and final response validation remain bridge-owned.

## Runtime Architecture

### Tier 1 Runtime

```text
Android DevPods App
  -> Desktop Bridge
  -> EventRouter
  -> Policy + SessionStore + AuditLog
  -> JarvisRuntime core adapters
  -> JarvisResponse
  -> Android speech/display
```

Tier 1 must work even if no agent runtime is installed.

### Tier 2 Runtime

```text
Android DevPods App
  -> Desktop Bridge
  -> Policy + approvals + audit
  -> AgentRuntime implemented by OpenClaw/Hermes
  -> approved work / progress / plan / report
  -> JarvisResponse
  -> Android speech/display
```

The bridge dispatches to the agent only when:

- the tier includes an agent runtime
- the requested intent needs agent reasoning
- policy allows the request
- approval rules are satisfied where needed

### Tier 3 Runtime

```text
Android DevPods App
  -> Desktop Bridge
  -> Policy + approvals + audit
  -> AgentRuntime implemented by OpenClaw/Hermes
      -> Intelligence Layer
          -> local codegraph / query / context / impact / detect_changes
  -> JarvisResponse
  -> Android speech/display
```

The Intelligence Layer sits behind the agent runtime.

The Android transport remains unchanged.

The bridge still returns normal `JarvisResponse` objects.

## Capability Detection

### Capability Negotiation Protocol

The bridge health response is the capability negotiation protocol between Android and desktop.

Android must not infer capability from installer choice, runtime name, or hidden state. It reads the bridge snapshot and adapts the UI.

Target shape:

```json
{
  "tier": "intelligence",
  "capabilities": {
    "core": {
      "available": true,
      "voiceLoop": true,
      "approvals": true,
      "gitBasics": true
    },
    "agent": {
      "available": true,
      "runtime": "openclaw",
      "healthy": true,
      "state": "idle",
      "degradedReason": null
    },
    "intelligence": {
      "available": true,
      "indexState": "ready",
      "workspaces": ["current_repo"],
      "currentWorkspace": {
        "workspaceId": "current_repo",
        "indexState": "ready",
        "indexedCommit": "abc123",
        "lastIndexedAtMs": 1780494300000,
        "stalenessReason": null
      }
    }
  }
}
```

Allowed `tier` values:

- `core`
- `agent`
- `intelligence`

Allowed agent states:

- `idle`
- `thinking`
- `planning`
- `awaiting_confirmation`
- `running`
- `done`
- `blocked`
- `error`
- `cancelled`

Allowed intelligence index states:

- `not_installed`
- `disabled`
- `not_indexed`
- `indexing`
- `ready`
- `stale`
- `failed`
- `paused`

### Android Display

Android should show:

- Core ready
- Agent available or unavailable
- Intelligence available, indexing, stale, or disabled
- current workspace capability
- last recovery action when degraded

Android should not show internal graph details unless the user opens diagnostics.

Android uses capability negotiation to:

- show Device tab status chips
- show or hide agent-related controls
- show or hide intelligence-specific explanations
- choose fallback copy when a runtime is unavailable
- avoid showing UI for capabilities that are not installed

### Fallback Rules

If Agent is unavailable:

- Core commands continue
- agent-specific requests get a short fallback
- Android shows "Agent unavailable" with one recovery action

If Intelligence is unavailable:

- Agent still works
- agent responses mention uncertainty only when relevant
- impact/codegraph questions fall back to git diff/status and plain workspace context

If indexing is stale:

- use available stale index for advisory answers when safe
- warn in display
- speak only if it changes the user decision

## Voice Response Design

The 24-word spoken budget remains.

The goal is smarter content, not longer speech.

### Voice Rules

- one short spoken sentence by default
- detailed evidence goes in `display`
- no raw code in speech
- no long file lists in speech
- no model caveats unless actionably relevant
- approvals state the action and gesture
- plans ask for confirmation before implementation

### Full Agent Voice Conversation Flow

The agentic loop has six distinct voice moments. Each moment needs a typed structure, a voice pattern, a word budget, and a matching Android display state.

The product rule:

```text
Voice carries the decision. Display carries the detail.
```

The developer hears enough to decide:

- confirm
- redirect
- cancel
- approve
- reject
- ignore

Android carries everything else:

- full plan steps
- affected files
- affected symbols
- line references
- risk explanation
- progress breakdown
- test results
- failure details
- next suggestions

#### Moment 1: Developer Gives Direction

Flow:

```text
Developer taps -> speaks direction -> agent acknowledges immediately -> agent plans
```

Data:

- `AgentAcknowledgement`

Voice budget:

- 8 to 16 words
- must arrive immediately
- should include what was understood and planning estimate

Voice example:

```text
Got it. Analysing approval flow. Plan ready in about 10 seconds.
```

Display:

- interpreted request
- workspace
- planning spinner
- expected time

#### Moment 2: Agent Returns A Plan

Flow:

```text
Agent finishes planning -> bridge stores plan -> Android displays full plan -> voice asks for decision
```

Data:

- `AgentPlanConfirmation`

Voice budget:

- maximum 24 words
- should include step count, top affected areas, risk level, and prompt
- should not read every step or file

Low-risk voice example:

```text
3 steps. Updates session store and approval gate. Low risk. Start?
```

High-risk voice example:

```text
4 steps. Touches approval flow, relay, and policy engine. High risk. Confirm to proceed.
```

The display layer carries the full plan, files, risk class, commands, tests, and alternatives.

Rules:

- the spoken plan summary must fit the spoken budget
- Do not read every step through earbuds by default.
- Say the riskiest area and approval gesture, not a complete implementation essay.
- If the plan is complex, offer detail on demand or show it on Android.
- Approval creates or unlocks an `actionId`; it does not bypass later hard approvals.

#### Moment 3: Developer Responds To The Plan

Flow:

```text
Developer confirms, redirects, or cancels -> bridge records decision -> agent proceeds, replans, or stops
```

Data:

- `AgentPlanResponse`

Voice budget:

- 1 to 6 words
- decision acknowledgement only

Decision mapping:

| Developer Response | Input Path | Voice | Bridge Event |
| --- | --- | --- | --- |
| Confirm | Right double tap | "Starting now." | `agent_plan_confirmed` |
| Redirect | Wake + speak | "Heard. Replanning." | `agent_plan_redirect` |
| Cancel | Both hold | "Cancelled." | `agent_plan_cancelled` |

Redirect examples:

- "Only do the relay part."
- "Skip implementation and explain the risk."
- "Run impact analysis first."
- "Use Hermes instead of OpenClaw for this task."

Display:

- confirmed plan state
- redirected utterance when present
- cancellation timestamp
- next agent state

#### Moment 4: Agent Reports Progress During Work

Flow:

```text
Approved plan runs -> progress is silent by default -> tap asks for status -> blocking issues interrupt
```

Data:

- `AgentProgressEvent`

Default behavior:

- silent while work is healthy
- no periodic spoken interruptions
- Android Activity tab updates continuously
- tap during active work returns a soft status report
- blocking issue interrupts and asks for input

Tap status voice example:

```text
Step 2 of 4. Updating the session store. All good.
```

Blocking issue voice example:

```text
Stuck on step 3. Two ways to handle this. Want to hear them?
```

Display:

- completed steps
- current step
- next step
- elapsed time
- logs or commands where safe
- blocking options when input is needed

#### Moment 5: Risky Action Approval Mid-Plan

Flow:

```text
Agent reaches risky action -> bridge policy triggers existing approval -> Android and earbuds use current approval UX
```

Data:

- existing `ApprovalRequest`

No new agent-specific approval path is allowed.

Voice budget:

- maximum 12 words where practical
- must include action, risk class, countdown, and prompt

Voice example:

```text
Commit staged files. Hard approval. 12 seconds. Approve?
```

Rules:

- right double tap approves
- reject/cancel gestures stay unchanged
- countdown UI stays unchanged
- audit log stays unchanged
- agent confidence never skips approval
- hard approval remains hard approval

#### Moment 6: Agent Completes Or Fails

Flow:

```text
Agent finishes, fails, or partially completes -> bridge emits completion report -> Android shows detail -> voice closes loop
```

Data:

- `AgentCompletionReport`

Voice budget:

- maximum 24 words
- should include outcome, count/area changed, test state or failure reason, and next choice when useful

Success voice example:

```text
Done. Refactored approval flow across 4 files. Tests still passing.
```

Failure voice example:

```text
Stopped at step 3. Couldn't resolve the session store conflict. Want to try differently?
```

Partial voice example:

```text
Completed 3 of 4 steps. Push requires your approval. Ready when you are.
```

Display:

- completed steps
- skipped or failed steps
- changed files
- test results
- failure reason
- next suggestion
- review-required flag

### Plan Confirmation Examples

| Plan Situation | Speak | Display Direction |
| --- | --- | --- |
| Small safe plan | "Plan ready: update the relay fallback, then run bridge tests. Right double tap to approve." | Two-step plan, files, test command |
| Multi-file refactor | "Six-step plan ready. Main risk is approval state. Review on phone, or right double tap to approve." | Full plan, risk, affected files, test matrix |
| Needs redirect | "I can do that, but pairing and audit both change. Say redirect, or approve from the phone." | Safer alternatives and why |
| Hard approval ahead | "Plan ready, but deploy remains hard-approval gated. I can prepare changes only." | Plan split into prepare versus deploy |

### Example Responses

| Situation | Speak | Display Direction |
| --- | --- | --- |
| Core status | "Feature branch. Four files changed. No tests running." | Branch, changed files, main file, test/CI state |
| Agent update | "Still working. Tests are running, and no approval is needed yet." | Task progress, current step, elapsed time |
| Agent opinion | "I would fix the pairing fallback first; it blocks the cleanest path." | Reasoning, files, alternative path |
| Confirm direction | "I can implement that plan. Right double tap to approve." | Full plan, risk class, affected workspace |
| Start implementation | "Starting the approved change. I will report when tests finish." | Action id, command/task state |
| Impact analysis | "High impact: 12 callers, mostly approval and relay state. Review tests first." | Full blast radius, affected processes, files |
| What changed | "Three areas changed: branding, Wear tile, and proof reports." | Changed symbols, files, affected flows |

## Response Formatter Design

Add a formatter boundary for Intelligence and agent conversation output:

```ts
interface CodeIntelligenceVoiceFormatter {
  formatAcknowledgement(input: AgentAcknowledgement, context: VoiceContext): string;
  formatPlanConfirmation(input: AgentPlanConfirmation, context: VoiceContext): string;
  formatProgressUpdate(input: AgentProgressEvent, context: VoiceContext): string;
  formatCompletionReport(input: AgentCompletionReport, context: VoiceContext): string;
  formatBlockingIssue(input: AgentProgressEvent, context: VoiceContext): string;
  formatImpact(result: ImpactResult, context: VoiceContext): JarvisResponseDraft;
  formatContext(result: SymbolContextResult, context: VoiceContext): JarvisResponseDraft;
  formatChanges(result: DetectChangesResult, context: VoiceContext): JarvisResponseDraft;
  formatQuery(result: CodeQueryResult, context: VoiceContext): JarvisResponseDraft;
}
```

The agent conversation formatter methods return spoken strings.

Contract:

- every returned spoken string must fit the 24-word budget
- acknowledgement should usually be 8 to 16 words
- plan response acknowledgement should usually be 1 to 6 words
- blocking issue should include the choice point, not all details
- completion report should include outcome and next useful action

The intelligence formatter methods convert rich graph results into:

- short `speak`
- richer `display`
- optional `followUpHint`
- optional approval or confirmation prompt if the next step is implementation

The bridge combines formatter output with the typed structure:

- `speak` gets the short voice line
- `display` gets the full detail
- `autonomy` captures whether the agent continues, waits, or needs direction
- `approvalRequest` remains the existing safety path for risky actions

### Blast Radius Format

Blast radius speech should include:

- count
- riskiest area
- next safe action

Example:

```text
High impact: 12 callers, mostly approval and relay state. Review tests first.
```

Display can include:

- risk level
- direct callers
- affected modules
- affected execution flows
- test suggestions
- stale index warning if applicable

### Detect Changes Format

Change review speech should include:

- number of changed areas
- highest-risk area
- whether implementation is safe to continue

Example:

```text
Five changes affect two flows. Pairing is the riskiest area.
```

### Query And Context Format

Query/context speech should answer the user directly:

```text
Pairing starts in the bridge page, then Android verifies and stores the relay token.
```

Display should carry:

- files
- symbols
- process steps
- links or line references where available

## Index State UX

Indexing should be visible but not noisy.

### Spoken Behavior

When indexing starts:

```text
Indexing this workspace locally. Core commands still work.
```

After that:

- no repeated spoken indexing updates
- completion can be a soft notification, not an interruption
- failures should be shown in status unless the user explicitly asked for an intelligence action

### Android Status

Android should show:

- Intelligence disabled
- Indexing
- Ready
- Stale
- Failed
- Paused

### Desktop Status

Desktop should show:

- indexed workspaces
- index size
- last indexed time
- current commit or staleness
- indexing progress
- pause/resume/delete index controls

### Nonblocking Rule

Indexing never blocks:

- wake
- push-to-talk
- quick status
- summarize diff
- CI status
- approvals
- cancel
- pause/resume

If an intelligence request arrives while indexing:

```text
Indexing is still running. I can answer from git status for now.
```

## Safety Model

### Policy Boundary

The bridge remains the policy boundary in all tiers.

The Intelligence Layer cannot:

- execute commands
- approve actions
- bypass hard approvals
- write files
- change workspace allowlists
- suppress audit records

### Agent Planning

Agent plans require confirmation before implementation.

High-risk implementation actions require approval even when:

- the agent is confident
- impact analysis is low risk
- the user previously approved a similar task

### Intelligence Is Read-Only

GitNexus-style intelligence is perception only.

It can say:

- what changed
- what depends on a symbol
- what flow is affected
- which route consumers may break
- what files deserve tests

It cannot decide:

- to execute
- to commit
- to push
- to deploy
- to delete
- to revert

## GitNexus Donor Strategy

Plan 60 correctly identified useful GitNexus donor areas.

This plan changes where those capabilities live.

They should be harvested or integrated behind OpenClaw/Hermes, not placed directly inside Android and not installed silently with Core.

### Useful Donor Capabilities

Harvest or integrate:

- `query`
- `context`
- `impact`
- `detect_changes`
- `route_map`
- `tool_map`
- parsing
- graph storage
- search
- ignore rules
- staleness checks
- parse cache
- worker-based ingestion

### Product Placement

The Intelligence Layer should expose these capabilities to:

- OpenClaw/Hermes runtime
- bridge health and diagnostics
- desktop settings/status

The Android app should only receive final bridge responses.

### Runtime Dependency Choice

Do not keep `vendor-sources/GitNexus-main` as a permanent runtime dependency unless that is an explicit distribution decision.

Preferred path:

- use the folder as donor/reference
- internalize the useful pieces into an owned DevPods intelligence package
- keep provenance notes and relevant parity tests
- remove runtime reliance on the donor folder

Allowed temporary path:

- use the donor package as an internal development sidecar while harvesting
- never make this invisible to the installer or user

### Provenance And Licensing

Before distribution:

- record donor source and version
- preserve required notices
- confirm usage rights for the selected release model
- document whether the Intelligence Layer is internalized, bundled, or separately installed

## Installer Capability Matrix

| Capability | Core | Agent | Intelligence |
| --- | --- | --- | --- |
| Android relay | Yes | Yes | Yes |
| Desktop bridge | Yes | Yes | Yes |
| Local pairing | Yes | Yes | Yes |
| Approvals | Yes | Yes | Yes |
| Basic git/status/diff | Yes | Yes | Yes |
| Talk to OpenClaw/Hermes | No | Yes | Yes |
| Agent planning | No | Yes | Yes |
| Agent progress reports | No | Yes | Yes |
| Approved implementation | No | Yes | Yes |
| Code indexing | No | No | Yes |
| Codegraph query/context | No | No | Yes |
| Impact analysis | No | No | Yes |
| Detect affected changes | No | No | Yes |

## Implementation Workstreams

### Dependency Order

The implementation order is not optional.

```text
Workstream 1: Tier metadata and capability negotiation
    -> required before all other workstreams

Workstream 2: Installer tier selection
    -> may run in parallel with Workstreams 3 and 4 after Workstream 1

Workstream 3: Agent runtime boundary
    -> required before Workstream 5

Workstream 4: Intelligence layer boundary
    -> required before Workstream 5

Workstream 5: GitNexus donor harvest
    -> requires Workstreams 3 and 4 contracts

Workstream 6: UX and safety proof
    -> requires Workstreams 1 through 5
```

Do not start GitNexus harvesting until the agent and intelligence interfaces are defined. Otherwise the donor code will shape the architecture instead of fitting behind it.

### Workstream 1: Tier Metadata

Add tier and capability state to bridge health.

Outputs:

- tier enum
- `BridgeCapabilitySnapshot`
- capability negotiation JSON shape
- Android capability display
- desktop status model
- fallback copy for unavailable capabilities

### Workstream 2: Installer Tier Selection

Build desktop installer choices:

- Core
- Agent
- Intelligence

Outputs:

- clear user-facing tradeoff copy
- optional dependency install gates
- upgrade/downgrade paths
- per-workspace intelligence consent

### Workstream 3: Agent Runtime Boundary

Make OpenClaw/Hermes the agent brain behind the bridge.

Outputs:

- `AgentRuntime` TypeScript interface
- `AgentRuntimeRequest`
- `AgentRuntimeResponse`
- `AgentAcknowledgement`
- `AgentPlanConfirmation`
- `AgentPlanResponse`
- `AgentProgressEvent`
- `AgentCompletionReport`
- six-moment voice conversation state machine
- agent capability detector
- degraded/fallback states
- plan confirmation storage and approval mapping
- redirect and cancellation handling for active plans
- approved work execution path through existing policy
- mid-plan risky actions routed through existing `ApprovalRequest`

### Workstream 4: Intelligence Layer Boundary

Put code intelligence behind the agent runtime.

Outputs:

- intelligence API consumed by `AgentRuntime`
- intelligence capability detector
- index state model
- graph query APIs
- `CodeIntelligenceVoiceFormatter`
- `formatAcknowledgement()`
- `formatPlanConfirmation()`
- `formatProgressUpdate()`
- `formatCompletionReport()`
- `formatBlockingIssue()`
- no Android-specific intelligence code
- read-only guarantee for graph/query operations

### Workstream 5: GitNexus Donor Harvest

Use Plan 60 as the donor checklist.

Start only after:

- Workstream 3 defines the agent contract
- Workstream 4 defines the intelligence contract
- Workstream 1 defines capability negotiation

Outputs:

- harvested query/context/impact/detect_changes
- route_map/tool_map where useful
- ignore rules
- staleness checks
- parse cache
- worker ingestion
- provenance and parity tests

### Workstream 6: UX And Safety Proof

Prove each tier behaves correctly.

Outputs:

- Core works with no agent or index
- Agent works with no index
- Intelligence enhances agent output
- indexing never blocks Core
- approvals remain enforced
- Android capability UI degrades correctly across all tier states
- plan confirmation is usable through earbuds and Android display

## Acceptance Criteria

### Tier 1: DevPods Core

Core is complete when:

- Android pairs with the desktop bridge
- bridge health returns `tier: "core"` with the capability negotiation shape
- voice loop works
- status/diff/basic CI commands work
- approvals work
- audit logging works
- no OpenClaw/Hermes install is required
- no code index exists or is required
- disabling Agent and Intelligence does not degrade Core

### Tier 2: DevPods + OpenClaw/Hermes

Agent tier is complete when:

- installer can enable an agent runtime
- bridge health returns `tier: "agent"` with agent runtime availability
- Android shows agent availability
- OpenClaw/Hermes implements `AgentRuntime`
- bridge sends `AgentRuntimeRequest` with policy constraints
- agent returns `AgentRuntimeResponse` with normal `JarvisResponse`
- developer direction returns immediate `AgentAcknowledgement`
- user can ask the agent for a plan
- implementation plans return `AgentPlanConfirmation`
- developer can confirm, redirect, or cancel through `AgentPlanResponse`
- plan confirmation works through earbuds and Android display
- partial progress returns through `AgentProgressEvent` and bridge-managed `JarvisResponse`/outbox surfaces
- active work is silent by default and reports progress on tap
- blocking issues interrupt with a short decision prompt
- completion, failure, and partial outcomes return `AgentCompletionReport`
- risky actions still require approval
- mid-plan commit/push/delete/deploy/revert actions use existing `ApprovalRequest`
- agent progress reports return normal `JarvisResponse`
- fallback is clear when agent runtime is unavailable
- every agent spoken line stays within the 24-word budget
- Android Activity tab shows the full detail for every truncated voice summary

### Tier 3: DevPods + Agent + Intelligence

Intelligence tier is complete when:

- installer requires explicit intelligence opt-in
- user grants workspace indexing consent
- bridge health returns `tier: "intelligence"` with index state and indexed workspaces
- indexing never blocks Core commands
- OpenClaw/Hermes can consume query/context/impact/detect_changes
- intelligence APIs sit behind `AgentRuntime`, not Android
- blast radius responses use the formatter budget
- `CodeIntelligenceVoiceFormatter` formats acknowledgement, plan, progress, blocking issue, and completion voice lines
- Android receives ordinary `JarvisResponse`
- codegraph cannot execute or approve anything
- donor provenance and distribution checks are documented
- GitNexus donor harvest starts only after agent and intelligence contracts are stable

## Final Product Rule

Core must stay light.

Agent must stay behind the bridge.

Intelligence must stay behind the agent.

Android must stay a relay and presence surface.

The bridge must remain the policy, approval, and audit boundary.

That gives DevPods a clear product ladder: lightweight voice presence first, agent collaboration second, codebase-aware intelligence third.
