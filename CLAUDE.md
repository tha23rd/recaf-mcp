@AGENTS.md

## Project Overview

MCP (Model Context Protocol) server plugin for [Recaf](https://github.com/Col-E/Recaf), enabling AI agents to perform Java reverse engineering tasks. 68 tools across 13 categories.

- Repo: https://github.com/tha23rd/recaf-mcp (private)
- Recaf: https://github.com/Col-E/Recaf
- Recaf docs: https://recaf.coley.software/dev/index.html

## Architecture

- **recaf-mcp-plugin/** — Java Recaf plugin with embedded Jetty HTTP server + MCP SDK
- **recaf-mcp-bridge/** — Python stdio-to-HTTP bridge for tools needing stdin/stdout
- Plugin runs inside Recaf's JVM, exposes Streamable HTTP endpoint at `localhost:8085/mcp`
- Shadow JAR shades Jetty, MCP SDK, and TOON into the plugin JAR

## Build & Test

```bash
cd recaf-mcp-plugin
./gradlew test          # Run unit tests
./gradlew shadowJar     # Build plugin JAR
./gradlew runRecaf      # Build, deploy to ~/.config/Recaf/plugins/, launch Recaf
```

Plugin JAR output: `recaf-mcp-plugin/build/libs/recaf-mcp-plugin-<version>.jar`

## Release Flow

1. Bump version in `recaf-mcp-plugin/build.gradle` (`version = 'x.y.z'`)
2. Commit and push
3. Tag: `git tag vx.y.z && git push origin vx.y.z`
4. GitHub Actions builds the JAR and creates a release with the artifact attached

CI runs on every push to master/main and on PRs.

## Key Conventions

- Tool providers extend `AbstractToolProvider` in `recaf-mcp-plugin/src/main/java/dev/recafmcp/providers/`
- Resource providers in `recaf-mcp-plugin/src/main/java/dev/recafmcp/resources/`
- Recaf dependencies are `compileOnly` (provided at runtime by Recaf)
- Response format: TOON by default (`-Drecaf.mcp.format=json` for JSON)
- Port/host configurable via `RECAF_MCP_PORT`/`RECAF_MCP_HOST` env vars
