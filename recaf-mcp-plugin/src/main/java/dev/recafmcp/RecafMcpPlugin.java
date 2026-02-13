package dev.recafmcp;

import dev.recafmcp.server.McpServerManager;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.plugin.Plugin;
import software.coley.recaf.plugin.PluginInformation;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.workspace.WorkspaceManager;

@Dependent
@PluginInformation(id = BuildConfig.PLUGIN_ID, version = BuildConfig.PLUGIN_VERSION,
		name = BuildConfig.PLUGIN_NAME, description = BuildConfig.PLUGIN_DESC)
public class RecafMcpPlugin implements Plugin {
	private static final Logger logger = Logging.get(RecafMcpPlugin.class);

	private final WorkspaceManager workspaceManager;
	private final DecompilerManager decompilerManager;
	private McpServerManager serverManager;

	@Inject
	public RecafMcpPlugin(WorkspaceManager workspaceManager,
	                      DecompilerManager decompilerManager) {
		this.workspaceManager = workspaceManager;
		this.decompilerManager = decompilerManager;
	}

	@Override
	public void onEnable() {
		logger.info("Recaf MCP Server plugin enabling...");
		try {
			serverManager = new McpServerManager();
			serverManager.start();
			logger.info("Recaf MCP Server plugin enabled successfully");
		} catch (Exception e) {
			logger.error("Failed to start MCP server", e);
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
