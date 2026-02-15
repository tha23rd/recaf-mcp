package dev.recafmcp.providers;

import dev.recafmcp.ssvm.InitializationController;
import dev.recafmcp.ssvm.StackTraceInterceptor;
import dev.recafmcp.ssvm.SsvmManager;
import dev.xdark.ssvm.VirtualMachine;
import dev.xdark.ssvm.classloading.SupplyingClassLoaderInstaller;
import dev.xdark.ssvm.execution.Interpreter;
import dev.xdark.ssvm.execution.VMException;
import dev.xdark.ssvm.invoke.Argument;
import dev.xdark.ssvm.invoke.InvocationUtil;
import dev.xdark.ssvm.mirror.member.JavaField;
import dev.xdark.ssvm.mirror.member.JavaMethod;
import dev.xdark.ssvm.mirror.type.InstanceClass;
import dev.xdark.ssvm.operation.VMOperations;
import dev.xdark.ssvm.value.ArrayValue;
import dev.xdark.ssvm.value.InstanceValue;
import dev.xdark.ssvm.value.ObjectValue;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.workspace.WorkspaceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP tool provider for sandboxed SSVM execution tools.
 * <p>
 * Provides tools for invoking static methods and reading static fields inside
 * SSVM's sandboxed bytecode interpreter. Workspace classes are loaded into SSVM
 * via {@link SsvmManager}'s class loading bridge.
 * <p>
 * <b>v1 scope:</b> static methods and static fields only.
 */
public class SsvmExecutionProvider extends AbstractToolProvider {
	private static final Logger logger = Logging.get(SsvmExecutionProvider.class);
	private static final int DEFAULT_MAX_ITERATIONS = 10_000_000;
	private static final int MAX_ARRAY_ELEMENTS = 1000;

	private final SsvmManager ssvmManager;

	public SsvmExecutionProvider(McpSyncServer server,
	                             WorkspaceManager workspaceManager,
	                             SsvmManager ssvmManager) {
		super(server, workspaceManager);
		this.ssvmManager = ssvmManager;
	}

	@Override
	public void registerTools() {
		registerVmInvokeMethod();
		registerVmGetField();
		registerVmRunClinit();
	}

	// ---- vm-invoke-method ----

	private void registerVmInvokeMethod() {
		Tool tool = Tool.builder()
				.name("vm-invoke-method")
				.description("Invoke a static method on a workspace class inside the sandboxed SSVM bytecode interpreter. " +
						"Returns the method's return value, captured stdout/stderr, and execution metadata. " +
						"Useful for decrypting strings, computing keys, and running short static methods " +
						"from obfuscated code. v1: static methods only.")
				.inputSchema(createSchema(
						mapOf(
								"className", stringParam("Fully qualified class name (dot or slash notation, e.g. 'com.example.Foo' or 'com/example/Foo')"),
								"methodName", stringParam("Name of the static method to invoke"),
								"methodDescriptor", stringParam("JVM method descriptor (e.g. '(II)I', '(Ljava/lang/String;)V')"),
								"args", Map.of(
										"type", "array",
										"description", "JSON array of arguments. Types are inferred from the method descriptor: " +
												"JSON number -> int/long/float/double, JSON string -> java.lang.String, " +
												"JSON boolean -> boolean (0/1), JSON null -> null reference. " +
												"Omit for no-arg methods.",
										"items", Map.of()
								),
								"maxIterations", intParam("Max bytecode instructions before aborting (default: 10,000,000). " +
										"Increase for methods with heavy computation."),
								"stackTraceOverride", stackTraceOverrideParam()
						),
						List.of("className", "methodName", "methodDescriptor")
				))
				.build();

		registerTool(tool, (exchange, args) -> {
			requireWorkspace();

			String className = getString(args, "className");
			String methodName = getString(args, "methodName");
			String methodDescriptor = getString(args, "methodDescriptor");
			int maxIterations = getInt(args, "maxIterations", DEFAULT_MAX_ITERATIONS);

			// Normalize class name: accept both dot and slash notation
			String dotClassName = className.replace('/', '.');

			// Extract the optional args array
			List<Object> jsonArgs = getOptionalList(args, "args");

			// Parse optional stack trace override
			List<StackTraceInterceptor.StackFrame> stackFrames = parseStackTraceFrames(
					getOptionalList(args, "stackTraceOverride"));

			return invokeStaticMethod(dotClassName, methodName, methodDescriptor, jsonArgs, maxIterations, stackFrames);
		});
	}

	// ---- Core invocation logic ----

	/**
	 * Invoke a static method in the SSVM and return a structured result.
	 */
	private io.modelcontextprotocol.spec.McpSchema.CallToolResult invokeStaticMethod(
			String dotClassName, String methodName, String methodDescriptor,
			List<Object> jsonArgs, int maxIterations,
			List<StackTraceInterceptor.StackFrame> stackFrames) {

		// Save and set max iterations (Interpreter.setMaxIterations is static/global)
		int previousMaxIterations = Interpreter.getMaxIterations();
		StackTraceInterceptor interceptor = null;
		VirtualMachine vm = null;
		try {
			Interpreter.setMaxIterations(maxIterations);

			// Bootstrap VM (lazy, ~2-10s first time)
			vm = ssvmManager.getVm();

			// Attach the current thread to SSVM — required before any bytecode execution.
			// MCP tool handlers run on Jetty/Reactor threads which are not registered with SSVM.
			vm.getThreadManager().attachCurrentThread();

			// Install stack trace interceptor if override frames were provided
			if (stackFrames != null && !stackFrames.isEmpty()) {
				interceptor = new StackTraceInterceptor(vm, stackFrames);
				interceptor.install();
			}
			SupplyingClassLoaderInstaller.Helper helper = ssvmManager.getHelper();
			InvocationUtil util = ssvmManager.getInvocationUtil();
			VMOperations ops = ssvmManager.getOperations();

			// Load the class
			InstanceClass cls;
			try {
				cls = helper.loadClass(dotClassName);
			} catch (ClassNotFoundException e) {
				return createErrorResult("Class not found: " + dotClassName +
						". Ensure the class exists in the workspace.");
			}

			// Find the method
			JavaMethod method = cls.getMethod(methodName, methodDescriptor);
			if (method == null) {
				return createErrorResult(buildMethodNotFoundMessage(
						dotClassName, methodName, methodDescriptor, cls));
			}

			// Verify it is static
			if ((method.getModifiers() & Opcodes.ACC_STATIC) == 0) {
				return createErrorResult("Method " + methodName + methodDescriptor +
						" is not static. v1 supports static methods only.");
			}

			// Map JSON arguments to SSVM Arguments
			Argument[] ssvmArgs;
			try {
				ssvmArgs = mapJsonArgsToSsvm(vm, methodDescriptor, jsonArgs);
			} catch (IllegalArgumentException e) {
				return createErrorResult("Argument mapping error: " + e.getMessage());
			}

			// Determine return type from descriptor
			char returnType = parseReturnType(methodDescriptor);

			// Invoke the method
			Object returnValue;
			String returnTypeName;
			try {
				switch (returnType) {
					case 'V':
						util.invokeVoid(method, ssvmArgs);
						returnValue = null;
						returnTypeName = "void";
						break;
					case 'I':
						returnValue = util.invokeInt(method, ssvmArgs);
						returnTypeName = "int";
						break;
					case 'J':
						returnValue = util.invokeLong(method, ssvmArgs);
						returnTypeName = "long";
						break;
					case 'F':
						returnValue = util.invokeFloat(method, ssvmArgs);
						returnTypeName = "float";
						break;
					case 'D':
						returnValue = util.invokeDouble(method, ssvmArgs);
						returnTypeName = "double";
						break;
					case 'Z':
						returnValue = util.invokeInt(method, ssvmArgs) != 0;
						returnTypeName = "boolean";
						break;
					case 'B':
						returnValue = (byte) util.invokeInt(method, ssvmArgs);
						returnTypeName = "byte";
						break;
					case 'C':
						returnValue = String.valueOf((char) util.invokeInt(method, ssvmArgs));
						returnTypeName = "char";
						break;
					case 'S':
						returnValue = (short) util.invokeInt(method, ssvmArgs);
						returnTypeName = "short";
						break;
					case 'L':
					case '[':
						ObjectValue objResult = util.invokeReference(method, ssvmArgs);
						returnValue = serializeObjectValue(vm, ops, objResult, returnType, methodDescriptor);
						returnTypeName = describeReturnType(methodDescriptor);
						break;
					default:
						return createErrorResult("Unsupported return type: " + returnType);
				}
			} catch (VMException e) {
				return handleVmException(vm, e, maxIterations);
			}

			// Capture stdout/stderr
			String stdout = ssvmManager.getAndResetStdout();
			String stderr = ssvmManager.getAndResetStderr();

			// Build result
			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("returnValue", returnValue);
			result.put("returnType", returnTypeName);
			result.put("stdout", stdout);
			result.put("stderr", stderr);
			result.put("maxIterations", maxIterations);
			result.put("classesInitialized", Collections.emptyList());

			return createJsonResult(result);

		} catch (IllegalStateException e) {
			// Could be "No workspace" from SsvmManager
			throw e;
		} catch (Exception e) {
			// Capture any stdout/stderr before returning error
			String stdout = ssvmManager.getAndResetStdout();
			String stderr = ssvmManager.getAndResetStderr();
			logger.error("Unexpected error invoking method", e);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("error", e.getClass().getName());
			result.put("message", e.getMessage());
			result.put("stdout", stdout);
			result.put("stderr", stderr);
			return createJsonResult(result);
		} finally {
			if (vm != null) {
				vm.getThreadManager().detachCurrentThread();
			}
			if (interceptor != null) {
				interceptor.uninstall();
			}
			Interpreter.setMaxIterations(previousMaxIterations);
		}
	}

	// ---- vm-get-field ----

	private void registerVmGetField() {
		Tool tool = Tool.builder()
				.name("vm-get-field")
				.description("Read a static field value from a workspace class inside the sandboxed SSVM. " +
						"Class initialization (<clinit>) runs automatically if the class hasn't been initialized yet. " +
						"Useful for reading decryption keys, lookup tables, and other static data populated during " +
						"class initialization. v1: static fields only.")
				.inputSchema(createSchema(
						mapOf(
								"className", stringParam("Fully qualified class name (dot or slash notation, e.g. 'com.example.Foo' or 'com/example/Foo')"),
								"fieldName", stringParam("Name of the static field to read"),
								"fieldDescriptor", stringParam("JVM field descriptor for disambiguation (e.g. 'I', 'Ljava/lang/String;', '[B'). " +
										"Required only when multiple fields share the same name."),
								"stackTraceOverride", stackTraceOverrideParam()
						),
						List.of("className", "fieldName")
				))
				.build();

		registerTool(tool, (exchange, args) -> {
			requireWorkspace();

			String className = getString(args, "className");
			String fieldName = getString(args, "fieldName");
			String fieldDescriptor = getOptionalString(args, "fieldDescriptor", null);

			// Normalize class name: accept both dot and slash notation
			String dotClassName = className.replace('/', '.');

			// Parse optional stack trace override
			List<StackTraceInterceptor.StackFrame> stackFrames = parseStackTraceFrames(
					getOptionalList(args, "stackTraceOverride"));

			return getStaticField(dotClassName, fieldName, fieldDescriptor, stackFrames);
		});
	}

	// ---- Core field reading logic ----

	/**
	 * Read a static field value from the SSVM and return a structured result.
	 */
	private io.modelcontextprotocol.spec.McpSchema.CallToolResult getStaticField(
			String dotClassName, String fieldName, String fieldDescriptor,
			List<StackTraceInterceptor.StackFrame> stackFrames) {

		StackTraceInterceptor interceptor = null;
		VirtualMachine vm = null;
		try {
			// Bootstrap VM (lazy, ~2-10s first time)
			vm = ssvmManager.getVm();

			// Attach the current thread to SSVM — required before any bytecode execution.
			vm.getThreadManager().attachCurrentThread();

			// Install stack trace interceptor if override frames were provided
			if (stackFrames != null && !stackFrames.isEmpty()) {
				interceptor = new StackTraceInterceptor(vm, stackFrames);
				interceptor.install();
			}
			SupplyingClassLoaderInstaller.Helper helper = ssvmManager.getHelper();
			VMOperations ops = ssvmManager.getOperations();

			// Load the class (triggers <clinit> automatically)
			InstanceClass cls;
			try {
				cls = helper.loadClass(dotClassName);
			} catch (ClassNotFoundException e) {
				return createErrorResult("Class not found: " + dotClassName +
						". Ensure the class exists in the workspace.");
			}

			// Determine if the class was initialized (SSVM never sets COMPLETE; IN_PROGRESS = initialized)
			boolean classInitialized = cls.state().get() == InstanceClass.State.IN_PROGRESS;

			// Find the field
			JavaField field = resolveStaticField(cls, fieldName, fieldDescriptor);
			if (field == null) {
				return createErrorResult(buildFieldNotFoundMessage(
						dotClassName, fieldName, fieldDescriptor, cls));
			}

			// Verify it is static
			if ((field.getModifiers() & Opcodes.ACC_STATIC) == 0) {
				return createErrorResult("Field '" + fieldName + "' in " + dotClassName +
						" is not static. v1 supports static fields only." +
						"\n\nAvailable static fields:" +
						listStaticFields(cls));
			}

			String desc = field.getDesc();

			// Read the field value based on its descriptor
			Object value;
			String fieldTypeName;
			try {
				char typeChar = desc.charAt(0);
				switch (typeChar) {
					case 'I':
						value = ops.getInt(cls, fieldName);
						fieldTypeName = "int";
						break;
					case 'J':
						value = ops.getLong(cls, fieldName);
						fieldTypeName = "long";
						break;
					case 'F':
						value = ops.getFloat(cls, fieldName);
						fieldTypeName = "float";
						break;
					case 'D':
						value = ops.getDouble(cls, fieldName);
						fieldTypeName = "double";
						break;
					case 'Z':
						value = ops.getBoolean(cls, fieldName);
						fieldTypeName = "boolean";
						break;
					case 'B':
						value = ops.getByte(cls, fieldName);
						fieldTypeName = "byte";
						break;
					case 'C':
						value = String.valueOf(ops.getChar(cls, fieldName));
						fieldTypeName = "char";
						break;
					case 'S':
						value = ops.getShort(cls, fieldName);
						fieldTypeName = "short";
						break;
					case 'L':
					case '[':
						ObjectValue refValue = ops.getReference(cls, fieldName, desc);
						value = serializeFieldValue(vm, ops, refValue, desc);
						fieldTypeName = describeFieldType(desc);
						break;
					default:
						return createErrorResult("Unsupported field type: " + desc);
				}
			} catch (VMException e) {
				return handleVmException(vm, e, DEFAULT_MAX_ITERATIONS);
			}

			// Capture stdout/stderr (clinit may produce output)
			String stdout = ssvmManager.getAndResetStdout();
			String stderr = ssvmManager.getAndResetStderr();

			// Build result
			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("className", dotClassName);
			result.put("fieldName", fieldName);
			result.put("fieldType", fieldTypeName);
			result.put("value", value);
			result.put("classInitialized", classInitialized);
			result.put("stdout", stdout);
			result.put("stderr", stderr);
			result.put("classesInitialized", Collections.emptyList());

			return createJsonResult(result);

		} catch (IllegalStateException e) {
			// Could be "No workspace" from SsvmManager
			throw e;
		} catch (Exception e) {
			// Capture any stdout/stderr before returning error
			String stdout = ssvmManager.getAndResetStdout();
			String stderr = ssvmManager.getAndResetStderr();
			logger.error("Unexpected error reading field", e);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("error", e.getClass().getName());
			result.put("message", e.getMessage());
			result.put("stdout", stdout);
			result.put("stderr", stderr);
			return createJsonResult(result);
		} finally {
			if (vm != null) {
				vm.getThreadManager().detachCurrentThread();
			}
			if (interceptor != null) {
				interceptor.uninstall();
			}
		}
	}

	/**
	 * Resolve a static field by name and optional descriptor.
	 * <p>
	 * If a descriptor is provided, does an exact lookup. Otherwise, searches
	 * declared fields for a matching name, preferring static fields.
	 *
	 * @param cls             The class to search.
	 * @param fieldName       Field name.
	 * @param fieldDescriptor Optional field descriptor for disambiguation.
	 * @return The resolved field, or {@code null} if not found.
	 */
	private static JavaField resolveStaticField(InstanceClass cls, String fieldName, String fieldDescriptor) {
		if (fieldDescriptor != null && !fieldDescriptor.isEmpty()) {
			// Exact lookup by name + descriptor
			return cls.getField(fieldName, fieldDescriptor);
		}

		// Search declared fields for a matching name
		List<JavaField> fields = cls.getDeclaredFields(false);
		if (fields == null) {
			return null;
		}

		JavaField match = null;
		for (JavaField f : fields) {
			if (f.getName().equals(fieldName)) {
				if (match != null) {
					// Ambiguous: multiple fields with the same name
					// This shouldn't normally happen in valid bytecode, but
					// obfuscated code can have duplicate field names with different descriptors
					return null;
				}
				match = f;
			}
		}
		return match;
	}

	/**
	 * Serialize a field's ObjectValue to a JSON-compatible representation.
	 * Similar to {@link #serializeObjectValue} but takes a field descriptor directly.
	 */
	private Object serializeFieldValue(VirtualMachine vm, VMOperations ops,
	                                   ObjectValue value, String fieldDescriptor) {
		if (value == null || value.isNull()) {
			return null;
		}

		// Check if it's a String
		if ("Ljava/lang/String;".equals(fieldDescriptor)) {
			try {
				return ops.readUtf8(value);
			} catch (Exception e) {
				logger.debug("Failed to read string field value", e);
				return serializeAsGenericObject(vm, ops, value);
			}
		}

		// Check if it's an array
		if (fieldDescriptor.startsWith("[")) {
			return serializeArray(vm, ops, value, fieldDescriptor);
		}

		// Generic object: serialize as {type, toString}
		return serializeAsGenericObject(vm, ops, value);
	}

	/**
	 * Describe a field type from its descriptor for the response.
	 */
	static String describeFieldType(String descriptor) {
		if (descriptor == null || descriptor.isEmpty()) {
			return "<unknown>";
		}
		char c = descriptor.charAt(0);
		switch (c) {
			case 'I': return "int";
			case 'J': return "long";
			case 'F': return "float";
			case 'D': return "double";
			case 'Z': return "boolean";
			case 'B': return "byte";
			case 'C': return "char";
			case 'S': return "short";
			case 'V': return "void";
			case 'L':
				if (descriptor.endsWith(";")) {
					return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
				}
				return descriptor;
			case '[':
				return descriptor; // Keep array descriptor as-is
			default:
				return descriptor;
		}
	}

	// ---- Field not found helper ----

	/**
	 * Build a helpful error message when a field is not found, listing available static fields.
	 */
	private static String buildFieldNotFoundMessage(String className, String fieldName,
	                                                 String fieldDescriptor, InstanceClass cls) {
		StringBuilder sb = new StringBuilder();
		sb.append("Field not found: '").append(fieldName).append("'");
		if (fieldDescriptor != null) {
			sb.append(" with descriptor '").append(fieldDescriptor).append("'");
		}
		sb.append(" in class ").append(className).append(".");

		// Check if there are multiple fields with the same name (ambiguity case)
		try {
			List<JavaField> fields = cls.getDeclaredFields(false);
			if (fields != null) {
				List<JavaField> nameMatches = new ArrayList<>();
				for (JavaField f : fields) {
					if (f.getName().equals(fieldName)) {
						nameMatches.add(f);
					}
				}
				if (nameMatches.size() > 1 && fieldDescriptor == null) {
					sb.append("\n\nMultiple fields named '").append(fieldName)
							.append("' found. Provide fieldDescriptor to disambiguate:");
					for (JavaField f : nameMatches) {
						String modifier = (f.getModifiers() & Opcodes.ACC_STATIC) != 0 ? "static " : "";
						sb.append("\n  ").append(modifier).append(describeFieldType(f.getDesc()))
								.append(" ").append(f.getName())
								.append(" (descriptor: ").append(f.getDesc()).append(")");
					}
					return sb.toString();
				}
			}
		} catch (Exception e) {
			// Ignore — best-effort listing
		}

		sb.append(listStaticFields(cls));
		return sb.toString();
	}

	/**
	 * List available static fields for a class.
	 */
	private static String listStaticFields(InstanceClass cls) {
		StringBuilder sb = new StringBuilder();
		try {
			List<JavaField> fields = cls.getDeclaredFields(false);
			if (fields != null && !fields.isEmpty()) {
				sb.append("\n\nAvailable static fields:");
				boolean foundAny = false;
				for (JavaField f : fields) {
					if ((f.getModifiers() & Opcodes.ACC_STATIC) != 0) {
						sb.append("\n  ").append(describeFieldType(f.getDesc()))
								.append(" ").append(f.getName())
								.append(" (descriptor: ").append(f.getDesc()).append(")");
						foundAny = true;
					}
				}
				if (!foundAny) {
					sb.append("\n  (no static fields found)");
				}
			}
		} catch (Exception e) {
			// Ignore — best-effort listing
		}
		return sb.toString();
	}

	// ---- vm-run-clinit ----

	private void registerVmRunClinit() {
		Tool tool = Tool.builder()
				.name("vm-run-clinit")
				.description("Explicitly trigger class initialization (<clinit>) with controlled ordering inside " +
						"the sandboxed SSVM. Primary tool for string decryption workflows where <clinit> populates " +
						"static fields. Returns which classes initialized and which were deferred.")
				.inputSchema(createSchema(
						mapOf(
								"className", stringParam("Fully qualified class name (dot or slash notation, e.g. 'com.example.Foo' or 'com/example/Foo')"),
								"stackTraceOverride", stackTraceOverrideParam(),
								"allowTransitiveInit", Map.of(
										"type", "array",
										"description", "Whitelist of class names whose <clinit> may fire transitively. " +
												"If omitted, all transitive init is allowed (standard JVM behavior). " +
												"If provided, only listed classes + the target class can initialize; " +
												"all others have their <clinit> no-oped. " +
												"Use this to prevent side effects from unrelated class initializers.",
										"items", Map.of("type", "string")
								),
								"maxIterations", intParam("Max bytecode instructions before aborting (default: 10,000,000). " +
										"Increase for classes with heavy <clinit> computation.")
						),
						List.of("className")
				))
				.build();

		registerTool(tool, (exchange, args) -> {
			requireWorkspace();

			String className = getString(args, "className");
			int maxIterations = getInt(args, "maxIterations", DEFAULT_MAX_ITERATIONS);

			// Normalize class name: accept both dot and slash notation
			String dotClassName = className.replace('/', '.');

			// Parse optional stack trace override
			List<StackTraceInterceptor.StackFrame> stackFrames = parseStackTraceFrames(
					getOptionalList(args, "stackTraceOverride"));

			// Parse optional transitive init whitelist
			Set<String> allowTransitiveInit = parseAllowTransitiveInit(
					getOptionalList(args, "allowTransitiveInit"));

			return runClinit(dotClassName, stackFrames, allowTransitiveInit, maxIterations);
		});
	}

	// ---- Core clinit logic ----

	/**
	 * Trigger class initialization in the SSVM and return a structured result.
	 */
	private io.modelcontextprotocol.spec.McpSchema.CallToolResult runClinit(
			String dotClassName,
			List<StackTraceInterceptor.StackFrame> stackFrames,
			Set<String> allowTransitiveInit,
			int maxIterations) {

		int previousMaxIterations = Interpreter.getMaxIterations();
		StackTraceInterceptor interceptor = null;
		InitializationController initController = null;
		VirtualMachine vm = null;
		try {
			Interpreter.setMaxIterations(maxIterations);

			vm = ssvmManager.getVm();

			// Attach the current thread to SSVM — required before any bytecode execution.
			vm.getThreadManager().attachCurrentThread();

			// Install stack trace interceptor if override frames were provided
			if (stackFrames != null && !stackFrames.isEmpty()) {
				interceptor = new StackTraceInterceptor(vm, stackFrames);
				interceptor.install();
			}

			// Install initialization controller
			initController = new InitializationController(vm, dotClassName, allowTransitiveInit);
			initController.install();

			// Load and initialize the class (loadClass triggers <clinit>)
			SupplyingClassLoaderInstaller.Helper helper = ssvmManager.getHelper();
			InstanceClass cls;
			try {
				cls = helper.loadClass(dotClassName);
			} catch (ClassNotFoundException e) {
				return createErrorResult("Class not found: " + dotClassName +
						". Ensure the class exists in the workspace.");
			}

			// SSVM never sets COMPLETE; after successful init, state = IN_PROGRESS
			boolean initialized = cls.state().get() == InstanceClass.State.IN_PROGRESS;

			// Capture stdout/stderr (clinit may produce output)
			String stdout = ssvmManager.getAndResetStdout();
			String stderr = ssvmManager.getAndResetStderr();

			// Build result
			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("className", dotClassName);
			result.put("initialized", initialized);
			result.put("stdout", stdout);
			result.put("stderr", stderr);
			result.put("classesInitialized", initController.getClassesInitialized());
			result.put("classesDeferred", initController.getClassesDeferred());

			return createJsonResult(result);

		} catch (VMException e) {
			return handleVmException(vm != null ? vm : ssvmManager.getVm(), e, maxIterations);
		} catch (IllegalStateException e) {
			throw e;
		} catch (Exception e) {
			String stdout = ssvmManager.getAndResetStdout();
			String stderr = ssvmManager.getAndResetStderr();
			logger.error("Unexpected error running <clinit>", e);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("error", e.getClass().getName());
			result.put("message", e.getMessage());
			result.put("stdout", stdout);
			result.put("stderr", stderr);
			return createJsonResult(result);
		} finally {
			if (vm != null) {
				vm.getThreadManager().detachCurrentThread();
			}
			if (initController != null) {
				initController.close();
			}
			if (interceptor != null) {
				interceptor.uninstall();
			}
			Interpreter.setMaxIterations(previousMaxIterations);
		}
	}

	/**
	 * Parse the allowTransitiveInit parameter into a set of normalized class names.
	 * <p>
	 * Package-private for testability.
	 *
	 * @param rawList List of class name strings from MCP JSON, or null if omitted.
	 * @return Set of normalized class names (dot notation), or {@code null} if input was null
	 *         (null means "allow all transitive init").
	 */
	static Set<String> parseAllowTransitiveInit(List<Object> rawList) {
		if (rawList == null) {
			return null;
		}
		Set<String> whitelist = new HashSet<>();
		for (Object item : rawList) {
			whitelist.add(item.toString().replace('/', '.'));
		}
		return whitelist;
	}

	// ---- VM exception handling ----

	/**
	 * Handle a VM exception, distinguishing iteration limit exceeded from other errors.
	 */
	private io.modelcontextprotocol.spec.McpSchema.CallToolResult handleVmException(
			VirtualMachine vm, VMException ex, int maxIterations) {

		String stdout = ssvmManager.getAndResetStdout();
		String stderr = ssvmManager.getAndResetStderr();

		InstanceValue oop = ex.getOop();
		VMOperations ops = vm.getOperations();

		// Determine the exception class name
		String exceptionClassName = oop.getJavaClass().getInternalName().replace('/', '.');

		// Extract message from the VM exception
		String exceptionMessage = extractVmExceptionMessage(vm, ops, oop);

		// Check if this is an iteration limit exceeded (IllegalStateException from maxIterations handler)
		if ("java.lang.IllegalStateException".equals(exceptionClassName)) {
			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("error", "IterationLimitExceeded");
			result.put("message", "Execution exceeded " + maxIterations +
					" iterations. Retry with a higher maxIterations value.");
			result.put("iterationsUsed", maxIterations);
			result.put("maxIterations", maxIterations);
			result.put("stdout", stdout);
			result.put("stderr", stderr);
			return createJsonResult(result);
		}

		// Extract stack trace
		String vmStackTrace = extractVmStackTrace(vm, ops, oop);

		LinkedHashMap<String, Object> result = new LinkedHashMap<>();
		result.put("error", exceptionClassName);
		result.put("message", exceptionMessage);
		result.put("vmStackTrace", vmStackTrace);
		result.put("stdout", stdout);
		result.put("stderr", stderr);
		return createJsonResult(result);
	}

	/**
	 * Extract the message from a VM Throwable oop.
	 */
	private String extractVmExceptionMessage(VirtualMachine vm, VMOperations ops, InstanceValue oop) {
		try {
			InvocationUtil util = ssvmManager.getInvocationUtil();
			JavaMethod getMessageMethod = vm.getSymbols().java_lang_Throwable()
					.getMethod("getMessage", "()Ljava/lang/String;");
			if (getMessageMethod != null) {
				ObjectValue msgValue = util.invokeReference(getMessageMethod, Argument.reference(oop));
				if (msgValue != null && !msgValue.isNull()) {
					return ops.readUtf8(msgValue);
				}
			}
		} catch (Exception e) {
			logger.debug("Failed to extract VM exception message", e);
		}
		return null;
	}

	/**
	 * Extract a formatted stack trace from a VM Throwable oop.
	 */
	private String extractVmStackTrace(VirtualMachine vm, VMOperations ops, InstanceValue oop) {
		try {
			InvocationUtil util = ssvmManager.getInvocationUtil();
			// Call Throwable.getStackTrace() -> StackTraceElement[]
			JavaMethod getStackTraceMethod = vm.getSymbols().java_lang_Throwable()
					.getMethod("getStackTrace", "()[Ljava/lang/StackTraceElement;");
			if (getStackTraceMethod == null) {
				return "";
			}
			ObjectValue stackTraceArray = util.invokeReference(getStackTraceMethod, Argument.reference(oop));
			if (stackTraceArray == null || stackTraceArray.isNull()) {
				return "";
			}

			int length = ops.getArrayLength(stackTraceArray);
			StringBuilder sb = new StringBuilder();
			// Call toString() on each StackTraceElement
			JavaMethod toStringMethod = vm.getSymbols().java_lang_Object()
					.getMethod("toString", "()Ljava/lang/String;");

			for (int i = 0; i < Math.min(length, 50); i++) {
				ObjectValue element = ops.arrayLoadReference(stackTraceArray, i);
				if (element != null && !element.isNull()) {
					ObjectValue strValue = util.invokeReference(toStringMethod, Argument.reference(element));
					if (strValue != null && !strValue.isNull()) {
						if (i > 0) sb.append("\n");
						sb.append("at ").append(ops.readUtf8(strValue));
					}
				}
			}
			if (length > 50) {
				sb.append("\n... ").append(length - 50).append(" more");
			}
			return sb.toString();
		} catch (Exception e) {
			logger.debug("Failed to extract VM stack trace", e);
			return "";
		}
	}

	// ---- Argument mapping ----

	/**
	 * Map JSON arguments to SSVM {@link Argument} objects based on the method descriptor.
	 * <p>
	 * Package-private for testability.
	 *
	 * @param vm             The SSVM instance (needed for String creation).
	 * @param descriptor     JVM method descriptor, e.g. {@code (ILjava/lang/String;)V}.
	 * @param jsonArgs       List of JSON-typed arguments (Number, String, Boolean, null).
	 * @return Array of SSVM Arguments ready for invocation.
	 * @throws IllegalArgumentException if argument count or types don't match.
	 */
	static Argument[] mapJsonArgsToSsvm(VirtualMachine vm, String descriptor, List<Object> jsonArgs) {
		List<Character> paramTypes = parseParameterTypes(descriptor);

		if (jsonArgs == null) {
			jsonArgs = Collections.emptyList();
		}

		if (jsonArgs.size() != paramTypes.size()) {
			throw new IllegalArgumentException(
					"Expected " + paramTypes.size() + " arguments for descriptor " + descriptor +
							", but got " + jsonArgs.size());
		}

		Argument[] result = new Argument[paramTypes.size()];
		for (int i = 0; i < paramTypes.size(); i++) {
			result[i] = mapSingleArg(vm, paramTypes.get(i), jsonArgs.get(i), i, descriptor);
		}
		return result;
	}

	/**
	 * Map a single JSON argument to an SSVM Argument based on the expected JVM type.
	 */
	private static Argument mapSingleArg(VirtualMachine vm, char type, Object jsonValue, int index, String descriptor) {
		if (jsonValue == null) {
			// null is only valid for reference types
			if (type == 'L' || type == '[') {
				return Argument.reference(vm.getMemoryManager().nullValue());
			}
			throw new IllegalArgumentException(
					"Argument " + index + " is null but descriptor expects primitive type '" + type + "'");
		}

		switch (type) {
			case 'I': // int
			case 'B': // byte
			case 'C': // char
			case 'S': // short
			case 'Z': // boolean
				if (jsonValue instanceof Boolean bool) {
					return Argument.int32(bool ? 1 : 0);
				}
				if (jsonValue instanceof Number num) {
					return Argument.int32(num.intValue());
				}
				throw new IllegalArgumentException(
						"Argument " + index + " must be a number or boolean for type '" + type + "', got: " +
								jsonValue.getClass().getSimpleName());

			case 'J': // long
				if (jsonValue instanceof Number num) {
					return Argument.int64(num.longValue());
				}
				throw new IllegalArgumentException(
						"Argument " + index + " must be a number for type 'J' (long), got: " +
								jsonValue.getClass().getSimpleName());

			case 'F': // float
				if (jsonValue instanceof Number num) {
					return Argument.float32(num.floatValue());
				}
				throw new IllegalArgumentException(
						"Argument " + index + " must be a number for type 'F' (float), got: " +
								jsonValue.getClass().getSimpleName());

			case 'D': // double
				if (jsonValue instanceof Number num) {
					return Argument.float64(num.doubleValue());
				}
				throw new IllegalArgumentException(
						"Argument " + index + " must be a number for type 'D' (double), got: " +
								jsonValue.getClass().getSimpleName());

			case 'L': // object reference
				if (jsonValue instanceof String str) {
					// Create a String in the SSVM
					InstanceValue vmString = vm.getOperations().newUtf8(str);
					return Argument.reference(vmString);
				}
				if (jsonValue instanceof Number || jsonValue instanceof Boolean) {
					throw new IllegalArgumentException(
							"Argument " + index + " expects an object reference (String), got: " +
									jsonValue.getClass().getSimpleName());
				}
				throw new IllegalArgumentException(
						"Argument " + index + " expects an object reference, got: " +
								jsonValue.getClass().getSimpleName());

			case '[': // array reference
				if (jsonValue instanceof String str) {
					InstanceValue vmString = vm.getOperations().newUtf8(str);
					return Argument.reference(vmString);
				}
				throw new IllegalArgumentException(
						"Argument " + index + " expects an array reference. " +
								"Array argument passing is not yet supported in v1.");

			default:
				throw new IllegalArgumentException(
						"Unsupported parameter type '" + type + "' at argument " + index);
		}
	}

	// ---- Descriptor parsing ----

	/**
	 * Parse parameter types from a JVM method descriptor.
	 * <p>
	 * For example, {@code (ILjava/lang/String;D)V} returns {@code ['I', 'L', 'D']}.
	 * Object types ({@code L...;}) are collapsed to {@code 'L'}, and array types
	 * ({@code [...}) are collapsed to {@code '['}.
	 * <p>
	 * Package-private for testability.
	 *
	 * @param descriptor JVM method descriptor.
	 * @return List of type characters for each parameter.
	 */
	static List<Character> parseParameterTypes(String descriptor) {
		List<Character> types = new ArrayList<>();
		int i = 1; // skip opening '('
		while (i < descriptor.length() && descriptor.charAt(i) != ')') {
			char c = descriptor.charAt(i);
			switch (c) {
				case 'B':
				case 'C':
				case 'D':
				case 'F':
				case 'I':
				case 'J':
				case 'S':
				case 'Z':
					types.add(c);
					i++;
					break;
				case 'L':
					types.add('L');
					// Skip to the closing ';'
					i = descriptor.indexOf(';', i) + 1;
					break;
				case '[':
					types.add('[');
					// Skip array dimensions and the element type
					while (i < descriptor.length() && descriptor.charAt(i) == '[') {
						i++;
					}
					if (i < descriptor.length() && descriptor.charAt(i) == 'L') {
						i = descriptor.indexOf(';', i) + 1;
					} else {
						i++; // skip the primitive element type
					}
					break;
				default:
					throw new IllegalArgumentException(
							"Invalid descriptor character '" + c + "' at position " + i +
									" in descriptor: " + descriptor);
			}
		}
		return types;
	}

	/**
	 * Parse the return type character from a JVM method descriptor.
	 *
	 * @param descriptor JVM method descriptor, e.g. {@code (II)I}.
	 * @return The return type character ('V', 'I', 'J', 'L', '[', etc.).
	 */
	static char parseReturnType(String descriptor) {
		int closeIndex = descriptor.lastIndexOf(')');
		if (closeIndex < 0 || closeIndex + 1 >= descriptor.length()) {
			throw new IllegalArgumentException("Invalid method descriptor: " + descriptor);
		}
		return descriptor.charAt(closeIndex + 1);
	}

	/**
	 * Describe the return type from a method descriptor for the response.
	 */
	private static String describeReturnType(String descriptor) {
		int closeIndex = descriptor.lastIndexOf(')');
		String returnDesc = descriptor.substring(closeIndex + 1);
		if (returnDesc.startsWith("L") && returnDesc.endsWith(";")) {
			return returnDesc.substring(1, returnDesc.length() - 1).replace('/', '.');
		}
		if (returnDesc.startsWith("[")) {
			return returnDesc; // Keep array descriptor as-is
		}
		return returnDesc;
	}

	// ---- Return value serialization ----

	/**
	 * Serialize an SSVM ObjectValue to a JSON-compatible representation.
	 */
	private Object serializeObjectValue(VirtualMachine vm, VMOperations ops,
	                                    ObjectValue value, char typeChar, String methodDescriptor) {
		if (value == null || value.isNull()) {
			return null;
		}

		// Check if it's a String
		String returnDesc = methodDescriptor.substring(methodDescriptor.lastIndexOf(')') + 1);
		if ("Ljava/lang/String;".equals(returnDesc)) {
			try {
				return ops.readUtf8(value);
			} catch (Exception e) {
				logger.debug("Failed to read string return value", e);
				return serializeAsGenericObject(vm, ops, value);
			}
		}

		// Check if it's an array
		if (typeChar == '[' || returnDesc.startsWith("[")) {
			return serializeArray(vm, ops, value, returnDesc);
		}

		// Generic object: serialize as {type, toString}
		return serializeAsGenericObject(vm, ops, value);
	}

	/**
	 * Serialize an array value to a JSON-compatible list.
	 */
	private Object serializeArray(VirtualMachine vm, VMOperations ops,
	                              ObjectValue arrayValue, String arrayDescriptor) {
		try {
			int length = ops.getArrayLength(arrayValue);
			boolean truncated = length > MAX_ARRAY_ELEMENTS;
			int readLength = Math.min(length, MAX_ARRAY_ELEMENTS);

			// Determine element type from descriptor
			String elementDesc = arrayDescriptor.substring(1); // strip leading '['

			List<Object> elements = new ArrayList<>(readLength);
			for (int i = 0; i < readLength; i++) {
				elements.add(readArrayElement(ops, arrayValue, i, elementDesc));
			}

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("length", length);
			result.put("elements", elements);
			if (truncated) {
				result.put("truncated", true);
			}
			return result;
		} catch (Exception e) {
			logger.debug("Failed to serialize array", e);
			return serializeAsGenericObject(vm, ops, arrayValue);
		}
	}

	/**
	 * Read a single element from an array at the given index.
	 */
	private Object readArrayElement(VMOperations ops, ObjectValue array, int index, String elementDesc) {
		try {
			if (elementDesc.isEmpty()) {
				return null;
			}
			char elemType = elementDesc.charAt(0);
			switch (elemType) {
				case 'I':
					return ops.arrayLoadInt(array, index);
				case 'J':
					return ops.arrayLoadLong(array, index);
				case 'F':
					return ops.arrayLoadFloat(array, index);
				case 'D':
					return ops.arrayLoadDouble(array, index);
				case 'B':
					return ops.arrayLoadByte(array, index);
				case 'C':
					return String.valueOf(ops.arrayLoadChar(array, index));
				case 'S':
					return ops.arrayLoadShort(array, index);
				case 'Z':
					return ops.arrayLoadBoolean(array, index);
				case 'L':
				case '[':
					ObjectValue ref = ops.arrayLoadReference(array, index);
					if (ref == null || ref.isNull()) {
						return null;
					}
					// If it's a String array
					if (elementDesc.equals("Ljava/lang/String;")) {
						try {
							return ops.readUtf8(ref);
						} catch (Exception e) {
							return "<unreadable>";
						}
					}
					return "<object>";
				default:
					return "<unknown:" + elemType + ">";
			}
		} catch (Exception e) {
			return "<error>";
		}
	}

	/**
	 * Serialize a generic VM object as {@code {type, toString}}.
	 */
	private Object serializeAsGenericObject(VirtualMachine vm, VMOperations ops, ObjectValue value) {
		LinkedHashMap<String, Object> result = new LinkedHashMap<>();
		try {
			String typeName = value.getJavaClass().getInternalName().replace('/', '.');
			result.put("type", typeName);
		} catch (Exception e) {
			result.put("type", "<unknown>");
		}

		try {
			InvocationUtil util = ssvmManager.getInvocationUtil();
			JavaMethod toStringMethod = vm.getSymbols().java_lang_Object()
					.getMethod("toString", "()Ljava/lang/String;");
			if (toStringMethod != null) {
				ObjectValue strValue = util.invokeReference(toStringMethod, Argument.reference(value));
				if (strValue != null && !strValue.isNull()) {
					result.put("toString", ops.readUtf8(strValue));
				}
			}
		} catch (Exception e) {
			result.put("toString", "<error calling toString()>");
		}
		return result;
	}

	// ---- Method not found helper ----

	/**
	 * Build a helpful error message when a method is not found, listing available methods.
	 */
	private static String buildMethodNotFoundMessage(String className, String methodName,
	                                                  String methodDescriptor, InstanceClass cls) {
		StringBuilder sb = new StringBuilder();
		sb.append("Method not found: ").append(methodName).append(methodDescriptor);
		sb.append(" in class ").append(className).append(".");

		try {
			List<JavaMethod> methods = cls.getDeclaredMethods(false);
			if (methods != null && !methods.isEmpty()) {
				sb.append("\n\nAvailable static methods:");
				boolean foundAny = false;
				for (JavaMethod m : methods) {
					if ((m.getModifiers() & Opcodes.ACC_STATIC) != 0) {
						sb.append("\n  ").append(m.getName()).append(m.getDesc());
						foundAny = true;
					}
				}
				if (!foundAny) {
					sb.append("\n  (no static methods found)");
				}
			}
		} catch (Exception e) {
			// Ignore — best-effort listing
		}
		return sb.toString();
	}

	// ---- Parameter extraction helpers ----

	/**
	 * Get an optional list parameter. MCP delivers JSON arrays as {@code List<Object>}.
	 */
	@SuppressWarnings("unchecked")
	private static List<Object> getOptionalList(Map<String, Object> args, String key) {
		Object value = args.get(key);
		if (value == null) {
			return null;
		}
		if (value instanceof List<?> list) {
			return (List<Object>) list;
		}
		throw new IllegalArgumentException("Parameter '" + key + "' must be an array");
	}

	// ---- Stack trace override helpers ----

	/**
	 * Build the JSON schema for the stackTraceOverride parameter.
	 */
	private static Map<String, Object> stackTraceOverrideParam() {
		return Map.of(
				"type", "array",
				"description", "Synthetic stack trace frames for Thread.getStackTrace() interception. " +
						"Each element: {className, methodName, fileName?, lineNumber?}. " +
						"Frame indexing matters — obfuscators read specific indices (e.g. getStackTrace()[3]).",
				"items", Map.of(
						"type", "object",
						"properties", Map.of(
								"className", Map.of("type", "string"),
								"methodName", Map.of("type", "string"),
								"fileName", Map.of("type", "string"),
								"lineNumber", Map.of("type", "integer")
						),
						"required", List.of("className", "methodName")
				)
		);
	}

	/**
	 * Parse a list of JSON frame maps into {@link StackTraceInterceptor.StackFrame} objects.
	 * <p>
	 * Package-private for testability.
	 *
	 * @param rawFrames List of maps from MCP JSON, or null if the parameter was omitted.
	 * @return Parsed frames, or null if input was null.
	 */
	@SuppressWarnings("unchecked")
	static List<StackTraceInterceptor.StackFrame> parseStackTraceFrames(List<Object> rawFrames) {
		if (rawFrames == null) {
			return null;
		}
		List<StackTraceInterceptor.StackFrame> frames = new ArrayList<>(rawFrames.size());
		for (int i = 0; i < rawFrames.size(); i++) {
			Object entry = rawFrames.get(i);
			if (!(entry instanceof Map<?, ?> map)) {
				throw new IllegalArgumentException(
						"stackTraceOverride[" + i + "] must be an object, got: " +
								(entry == null ? "null" : entry.getClass().getSimpleName()));
			}
			Map<String, Object> frameMap = (Map<String, Object>) map;

			Object classNameObj = frameMap.get("className");
			if (classNameObj == null) {
				throw new IllegalArgumentException(
						"stackTraceOverride[" + i + "] missing required field 'className'");
			}
			Object methodNameObj = frameMap.get("methodName");
			if (methodNameObj == null) {
				throw new IllegalArgumentException(
						"stackTraceOverride[" + i + "] missing required field 'methodName'");
			}

			String className = classNameObj.toString();
			String methodName = methodNameObj.toString();

			Object fileNameObj = frameMap.get("fileName");
			String fileName = fileNameObj != null ? fileNameObj.toString() : null;

			int lineNumber = -1;
			Object lineNumberObj = frameMap.get("lineNumber");
			if (lineNumberObj instanceof Number num) {
				lineNumber = num.intValue();
			}

			frames.add(new StackTraceInterceptor.StackFrame(className, methodName, fileName, lineNumber));
		}
		return frames;
	}

	/**
	 * Helper to build a mutable map from key-value pairs.
	 * <p>
	 * {@code Map.of()} has a limit of 10 entries. Tool schemas with many parameters
	 * (e.g. vm-invoke-method with 6 properties) can exceed this after adding
	 * stackTraceOverride. This utility avoids that limitation.
	 */
	@SuppressWarnings("unchecked")
	private static <K, V> Map<K, V> mapOf(Object... keyValues) {
		if (keyValues.length % 2 != 0) {
			throw new IllegalArgumentException("Odd number of arguments");
		}
		LinkedHashMap<K, V> map = new LinkedHashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			map.put((K) keyValues[i], (V) keyValues[i + 1]);
		}
		return map;
	}
}
