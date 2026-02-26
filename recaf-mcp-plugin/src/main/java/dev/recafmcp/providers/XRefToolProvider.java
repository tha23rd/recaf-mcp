package dev.recafmcp.providers;

import dev.recafmcp.cache.InstructionAnalysisCache;
import dev.recafmcp.cache.SearchQueryCache;
import dev.recafmcp.cache.WorkspaceRevisionTracker;
import dev.recafmcp.util.ClassResolver;
import dev.recafmcp.util.ErrorHelper;
import dev.recafmcp.util.PaginationUtil;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.InstructionPathNode;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP tool provider for cross-reference (xref) analysis.
 * <p>
 * Provides tools for finding references to classes and members within the
 * current workspace using Recaf's {@link SearchService}.
 */
public class XRefToolProvider extends AbstractToolProvider {
	private static final Logger logger = Logging.get(XRefToolProvider.class);

	private final SearchService searchService;
	private final InstructionAnalysisCache instructionAnalysisCache;
	private final SearchQueryCache searchQueryCache;
	private final WorkspaceRevisionTracker revisionTracker;

	public XRefToolProvider(McpSyncServer server,
	                        WorkspaceManager workspaceManager,
	                        SearchService searchService,
	                        InstructionAnalysisCache instructionAnalysisCache,
	                        SearchQueryCache searchQueryCache,
	                        WorkspaceRevisionTracker revisionTracker) {
		super(server, workspaceManager);
		this.searchService = searchService;
		this.instructionAnalysisCache = instructionAnalysisCache;
		this.searchQueryCache = searchQueryCache;
		this.revisionTracker = revisionTracker;
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

			List<Map<String, Object>> resultList = getCachedReferenceResults(
					workspace, className, memberName, memberDescriptor
			);
			List<Map<String, Object>> page = PaginationUtil.paginate(resultList, offset, limit);

			return createJsonResult(PaginationUtil.paginatedResult(page, offset, limit, resultList.size()));
		});
	}

	// ---- xrefs-from ----

	private void registerXRefsFrom() {
		Tool tool = Tool.builder()
				.name("xrefs-from")
				.description("Find all outgoing references from a class or method. Analyzes bytecode " +
						"to discover what classes, methods, and fields are referenced. " +
						"If methodName is provided, only that method is analyzed; otherwise all methods " +
						"in the class are analyzed.")
				.inputSchema(createSchema(
						Map.of(
								"className", stringParam("Internal or dot-notation class name to analyze (required)"),
								"methodName", stringParam("Method name to analyze (optional, analyzes all methods if omitted)"),
								"methodDescriptor", stringParam("Method descriptor to disambiguate overloads (optional)")
						),
						List.of("className")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();

			String className = ClassResolver.normalizeClassName(getString(args, "className"));
			String methodName = getOptionalString(args, "methodName", null);
			String methodDescriptor = getOptionalString(args, "methodDescriptor", null);

			// Resolve the class
			ClassPathNode pathNode = ClassResolver.resolveClass(workspace, className);
			if (pathNode == null) {
				return createErrorResult(ErrorHelper.classNotFound(className, workspace));
			}

			ClassInfo classInfo = pathNode.getValue();
			if (!classInfo.isJvmClass()) {
				return createErrorResult("Class '" + className + "' is not a JVM class. " +
						"xrefs-from only supports JVM bytecode analysis.");
			}

			JvmClassInfo jvmClass = classInfo.asJvmClass();
			String resolvedClassName = classInfo.getName();

			InstructionAnalysisCache.ClassAnalysis analysis = getInstructionAnalysis(workspace, jvmClass);

			// Collect outgoing references
			List<Map<String, Object>> methodRefs = new ArrayList<>();
			List<Map<String, Object>> fieldRefs = new ArrayList<>();
			Set<String> typeRefs = new LinkedHashSet<>();

			for (InstructionAnalysisCache.MethodAnalysis method : analysis.methods()) {
				// Filter to specific method if requested
				if (methodName != null && !method.methodName().equals(methodName)) continue;
				if (methodDescriptor != null && !method.methodDescriptor().equals(methodDescriptor)) continue;

				for (InstructionAnalysisCache.MethodReference reference : method.methodReferences()) {
					methodRefs.add(toMethodReferenceMap(reference));
				}
				for (InstructionAnalysisCache.FieldReference reference : method.fieldReferences()) {
					fieldRefs.add(toFieldReferenceMap(reference));
				}
				typeRefs.addAll(method.typeReferences());
			}

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("className", resolvedClassName);
			if (methodName != null) {
				result.put("methodFilter", methodName + (methodDescriptor != null ? methodDescriptor : ""));
			}
			result.put("methodReferences", methodRefs);
			result.put("fieldReferences", fieldRefs);
			result.put("referencedTypes", typeRefs);
			result.put("totalMethodRefs", methodRefs.size());
			result.put("totalFieldRefs", fieldRefs.size());
			result.put("totalTypeRefs", typeRefs.size());
			return createJsonResult(result);
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

			List<Map<String, Object>> results = getCachedReferenceResults(
					workspace, className, memberName, memberDescriptor
			);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("className", className);
			if (memberName != null) result.put("memberName", memberName);
			if (memberDescriptor != null) result.put("memberDescriptor", memberDescriptor);
			result.put("referenceCount", results.size());
			return createJsonResult(result);
		});
	}

	// ---- Helpers ----

	private List<Map<String, Object>> getCachedReferenceResults(Workspace workspace,
	                                                            String className,
	                                                            String memberName,
	                                                            String memberDescriptor) {
		String normalizedQuery = "className=" + className +
				"|memberName=" + cacheKeyPart(memberName) +
				"|memberDescriptor=" + cacheKeyPart(memberDescriptor);
		long revision = revisionTracker.getRevision(workspace);
		SearchQueryCache.Key key = searchQueryCache.keyFor(workspace, revision, "xrefs-to", normalizedQuery);
		return searchQueryCache.getOrLoad(key, () -> {
			Results results = executeReferenceSearch(workspace, className, memberName, memberDescriptor);
			return buildResultList(results);
		});
	}

	private static String cacheKeyPart(String value) {
		return value == null ? "<null>" : value;
	}

	private InstructionAnalysisCache.ClassAnalysis getInstructionAnalysis(Workspace workspace, JvmClassInfo classInfo) {
		long revision = revisionTracker.getRevision(workspace);
		InstructionAnalysisCache.Key key = instructionAnalysisCache.keyFor(workspace, revision, classInfo);
		return instructionAnalysisCache.getOrLoad(key, () -> InstructionAnalysisCache.analyzeClass(classInfo));
	}

	private static Map<String, Object> toMethodReferenceMap(InstructionAnalysisCache.MethodReference reference) {
		LinkedHashMap<String, Object> ref = new LinkedHashMap<>();
		ref.put("fromMethod", reference.fromMethod());
		ref.put("type", reference.type());
		if ("invokedynamic".equals(reference.type())) {
			ref.put("bootstrapOwner", reference.bootstrapOwner());
			ref.put("bootstrapName", reference.bootstrapName());
			ref.put("bootstrapDescriptor", reference.bootstrapDescriptor());
			ref.put("callName", reference.callName());
			ref.put("callDescriptor", reference.callDescriptor());
			if (!reference.bootstrapArgs().isEmpty()) {
				ref.put("bootstrapArgs", reference.bootstrapArgs());
			}
		} else {
			ref.put("targetOwner", reference.targetOwner());
			ref.put("targetName", reference.targetName());
			ref.put("targetDescriptor", reference.targetDescriptor());
		}
		return ref;
	}

	private static Map<String, Object> toFieldReferenceMap(InstructionAnalysisCache.FieldReference reference) {
		LinkedHashMap<String, Object> ref = new LinkedHashMap<>();
		ref.put("fromMethod", reference.fromMethod());
		ref.put("targetOwner", reference.targetOwner());
		ref.put("targetName", reference.targetName());
		ref.put("targetDescriptor", reference.targetDescriptor());
		ref.put("type", "field");
		return ref;
	}

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
	 * Convert search results into a serializable list of maps with structured location info.
	 * <p>
	 * Walks each result's {@link PathNode} chain to extract the containing class,
	 * method (name + descriptor), and instruction index rather than returning
	 * opaque {@code toString()} output.
	 *
	 * @param results The search results.
	 * @return A list of maps describing each result location.
	 */
	private static List<Map<String, Object>> buildResultList(Results results) {
		List<Map<String, Object>> list = new ArrayList<>();
		for (Result<?> result : results) {
			LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
			PathNode<?> path = result.getPath();

			if (path != null) {
				// Extract containing class
				ClassInfo classInfo = path.getValueOfType(ClassInfo.class);
				if (classInfo != null) {
					entry.put("className", classInfo.getName());
				}

				// Extract containing method (name + descriptor)
				ClassMember member = path.getValueOfType(ClassMember.class);
				if (member != null) {
					entry.put("memberName", member.getName());
					entry.put("memberDescriptor", member.getDescriptor());
				}

				// Extract instruction index if available
				InstructionPathNode instrNode = path.getPathOfType(AbstractInsnNode.class);
				if (instrNode != null) {
					entry.put("instructionIndex", instrNode.getInstructionIndex());
				}
			}

			// Fallback: if we couldn't extract structured data, include raw location
			if (entry.isEmpty()) {
				entry.put("location", path != null ? path.toString() : "unknown");
			}

			list.add(entry);
		}
		return list;
	}
}
