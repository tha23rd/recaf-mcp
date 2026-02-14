# Research: Bytecode Pattern Search via MCP (recaf-mcp-07c)

## 1. Current Capability Assessment

### 1.1 `search-instructions` (SearchToolProvider)

**What it does:** Iterates over all JVM classes in the workspace using raw ASM `ClassNode`/`MethodNode` tree API. For each instruction, it formats it via a custom `formatInstruction()` method and matches a user-supplied regex against the text.

**Format produced by `formatInstruction()`:**
- `MethodInsnNode`: `INVOKEVIRTUAL java/io/PrintStream.println(Ljava/lang/String;)V`
- `FieldInsnNode`: `GETSTATIC java/lang/System.out Ljava/io/PrintStream;`
- `TypeInsnNode`: `NEW java/lang/StringBuilder`
- `LdcInsnNode`: `LDC hello world`
- `IntInsnNode`: `BIPUSH 42`
- `VarInsnNode`: `ALOAD 0`
- `InvokeDynamicInsnNode`: **NOT HANDLED** (falls through to `default -> opcodeName` producing just `INVOKEDYNAMIC` with no operand detail)
- `JumpInsnNode`: **NOT HANDLED** (just opcode name, e.g. `IFLE`)
- `IincInsnNode`: **NOT HANDLED** (just opcode name)
- `InsnNode` (simple opcodes): Just opcode name, e.g. `LCMP`, `RETURN`

**Strengths:**
- Regex-based, so flexible single-instruction matching
- Returns structured results with class/method/instruction index
- Configurable `maxClasses` and `maxResultsPerClass` limits

**Weaknesses:**
- Only matches individual instructions, no multi-instruction sequence matching
- Missing `InvokeDynamicInsnNode` formatting (critical for obfuscation analysis)
- Missing `JumpInsnNode`, `IincInsnNode`, `TableSwitchInsnNode`, `LookupSwitchInsnNode` operand formatting
- Does not use Recaf's built-in `InstructionQuery` or `BlwUtil.toString()` (which produces JASM-formatted output)
- Cannot search for patterns like "currentTimeMillis followed by LCMP then IFLE" (multi-instruction sequences)
- 200-class default limit can miss results in large JARs

### 1.2 `search-references` (SearchToolProvider)

**What it does:** Uses Recaf's `ReferenceQuery` via `SearchService.search()`. Finds references to classes, methods, or fields using `contains` string predicates on owner, name, and descriptor.

**Strengths:**
- Leverages Recaf's native search infrastructure (parallelized, covers all classes)
- Handles `invokedynamic` bootstrap method references (via `ReferenceQuery.visitInvokeDynamicInsn()` which inspects BSM handles and args)
- Handles annotations, type references, catch blocks, local variables
- No class count limit (searches entire workspace)

**Weaknesses:**
- Only uses `contains` matching, not regex or exact match
- Result format is opaque (`result.toString()`, `path.toString()`) -- limited structured detail
- Cannot filter by opcode type (e.g. "only INVOKEDYNAMIC calls to X")

### 1.3 `xrefs-to` / `xrefs-from` (XRefToolProvider)

**`xrefs-to`:** Uses `ReferenceQuery` with exact equality predicates on class/member. Finds all locations that reference a specific class or member. Well-structured output.

**`xrefs-from`:** Custom ASM tree walk of a single class. Finds all outgoing method calls, field accesses, and type references from a class or method.

**Strengths:**
- `xrefs-to` is good for "who calls method X?"
- `xrefs-from` is good for "what does method Y call?"
- Both produce structured output with owner/name/descriptor

**Weaknesses:**
- `xrefs-from` does not handle `InvokeDynamicInsnNode` (only `MethodInsnNode`, `FieldInsnNode`, `TypeInsnNode`)
- Neither supports pattern/regex matching on targets
- `xrefs-to` requires knowing exact class name (not useful for "find all invokedynamic sites")

### 1.4 `callgraph-callers` / `callgraph-callees` (CallGraphToolProvider)

**What they do:** Uses Recaf's `CallGraphService` for whole-program method caller/callee analysis via a prebuilt call graph.

**Strengths:**
- Transitive analysis (can follow call chains via `callgraph-path`)
- Whole-program scope

**Weaknesses:**
- Requires explicit `callgraph-build` step
- Only provides method-level granularity (which methods call which), not instruction-level
- Does not surface instruction details (e.g., which instruction index makes the call)

### 1.5 `disassemble-method` / `disassemble-class` (AssemblerToolProvider)

**What they do:** Disassemble to JASM format using Recaf's `JvmAssemblerPipeline`.

**Relevance:** JASM output could be searched as text for patterns, but:
- Only works one class/method at a time (no batch disassembly search)
- Would require the LLM to call disassemble + analyze for each class manually
- Not practical for "search all classes for pattern X"

---

## 2. Gaps Identified

### Gap 1: No `InvokeDynamicInsnNode` formatting in `search-instructions`

**Impact: HIGH.** The existing `search-instructions` tool produces just `INVOKEDYNAMIC` with no operand information when encountering an `invokedynamic` instruction. This makes it impossible to find specific invokedynamic call sites (critical for obfuscation analysis where string encryption, method handle proxies, and lambda factories all use invokedynamic).

### Gap 2: No multi-instruction sequence search

**Impact: HIGH.** Cannot search for bytecode patterns spanning multiple instructions. Example use cases:
- Time-bomb detection: `INVOKESTATIC java/lang/System.currentTimeMillis` followed (within N instructions) by `LCMP` then `IFLE`/`IFGT`
- Encrypted string pattern: `LDC <int>` + `LDC <int>` + `INVOKESTATIC com/foo/a.a(II)Ljava/lang/String;`
- Standard crypto initialization patterns

### Gap 3: Recaf's `InstructionQuery` is not exposed via MCP

**Impact: HIGH.** Recaf already has a built-in `InstructionQuery` class that:
- Takes a `List<StringPredicate>` where each predicate matches one consecutive instruction
- Uses `BlwUtil.toString()` to produce JASM-formatted instruction text (more complete than our `formatInstruction()`)
- Automatically supports multi-instruction sequence matching (sliding window)
- Integrates with `SearchService.search()` for parallelized whole-workspace search
- Returns `InstructionPathNode` results with precise location information

This is the most significant gap: we reimplemented a subset of functionality that already exists in Recaf's API, and the native version is better.

### Gap 4: `xrefs-from` missing `InvokeDynamicInsnNode` handling

**Impact: MEDIUM.** When analyzing outgoing references from a class/method, `invokedynamic` instructions are silently skipped. This means bootstrap method references and lambda targets are invisible.

### Gap 5: No way to find all `invokedynamic` sites across the workspace

**Impact: MEDIUM.** There is no single tool call that answers "find every invokedynamic instruction in the JAR and show me its bootstrap method, name, and descriptor." This is a common need for analyzing obfuscated code.

### Gap 6: No class filter on `search-instructions`

**Impact: LOW-MEDIUM.** The existing tool can only limit by `maxClasses` count, not filter to specific class name patterns. When analyzing a large JAR, you often want to search only within certain packages.

---

## 3. Recommended New Tools / Enhancements

### Recommendation 1: New `search-instruction-sequence` tool using Recaf's `InstructionQuery`

**Priority: P1 (High)**

Expose Recaf's native `InstructionQuery` through the MCP. This gives us multi-instruction sequence matching with the JASM-formatted text that `BlwUtil.toString()` produces.

**Design sketch:**
```java
Tool: "search-instruction-sequence"
Parameters:
  - patterns: string[] (required) - List of regex patterns, one per consecutive instruction.
    Each pattern is matched against JASM-formatted instruction text.
    Example: ["invokestatic java/lang/System.currentTimeMillis", ".*", "lcmp"]
  - classFilter: string (optional) - Regex to filter class names
  - offset / limit: pagination

Implementation:
  List<StringPredicate> predicates = patterns.stream()
      .map(p -> new StringPredicate("regex-partial",
           s -> Pattern.compile(p, CASE_INSENSITIVE).matcher(s).find()))
      .toList();
  InstructionQuery query = new InstructionQuery(predicates);
  Results results = searchService.search(workspace, query);
  // Format results from InstructionPathNode with class/method/index info
```

**Why `InstructionQuery` vs our existing approach:**
- Uses `BlwUtil.toString()` which handles ALL instruction types including `invokedynamic`, switches, jumps, iinc
- Built-in sliding-window multi-instruction matching
- Runs through `SearchService` which parallelizes across classes
- Returns `InstructionPathNode` with precise path information
- No class count limit (searches full workspace)

### Recommendation 2: Fix `search-instructions` to use `BlwUtil.toString()`

**Priority: P1 (High)**

Replace the custom `formatInstruction()` with `BlwUtil.toString()` in the existing `search-instructions` tool. This immediately fixes:
- Missing `InvokeDynamicInsnNode` formatting
- Missing `JumpInsnNode` target label formatting
- Missing `IincInsnNode` variable/increment formatting
- Missing `TableSwitch`/`LookupSwitch` formatting

The format would shift to JASM syntax (e.g., `invokevirtual java/io/PrintStream.println (Ljava/lang/String;)V` instead of `INVOKEVIRTUAL java/io/PrintStream.println(Ljava/lang/String;)V`), which is actually more useful since it matches what `disassemble-method` produces.

**Alternatively**, if we add `search-instruction-sequence` (Recommendation 1), the single-instruction case is just a sequence of length 1. We could deprecate the existing `search-instructions` or keep it as a lightweight variant.

### Recommendation 3: Add `classFilter` parameter to `search-instructions`

**Priority: P2 (Medium)**

Add an optional `classFilter` regex parameter that filters which classes are searched. This lets users scope searches to specific packages.

```java
Parameters:
  - classFilter: string (optional) - Regex matched against class names to limit search scope
    Example: "com/example/obfuscated/.*"
```

### Recommendation 4: Fix `xrefs-from` to handle `InvokeDynamicInsnNode`

**Priority: P2 (Medium)**

Add a case for `InvokeDynamicInsnNode` in the `xrefs-from` bytecode walker:

```java
case InvokeDynamicInsnNode indy -> {
    LinkedHashMap<String, Object> ref = new LinkedHashMap<>();
    ref.put("fromMethod", methodContext);
    ref.put("bootstrapOwner", indy.bsm.getOwner());
    ref.put("bootstrapName", indy.bsm.getName());
    ref.put("bootstrapDescriptor", indy.bsm.getDesc());
    ref.put("callName", indy.name);
    ref.put("callDescriptor", indy.desc);
    ref.put("type", "invokedynamic");
    // Also include bsmArgs for analysis
    if (indy.bsmArgs != null && indy.bsmArgs.length > 0) {
        List<String> bsmArgStrs = new ArrayList<>();
        for (Object arg : indy.bsmArgs) bsmArgStrs.add(arg.toString());
        ref.put("bootstrapArgs", bsmArgStrs);
    }
    methodRefs.add(ref);
}
```

### Recommendation 5: New `search-invokedynamic` specialized tool

**Priority: P3 (Nice to have)**

A purpose-built tool for finding and inspecting invokedynamic call sites across the workspace. Returns rich structured data about each site including bootstrap method, call name/descriptor, and bootstrap arguments.

This could be implemented as a convenience wrapper around `search-instruction-sequence` with pattern `["invokedynamic.*"]`, but with richer output that parses the `InvokeDynamicInsnNode` fields.

**Design sketch:**
```java
Tool: "search-invokedynamic"
Parameters:
  - bootstrapOwner: string (optional) - Filter by bootstrap method owner class
  - bootstrapName: string (optional) - Filter by bootstrap method name
  - callName: string (optional) - Filter by invokedynamic call name
  - classFilter: string (optional) - Regex to filter class names

Returns per match:
  - className, methodName, methodDescriptor
  - instructionIndex
  - bootstrapMethod: {owner, name, desc, tag}
  - callSite: {name, desc}
  - bootstrapArgs: [list of args with types]
```

---

## 4. Feasibility Assessment

| Recommendation | Effort | Risk | Feasibility |
|---|---|---|---|
| 1. `search-instruction-sequence` via `InstructionQuery` | **Low** - Direct use of existing Recaf API, 50-80 lines | Low - API is stable and tested | **Excellent** |
| 2. Fix `search-instructions` to use `BlwUtil.toString()` | **Very Low** - Replace one method call | Very Low - BlwUtil is used by InstructionQuery already | **Excellent** |
| 3. Add `classFilter` to `search-instructions` | **Very Low** - Add one parameter and filter | None | **Excellent** |
| 4. Fix `xrefs-from` for `InvokeDynamicInsnNode` | **Low** - Add one switch case ~15 lines | Low | **Excellent** |
| 5. `search-invokedynamic` specialized tool | **Medium** - Custom ASM visitor, 100-150 lines | Low | **Good** |

### Import Availability

All required classes are already available in the Recaf core dependency:
- `InstructionQuery` - `software.coley.recaf.services.search.query.InstructionQuery`
- `BlwUtil` - `software.coley.recaf.util.BlwUtil`
- `StringPredicate` - `software.coley.recaf.services.search.match.StringPredicate`
- `InstructionPathNode` - `software.coley.recaf.path.InstructionPathNode`
- `InvokeDynamicInsnNode` - `org.objectweb.asm.tree.InvokeDynamicInsnNode` (already in ASM dependency)

### Suggested Implementation Order

1. **Fix `formatInstruction()` -> `BlwUtil.toString()`** (5 min, immediate improvement)
2. **Add `search-instruction-sequence`** (30 min, biggest value add)
3. **Fix `xrefs-from` for invokedynamic** (10 min, bug fix)
4. **Add `classFilter` to search tools** (15 min, ergonomic improvement)
5. **`search-invokedynamic`** (1 hr, specialized convenience tool, optional)

---

## 5. Summary

The most impactful finding is that Recaf already provides `InstructionQuery` -- a purpose-built, tested, parallelized instruction sequence search that handles all instruction types via `BlwUtil.toString()` (JASM format). Our current `search-instructions` tool reimplements a subset of this functionality with a weaker instruction formatter. The highest-value change is to expose `InstructionQuery` through a new `search-instruction-sequence` tool and simultaneously fix the existing `search-instructions` to use `BlwUtil.toString()`.

The invokedynamic gap in both `search-instructions` and `xrefs-from` is a concrete bug affecting real-world obfuscation analysis workflows.
