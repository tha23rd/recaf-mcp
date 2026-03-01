# recaf-mcp

MCP (Model Context Protocol) server plugin for [Recaf](https://github.com/Col-E/Recaf), enabling AI agents to perform Java reverse engineering tasks.

3 tools across 2 categories for Cloudflare-style Code Mode workflows: dynamic tool discovery and Groovy scripting.

## Quick Start

1. **Install the plugin** — download the JAR and drop it into Recaf's plugin directory
2. **Start Recaf** — open a JAR/APK/class file, the MCP server starts automatically on `localhost:8085`
3. **Connect your tool** — point your AI coding assistant at the MCP endpoint
4. **Start with discovery** — call `search-tools` to find relevant tools before calling task-specific tools
5. **Optional scripting** — enable `execute-recaf-script` only when needed (disabled by default)

## Starting From Scratch

1. Install Recaf 4.x and Java 22+.
2. Install the plugin JAR into your Recaf plugins directory.
3. Launch Recaf (safe default mode):

```bash
# from recaf-mcp/recaf-mcp-plugin while developing
./gradlew runRecaf
```

4. Connect your MCP client to:

```text
http://localhost:8085/mcp
```

5. Open your target JAR/APK/class in Recaf.
6. Call `search-tools` first, then use task-specific tools.
7. Only if you need Groovy scripting, restart Recaf with explicit opt-in:

```bash
RECAF_MCP_SCRIPT_EXECUTION_ENABLED=true ./gradlew runRecaf
```

You can also enable with JVM property:

```bash
java -Drecaf.mcp.script.execution.enabled=true -jar recaf.jar
```

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
git clone https://github.com/tha23rd/recaf-mcp.git
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
| **Tool Discovery** | `search-tools` | Query-driven discovery of available MCP tools by keyword |
| **Groovy Scripting** | `describe-recaf-api`, `execute-recaf-script` | Multi-step reverse-engineering workflows in a single tool call |

## Code Mode Workflow

For token-efficient agent workflows, use this sequence:

1. `search-tools` with a short query (`script`, `api`, `workspace`, `search`) to find relevant tools
2. `describe-recaf-api` to retrieve only the API sections needed for scripting
3. `execute-recaf-script` to combine multiple analysis steps in one roundtrip

Code Mode text outputs for these three tools are size-bounded to 4096 characters (with a deterministic truncation marker) for token efficiency and predictable failure behavior under large responses.

This follows Cloudflare's query-driven Code Mode pattern for MCP tool discovery:
[https://blog.cloudflare.com/code-mode-mcp/](https://blog.cloudflare.com/code-mode-mcp/)

## Code Mode Script Security

`execute-recaf-script` is guarded by an explicit runtime policy and is disabled by default.

- Enable script execution only when needed by setting `RECAF_MCP_SCRIPT_EXECUTION_ENABLED=true` or `-Drecaf.mcp.script.execution.enabled=true`.
- Exposed Groovy bindings are intentionally minimal: `workspace`, `decompilerManager`, `searchService`, `callGraphService`, `inheritanceGraphService`, and `out`.
- Script execution is time-bounded, stdout is capped, and timeout handling interrupts execution with loop/method interruption checks applied at compile time.

This feature executes user-provided code inside the Recaf JVM. Keep it disabled for untrusted sessions and enable it only for controlled workflows.

## Configuration

| Setting | Default | Description |
|---|---|---|
| `RECAF_MCP_HOST` / `-Drecaf.mcp.host` | `127.0.0.1` | Bind address |
| `RECAF_MCP_PORT` / `-Drecaf.mcp.port` | `8085` | Listen port |
| `-Drecaf.mcp.format` | `toon` | Response format: `toon` (token-optimized, ~36% smaller) or `json` |
| `RECAF_MCP_SCRIPT_EXECUTION_ENABLED` / `-Drecaf.mcp.script.execution.enabled` | `false` | Enables `execute-recaf-script` (disabled by default for safety) |

Environment variables take priority over system properties.

## Response Format

Tool responses use [TOON](https://toonformat.dev) by default, a token-optimized serialization format that reduces wire size by ~36% compared to JSON. This saves tokens when working with LLMs. Set `-Drecaf.mcp.format=json` to use plain JSON instead.

## E2E Validation

Validated end-to-end using `./gradlew runRecaf` and a real workspace JAR (`SKlauncher-3.2.18.jar`):

- Opened workspace directly in the Recaf UI
- Verified `search-tools` discovers both `describe-recaf-api` and `execute-recaf-script`
- Verified `describe-recaf-api` keyword filtering
- Verified default policy blocks `execute-recaf-script` with explicit opt-in guidance
- Verified `execute-recaf-script` succeeds when `RECAF_MCP_SCRIPT_EXECUTION_ENABLED=true`

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
