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


<!-- BEGIN BEADS INTEGRATION -->
## Issue Tracking with bd (beads)

**IMPORTANT**: This project uses **bd (beads)** for ALL issue tracking. Do NOT use markdown TODOs, task lists, or other tracking methods.

### Why bd?

- Dependency-aware: Track blockers and relationships between issues
- Git-friendly: Auto-syncs to JSONL for version control
- Agent-optimized: JSON output, ready work detection, discovered-from links
- Prevents duplicate tracking systems and confusion

### Quick Start

**Check for ready work:**

```bash
bd ready --json
```

**Create new issues:**

```bash
bd create "Issue title" --description="Detailed context" -t bug|feature|task -p 0-4 --json
bd create "Issue title" --description="What this issue is about" -p 1 --deps discovered-from:bd-123 --json
```

**Claim and update:**

```bash
bd update bd-42 --status in_progress --json
bd update bd-42 --priority 1 --json
```

**Complete work:**

```bash
bd close bd-42 --reason "Completed" --json
```

### Issue Types

- `bug` - Something broken
- `feature` - New functionality
- `task` - Work item (tests, docs, refactoring)
- `epic` - Large feature with subtasks
- `chore` - Maintenance (dependencies, tooling)

### Priorities

- `0` - Critical (security, data loss, broken builds)
- `1` - High (major features, important bugs)
- `2` - Medium (default, nice-to-have)
- `3` - Low (polish, optimization)
- `4` - Backlog (future ideas)

### Workflow for AI Agents

1. **Check ready work**: `bd ready` shows unblocked issues
2. **Claim your task**: `bd update <id> --status in_progress`
3. **Work on it**: Implement, test, document
4. **Discover new work?** Create linked issue:
   - `bd create "Found bug" --description="Details about what was found" -p 1 --deps discovered-from:<parent-id>`
5. **Complete**: `bd close <id> --reason "Done"`

### Auto-Sync

bd automatically syncs with git:

- Exports to `.beads/issues.jsonl` after changes (5s debounce)
- Imports from JSONL when newer (e.g., after `git pull`)
- No manual export/import needed!

### Important Rules

- ✅ Use bd for ALL task tracking
- ✅ Always use `--json` flag for programmatic use
- ✅ Link discovered work with `discovered-from` dependencies
- ✅ Check `bd ready` before asking "what should I work on?"
- ❌ Do NOT create markdown TODO lists
- ❌ Do NOT use external issue trackers
- ❌ Do NOT duplicate tracking systems

For more details, see README.md and docs/QUICKSTART.md.

<!-- END BEADS INTEGRATION -->