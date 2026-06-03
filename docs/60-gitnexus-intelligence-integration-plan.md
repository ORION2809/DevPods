# GitNexus Donor Integration And Harvest Plan

Generated: 2026-06-03

## Executive Verdict

The correct direction is not to run GitNexus forever as an external dependency from `vendor-sources/GitNexus-main`.

The correct direction is to use GitNexus as a donor and absorb the useful parts into an owned DevPods desktop intelligence subsystem.

That means:

- keep the current GitNexus folder as the donor reference during migration
- transplant the code-intelligence core into DevPods
- strip away GitNexus product shell pieces we do not need
- preserve all of the parts that improve code understanding, impact analysis, diff analysis, search quality, and indexing reliability

This is the right choice for DevPods because the desktop side is still young, while GitNexus already contains a mature code-graph stack we can repurpose.

For this plan, licensing is treated as cleared under the current project assumption: you are part of the developing company, authorized to use the technology, and the current DevPods work is internal and educational. Re-check only if the future release and distribution model changes materially.

## Core Product Position

GitNexus should become DevPods' owned desktop code-intelligence engine.

It should not remain:

- a runtime dependency on the vendored folder
- a separate product shell we call forever
- the source of truth for policy, approvals, or execution

It should become:

- an internal DevPods subsystem
- a local code graph and repository understanding layer
- the engine behind advanced read-only intelligence features
- a reusable desktop service for indexing, query, blast-radius analysis, and diff-aware reasoning

## What I Verified In GitNexus

The donor is substantial, not small:

- around `627` source files under `vendor-sources/GitNexus-main/gitnexus/src`
- around `2479` test files under `vendor-sources/GitNexus-main/gitnexus/test`
- a real ingestion pipeline, graph store, search stack, route/tool extraction system, diff-impact analysis, embeddings support, and cross-repo analysis machinery

Important runtime facts:

- the package requires Node `>=22.0.0`
- this environment already satisfies that with `node -v = v24.13.1`
- the donor supports the languages that matter most to us now:
  - TypeScript
  - JavaScript
  - Kotlin
  - Swift

That language coverage maps well to DevPods:

- TypeScript for bridge and runtime
- Kotlin for Android and Wear
- optional future value from Swift support if Apple-side work grows later

## The New Direction

The previous version of this plan treated GitNexus mostly as a sidecar or external service.

That is no longer the target.

The new target is:

```text
GitNexus donor snapshot
  -> selective subsystem transplant
  -> owned DevPods codegraph package
  -> bridge-integrated intelligence APIs
  -> new DevPods read-only intelligence intents
```

This is not a wholesale copy of all GitNexus code as-is.

It is also not a minimal wrapper around the donor folder.

It is a deliberate internalization effort:

- preserve the useful core
- adapt it to DevPods architecture
- remove product-shell layers we do not need
- keep enough provenance and parity tests that we can safely evolve it afterward

## What We Must Not Miss

The main risk in a donor strategy is not "can we copy code".

The main risk is missing second-order systems that make the engine reliable in practice.

So this plan treats GitNexus as a harvest map, not just a query feature set.

The useful parts fall into three buckets:

1. Must harvest now
2. Harvest after the core lands
3. Skip as product shell, but keep as reference

## Harvest Matrix

### Must Harvest Now

| GitNexus area | Donor path | Why it matters to DevPods | Harvest decision |
| --- | --- | --- | --- |
| File ignore and filtering | `src/config/ignore-service.ts` | Prevents indexing junk, binaries, secrets, build outputs, generated files | Harvest and adapt |
| Repo and storage management | `src/storage/repo-manager.ts` | Index location, repo identity, metadata, registry concepts | Harvest core ideas and simplify |
| Git helpers and staleness | `src/storage/git.ts`, `src/core/git-staleness.ts` | Freshness checks, worktree awareness, diff-aware reasoning | Harvest |
| File hashing and parse cache | `src/storage/file-hash.ts`, `src/storage/parse-cache.ts` | Makes repeat indexing practical instead of slow every time | Harvest |
| Ingestion pipeline | `src/core/ingestion/*` | This is the heart of code graph construction | Harvest selectively but deeply |
| Tree-sitter language support | `src/core/ingestion/languages/*` | Needed to parse TypeScript, JavaScript, Kotlin | Harvest TS, JS, Kotlin first |
| Worker-based parsing | `src/core/ingestion/workers/*` | Critical for performance on real repos | Harvest |
| Graph schema and persistence | `src/core/lbug/*` | Gives us the actual local graph store and query substrate | Harvest |
| Search stack | `src/core/search/*` | BM25 and hybrid ranking are core to usable query quality | Harvest |
| Local backend tool logic | `src/mcp/local/local-backend.ts` | Contains the real implementations of `query`, `context`, `impact`, `detect_changes`, `route_map`, `shape_check`, `api_impact`, `tool_map` | Harvest and refactor into owned DevPods APIs |
| Route extraction | `src/core/ingestion/pipeline-phases/routes.ts`, `route-extractors/*` | Very useful for understanding APIs in developer repos | Harvest |
| Tool extraction | `src/core/ingestion/pipeline-phases/tools.ts` | Useful for understanding MCP or tool-driven repos | Harvest |
| Process and community generation | `communities.ts`, `processes.ts`, community and process processors | Needed for execution-flow answers that feel intelligent rather than grep-like | Harvest |
| Platform and DB operational safety | `src/core/platform/capabilities.ts`, `src/core/lbug/lbug-config.ts`, `sidecar-recovery.ts`, WAL helpers | Important for stability and recoverability | Harvest |
| Tests and fixtures | `test/unit`, `test/integration`, parity and cross-platform suites | We need these to keep the transplant honest | Harvest relevant suites, not all tests blindly |

### Harvest After Core Lands

| GitNexus area | Donor path | Why it is useful | Decision |
| --- | --- | --- | --- |
| Embeddings | `src/core/embeddings/*` | Better natural-language query quality and semantic retrieval | Harvest in phase 2, after graph parity is stable |
| Incremental and shadow helpers | `src/core/incremental/*` | Helpful for smarter refresh and partial rebuilds | Harvest after initial working engine |
| MCP resources | `src/mcp/resources.ts` | Useful as internal diagnostics or developer endpoints | Harvest concepts later |
| Wiki generation | `src/core/wiki/*` | Useful for onboarding and auto-generated repo understanding docs | Harvest later |
| Cross-repo groups | `src/core/group/*` | Very useful if DevPods expands to multi-repo reasoning across app, firmware, backend, tooling repos | Harvest later, not first |
| More language packs | Python, Java, Swift, Go, etc. | Potentially useful if we broaden supported target workspaces | Defer until real need |

### Skip As Product Shell, Keep As Reference

| GitNexus area | Donor path | Why we are not transplanting it first |
| --- | --- | --- |
| GitNexus web UI | `gitnexus-web/` and `src/server/api.ts` web-serving path | Useful as a reference, but not part of the first DevPods desktop intelligence core |
| CLI shell | `src/cli/*` as a whole | Good for reference and validation, but we want owned DevPods APIs, not a copied standalone product CLI |
| MCP stdio server shell | `src/mcp/server.ts`, transport glue | Useful later if we expose DevPods intelligence outward, not needed first |
| Eval server | `src/cli/eval-server.ts` | Evaluation-focused shell, not a product requirement |
| Setup, hooks, skills injection | `setup.ts`, `ai-context.ts`, skills, hooks | These are GitNexus product conveniences, not DevPods product needs |
| Publish and registry flows | `publish.ts`, public registry concepts | Not relevant to our current product goal |
| AGENTS and CLAUDE file injection | `ai-context.ts` | Explicitly not wanted in DevPods runtime behavior |
| Full multi-language donor footprint | all language packs from day one | Too much scope for first internalization pass |

## The Owned DevPods Target

The end state should be an internal package or module owned by this repo, for example:

- `packages/devpods-codegraph/`

or, if we keep it inside the current app tree:

- `src/intelligence/codegraph/`

The package should own:

- ingestion
- local graph storage
- search
- codegraph query APIs
- diff-impact APIs
- route and tool understanding
- repo freshness checks

The bridge should consume that package directly.

The final product should not depend on:

- `vendor-sources/GitNexus-main` being present at runtime
- a globally installed `gitnexus` command
- an external sidecar process for normal operation

## Recommended Internal Package Layout

Suggested owned layout:

```text
packages/devpods-codegraph/
  src/
    config/
    storage/
    ingestion/
    languages/
      typescript/
      javascript/
      kotlin/
    graph/
    search/
    embeddings/
    analysis/
      query/
      context/
      impact/
      detect-changes/
      route-map/
      api-impact/
      shape-check/
    runtime/
    tests/
```

And then the product-facing bridge adapter:

```text
src/intelligence/codegraph/
  devpods-codegraph-service.ts
  devpods-codegraph-formatters.ts
  devpods-codegraph-intents.ts
```

## Donor Transplant Strategy

### Phase 0: Freeze The Donor Baseline

Before moving code:

- treat `vendor-sources/GitNexus-main` as the canonical donor snapshot
- record the donor version in the plan and internal notes
- do not mutate donor code casually
- use donor tests as the parity reference

This lets us transplant with discipline instead of creating an untraceable fork immediately.

### Phase 1: Create The Owned DevPods Codegraph Package

Start by scaffolding the owned target package and copying in the operational foundation:

- ignore rules
- repo path and metadata logic
- file hashing
- parse cache
- graph schema
- local storage bootstrap
- worker framework

This creates the shell that the real engine can live inside.

### Phase 2: Port The Minimum Viable Language Engine

Port only these languages first:

- TypeScript
- JavaScript
- Kotlin

That includes:

- language providers
- import resolvers
- call extractors
- method, variable, field, class extractors
- scope resolution logic
- tree-sitter query packs

Do not start with every supported GitNexus language. That increases complexity without improving DevPods' first useful target set.

### Phase 3: Port Query-Critical Graph Construction

Port the pipeline pieces that create useful repository intelligence:

- structure phase
- parse phase
- routes phase
- tools phase
- cross-file resolution
- MRO logic where applicable
- communities
- processes

This is what turns parsing into actual architectural understanding.

### Phase 4: Port The Core Analysis APIs

The first owned APIs should be:

- `query`
- `context`
- `impact`
- `detectChanges`
- `routeMap`
- `shapeCheck`
- `apiImpact`
- `toolMap`

These should be exposed as internal TypeScript APIs, not as copied CLI commands.

### Phase 5: Bridge Integration

After the internal package works, wire it into DevPods:

- add new read-only intents
- call the owned codegraph APIs from `JarvisRuntime`
- keep the responses short and ear-safe
- preserve current policy and approval boundaries

### Phase 6: Embeddings And Optional Intelligence Upgrades

Once the graph and deterministic analysis are stable:

- port embeddings
- add hybrid search
- improve natural-language query quality

This should come after graph correctness, not before.

### Phase 7: Secondary Feature Harvest

After the core works, revisit:

- wiki generation
- repo overview resources
- cross-repo contract extraction
- multi-repo impact
- additional languages

## New DevPods Product Uses

Once internalized, the owned codegraph engine should power:

- explain code path
- find symbol usage
- impact analysis
- review local changes
- API contract impact
- route-to-consumer explanations
- tool-to-handler explanations
- richer background workspace awareness

## What Should Still Stay Out Of The Hot Path

Even after internalization, the codegraph engine should not replace:

- `quick_status`
- `summarize_diff`
- `latest_ci_failure`
- wake gesture handling
- STT startup
- TTS warmup
- approval flows
- command execution

Those paths already have a better local-first and lower-latency architecture.

So the donor strategy is about adding intelligence, not replacing the entire product runtime with graph queries.

## Exact GitNexus Areas We Should Explicitly Review During Harvest

If the goal is "do not miss anything useful", these donor areas must all be reviewed and dispositioned:

- `gitnexus/src/config/ignore-service.ts`
- `gitnexus/src/storage/file-hash.ts`
- `gitnexus/src/storage/git.ts`
- `gitnexus/src/storage/parse-cache.ts`
- `gitnexus/src/storage/repo-manager.ts`
- `gitnexus/src/core/git-staleness.ts`
- `gitnexus/src/core/lbug/*`
- `gitnexus/src/core/platform/capabilities.ts`
- `gitnexus/src/core/search/*`
- `gitnexus/src/core/embeddings/*`
- `gitnexus/src/core/incremental/*`
- `gitnexus/src/core/ingestion/*`
- `gitnexus/src/core/group/*`
- `gitnexus/src/core/wiki/*`
- `gitnexus/src/mcp/local/local-backend.ts`
- `gitnexus/src/mcp/resources.ts`
- relevant tests under `gitnexus/test/unit` and `gitnexus/test/integration`

The point is not that all of them ship in v1.

The point is that all of them get explicitly evaluated so nothing useful is lost by accident.

## What DevPods Should Not Do During This Migration

Do not:

- keep a forever dependency on the donor folder
- blindly copy all GitNexus code without refactoring boundaries
- drag the GitNexus web UI into the first product milestone
- expose `rename`, `clean`, `remove`, or free-form `cypher` through voice
- let the codegraph engine bypass allowlists, policy, or approvals
- stall the current low-latency work while doing the transplant

## DevPods Files Likely To Change

### New owned codegraph package

- `packages/devpods-codegraph/*` or `src/intelligence/codegraph/*`

### Existing bridge and runtime code

- `src/protocol/types.ts`
- `src/protocol/schemas.ts`
- `src/jarvis/router.ts`
- `src/jarvis/intent-resolution-engine.ts`
- `src/jarvis/runtime.ts`
- `src/bridge/runtime.ts`
- `src/bridge/server.ts`
- `src/policy/engine.ts`
- `config/workspaces.json`

### Android impact

Android does not need a new transport path for the first internalized-codegraph milestone.

The flow stays:

- Android sends request
- bridge resolves intent
- bridge calls owned codegraph subsystem if needed
- bridge returns ordinary `JarvisResponse`

## Acceptance Criteria

This donor integration should only be considered correct when all of these are true:

1. DevPods owns the codegraph runtime directly and does not require the donor folder at runtime.
2. The first owned engine supports TypeScript, JavaScript, and Kotlin.
3. `query`, `context`, `impact`, and `detect_changes` all work through owned DevPods APIs.
4. Route and tool extraction survive the transplant, not just symbol lookup.
5. Parse caching, ignore rules, staleness checks, and worker-based parsing are preserved.
6. Current latency-critical intents do not regress because of the transplant.
7. Voice answers remain short and ear-safe.
8. Policy and approval boundaries remain exactly DevPods-owned.
9. Relevant donor tests are ported or mirrored so we can prove parity on the harvested features.
10. Every major donor subsystem is explicitly marked as harvested, deferred, or intentionally skipped.

## Final Recommendation

Use GitNexus as a donor, not as a permanent external dependency.

Absorb every part of it that materially improves DevPods:

- indexing reliability
- graph construction
- search quality
- execution-flow understanding
- blast-radius analysis
- diff-impact reasoning
- route and tool understanding
- optional embeddings later
- optional wiki and cross-repo capabilities later

That gives DevPods the full upside of GitNexus while turning the intelligence layer into something we actually own, shape, and optimize for the product we are building.
