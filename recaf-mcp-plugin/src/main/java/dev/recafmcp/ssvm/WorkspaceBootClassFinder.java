package dev.recafmcp.ssvm;

import dev.xdark.ssvm.classloading.BootClassFinder;
import dev.xdark.ssvm.classloading.ParsedClassData;
import dev.xdark.ssvm.util.ClassUtil;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;

import java.util.function.Function;

/**
 * A composite {@link BootClassFinder} that first checks JDK boot classes,
 * then falls back to workspace classes.
 * <p>
 * Loading workspace classes through the boot class finder bypasses the JDK's
 * {@code ClassLoader} infrastructure (protection domains, code sources, file I/O),
 * which requires many native method stubs that SSVM doesn't provide. Boot class
 * loading uses a simpler path that only needs the class bytecode.
 */
public class WorkspaceBootClassFinder implements BootClassFinder, AutoCloseable {
	private static final Logger logger = Logging.get(WorkspaceBootClassFinder.class);

	private final BootClassFinder delegate;
	private volatile Function<String, byte[]> workspaceSupplier;

	/**
	 * Create a composite boot class finder.
	 *
	 * @param delegate The primary boot class finder (JRT-based or default).
	 */
	public WorkspaceBootClassFinder(BootClassFinder delegate) {
		this.delegate = delegate;
	}

	/**
	 * Set the workspace class supplier. Called after VM construction when the
	 * workspace is available.
	 *
	 * @param supplier Function that takes an internal class name (e.g., "com/example/Foo")
	 *                 and returns the class bytecode, or null if not found.
	 */
	public void setWorkspaceSupplier(Function<String, byte[]> supplier) {
		this.workspaceSupplier = supplier;
	}

	@Override
	public ParsedClassData findBootClass(String name) {
		// First, try JDK boot classes
		ParsedClassData result = delegate.findBootClass(name);
		if (result != null) {
			return result;
		}

		// Fall back to workspace classes
		Function<String, byte[]> supplier = this.workspaceSupplier;
		if (supplier != null) {
			byte[] bytes = supplier.apply(name);
			if (bytes != null) {
				logger.debug("Boot class finder: loaded '{}' from workspace ({} bytes)", name, bytes.length);
				ClassReader cr = new ClassReader(bytes);
				ClassNode node = ClassUtil.readNode(cr);
				return new ParsedClassData(cr, node);
			}
		}

		return null;
	}

	@Override
	public void close() {
		if (delegate instanceof AutoCloseable closeable) {
			try {
				closeable.close();
			} catch (Exception e) {
				logger.debug("Error closing delegate boot class finder", e);
			}
		}
	}
}
