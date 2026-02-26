package dev.recafmcp.providers;

import dev.recafmcp.cache.ClassInventoryCache;
import dev.recafmcp.cache.WorkspaceRevisionTracker;
import dev.recafmcp.util.ClassResolver;
import dev.recafmcp.util.PaginationUtil;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Provides MCP tools for navigating classes, methods, fields, and packages
 * within a Recaf workspace.
 */
public class NavigationToolProvider extends AbstractToolProvider {
	private static final int MAX_SUGGESTIONS = 5;
	private final ClassInventoryCache classInventoryCache;
	private final WorkspaceRevisionTracker revisionTracker;

	public NavigationToolProvider(McpSyncServer server,
	                              WorkspaceManager workspaceManager,
	                              ClassInventoryCache classInventoryCache,
	                              WorkspaceRevisionTracker revisionTracker) {
		super(server, workspaceManager);
		this.classInventoryCache = classInventoryCache;
		this.revisionTracker = revisionTracker;
	}

	@Override
	public void registerTools() {
		registerClassList();
		registerClassCount();
		registerClassGetInfo();
		registerClassGetHierarchy();
		registerMethodList();
		registerFieldList();
		registerFieldGetValue();
		registerPackageList();
		registerClassSearchByName();
		registerFieldGetAllConstants();
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

	private ClassInventoryCache.Inventory getInventory(Workspace workspace) {
		long revision = revisionTracker.getRevision(workspace);
		ClassInventoryCache.Key key = classInventoryCache.keyFor(workspace, revision);
		return classInventoryCache.getOrLoad(key, () -> ClassInventoryCache.buildInventory(workspace));
	}

	private static String classNotFoundWithSuggestions(String normalizedClassName,
	                                                   ClassInventoryCache.Inventory inventory) {
		StringBuilder sb = new StringBuilder();
		sb.append("Class '").append(normalizedClassName).append("' not found.");

		List<String> suggestions = ClassResolver.findSimilarClassNames(inventory, normalizedClassName, MAX_SUGGESTIONS);
		if (!suggestions.isEmpty()) {
			sb.append(" Did you mean one of these?\n");
			for (String suggestion : suggestions) {
				sb.append("  - ").append(suggestion).append('\n');
			}
		}

		return sb.toString();
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
			ClassInventoryCache.Inventory inventory = getInventory(workspace);
			String packageFilter = getOptionalString(args, "packageFilter", null);
			int offset = getInt(args, "offset", 0);
			int limit = getInt(args, "limit", 100);

			String normalizedFilter = packageFilter != null
					? ClassResolver.normalizeClassName(packageFilter)
					: null;

			List<Map<String, Object>> allClasses = inventory.jvmClassEntries().stream()
					.filter(cls -> normalizedFilter == null || cls.name().startsWith(normalizedFilter))
					.map(cls -> {
						Map<String, Object> entry = new LinkedHashMap<>();
						entry.put("name", cls.name());
						entry.put("superName", cls.superName());
						entry.put("access", cls.access());
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
			ClassInventoryCache.Inventory inventory = getInventory(workspace);
			String packageFilter = getOptionalString(args, "packageFilter", null);

			String normalizedFilter = packageFilter != null
					? ClassResolver.normalizeClassName(packageFilter)
					: null;

			long count = inventory.jvmClassEntries().stream()
					.filter(cls -> normalizedFilter == null || cls.name().startsWith(normalizedFilter))
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
				"Get detailed information about a specific class including name, access flags, super class, interfaces, fields, and methods. Fields with compile-time constants (ConstantValue attribute) include defaultValue and defaultValueType.",
				createSchema(Map.of(
						"className", stringParam("Fully qualified class name (dot or slash notation)")
				), List.of("className"))
		);

		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();
			ClassInventoryCache.Inventory inventory = getInventory(workspace);
			String className = getString(args, "className");
			String normalized = ClassResolver.normalizeClassName(className);

			ClassPathNode node = ClassResolver.resolveClass(workspace, normalized, inventory);
			if (node == null) {
				return createErrorResult(classNotFoundWithSuggestions(normalized, inventory));
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
						Object defaultValue = field.getDefaultValue();
						if (defaultValue != null) {
							f.put("defaultValue", defaultValue);
							f.put("defaultValueType", getValueTypeName(defaultValue));
						}
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
			ClassInventoryCache.Inventory inventory = getInventory(workspace);
			String className = getString(args, "className");
			String normalized = ClassResolver.normalizeClassName(className);

			ClassPathNode node = ClassResolver.resolveClass(workspace, normalized, inventory);
			if (node == null) {
				return createErrorResult(classNotFoundWithSuggestions(normalized, inventory));
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
			ClassInventoryCache.Inventory inventory = getInventory(workspace);
			String className = getString(args, "className");
			int offset = getInt(args, "offset", 0);
			int limit = getInt(args, "limit", 100);

			String normalized = ClassResolver.normalizeClassName(className);
			ClassPathNode node = ClassResolver.resolveClass(workspace, normalized, inventory);
			if (node == null) {
				return createErrorResult(classNotFoundWithSuggestions(normalized, inventory));
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
				"List fields of a class with name, descriptor, and access flags. Fields with compile-time constants (ConstantValue attribute) include defaultValue and defaultValueType. Supports pagination.",
				createSchema(Map.of(
						"className", stringParam("Fully qualified class name (dot or slash notation)"),
						"offset", intParam("Pagination offset (default 0)"),
						"limit", intParam("Maximum number of results (default 100)")
				), List.of("className"))
		);

		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();
			ClassInventoryCache.Inventory inventory = getInventory(workspace);
			String className = getString(args, "className");
			int offset = getInt(args, "offset", 0);
			int limit = getInt(args, "limit", 100);

			String normalized = ClassResolver.normalizeClassName(className);
			ClassPathNode node = ClassResolver.resolveClass(workspace, normalized, inventory);
			if (node == null) {
				return createErrorResult(classNotFoundWithSuggestions(normalized, inventory));
			}

			List<FieldMember> allFields = node.getValue().getFields();
			List<Map<String, Object>> fieldMaps = allFields.stream()
					.map(field -> {
						Map<String, Object> f = new LinkedHashMap<>();
						f.put("name", field.getName());
						f.put("descriptor", field.getDescriptor());
						f.put("access", field.getAccess());
						Object defaultValue = field.getDefaultValue();
						if (defaultValue != null) {
							f.put("defaultValue", defaultValue);
							f.put("defaultValueType", getValueTypeName(defaultValue));
						}
						return f;
					})
					.collect(Collectors.toList());

			List<Map<String, Object>> page = PaginationUtil.paginate(fieldMaps, offset, limit);
			Map<String, Object> result = PaginationUtil.paginatedResult(page, offset, limit, fieldMaps.size());
			return createJsonResult(result);
		});
	}

	// ---- field-get-value ----

	private void registerFieldGetValue() {
		Tool tool = buildTool(
				"field-get-value",
				"Get the compile-time constant value (ConstantValue attribute) of a specific field. " +
						"Returns the field's value, type, access flags, and static/final modifiers. " +
						"Useful for extracting configuration constants, version strings, magic numbers, and enum-like values.",
				createSchema(Map.of(
						"className", stringParam("Fully qualified class name (dot or slash notation)"),
						"fieldName", stringParam("Name of the field"),
						"fieldDescriptor", stringParam("Field descriptor for disambiguation (e.g. 'I', 'Ljava/lang/String;'). Optional if field name is unambiguous.")
				), List.of("className", "fieldName"))
		);

		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();
			ClassInventoryCache.Inventory inventory = getInventory(workspace);
			String className = getString(args, "className");
			String fieldName = getString(args, "fieldName");
			String fieldDescriptor = getOptionalString(args, "fieldDescriptor", null);

			String normalized = ClassResolver.normalizeClassName(className);
			ClassPathNode node = ClassResolver.resolveClass(workspace, normalized, inventory);
			if (node == null) {
				return createErrorResult(classNotFoundWithSuggestions(normalized, inventory));
			}

			ClassInfo classInfo = node.getValue();
			FieldMember field;
			if (fieldDescriptor != null) {
				field = classInfo.getDeclaredField(fieldName, fieldDescriptor);
			} else {
				field = classInfo.getFirstDeclaredFieldByName(fieldName);
			}

			if (field == null) {
				String msg = "Field '" + fieldName + "' not found in class '" + normalized + "'";
				if (fieldDescriptor != null) {
					msg += " with descriptor '" + fieldDescriptor + "'";
				}
				return createErrorResult(msg);
			}

			boolean isStatic = field.hasStaticModifier();
			boolean isFinal = field.hasFinalModifier();
			Object value = field.getDefaultValue();

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("className", normalized);
			result.put("fieldName", field.getName());
			result.put("fieldDescriptor", field.getDescriptor());
			result.put("access", field.getAccess());
			result.put("isStatic", isStatic);
			result.put("isFinal", isFinal);
			result.put("hasConstantValue", value != null);
			result.put("value", value);
			if (value != null) {
				result.put("valueType", getValueTypeName(value));
			} else {
				if (!isStatic) {
					result.put("note", "Instance fields do not have compile-time constant values. " +
							"The value is assigned at construction time.");
				} else if (!isFinal) {
					result.put("note", "Non-final static fields do not have compile-time constant values. " +
							"The value may be initialized in <clinit> (static initializer).");
				} else {
					result.put("note", "This static final field does not have a ConstantValue attribute. " +
							"Its value is likely computed in <clinit> (static initializer) rather than being a compile-time constant.");
				}
			}

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
			ClassInventoryCache.Inventory inventory = getInventory(workspace);

			List<String> packageList = inventory.packageDisplayNames();

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("packages", packageList);
			result.put("count", packageList.size());
			return createJsonResult(result);
		});
	}

	// ---- helper: value type name ----

	/**
	 * Return a human-readable type name for a JVM ConstantValue attribute value.
	 *
	 * @param value The value returned by {@link FieldMember#getDefaultValue()}.
	 * @return A short type name such as "int", "long", "float", "double", or "String".
	 */
	static String getValueTypeName(Object value) {
		if (value instanceof Integer) return "int";
		if (value instanceof Long) return "long";
		if (value instanceof Float) return "float";
		if (value instanceof Double) return "double";
		if (value instanceof String) return "String";
		return value.getClass().getSimpleName();
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
			ClassInventoryCache.Inventory inventory = getInventory(workspace);
			String patternStr = getString(args, "pattern");
			int offset = getInt(args, "offset", 0);
			int limit = getInt(args, "limit", 100);

			Pattern regex;
			try {
				regex = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
			} catch (PatternSyntaxException e) {
				return createErrorResult("Invalid regex pattern: " + e.getMessage());
			}

			List<Map<String, Object>> matches = inventory.jvmClassEntries().stream()
					.filter(cls -> regex.matcher(cls.name()).find())
					.map(cls -> {
						Map<String, Object> entry = new LinkedHashMap<>();
						entry.put("name", cls.name());
						entry.put("superName", cls.superName());
						entry.put("access", cls.access());
						return entry;
					})
					.collect(Collectors.toList());

			List<Map<String, Object>> page = PaginationUtil.paginate(matches, offset, limit);
			Map<String, Object> result = PaginationUtil.paginatedResult(page, offset, limit, matches.size());
			return createJsonResult(result);
		});
	}

	// ---- field-get-all-constants ----

	private void registerFieldGetAllConstants() {
		Tool tool = buildTool(
				"field-get-all-constants",
				"Get all fields with compile-time constant values (ConstantValue attribute) from a class. Returns only fields that have a non-null default value. Useful for extracting configuration values, keys, magic numbers, etc.",
				createSchema(Map.of(
						"className", stringParam("Fully qualified class name (dot or slash notation)"),
						"includeNonStatic", boolParam("Include non-static fields with default values (default false)")
				), List.of("className"))
		);

		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();
			ClassInventoryCache.Inventory inventory = getInventory(workspace);
			String className = getString(args, "className");
			boolean includeNonStatic = getBoolean(args, "includeNonStatic", false);

			String normalized = ClassResolver.normalizeClassName(className);
			ClassPathNode node = ClassResolver.resolveClass(workspace, normalized, inventory);
			if (node == null) {
				return createErrorResult(classNotFoundWithSuggestions(normalized, inventory));
			}

			ClassInfo classInfo = node.getValue();

			List<Map<String, Object>> constants = classInfo.getFields().stream()
					.filter(f -> f.getDefaultValue() != null)
					.filter(f -> includeNonStatic || f.hasStaticModifier())
					.map(field -> {
						Map<String, Object> entry = new LinkedHashMap<>();
						entry.put("name", field.getName());
						entry.put("descriptor", field.getDescriptor());
						entry.put("access", field.getAccess());
						entry.put("value", field.getDefaultValue());
						entry.put("valueType", getValueTypeName(field.getDefaultValue()));
						return entry;
					})
					.collect(Collectors.toList());

			Map<String, Object> result = new LinkedHashMap<>();
			result.put("className", normalized);
			result.put("constantCount", constants.size());
			result.put("totalFieldCount", classInfo.getFields().size());
			result.put("constants", constants);
			return createJsonResult(result);
		});
	}
}
