# Deep Analysis Skill

Depth-first investigation loop for understanding and annotating specific code areas in a Java binary.

## Prerequisites
- Recaf MCP workspace open
- Target class or method identified (from triage or prior analysis)

## Investigation Cycle

Repeat this cycle for each area of interest:

### 1. READ — Decompile the Target
- `decompile-class` or `decompile-method` for the target
- Read and understand the control flow

### 2. UNDERSTAND — Analyze Patterns
- Identify data flow, algorithms, protocols
- Note obfuscation patterns (string encryption, control flow flattening, reflection)
- Identify external dependencies and API usage

### 3. IMPROVE — Rename and Annotate
- `rename-class` / `rename-method` / `rename-field` for meaningful names
- `comment-set` to record analysis findings
- Goal: make the code self-documenting

### 4. VERIFY — Confirm Changes
- `decompile-class` again to verify renaming improved readability
- `decompile-diff` against the before state to review changes

### 5. FOLLOW THREADS — Trace Relationships
- `xrefs-to` — who calls this method/uses this class?
- `callgraph-callers` / `callgraph-callees` — call chain analysis
- `inheritance-subtypes` / `inheritance-supertypes` — type hierarchy
- Add newly discovered interesting targets to investigation queue

### 6. TRACK — Record Progress
- `comment-set` with findings and conclusions
- Note any remaining unknowns or areas needing further investigation

## Decision Points

**When to go deeper:** Complex algorithms, encryption routines, protocol implementations
**When to go wider:** Understanding call chains, data flow between components
**When to stop:** The code's purpose is clear and well-annotated
