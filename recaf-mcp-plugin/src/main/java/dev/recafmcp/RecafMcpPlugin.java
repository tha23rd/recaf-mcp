package dev.recafmcp;

import dev.recafmcp.providers.*;
import dev.recafmcp.resources.ClassResourceProvider;
import dev.recafmcp.resources.WorkspaceResourceProvider;
import dev.recafmcp.server.McpServerManager;
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
import software.coley.recaf.services.mapping.aggregate.AggregateMappingManager;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.search.SearchService;
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
	private final ResourceImporter resourceImporter;
	private McpServerManager serverManager;

	@Inject
	public RecafMcpPlugin(WorkspaceManager workspaceManager,
	                      DecompilerManager decompilerManager,
	                      SearchService searchService,
	                      CallGraphService callGraphService,
	                      InheritanceGraphService inheritanceGraphService,
	                      MappingApplierService mappingApplier,
	                      AggregateMappingManager mappingManager,
	                      ResourceImporter resourceImporter) {
		this.workspaceManager = workspaceManager;
		this.decompilerManager = decompilerManager;
		this.searchService = searchService;
		this.callGraphService = callGraphService;
		this.inheritanceGraphService = inheritanceGraphService;
		this.mappingApplier = mappingApplier;
		this.mappingManager = mappingManager;
		this.resourceImporter = resourceImporter;
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
			McpSyncServer mcp = serverManager.start();

			// Register all tool providers
			new WorkspaceToolProvider(mcp, workspaceManager, resourceImporter).registerTools();
			new NavigationToolProvider(mcp, workspaceManager).registerTools();
			new DecompilerToolProvider(mcp, workspaceManager, decompilerManager).registerTools();
			new SearchToolProvider(mcp, workspaceManager, searchService, decompilerManager).registerTools();
			new XRefToolProvider(mcp, workspaceManager, searchService).registerTools();
			new CallGraphToolProvider(mcp, workspaceManager, callGraphService).registerTools();
			new InheritanceToolProvider(mcp, workspaceManager, inheritanceGraphService).registerTools();
			new MappingToolProvider(mcp, workspaceManager, mappingApplier, mappingManager).registerTools();
			new CommentToolProvider(mcp, workspaceManager).registerTools();
			new CompilerToolProvider(mcp, workspaceManager).registerTools();
			new AssemblerToolProvider(mcp, workspaceManager).registerTools();
			new TransformToolProvider(mcp, workspaceManager).registerTools();
			new AttachToolProvider(mcp, workspaceManager).registerTools();

			// Register MCP resources
			new WorkspaceResourceProvider(mcp, workspaceManager).register();
			new ClassResourceProvider(mcp, workspaceManager, decompilerManager).register();

			logger.info("Recaf MCP Server plugin enabled — {} tool providers, {} resource providers",
					13, 2);
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
}
