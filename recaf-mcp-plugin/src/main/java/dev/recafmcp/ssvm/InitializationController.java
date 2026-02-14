package dev.recafmcp.ssvm;

import dev.xdark.ssvm.VirtualMachine;
import dev.xdark.ssvm.execution.Result;
import dev.xdark.ssvm.jvmti.JVMTIEnv;
import dev.xdark.ssvm.mirror.member.JavaMethod;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Controls which classes can initialize during an SSVM tool call.
 * <p>
 * Uses JVMTI listeners to:
 * <ul>
 *     <li>Track which classes have their {@code <clinit>} run (via MethodEnter)</li>
 *     <li>Optionally enforce a whitelist of classes allowed to initialize transitively
 *         (via ClassLink listener that no-ops non-whitelisted {@code <clinit>} methods)</li>
 * </ul>
 * <p>
 * JDK bootstrap classes (with a null classloader) are always allowed to initialize,
 * as blocking them would break fundamental JVM behavior.
 */
public class InitializationController implements AutoCloseable {
	private static final Logger logger = Logging.get(InitializationController.class);

	private final VirtualMachine vm;
	private final String targetClass;
	private final Set<String> whitelist;
	private final List<String> classesInitialized = new ArrayList<>();
	private final List<String> classesDeferred = new ArrayList<>();
	private JVMTIEnv env;

	/**
	 * Create a new initialization controller.
	 *
	 * @param vm          The SSVM instance.
	 * @param targetClass The target class name (dot notation) that is always allowed to initialize.
	 * @param whitelist   Set of class names (dot notation) whose {@code <clinit>} may fire
	 *                    transitively. {@code null} means allow all (standard JVM behavior).
	 *                    An empty set means only the target class and JDK classes can initialize.
	 */
	public InitializationController(VirtualMachine vm, String targetClass, Set<String> whitelist) {
		this.vm = vm;
		this.targetClass = targetClass;
		this.whitelist = whitelist;
	}

	/**
	 * Install JVMTI listeners. Must be called before loading/initializing the target class.
	 */
	public void install() {
		env = vm.newJvmtiEnv();

		// Track clinit calls via MethodEnter
		env.setMethodEnter(ctx -> {
			JavaMethod method = ctx.getMethod();
			if ("<clinit>".equals(method.getName())) {
				String className = method.getOwner().getInternalName().replace('/', '.');
				classesInitialized.add(className);
			}
		});

		// Block non-whitelisted clinits via ClassLink (fires AFTER methods are set up, BEFORE initialize())
		if (whitelist != null) {
			env.setClassLink(instanceClass -> {
				String className = instanceClass.getInternalName().replace('/', '.');

				// Skip JDK/bootstrap classes (null classloader) - always allowed
				if (instanceClass.getClassLoader().isNull()) {
					return;
				}

				// Target class is always allowed
				if (className.equals(targetClass)) {
					return;
				}

				// If class is not in the whitelist, noop its clinit
				if (!whitelist.contains(className)) {
					JavaMethod clinit = instanceClass.getMethod("<clinit>", "()V");
					if (clinit != null) {
						vm.getInterface().setInvoker(clinit, ctx -> Result.ABORT);
						classesDeferred.add(className);
						logger.debug("Deferred <clinit> for non-whitelisted class: {}", className);
					}
				}
			});
		}

		logger.debug("Installed initialization controller for {} (whitelist: {})",
				targetClass, whitelist == null ? "allow-all" : whitelist.size() + " classes");
	}

	/**
	 * Remove all JVMTI listeners. Safe to call multiple times.
	 */
	@Override
	public void close() {
		if (env != null) {
			env.close();
			env = null;
			logger.debug("Closed initialization controller (initialized: {}, deferred: {})",
					classesInitialized.size(), classesDeferred.size());
		}
	}

	/**
	 * @return Ordered list of class names whose {@code <clinit>} actually ran.
	 */
	public List<String> getClassesInitialized() {
		return Collections.unmodifiableList(classesInitialized);
	}

	/**
	 * @return Ordered list of class names whose {@code <clinit>} was blocked by the whitelist.
	 */
	public List<String> getClassesDeferred() {
		return Collections.unmodifiableList(classesDeferred);
	}
}
