# SSVM Runtime Execution Design

**Issue**: recaf-mcp-62g
**Status**: Approved design
**Date**: 2026-02-14

## Summary

Add sandboxed runtime execution of workspace classes using [SSVM](https://github.com/xxDark/SSVM) (a pure-Java bytecode interpreter). Two MCP tools in v1: `vm-invoke-method` and `vm-get-field`.

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
vm-invoke-method / vm-get-field  (MCP tools)
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
  |       |
  |       +---> WorkspaceManager.addWorkspaceOpenListener/CloseListener
  |               (auto-reset VM on workspace change)
  |
  +---> InvocationUtil / VMOperations  (SSVM APIs for method calls + field reads)
```

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
| `maxIterations` | int | no | Max bytecode instructions (default 1,000,000) |

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
  "iterationsUsed": 42350
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

**Scope (v1):** Static methods only. Instance methods require object construction which adds complexity — defer to v2.

### `vm-get-field`

Read a static field value after class initialization (`<clinit>` runs automatically).

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| `className` | string | yes | Fully qualified class name |
| `fieldName` | string | yes | Field name to read |
| `fieldDescriptor` | string | no | JVM field descriptor for disambiguation |

**Return format:**
```json
{
  "className": "com.example.Config",
  "fieldName": "SECRET_KEY",
  "fieldType": "int",
  "value": 42,
  "classInitialized": true,
  "stdout": "any clinit output",
  "stderr": ""
}
```

**Scope (v1):** Static fields only.

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
| Infinite loops | `maxIterations` parameter (default 1M instructions per call) |
| Memory | SSVM uses host heap; large allocations bounded by Recaf's JVM heap limit |

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
    SsvmExecutionProvider.java    (~250 LOC - tool registration, arg mapping, result serialization)
  ssvm/
    SsvmManager.java              (~150 LOC - VM lifecycle, workspace listener, lazy init)
```

## Estimated Scope

| Component | LOC | Effort |
|-----------|-----|--------|
| `SsvmManager` | ~150 | VM lifecycle, workspace listener, class loading bridge |
| `SsvmExecutionProvider` | ~250 | Tool registration, argument mapping, result serialization |
| Gradle build integration | ~50 | Submodule, composite build, shading rules |
| Unit tests | ~300 | Mock workspace + real SSVM invocations |
| **Total** | **~750** | **3-5 days** |

## Future Expansion (v2+)

- `vm-invoke-constructor` + `vm-invoke-instance-method` — object lifecycle
- `vm-eval-expression` — compile + execute Java snippets in VM context
- `vm-run-clinit` — explicitly trigger class initialization with detailed output
- `vm-set-field` — modify field values before method invocation (test different inputs)
- Array/collection inspection with pagination
- Custom native method mocking via tool parameters
