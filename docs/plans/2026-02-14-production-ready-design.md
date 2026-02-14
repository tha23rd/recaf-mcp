# Production-Ready Recaf MCP — Design

**Date:** 2026-02-14
**Status:** Approved

## Goal

Make recaf-mcp installable and usable by agentic coding tools (Claude Code, Cursor, Codex, OpenCode, etc.) with clear documentation and minimal friction.

## Architecture

Recaf is a GUI application. The MCP plugin runs inside Recaf's JVM as an embedded Jetty HTTP server. Two connection modes:

1. **HTTP mode** — Tools that support HTTP/SSE transport connect directly to `http://localhost:8085/mcp`
2. **Stdio bridge mode** — A Python bridge (`recaf-mcp-bridge`) proxies stdio ↔ HTTP for tools requiring stdin/stdout

## Installation Paths

### Plugin (Java)
- **Primary:** Pre-built JAR from GitHub Releases → drop into `~/.config/Recaf/plugins/`
- **Developer:** Clone repo → `./gradlew shadowJar` → copy JAR to plugins dir

### Bridge (Python)
- Clone repo → `uv tool install ./recaf-mcp-bridge`
- Provides `recaf-mcp-bridge` CLI command on PATH

## Client Configuration

### Claude Code
- HTTP: `claude mcp add --transport http recaf http://localhost:8085/mcp`
- Stdio: `claude mcp add recaf -- recaf-mcp-bridge`

### Generic (Other Tools)
- HTTP endpoint: `http://localhost:8085/mcp` (Streamable HTTP transport)
- Stdio: run `recaf-mcp-bridge` command

## Code Changes

1. **Bridge: add `--host` flag** — Support custom host for remote Recaf instances (default: localhost)
2. **Plugin: `RECAF_MCP_PORT` env var** — Configurable port without JVM flags
3. **Bridge: add `httpx-sse` dependency** — Required by `streamablehttp_client`

## README Structure

1. Hero section (project description, tool count)
2. Quick Start (3 steps)
3. Installation (plugin + bridge)
4. Claude Code Setup (HTTP + stdio)
5. Other Tools (generic HTTP + stdio info)
6. Tool Reference (categorized list)
7. Configuration (port, format)

## Out of Scope

- PyPI publishing
- CI/CD pipelines
- Docker containerization
- Claude Code marketplace plugin
- Headless/CLI mode
