# recaf-mcp

MCP (Model Context Protocol) server plugin for [Recaf](https://github.com/Col-E/Recaf), enabling AI agents to perform Java reverse engineering tasks.

74 tools across 16 categories: decompilation, bytecode search, cross-references, call graphs, inheritance analysis, renaming/mapping, compilation, assembly, comments, sandboxed execution, dynamic tool discovery, Groovy scripting, and more.

## Quick Start

1. **Install the plugin** — download the JAR and drop it into Recaf's plugin directory
2. **Start Recaf** — open a JAR/APK/class file, the MCP server starts automatically on `localhost:8085`
3. **Connect your tool** — point your AI coding assistant at the MCP endpoint
4. **Start with discovery** — call `search-tools` to find relevant tools before calling task-specific tools

## Prerequisites

- [Recaf 4.x](https://github.com/Col-E/Recaf) (snapshot build)
- Java 22+
- Python 3.11+ and [uv](https://docs.astral.sh/uv/) (only if using the stdio bridge)

## Installation

### Plugin (Recaf)

**Option A: Download release**

Download `recaf-mcp-plugin-0.1.1.jar` from the [Releases](../../releases) page and copy it to your Recaf plugins directory:

| OS | Plugin Directory |
|---------|----------------------------------------------|
| Linux | `~/.config/Recaf/plugins/` |
| macOS | `~/Library/Application Support/Recaf/plugins/` |
| Windows | `%APPDATA%/Recaf/plugins/` |

**Option B: Build from source**

```bash
git clone https://github.com/<owner>/recaf-mcp.git
cd recaf-mcp/recaf-mcp-plugin
./gradlew shadowJar
```

Then copy `build/libs/recaf-mcp-plugin-0.1.1.jar` to your plugins directory (see table above).

### Stdio Bridge (optional)

The stdio bridge is needed for tools that communicate via stdin/stdout instead of HTTP (e.g., Claude Desktop).

```bash
cd recaf-mcp
uv tool install ./recaf-mcp-bridge
```

This installs the `recaf-mcp-bridge` command globally.

## Connecting Your AI Tool

### Claude Code

**HTTP mode** (recommended — no bridge needed):

```bash
claude mcp add --transport http recaf http://localhost:8085/mcp
```

**Stdio mode** (if HTTP transport is not supported):

```bash
claude mcp add recaf -- recaf-mcp-bridge
```

### Other Tools (Cursor, VS Code, Codex, OpenCode, etc.)

**If the tool supports HTTP/SSE MCP servers**, point it at:

```
http://localhost:8085/mcp
```

This is a Streamable HTTP endpoint. Example configurations:

<details>
<summary>VS Code / Cursor (<code>.vscode/mcp.json</code>)</summary>

```json
{
  "servers": {
    "recaf": {
      "type": "http",
      "url": "http://localhost:8085/mcp"
    }
  }
}
```

</details>

<details>
<summary>Claude Desktop (<code>claude_desktop_config.json</code>)</summary>

```json
{
  "mcpServers": {
    "recaf": {
      "command": "recaf-mcp-bridge",
      "args": []
    }
  }
}
```

</details>

**If the tool only supports stdio**, use the bridge:

```bash
recaf-mcp-bridge [--host localhost] [--port 8085]
```

## Tools

| Category | Tools | Description |
|---|---|---|
| **Navigation** | `class-list`, `class-count`, `class-get-info`, `class-get-hierarchy`, `method-list`, `field-list`, `field-get-value`, `package-list`, `class-search-by-name`, `field-get-all-constants` | Browse classes, methods, fields, and packages |
| **Decompiler** | `decompile-class`, `decompile-method`, `decompiler-list`, `decompiler-set`, `decompile-diff` | Decompile to Java source |
| **Search** | `search-strings`, `search-strings-count`, `search-numbers`, `search-references`, `search-declarations`, `search-instructions`, `search-instruction-sequence`, `search-files`, `search-text-in-decompilation` | Search bytecode, strings, numbers, references |
| **Cross-References** | `xrefs-to`, `xrefs-from`, `xrefs-count` | Track references between classes and members |
| **Call Graph** | `callgraph-callers`, `callgraph-callees`, `callgraph-path`, `callgraph-build` | Analyze method call relationships |
| **Mapping** | `rename-class`, `rename-method`, `rename-field`, `rename-variable`, `mapping-apply`, `mapping-export`, `mapping-list-formats` | Rename and apply obfuscation mappings |
| **Workspace** | `workspace-open`, `workspace-close`, `workspace-get-info`, `workspace-export`, `workspace-list-resources`, `workspace-add-supporting`, `workspace-get-history` | Manage workspace files |
| **Compiler** | `compile-java`, `compile-check`, `phantom-generate` | Compile Java source, generate phantom stubs |
| **Inheritance** | `inheritance-subtypes`, `inheritance-supertypes`, `inheritance-common-parent` | Analyze class hierarchy |
| **Comment** | `comment-set`, `comment-get`, `comment-search`, `comment-delete` | Annotate classes and members |
| **Assembler** | `disassemble-method`, `assemble-method`, `disassemble-class`, `assemble-class` | JASM assembly and disassembly |
| **Transform** | `transform-list`, `transform-apply`, `transform-apply-batch`, `transform-preview`, `transform-undo` | Apply bytecode transformers |
| **SSVM Execution** | `vm-invoke-method`, `vm-get-field`, `vm-run-clinit` | Sandboxed bytecode execution for string decryption, key derivation, and static analysis |
| **Tool Discovery** | `search-tools` | Query-driven discovery of available MCP tools by keyword |
| **Groovy Scripting** | `describe-recaf-api`, `execute-recaf-script` | Multi-step reverse-engineering workflows in a single tool call |
| **Attach** | `attach-list-vms`, `attach-connect`, `attach-load-classes`, `attach-disconnect` | Attach to running JVMs *(TODO)* |

## Code Mode Workflow

For token-efficient agent workflows, use this sequence:

1. `search-tools` with a short query (`decompile`, `search string`, `callgraph`) to find relevant tools
2. `describe-recaf-api` to retrieve only the API sections needed for scripting
3. `execute-recaf-script` to combine multiple analysis steps in one roundtrip

This follows Cloudflare's query-driven Code Mode pattern for MCP tool discovery:
[https://blog.cloudflare.com/code-mode-mcp/](https://blog.cloudflare.com/code-mode-mcp/)

## Resources

| URI | Description |
|---|---|
| `recaf://workspace` | Current workspace metadata |
| `recaf://classes` | List of all classes with basic info |
| `recaf://class/{name}` | Full class info and decompiled source (use dot notation, e.g., `java.lang.String`) |

## Configuration

| Setting | Default | Description |
|---|---|---|
| `RECAF_MCP_HOST` / `-Drecaf.mcp.host` | `127.0.0.1` | Bind address |
| `RECAF_MCP_PORT` / `-Drecaf.mcp.port` | `8085` | Listen port |
| `-Drecaf.mcp.format` | `toon` | Response format: `toon` (token-optimized, ~36% smaller) or `json` |
| `-Drecaf.mcp.cache.enabled` | `true` | Enable/disable plugin-side tool/resource caches |
| `-Drecaf.mcp.cache.ttl.seconds` | `120` | Cache TTL in seconds for plugin-side caches |
| `-Drecaf.mcp.cache.max.entries` | `1000` | Maximum entries per plugin-side cache |

Environment variables take priority over system properties.

## Response Format

Tool responses use [TOON](https://toonformat.dev) by default, a token-optimized serialization format that reduces wire size by ~36% compared to JSON. This saves tokens when working with LLMs. Set `-Drecaf.mcp.format=json` to use plain JSON instead.

## Cache Compatibility

The new Code Mode tools (`search-tools`, `describe-recaf-api`, `execute-recaf-script`) are compatible with the existing cache implementation.

- Existing cache-backed tools (`decompile-class`, `search-strings`, resources) still use the same cache keys and revision tracking.
- Repeated calls return identical payloads and retain expected cache speedups.
- Cache behavior remains controlled by:
  - `-Drecaf.mcp.cache.enabled`
  - `-Drecaf.mcp.cache.ttl.seconds`
  - `-Drecaf.mcp.cache.max.entries`

## E2E Validation

Validated end-to-end using `./gradlew runRecaf` and a real workspace JAR (`SKlauncher-3.2.18.jar`):

- Opened workspace via `workspace-open`
- Verified `search-tools` discovers both `describe-recaf-api` and `execute-recaf-script`
- Verified `describe-recaf-api` keyword filtering
- Verified `execute-recaf-script` execution against live workspace data
- Repeated cache-backed calls (`decompile-class`, `search-strings`) to confirm cache compatibility

## Building & Development

```bash
# Build plugin
cd recaf-mcp-plugin
./gradlew shadowJar

# Run tests
./gradlew test

# Build + deploy + launch Recaf (dev convenience)
./gradlew runRecaf

# Install bridge for development
cd ../recaf-mcp-bridge
uv sync
uv run recaf-mcp-bridge --help
```
