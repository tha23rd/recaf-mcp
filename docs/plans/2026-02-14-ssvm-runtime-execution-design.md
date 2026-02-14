# SSVM Runtime Execution Design

**Issue**: recaf-mcp-62g
**Status**: Approved design (rev 2 — incorporates RE practitioner feedback)
**Date**: 2026-02-14

## Summary

Add sandboxed runtime execution of workspace classes using [SSVM](https://github.com/xxDark/SSVM) (a pure-Java bytecode interpreter). Three MCP tools in v1: `vm-invoke-method`, `vm-get-field`, and `vm-run-clinit`.

**Key differentiator**: Stack trace override support. Obfuscators commonly use `Thread.getStackTrace()` for key derivation. SSVM's interpreter frames don't match real JVM frames, producing wrong keys. All three tools accept a `stackTraceOverride` parameter that intercepts `Thread.getStackTrace()` to return a user-specified synthetic trace, enabling correct execution of stack-introspection-dependent code.

## Why SSVM

SSVM is a JVM-in-JVM bytecode interpreter designed for Java deobfuscation/RE. It provides:

- **Sandboxing by default** — code runs in an interpreted VM, cannot crash or access the host Recaf JVM
- **`byte[]` class loading** — `SupplyingClassLoaderInstaller.supplyFromFunctions()` maps directly to Recaf's workspace model
- **Fine-grained interception** — `VMInterface.setInvoker()` can override any method (native or not)
- **Field access** — `VMOperations` provides typed getters for all primitive and reference types
- **Stdout capture** — custom `FileManager` redirects I/O to `ByteArrayOutputStream`
- **Execution limits** — `Interpreter.setMaxIterations()` prevents infinite loops
- **Ecosystem fit** — Matt Coley (Recaf author) contributed to SSVM; Recaf v3 had SSVM integration

Alternatives considered:
- **In-process classloader**: Fast but dangerous — malicious code can crash Recaf or access its internals
- **Subprocess**: Safe but slow (1-2s startup), overlaps with patch-and-run tool (recaf-mcp-al1)

## Architecture

```
Agent (MCP client)
  |
  v
vm-invoke-method / vm-get-field / vm-run-clinit  (MCP tools)
  |
  v
SsvmExecutionProvider  (new tool provider)
  |
  +---> SsvmManager  (lazy init, lifecycle, workspace listener)
  |       |
  |       +---> VirtualMachine  (SSVM instance)
  |       |       +-- SupplyingClassLoaderInstaller <-- reads bytecode from workspace
  |       |       +-- FileManager <-- captures stdout/stderr
  |       |       +-- Interpreter <-- max iterations limit
  |       |       +-- InitializationController <-- whitelist-based clinit gating
  |       |       +-- StackTraceInterceptor <-- synthetic stack trace injection
  |       |
  |       +---> WorkspaceManager.addWorkspaceOpenListener/CloseListener
  |               (auto-reset VM on workspace change)
  |
  +---> InvocationUtil / VMOperations  (SSVM APIs for method calls + field reads)
```

## Cross-Cutting: Stack Trace Override

### The Problem

Obfuscators use `Thread.getStackTrace()` to derive XOR keys from caller identity. SSVM interprets bytecode on a single host thread — `Thread.getStackTrace()` returns SSVM interpreter frames, not the expected `<clinit> -> decrypt -> keyDerive` chain. This produces wrong keys.

### The Solution

All three tools accept an optional `stackTraceOverride` parameter. When provided, `VMInterface.setInvoker()` intercepts `Thread.getStackTrace()` and returns a synthetic `StackTraceElement[]` matching the user-specified frames:

```java
vmi.setInvoker(threadClass, "getStackTrace", "()[Ljava/lang/StackTraceElement;", ctx -> {
    ArrayValue syntheticTrace = buildStackTraceArray(vm, overrideFrames);
    ctx.setResult(syntheticTrace);
    return Result.ABORT;
});
```

### Parameter Format

```json
"stackTraceOverride": [
  {"className": "java.lang.Thread", "methodName": "getStackTrace"},
  {"className": "bigdick55_10", "methodName": "0"},
  {"className": "bigdick55_10", "methodName": "2"},
  {"className": "com.target.IIIiiiiiII", "methodName": "<clinit>"}
]
```

Each entry becomes a `StackTraceElement(className, methodName, fileName, lineNumber)`. `fileName` and `lineNumber` default to `null`/`-1` if omitted.

**Frame indexing matters.** Obfuscators read specific frame indices (e.g. `getStackTrace()[3]`). The override array must be positioned so the correct class appears at the expected index. The override is not just "what classes are on the stack" but "what class is at position N." When in doubt, include `Thread.getStackTrace` at index 0 and the calling chain in order.

## Cross-Cutting: Initialization Control

### The Problem

When `vm-run-clinit` triggers class A's `<clinit>`, SSVM normally auto-initializes all referenced classes (B, C, D...). For obfuscation RE, this pollutes shared caches (like `ConcurrentHashMap` lookup tables) with wrong-order entries. The agent needs to control which classes can initialize and which should be loaded-but-not-initialized.

### The Solution

`vm-run-clinit` accepts an `allowTransitiveInit` whitelist. Only classes on the whitelist can have their `<clinit>` triggered during execution. All other classes are loadable (their bytecode is available) but initialization is deferred until explicitly requested.

Implementation: Hook SSVM's class initialization path to check the whitelist before running `<clinit>`.

### Initialization Tracking

All three tools return a `classesInitialized` field listing which classes had their `<clinit>` run during that tool call. This enables the agent to track initialization state across calls.

## Tools (v1)

### `vm-invoke-method`

Invoke a static method on a workspace class inside the sandboxed SSVM.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `className` | string | yes | Fully qualified class name (dot or slash notation) |
| `methodName` | string | yes | Method name to invoke |
| `methodDescriptor` | string | yes | JVM method descriptor, e.g. `(II)I` |
| `args` | array | no | JSON array of arguments |
| `stackTraceOverride` | array | no | Synthetic stack trace frames (see above) |
| `maxIterations` | int | no | Max bytecode instructions (default 10,000,000) |

**Argument mapping** (JSON -> JVM, inferred from descriptor):
- JSON number -> `int`, `long`, `float`, `double` (based on descriptor char)
- JSON string -> `java.lang.String`
- JSON boolean -> `boolean`
- JSON null -> null reference

**Return format:**
```json
{
  "returnValue": "<serialized result>",
  "returnType": "int",
  "stdout": "captured stdout text",
  "stderr": "captured stderr text",
  "iterationsUsed": 42350,
  "classesInitialized": ["bigdick55_10", "mapped.Class1"]
}
```

**Return value serialization:**
- Primitives: JSON number/boolean
- Strings: JSON string
- Arrays: JSON array (first 1000 elements; `truncated: true` if larger)
- Objects: `{"type": "com.example.Foo", "toString": "Foo@abc"}`
- void: `null`

**Errors** (VM exceptions):
```json
{
  "error": "java.lang.NullPointerException",
  "message": "Cannot invoke method on null",
  "vmStackTrace": "at com.example.Foo.bar(Foo.java:42)\n...",
  "stdout": "any output before crash",
  "stderr": ""
}
```

**Iteration limit exceeded** (distinct from exceptions — agent should retry with higher limit):
```json
{
  "error": "IterationLimitExceeded",
  "message": "Execution exceeded 10000000 iterations. Retry with a higher maxIterations value.",
  "iterationsUsed": 10000000,
  "maxIterations": 10000000,
  "stdout": "any partial output",
  "stderr": ""
}
```

**Scope (v1):** Static methods only. Instance methods require object construction — defer to v2.

### `vm-get-field`

Read a static field value. Class initialization (`<clinit>`) runs automatically if not already initialized.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `className` | string | yes | Fully qualified class name |
| `fieldName` | string | yes | Field name to read |
| `fieldDescriptor` | string | no | JVM field descriptor for disambiguation |
| `stackTraceOverride` | array | no | Synthetic stack trace for clinit-triggered code |

**Return format:**
```json
{
  "className": "com.example.Config",
  "fieldName": "SECRET_KEY",
  "fieldType": "int",
  "value": 42,
  "classInitialized": true,
  "stdout": "any clinit output",
  "stderr": "",
  "classesInitialized": ["com.example.Config"]
}
```

**Scope (v1):** Static fields only.

### `vm-run-clinit`

Explicitly trigger class initialization with controlled ordering. This is the primary tool for string decryption workflows where `<clinit>` populates static fields.

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `className` | string | yes | Class to initialize |
| `stackTraceOverride` | array | no | Synthetic stack trace for stack-introspection-dependent code |
| `allowTransitiveInit` | array | no | Whitelist of class names whose `<clinit>` may fire transitively. If omitted, all transitive initialization is allowed (standard JVM behavior). If provided, only listed classes + the target class can initialize. |
| `maxIterations` | int | no | Max bytecode instructions (default 10,000,000) |

**Return format:**
```json
{
  "className": "com.target.IIIiiiiiII",
  "initialized": true,
  "stdout": "any clinit output",
  "stderr": "",
  "classesInitialized": ["bigdick55_10", "mapped.Class1", "com.target.IIIiiiiiII"],
  "classesDeferred": ["com.other.Unrelated"]
}
```

`classesDeferred` lists classes whose initialization was blocked by the whitelist. This helps the agent discover dependencies they may need to add to the whitelist.

**Example workflow** (from real RLPL sideload analysis):
```
1. vm-run-clinit(className="bigdick55_10")
   → initializes decryption infrastructure

2. vm-get-field(className="bigdick55_10", fieldName="random_array")
   → reads the ACTUAL runtime random array (differs from bytecode)

3. vm-run-clinit(
     className="com.target.IIIiiiiiII",
     stackTraceOverride=[
       {className: "java.lang.Thread", methodName: "getStackTrace"},
       {className: "bigdick55_10", methodName: "0"},
       {className: "bigdick55_10", methodName: "2"},
       {className: "com.target.IIIiiiiiII", methodName: "<clinit>"}
     ],
     allowTransitiveInit=["bigdick55_10", "bigdick55_11", "mapped.Class1"]
   )
   → initializes target class with correct stack trace context

4. vm-get-field(className="com.target.IIIiiiiiII", fieldName="a")
   → reads correctly-populated cipher array

5. vm-invoke-method(
     className="com.target.IIIiiiiiII",
     methodName="a", methodDescriptor="(II)Ljava/lang/String;",
     args=[26880, 0]
   )
   → decrypts URL directly
```

## VM Lifecycle

### Lazy initialization
`SsvmManager` bootstraps the VM on the first `vm-*` tool call (~2-10s). Subsequent calls reuse the running VM instance (~ms latency).

### Auto-reset on workspace change
`SsvmManager` registers as a workspace listener via `WorkspaceManager.addWorkspaceOpenListener()` and close listener. When the workspace changes (class compiled, method assembled, JAR imported), the VM is discarded and will be re-bootstrapped on the next tool call.

### Thread safety
VM access is synchronized — one tool call executes at a time. SSVM is single-threaded internally.

## SSVM Dependency Management

### Git submodule
SSVM is added as a git submodule pinned to a known-good commit on the 2.0.0 branch:
```
git submodule add https://github.com/xxDark/SSVM.git ssvm
```

### Gradle integration
Use Gradle composite build or `includeBuild` to compile SSVM from source as part of the plugin build.

### Shadow JAR shading
SSVM's transitive dependencies must be relocated to avoid conflicts with Recaf:

| Dependency | Relocation |
|-----------|-----------|
| `org.objectweb.asm` | `dev.recafmcp.shadow.ssvm.asm` |
| `software.coley.cafedood` | `dev.recafmcp.shadow.ssvm.cafedood` |

SSVM's `jlinker` dependency has no conflicts and doesn't need relocation.

## Sandboxing

| Concern | Mitigation |
|---------|-----------|
| File I/O | Custom `FileManager` with `ByteArrayOutputStream` for stdout/stderr; all other file ops return errors |
| Network | Not implemented in SSVM (fails safely) |
| Process execution | Override `Runtime.exec` via `VMInterface.setInvoker()` to block |
| Infinite loops | `maxIterations` parameter (default 10M instructions per call) |
| Memory | SSVM uses host heap; large allocations bounded by Recaf's JVM heap limit |
| Stack introspection | `stackTraceOverride` ensures correct frames for key derivation |
| Transitive init | `allowTransitiveInit` whitelist prevents uncontrolled class initialization |

## Known Limitations

- **Performance**: Pure interpreter, ~100-1000x slower than native. Fine for short method calls (decrypt, compute key), not for heavy computation.
- **Threading**: SSVM simulates threads cooperatively on a single host thread. Multi-threaded code may not behave correctly.
- **NIO**: `java.nio.file` not fully implemented in SSVM.
- **ConstantDynamic**: Open PR in SSVM (#28), not yet merged. Affects some Java 11+ bytecode patterns. InvokeDynamic (lambdas) IS supported.
- **Instance methods**: v1 supports static methods only. Instance method support (object construction + invocation) deferred to v2.
- **Bootstrap latency**: First call takes 2-10s. Subsequent calls are fast until workspace changes.

## File Layout

```
recaf-mcp-plugin/src/main/java/dev/recafmcp/
  providers/
    SsvmExecutionProvider.java    (~350 LOC - tool registration, arg mapping, result serialization)
  ssvm/
    SsvmManager.java              (~200 LOC - VM lifecycle, workspace listener, lazy init)
    StackTraceInterceptor.java    (~80 LOC - synthetic stack trace injection)
    InitializationController.java (~100 LOC - whitelist-based clinit gating)
```

## Estimated Scope

| Component | LOC | Effort |
|-----------|-----|--------|
| `SsvmManager` | ~200 | VM lifecycle, workspace listener, class loading bridge |
| `SsvmExecutionProvider` | ~350 | 3 tools, argument mapping, result serialization |
| `StackTraceInterceptor` | ~80 | Thread.getStackTrace() override via VMInterface |
| `InitializationController` | ~100 | Whitelist-based clinit gating + tracking |
| Gradle build integration | ~50 | Submodule, composite build, shading rules |
| Unit tests | ~400 | All tools, stack trace override, init control, workspace reset |
| **Total** | **~1180** | **5-7 days** |

## Future Expansion (v2+)

- `vm-invoke-constructor` + `vm-invoke-instance-method` — object lifecycle
- `vm-eval-expression` — compile + execute Java snippets in VM context
- `vm-set-field` — modify field values before method invocation (test different inputs)
- `vm-get-initialization-state` — query which classes are currently initialized in the VM
- Array/collection deep inspection with pagination
- Custom native method mocking via tool parameters
