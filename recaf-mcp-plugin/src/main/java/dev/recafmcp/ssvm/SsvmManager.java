package dev.recafmcp.ssvm;

import dev.xdark.ssvm.VirtualMachine;
import dev.xdark.ssvm.api.VMInterface;
import dev.xdark.ssvm.classloading.BootClassFinder;
import dev.xdark.ssvm.classloading.SupplyingClassLoader;
import dev.xdark.ssvm.classloading.SupplyingClassLoaderInstaller;
import dev.xdark.ssvm.execution.Interpreter;
import dev.xdark.ssvm.execution.Result;
import dev.xdark.ssvm.filesystem.FileManager;
import dev.xdark.ssvm.filesystem.HostFileManager;
import dev.xdark.ssvm.invoke.InvocationUtil;
import dev.xdark.ssvm.memory.management.MemoryManager;
import dev.xdark.ssvm.mirror.type.InstanceClass;
import dev.xdark.ssvm.operation.VMOperations;
import dev.xdark.ssvm.util.ClassLoaderUtils;
import dev.xdark.ssvm.util.IOUtil;
import dev.xdark.ssvm.value.InstanceValue;
import dev.xdark.ssvm.value.ObjectValue;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.workspace.WorkspaceCloseListener;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.WorkspaceOpenListener;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Function;

/**
 * Manages the SSVM {@link VirtualMachine} lifecycle for sandboxed bytecode execution.
 * <p>
 * Responsibilities:
 * <ul>
 *     <li>Lazy VM bootstrap on first access (expensive: ~2-10s)</li>
 *     <li>Class loading bridge from Recaf workspace to SSVM</li>
 *     <li>Stdout/stderr capture via custom {@link FileManager}</li>
 *     <li>Auto-reset on workspace changes (open/close)</li>
 *     <li>Thread-safe synchronized access (one tool call at a time)</li>
 *     <li>Sandbox: blocks {@code Runtime.exec}, sets max iterations</li>
 * </ul>
 */
public class SsvmManager {
	private static final Logger logger = Logging.get(SsvmManager.class);
	private static final int DEFAULT_MAX_ITERATIONS = 10_000_000;

	private final WorkspaceManager workspaceManager;
	private final WorkspaceOpenListener openListener;
	private final WorkspaceCloseListener closeListener;

	private final String bootJdkHome;

	private VirtualMachine vm;
	private JrtBootClassFinder bootClassFinder;
	private SupplyingClassLoaderInstaller.Helper helper;
	private InvocationUtil invocationUtil;
	private ByteArrayOutputStream stdoutCapture;
	private ByteArrayOutputStream stderrCapture;

	/**
	 * Create a new SsvmManager that listens for workspace changes.
	 *
	 * @param workspaceManager The Recaf workspace manager to bridge classes from.
	 * @param bootJdkHome      Path to a JDK 11-17 installation for SSVM boot classes.
	 *                         SSVM requires JDK 11-17 class layouts (e.g. Thread.priority field)
	 *                         which are absent in JDK 19+ (Project Loom). If {@code null},
	 *                         uses the running JVM's classes (only works on JDK 11-17).
	 */
	public SsvmManager(WorkspaceManager workspaceManager, String bootJdkHome) {
		this.workspaceManager = workspaceManager;
		this.bootJdkHome = bootJdkHome;

		// Register workspace listeners to auto-reset VM on workspace change
		this.openListener = workspace -> {
			logger.info("Workspace opened, resetting SSVM");
			resetVm();
		};
		this.closeListener = workspace -> {
			logger.info("Workspace closed, resetting SSVM");
			resetVm();
		};
		workspaceManager.addWorkspaceOpenListener(openListener);
		workspaceManager.addWorkspaceCloseListener(closeListener);
	}

	/**
	 * Get the SSVM {@link VirtualMachine}, bootstrapping it lazily on first access.
	 * <p>
	 * The first call is expensive (~2-10s) as SSVM runs the full JDK init sequence.
	 * Subsequent calls return the same instance until the workspace changes.
	 *
	 * @return The bootstrapped VM instance.
	 * @throws IllegalStateException if no workspace is open or bootstrap fails.
	 */
	public synchronized VirtualMachine getVm() {
		if (vm == null) {
			bootstrapVm();
		}
		return vm;
	}

	/**
	 * Get the class loader helper for loading workspace classes into SSVM.
	 *
	 * @return The helper instance.
	 * @throws IllegalStateException if no workspace is open or bootstrap fails.
	 */
	public synchronized SupplyingClassLoaderInstaller.Helper getHelper() {
		if (vm == null) {
			bootstrapVm();
		}
		return helper;
	}

	/**
	 * Get the {@link InvocationUtil} for calling methods in the VM.
	 *
	 * @return The invocation utility.
	 * @throws IllegalStateException if no workspace is open or bootstrap fails.
	 */
	public synchronized InvocationUtil getInvocationUtil() {
		if (vm == null) {
			bootstrapVm();
		}
		return invocationUtil;
	}

	/**
	 * Get the {@link VMOperations} for reading fields and performing VM operations.
	 *
	 * @return The VM operations utility.
	 * @throws IllegalStateException if no workspace is open or bootstrap fails.
	 */
	public synchronized VMOperations getOperations() {
		if (vm == null) {
			bootstrapVm();
		}
		return vm.getOperations();
	}

	/**
	 * Get captured stdout output and reset the buffer.
	 *
	 * @return The captured stdout text, or empty string if VM not initialized.
	 */
	public synchronized String getAndResetStdout() {
		if (stdoutCapture == null) {
			return "";
		}
		String output = stdoutCapture.toString();
		stdoutCapture.reset();
		return output;
	}

	/**
	 * Get captured stderr output and reset the buffer.
	 *
	 * @return The captured stderr text, or empty string if VM not initialized.
	 */
	public synchronized String getAndResetStderr() {
		if (stderrCapture == null) {
			return "";
		}
		String output = stderrCapture.toString();
		stderrCapture.reset();
		return output;
	}

	/**
	 * Force a VM reset. The VM will be re-bootstrapped on next access.
	 * Called automatically when workspace changes.
	 */
	public synchronized void resetVm() {
		if (vm != null) {
			logger.info("Resetting SSVM instance");
		}
		if (bootClassFinder != null) {
			bootClassFinder.close();
			bootClassFinder = null;
		}
		vm = null;
		helper = null;
		invocationUtil = null;
		stdoutCapture = null;
		stderrCapture = null;
	}

	/**
	 * Check if the VM has been bootstrapped.
	 *
	 * @return {@code true} if the VM is currently initialized and ready.
	 */
	public synchronized boolean isInitialized() {
		return vm != null;
	}

	/**
	 * Remove workspace listeners. Call when shutting down.
	 */
	public void close() {
		workspaceManager.removeWorkspaceOpenListener(openListener);
		workspaceManager.removeWorkspaceCloseListener(closeListener);
		resetVm();
	}

	// ---- Internal bootstrap ----

	/**
	 * Bootstrap the SSVM VirtualMachine with workspace class loading,
	 * stdout/stderr capture, and sandbox restrictions.
	 */
	private void bootstrapVm() {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			throw new IllegalStateException(
					"No workspace is currently open in Recaf. " +
					"Open a JAR, APK, or other supported file before using VM tools."
			);
		}

		logger.info("Bootstrapping SSVM (this may take a few seconds)...");
		long startTime = System.currentTimeMillis();

		try {
			// Create output capture streams
			stdoutCapture = new ByteArrayOutputStream();
			stderrCapture = new ByteArrayOutputStream();

			// Create boot class finder from a compatible JDK if needed
			JrtBootClassFinder jrtFinder = null;
			if (bootJdkHome != null) {
				jrtFinder = new JrtBootClassFinder(bootJdkHome);
				logger.info("Using JDK boot classes from: {}", bootJdkHome);
			}
			final JrtBootClassFinder finalJrtFinder = jrtFinder;

			// Create VM with custom file manager and boot class finder
			VirtualMachine newVm = new VirtualMachine() {
				@Override
				protected FileManager createFileManager() {
					return new HostFileManager(InputStream.nullInputStream(), stdoutCapture, stderrCapture);
				}

				@Override
				protected BootClassFinder createBootClassFinder() {
					if (finalJrtFinder != null) {
						return finalJrtFinder;
					}
					return super.createBootClassFinder();
				}
			};

			// Two-phase bootstrap:
			// 1) initialize() — links core classes, registers native stubs (System, Thread, etc.)
			// 2) Install initPhase2 stub — JDK 11's System.initPhase2 runs ModuleBootstrap.boot()
			//    which fails on JDK 19+ hosts. We stub it to return 0 (success) since the module
			//    system isn't needed for our sandboxed execution.
			// 3) bootstrap() — skips init (already done), runs boot() with our stub active
			newVm.initialize();
			installInitPhase2Stub(newVm);
			newVm.bootstrap();

			// Set sandbox defaults
			configureSandbox(newVm);

			// Set up class loading bridge from Recaf workspace
			// Note: Can't use SupplyingClassLoaderInstaller.install() because it uses
			// ClassLoader.getSystemResourceAsStream() which can't find the shaded
			// SupplyingClassLoader class in Recaf's plugin classloader.
			SupplyingClassLoaderInstaller.DataSupplier supplier = createWorkspaceSupplier(workspace);
			SupplyingClassLoaderInstaller.Helper newHelper = installClassLoader(newVm, supplier);

			// Create utilities
			InvocationUtil newInvocationUtil = InvocationUtil.create(newVm);

			// Commit state
			this.vm = newVm;
			this.bootClassFinder = jrtFinder;
			this.helper = newHelper;
			this.invocationUtil = newInvocationUtil;

			long elapsed = System.currentTimeMillis() - startTime;
			logger.info("SSVM bootstrapped in {}ms", elapsed);
		} catch (IOException e) {
			logger.error("Failed to install class loader into SSVM", e);
			throw new IllegalStateException("Failed to install class loader into SSVM", e);
		} catch (Exception e) {
			logger.error("Failed to bootstrap SSVM", e);
			throw new IllegalStateException("Failed to bootstrap SSVM: " + e.getMessage(), e);
		}
	}

	/**
	 * Configure sandbox restrictions on the VM.
	 * <ul>
	 *     <li>Override {@code Runtime.exec} to throw SecurityException</li>
	 *     <li>Set default max iterations to prevent infinite loops</li>
	 * </ul>
	 */
	private void configureSandbox(VirtualMachine vm) {
		// Set max iterations to prevent infinite loops
		Interpreter.setMaxIterations(DEFAULT_MAX_ITERATIONS);

		// Block Runtime.exec — intercept all exec overloads
		VMInterface vmi = vm.getInterface();
		InstanceClass runtimeClass = (InstanceClass) vm.findBootstrapClass("java/lang/Runtime");
		if (runtimeClass != null) {
			InstanceClass securityException = (InstanceClass) vm.findBootstrapClass("java/lang/SecurityException");

			String[] execDescriptors = {
					"(Ljava/lang/String;)Ljava/lang/Process;",
					"([Ljava/lang/String;)Ljava/lang/Process;",
					"(Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/Process;",
					"([Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/Process;",
					"(Ljava/lang/String;[Ljava/lang/String;Ljava/io/File;)Ljava/lang/Process;",
					"([Ljava/lang/String;[Ljava/lang/String;Ljava/io/File;)Ljava/lang/Process;"
			};

			for (String descriptor : execDescriptors) {
				vmi.setInvoker(runtimeClass, "exec", descriptor, ctx -> {
					vm.getOperations().throwException(securityException,
							"Process execution is blocked in the SSVM sandbox");
					return Result.ABORT;
				});
			}
			logger.debug("Blocked Runtime.exec in SSVM sandbox");
		}
	}

	/**
	 * Install a stub for {@code System.initPhase2(boolean, boolean)} that returns 0 (success).
	 * <p>
	 * On JDK 9+, SSVM's boot sequence calls {@code System.initPhase2(true, true)} which runs
	 * {@code ModuleBootstrap.boot()} to initialize the module system. This fails when the host
	 * JVM is JDK 19+ because the module bootstrap code invokes native methods whose behavior
	 * changed across JDK versions. Since module system initialization isn't required for our
	 * sandboxed deobfuscation use case, we stub it out.
	 * <p>
	 * Must be called after {@link VirtualMachine#initialize()} (which links {@code java.lang.System})
	 * and before {@link VirtualMachine#bootstrap()} (which calls {@code initPhase2}).
	 *
	 * @param vm The initialized (but not yet booted) VM.
	 */
	private void installInitPhase2Stub(VirtualMachine vm) {
		VMInterface vmi = vm.getInterface();
		InstanceClass systemClass = (InstanceClass) vm.findBootstrapClass("java/lang/System");
		if (systemClass == null) {
			logger.warn("Could not find java/lang/System — initPhase2 stub not installed");
			return;
		}

		boolean installed = vmi.setInvoker(systemClass, "initPhase2", "(ZZ)I", ctx -> {
			ctx.setResult(0); // 0 = success
			return Result.ABORT;
		});

		if (installed) {
			logger.debug("Installed initPhase2 stub (module bootstrap skipped)");
		} else {
			logger.warn("Failed to install initPhase2 stub — method not found on System class");
		}
	}

	/**
	 * Install a {@link SupplyingClassLoader} into the VM, working around the shaded classloader issue.
	 * <p>
	 * {@link SupplyingClassLoaderInstaller#install} uses {@code ClassLoader.getSystemResourceAsStream()}
	 * to read the {@link SupplyingClassLoader} bytecode, which fails when running inside Recaf's plugin
	 * classloader with shaded packages. This method does the same thing but reads the class from the
	 * plugin's classloader instead.
	 *
	 * @param vm       The bootstrapped VM.
	 * @param supplier The data supplier for workspace classes.
	 * @return Helper for loading classes into the VM.
	 * @throws IOException If the SupplyingClassLoader bytecode cannot be read.
	 */
	private static SupplyingClassLoaderInstaller.Helper installClassLoader(
			VirtualMachine vm, SupplyingClassLoaderInstaller.DataSupplier supplier) throws IOException {
		// Read SupplyingClassLoader bytecode from the plugin's classloader (not system classloader)
		String loaderName = SupplyingClassLoader.class.getName();
		String resourcePath = loaderName.replace('.', '/') + ".class";
		InputStream loaderStream = SupplyingClassLoader.class.getClassLoader().getResourceAsStream(resourcePath);
		if (loaderStream == null) {
			throw new FileNotFoundException(loaderName + " (resource: " + resourcePath + ")");
		}

		// Define the class in the VM with a unique name (same logic as SupplyingClassLoaderInstaller)
		String uniqueName = loaderName + System.nanoTime();
		byte[] originalBytes = IOUtil.readAll(loaderStream);
		byte[] bytes = remapClass(originalBytes, loaderName, uniqueName);
		ObjectValue nullV = vm.getMemoryManager().nullValue();
		String sourceName = uniqueName.substring(uniqueName.lastIndexOf('/') + 1) + ".java";
		InstanceValue loader = ClassLoaderUtils.systemClassLoader(vm);
		VMOperations operations = vm.getOperations();
		InstanceClass loaderClass = operations.defineClass(
				loader, uniqueName, bytes, 0, bytes.length, nullV, sourceName, 0);

		// Register invokers for the native provide methods
		VMInterface vmi = vm.getInterface();
		MemoryManager memoryManager = vm.getMemoryManager();
		vmi.setInvoker(loaderClass, "provideClass", "(Ljava/lang/String;)[B",
				createProviderInvoker(supplier::getClass, operations, memoryManager));
		vmi.setInvoker(loaderClass, "provideResource", "(Ljava/lang/String;)[B",
				createProviderInvoker(supplier::getResource, operations, memoryManager));

		return new SupplyingClassLoaderInstaller.Helper(vm, loaderClass);
	}

	/**
	 * Remap a class's internal name using ASM {@link ClassRemapper}.
	 */
	private static byte[] remapClass(byte[] bytes, String fromName, String toName) {
		ClassWriter writer = new ClassWriter(0);
		ClassReader reader = new ClassReader(bytes);
		reader.accept(new ClassRemapper(writer,
				new SimpleRemapper(fromName.replace('.', '/'), toName.replace('.', '/'))), 0);
		return writer.toByteArray();
	}

	/**
	 * Create a {@link dev.xdark.ssvm.api.MethodInvoker} that bridges a supplier function
	 * to the VM's native method interface.
	 */
	private static dev.xdark.ssvm.api.MethodInvoker createProviderInvoker(
			Function<String, byte[]> supplier, VMOperations operations, MemoryManager memoryManager) {
		return ctx -> {
			String paramName = operations.readUtf8(ctx.getLocals().loadReference(1));
			byte[] data = supplier.apply(paramName);
			if (data == null) {
				ctx.setResult(memoryManager.nullValue());
			} else {
				ctx.setResult(operations.toVMBytes(data));
			}
			return Result.ABORT;
		};
	}

	/**
	 * Create a {@link SupplyingClassLoaderInstaller.DataSupplier} that resolves classes
	 * from the Recaf workspace.
	 * <p>
	 * Lookup order:
	 * <ol>
	 *     <li>Primary resource's JVM class bundle</li>
	 *     <li>Supporting resources' JVM class bundles</li>
	 * </ol>
	 * Returns {@code null} if not found (SSVM falls back to host JVM boot classes).
	 *
	 * @param workspace The Recaf workspace to bridge.
	 * @return A data supplier for SSVM class loading.
	 */
	private SupplyingClassLoaderInstaller.DataSupplier createWorkspaceSupplier(Workspace workspace) {
		return SupplyingClassLoaderInstaller.supplyFromFunctions(
				className -> lookupClassInWorkspace(workspace, className),
				resourceName -> null  // Resource loading not needed for v1
		);
	}

	/**
	 * Look up a class in the Recaf workspace by name.
	 * <p>
	 * The {@link SupplyingClassLoaderInstaller} calls this with dot-notation class names
	 * (e.g., {@code com.example.Foo}). We convert to internal format (slashes) for Recaf
	 * bundle lookup.
	 *
	 * @param workspace The workspace to search.
	 * @param className Class name in dot notation (e.g., {@code com.example.Foo}).
	 * @return The class bytecode, or {@code null} if not found.
	 */
	private byte[] lookupClassInWorkspace(Workspace workspace, String className) {
		// Convert dot notation to internal name (slashes)
		String internalName = className.replace('.', '/');

		// Check primary resource first
		WorkspaceResource primary = workspace.getPrimaryResource();
		JvmClassBundle primaryBundle = primary.getJvmClassBundle();
		JvmClassInfo classInfo = primaryBundle.get(internalName);
		if (classInfo != null) {
			return classInfo.getBytecode();
		}

		// Check supporting resources
		for (WorkspaceResource supporting : workspace.getSupportingResources()) {
			JvmClassBundle supportingBundle = supporting.getJvmClassBundle();
			JvmClassInfo supportingInfo = supportingBundle.get(internalName);
			if (supportingInfo != null) {
				return supportingInfo.getBytecode();
			}
		}

		// Not found in workspace — SSVM will try host JVM boot classes
		return null;
	}
}
