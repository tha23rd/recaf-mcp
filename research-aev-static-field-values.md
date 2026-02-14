# Research: Static Field Value Reading via MCP (recaf-mcp-aev)

## 1. Current Capability Assessment

### 1.1 `field-list` (NavigationToolProvider)

**What it does:** Lists fields of a class with name, descriptor, and access flags. Supports pagination.

**What it returns per field:**
- `name` - field name
- `descriptor` - JVM type descriptor (e.g., `I`, `Ljava/lang/String;`)
- `access` - access modifier bitmask

**What it does NOT return:**
- No `defaultValue` / `constantValue` -- the ConstantValue attribute is completely ignored
- No indication whether a field has a compile-time constant

### 1.2 `class-get-info` (NavigationToolProvider)

**What it does:** Returns detailed class info including all fields. Same field output as `field-list` -- name, descriptor, access only. No constant values.

### 1.3 `search-strings` / `search-numbers` (SearchToolProvider)

**What they do:** Search for string/numeric constants across all classes using Recaf's `StringQuery` and `NumberQuery`. These find constants in the constant pool (LDC instructions, etc.) but do NOT specifically link a constant to the field that holds it.

**Relevance:** These tools can find the value of a constant, but you have to correlate manually -- they tell you "this string appears in class X" not "field Y in class X has this value."

### 1.4 `decompile-class` / `decompile-method` (DecompilerToolProvider)

**What they do:** Decompile a class or method to Java source. Static final fields with ConstantValue attributes will typically appear as `static final int X = 42;` in decompiled output.

**Limitation:** Requires decompiling the entire class to extract a single field value. Slow and imprecise for programmatic use.

### 1.5 `search-instructions` / `search-instruction-sequence` (SearchToolProvider)

**Relevance:** Can find `getstatic` or `putstatic` instructions referencing specific fields, and can search `<clinit>` bodies for initialization patterns. However, they cannot extract the actual values being assigned.

---

## 2. Recaf APIs Available for Reading Field Values

### 2.1 `FieldMember.getDefaultValue()` -- The Primary API

**Source:** `software.coley.recaf.info.member.FieldMember`

```java
/**
 * Fields can declare default values.
 * Only acknowledged by the JVM when hasStaticModifier() is true.
 *
 * @return Default value of the field. Must be an Integer, a Float, a Long,
 * a Double or a String. May be null.
 */
@Nullable
Object getDefaultValue();
```

This maps directly to the JVM `ConstantValue` attribute (JVMS 4.7.2). The JVM only uses this attribute for `static` fields, though the classfile format allows it on any field.

**Supported types (per JVM spec):**
| Field Type | `getDefaultValue()` Returns |
|---|---|
| `int`, `short`, `char`, `byte`, `boolean` | `Integer` |
| `float` | `Float` |
| `long` | `Long` |
| `double` | `Double` |
| `String` | `String` |

**How it works internally:** `JvmClassInfoBuilder$FieldBuilderAdapter` extends ASM's `FieldVisitor`. ASM's `ClassReader` calls `visitField(access, name, descriptor, signature, value)` where `value` is the ConstantValue attribute content. This is passed directly to `BasicFieldMember(name, desc, signature, access, defaultValue)`.

### 2.2 `WorkspaceFieldValueLookup` -- Workspace-Aware Lookup

**Source:** `software.coley.recaf.services.assembler.WorkspaceFieldValueLookup`

This class implements the JASM assembler's `FieldValueLookup` interface and looks up field values across the workspace. It:
1. Resolves the owning class via `workspace.findClass(ownerName)`
2. Gets the field via `classInfo.getDeclaredField(name, descriptor)`
3. Calls `field.getDefaultValue()` and type-checks the result

This is the exact pattern we should use for our MCP tool.

### 2.3 `BasicGetStaticLookup` -- Well-Known JDK Constants

**Source:** `software.coley.recaf.util.analysis.lookup.BasicGetStaticLookup`

Maintains a hardcoded map of well-known JDK static field values (e.g., `Integer.MAX_VALUE`, `Byte.SIZE`, `File.separator`). Used by the `ReInterpreter` for dataflow analysis. Not directly useful for our purposes since we want to read values from the loaded workspace, not hardcoded JDK values.

### 2.4 `ReInterpreter` + `GetStaticLookup` -- Analysis Framework

**Source:** `software.coley.recaf.util.analysis.ReInterpreter`

The `ReInterpreter` is an ASM `Interpreter<ReValue>` that can track values through bytecode dataflow analysis. It has pluggable lookup hooks:
- `GetStaticLookup` -- resolve `getstatic` instructions to known values
- `GetFieldLookup` -- resolve `getfield` instructions
- `InvokeStaticLookup` -- resolve `invokestatic` return values
- `InvokeVirtualLookup` -- resolve `invokevirtual` return values

This is used by the `OpaqueConstantFoldingTransformer` for deobfuscation. While powerful, it is overkill for simply reading field ConstantValue attributes. It would be relevant for future work on `<clinit>` analysis (see section 4).

### 2.5 `JvmClassInfo.getStringConstants()` -- String Pool Extraction

Returns all string constants in the class constant pool. Not field-specific; just enumerates every `CONSTANT_String_info` entry. Already exposed via `search-strings`.

### 2.6 `ClassInfo.getDeclaredField(name, descriptor)` and `getFirstDeclaredFieldByName(name)`

Convenience methods for looking up specific fields:
- `getDeclaredField(String name, String descriptor)` -- exact match on both name and descriptor
- `getFirstDeclaredFieldByName(String name)` -- match by name only, returns first match

Both return `FieldMember` which has `getDefaultValue()`.

---

## 3. Recommended Implementation

### 3.1 Enhancement: Add `defaultValue` to existing `field-list` and `class-get-info` tools

**Priority: P1 (High) -- Minimal effort, high value**

The simplest and most impactful change is to add `defaultValue` to the field output in both existing tools. This requires changing ~3 lines of code.

**Current field output:**
```json
{"name": "MAX_SIZE", "descriptor": "I", "access": 25}
```

**Proposed field output:**
```json
{"name": "MAX_SIZE", "descriptor": "I", "access": 25, "defaultValue": 1024, "defaultValueType": "int"}
```

**Implementation in `NavigationToolProvider.registerFieldList()`:**
```java
List<FieldMember> allFields = node.getValue().getFields();
List<Map<String, Object>> fieldMaps = allFields.stream()
    .map(field -> {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", field.getName());
        f.put("descriptor", field.getDescriptor());
        f.put("access", field.getAccess());
        // Add ConstantValue attribute if present
        Object defaultValue = field.getDefaultValue();
        if (defaultValue != null) {
            f.put("defaultValue", defaultValue);
            f.put("defaultValueType", getValueTypeName(defaultValue));
        }
        return f;
    })
    .collect(Collectors.toList());
```

Same change applies to the fields section in `registerClassGetInfo()`.

**Helper method:**
```java
private static String getValueTypeName(Object value) {
    if (value instanceof Integer) return "int";
    if (value instanceof Long) return "long";
    if (value instanceof Float) return "float";
    if (value instanceof Double) return "double";
    if (value instanceof String) return "String";
    return value.getClass().getSimpleName();
}
```

### 3.2 New Tool: `field-get-value`

**Priority: P1 (High) -- Purpose-built tool for targeted field value lookup**

A dedicated tool that looks up a specific field and returns its compile-time constant value with rich context.

**Tool definition:**
```java
private void registerFieldGetValue() {
    Tool tool = buildTool(
        "field-get-value",
        "Get the compile-time constant value of a static field (ConstantValue attribute). " +
        "Returns the value for fields declared as 'static final' with primitive or String types. " +
        "Returns null if the field has no compile-time constant (e.g., initialized in <clinit>).",
        createSchema(Map.of(
            "className", stringParam("Fully qualified class name (dot or slash notation)"),
            "fieldName", stringParam("Name of the field"),
            "fieldDescriptor", stringParam("Optional: field descriptor (e.g. 'I', 'Ljava/lang/String;') " +
                "for disambiguation when multiple fields share a name")
        ), List.of("className", "fieldName"))
    );

    registerTool(tool, (exchange, args) -> {
        Workspace workspace = requireWorkspace();
        String className = getString(args, "className");
        String fieldName = getString(args, "fieldName");
        String fieldDescriptor = getOptionalString(args, "fieldDescriptor", null);

        String normalized = ClassResolver.normalizeClassName(className);
        ClassPathNode node = ClassResolver.resolveClass(workspace, normalized);
        if (node == null) {
            return createErrorResult(ErrorHelper.classNotFound(normalized, workspace));
        }

        ClassInfo classInfo = node.getValue();
        FieldMember field;
        if (fieldDescriptor != null) {
            field = classInfo.getDeclaredField(fieldName, fieldDescriptor);
        } else {
            field = classInfo.getFirstDeclaredFieldByName(fieldName);
        }

        if (field == null) {
            return createErrorResult("Field '" + fieldName + "' not found in class '" + normalized + "'");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("className", normalized);
        result.put("fieldName", field.getName());
        result.put("fieldDescriptor", field.getDescriptor());
        result.put("access", field.getAccess());
        result.put("isStatic", field.hasStaticModifier());
        result.put("isFinal", field.hasFinalModifier());

        Object defaultValue = field.getDefaultValue();
        if (defaultValue != null) {
            result.put("hasConstantValue", true);
            result.put("value", defaultValue);
            result.put("valueType", getValueTypeName(defaultValue));
        } else {
            result.put("hasConstantValue", false);
            result.put("value", null);
            // Hint about why the value might be null
            if (field.hasStaticModifier()) {
                result.put("note", "Field is static but has no ConstantValue attribute. " +
                    "It may be initialized in <clinit> (static initializer block). " +
                    "Use 'decompile-class' or 'disassemble-method' on <clinit> to see initialization logic.");
            } else {
                result.put("note", "Instance fields cannot have compile-time constant values. " +
                    "Use 'decompile-class' to see initialization in constructors.");
            }
        }

        return createJsonResult(result);
    });
}
```

### 3.3 New Tool: `field-get-all-constants`

**Priority: P2 (Medium) -- Batch extraction of all compile-time constants from a class**

Useful for reverse engineering scenarios where you want to dump all constants at once (e.g., configuration classes, key tables, obfuscation lookup tables).

**Tool definition:**
```java
private void registerFieldGetAllConstants() {
    Tool tool = buildTool(
        "field-get-all-constants",
        "Get all fields with compile-time constant values (ConstantValue attribute) from a class. " +
        "Returns only fields that have a non-null default value. " +
        "Useful for extracting configuration values, keys, magic numbers, etc.",
        createSchema(Map.of(
            "className", stringParam("Fully qualified class name (dot or slash notation)"),
            "includeNonStatic", boolParam("Include non-static fields with default values (default: false)")
        ), List.of("className"))
    );

    registerTool(tool, (exchange, args) -> {
        Workspace workspace = requireWorkspace();
        String className = getString(args, "className");
        boolean includeNonStatic = getBoolean(args, "includeNonStatic", false);

        String normalized = ClassResolver.normalizeClassName(className);
        ClassPathNode node = ClassResolver.resolveClass(workspace, normalized);
        if (node == null) {
            return createErrorResult(ErrorHelper.classNotFound(normalized, workspace));
        }

        ClassInfo classInfo = node.getValue();
        List<Map<String, Object>> constants = classInfo.getFields().stream()
            .filter(f -> f.getDefaultValue() != null)
            .filter(f -> includeNonStatic || f.hasStaticModifier())
            .map(field -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", field.getName());
                entry.put("descriptor", field.getDescriptor());
                entry.put("access", field.getAccess());
                entry.put("value", field.getDefaultValue());
                entry.put("valueType", getValueTypeName(field.getDefaultValue()));
                return entry;
            })
            .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("className", normalized);
        result.put("constantCount", constants.size());
        result.put("totalFieldCount", classInfo.getFields().size());
        result.put("constants", constants);
        return createJsonResult(result);
    });
}
```

### 3.4 Enhancement: Workspace-Wide Constant Search

**Priority: P3 (Low) -- Nice to have, covered by `search-strings` and `search-numbers`**

A tool that searches across all classes for fields with specific constant values. This is largely already covered by `search-strings` and `search-numbers`, so it is lower priority. The main added value would be returning the field name/class context alongside the value, but this can be achieved by combining `search-strings` with `field-list` in a multi-tool workflow.

---

## 4. Feasibility Assessment: What Works vs What Does Not

### 4.1 Compile-Time Constants (ConstantValue Attribute) -- FULLY FEASIBLE

**What the JVM spec says (JVMS 4.7.2):**
> A ConstantValue attribute represents the value of a constant expression. If the ACC_STATIC flag in the access_flags item of the field_info structure is set, then the field represented by the field_info structure is assigned the value represented by its ConstantValue attribute as part of the initialization of the class or interface declaring the field.

**Which fields have ConstantValue attributes:**
- `static final` fields with primitive types (`int`, `long`, `float`, `double`, `boolean`, `char`, `byte`, `short`) where the value is a compile-time constant expression
- `static final String` fields with compile-time constant string values
- The javac compiler emits ConstantValue for these when the right-hand side is a constant expression

**Examples that WILL have ConstantValue:**
```java
static final int MAX_SIZE = 1024;           // Integer(1024)
static final String VERSION = "1.0.0";      // String("1.0.0")
static final long TIMEOUT = 30000L;         // Long(30000)
static final double PI = 3.14159;           // Double(3.14159)
static final boolean DEBUG = false;         // Integer(0)
```

**Examples that will NOT have ConstantValue:**
```java
static final int HASH = "hello".hashCode();  // Computed at runtime in <clinit>
static final String PATH = System.getenv("PATH"); // Runtime value
static final int[] TABLE = {1, 2, 3};       // Array, initialized in <clinit>
static final Object LOCK = new Object();     // Object creation in <clinit>
static int counter = 0;                      // Not final (mutable)
```

**Implementation effort:** Very low. The API (`FieldMember.getDefaultValue()`) already exists and returns the correct types. We just need to expose it through MCP.

### 4.2 Runtime-Initialized Static Fields (`<clinit>`) -- PARTIALLY FEASIBLE

For fields initialized in `<clinit>` (static initializer), the value is not stored in the ConstantValue attribute. Current workarounds:

**What we CAN do now:**
1. Decompile the `<clinit>` method to see the initialization logic (`decompile-class` or `disassemble-method`)
2. Search for `putstatic` instructions targeting the field (`search-instructions` with pattern `putstatic.*ClassName.*fieldName`)
3. Use `search-instruction-sequence` to find the initialization pattern (e.g., `ldc.*` followed by `putstatic.*fieldName`)

**What Recaf has for deeper analysis:**
- `ReInterpreter` with `GetStaticLookup` provides a framework for dataflow analysis through `<clinit>` bytecode
- `OpaqueConstantFoldingTransformer` does limited constant folding (arithmetic on known values)
- These could theoretically trace a value from LDC through to PUTSTATIC, but:
  - They are designed for deobfuscation transforms, not value extraction
  - They require `InheritanceGraphService` setup
  - They handle simple cases (arithmetic on constants) but not complex ones (method calls, object creation)

**Recommendation:** Document as out-of-scope for the initial implementation. The decompiler is the best tool for understanding `<clinit>` initialization. A future `field-analyze-clinit` tool could use `ReInterpreter` dataflow analysis for simple cases, but this is a significant engineering effort.

### 4.3 Instance Field Values -- NOT FEASIBLE

Instance fields are initialized per-object in constructors. There is no static way to know their values without:
- Attaching to a running JVM and inspecting object instances (via `AttachManager`)
- This requires a live process, not just a JAR file

**Recommendation:** Out of scope. The `AttachManager` API exists but our attach tools are currently stubbed (P3).

### 4.4 Array Constants -- NOT FEASIBLE (Static Analysis)

Arrays in Java are always allocated on the heap, even for `static final` fields:
```java
static final int[] XOR_TABLE = {0xDE, 0xAD, 0xBE, 0xEF};
```
This compiles to `<clinit>` bytecode that does `newarray` + repeated `iastore`. The array content is not in a ConstantValue attribute.

**Potential future approach:** Parse `<clinit>` bytecode for simple array initialization patterns (newarray + sequential astore/iastore with constants). This is a well-defined pattern that could be detected, but it requires custom bytecode analysis beyond what `FieldMember.getDefaultValue()` provides.

---

## 5. Implementation Plan

### Phase 1: Immediate (Recommended)

| Change | Effort | Impact |
|---|---|---|
| Add `defaultValue` to `field-list` output | 5 min | High -- zero new tools, immediate value |
| Add `defaultValue` to `class-get-info` field output | 5 min | High -- same as above |
| New `field-get-value` tool | 30 min | High -- targeted single-field lookup |
| New `field-get-all-constants` tool | 20 min | Medium -- batch extraction |

All Phase 1 changes use only `FieldMember.getDefaultValue()` which is already available and tested. No new Recaf service dependencies required. The tools can be added to the existing `NavigationToolProvider` since they are navigation/inspection operations.

### Phase 2: Future Consideration

| Change | Effort | Impact |
|---|---|---|
| `field-analyze-clinit` using `ReInterpreter` | 2-4 hours | Medium -- complex, limited applicability |
| Array constant extraction from `<clinit>` | 2-3 hours | Medium -- pattern-specific |
| Live value reading via `AttachManager` | 4+ hours | High -- requires attach infrastructure |

---

## 6. Summary

The `FieldMember.getDefaultValue()` API in Recaf provides direct, reliable access to the JVM `ConstantValue` attribute for static fields. This covers the most common and most useful case: reading compile-time constant primitives and strings. The API is already proven in production (used by `WorkspaceFieldValueLookup` in the JASM assembler integration).

The recommended implementation is straightforward:
1. Augment existing `field-list` and `class-get-info` with `defaultValue` output (backward-compatible enhancement)
2. Add a `field-get-value` tool for targeted single-field lookup with rich context
3. Add a `field-get-all-constants` tool for batch constant extraction

Runtime-initialized values (`<clinit>`, instance fields, arrays) are out of scope for the initial implementation. The decompiler and instruction search tools already provide adequate workarounds for understanding initialization logic in those cases.
