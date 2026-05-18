# Hermes + Claude Fulcrum Integration Plan

Date: 2026-05-18

## Objective

Make Hermes Agent a first-class runtime for Claude Fulcrum so it can use Fulcrum's capabilities intentionally and safely:

- 26 Fulcrum specialized agents
- 122 Fulcrum skills
- 66 Fulcrum commands
- Fulcrum rules, verification workflows, memory patterns, MCP servers, code-review graph, and cross-platform harness guidance
- Hermes skills, sessions, memory, profiles, cron, hooks, ACP, MCP, gateway, dashboard, and self-improving skill loop

The goal is not to copy everything into Hermes or enable every Hermes tool. The goal is a clean integration where Fulcrum remains the source of truth for development workflows, while Hermes provides a persistent, learning, automatable agent surface.

## Current State

Verified locally from `c:\Users\ShreyasSuvarna\Desktop\its_mine\claude-flows`:

- `hermes` is installed at `C:\Users\ShreyasSuvarna\AppData\Local\hermes\hermes-agent\venv\Scripts\hermes.exe`.
- `hermes-agent` is also installed, but `hermes-agent --help` appears to start an agent turn instead of showing help. Prefer the `hermes` command for automation.
- Hermes version is `Hermes Agent v0.14.0 (2026.5.16)`.
- Hermes project path is `C:\Users\ShreyasSuvarna\AppData\Local\hermes\hermes-agent`.
- Python is `3.11.15`.
- OpenAI SDK is `2.24.0`.
- Hermes home is `C:\Users\ShreyasSuvarna\AppData\Local\hermes`.
- `hermes -z "Reply with exactly OK."` returned `OK`.
- Hermes config is valid and at config version `23`.
- Hermes default profile is `default`.
- Default model is `qwen3-coder:30b`.
- Default provider is a custom OpenAI-compatible local endpoint at `http://127.0.0.1:11434/v1`.
- Gateway is stopped.
- Messaging platforms are not configured.
- MCP servers are not configured.
- Shell hooks are not configured.
- Scheduled jobs count is `0`.
- Hermes reports `6` stored sessions.
- Hermes reports no active security advisories.
- Hermes has built-in memory active, with no external memory provider configured.
- Hermes curator is enabled, with no agent-created skills yet.
- `hermes skills list` reports `74` built-in skills enabled, `0` hub-installed, and `0` disabled.
- Filesystem scan found `87` `SKILL.md` files in the Hermes home skills tree and `87` in the bundled source skills tree.
- Hermes plugins are present but not enabled by default.
- Hermes CLI toolsets currently enabled include web, browser, terminal, file, code execution, vision, image generation, TTS, skills, todo, memory, session search, clarify, delegation, cronjob, messaging, and computer use.
- Doctor reports some enabled toolsets still lack requirements or credentials, especially web search providers, image/video providers, messaging providers, Discord/Telegram packages, X search, and some platform integrations.
- Hermes terminal backend is local with `cwd: "."`, `timeout: 180`, `lifetime_seconds: 300`, and Docker cwd mounting disabled.

Existing Fulcrum assets to integrate:

- `AGENTS.md`
- `agents/`
- `skills/`
- `.agents/skills/`
- `commands/`
- `.mcp.json`
- `mcp-configs/mcp-servers.json`
- `.codex/config.toml`
- `.crush/crush.json`
- `.opencode/opencode.json`
- `docs/OPENCLAW_FULCRUM_INTEGRATION_PLAN.md`

## Target Architecture

Hermes should become a persistent Fulcrum-compatible operator runtime:

```text
CLI / TUI / Dashboard / Gateway / ACP / MCP
                  |
             Hermes Agent
                  |
   profiles + toolsets + memory + sessions + cron
                  |
      Fulcrum capability registry, read-only
                  |
  skills + commands + agents + MCP + verification
                  |
 Codex / Claude CLI / OpenCode / Crush / local tools
```

Key design choices:

- Fulcrum repo remains the source of truth for Fulcrum skills, commands, agents, rules, and MCP definitions.
- Hermes discovers Fulcrum skills through `skills.external_dirs`, not by duplicating the skill tree into Hermes.
- Hermes-created skills remain in `~/.hermes/skills/`; Fulcrum skills stay read-only.
- Fulcrum commands map first to Hermes skill slash commands. Only commands that need richer dispatch get wrappers.
- High-risk toolsets stay disabled or profile-gated until credentials, sandboxing, and allowlists are configured.
- Hermes profiles represent operational roles, while Fulcrum agent markdown remains the role prompt source.
- Hermes ACP/MCP are used as bridges, not as a second source of truth.

## Phase 0: Freeze And Back Up

Context brief:
Hermes is already usable, but its home contains config, secrets, sessions, skills, auth, and logs. Back up before changing integration settings.

Tasks:

1. Create a quick Hermes restore point:

   ```powershell
   hermes backup --quick --label pre-fulcrum-integration
   ```

2. Create a full Hermes backup:

   ```powershell
   hermes backup --label pre-fulcrum-integration-full
   ```

3. Save non-secret diagnostics:

   ```powershell
   hermes --version
   hermes status
   hermes doctor
   hermes config check
   hermes skills list
   hermes skills check
   hermes mcp list
   hermes tools --summary list
   hermes hooks list
   hermes profile list
   ```

4. Redact any API keys, tokens, auth files, `.env`, and provider credentials before writing diagnostics into repo docs.
5. Record the current Fulcrum git status so unrelated user work is not mixed with integration changes.

Exit criteria:

- A Hermes backup exists and can be restored with `hermes import`.
- Current config and status are documented without secrets.
- No integration changes have been made yet.

Rollback:

- Restore the backup with `hermes import <backup.zip>`.

## Phase 1: Secure The Baseline

Context brief:
Hermes currently has many powerful toolsets enabled. Some are not fully configured, and the default model is a local 30B model. Before adding Fulcrum capabilities, reduce blast radius.

Tasks:

1. Keep the gateway stopped until channel allowlists are defined.
2. Keep messaging integrations disabled until allowed users are configured for the target platform.
3. Decide whether the default CLI should remain local or move to Docker/SSH for isolation:
   - Local is fastest but broadest.
   - Docker is better for untrusted tasks.
   - SSH is better for remote isolated compute.
4. Keep `terminal.docker_mount_cwd_to_workspace: false` unless a specific Docker workflow needs it.
5. Disable toolsets that are enabled but not ready:

   ```powershell
   hermes tools disable web image_gen messaging computer_use
   ```

   Re-enable each only after its provider, permissions, and use case are verified.
6. Keep `--yolo` out of scripts and docs.
7. Do not use `--accept-hooks` in automation until hooks are reviewed and allowlisted.
8. Configure model fallback deliberately:
   - Use the local model for low-risk, local-only tasks.
   - Use a stronger provider for tool-enabled coding, security review, and untrusted input.
9. Run a smoke test after any model/provider change:

   ```powershell
   hermes -z "Reply with exactly OK."
   ```

Verification:

```powershell
hermes doctor
hermes tools --summary list
hermes status
```

Exit criteria:

- Hermes doctor has no urgent security findings.
- Toolsets with missing requirements are disabled or intentionally documented.
- Local model is not the default for high-risk automated coding unless the terminal backend is isolated.

Rollback:

- Re-enable prior tools from the Phase 0 diagnostics or restore the backup.

## Phase 2: Add Fulcrum Skills As Read-Only External Skill Roots

Context brief:
Hermes supports `skills.external_dirs` in `config.yaml`. External skills are scanned read-only and appear in the system prompt, `skills_list`, `skill_view`, and slash commands. This is the cleanest path for Fulcrum skills.

Tasks:

1. Add Fulcrum skill roots to Hermes config:

   ```yaml
   skills:
     external_dirs:
       - C:/Users/ShreyasSuvarna/Desktop/its_mine/claude-flows/skills
       - C:/Users/ShreyasSuvarna/Desktop/its_mine/claude-flows/.agents/skills
   ```

2. Keep `skills.inline_shell: false`.
3. Keep `skills.template_vars: true`.
4. Keep `skills.guard_agent_created` off unless you want extra friction for agent-created skills.
5. Do not copy Fulcrum skills into `~/.hermes/skills/` unless a skill must be forked for Hermes-specific behavior.
6. Record any naming collisions between Hermes built-ins and Fulcrum skills. Hermes local skills take precedence over external skills.

Verification:

```powershell
hermes config check
hermes skills list
hermes -z "List the available skills related to test-driven development."
```

Manual smoke tests:

```text
/tdd-workflow explain the workflow
/verification-loop create a verification checklist for this repo
/security-review list the security review phases
```

Exit criteria:

- Fulcrum skills appear in Hermes skill discovery.
- Core Fulcrum skills are invokable from Hermes.
- Fulcrum skills remain read-only from Hermes' perspective.

Rollback:

- Remove the two `skills.external_dirs` entries.

## Phase 3: Normalize Skill Compatibility

Context brief:
Fulcrum skills use `SKILL.md` and YAML frontmatter, which Hermes supports. Some Fulcrum skills may reference Codex/Claude-specific tooling or MCP servers that Hermes does not have yet.

Tasks:

1. Create a compatibility report for all Fulcrum skills:
   - parses YAML frontmatter
   - checks `name`
   - checks `description`
   - detects duplicate names
   - detects missing referenced files
   - detects unsupported required tools
   - detects shell snippets or dangerous setup notes
2. Classify skills:
   - Works as-is in Hermes
   - Works after MCP/tool setup
   - Needs Hermes wrapper
   - Should stay Codex/Claude-only
3. Add Hermes-specific wrappers only for skills that genuinely need them.
4. Prefer small wrapper skills in `~/.hermes/skills/fulcrum-wrappers/` that point back to the Fulcrum source files.
5. Keep a generated manifest:

   ```text
   docs/generated/hermes-fulcrum-skill-parity.md
   ```

Verification:

```powershell
hermes skills list
hermes skills audit
npm run harness:audit
```

Exit criteria:

- Every Fulcrum skill has a compatibility status.
- Core coding, testing, security, docs, and research skills are usable.
- No external skill uses inline shell execution by default.

Rollback:

- Remove wrapper skills and keep only external skill roots.

## Phase 4: Map Fulcrum Commands To Hermes Slash Commands

Context brief:
Hermes automatically exposes skills as slash commands. Fulcrum has 66 command markdown files, many of which are workflow prompts that can map to skills or thin wrappers.

Tasks:

1. Build a command inventory from `commands/*.md`.
2. Map each command to one of four paths:
   - Direct Hermes skill command.
   - Hermes wrapper skill.
   - Hermes native command extension.
   - Unsupported or unnecessary in Hermes.
3. Prioritize core Fulcrum commands:
   - `plan`
   - `tdd`
   - `verify`
   - `code-review`
   - `security`
   - `build-fix`
   - `e2e`
   - `orchestrate`
   - `checkpoint`
   - `memory-search`
   - `code-graph-build`
   - `code-graph-review`
4. Avoid editing Hermes source command registry unless absolutely needed.
5. Use wrapper skills for command parity first because wrappers survive Hermes updates better than source edits.
6. Add a generated parity table:

   ```text
   docs/generated/hermes-fulcrum-command-parity.md
   ```

Verification:

```powershell
hermes -z "What slash commands or skills are available for code review?"
```

Manual smoke tests:

```text
/plan create a plan for integrating Fulcrum with Hermes
/test-driven-development explain the red-green-refactor loop
/requesting-code-review review this repository's current git diff
```

Exit criteria:

- The important Fulcrum command workflows can be run from Hermes.
- Command names and behavior are documented.
- Wrapper commands are clearly marked as Fulcrum-backed.

Rollback:

- Delete wrapper skills and rely on direct Fulcrum skill names.

## Phase 5: Configure Fulcrum MCP Baseline In Hermes

Context brief:
Hermes has no MCP servers configured. Fulcrum already defines a baseline in `.mcp.json`, `.codex/config.toml`, `.crush/crush.json`, and `mcp-configs/mcp-servers.json`.

Tasks:

1. Add safe baseline MCP servers:

   ```powershell
   hermes mcp add context7 --command npx --args -y @upstash/context7-mcp@latest
   hermes mcp add memory --command npx --args -y @modelcontextprotocol/server-memory
   hermes mcp add sequential-thinking --command npx --args -y @modelcontextprotocol/server-sequential-thinking
   hermes mcp add playwright --command npx --args -y @playwright/mcp --browser chrome
   hermes mcp add code-review-graph --command uvx --args code-review-graph serve
   hermes mcp add claude-flow --command npx --args -y claude-flow@alpha mcp start
   ```

2. Add optional MCPs only after secrets are configured:
   - GitHub
   - Exa
   - Firecrawl
   - Vercel
   - Cloudflare
   - fal.ai
3. Do not add placeholder secrets like `YOUR_KEY_HERE`.
4. Use environment variables or Hermes `.env` for secret-backed MCPs.
5. Disable or omit MCP tools that duplicate high-risk native Hermes tools unless there is a specific benefit.

Verification:

```powershell
hermes mcp list
hermes mcp test context7
hermes mcp test memory
hermes mcp test code-review-graph
```

Exit criteria:

- Hermes MCP list matches the Fulcrum baseline.
- Each configured MCP either tests successfully or has a documented missing dependency.
- No MCP secrets are committed to the repo.

Rollback:

```powershell
hermes mcp remove <name>
```

## Phase 6: Represent Fulcrum Agents With Hermes Profiles And Skills

Context brief:
Hermes does not need to duplicate every Fulcrum agent as a separate source-level agent. Use Hermes profiles, preloaded skills, toolsets, and worktrees to represent agent roles.

Tasks:

1. Create role profiles:
   - `fulcrum-main`
   - `fulcrum-coding`
   - `fulcrum-review`
   - `fulcrum-security`
   - `fulcrum-research`
   - `fulcrum-docs`
2. Each profile should define:
   - model/provider
   - terminal backend
   - enabled toolsets
   - preloaded skills
   - working directory
   - description
3. Map Fulcrum agents to Hermes role profiles:

   | Fulcrum role | Hermes representation |
   | --- | --- |
   | planner | `fulcrum-main` + `plan` or `blueprint` skill |
   | architect | `fulcrum-main` + architecture/design skills |
   | tdd-guide | `fulcrum-coding` + `tdd-workflow` |
   | code-reviewer | `fulcrum-review` + code review skills |
   | security-reviewer | `fulcrum-security` + security skills |
   | build-error-resolver | `fulcrum-coding` + terminal/file tools |
   | e2e-runner | `fulcrum-coding` + Playwright MCP/tool |
   | doc-updater | `fulcrum-docs` + documentation skills |
   | code-graph-reviewer | `fulcrum-review` + code-review-graph MCP |

4. Use `--worktree` for parallel coding agents when editing the same repo.
5. Keep review/security profiles read-mostly.
6. Enable delegation and mixture-of-agents only after role profiles have safe tool boundaries.

Verification:

```powershell
hermes profile list
hermes --profile fulcrum-review -z "Explain how you would review this repo without editing files."
hermes --profile fulcrum-coding --worktree -z "Reply with exactly OK."
```

Exit criteria:

- Fulcrum agent roles have Hermes equivalents.
- Coding profiles can edit only in intended workspaces or worktrees.
- Review/security profiles do not casually write files.

Rollback:

- Delete created profiles with `hermes profile delete <name>`.

## Phase 7: Runtime Routing And Provider Policy

Context brief:
Hermes can use local endpoints, OpenRouter, Gemini, Codex, Claude Code, OpenCode, ACP, and other providers. The current local model is useful, but not enough for every Fulcrum workflow.

Tasks:

1. Keep local `qwen3-coder:30b` for low-risk local tasks and drafts.
2. Configure a stronger coding/reasoning provider for:
   - security reviews
   - multi-file refactors
   - code generation with broad tool access
   - untrusted input handling
3. Decide provider priority:
   - Codex for coding tasks when authenticated.
   - Claude CLI/ACP for Claude-specific workflows.
   - OpenRouter/Gemini for general reasoning if already configured.
   - Local endpoint for private/offline low-risk work.
4. Configure fallback providers with `hermes fallback`.
5. Keep all provider secrets in Hermes `.env` or auth profiles.
6. Document runtime selection in a small runbook:

   ```text
   docs/generated/hermes-runtime-routing.md
   ```

Verification:

```powershell
hermes status
hermes fallback list
hermes -z "Reply with exactly OK."
```

Exit criteria:

- Every high-risk Fulcrum workflow has a strong runtime.
- Local small model is not silently used for security-sensitive automation.
- Provider failures have a fallback path.

Rollback:

- Reset model/provider through `hermes model` or restore the backup.

## Phase 8: Memory, Sessions, And Knowledge Flow

Context brief:
Hermes has built-in memory, session search, and a self-improving skill loop. Fulcrum has memory/learning skills and documentation. These should complement each other rather than create duplicate truth.

Tasks:

1. Keep Hermes built-in memory active.
2. Do not bulk-import Fulcrum docs into Hermes memory.
3. Store only stable operational learnings:
   - integration decisions
   - successful commands
   - known local quirks
   - runtime routing choices
4. Use Fulcrum docs as source files and Hermes memory as learned context.
5. Decide whether to enable an external memory provider only after baseline integration works.
6. Use session export for evidence when debugging integration:

   ```powershell
   hermes sessions list
   hermes sessions export <session-id>
   ```

Verification:

```powershell
hermes memory status
hermes sessions list
```

Exit criteria:

- Hermes remembers stable integration facts without duplicating large docs.
- Session search can find prior integration decisions.
- Sensitive data is not written into memory.

Rollback:

- Use `hermes memory reset` only if you intentionally want to erase built-in memory.

## Phase 9: Hooks, Cron, Curator, And Automation

Context brief:
Hermes currently has no shell hooks and no cron jobs. Curator is enabled but has no agent-created skills to maintain.

Tasks:

1. Keep hooks disabled until their command bodies are reviewed.
2. If hooks are added, use:
   - narrow matchers
   - explicit timeouts
   - no secrets in command strings
   - no `--accept-hooks` until allowlisted
3. Add cron only after Phase 1 security is complete.
4. Candidate cron jobs:
   - weekly `hermes doctor`
   - weekly Fulcrum harness audit
   - code-review graph refresh
   - dependency audit
   - session/backup health check
5. Let curator manage only Hermes-created skills, not Fulcrum external skill roots.
6. Do not let curator rewrite external Fulcrum skills.

Verification:

```powershell
hermes hooks doctor
hermes cron list
hermes curator status
```

Exit criteria:

- Automations are explicit, owner-approved, and reversible.
- Fulcrum external skills remain read-only.
- Cron outputs have a clear destination.

Rollback:

- Remove hook entries from `config.yaml`.
- Delete cron jobs with `hermes cron`.

## Phase 10: OpenClaw Migration Boundary

Context brief:
Hermes can migrate from OpenClaw using `hermes claw migrate`. This is related to but separate from Fulcrum integration. Use it only when you want Hermes to inherit OpenClaw state.

Tasks:

1. Preview migration first:

   ```powershell
   hermes claw migrate --dry-run --source C:/Users/ShreyasSuvarna/.openclaw
   ```

2. Use `--preset user-data` first unless you explicitly want a full migration.
3. Do not use `--migrate-secrets` until the preview is reviewed.
4. If applying migration, keep the default backup behavior.
5. Route migrated skills under `~/.hermes/skills/openclaw-imports/`.
6. Keep OpenClaw/Fulcrum/Hermes docs separate:
   - OpenClaw plan: `docs/OPENCLAW_FULCRUM_INTEGRATION_PLAN.md`
   - Hermes plan: this document

Verification:

```powershell
hermes claw migrate --dry-run
hermes skills list
hermes config check
```

Exit criteria:

- OpenClaw state is imported only if useful.
- No secrets are migrated without explicit intent.
- Migrated OpenClaw skills do not shadow Fulcrum skills accidentally.

Rollback:

- Restore the Hermes backup made before migration.

## Phase 11: Build A Hermes/Fulcrum Drift Auditor

Context brief:
Once Hermes points at Fulcrum skill roots and MCP definitions, drift can creep in quietly. Add an audit layer.

Tasks:

1. Extend `scripts/harness-audit.js` or add a focused script.
2. Check:
   - Hermes config includes Fulcrum `skills.external_dirs`.
   - Hermes MCP baseline matches Fulcrum `.mcp.json`.
   - Core Fulcrum skills are visible to Hermes.
   - Core Fulcrum command wrappers exist or map to direct skills.
   - Hermes profile roles exist.
   - High-risk toolsets are disabled or isolated.
   - Gateway is stopped unless channel allowlists exist.
3. Output each finding with:
   - status
   - summary
   - risk
   - next action
   - artifact path
4. Add tests for the auditor.

Verification:

```powershell
npm run harness:audit
npm test
```

Exit criteria:

- Drift is visible before a user discovers a broken workflow.
- The audit is non-mutating by default.
- The audit can be run from Hermes, Codex, or normal shell.

Rollback:

- Keep the auditor read-only until its output is stable.

## Phase 12: End-To-End Acceptance Tests

Context brief:
Integration is complete only when Hermes can actually use Fulcrum workflows.

Test matrix:

1. Core Hermes:
   - `hermes -z "Reply with exactly OK."` returns `OK`.
   - `hermes doctor` has no urgent issues.
2. Skills:
   - Hermes sees Fulcrum `tdd-workflow`.
   - Hermes sees Fulcrum `verification-loop`.
   - Hermes sees Fulcrum `security-review`.
3. Commands:
   - `/plan` or wrapper equivalent creates a plan.
   - `/verify` or wrapper equivalent creates a verification checklist.
   - `/code-review` or wrapper equivalent performs read-only review.
4. MCP:
   - Context7 test passes.
   - Memory MCP test passes.
   - Code-review graph test passes or reports a missing dependency clearly.
5. Profiles:
   - `fulcrum-review` does not edit files.
   - `fulcrum-coding` can work in a worktree.
   - `fulcrum-security` uses a strong model/provider.
6. Security:
   - Gateway remains stopped or channel allowlists are configured.
   - Hooks are empty or consent-allowlisted.
   - No secrets appear in docs, git diff, or generated reports.
7. Fulcrum repo:
   - `npm run harness:audit` passes.
   - `npm test` passes or failures are documented as unrelated/pre-existing.

Verification commands:

```powershell
hermes -z "Reply with exactly OK."
hermes doctor
hermes config check
hermes skills list
hermes mcp list
hermes profile list
hermes hooks doctor
npm run harness:audit
npm test
```

Exit criteria:

- Hermes can invoke the core Fulcrum workflows.
- Hermes tool and runtime policy are safe for the active usage mode.
- Drift audit covers the integration.

## Phase 13: Documentation And Runbook

Tasks:

1. Add a Hermes runbook:

   ```text
   docs/HERMES_FULCRUM_RUNBOOK.md
   ```

2. Cover:
   - startup
   - profile selection
   - model/runtime selection
   - skill discovery
   - MCP maintenance
   - backup/restore
   - gateway/channel setup
   - cron/hook safety
   - OpenClaw migration boundary
3. Update cross-platform integration docs to include Hermes:
   - Claude Code
   - Codex
   - OpenCode
   - Crush/OpenClaw
   - Hermes
4. Add a troubleshooting table:
   - skill not visible
   - slash command collision
   - MCP server fails
   - local model fails
   - gateway stopped
   - hooks blocked
   - missing tool requirement

Exit criteria:

- A fresh agent can reproduce the integration from docs.
- The user can tell whether to use Hermes, OpenClaw, Codex, Claude CLI, OpenCode, or Crush for a given task.

## Parallelization

Can run in parallel after Phase 1:

- Phase 2 skills external dirs
- Phase 3 skill compatibility report
- Phase 5 MCP baseline
- Phase 6 profiles
- Phase 7 runtime routing

Must remain serial:

- Phase 0 before all config changes
- Phase 1 before enabling more access
- Phase 10 only after backup and skill collision checks
- Phase 11 after expected integration surfaces exist
- Phase 12 after implementation
- Phase 13 after behavior stabilizes

## Definition Of Done

Hermes integration is complete when:

- Hermes can run a one-shot prompt successfully.
- Hermes discovers Fulcrum skills through `skills.external_dirs`.
- Core Fulcrum workflows are invokable from Hermes slash commands or wrapper skills.
- Hermes has the Fulcrum MCP baseline configured.
- Hermes profiles represent Fulcrum roles with safe tool boundaries.
- Strong runtime/provider routing exists for high-risk coding and security work.
- Hooks and cron are absent or reviewed, allowlisted, and documented.
- Gateway channels are absent or protected by allowed users/pairing.
- OpenClaw migration is either intentionally skipped or applied from a reviewed dry run.
- A drift auditor catches lost skill, command, MCP, profile, and security parity.
- No secrets are committed or written into generated docs.
