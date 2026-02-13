package dev.recafmcp;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.plugin.Plugin;
import software.coley.recaf.plugin.PluginInformation;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.workspace.WorkspaceManager;

@Dependent
@PluginInformation(id = "##ID##", version = "##VERSION##", name = "##NAME##", description = "##DESC##")
public class RecafMcpPlugin implements Plugin {
	private static final Logger logger = Logging.get(RecafMcpPlugin.class);

	private final WorkspaceManager workspaceManager;
	private final DecompilerManager decompilerManager;

	@Inject
	public RecafMcpPlugin(WorkspaceManager workspaceManager,
	                      DecompilerManager decompilerManager) {
		this.workspaceManager = workspaceManager;
		this.decompilerManager = decompilerManager;
	}

	@Override
	public void onEnable() {
		logger.info("Recaf MCP Server plugin enabled");
	}

	@Override
	public void onDisable() {
		logger.info("Recaf MCP Server plugin disabled");
	}
}
