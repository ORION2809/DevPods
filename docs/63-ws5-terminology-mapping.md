# WS5 Terminology Mapping: GitNexus → DevPods

When harvesting donor code, replace GitNexus terminology with DevPods-native terminology.
This keeps the codebase internally consistent and avoids confusion.

| GitNexus term | DevPods term | Rationale |
|---------------|--------------|-----------|
| GitNexus | DevPods Intelligence | Product name |
| LadybugDB / lbug | CodeGraph | Graph database abstraction |
| repo | workspace | DevPods already uses workspace |
| analyze (a repo) | index (a workspace) | Clearer action name |
| storage path | index path | What it actually is |
| registry (repo registry) | index registry | Avoid confusion with workspace registry |
| repo manager | index manager | Manages indexed workspaces |
| codebase context | workspace context | DevPods-native |
| LocalBackend | IntelligenceEngine | The intelligence implementation |
| query (method) | searchSymbols | Avoid confusion with CodeQueryResult.query |
| context (method) | getSymbolContext | Avoid confusion with general "context" |
| impact (method) | analyzeImpact | Verb form for clarity |
| detect_changes | detectChanges | Already in our interface |
| routeMap / route_map | mapRoutes | DevPods interface name |
| toolMap / tool_map | mapTools | DevPods interface name |
| CodeRelation | CodeEdge | Simpler, graph-native term |
| node table | node type | LadybugDB-specific → generic graph |
| `@ladybugdb/core` | `@ladybugdb/core` (keep package name) | npm package name stays |
| lbugjs.node | codegraph native binary | Internal reference only |
| GitNexus shared | intelligence shared | Internal package reference |
| MCP | (remove entirely) | Not part of DevPods product |
| CLI shell | (remove entirely) | Not part of DevPods product |
| eval server | (remove entirely) | Not part of DevPods product |

## Naming Conventions for Harvested Files

- `src/intelligence/gitnexus/` → `src/intelligence/engine/` (the directory name stays as implementation detail)
- `src/intelligence/gitnexus/graph/` → graph store layer
- `src/intelligence/gitnexus/ingestion/` → indexing pipeline
- `src/intelligence/gitnexus/search/` → search stack

## File Naming

Use kebab-case matching existing DevPods conventions:
- `schema-constants.ts` ✓
- `intelligence-engine.ts` (not `local-backend.ts`)
- `workspace-indexer.ts` (not `repo-manager.ts`)
- `graph-store.ts` (not `lbug-adapter.ts`)
- `index-registry.ts` (not `repo-registry.ts`)
