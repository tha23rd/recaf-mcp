package dev.recafmcp.ssvm;

import dev.xdark.ssvm.classloading.BootClassFinder;
import dev.xdark.ssvm.classloading.ParsedClassData;
import dev.xdark.ssvm.util.ClassUtil;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * A {@link BootClassFinder} that reads JDK boot classes from a specific JDK installation's
 * jrt filesystem, rather than from the running JVM.
 * <p>
 * SSVM requires JDK 11-17 boot classes (Thread.priority field, etc.) which are not present
 * in JDK 19+ (Project Loom restructured Thread). This class allows SSVM to bootstrap
 * against a compatible JDK regardless of the host JVM version.
 */
public class JrtBootClassFinder implements BootClassFinder, AutoCloseable {
	private static final Logger logger = Logging.get(JrtBootClassFinder.class);

	private final FileSystem jrtFs;
	private final URLClassLoader fsLoader;

	/**
	 * Create a boot class finder backed by the given JDK's jrt filesystem.
	 *
	 * @param javaHome Path to a JDK 11-17 installation (e.g. {@code /usr/lib/jvm/java-11-openjdk-amd64}).
	 * @throws IOException if the jrt filesystem cannot be opened.
	 */
	public JrtBootClassFinder(String javaHome) throws IOException {
		File jrtFsJar = new File(javaHome, "lib/jrt-fs.jar");
		if (!jrtFsJar.exists()) {
			throw new IOException("jrt-fs.jar not found at " + jrtFsJar.getAbsolutePath() +
					". Ensure JAVA_HOME points to a JDK 11-17 installation (not a JRE).");
		}

		this.fsLoader = new URLClassLoader(new URL[]{jrtFsJar.toURI().toURL()});
		this.jrtFs = FileSystems.newFileSystem(
				URI.create("jrt:/"),
				Map.of("java.home", javaHome),
				fsLoader
		);

		logger.debug("Opened jrt filesystem for boot classes from: {}", javaHome);
	}

	@Override
	public ParsedClassData findBootClass(String name) {
		// jrt filesystem structure: /modules/<module>/<package>/<class>.class
		// Boot classes are primarily in java.base but could be in other modules.
		// Try java.base first (covers 99% of cases), then scan other modules.
		Path classFile = jrtFs.getPath("/modules/java.base/" + name + ".class");
		if (!Files.exists(classFile)) {
			// Try other modules (java.logging, java.management, etc.)
			classFile = findInModules(name);
			if (classFile == null) {
				return null;
			}
		}

		try {
			byte[] bytes = Files.readAllBytes(classFile);
			ClassReader cr = new ClassReader(bytes);
			ClassNode node = ClassUtil.readNode(cr);
			return new ParsedClassData(cr, node);
		} catch (IOException e) {
			throw new IllegalStateException("Could not read bootstrap class: " + name, e);
		}
	}

	/**
	 * Search all modules for the given class name.
	 */
	private Path findInModules(String name) {
		try {
			Path modulesRoot = jrtFs.getPath("/modules");
			for (Path moduleDir : (Iterable<Path>) Files.list(modulesRoot)::iterator) {
				Path candidate = moduleDir.resolve(name + ".class");
				if (Files.exists(candidate)) {
					return candidate;
				}
			}
		} catch (IOException e) {
			logger.warn("Error scanning jrt modules for class: {}", name, e);
		}
		return null;
	}

	@Override
	public void close() {
		try {
			jrtFs.close();
		} catch (IOException e) {
			logger.debug("Error closing jrt filesystem", e);
		}
		try {
			fsLoader.close();
		} catch (IOException e) {
			logger.debug("Error closing URLClassLoader", e);
		}
	}
}
