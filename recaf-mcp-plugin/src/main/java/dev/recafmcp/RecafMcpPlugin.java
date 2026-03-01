package dev.recafmcp;

import dev.recafmcp.providers.*;
import dev.recafmcp.server.JsonResponseSerializer;
import dev.recafmcp.server.McpServerManager;
import dev.recafmcp.server.ResponseSerializer;
import dev.recafmcp.server.ToonResponseSerializer;
import io.modelcontextprotocol.server.McpSyncServer;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.plugin.Plugin;
import software.coley.recaf.plugin.PluginInformation;
import software.coley.recaf.services.callgraph.CallGraphService;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.comment.CommentManager;
import software.coley.recaf.services.compile.JavacCompiler;
import software.coley.recaf.services.mapping.aggregate.AggregateMappingManager;
import software.coley.recaf.services.mapping.format.MappingFormatManager;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.phantom.PhantomGenerator;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.transform.TransformationApplierService;
import software.coley.recaf.services.transform.TransformationManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.io.ResourceImporter;

@Dependent
@PluginInformation(id = BuildConfig.PLUGIN_ID, version = BuildConfig.PLUGIN_VERSION,
		name = BuildConfig.PLUGIN_NAME, description = BuildConfig.PLUGIN_DESC)
public class RecafMcpPlugin implements Plugin {
	private static final Logger logger = Logging.get(RecafMcpPlugin.class);

	private final WorkspaceManager workspaceManager;
	private final DecompilerManager decompilerManager;
	private final SearchService searchService;
	private final CallGraphService callGraphService;
	private final InheritanceGraphService inheritanceGraphService;
	private final MappingApplierService mappingApplier;
	private final AggregateMappingManager mappingManager;
	private final MappingFormatManager mappingFormatManager;
	private final ResourceImporter resourceImporter;
	private final JavacCompiler javacCompiler;
	private final PhantomGenerator phantomGenerator;
	private final AssemblerPipelineManager assemblerPipelineManager;
	private final CommentManager commentManager;
	private final TransformationManager transformationManager;
	private final TransformationApplierService transformationApplierService;
	private McpServerManager serverManager;

	@Inject
	public RecafMcpPlugin(WorkspaceManager workspaceManager,
	                      DecompilerManager decompilerManager,
	                      SearchService searchService,
	                      CallGraphService callGraphService,
	                      InheritanceGraphService inheritanceGraphService,
	                      MappingApplierService mappingApplier,
	                      AggregateMappingManager mappingManager,
	                      MappingFormatManager mappingFormatManager,
	                      ResourceImporter resourceImporter,
	                      JavacCompiler javacCompiler,
	                      PhantomGenerator phantomGenerator,
	                      AssemblerPipelineManager assemblerPipelineManager,
	                      CommentManager commentManager,
	                      TransformationManager transformationManager,
	                      TransformationApplierService transformationApplierService) {
		this.workspaceManager = workspaceManager;
		this.decompilerManager = decompilerManager;
		this.searchService = searchService;
		this.callGraphService = callGraphService;
		this.inheritanceGraphService = inheritanceGraphService;
		this.mappingApplier = mappingApplier;
		this.mappingManager = mappingManager;
		this.mappingFormatManager = mappingFormatManager;
		this.resourceImporter = resourceImporter;
		this.javacCompiler = javacCompiler;
		this.phantomGenerator = phantomGenerator;
		this.assemblerPipelineManager = assemblerPipelineManager;
		this.commentManager = commentManager;
		this.transformationManager = transformationManager;
		this.transformationApplierService = transformationApplierService;
	}

	@Override
	public void onEnable() {
		logger.info("Recaf MCP Server plugin enabling...");
		// MCP SDK uses ServiceLoader internally. Recaf's plugin classloader is not the
		// thread context classloader, so ServiceLoader won't find our shaded service
		// entries unless we temporarily set it.
		ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
		try {
			Thread.currentThread().setContextClassLoader(getClass().getClassLoader());

			serverManager = new McpServerManager();
			McpSyncServer mcp = serverManager.start(McpServerManager.resolveHost(), McpServerManager.resolvePort());
			ToolRegistry toolRegistry = new ToolRegistry();

			// Resolve response format: -Drecaf.mcp.format=toon to enable TOON serialization
			ResponseSerializer serializer = resolveResponseSerializer();
			logger.info("Using response format: {}", serializer.formatName());
			registerCodeModeProviders(mcp, toolRegistry, serializer);

			logger.info("Recaf MCP Server plugin enabled — {} tool providers, {} resource providers",
					2, 0);
		} catch (Exception e) {
			logger.error("Failed to start MCP server", e);
		} finally {
			Thread.currentThread().setContextClassLoader(originalCl);
		}
	}

	@Override
	public void onDisable() {
		if (serverManager != null) {
			serverManager.stop();
			serverManager = null;
		}
		logger.info("Recaf MCP Server plugin disabled");
	}

	/**
	 * Resolves the response serializer from the {@code recaf.mcp.format} system property.
	 * Supported values: "json" (default), "toon".
	 *
	 * @return The configured {@link ResponseSerializer}.
	 */
	private static ResponseSerializer resolveResponseSerializer() {
		String format = System.getProperty("recaf.mcp.format", "toon");
		if ("json".equalsIgnoreCase(format)) {
			return new JsonResponseSerializer();
		}
		return new ToonResponseSerializer();
	}

	void registerCodeModeProviders(McpSyncServer mcp, ToolRegistry toolRegistry, ResponseSerializer serializer) {
		SearchToolsProvider searchTools = new SearchToolsProvider(mcp, workspaceManager, toolRegistry);
		searchTools.setResponseSerializer(serializer);
		searchTools.setToolRegistry(toolRegistry);
		searchTools.registerTools();

		GroovyScriptingProvider groovyScripting = new GroovyScriptingProvider(
				mcp, workspaceManager, decompilerManager, searchService,
				callGraphService, inheritanceGraphService
		);
		groovyScripting.setResponseSerializer(serializer);
		groovyScripting.setToolRegistry(toolRegistry);
		groovyScripting.registerTools();
	}

	/**
	 * Find a JDK 11-17 installation for SSVM boot classes.
	 * <p>
	 * SSVM requires JDK 11-17 class layouts (Thread.priority field, etc.) which are absent
	 * in JDK 19+ (Project Loom restructured Thread). This method auto-detects a compatible JDK.
	 * <p>
	 * Search order:
	 * <ol>
	 *     <li>{@code SSVM_BOOT_JDK} environment variable</li>
	 *     <li>{@code ssvm.boot.jdk} system property</li>
	 *     <li>Running JVM if version is 11-18</li>
	 *     <li>Well-known JDK paths for Linux/macOS/Windows</li>
	 * </ol>
	 *
	 * @return Path to a compatible JDK, or {@code null} if the running JVM is compatible.
	 */
	private static String findCompatibleJdk() {
		// Check explicit configuration
		String explicit = System.getenv("SSVM_BOOT_JDK");
		if (explicit == null) {
			explicit = System.getProperty("ssvm.boot.jdk");
		}
		if (explicit != null) {
			logger.info("Using configured SSVM boot JDK: {}", explicit);
			return explicit;
		}

		// Check if running JVM is compatible (JDK 11-18)
		int version = Runtime.version().feature();
		if (version >= 11 && version <= 18) {
			logger.debug("Running JVM (JDK {}) is compatible with SSVM, no boot JDK override needed", version);
			return null;
		}

		logger.info("Running JVM is JDK {} (SSVM needs 11-17 boot classes), searching for compatible JDK...", version);

		// Search well-known paths
		String[] candidates = {
				// Linux (Debian/Ubuntu)
				"/usr/lib/jvm/java-17-openjdk-amd64",
				"/usr/lib/jvm/java-11-openjdk-amd64",
				// Linux (RHEL/Fedora)
				"/usr/lib/jvm/java-17-openjdk",
				"/usr/lib/jvm/java-11-openjdk",
				// Linux (generic)
				"/usr/lib/jvm/java-17",
				"/usr/lib/jvm/java-11",
				// macOS (Homebrew)
				"/opt/homebrew/opt/openjdk@17/libexec",
				"/opt/homebrew/opt/openjdk@11/libexec",
				"/usr/local/opt/openjdk@17/libexec",
				"/usr/local/opt/openjdk@11/libexec",
				// macOS (Temurin via sdkman/jabba)
				System.getProperty("user.home") + "/.sdkman/candidates/java/17-tem",
				System.getProperty("user.home") + "/.sdkman/candidates/java/11-tem",
		};

		for (String candidate : candidates) {
			java.io.File jrtFs = new java.io.File(candidate, "lib/jrt-fs.jar");
			if (jrtFs.exists()) {
				logger.info("Found compatible JDK for SSVM boot classes: {}", candidate);
				return candidate;
			}
		}

		logger.warn("No compatible JDK 11-17 found for SSVM boot classes. " +
				"Set SSVM_BOOT_JDK environment variable to a JDK 11-17 installation path. " +
				"VM tools will fail until this is resolved.");
		return null;
	}
}
