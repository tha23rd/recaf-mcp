package dev.recafmcp.providers;

import dev.recafmcp.util.ClassResolver;
import dev.recafmcp.util.ErrorHelper;
import dev.recafmcp.util.PaginationUtil;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Provides MCP tools for navigating classes, methods, fields, and packages
 * within a Recaf workspace.
 */
public class NavigationToolProvider extends AbstractToolProvider {

	public NavigationToolProvider(McpSyncServer server, WorkspaceManager workspaceManager) {
		super(server, workspaceManager);
	}

	@Override
	public void registerTools() {
		registerClassList();
		registerClassCount();
		registerClassGetInfo();
		registerClassGetHierarchy();
		registerMethodList();
		registerFieldList();
		registerPackageList();
		registerClassSearchByName();
	}

	/**
	 * Build a {@link Tool} with name, description, and input schema.
	 * Uses the builder to avoid specifying all record fields explicitly.
	 */
	private static Tool buildTool(String name, String description, JsonSchema inputSchema) {
		return Tool.builder()
				.name(name)
				.description(description)
				.inputSchema(inputSchema)
				.build();
	}

	// ---- class-list ----

	private void registerClassList() {
		Tool tool = buildTool(
				"class-list",
				"List JVM classes in the workspace, optionally filtered by package prefix. Returns paginated results with class name, super name, and access flags.",
				createSchema(Map.of(
						"packageFilter", stringParam("Optional package prefix to filter classes (e.g. 'com/example')"),
						"offset", intParam("Pagination offset (default 0)"),
						"limit", intParam("Maximum number of results (default 100)")
				), List.of())
		);

		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();
			String packageFilter = getOptionalString(args, "packageFilter", null);
			int offset = getInt(args, "offset", 0);
			int limit = getInt(args, "limit", 100);

			String normalizedFilter = packageFilter != null
					? ClassResolver.normalizeClassName(packageFilter)
					: null;

			List<Map<String, Object>> allClasses = workspace.jvmClassesStream()
					.map(cpn -> (JvmClassInfo) cpn.getValue())
					.filter(cls -> normalizedFilter == null || cls.getName().startsWith(normalizedFilter))
					.sorted((a, b) -> a.getName().compareTo(b.getName()))
					.map(cls -> {
						Map<String, Object> entry = new LinkedHashMap<>();
						entry.put("name", cls.getName());
						entry.put("superName", cls.getSuperName());
						entry.put("access", cls.getAccess());
						return entry;
					})
					.collect(Collectors.toList());

			List<Map<String, Object>> page = PaginationUtil.paginate(allClasses, offset, limit);
			Map<String, Object> result = PaginationUtil.paginatedResult(page, offset, limit, allClasses.size());
			return createJsonResult(result);
		});
	}

	// ---- class-count ----

	private void registerClassCount() {
		Tool tool = buildTool(
				"class-count",
				"Count the number of JVM classes in the workspace, optionally filtered by package prefix.",
				createSchema(Map.of(
						"packageFilter", stringParam("Optional package prefix to filter classes")
				), List.of())
		);

		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();
			String packageFilter = getOptionalString(args, "packageFilter", null);

			String normalizedFilter = packageFilter != null
					? ClassResolver.normalizeClassName(packageFilter)
					: null;

			long count = workspace.jvmClassesStream()
					.map(cpn -> (JvmClassInfo) cpn.getValue())
					.filter(cls -> normalizedFilter == null || cls.getName().startsWith(normalizedFilter))
					.count();

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("count", count);
			if (normalizedFilter != null) {
				result.put("packageFilter", normalizedFilter);
			}
			return createJsonResult(result);
		});
	}

	// ---- class-get-info ----

	private void registerClassGetInfo() {
		Tool tool = buildTool(
				"class-get-info",
				"Get detailed information about a specific class including name, access flags, super class, interfaces, fields, and methods.",
				createSchema(Map.of(
						"className", stringParam("Fully qualified class name (dot or slash notation)")
				), List.of("className"))
		);

		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();
			String className = getString(args, "className");
			String normalized = ClassResolver.normalizeClassName(className);

			ClassPathNode node = ClassResolver.resolveClass(workspace, normalized);
			if (node == null) {
				return createErrorResult(ErrorHelper.classNotFound(normalized, workspace));
			}

			ClassInfo classInfo = node.getValue();

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("name", classInfo.getName());
			result.put("access", classInfo.getAccess());
			result.put("superName", classInfo.getSuperName());
			result.put("interfaces", classInfo.getInterfaces());
			result.put("sourceFileName", classInfo.getSourceFileName());

			// Fields
			List<Map<String, Object>> fields = classInfo.getFields().stream()
					.map(field -> {
						Map<String, Object> f = new LinkedHashMap<>();
						f.put("name", field.getName());
						f.put("descriptor", field.getDescriptor());
						f.put("access", field.getAccess());
						return f;
					})
					.collect(Collectors.toList());
			result.put("fields", fields);

			// Methods
			List<Map<String, Object>> methods = classInfo.getMethods().stream()
					.map(method -> {
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("name", method.getName());
						m.put("descriptor", method.getDescriptor());
						m.put("access", method.getAccess());
						return m;
					})
					.collect(Collectors.toList());
			result.put("methods", methods);

			return createJsonResult(result);
		});
	}

	// ---- class-get-hierarchy ----

	private void registerClassGetHierarchy() {
		Tool tool = buildTool(
				"class-get-hierarchy",
				"Walk the superclass chain of a class up to java/lang/Object. Returns an ordered list of ancestor class names.",
				createSchema(Map.of(
						"className", stringParam("Fully qualified class name (dot or slash notation)")
				), List.of("className"))
		);

		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();
			String className = getString(args, "className");
			String normalized = ClassResolver.normalizeClassName(className);

			ClassPathNode node = ClassResolver.resolveClass(workspace, normalized);
			if (node == null) {
				return createErrorResult(ErrorHelper.classNotFound(normalized, workspace));
			}

			List<String> hierarchy = new ArrayList<>();
			hierarchy.add(node.getValue().getName());

			String superName = node.getValue().getSuperName();
			while (superName != null && !superName.isEmpty()) {
				hierarchy.add(superName);
				if ("java/lang/Object".equals(superName)) {
					break;
				}
				// Try to resolve the superclass within the workspace
				ClassPathNode superNode = workspace.findJvmClass(superName);
				if (superNode == null) {
					// Superclass not in workspace; chain ends here
					break;
				}
				superName = superNode.getValue().getSuperName();
			}

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("className", normalized);
			result.put("hierarchy", hierarchy);
			return createJsonResult(result);
		});
	}

	// ---- method-list ----

	private void registerMethodList() {
		Tool tool = buildTool(
				"method-list",
				"List methods of a class with name, descriptor, and access flags. Supports pagination.",
				createSchema(Map.of(
						"className", stringParam("Fully qualified class name (dot or slash notation)"),
						"offset", intParam("Pagination offset (default 0)"),
						"limit", intParam("Maximum number of results (default 100)")
				), List.of("className"))
		);

		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();
			String className = getString(args, "className");
			int offset = getInt(args, "offset", 0);
			int limit = getInt(args, "limit", 100);

			String normalized = ClassResolver.normalizeClassName(className);
			ClassPathNode node = ClassResolver.resolveClass(workspace, normalized);
			if (node == null) {
				return createErrorResult(ErrorHelper.classNotFound(normalized, workspace));
			}

			List<MethodMember> allMethods = node.getValue().getMethods();
			List<Map<String, Object>> methodMaps = allMethods.stream()
					.map(method -> {
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("name", method.getName());
						m.put("descriptor", method.getDescriptor());
						m.put("access", method.getAccess());
						return m;
					})
					.collect(Collectors.toList());

			List<Map<String, Object>> page = PaginationUtil.paginate(methodMaps, offset, limit);
			Map<String, Object> result = PaginationUtil.paginatedResult(page, offset, limit, methodMaps.size());
			return createJsonResult(result);
		});
	}

	// ---- field-list ----

	private void registerFieldList() {
		Tool tool = buildTool(
				"field-list",
				"List fields of a class with name, descriptor, and access flags. Supports pagination.",
				createSchema(Map.of(
						"className", stringParam("Fully qualified class name (dot or slash notation)"),
						"offset", intParam("Pagination offset (default 0)"),
						"limit", intParam("Maximum number of results (default 100)")
				), List.of("className"))
		);

		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();
			String className = getString(args, "className");
			int offset = getInt(args, "offset", 0);
			int limit = getInt(args, "limit", 100);

			String normalized = ClassResolver.normalizeClassName(className);
			ClassPathNode node = ClassResolver.resolveClass(workspace, normalized);
			if (node == null) {
				return createErrorResult(ErrorHelper.classNotFound(normalized, workspace));
			}

			List<FieldMember> allFields = node.getValue().getFields();
			List<Map<String, Object>> fieldMaps = allFields.stream()
					.map(field -> {
						Map<String, Object> f = new LinkedHashMap<>();
						f.put("name", field.getName());
						f.put("descriptor", field.getDescriptor());
						f.put("access", field.getAccess());
						return f;
					})
					.collect(Collectors.toList());

			List<Map<String, Object>> page = PaginationUtil.paginate(fieldMaps, offset, limit);
			Map<String, Object> result = PaginationUtil.paginatedResult(page, offset, limit, fieldMaps.size());
			return createJsonResult(result);
		});
	}

	// ---- package-list ----

	private void registerPackageList() {
		Tool tool = buildTool(
				"package-list",
				"List all unique package names found across JVM classes in the workspace.",
				createSchema(Map.of(), List.of())
		);

		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();

			TreeSet<String> packages = workspace.jvmClassesStream()
					.map(cpn -> cpn.getValue().getName())
					.map(name -> {
						int lastSlash = name.lastIndexOf('/');
						return lastSlash > 0 ? name.substring(0, lastSlash) : "";
					})
					.collect(Collectors.toCollection(TreeSet::new));

			// Remove the default (empty) package entry if present, then re-add as explicit label
			boolean hasDefaultPackage = packages.remove("");

			List<String> packageList = new ArrayList<>(packages);
			if (hasDefaultPackage) {
				packageList.addFirst("(default)");
			}

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("packages", packageList);
			result.put("count", packageList.size());
			return createJsonResult(result);
		});
	}

	// ---- class-search-by-name ----

	private void registerClassSearchByName() {
		Tool tool = buildTool(
				"class-search-by-name",
				"Search for classes by name using a regular expression pattern. Returns paginated results.",
				createSchema(Map.of(
						"pattern", stringParam("Regular expression pattern to match against class names"),
						"offset", intParam("Pagination offset (default 0)"),
						"limit", intParam("Maximum number of results (default 100)")
				), List.of("pattern"))
		);

		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();
			String patternStr = getString(args, "pattern");
			int offset = getInt(args, "offset", 0);
			int limit = getInt(args, "limit", 100);

			Pattern regex;
			try {
				regex = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
			} catch (PatternSyntaxException e) {
				return createErrorResult("Invalid regex pattern: " + e.getMessage());
			}

			List<Map<String, Object>> matches = workspace.jvmClassesStream()
					.map(cpn -> (JvmClassInfo) cpn.getValue())
					.filter(cls -> regex.matcher(cls.getName()).find())
					.sorted((a, b) -> a.getName().compareTo(b.getName()))
					.map(cls -> {
						Map<String, Object> entry = new LinkedHashMap<>();
						entry.put("name", cls.getName());
						entry.put("superName", cls.getSuperName());
						entry.put("access", cls.getAccess());
						return entry;
					})
					.collect(Collectors.toList());

			List<Map<String, Object>> page = PaginationUtil.paginate(matches, offset, limit);
			Map<String, Object> result = PaginationUtil.paginatedResult(page, offset, limit, matches.size());
			return createJsonResult(result);
		});
	}
}
