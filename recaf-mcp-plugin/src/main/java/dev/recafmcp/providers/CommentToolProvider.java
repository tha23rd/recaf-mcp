package dev.recafmcp.providers;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.workspace.WorkspaceManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool provider for comment management operations.
 * <p>
 * Provides tools for setting, getting, searching, and deleting comments
 * on classes and members within the current Recaf workspace.
 */
public class CommentToolProvider extends AbstractToolProvider {
	private static final Logger logger = Logging.get(CommentToolProvider.class);

	public CommentToolProvider(McpSyncServer server, WorkspaceManager workspaceManager) {
		super(server, workspaceManager);
	}

	@Override
	public void registerTools() {
		registerCommentSet();
		registerCommentGet();
		registerCommentSearch();
		registerCommentDelete();
	}

	// ---- comment-set ----

	private void registerCommentSet() {
		Tool tool = Tool.builder()
				.name("comment-set")
				.description("Set a comment on a class or class member in the current workspace")
				.inputSchema(createSchema(
						Map.of(
								"className", stringParam("Fully qualified class name (e.g., 'com/example/MyClass')"),
								"memberName", stringParam("Optional member name (field or method) within the class"),
								"comment", stringParam("The comment text to set")
						),
						List.of("className", "comment")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			requireWorkspace();
			String className = getString(args, "className");
			String memberName = getOptionalString(args, "memberName", null);
			String comment = getString(args, "comment");

			logger.info("Setting comment on class='{}', member='{}': {}", className, memberName, comment);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "stub");
			result.put("className", className);
			if (memberName != null) {
				result.put("memberName", memberName);
			}
			result.put("comment", comment);
			result.put("message", "Comment management requires integration with Recaf's CommentManager " +
					"path-based API. This tool will set comments on classes and members.");
			return createJsonResult(result);
		});
	}

	// ---- comment-get ----

	private void registerCommentGet() {
		Tool tool = Tool.builder()
				.name("comment-get")
				.description("Get the comment on a class or class member in the current workspace")
				.inputSchema(createSchema(
						Map.of(
								"className", stringParam("Fully qualified class name (e.g., 'com/example/MyClass')"),
								"memberName", stringParam("Optional member name (field or method) within the class")
						),
						List.of("className")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			requireWorkspace();
			String className = getString(args, "className");
			String memberName = getOptionalString(args, "memberName", null);

			logger.info("Getting comment for class='{}', member='{}'", className, memberName);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "stub");
			result.put("className", className);
			if (memberName != null) {
				result.put("memberName", memberName);
			}
			result.put("message", "Comment retrieval requires integration with Recaf's CommentManager " +
					"path-based API. This tool will return the comment text associated with the specified " +
					"class or member, or indicate that no comment exists.");
			return createJsonResult(result);
		});
	}

	// ---- comment-search ----

	private void registerCommentSearch() {
		Tool tool = Tool.builder()
				.name("comment-search")
				.description("Search comments across all classes and members in the current workspace")
				.inputSchema(createSchema(
						Map.of(
								"query", stringParam("Search query to match against comment text"),
								"offset", intParam("Starting offset for paginated results (default: 0)"),
								"limit", intParam("Maximum number of results to return (default: 100)")
						),
						List.of("query")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			requireWorkspace();
			String query = getString(args, "query");
			int offset = getInt(args, "offset", 0);
			int limit = getInt(args, "limit", 100);

			logger.info("Searching comments with query='{}', offset={}, limit={}", query, offset, limit);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "stub");
			result.put("query", query);
			result.put("offset", offset);
			result.put("limit", limit);
			result.put("message", "Comment search requires integration with Recaf's CommentManager " +
					"path-based API. This tool will search all workspace comments matching the query text " +
					"and return paginated results with class/member paths and comment content.");
			return createJsonResult(result);
		});
	}

	// ---- comment-delete ----

	private void registerCommentDelete() {
		Tool tool = Tool.builder()
				.name("comment-delete")
				.description("Delete the comment on a class or class member in the current workspace")
				.inputSchema(createSchema(
						Map.of(
								"className", stringParam("Fully qualified class name (e.g., 'com/example/MyClass')"),
								"memberName", stringParam("Optional member name (field or method) within the class")
						),
						List.of("className")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			requireWorkspace();
			String className = getString(args, "className");
			String memberName = getOptionalString(args, "memberName", null);

			logger.info("Deleting comment for class='{}', member='{}'", className, memberName);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "stub");
			result.put("className", className);
			if (memberName != null) {
				result.put("memberName", memberName);
			}
			result.put("message", "Comment deletion requires integration with Recaf's CommentManager " +
					"path-based API. This tool will remove the comment associated with the specified " +
					"class or member.");
			return createJsonResult(result);
		});
	}
}
