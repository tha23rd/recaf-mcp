package dev.recafmcp.providers;

import dev.recafmcp.util.ClassResolver;
import dev.recafmcp.util.ErrorHelper;
import dev.recafmcp.util.PaginationUtil;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.callgraph.CallGraph;
import software.coley.recaf.services.callgraph.CallGraphService;
import software.coley.recaf.services.callgraph.ClassMethodsContainer;
import software.coley.recaf.services.callgraph.MethodRef;
import software.coley.recaf.services.callgraph.MethodVertex;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool provider for call graph analysis.
 * <p>
 * Provides tools for querying caller/callee relationships between methods
 * using Recaf's {@link CallGraphService}.
 */
public class CallGraphToolProvider extends AbstractToolProvider {
	private static final Logger logger = Logging.get(CallGraphToolProvider.class);

	private final CallGraphService callGraphService;

	public CallGraphToolProvider(McpSyncServer server,
	                             WorkspaceManager workspaceManager,
	                             CallGraphService callGraphService) {
		super(server, workspaceManager);
		this.callGraphService = callGraphService;
	}

	@Override
	public void registerTools() {
		registerCallGraphCallers();
		registerCallGraphCallees();
		registerCallGraphPath();
		registerCallGraphBuild();
	}

	// ---- callgraph-callers ----

	private void registerCallGraphCallers() {
		Tool tool = Tool.builder()
				.name("callgraph-callers")
				.description("Find all methods that call the specified method. " +
						"Requires the call graph to be built first (use callgraph-build).")
				.inputSchema(createSchema(
						Map.of(
								"className", stringParam("Internal or dot-notation class name containing the method (required)"),
								"methodName", stringParam("Name of the method to find callers of (required)"),
								"methodDescriptor", stringParam("Method descriptor, e.g. '(I)V' (required)")
						),
						List.of("className", "methodName", "methodDescriptor")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();

			String className = ClassResolver.normalizeClassName(getString(args, "className"));
			String methodName = getString(args, "methodName");
			String methodDescriptor = getString(args, "methodDescriptor");

			MethodVertex vertex = resolveMethodVertex(workspace, className, methodName, methodDescriptor);
			if (vertex == null) {
				return createErrorResult("Could not resolve method vertex for " +
						className + "." + methodName + methodDescriptor +
						". Ensure the call graph is built (use callgraph-build) and the method exists.");
			}

			Collection<MethodVertex> callers = vertex.getCallers();
			List<Map<String, Object>> callerList = new ArrayList<>();
			for (MethodVertex caller : callers) {
				callerList.add(methodVertexToMap(caller));
			}

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("target", className + "." + methodName + methodDescriptor);
			result.put("callerCount", callerList.size());
			result.put("callers", callerList);
			return createJsonResult(result);
		});
	}

	// ---- callgraph-callees ----

	private void registerCallGraphCallees() {
		Tool tool = Tool.builder()
				.name("callgraph-callees")
				.description("Find all methods called by the specified method. " +
						"Requires the call graph to be built first (use callgraph-build).")
				.inputSchema(createSchema(
						Map.of(
								"className", stringParam("Internal or dot-notation class name containing the method (required)"),
								"methodName", stringParam("Name of the method to find callees of (required)"),
								"methodDescriptor", stringParam("Method descriptor, e.g. '(Ljava/lang/String;)V' (required)")
						),
						List.of("className", "methodName", "methodDescriptor")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();

			String className = ClassResolver.normalizeClassName(getString(args, "className"));
			String methodName = getString(args, "methodName");
			String methodDescriptor = getString(args, "methodDescriptor");

			MethodVertex vertex = resolveMethodVertex(workspace, className, methodName, methodDescriptor);
			if (vertex == null) {
				return createErrorResult("Could not resolve method vertex for " +
						className + "." + methodName + methodDescriptor +
						". Ensure the call graph is built (use callgraph-build) and the method exists.");
			}

			Collection<MethodVertex> callees = vertex.getCalls();
			List<Map<String, Object>> calleeList = new ArrayList<>();
			for (MethodVertex callee : callees) {
				calleeList.add(methodVertexToMap(callee));
			}

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("source", className + "." + methodName + methodDescriptor);
			result.put("calleeCount", calleeList.size());
			result.put("callees", calleeList);
			return createJsonResult(result);
		});
	}

	// ---- callgraph-path ----

	private void registerCallGraphPath() {
		Tool tool = Tool.builder()
				.name("callgraph-path")
				.description("Find a call path between two methods (from a source method to a target method). " +
						"Useful for understanding how control flow reaches a specific method.")
				.inputSchema(createSchema(
						Map.of(
								"fromClass", stringParam("Class containing the source method (required)"),
								"fromMethod", stringParam("Source method name (required)"),
								"toClass", stringParam("Class containing the target method (required)"),
								"toMethod", stringParam("Target method name (required)")
						),
						List.of("fromClass", "fromMethod", "toClass", "toMethod")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			requireWorkspace();
			getString(args, "fromClass");
			getString(args, "fromMethod");
			getString(args, "toClass");
			getString(args, "toMethod");
			return createTextResult(
					"Call graph path finding is not yet implemented. " +
					"Use 'callgraph-callers' and 'callgraph-callees' to manually trace call chains."
			);
		});
	}

	// ---- callgraph-build ----

	private void registerCallGraphBuild() {
		Tool tool = Tool.builder()
				.name("callgraph-build")
				.description("Trigger construction of the call graph for the current workspace. " +
						"This must be done before using callgraph-callers or callgraph-callees. " +
						"May take a moment for large workspaces.")
				.inputSchema(createSchema(Map.of(), List.of()))
				.build();
		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();

			CallGraph callGraph = callGraphService.getCurrentWorkspaceCallGraph();
			if (callGraph == null) {
				callGraph = callGraphService.newCallGraph(workspace);
			}

			if (!callGraph.isInitialized()) {
				callGraph.initialize();
			}

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "initialized");
			result.put("ready", callGraph.isReady().getValue());
			return createJsonResult(result);
		});
	}

	// ---- Helpers ----

	/**
	 * Resolve a method to its {@link MethodVertex} in the call graph.
	 *
	 * @param workspace        The current workspace.
	 * @param className        The class name (internal format).
	 * @param methodName       The method name.
	 * @param methodDescriptor The method descriptor.
	 * @return The method vertex, or {@code null} if not found.
	 */
	private MethodVertex resolveMethodVertex(Workspace workspace,
	                                         String className,
	                                         String methodName,
	                                         String methodDescriptor) {
		CallGraph callGraph = callGraphService.getCurrentWorkspaceCallGraph();
		if (callGraph == null) {
			return null;
		}

		ClassPathNode classPath = ClassResolver.resolveClass(workspace, className);
		if (classPath == null) {
			return null;
		}

		ClassInfo classInfo = classPath.getValue();
		if (!classInfo.isJvmClass()) {
			return null;
		}

		JvmClassInfo jvmClass = classInfo.asJvmClass();
		try {
			ClassMethodsContainer container = callGraph.getClassMethodsContainer(jvmClass);
			if (container == null) {
				return null;
			}
			return container.getVertex(methodName, methodDescriptor);
		} catch (Exception e) {
			logger.debug("Failed to resolve method vertex for {}.{}{}", className, methodName, methodDescriptor, e);
			return null;
		}
	}

	/**
	 * Convert a {@link MethodVertex} to a serializable map.
	 *
	 * @param vertex The method vertex.
	 * @return A map with owner, name, and descriptor fields.
	 */
	private static Map<String, Object> methodVertexToMap(MethodVertex vertex) {
		LinkedHashMap<String, Object> map = new LinkedHashMap<>();
		MethodRef ref = vertex.getMethod();
		map.put("owner", ref.owner());
		map.put("name", ref.name());
		map.put("descriptor", ref.desc());
		return map;
	}
}
