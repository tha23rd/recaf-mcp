package dev.recafmcp.ssvm;

import dev.xdark.ssvm.VirtualMachine;
import dev.xdark.ssvm.api.MethodInvoker;
import dev.xdark.ssvm.api.VMInterface;
import dev.xdark.ssvm.execution.Result;
import dev.xdark.ssvm.mirror.member.JavaMethod;
import dev.xdark.ssvm.mirror.type.InstanceClass;
import dev.xdark.ssvm.operation.VMOperations;
import dev.xdark.ssvm.symbol.Symbols;
import dev.xdark.ssvm.value.ArrayValue;
import dev.xdark.ssvm.value.InstanceValue;
import dev.xdark.ssvm.value.ObjectValue;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;

import java.util.List;

/**
 * Intercepts {@code Thread.getStackTrace()} in SSVM to return a synthetic stack trace.
 * <p>
 * Obfuscators use {@code Thread.getStackTrace()} for XOR key derivation, reading specific
 * frame indices to derive decryption keys. SSVM interprets bytecode on a single host thread,
 * so the real stack trace contains interpreter frames instead of the expected
 * {@code <clinit> -> decrypt -> keyDerive} chain. This interceptor replaces the return value
 * with user-specified synthetic frames so that stack-introspection-dependent key derivation
 * produces the correct results.
 * <p>
 * Frame indexing matters: obfuscators read specific indices (e.g. {@code getStackTrace()[3]}).
 * The override array must be positioned correctly.
 */
public class StackTraceInterceptor {
	private static final Logger logger = Logging.get(StackTraceInterceptor.class);

	private final VirtualMachine vm;
	private final List<StackFrame> frames;
	private JavaMethod getStackTraceMethod;
	private MethodInvoker originalInvoker;

	/**
	 * Create a new interceptor.
	 *
	 * @param vm     The SSVM instance.
	 * @param frames The synthetic stack trace frames to return from {@code Thread.getStackTrace()}.
	 */
	public StackTraceInterceptor(VirtualMachine vm, List<StackFrame> frames) {
		this.vm = vm;
		this.frames = frames;
	}

	/**
	 * Install the interceptor, replacing {@code Thread.getStackTrace()} with the synthetic trace.
	 * <p>
	 * Saves the original invoker so it can be restored by {@link #uninstall()}.
	 */
	public void install() {
		Symbols symbols = vm.getSymbols();
		VMInterface vmi = vm.getInterface();
		InstanceClass threadClass = symbols.java_lang_Thread();

		getStackTraceMethod = threadClass.getMethod("getStackTrace", "()[Ljava/lang/StackTraceElement;");
		if (getStackTraceMethod == null) {
			logger.warn("Thread.getStackTrace() method not found in SSVM — interceptor not installed");
			return;
		}

		// Save the original invoker (may be null if no custom invoker was set)
		originalInvoker = vmi.getInvoker(getStackTraceMethod);

		// Build the synthetic array once and reuse for each call
		ArrayValue syntheticTrace = buildStackTraceArray();

		vmi.setInvoker(getStackTraceMethod, ctx -> {
			ctx.setResult(syntheticTrace);
			return Result.ABORT;
		});

		logger.debug("Installed stack trace interceptor with {} synthetic frames", frames.size());
	}

	/**
	 * Uninstall the interceptor, restoring the original {@code Thread.getStackTrace()} behavior.
	 */
	public void uninstall() {
		if (getStackTraceMethod == null) {
			return;
		}

		VMInterface vmi = vm.getInterface();
		if (originalInvoker != null) {
			vmi.setInvoker(getStackTraceMethod, originalInvoker);
		} else {
			// No original invoker was set — remove our override by setting a pass-through
			// that lets the VM use its default interpreted behavior
			vmi.setInvoker(getStackTraceMethod, ctx -> Result.CONTINUE);
		}

		logger.debug("Uninstalled stack trace interceptor");
	}

	/**
	 * Build a VM {@code StackTraceElement[]} from the synthetic frame specifications.
	 *
	 * @return An SSVM ArrayValue containing the synthetic StackTraceElement objects.
	 */
	private ArrayValue buildStackTraceArray() {
		Symbols symbols = vm.getSymbols();
		VMOperations ops = vm.getOperations();
		InstanceClass steClass = symbols.java_lang_StackTraceElement();
		ops.initialize(steClass);

		ArrayValue array = ops.allocateArray(steClass, frames.size());
		for (int i = 0; i < frames.size(); i++) {
			StackFrame frame = frames.get(i);
			InstanceValue ste = vm.getMemoryManager().newInstance(steClass);

			ops.putReference(ste, "declaringClass", "Ljava/lang/String;",
					ops.newUtf8(frame.className));
			ops.putReference(ste, "methodName", "Ljava/lang/String;",
					ops.newUtf8(frame.methodName));
			if (frame.fileName != null) {
				ops.putReference(ste, "fileName", "Ljava/lang/String;",
						ops.newUtf8(frame.fileName));
			}
			ops.putInt(ste, "lineNumber", frame.lineNumber);

			array.setReference(i, ste);
		}
		return array;
	}

	/**
	 * A single frame in a synthetic stack trace.
	 */
	public static class StackFrame {
		public final String className;
		public final String methodName;
		public final String fileName;   // nullable
		public final int lineNumber;    // -1 if omitted

		public StackFrame(String className, String methodName, String fileName, int lineNumber) {
			this.className = className;
			this.methodName = methodName;
			this.fileName = fileName;
			this.lineNumber = lineNumber;
		}
	}
}
