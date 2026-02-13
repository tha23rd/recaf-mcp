# JAR Triage Skill

Structured breadth-first analysis of a Java binary for initial intelligence gathering.

## Prerequisites
- Recaf MCP server running with a workspace open (or a file path to open)

## Workflow

### Step 1: Open Workspace (if needed)
If no workspace is open, use `workspace-open` with the provided file path.

### Step 2: Get Overview
- `workspace-get-info` — class count, file count, resource structure
- `class-count` — total JVM classes
- `package-list` — unique package names to understand code organization

### Step 3: Sample Strings
- `search-strings` with `query=""` and `limit=50` — get first 50 string constants
- Look for: URLs, API keys, config paths, error messages, library identifiers

### Step 4: Identify Entry Points
Search for common Java entry point patterns:
- `search-declarations` with `name="main"` — find main methods
- `search-references` with `className="javax/servlet"` — Servlet classes
- `search-references` with `className="org/springframework"` — Spring controllers
- `search-references` with `className="android/app/Activity"` — Android activities

### Step 5: Survey Package Structure
- For each top-level package, use `class-list` with `packageFilter` to get a class sample
- Identify naming patterns (obfuscated single-letter names vs. meaningful names)

### Step 6: Decompile Key Classes
- Decompile 3-5 key classes identified from entry points or largest packages
- `decompile-class` for each target
- Note: focus on understanding program structure, not every detail

### Step 7: Report Findings
Summarize:
- Application type (Android app, web service, library, CLI tool)
- Obfuscation level (none, light, heavy)
- Key packages and their likely purposes
- Entry points found
- Notable strings or patterns
- Areas requiring deeper investigation
