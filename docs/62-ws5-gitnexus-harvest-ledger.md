# WS5: GitNexus Harvest Ledger

> Locked against: `intelligence-layer-contract.ts` (W4 checkpoint, 2026-06-08)
> Donor snapshot: `vendor-sources/GitNexus-main`
> Branch: `work/hermes-ticket-queue`

## Rule

GitNexus must fit **behind** the `IntelligenceLayer` interface. The contract is frozen.
No donor code reshapes the boundary. Donor code becomes the implementation.

## Must Harvest (Phase 1 — Core Engine)

| # | GitNexus area | Donor path | DevPods target | Notes |
|---|---------------|------------|----------------|-------|
| 1 | File ignore and filtering | `src/config/ignore-service.ts` | `src/intelligence/ignore-service.ts` | Prevents indexing junk, binaries, secrets, build outputs |
| 2 | Repo and storage management | `src/storage/repo-manager.ts` | `src/intelligence/repo-manager.ts` | Index location, repo identity, metadata, registry concepts. Simplify heavily. |
| 3 | Git helpers and staleness | `src/storage/git.ts`, `src/core/git-staleness.ts` | `src/intelligence/git-staleness.ts` | Freshness checks, worktree awareness, diff-aware reasoning |
| 4 | File hashing and parse cache | `src/storage/file-hash.ts`, `src/storage/parse-cache.ts` | `src/intelligence/parse-cache.ts` | Makes repeat indexing practical |
| 5 | Ingestion pipeline | `src/core/ingestion/*` | `src/intelligence/ingestion/*` | Heart of code graph construction. Harvest selectively but deeply. |
| 6 | Tree-sitter language support | `src/core/ingestion/languages/*` | `src/intelligence/ingestion/languages/*` | TS, JS, Kotlin first. Skip other languages initially. |
| 7 | Worker-based parsing | `src/core/ingestion/workers/*` | `src/intelligence/ingestion/workers/*` | Critical for performance on real repos |
| 8 | Graph schema and persistence | `src/core/lbug/*` | `src/intelligence/graph/*` | Local graph store and query substrate |
| 9 | Search stack | `src/core/search/*` | `src/intelligence/search/*` | BM25 and hybrid ranking for usable query quality |
| 10 | Local backend tool logic | `src/mcp/local/local-backend.ts` | `src/intelligence/local-backend.ts` | Real implementations of `query`, `context`, `impact`, `detect_changes`, `routeMap`, `toolMap` |
| 11 | Route extraction | `src/core/ingestion/pipeline-phases/routes.ts`, `route-extractors/*` | `src/intelligence/ingestion/routes.ts` | Understanding APIs in developer repos |
| 12 | Tool extraction | `src/core/ingestion/pipeline-phases/tools.ts` | `src/intelligence/ingestion/tools.ts` | Understanding MCP or tool-driven repos |
| 13 | Process and community generation | `communities.ts`, `processes.ts` | `src/intelligence/communities.ts` | Execution-flow answers that feel intelligent |
| 14 | Platform and DB operational safety | `src/core/platform/capabilities.ts`, `src/core/lbug/lbug-config.ts`, `sidecar-recovery.ts` | `src/intelligence/platform/*` | Stability and recoverability |
| 15 | Tests and fixtures | `test/unit`, `test/integration` | `test/intelligence/*` | Port selectively to prove parity |

## Deferred Harvest (Phase 2 — After Core Lands)

| # | GitNexus area | Donor path | When | Notes |
|---|---------------|------------|------|-------|
| 16 | Embeddings | `src/core/embeddings/*` | Phase 2 | Better natural-language query quality |
| 17 | Incremental and shadow helpers | `src/core/incremental/*` | Phase 2 | Smarter refresh and partial rebuilds |
| 18 | MCP resources | `src/mcp/resources.ts` | Phase 2 | Internal diagnostics or developer endpoints |
| 19 | Wiki generation | `src/core/wiki/*` | Phase 2 | Auto-generated repo understanding docs |
| 20 | Cross-repo groups | `src/core/group/*` | Phase 2 | Multi-repo reasoning |
| 21 | More language packs | Python, Java, Swift, Go | Phase 2+ | Broaden supported target workspaces |

## Intentionally Skipped

| GitNexus area | Donor path | Why skipped |
|---------------|------------|-------------|
| Web UI | `gitnexus-web/`, `src/server/api.ts` | Not part of DevPods desktop intelligence core |
| CLI shell | `src/cli/*` | We want owned DevPods APIs, not copied standalone CLI |
| MCP stdio server shell | `src/mcp/server.ts` | Not needed for first internalization |
| Eval server | `src/cli/eval-server.ts` | Evaluation-focused, not product requirement |
| Setup, hooks, skills injection | `setup.ts`, `ai-context.ts` | GitNexus product conveniences, not DevPods needs |
| Publish and registry flows | `publish.ts` | Not relevant to current product goal |
| AGENTS and CLAUDE file injection | `ai-context.ts` | Explicitly not wanted in DevPods runtime |

## Interface Compliance Checklist

Before any harvested subsystem is considered integrated, it must satisfy:

- [ ] Implements `IntelligenceLayer` without changing the interface
- [ ] `getIndexState(workspaceId)` returns accurate state from owned index
- [ ] `query()` returns `CodeQueryResult` with `answer`, `files`, `lineReferences`, `confidence`
- [ ] `context()` returns `SymbolContextResult` with `definition`, `callers`, `callees`, `affectedFlows`, `files`
- [ ] `impact()` returns `ImpactResult` with `directCallers`, `affectedModules`, `affectedFlows`, `testSuggestions`, `riskLevel`, `safeToContinue`
- [ ] `detectChanges()` returns `DetectChangesResult` with `changedAreas`, `affectedFlows`, `riskiestArea`, `safeToContinue`, `files`
- [ ] `routeMap()` returns `RouteMapResult` with `route`, `consumers`, `middleware`, `handlers`
- [ ] `toolMap()` returns `ToolMapResult` with `tool`, `callSites`, `implementations`
- [ ] Read-only guarantee enforced (no execution, no approval, no write)
- [ ] Voice formatter produces output within 24-word budget
- [ ] Tests prove parity with donor behavior for harvested features
- [ ] `vendor-sources/GitNexus-main` is not required at runtime

## Acceptance Criteria for WS5 Complete

1. [ ] DevPods owns the codegraph runtime directly
2. [ ] First owned engine supports TypeScript, JavaScript, and Kotlin
3. [ ] `query`, `context`, `impact`, `detect_changes` work through owned APIs
4. [ ] Route and tool extraction survive the transplant
5. [ ] Indexing never blocks Core commands
6. [ ] Latency-critical intents do not regress
7. [ ] Voice answers remain short and ear-safe
8. [ ] Policy and approval boundaries remain DevPods-owned
9. [ ] Relevant donor tests ported or mirrored for parity proof
10. [ ] Every major donor subsystem marked harvested, deferred, or skipped
