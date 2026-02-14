package dev.recafmcp.ssvm;

import dev.xdark.ssvm.VirtualMachine;
import dev.xdark.ssvm.api.VMInterface;
import dev.xdark.ssvm.classloading.SupplyingClassLoaderInstaller;
import dev.xdark.ssvm.execution.Interpreter;
import dev.xdark.ssvm.execution.Result;
import dev.xdark.ssvm.filesystem.FileManager;
import dev.xdark.ssvm.filesystem.HostFileManager;
import dev.xdark.ssvm.invoke.InvocationUtil;
import dev.xdark.ssvm.mirror.type.InstanceClass;
import dev.xdark.ssvm.operation.VMOperations;
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
import java.io.IOException;
import java.io.InputStream;

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

	private VirtualMachine vm;
	private SupplyingClassLoaderInstaller.Helper helper;
	private InvocationUtil invocationUtil;
	private ByteArrayOutputStream stdoutCapture;
	private ByteArrayOutputStream stderrCapture;

	/**
	 * Create a new SsvmManager that listens for workspace changes.
	 *
	 * @param workspaceManager The Recaf workspace manager to bridge classes from.
	 */
	public SsvmManager(WorkspaceManager workspaceManager) {
		this.workspaceManager = workspaceManager;

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

			// Create VM with custom file manager for stdout/stderr capture
			VirtualMachine newVm = new VirtualMachine() {
				@Override
				protected FileManager createFileManager() {
					return new HostFileManager(InputStream.nullInputStream(), stdoutCapture, stderrCapture);
				}
			};

			// Bootstrap the VM (runs JDK init sequence)
			newVm.bootstrap();

			// Set sandbox defaults
			configureSandbox(newVm);

			// Set up class loading bridge from Recaf workspace
			SupplyingClassLoaderInstaller.DataSupplier supplier = createWorkspaceSupplier(workspace);
			SupplyingClassLoaderInstaller.Helper newHelper = SupplyingClassLoaderInstaller.install(newVm, supplier);

			// Create utilities
			InvocationUtil newInvocationUtil = InvocationUtil.create(newVm);

			// Commit state
			this.vm = newVm;
			this.helper = newHelper;
			this.invocationUtil = newInvocationUtil;

			long elapsed = System.currentTimeMillis() - startTime;
			logger.info("SSVM bootstrapped in {}ms", elapsed);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to install class loader into SSVM", e);
		} catch (Exception e) {
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
