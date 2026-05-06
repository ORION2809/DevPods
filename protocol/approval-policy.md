# Approval Policy

This file describes the current approval behavior implemented in the MVP runtime.

## Risk Classes

| Risk class | Behavior |
| --- | --- |
| `immediate` | Executes immediately |
| `approval_required` | Blocks until a valid approval gesture arrives |
| `hard_approval` | Blocks until approval, announces that the action is high-risk, and is used for staged commits, pushes, deploys, deletes, and reverts |
| `denied` | Blocked by workspace policy |

## Gesture Mapping

| Input | Meaning |
| --- | --- |
| `approve_right_double_tap` | Approve pending action |
| `reject_left_double_tap` | Reject pending action |
| `both_hold_cancel` | Cancel active or pending action |

## Current Workspace Policy

The default `current_repo` workspace allows:

- `quick_status`
- `summarize_diff`
- `latest_ci_failure`
- `run_tests`
- `create_commit_message`
- `open_file`
- `commit_staged`
- `push`
- `deploy`
- `delete`
- `revert`

And it requires approval for:

- `run_tests`
- `open_file`

And it requires hard approval for:

- `commit_staged`
- `push`
- `deploy`
- `delete`
- `revert`

The default workspace config currently maps `deploy` to the local build command, while `push`, `delete`, and `revert` execute their local runtime implementations directly.

## Approval Lifecycle

1. The bridge classifies an intent.
2. If approval is required, the bridge returns an `approvalRequest` and `actionId`.
3. The pending action is stored in the session store with an expiry.
4. An approval or rejection gesture references the same session and optionally the same `actionId`.
5. If approved before expiry, the original intent executes.
6. If rejected, cancelled, or expired, the session returns to idle.

## Currently Implemented Local Actions

| Intent | Behavior |
| --- | --- |
| `quick_status` | Reports branch and local file-change summary when inside a git repo, otherwise reports workspace readiness |
| `summarize_diff` | Summarizes current local diff |
| `latest_ci_failure` | Reads the latest failed GitHub Actions run when the workspace origin remote points to GitHub and the run data is accessible |
| `run_tests` | Requires approval, then runs the configured test command and announces completion later |
| `create_commit_message` | Generates a deterministic commit message from staged changes, or falls back to working-tree changes |
| `commit_staged` | Requires hard approval, then creates a real local git commit using the deterministic staged commit subject |
| `open_file` | Requires approval, then opens the requested workspace file or the main changed file in VS Code when available |
| `push` | Requires hard approval, then pushes the current branch to its already configured upstream without opening credential prompts |
| `deploy` | Requires hard approval, then runs the configured deploy command in the background and announces completion later |
| `delete` | Requires hard approval, then deletes one explicit workspace file; protected paths such as `.git` are blocked |
| `revert` | Requires hard approval, then restores one explicit tracked workspace file back to `HEAD`; protected paths such as `.git` are blocked |

## Voice Rule

Approval prompts must remain short. The spoken output should say the action and the approval gesture, not a long explanation.
