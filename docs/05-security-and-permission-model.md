# Security And Permission Model

## Purpose

The developer-earbuds system is read-first, deny-by-default, and approval-gated.

The earbuds are a control surface and audio endpoint, not an autonomous operator.

## Threats We Are Designing Against

Minimum threat set:

- misheard voice commands
- wrong-workspace execution
- accidental destructive actions
- secret leakage into speech, display, or logs
- stale approvals being reused later
- invisible background recording
- policy bypass through the bridge or tool adapter layer

## Trust Boundaries

| Boundary | Trusted for | Never trusted for |
| --- | --- | --- |
| Earbud firmware | Gestures, wear state, battery state, approval signals | Always-on recording, tool execution, repo access |
| Bridge or companion | Mic session control, STT/TTS, policy checks, redaction, audit logging | Bypassing approval or running commands outside policy |
| Agent runtime | Intent routing, allowed workspace context, result summarization | Credential access, unrestricted shell, policy override |
| Tool adapters | Running explicit allowlisted actions | Hidden side effects, cross-workspace access, destructive mutation without approval |
| User | Final approval and cancel actions | Granting permanent blanket approval |

## Recording And Interaction Policy

- no always-on recording by default
- MVP interaction is gesture-to-talk or push-to-talk only
- listening starts only after an explicit gesture or button press and ends on timeout, cancel, or request completion
- the bridge must signal when listening starts and stops
- raw audio is processed for the current request only and is not retained by default

## Workspace And Command Scope

- the system may operate only in a user-managed workspace allowlist
- each allowed repository must define a per-repo command allowlist
- allowlists must enumerate exact commands, scripts, or named intents
- wildcard shell access is not allowed
- spoken input may select an allowlisted intent, but it must not be interpolated directly into arbitrary shell text
- commands outside the active repo allowlist are denied by default

## Permission Levels

| Level | Description | Examples |
| --- | --- | --- |
| Immediate | Low-risk read operations inside the repo allowlist | repo status, diff summary, CI status, local logs |
| Approval-required | Bounded non-destructive actions that may be slow, noisy, or context-switching | run tests, open file, create commit message |
| Hard-approval | Any destructive or externally visible action | push, deploy, delete, revert, branch rewrite |

## Approval Rules

- no destructive shell commands without approval
- deploy, push, delete, and revert always require hard approval
- hard approval must be fresh, bound to one action ID, and expire quickly if unused
- approval prompts must state the workspace, intended action, and expected side effect
- both-hold emergency cancel must immediately terminate any pending or active action flow
- policy enforcement is defense in depth: both the bridge and the agent enforce the same deny-by-default rules

## Credential And Secret Policy

- no credential reading
- the system must not read `.env` files, OS keychains, SSH keys, browser credential stores, clipboard secrets, shell history, or cloud credential files
- requests to reveal, copy, rotate, or search for credentials are denied by default
- if secret-like data appears in tool output, it must be redacted before speech, display, or logging

## Audit Logging And Redaction

- every allowed, denied, canceled, and expired action creates an audit record
- audit records include timestamp, workspace, repo, gesture source, action ID, policy decision, approval state, and outcome
- logs store redacted summaries or command identifiers rather than raw secret-bearing inputs
- audit logs are local by default and export only on explicit user action
- if redaction fails, the system fails closed and withholds the output

## Privacy And Data Retention

- collect the minimum data required for the current request
- raw audio retention is none by default
- transcript retention is in memory for the active session only unless the user explicitly saves it
- audit log retention is redacted metadata only, kept locally for a limited default window such as 30 days and clearable by the user at any time
- crash reports and diagnostics must exclude audio, credentials, and unredacted command output

## Failure Handling

- if workspace identity is unclear, the repo is not allowlisted, the command is not allowlisted, approval expires, or the policy service is unavailable, the system fails closed
- if STT confidence is low or a gesture is ambiguous, the bridge asks for confirmation instead of executing
- if audit logging or redaction is unavailable, risky actions are blocked
- if an action fails mid-flight, the system reports the failure briefly, records the partial state, and returns to a safe idle state
- emergency cancel takes precedence over queued work

## Default Policy Statement

The developer-earbuds system does not record continuously, does not read credentials, does not execute outside an allowlisted workspace and per-repo command policy, and does not perform push, deploy, delete, revert, or other destructive actions without hard approval.

Every action is auditable, redacted, time-bounded, and user-revocable.