package dev.recafmcp.providers;

import dev.recafmcp.util.ClassResolver;
import dev.recafmcp.util.ErrorHelper;
import dev.recafmcp.util.PaginationUtil;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.search.match.StringPredicate;
import software.coley.recaf.services.search.match.StringPredicateProvider;
import software.coley.recaf.services.search.query.ReferenceQuery;
import software.coley.recaf.services.search.result.Result;
import software.coley.recaf.services.search.result.Results;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool provider for cross-reference (xref) analysis.
 * <p>
 * Provides tools for finding references to classes and members within the
 * current workspace using Recaf's {@link SearchService}.
 */
public class XRefToolProvider extends AbstractToolProvider {
	private static final Logger logger = Logging.get(XRefToolProvider.class);

	private final SearchService searchService;

	public XRefToolProvider(McpSyncServer server,
	                        WorkspaceManager workspaceManager,
	                        SearchService searchService) {
		super(server, workspaceManager);
		this.searchService = searchService;
	}

	@Override
	public void registerTools() {
		registerXRefsTo();
		registerXRefsFrom();
		registerXRefsCount();
	}

	// ---- xrefs-to ----

	private void registerXRefsTo() {
		Tool tool = Tool.builder()
				.name("xrefs-to")
				.description("Find all references to a class or member within the workspace. " +
						"Returns locations where the specified class/member is used.")
				.inputSchema(createSchema(
						Map.of(
								"className", stringParam("Internal or dot-notation class name to find references to (required)"),
								"memberName", stringParam("Member name to find references to (optional, narrows to field/method)"),
								"memberDescriptor", stringParam("Member descriptor to find references to (optional, further narrows results)"),
								"offset", intParam("Pagination offset (default 0)"),
								"limit", intParam("Maximum results to return (default 100, max 1000)")
						),
						List.of("className")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();

			String className = ClassResolver.normalizeClassName(getString(args, "className"));
			String memberName = getOptionalString(args, "memberName", null);
			String memberDescriptor = getOptionalString(args, "memberDescriptor", null);
			int offset = getInt(args, "offset", 0);
			int limit = getInt(args, "limit", PaginationUtil.DEFAULT_LIMIT);

			Results results = executeReferenceSearch(workspace, className, memberName, memberDescriptor);
			List<Map<String, Object>> resultList = buildResultList(results);
			List<Map<String, Object>> page = PaginationUtil.paginate(resultList, offset, limit);

			return createJsonResult(PaginationUtil.paginatedResult(page, offset, limit, resultList.size()));
		});
	}

	// ---- xrefs-from ----

	private void registerXRefsFrom() {
		Tool tool = Tool.builder()
				.name("xrefs-from")
				.description("Find all outgoing references from a method (what classes/members does this method reference). " +
						"Requires bytecode analysis of the method body.")
				.inputSchema(createSchema(
						Map.of(
								"className", stringParam("Internal or dot-notation class name containing the method (required)"),
								"methodName", stringParam("Method name to analyze (required)"),
								"methodDescriptor", stringParam("Method descriptor (optional, helps disambiguate overloads)")
						),
						List.of("className", "methodName")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			requireWorkspace();
			getString(args, "className");
			getString(args, "methodName");
			return createTextResult(
					"xrefs-from requires bytecode analysis - not yet implemented. " +
					"Use 'xrefs-to' to find references to a target instead."
			);
		});
	}

	// ---- xrefs-count ----

	private void registerXRefsCount() {
		Tool tool = Tool.builder()
				.name("xrefs-count")
				.description("Count the number of references to a class or member without returning full details. " +
						"Faster alternative to xrefs-to when only the count is needed.")
				.inputSchema(createSchema(
						Map.of(
								"className", stringParam("Internal or dot-notation class name to count references to (required)"),
								"memberName", stringParam("Member name to count references to (optional)"),
								"memberDescriptor", stringParam("Member descriptor to count references to (optional)")
						),
						List.of("className")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();

			String className = ClassResolver.normalizeClassName(getString(args, "className"));
			String memberName = getOptionalString(args, "memberName", null);
			String memberDescriptor = getOptionalString(args, "memberDescriptor", null);

			Results results = executeReferenceSearch(workspace, className, memberName, memberDescriptor);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("className", className);
			if (memberName != null) result.put("memberName", memberName);
			if (memberDescriptor != null) result.put("memberDescriptor", memberDescriptor);
			result.put("referenceCount", results.size());
			return createJsonResult(result);
		});
	}

	// ---- Helpers ----

	/**
	 * Execute a reference search using the search service.
	 *
	 * @param workspace        The workspace to search in.
	 * @param className        The target class name (internal format).
	 * @param memberName       Optional member name.
	 * @param memberDescriptor Optional member descriptor.
	 * @return The search results.
	 */
	private Results executeReferenceSearch(Workspace workspace,
	                                       String className,
	                                       String memberName,
	                                       String memberDescriptor) {
		StringPredicateProvider predicateProvider = new StringPredicateProvider();
		StringPredicate classPredicate = predicateProvider.newEqualPredicate(className);

		ReferenceQuery query;
		if (memberName != null) {
			StringPredicate namePredicate = predicateProvider.newEqualPredicate(memberName);
			StringPredicate descPredicate = memberDescriptor != null
					? predicateProvider.newEqualPredicate(memberDescriptor)
					: predicateProvider.newAnythingPredicate();
			query = new ReferenceQuery(classPredicate, namePredicate, descPredicate);
		} else {
			query = new ReferenceQuery(classPredicate);
		}

		return searchService.search(workspace, query);
	}

	/**
	 * Convert search results into a serializable list of maps.
	 *
	 * @param results The search results.
	 * @return A list of maps describing each result location.
	 */
	private static List<Map<String, Object>> buildResultList(Results results) {
		List<Map<String, Object>> list = new ArrayList<>();
		for (Result<?> result : results) {
			LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
			PathNode<?> path = result.getPath();
			entry.put("location", path != null ? path.toString() : "unknown");
			list.add(entry);
		}
		return list;
	}
}
