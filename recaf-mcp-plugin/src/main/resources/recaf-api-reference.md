# Recaf API Reference for Groovy Scripts

Scripts run in a Groovy shell with the following variables pre-bound.
Return a value (or set `result`) to produce output from the tool.

`execute-recaf-script` is disabled by default. Enable explicitly with:

- `RECAF_MCP_SCRIPT_EXECUTION_ENABLED=true`, or
- `-Drecaf.mcp.script.execution.enabled=true`

---

## Workspace and Classes

Variable: `workspace` (`software.coley.recaf.workspace.model.Workspace`)

```groovy
// Stream all JVM classes
workspace.jvmClassesStream()                    // Stream<ClassPathNode>

// Stream only primary resource classes (application, not libraries)
workspace.getPrimaryResource().jvmClassBundleStream()  // Stream<JvmClassBundle>

// Stream resource files (non-class)
workspace.filesStream()                         // Stream<FilePathNode>

// Get primary resource
workspace.getPrimaryResource()                  // WorkspaceResource

// Find a specific class (dot or slash notation)
workspace.findClass("com/example/Foo")          // ClassPathNode (or null)
workspace.findClass("com.example.Foo")          // also works
```

ClassPathNode / ClassInfo:

```groovy
ClassPathNode node = workspace.findClass("com/example/Foo")
if (node == null) return "Class not found"
ClassInfo info = node.getValue()
info.getName()               // "com/example/Foo" (slash notation)
info.isJvmClass()            // true for .class files
JvmClassInfo jvmInfo = info.asJvmClass()
jvmInfo.getMethods()         // Collection<MethodMember>
jvmInfo.getFields()          // Collection<FieldMember>
jvmInfo.getSuperName()       // "java/lang/Object"
jvmInfo.getInterfaces()      // List<String>
```

---

## Decompiler

Variable: `decompilerManager` (`software.coley.recaf.services.decompile.DecompilerManager`)

```groovy
// Decompile a class to Java source (blocks up to 10s)
import java.util.concurrent.TimeUnit
def result = decompilerManager.decompile(workspace, jvmClassInfo).get(10, TimeUnit.SECONDS)
result.getType()   // DecompileResult.ResultType.SUCCESS or FAILURE
result.getText()   // String source code (null on failure)
```

---

## Search

Variable: `searchService` (`software.coley.recaf.services.search.SearchService`)

```groovy
import software.coley.recaf.services.search.query.*
import software.coley.recaf.services.search.match.*

// Search string constants (case-insensitive contains)
def predicate = new StringPredicate("contains-ignore-case", s -> s?.toLowerCase()?.contains("password"))
def results = searchService.search(workspace, new StringQuery(predicate))

// Search class/method/field references
def ownerPred = new StringPredicate("contains", s -> s?.contains("HttpClient"))
def refResults = searchService.search(workspace, new ReferenceQuery(ownerPred))

// Search declarations by regex
def declResults = searchService.search(workspace, new DeclarationQuery(
    null,
    new StringPredicate("regex", s -> s?.matches(".*encrypt.*")),
    null
))

// Iterate results
for (def r : results) {
    def path = r.getPath()
    def classNode = path.getPathOfType(software.coley.recaf.info.ClassInfo)
    if (classNode) println classNode.getValue().getName()
}
```

---

## Call Graph

Variable: `callGraphService` (`software.coley.recaf.services.callgraph.CallGraphService`)

```groovy
// Build a call graph for the current workspace
def graph = callGraphService.newCallGraph(workspace)

// Or fetch the current workspace call graph if already available
def current = callGraphService.getCurrentWorkspaceCallGraph()
```

---

## Inheritance

Variable: `inheritanceGraphService` (`software.coley.recaf.services.inheritance.InheritanceGraphService`)

```groovy
def graph = inheritanceGraphService.getOrCreateInheritanceGraph(workspace)
def v = graph.getVertex("com/example/Foo")
v.getParents()            // Set<InheritanceVertex> direct parents
v.getChildren()           // Set<InheritanceVertex> direct children
v.getAllParents()         // Set<InheritanceVertex> all ancestors
v.getAllChildren()        // Set<InheritanceVertex> all descendants
```

---

## SSVM (vmService)

Variable: `vmService` (`dev.recafmcp.ssvm.SsvmScriptFacade`) — a thin Groovy-friendly facade
over the sandboxed Static Virtual Machine. Lets you invoke obfuscated decryptors and read
their decoded outputs without running the whole jar. Bootstrap is lazy (~2-10s on first
call) and requires JDK 11-22 boot classes on the host (set `SSVM_BOOT_JDK=/path/to/jdk`
on the Recaf JVM if running under JDK 23+). Binding is absent if no compatible JDK.

```groovy
// Invoke a static method by descriptor. Primitive args coerce from boxed values; null
// is only valid for reference slots. Returns the boxed primitive, a String for
// java.lang.String returns, or the raw ObjectValue for other refs.
def sum = vmService.invokeStatic("com/foo/Math", "add", "(II)I", 7, 35)        // -> 42
def msg = vmService.invokeStatic("com/foo/Decryptor", "decrypt",
                                 "(Ljava/lang/String;)Ljava/lang/String;", "ciphertext")

// Read / write static fields. Descriptor optional unless the class has same-named
// fields with different types (rare).
def k = vmService.getStaticField("com/foo/Decryptor", "key")                  // -> Number
vmService.setStaticField("com/foo/Decryptor", "key", 0xCAFEBABE)
vmService.setStaticField("com/foo/Decryptor", "tag", "Ljava/lang/String;", "x")

// Force <clinit> on a class (useful before reading lazily-initialized statics).
vmService.runClinit("com/foo/Decryptor")

// Drain captured stdout/stderr from anything the VM printed.
def out = vmService.drainStdout()

// Power-user escape hatches: drop down to the raw SSVM API.
def vm    = vmService.vm()             // dev.xdark.ssvm.VirtualMachine
def util  = vmService.invocationUtil() // for invokeVirtual / array args / non-static
def ops   = vmService.operations()     // VMOperations: arrays, allocations, etc.
def cls   = vmService.findClass("com/foo/Decryptor")  // InstanceClass
```

Failures throw `IllegalArgumentException` (bad descriptor / missing class / missing
member / wrong arg count) or `IllegalStateException` (SSVM unavailable on this JVM).
The script-execution wrapper surfaces the message in the tool result.

---

## Utility Examples

```groovy
// Convert class name notations
"com.example.Foo".replace('.', '/')   // "com/example/Foo"
"com/example/Foo".replace('/', '.')   // "com.example.Foo"

// Collect from stream
workspace.jvmClassesStream()
    .map { it.getValue().getName() }
    .filter { it.startsWith("com/example") }
    .collect(java.util.stream.Collectors.toList())
```

---

## Output and Return Values

- Scripts should `return` a value, or the last expression is returned automatically.
- Returned objects are serialized via `.toString()`. Use maps, lists, or strings for structured output.
- Print statements (`println`) are captured and included in output.
- Example: `return workspace.jvmClassesStream().count()` returns the class count.
