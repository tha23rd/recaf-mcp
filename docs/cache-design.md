# Cache Design

This document describes cache behavior for the Recaf MCP plugin and bridge.

## Cache Configuration

Plugin-side caches are controlled with JVM system properties:

- `-Drecaf.mcp.cache.enabled` (default: `true`)
- `-Drecaf.mcp.cache.ttl.seconds` (default: `120`)
- `-Drecaf.mcp.cache.max.entries` (default: `1000`)

These values apply to all plugin cache instances (`decompile`, `search query`, `class inventory`, `instruction analysis`).

## Cache Layers And Boundaries

| Layer | Key Boundary | Cached Value Boundary | Used By |
|---|---|---|---|
| `DecompileCache` | `workspaceIdentity + workspaceRevision + className + classBytecodeHash + decompilerName` | Decompiled source text for one class/decompiler/revision | `decompile-class`, `decompile-method`, `decompile-diff`, `search-text-in-decompilation`, `recaf://class/{name}` |
| `SearchQueryCache` | `workspaceIdentity + workspaceRevision + queryType + normalizedQuery` | Immutable list of serialized search/xref result DTO maps | `search-strings`, `search-strings-count`, `search-numbers`, `search-references`, `search-declarations`, `xrefs-to`, `xrefs-count` |
| `ClassInventoryCache` | `workspaceIdentity + workspaceRevision` | Immutable inventory snapshot (class list, package list, simple-name index) | Navigation tools and `recaf://classes` / class-resolution suggestion paths |
| `InstructionAnalysisCache` | `workspaceIdentity + workspaceRevision + className + classBytecodeHash` | Immutable per-class analysis DTO (instruction text + outgoing refs) | `search-instructions`, `xrefs-from` |
| Bridge metadata cache | Bridge process + TTL window | `list_tools` / `list_resources` backend metadata | `recaf-mcp-bridge` (`30s` TTL, cleared on backend reconnect) |

### Non-Goals / Not Cached

- Mutation tool outputs are never cached.
- Caches are internal only; response schema and payload shape are unchanged.
- Not every read path is cached (for example, `search-files` and `search-instruction-sequence` still execute directly).

## Invalidation Matrix

Workspace revision increments invalidate plugin cache keys immediately because every plugin cache key includes `workspaceRevision`.

| Tool | Revision bump |
|---|---|
| `workspace-open` | Yes |
| `workspace-close` | Yes |
| `workspace-add-supporting` | Yes |
| `compile-java` | Yes |
| `phantom-generate` | Yes |
| `assemble-method` | Yes |
| `assemble-class` | Yes |
| `rename-class` | Yes |
| `rename-method` | Yes |
| `rename-field` | Yes |
| `rename-variable` | Yes |
| `mapping-apply` | Yes |
| `transform-apply` | Yes |
| `transform-apply-batch` | Yes |
| `transform-undo` | Yes |

### Read-Only Behavior

- Read-only tools/resources do not bump revision.
- Any read path that uses a cache observes invalidation automatically after a mutation because revision changes create new keys.

## Staleness Boundary

If workspace changes occur outside these MCP mutation paths, TTL and max-entry eviction provide eventual refresh protection. In normal MCP-driven workflows, revision-based invalidation is immediate.
