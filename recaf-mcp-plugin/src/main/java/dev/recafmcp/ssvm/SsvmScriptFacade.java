package dev.recafmcp.ssvm;

import dev.xdark.ssvm.VirtualMachine;
import dev.xdark.ssvm.invoke.Argument;
import dev.xdark.ssvm.invoke.InvocationUtil;
import dev.xdark.ssvm.mirror.member.JavaField;
import dev.xdark.ssvm.mirror.member.JavaMethod;
import dev.xdark.ssvm.mirror.type.InstanceClass;
import dev.xdark.ssvm.operation.VMOperations;
import dev.xdark.ssvm.value.InstanceValue;
import dev.xdark.ssvm.value.ObjectValue;
import org.objectweb.asm.Opcodes;

/**
 * Groovy-friendly facade over {@link SsvmManager} for {@code execute-recaf-script}
 * bindings (recaf-mcp-aox).
 * <p>
 * SSVM is invaluable for invoking obfuscated decryptors and reading their outputs
 * without running the whole jar — but agents writing Groovy scripts previously had
 * to fall back to standalone Java harnesses (with bytecode-patch gates and
 * {@code Thread.getStackTrace()} bypasses) because no in-script SSVM surface
 * existed. This class exposes a deliberately-tiny surface that maps cleanly onto
 * the four operations agents reach for most often:
 * <ul>
 *     <li>{@link #invokeStatic(String, String, String, Object...)} — call a static
 *         method by descriptor; primitive args are coerced from boxed values.</li>
 *     <li>{@link #getStaticField(String, String)} /
 *         {@link #getStaticField(String, String, String)} — read a static field;
 *         references are returned as {@code String} where they are {@code String}s
 *         in the VM, otherwise the raw {@link ObjectValue} for power users.</li>
 *     <li>{@link #setStaticField(String, String, String, Object)} — write a static
 *         field; primitives are coerced from boxed values, {@code String}s are
 *         interned into the VM, {@code null} writes the VM null.</li>
 *     <li>{@link #runClinit(String)} — force {@code <clinit>} on a class.</li>
 * </ul>
 * Power users can drop down to the raw {@link SsvmManager}, {@link VirtualMachine},
 * {@link InvocationUtil}, or {@link VMOperations} via {@link #getManager()},
 * {@link #vm()}, {@link #invocationUtil()}, and {@link #operations()}.
 * <p>
 * The facade attaches the calling thread to SSVM on every call (MCP handlers
 * run on Jetty/Reactor threads which SSVM does not know about by default) and
 * detaches in a {@code finally} block, mirroring the contract used by the
 * existing {@code vm-*} MCP tools.
 * <p>
 * Failures surface as {@link IllegalStateException} (SSVM unavailable) or
 * {@link IllegalArgumentException} (bad descriptor / class / member) rather
 * than silent {@code null} or {@code NullPointerException}, so script authors
 * see a usable error message in the {@code execute-recaf-script} response.
 */
public class SsvmScriptFacade {
	private final SsvmManager manager;

	public SsvmScriptFacade(SsvmManager manager) {
		if (manager == null) {
			throw new IllegalArgumentException("SsvmManager must not be null");
		}
		this.manager = manager;
	}

	// ---- escape hatches for power users ----

	public SsvmManager getManager() {
		return manager;
	}

	public VirtualMachine vm() {
		return manager.getVm();
	}

	public InvocationUtil invocationUtil() {
		return manager.getInvocationUtil();
	}

	public VMOperations operations() {
		return manager.getOperations();
	}

	/** Captured stdout since the last drain. Empty string if VM not initialized. */
	public String drainStdout() {
		return manager.getAndResetStdout();
	}

	/** Captured stderr since the last drain. Empty string if VM not initialized. */
	public String drainStderr() {
		return manager.getAndResetStderr();
	}

	// ---- core operations ----

	/**
	 * Find and initialize a class by name. Triggers {@code <clinit>} if not already run.
	 * Works for both JDK boot classes and workspace classes.
	 *
	 * @param className Dot or slash notation.
	 * @return The {@link InstanceClass} for power users that need direct VM access.
	 * @throws IllegalArgumentException if the class is not found.
	 * @throws IllegalStateException    if SSVM is not available (no boot JDK / bootstrap failed).
	 */
	public InstanceClass findClass(String className) {
		try {
			return manager.findClass(className);
		} catch (ClassNotFoundException e) {
			throw new IllegalArgumentException("SSVM class not found: " + className +
					". Ensure the class exists in the workspace or JDK boot classes.", e);
		}
	}

	/**
	 * Force a class's static initializer ({@code <clinit>}) to run. No-op if it has
	 * already executed. Useful for staging obfuscated decryptors before reading their
	 * decoded static fields.
	 *
	 * @param className Dot or slash notation.
	 * @throws IllegalArgumentException if the class is not found.
	 * @throws IllegalStateException    if SSVM is not available.
	 */
	public void runClinit(String className) {
		VirtualMachine vm = manager.getVm();
		vm.getThreadManager().attachCurrentThread();
		try {
			findClass(className); // findClass triggers initialize() inside SsvmManager
		} finally {
			vm.getThreadManager().detachCurrentThread();
		}
	}

	/**
	 * Invoke a static method on an SSVM-resolved class. Argument values are coerced
	 * from boxed primitives ({@link Number}, {@link Boolean}) and {@link String} per
	 * the descriptor's parameter slots; {@code null} is only valid for reference slots.
	 *
	 * @param className  Dot or slash notation.
	 * @param methodName Method name.
	 * @param descriptor JVM method descriptor (e.g. {@code (II)Ljava/lang/String;}).
	 * @param args       Caller-supplied arguments. Length and types must match the descriptor.
	 * @return Boxed primitive for primitive returns, {@code null} for {@code void} or VM-null,
	 *         a {@link String} for {@code java.lang.String} returns, otherwise the raw
	 *         {@link ObjectValue} (escape hatch for callers that need direct VM access).
	 * @throws IllegalArgumentException if the class/method is missing, the method is non-static,
	 *                                  or the args do not match the descriptor.
	 * @throws IllegalStateException    if SSVM is not available.
	 */
	public Object invokeStatic(String className, String methodName, String descriptor, Object... args) {
		if (descriptor == null || descriptor.isEmpty() || descriptor.charAt(0) != '(') {
			throw new IllegalArgumentException("Invalid method descriptor: " + descriptor);
		}
		VirtualMachine vm = manager.getVm();
		vm.getThreadManager().attachCurrentThread();
		try {
			InstanceClass cls = findClass(className);
			JavaMethod method = cls.getMethod(methodName, descriptor);
			if (method == null) {
				throw new IllegalArgumentException("Method not found: " + className + "." +
						methodName + descriptor);
			}
			if ((method.getModifiers() & Opcodes.ACC_STATIC) == 0) {
				throw new IllegalArgumentException("Method " + methodName + descriptor +
						" on " + className + " is not static. Use the (currently TODO) invokeVirtual " +
						"facade for instance methods, or invoke via invocationUtil() directly.");
			}
			Argument[] vmArgs = coerceArgs(vm, descriptor, args == null ? new Object[0] : args);
			InvocationUtil util = manager.getInvocationUtil();
			VMOperations ops = manager.getOperations();
			char ret = parseReturnType(descriptor);
			switch (ret) {
				case 'V':
					util.invokeVoid(method, vmArgs);
					return null;
				case 'I':
					return util.invokeInt(method, vmArgs);
				case 'J':
					return util.invokeLong(method, vmArgs);
				case 'F':
					return util.invokeFloat(method, vmArgs);
				case 'D':
					return util.invokeDouble(method, vmArgs);
				case 'Z':
					return util.invokeInt(method, vmArgs) != 0;
				case 'B':
					return (byte) util.invokeInt(method, vmArgs);
				case 'C':
					return (char) util.invokeInt(method, vmArgs);
				case 'S':
					return (short) util.invokeInt(method, vmArgs);
				case 'L':
				case '[':
					ObjectValue ref = util.invokeReference(method, vmArgs);
					String returnDesc = descriptor.substring(descriptor.lastIndexOf(')') + 1);
					return decodeReference(vm, ops, ref, returnDesc);
				default:
					throw new IllegalArgumentException("Unsupported return type '" + ret +
							"' in descriptor " + descriptor);
			}
		} finally {
			vm.getThreadManager().detachCurrentThread();
		}
	}

	/**
	 * Read a static field. Resolves the field by name only — if the class declares
	 * multiple static fields with the same name (rare but legal at the bytecode
	 * level), use {@link #getStaticField(String, String, String)} with an explicit
	 * descriptor.
	 */
	public Object getStaticField(String className, String fieldName) {
		return getStaticField(className, fieldName, null);
	}

	/**
	 * Read a static field with an optional explicit descriptor for disambiguation.
	 */
	public Object getStaticField(String className, String fieldName, String descriptor) {
		VirtualMachine vm = manager.getVm();
		vm.getThreadManager().attachCurrentThread();
		try {
			InstanceClass cls = findClass(className);
			JavaField field = resolveStaticField(cls, fieldName, descriptor);
			VMOperations ops = manager.getOperations();
			String desc = field.getDesc();
			char type = desc.charAt(0);
			switch (type) {
				case 'I': return ops.getInt(cls, fieldName);
				case 'J': return ops.getLong(cls, fieldName);
				case 'F': return ops.getFloat(cls, fieldName);
				case 'D': return ops.getDouble(cls, fieldName);
				case 'Z': return ops.getBoolean(cls, fieldName);
				case 'B': return ops.getByte(cls, fieldName);
				case 'C': return ops.getChar(cls, fieldName);
				case 'S': return ops.getShort(cls, fieldName);
				case 'L':
				case '[':
					return decodeReference(vm, ops, ops.getReference(cls, fieldName, desc), desc);
				default:
					throw new IllegalArgumentException("Unsupported field type '" + type +
							"' for " + className + "." + fieldName);
			}
		} finally {
			vm.getThreadManager().detachCurrentThread();
		}
	}

	/**
	 * Write a static field. The descriptor is inferred when there's only one
	 * field with the given name; if ambiguous, throws — call the explicit-descriptor
	 * overload.
	 */
	public void setStaticField(String className, String fieldName, Object value) {
		setStaticField(className, fieldName, null, value);
	}

	/**
	 * Write a static field with an optional explicit descriptor for disambiguation.
	 */
	public void setStaticField(String className, String fieldName, String descriptor, Object value) {
		VirtualMachine vm = manager.getVm();
		vm.getThreadManager().attachCurrentThread();
		try {
			InstanceClass cls = findClass(className);
			JavaField field = resolveStaticField(cls, fieldName, descriptor);
			VMOperations ops = manager.getOperations();
			String desc = field.getDesc();
			char type = desc.charAt(0);
			switch (type) {
				case 'I':
					ops.putInt(cls, fieldName, requireNumber(value, "int").intValue()); break;
				case 'J':
					ops.putLong(cls, fieldName, requireNumber(value, "long").longValue()); break;
				case 'F':
					ops.putFloat(cls, fieldName, requireNumber(value, "float").floatValue()); break;
				case 'D':
					ops.putDouble(cls, fieldName, requireNumber(value, "double").doubleValue()); break;
				case 'Z':
					ops.putBoolean(cls, fieldName, requireBoolean(value)); break;
				case 'B':
					ops.putByte(cls, fieldName, requireNumber(value, "byte").byteValue()); break;
				case 'C':
					ops.putChar(cls, fieldName, requireChar(value)); break;
				case 'S':
					ops.putShort(cls, fieldName, requireNumber(value, "short").shortValue()); break;
				case 'L':
				case '[':
					ops.putReference(cls, fieldName, desc, encodeReference(vm, ops, value));
					break;
				default:
					throw new IllegalArgumentException("Unsupported field type '" + type +
							"' for " + className + "." + fieldName);
			}
		} finally {
			vm.getThreadManager().detachCurrentThread();
		}
	}

	// ---- coercion helpers ----

	/**
	 * Map caller-supplied boxed values into SSVM {@link Argument} slots according to
	 * the parameter descriptor. Mirrors the contract used by the {@code vm-invoke-method}
	 * MCP tool so script users get the same accepted types.
	 */
	static Argument[] coerceArgs(VirtualMachine vm, String descriptor, Object[] args) {
		java.util.List<Character> types = parseParameterTypes(descriptor);
		if (types.size() != args.length) {
			throw new IllegalArgumentException("Argument count mismatch: descriptor expects " +
					types.size() + " args, got " + args.length);
		}
		Argument[] out = new Argument[args.length];
		for (int i = 0; i < args.length; i++) {
			out[i] = coerceSingleArg(vm, types.get(i), args[i], i);
		}
		return out;
	}

	private static Argument coerceSingleArg(VirtualMachine vm, char type, Object value, int index) {
		if (value == null) {
			if (type == 'L' || type == '[') {
				return Argument.reference(vm.getMemoryManager().nullValue());
			}
			throw new IllegalArgumentException("Argument " + index + " is null but descriptor expects '" +
					type + "'");
		}
		switch (type) {
			case 'I':
			case 'B':
			case 'C':
			case 'S':
			case 'Z':
				if (value instanceof Boolean b) return Argument.int32(b ? 1 : 0);
				if (value instanceof Character c) return Argument.int32(c);
				if (value instanceof Number n) return Argument.int32(n.intValue());
				throw badArg(index, type, value);
			case 'J':
				if (value instanceof Number n) return Argument.int64(n.longValue());
				throw badArg(index, type, value);
			case 'F':
				if (value instanceof Number n) return Argument.float32(n.floatValue());
				throw badArg(index, type, value);
			case 'D':
				if (value instanceof Number n) return Argument.float64(n.doubleValue());
				throw badArg(index, type, value);
			case 'L':
				if (value instanceof String s) {
					return Argument.reference(vm.getOperations().newUtf8(s));
				}
				if (value instanceof ObjectValue ov) {
					return Argument.reference(ov);
				}
				throw new IllegalArgumentException("Argument " + index + " expects a reference; got " +
						value.getClass().getName() + ". Pass a String, an ObjectValue (e.g. from " +
						"vmService.findClass(...)), or null.");
			case '[':
				if (value instanceof ObjectValue ov) {
					return Argument.reference(ov);
				}
				throw new IllegalArgumentException("Argument " + index + " expects an array reference; " +
						"build it via vmService.operations() (array argument coercion not yet supported).");
			default:
				throw new IllegalArgumentException("Unsupported parameter type '" + type +
						"' at index " + index);
		}
	}

	private static IllegalArgumentException badArg(int index, char type, Object value) {
		return new IllegalArgumentException("Argument " + index + " for type '" + type +
				"' must be a primitive boxed type; got " + value.getClass().getName());
	}

	private static Number requireNumber(Object value, String label) {
		if (value instanceof Number n) return n;
		if (value instanceof Boolean b) return b ? 1 : 0;
		if (value instanceof Character c) return (int) c;
		throw new IllegalArgumentException("Field write requires a number for " + label +
				"; got " + (value == null ? "null" : value.getClass().getName()));
	}

	private static boolean requireBoolean(Object value) {
		if (value instanceof Boolean b) return b;
		if (value instanceof Number n) return n.intValue() != 0;
		throw new IllegalArgumentException("Field write requires a boolean; got " +
				(value == null ? "null" : value.getClass().getName()));
	}

	private static char requireChar(Object value) {
		if (value instanceof Character c) return c;
		if (value instanceof Number n) return (char) n.intValue();
		if (value instanceof String s && s.length() == 1) return s.charAt(0);
		throw new IllegalArgumentException("Field write requires a char; got " +
				(value == null ? "null" : value.getClass().getName()));
	}

	private static JavaField resolveStaticField(InstanceClass cls, String fieldName, String descriptor) {
		if (descriptor != null && !descriptor.isEmpty()) {
			JavaField exact = cls.getField(fieldName, descriptor);
			if (exact == null) {
				throw new IllegalArgumentException("Static field not found: " + cls.getName() +
						"." + fieldName + " " + descriptor);
			}
			if ((exact.getModifiers() & Opcodes.ACC_STATIC) == 0) {
				throw new IllegalArgumentException("Field " + fieldName + " on " + cls.getName() +
						" is not static.");
			}
			return exact;
		}
		// Search declared fields by name; require exactly one static match
		JavaField found = null;
		int matches = 0;
		for (JavaField f : cls.getDeclaredFields(false)) {
			if (!f.getName().equals(fieldName)) continue;
			if ((f.getModifiers() & Opcodes.ACC_STATIC) == 0) continue;
			found = f;
			matches++;
		}
		if (found == null) {
			throw new IllegalArgumentException("Static field not found: " + cls.getName() +
					"." + fieldName + ". Pass an explicit descriptor if the class has same-named " +
					"fields with different types.");
		}
		if (matches > 1) {
			throw new IllegalArgumentException("Ambiguous static field " + cls.getName() + "." +
					fieldName + " — multiple matches; pass an explicit descriptor.");
		}
		return found;
	}

	private static Object decodeReference(VirtualMachine vm, VMOperations ops, ObjectValue ref, String descriptor) {
		if (ref == null || ref.isNull()) return null;
		// Descriptor-driven String decode — avoids the deprecated ObjectValue#getJavaClass()
		// and matches what the underlying field/return type promises.
		if ("Ljava/lang/String;".equals(descriptor) && ref instanceof InstanceValue iv) {
			try {
				return ops.readUtf8(iv);
			} catch (Exception ignored) {
				// fall through to raw ObjectValue
			}
		}
		return ref;
	}

	private static ObjectValue encodeReference(VirtualMachine vm, VMOperations ops, Object value) {
		if (value == null) return vm.getMemoryManager().nullValue();
		if (value instanceof String s) return ops.newUtf8(s);
		if (value instanceof ObjectValue ov) return ov;
		throw new IllegalArgumentException("Reference write requires String, ObjectValue, or null; got " +
				value.getClass().getName());
	}

	// ---- descriptor parsing (mirrors SsvmExecutionProvider) ----

	static java.util.List<Character> parseParameterTypes(String descriptor) {
		java.util.List<Character> types = new java.util.ArrayList<>();
		int i = 1;
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
					i = descriptor.indexOf(';', i) + 1;
					break;
				case '[':
					types.add('[');
					while (i < descriptor.length() && descriptor.charAt(i) == '[') i++;
					if (i < descriptor.length() && descriptor.charAt(i) == 'L') {
						i = descriptor.indexOf(';', i) + 1;
					} else {
						i++;
					}
					break;
				default:
					throw new IllegalArgumentException("Invalid descriptor char '" + c +
							"' at " + i + " in " + descriptor);
			}
		}
		return types;
	}

	static char parseReturnType(String descriptor) {
		int close = descriptor.lastIndexOf(')');
		if (close < 0 || close + 1 >= descriptor.length()) {
			throw new IllegalArgumentException("Invalid method descriptor: " + descriptor);
		}
		return descriptor.charAt(close + 1);
	}
}
