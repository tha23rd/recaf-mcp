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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
				.description("Find a call path between two methods using BFS on the call graph. " +
						"Returns the shortest chain of method calls from source to target. " +
						"Requires the call graph to be built first (use callgraph-build).")
				.inputSchema(createSchema(
						Map.of(
								"sourceClass", stringParam("Class containing the source method (dot or slash notation)"),
								"sourceMethod", stringParam("Source method name"),
								"sourceDescriptor", stringParam("Source method descriptor (e.g. '(I)V')"),
								"targetClass", stringParam("Class containing the target method (dot or slash notation)"),
								"targetMethod", stringParam("Target method name"),
								"targetDescriptor", stringParam("Target method descriptor (e.g. '()V')"),
								"maxDepth", intParam("Maximum search depth (default: 20)")
						),
						List.of("sourceClass", "sourceMethod", "sourceDescriptor",
								"targetClass", "targetMethod", "targetDescriptor")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();

			String sourceClass = ClassResolver.normalizeClassName(getString(args, "sourceClass"));
			String sourceMethod = getString(args, "sourceMethod");
			String sourceDescriptor = getString(args, "sourceDescriptor");
			String targetClass = ClassResolver.normalizeClassName(getString(args, "targetClass"));
			String targetMethod = getString(args, "targetMethod");
			String targetDescriptor = getString(args, "targetDescriptor");
			int maxDepth = getInt(args, "maxDepth", 20);
			maxDepth = Math.clamp(maxDepth, 1, 100);

			// Resolve source vertex
			MethodVertex sourceVertex = resolveMethodVertex(workspace, sourceClass, sourceMethod, sourceDescriptor);
			if (sourceVertex == null) {
				return createErrorResult("Could not resolve source method: " +
						sourceClass + "." + sourceMethod + sourceDescriptor +
						". Ensure the call graph is built (use callgraph-build) and the method exists.");
			}

			// Resolve target vertex
			MethodVertex targetVertex = resolveMethodVertex(workspace, targetClass, targetMethod, targetDescriptor);
			if (targetVertex == null) {
				return createErrorResult("Could not resolve target method: " +
						targetClass + "." + targetMethod + targetDescriptor +
						". Ensure the call graph is built (use callgraph-build) and the method exists.");
			}

			// BFS from source, following callees, to find target
			List<MethodVertex> path = bfsCallPath(sourceVertex, targetVertex, maxDepth);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("source", sourceClass + "." + sourceMethod + sourceDescriptor);
			result.put("target", targetClass + "." + targetMethod + targetDescriptor);

			if (path == null) {
				result.put("found", false);
				result.put("message", "No call path found within " + maxDepth + " hops. " +
						"The methods may not be connected, or the path may exceed the max depth.");
			} else {
				result.put("found", true);
				result.put("pathLength", path.size());
				List<Map<String, Object>> pathList = new ArrayList<>();
				for (MethodVertex v : path) {
					pathList.add(methodVertexToMap(v));
				}
				result.put("path", pathList);
			}
			return createJsonResult(result);
		});
	}

	/**
	 * BFS to find shortest call path from source to target through callee edges.
	 *
	 * @param source   The starting method vertex.
	 * @param target   The target method vertex.
	 * @param maxDepth Maximum number of hops to search.
	 * @return The path as a list of vertices (including source and target), or null if not found.
	 */
	private static List<MethodVertex> bfsCallPath(MethodVertex source, MethodVertex target, int maxDepth) {
		if (source.equals(target)) {
			return List.of(source);
		}

		Deque<MethodVertex> queue = new ArrayDeque<>();
		Map<MethodVertex, MethodVertex> predecessors = new HashMap<>();
		Set<MethodVertex> visited = new HashSet<>();

		queue.add(source);
		visited.add(source);
		predecessors.put(source, null);

		int depth = 0;
		int levelSize = queue.size();

		while (!queue.isEmpty() && depth < maxDepth) {
			MethodVertex current = queue.poll();
			levelSize--;

			for (MethodVertex callee : current.getCalls()) {
				if (visited.contains(callee)) continue;
				visited.add(callee);
				predecessors.put(callee, current);

				if (callee.equals(target)) {
					// Reconstruct path
					List<MethodVertex> path = new ArrayList<>();
					MethodVertex node = callee;
					while (node != null) {
						path.add(node);
						node = predecessors.get(node);
					}
					Collections.reverse(path);
					return path;
				}

				queue.add(callee);
			}

			if (levelSize == 0) {
				depth++;
				levelSize = queue.size();
			}
		}

		return null; // No path found
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
