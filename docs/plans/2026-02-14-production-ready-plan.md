# Production-Ready Recaf MCP — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make recaf-mcp installable and documented for agentic coding tools (Claude Code, Cursor, Codex, etc.)

**Architecture:** Recaf plugin exposes HTTP MCP server on localhost:8085. Python stdio bridge proxies for tools needing stdin/stdout. README documents both paths.

**Tech Stack:** Java 22 (Gradle/Shadow JAR), Python 3.11+ (hatchling), MCP SDK

---

### Task 1: Add `--host` flag to Python bridge

**Files:**
- Modify: `recaf-mcp-bridge/src/recaf_mcp_bridge/bridge.py:85-93`
- Modify: `recaf-mcp-bridge/pyproject.toml`

**Step 1: Add `--host` argument and `httpx-sse` dependency**

In `bridge.py`, update the `main()` function to accept `--host`:

```python
def main():
    import argparse

    parser = argparse.ArgumentParser(description="Recaf MCP stdio bridge")
    parser.add_argument("--host", type=str, default="localhost", help="Recaf MCP host")
    parser.add_argument("--port", type=int, default=8085, help="Recaf MCP port")
    args = parser.parse_args()

    bridge = RecafMcpBridge(host=args.host, port=args.port)
    asyncio.run(bridge.run())
```

Update `RecafMcpBridge.__init__` to accept `host`:

```python
def __init__(self, host: str = "localhost", port: int = 8085):
    self.port = port
    self.host = host
    self.url = f"http://{host}:{port}/mcp"
    self.server = Server("recaf-mcp-bridge")
    self.backend: ClientSession | None = None
    self._register_handlers()
```

In `pyproject.toml`, add `httpx-sse` to dependencies:

```toml
dependencies = [
    "mcp>=1.0.0",
    "httpx>=0.27.0",
    "httpx-sse>=0.4.0",
]
```

**Step 2: Verify bridge installs and runs help**

Run:
```bash
cd recaf-mcp-bridge && uv sync && uv run recaf-mcp-bridge --help
```
Expected: Shows help with `--host` and `--port` options.

**Step 3: Commit**

```bash
git add recaf-mcp-bridge/src/recaf_mcp_bridge/bridge.py recaf-mcp-bridge/pyproject.toml
git commit -m "feat(bridge): add --host flag and httpx-sse dependency"
```

---

### Task 2: Add env var support for port/host in Java plugin

**Files:**
- Modify: `recaf-mcp-plugin/src/main/java/dev/recafmcp/server/McpServerManager.java:24-26,39-41`
- Modify: `recaf-mcp-plugin/src/main/java/dev/recafmcp/RecafMcpPlugin.java:99-100`

**Step 1: Add env var resolution to McpServerManager**

Add a static helper method and update `start()`:

```java
// In McpServerManager, add after the constants:

/**
 * Resolves the MCP server host from env var or system property.
 * Priority: RECAF_MCP_HOST env > recaf.mcp.host sysprop > default
 */
public static String resolveHost() {
    String env = System.getenv("RECAF_MCP_HOST");
    if (env != null && !env.isBlank()) return env;
    return System.getProperty("recaf.mcp.host", DEFAULT_HOST);
}

/**
 * Resolves the MCP server port from env var or system property.
 * Priority: RECAF_MCP_PORT env > recaf.mcp.port sysprop > default
 */
public static int resolvePort() {
    String env = System.getenv("RECAF_MCP_PORT");
    if (env != null && !env.isBlank()) {
        try { return Integer.parseInt(env); }
        catch (NumberFormatException e) {
            logger.warn("Invalid RECAF_MCP_PORT '{}', using default {}", env, DEFAULT_PORT);
        }
    }
    String prop = System.getProperty("recaf.mcp.port");
    if (prop != null && !prop.isBlank()) {
        try { return Integer.parseInt(prop); }
        catch (NumberFormatException e) {
            logger.warn("Invalid recaf.mcp.port '{}', using default {}", prop, DEFAULT_PORT);
        }
    }
    return DEFAULT_PORT;
}
```

**Step 2: Update plugin to use resolved host/port**

In `RecafMcpPlugin.onEnable()`, change:

```java
// Before:
McpSyncServer mcp = serverManager.start();

// After:
McpSyncServer mcp = serverManager.start(McpServerManager.resolveHost(), McpServerManager.resolvePort());
```

**Step 3: Run existing tests to verify no regression**

Run:
```bash
cd recaf-mcp-plugin && ./gradlew test
```
Expected: All 70 tests pass.

**Step 4: Commit**

```bash
git add recaf-mcp-plugin/src/main/java/dev/recafmcp/server/McpServerManager.java recaf-mcp-plugin/src/main/java/dev/recafmcp/RecafMcpPlugin.java
git commit -m "feat(server): support RECAF_MCP_HOST/PORT env vars for configuration"
```

---

### Task 3: Write comprehensive README

**Files:**
- Rewrite: `README.md`

**Step 1: Write the README**

The README should contain these sections in order. Full content below:

````markdown
# recaf-mcp

MCP (Model Context Protocol) server plugin for [Recaf](https://github.com/Col-E/Recaf), enabling AI agents to perform Java reverse engineering tasks.

73 tools across 13 categories: decompilation, bytecode search, cross-references, call graphs, inheritance analysis, renaming/mapping, compilation, assembly, comments, and more.

## Quick Start

1. **Install the plugin** — download the JAR and drop it into Recaf's plugin directory
2. **Start Recaf** — open a JAR/APK/class file, the MCP server starts automatically on `localhost:8085`
3. **Connect your tool** — point your AI coding assistant at the MCP endpoint

## Prerequisites

- [Recaf 4.x](https://github.com/Col-E/Recaf) (snapshot build)
- Java 22+
- Python 3.11+ and [uv](https://docs.astral.sh/uv/) (only if using the stdio bridge)

## Installation

### Plugin (Recaf)

**Option A: Download release**

Download `recaf-mcp-plugin-0.1.0.jar` from the [Releases](../../releases) page and copy it to your Recaf plugins directory:

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

Then copy `build/libs/recaf-mcp-plugin-0.1.0.jar` to your plugins directory (see table above).

### Stdio Bridge (optional)

The stdio bridge is needed for tools that communicate via stdin/stdout instead of HTTP (e.g., some CLI-based agents).

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

Environment variables take priority over system properties.

## Response Format

Tool responses use [TOON](https://toonformat.dev) by default, a token-optimized serialization format that reduces wire size by ~36% compared to JSON. This saves tokens when working with LLMs. Set `-Drecaf.mcp.format=json` to use plain JSON instead.

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

## License

[TODO: Add license]
````

**Step 2: Verify README renders correctly**

Visual check: the markdown should render with proper tables, collapsible sections, and code blocks.

**Step 3: Commit**

```bash
git add README.md
git commit -m "docs: add comprehensive README with installation and tool reference"
```

---

### Task 4: Final verification

**Step 1: Build plugin to verify no Java compilation errors**

```bash
cd recaf-mcp-plugin && ./gradlew shadowJar
```
Expected: BUILD SUCCESSFUL

**Step 2: Verify bridge installs cleanly**

```bash
cd recaf-mcp-bridge && uv sync && uv run recaf-mcp-bridge --help
```
Expected: Shows help with --host and --port

**Step 3: Run full test suite**

```bash
cd recaf-mcp-plugin && ./gradlew test
```
Expected: All 70 tests pass

**Step 4: Squash commit if needed, final push**

No squash needed — 3 clean commits covering bridge, plugin, and docs.
