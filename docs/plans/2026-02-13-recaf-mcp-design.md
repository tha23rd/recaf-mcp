# Recaf MCP Integration Design

**Date:** 2026-02-13
**Status:** Approved

## Goal

Build an MCP (Model Context Protocol) server that enables LLM agents to autonomously control Recaf for full-spectrum Java reverse engineering: decompilation, navigation, search, renaming, annotation, bytecode editing, deobfuscation, and more.

## Architecture

### Overview

```
Claude Code (stdio) --> Python Bridge (stdio-to-HTTP) --> Recaf Plugin (Jetty + MCP SDK)
                                                              |
                                                         Recaf CDI Container
                                                         (all 25+ services)
```

**Pattern:** Embedded MCP server inside a Recaf plugin (proven by ReVa/GhidrAssistMCP).

### Components

1. **RecafMcpPlugin** (Java) - A Recaf 4.x plugin that:
   - Injects all Recaf services via CDI (`@Inject`)
   - Embeds a Jetty 11 HTTP server on `localhost:8085`
   - Runs an `McpSyncServer` with Streamable HTTP transport
   - Registers ~62 tools across 13 ToolProvider classes
   - Registers MCP resources for workspace/class data

2. **recaf-mcp-bridge** (Python) - A thin stdio-to-HTTP proxy that:
   - Implements an MCP server with stdio transport (for Claude Code)
   - Proxies all requests to the Jetty HTTP server via Streamable HTTP
   - Same pattern as ReVa's `ReVaStdioBridge`

3. **Skills** (Claude Code) - Structured RE workflows:
   - `jar-triage` - breadth-first workspace survey
   - `deep-analysis` - depth-first investigation loop
   - `deobfuscation-workflow` - Java-specific deobfuscation pipeline

### Transport

- **Primary:** Streamable HTTP (`/mcp/message` on `localhost:8085`)
- **CLI:** Python stdio bridge for Claude Code compatibility
- **Multi-client:** Streamable HTTP allows multiple simultaneous connections

## Tool Inventory (62 tools)

### Workspace Management (7)

| Tool | Description |
|------|-------------|
| `workspace-open` | Open a JAR/APK/class file as a new workspace |
| `workspace-close` | Close the current workspace |
| `workspace-get-info` | Get workspace metadata (file counts, resource structure) |
| `workspace-export` | Export modified workspace to a file |
| `workspace-list-resources` | List all resources (primary + supporting) |
| `workspace-add-supporting` | Add a supporting resource (library JAR) |
| `workspace-get-history` | Get undo history for a bundle |

### Class Navigation (8)

| Tool | Description |
|------|-------------|
| `class-list` | List classes with pagination (package filter) |
| `class-count` | Count classes (optional package filter) |
| `class-get-info` | Get class metadata (flags, super, interfaces, members) |
| `class-get-hierarchy` | Get inheritance hierarchy for a class |
| `method-list` | List methods of a class |
| `field-list` | List fields of a class |
| `package-list` | List packages in the workspace |
| `class-search-by-name` | Search classes by name pattern |

### Decompilation (5)

| Tool | Description |
|------|-------------|
| `decompile-class` | Decompile class to Java source (optional xref context) |
| `decompile-method` | Decompile a single method |
| `decompiler-list` | List available decompiler backends |
| `decompiler-set` | Set the active decompiler backend |
| `decompile-diff` | Compare decompilation before/after changes |

### Disassembly / Assembly (4)

| Tool | Description |
|------|-------------|
| `disassemble-method` | Disassemble method to JASM bytecode |
| `assemble-method` | Assemble JASM bytecode into a method |
| `disassemble-class` | Disassemble an entire class |
| `assemble-class` | Assemble an entire class from JASM |

### Search (8)

| Tool | Description |
|------|-------------|
| `search-strings` | Search string constants across all classes |
| `search-strings-count` | Count string constant matches |
| `search-numbers` | Search numeric constants |
| `search-references` | Search class/member references |
| `search-declarations` | Search declarations by name |
| `search-instructions` | Search by instruction patterns |
| `search-files` | Search within non-class files |
| `search-text-in-decompilation` | Regex search across decompiled source |

### Cross-References (3)

| Tool | Description |
|------|-------------|
| `xrefs-to` | Find all references TO a class/method/field |
| `xrefs-from` | Find all references FROM a method |
| `xrefs-count` | Count references (pagination planning) |

### Call Graph (4)

| Tool | Description |
|------|-------------|
| `callgraph-callers` | Get all callers of a method |
| `callgraph-callees` | Get all callees from a method |
| `callgraph-path` | Find call paths between two methods |
| `callgraph-build` | Build/refresh the call graph |

### Renaming / Mapping (7)

| Tool | Description |
|------|-------------|
| `rename-class` | Rename a class |
| `rename-method` | Rename a method |
| `rename-field` | Rename a field |
| `rename-variable` | Rename a local variable |
| `mapping-apply` | Apply a mapping file (Enigma, ProGuard, SRG, etc.) |
| `mapping-export` | Export current mappings |
| `mapping-list-formats` | List supported mapping formats |

### Comments / Annotations (4)

| Tool | Description |
|------|-------------|
| `comment-set` | Set a comment on a class/method/field |
| `comment-get` | Get comments for a class/method/field |
| `comment-search` | Search all comments |
| `comment-delete` | Delete a comment |

### Compilation (3)

| Tool | Description |
|------|-------------|
| `compile-java` | Compile Java source to bytecode and update class |
| `compile-check` | Check if Java source compiles without applying |
| `phantom-generate` | Generate phantom classes for missing deps |

### Transforms / Deobfuscation (5)

| Tool | Description |
|------|-------------|
| `transform-list` | List available transformers with descriptions |
| `transform-apply` | Apply a transformer to the workspace |
| `transform-apply-batch` | Apply multiple transformers in sequence |
| `transform-preview` | Preview what a transformer would change |
| `transform-undo` | Undo the last transformation |

### JVM Attach (4)

| Tool | Description |
|------|-------------|
| `attach-list-vms` | List running JVMs |
| `attach-connect` | Attach to a running JVM |
| `attach-load-classes` | Load classes from attached JVM |
| `attach-disconnect` | Disconnect from attached JVM |

### MCP Resources

| URI | Description |
|-----|-------------|
| `recaf://workspace` | Current workspace metadata |
| `recaf://classes` | List of all classes |
| `recaf://class/{name}` | Full class info + decompiled source |

## Tool Design Principles

### 1. Flexible Input Parsing

Every tool accepting class/method/field references supports multiple formats:
- `com/example/MyClass` or `com.example.MyClass`
- `MyClass.myMethod` or `MyClass#myMethod(Ljava/lang/String;)V`
- Partial names resolve if unambiguous

### 2. Context Enrichment

Tools return more than asked to reduce round trips:
- `decompile-class` optionally includes xrefs and method signatures
- `class-get-info` includes the inheritance chain
- `xrefs-to` optionally includes code snippets from calling sites

### 3. Count-Before-List

Every listing tool has a `*-count` companion. All lists accept `offset` and `limit` (default: 100).

### 4. Helpful Error Messages

Errors include actionable suggestions:
- "Class `com/exmple/Foo` not found. Did you mean: `com/example/Foo`?"
- "No workspace open. Use `workspace-open` to load a JAR file first."

### 5. Pluggable Serialization (JSON / TOON)

Tool implementations return Java objects. A `ResponseSerializer` interface handles format:

```java
public interface ResponseSerializer {
    String serialize(Object response);
}
```

Two implementations:
- `JsonResponseSerializer` (default, Jackson)
- `ToonResponseSerializer` (JToon library, 30-60% token reduction)

Switchable via server configuration. Same POJOs for both formats.

### 6. Transaction Safety

All write operations use Recaf's undo system. Every modification creates a history checkpoint.

## Claude Code Skills

### `jar-triage` (Breadth-first survey, ~20 tool calls)

1. Open workspace, get class count and package structure
2. Sample strings for initial intelligence (URLs, config, errors)
3. Identify entry points (main, servlets, Spring, Android activities)
4. Survey package hierarchy
5. Quick decompilation of 3-5 key classes
6. Create task list for deeper investigation

### `deep-analysis` (Depth-first investigation loop, ~15 tool calls per cycle)

1. READ - decompile target method/class
2. UNDERSTAND - analyze logic, identify patterns
3. IMPROVE - rename variables, methods, classes; add comments
4. VERIFY - re-decompile to confirm improvement
5. FOLLOW THREADS - trace xrefs and call graph
6. TRACK - bookmark findings

### `deobfuscation-workflow` (Java-specific)

1. IDENTIFY - detect obfuscation patterns (string encryption, control flow, reflection)
2. SELECT - choose appropriate transforms
3. PREVIEW - check transform effects
4. APPLY - apply transforms incrementally
5. CLEAN UP - rename recovered symbols, annotate

## Project Structure

```
recaf-mcp/
├── recaf-mcp-plugin/                    # Java - Recaf plugin (Gradle)
│   ├── build.gradle
│   ├── settings.gradle
│   ├── src/main/java/dev/recafmcp/
│   │   ├── RecafMcpPlugin.java          # Plugin entry (@PluginInformation)
│   │   ├── server/
│   │   │   ├── McpServerManager.java    # Jetty + MCP lifecycle
│   │   │   └── ResponseSerializer.java  # JSON/TOON serialization
│   │   ├── providers/                   # One class per tool domain
│   │   │   ├── AbstractToolProvider.java
│   │   │   ├── WorkspaceToolProvider.java
│   │   │   ├── DecompilerToolProvider.java
│   │   │   ├── NavigationToolProvider.java
│   │   │   ├── SearchToolProvider.java
│   │   │   ├── MappingToolProvider.java
│   │   │   ├── AssemblerToolProvider.java
│   │   │   ├── CompilerToolProvider.java
│   │   │   ├── TransformToolProvider.java
│   │   │   ├── CallGraphToolProvider.java
│   │   │   ├── InheritanceToolProvider.java
│   │   │   ├── CommentToolProvider.java
│   │   │   ├── AttachToolProvider.java
│   │   │   └── XRefToolProvider.java
│   │   ├── resources/
│   │   │   ├── WorkspaceResource.java
│   │   │   └── ClassResource.java
│   │   └── util/
│   │       ├── AddressResolver.java     # Flexible resolution
│   │       ├── PaginationUtil.java
│   │       └── ErrorHelper.java
│   ├── src/main/resources/META-INF/services/
│   │   └── software.coley.recaf.plugin.Plugin
│   └── libs/
│
├── recaf-mcp-bridge/                    # Python - stdio bridge
│   ├── pyproject.toml
│   └── src/recaf_mcp_bridge/
│       ├── __init__.py
│       └── bridge.py
│
├── skills/                              # Claude Code skills
│   ├── jar-triage.md
│   ├── deep-analysis.md
│   └── deobfuscation-workflow.md
│
└── docs/plans/
    └── 2026-02-13-recaf-mcp-design.md
```

## Dependencies

### Java Plugin (Gradle)

- `io.modelcontextprotocol.sdk:mcp` - MCP Java SDK
- `org.eclipse.jetty:jetty-server` + `jetty-servlet` - Embedded HTTP
- `dev.toonformat:jtoon` - TOON serialization
- `software.coley:recaf-core` - Recaf core (via JitPack)
- `software.coley:recaf-ui` - Recaf UI (via JitPack)

### Python Bridge

- `mcp` - MCP Python SDK
- `httpx` - HTTP client for Streamable HTTP transport

## References

- [ReVa (Ghidra MCP)](https://github.com/cyberkaida/reverse-engineering-assistant) - Primary architectural reference
- [GhidrAssistMCP](https://github.com/jtang613/GhidrAssistMCP) - All-Java embedded MCP pattern
- [ida-pro-mcp](https://github.com/mrexodia/ida-pro-mcp) - Tool design reference
- [Recaf 4.x](https://github.com/Col-E/Recaf) - Target application
- [Recaf Plugin Workspace](https://github.com/Recaf-Plugins/Recaf-4x-plugin-workspace) - Plugin template
- [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk) - Server SDK
- [TOON Format](https://github.com/toon-format/toon) - Token-efficient serialization
