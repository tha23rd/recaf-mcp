package dev.recafmcp.providers;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import software.coley.recaf.services.workspace.WorkspaceManager;

import java.util.List;
import java.util.Map;

/**
 * Provider for the search-tools meta-tool, allowing dynamic discovery of tools.
 */
public class SearchToolsProvider extends AbstractToolProvider {
	private final ToolRegistry registry;

	public SearchToolsProvider(McpSyncServer server, WorkspaceManager workspaceManager, ToolRegistry registry) {
		super(server, workspaceManager);
		this.registry = registry;
	}

	@Override
	public void registerTools() {
		Tool tool = Tool.builder()
				.name("search-tools")
				.description("Find relevant Recaf MCP tools by keyword. " +
						"Returns tool names and descriptions matching your query. " +
						"Use an empty query to list all tools.")
				.inputSchema(createSchema(
						Map.of("query", stringParam(
								"Search terms (space-separated, case-insensitive). " +
										"All terms must match name or description. " +
										"Empty string returns all tools."
						)),
						List.of()
				))
				.build();

		registerTool(tool, (exchange, args) -> {
			String query = getOptionalString(args, "query", "");
			List<ToolRegistry.ToolEntry> matches = registry.search(query);
			if (matches.isEmpty()) {
				return createTextResult("No tools found matching: \"" + query + "\". " +
						"Try broader terms or an empty query to list all tools.");
			}

			StringBuilder sb = new StringBuilder();
			sb.append(matches.size()).append(" tool(s) matching \"").append(query).append("\":\n\n");

			String currentCategory = null;
			for (ToolRegistry.ToolEntry entry : matches) {
				if (!entry.category().equals(currentCategory)) {
					currentCategory = entry.category();
					sb.append("## ").append(currentCategory).append('\n');
				}
				sb.append("- **").append(entry.name()).append("**: ").append(entry.description()).append('\n');
			}

			return createTextResult(sb.toString());
		});
	}
}
