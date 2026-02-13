package dev.recafmcp.providers;

import dev.recafmcp.util.ClassResolver;
import dev.recafmcp.util.ErrorHelper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.inheritance.InheritanceVertex;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP tool provider for class inheritance analysis.
 * <p>
 * Provides tools for querying the inheritance hierarchy including subtypes,
 * supertypes, and common ancestors using Recaf's {@link InheritanceGraphService}.
 */
public class InheritanceToolProvider extends AbstractToolProvider {
	private static final Logger logger = Logging.get(InheritanceToolProvider.class);

	private final InheritanceGraphService inheritanceGraphService;

	public InheritanceToolProvider(McpSyncServer server,
	                               WorkspaceManager workspaceManager,
	                               InheritanceGraphService inheritanceGraphService) {
		super(server, workspaceManager);
		this.inheritanceGraphService = inheritanceGraphService;
	}

	@Override
	public void registerTools() {
		registerInheritanceSubtypes();
		registerInheritanceSupertypes();
		registerInheritanceCommonParent();
	}

	// ---- inheritance-subtypes ----

	private void registerInheritanceSubtypes() {
		Tool tool = Tool.builder()
				.name("inheritance-subtypes")
				.description("Find all subtypes (direct and transitive) of a class or interface. " +
						"Useful for finding implementations of interfaces or subclasses.")
				.inputSchema(createSchema(
						Map.of("className", stringParam("Internal or dot-notation class name to find subtypes of (required)")),
						List.of("className")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();
			String className = ClassResolver.normalizeClassName(getString(args, "className"));

			InheritanceGraph graph = getInheritanceGraph(workspace);
			InheritanceVertex vertex = graph.getVertex(className);
			if (vertex == null) {
				return createErrorResult(ErrorHelper.classNotFound(className, workspace));
			}

			Set<InheritanceVertex> children = vertex.getAllChildren();
			List<Map<String, Object>> subtypeList = new ArrayList<>();
			for (InheritanceVertex child : children) {
				subtypeList.add(vertexToMap(child));
			}

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("className", className);
			result.put("subtypeCount", subtypeList.size());
			result.put("subtypes", subtypeList);
			return createJsonResult(result);
		});
	}

	// ---- inheritance-supertypes ----

	private void registerInheritanceSupertypes() {
		Tool tool = Tool.builder()
				.name("inheritance-supertypes")
				.description("Walk the superclass and interface chain for a class, returning all ancestors. " +
						"Includes both direct parents and transitive supertypes.")
				.inputSchema(createSchema(
						Map.of("className", stringParam("Internal or dot-notation class name to find supertypes of (required)")),
						List.of("className")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();
			String className = ClassResolver.normalizeClassName(getString(args, "className"));

			InheritanceGraph graph = getInheritanceGraph(workspace);
			InheritanceVertex vertex = graph.getVertex(className);
			if (vertex == null) {
				return createErrorResult(ErrorHelper.classNotFound(className, workspace));
			}

			Set<InheritanceVertex> parents = vertex.getAllParents();
			List<Map<String, Object>> supertypeList = new ArrayList<>();
			for (InheritanceVertex parent : parents) {
				supertypeList.add(vertexToMap(parent));
			}

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("className", className);
			result.put("supertypeCount", supertypeList.size());
			result.put("supertypes", supertypeList);
			return createJsonResult(result);
		});
	}

	// ---- inheritance-common-parent ----

	private void registerInheritanceCommonParent() {
		Tool tool = Tool.builder()
				.name("inheritance-common-parent")
				.description("Find the lowest common ancestor (most specific shared supertype) of two classes. " +
						"Useful for understanding type compatibility.")
				.inputSchema(createSchema(
						Map.of(
								"classA", stringParam("First class name (required)"),
								"classB", stringParam("Second class name (required)")
						),
						List.of("classA", "classB")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();
			String classA = ClassResolver.normalizeClassName(getString(args, "classA"));
			String classB = ClassResolver.normalizeClassName(getString(args, "classB"));

			InheritanceGraph graph = getInheritanceGraph(workspace);

			// Verify both classes exist in the graph
			InheritanceVertex vertexA = graph.getVertex(classA);
			if (vertexA == null) {
				return createErrorResult(ErrorHelper.classNotFound(classA, workspace));
			}
			InheritanceVertex vertexB = graph.getVertex(classB);
			if (vertexB == null) {
				return createErrorResult(ErrorHelper.classNotFound(classB, workspace));
			}

			String common = graph.getCommon(classA, classB);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("classA", classA);
			result.put("classB", classB);
			result.put("commonParent", common != null ? common : "java/lang/Object");
			return createJsonResult(result);
		});
	}

	// ---- Helpers ----

	/**
	 * Get or create the inheritance graph for the current workspace.
	 *
	 * @param workspace The current workspace.
	 * @return The inheritance graph.
	 */
	private InheritanceGraph getInheritanceGraph(Workspace workspace) {
		InheritanceGraph graph = inheritanceGraphService.getCurrentWorkspaceInheritanceGraph();
		if (graph == null) {
			graph = inheritanceGraphService.getOrCreateInheritanceGraph(workspace);
		}
		return graph;
	}

	/**
	 * Convert an {@link InheritanceVertex} to a serializable map.
	 *
	 * @param vertex The inheritance vertex.
	 * @return A map with class name and library status.
	 */
	private static Map<String, Object> vertexToMap(InheritanceVertex vertex) {
		LinkedHashMap<String, Object> map = new LinkedHashMap<>();
		map.put("className", vertex.getName());
		map.put("isLibrary", vertex.isLibraryVertex());
		return map;
	}
}
