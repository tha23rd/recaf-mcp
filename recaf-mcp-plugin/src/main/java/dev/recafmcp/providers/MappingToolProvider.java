package dev.recafmcp.providers;

import dev.recafmcp.cache.WorkspaceRevisionTracker;
import dev.recafmcp.util.ClassResolver;
import dev.recafmcp.util.ErrorHelper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.MappingResults;
import software.coley.recaf.services.mapping.Mappings;
import software.coley.recaf.services.mapping.aggregate.AggregateMappingManager;
import software.coley.recaf.services.mapping.aggregate.AggregatedMappings;
import software.coley.recaf.services.mapping.format.InvalidMappingException;
import software.coley.recaf.services.mapping.format.MappingFileFormat;
import software.coley.recaf.services.mapping.format.MappingFormatManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP tool provider for mapping and renaming operations.
 * <p>
 * Provides tools for renaming classes, methods, and fields via Recaf's
 * mapping API, as well as stub tools for mapping file import/export and
 * format listing.
 */
public class MappingToolProvider extends AbstractToolProvider {
	private static final Logger logger = Logging.get(MappingToolProvider.class);

	private final MappingApplierService mappingApplierService;
	private final AggregateMappingManager aggregateMappingManager;
	private final MappingFormatManager mappingFormatManager;
	private final WorkspaceRevisionTracker revisionTracker;

	public MappingToolProvider(McpSyncServer server,
	                           WorkspaceManager workspaceManager,
	                           MappingApplierService mappingApplierService,
	                           AggregateMappingManager aggregateMappingManager,
	                           MappingFormatManager mappingFormatManager,
	                           WorkspaceRevisionTracker revisionTracker) {
		super(server, workspaceManager);
		this.mappingApplierService = mappingApplierService;
		this.aggregateMappingManager = aggregateMappingManager;
		this.mappingFormatManager = mappingFormatManager;
		this.revisionTracker = revisionTracker;
	}

	@Override
	public void registerTools() {
		registerRenameClass();
		registerRenameMethod();
		registerRenameField();
		registerRenameVariable();
		registerMappingApply();
		registerMappingExport();
		registerMappingListFormats();
	}

	// ---- rename-class ----

	private void registerRenameClass() {
		Tool tool = Tool.builder()
				.name("rename-class")
				.description("Rename a class by applying a mapping. The old name and new name should be in internal format (e.g. 'com/example/OldName' -> 'com/example/NewName').")
				.inputSchema(createSchema(
						Map.of(
								"oldName", stringParam("Fully qualified original class name (dot or slash notation)"),
								"newName", stringParam("Fully qualified new class name (dot or slash notation)")
						),
						List.of("oldName", "newName")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			String oldName = getString(args, "oldName");
			String newName = getString(args, "newName");
			Workspace workspace = requireWorkspace();

			String normalizedOld = ClassResolver.normalizeClassName(oldName);
			String normalizedNew = ClassResolver.normalizeClassName(newName);

			// Verify the source class exists
			ClassPathNode pathNode = ClassResolver.resolveClass(workspace, normalizedOld);
			if (pathNode == null) {
				return createErrorResult(ErrorHelper.classNotFound(normalizedOld, workspace));
			}

			// Use the actual resolved name in case the user provided a simple name
			String resolvedOldName = pathNode.getValue().getName();

			IntermediateMappings mappings = new IntermediateMappings();
			mappings.addClass(resolvedOldName, normalizedNew);

			MappingResults results = mappingApplierService.inWorkspace(workspace).applyToPrimaryResource(mappings);
			results.apply();
			markWorkspaceMutated(workspace);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "renamed");
			result.put("oldName", resolvedOldName);
			result.put("newName", normalizedNew);
			return createJsonResult(result);
		});
	}

	// ---- rename-method ----

	private void registerRenameMethod() {
		Tool tool = Tool.builder()
				.name("rename-method")
				.description("Rename a method by applying a mapping. Requires the owning class name, method name, and JVM method descriptor.")
				.inputSchema(createSchema(
						Map.of(
								"className", stringParam("Fully qualified class name containing the method (dot or slash notation)"),
								"methodName", stringParam("Current name of the method"),
								"methodDescriptor", stringParam("JVM method descriptor (e.g. '(Ljava/lang/String;)V')"),
								"newName", stringParam("New name for the method")
						),
						List.of("className", "methodName", "methodDescriptor", "newName")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			String className = getString(args, "className");
			String methodName = getString(args, "methodName");
			String methodDescriptor = getString(args, "methodDescriptor");
			String newName = getString(args, "newName");
			Workspace workspace = requireWorkspace();

			String normalizedClass = ClassResolver.normalizeClassName(className);

			// Verify the class exists
			ClassPathNode pathNode = ClassResolver.resolveClass(workspace, normalizedClass);
			if (pathNode == null) {
				return createErrorResult(ErrorHelper.classNotFound(normalizedClass, workspace));
			}

			String resolvedClassName = pathNode.getValue().getName();

			IntermediateMappings mappings = new IntermediateMappings();
			mappings.addMethod(resolvedClassName, methodDescriptor, methodName, newName);

			MappingResults results = mappingApplierService.inWorkspace(workspace).applyToPrimaryResource(mappings);
			results.apply();
			markWorkspaceMutated(workspace);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "renamed");
			result.put("className", resolvedClassName);
			result.put("oldMethodName", methodName);
			result.put("methodDescriptor", methodDescriptor);
			result.put("newMethodName", newName);
			return createJsonResult(result);
		});
	}

	// ---- rename-field ----

	private void registerRenameField() {
		Tool tool = Tool.builder()
				.name("rename-field")
				.description("Rename a field by applying a mapping. Requires the owning class name, field name, and JVM field descriptor.")
				.inputSchema(createSchema(
						Map.of(
								"className", stringParam("Fully qualified class name containing the field (dot or slash notation)"),
								"fieldName", stringParam("Current name of the field"),
								"fieldDescriptor", stringParam("JVM field descriptor (e.g. 'Ljava/lang/String;' or 'I')"),
								"newName", stringParam("New name for the field")
						),
						List.of("className", "fieldName", "fieldDescriptor", "newName")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			String className = getString(args, "className");
			String fieldName = getString(args, "fieldName");
			String fieldDescriptor = getString(args, "fieldDescriptor");
			String newName = getString(args, "newName");
			Workspace workspace = requireWorkspace();

			String normalizedClass = ClassResolver.normalizeClassName(className);

			// Verify the class exists
			ClassPathNode pathNode = ClassResolver.resolveClass(workspace, normalizedClass);
			if (pathNode == null) {
				return createErrorResult(ErrorHelper.classNotFound(normalizedClass, workspace));
			}

			String resolvedClassName = pathNode.getValue().getName();

			IntermediateMappings mappings = new IntermediateMappings();
			mappings.addField(resolvedClassName, fieldDescriptor, fieldName, newName);

			MappingResults results = mappingApplierService.inWorkspace(workspace).applyToPrimaryResource(mappings);
			results.apply();
			markWorkspaceMutated(workspace);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "renamed");
			result.put("className", resolvedClassName);
			result.put("oldFieldName", fieldName);
			result.put("fieldDescriptor", fieldDescriptor);
			result.put("newFieldName", newName);
			return createJsonResult(result);
		});
	}

	// ---- rename-variable ----

	private void registerRenameVariable() {
		Tool tool = Tool.builder()
				.name("rename-variable")
				.description("Rename a local variable in a method. Uses Recaf's mapping API to apply " +
						"variable-level renaming via the mapping applier service.")
				.inputSchema(createSchema(
						Map.of(
								"className", stringParam("Fully qualified class name containing the method (dot or slash notation)"),
								"methodName", stringParam("Name of the method containing the variable"),
								"methodDescriptor", stringParam("JVM method descriptor of the method (e.g. '(Ljava/lang/String;)V')"),
								"oldName", stringParam("Current name of the local variable"),
								"newName", stringParam("New name for the local variable")
						),
						List.of("className", "methodName", "methodDescriptor", "oldName", "newName")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			String className = getString(args, "className");
			String methodName = getString(args, "methodName");
			String methodDescriptor = getString(args, "methodDescriptor");
			String oldName = getString(args, "oldName");
			String newName = getString(args, "newName");
			Workspace workspace = requireWorkspace();

			String normalizedClass = ClassResolver.normalizeClassName(className);

			// Verify the class exists
			ClassPathNode pathNode = ClassResolver.resolveClass(workspace, normalizedClass);
			if (pathNode == null) {
				return createErrorResult(ErrorHelper.classNotFound(normalizedClass, workspace));
			}

			ClassInfo classInfo = pathNode.getValue();
			String resolvedClassName = classInfo.getName();

			// Verify the method exists and try to find the variable index
			MethodMember method = classInfo.getDeclaredMethod(methodName, methodDescriptor);
			if (method == null) {
				return createErrorResult("Method '" + methodName + methodDescriptor +
						"' not found in class '" + resolvedClassName + "'.");
			}

			// Find variable index from the local variable table
			int varIndex = -1;
			String varDesc = null;
			var localVars = method.getLocalVariables();
			if (localVars != null) {
				for (var lv : localVars) {
					if (lv.getName().equals(oldName)) {
						varIndex = lv.getIndex();
						varDesc = lv.getDescriptor();
						break;
					}
				}
			}

			if (varIndex < 0) {
				return createErrorResult("Local variable '" + oldName + "' not found in method '" +
						methodName + methodDescriptor + "'. The class may not have debug information " +
						"(local variable table). Available variables: " + formatLocalVars(localVars));
			}

			// Apply variable mapping: addVariable(owner, methodName, methodDesc, varDesc, oldName, index, newName)
			IntermediateMappings mappings = new IntermediateMappings();
			mappings.addVariable(resolvedClassName, methodName, methodDescriptor,
					varDesc, oldName, varIndex, newName);

			MappingResults results = mappingApplierService.inWorkspace(workspace).applyToPrimaryResource(mappings);
			results.apply();
			markWorkspaceMutated(workspace);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "renamed");
			result.put("className", resolvedClassName);
			result.put("methodName", methodName);
			result.put("methodDescriptor", methodDescriptor);
			result.put("oldVariableName", oldName);
			result.put("newVariableName", newName);
			result.put("variableIndex", varIndex);
			return createJsonResult(result);
		});
	}

	/**
	 * Format local variable list for error messages.
	 */
	private static String formatLocalVars(List<? extends software.coley.recaf.info.member.LocalVariable> vars) {
		if (vars == null || vars.isEmpty()) return "(none - no debug info)";
		StringBuilder sb = new StringBuilder();
		for (var lv : vars) {
			if (!sb.isEmpty()) sb.append(", ");
			sb.append(lv.getName()).append(" (index=").append(lv.getIndex())
					.append(", desc=").append(lv.getDescriptor()).append(")");
		}
		return sb.toString();
	}

	// ---- mapping-apply ----

	private void registerMappingApply() {
		Tool tool = Tool.builder()
				.name("mapping-apply")
				.description("Parse and apply mapping text to the current workspace. Supports various formats " +
						"(use 'mapping-list-formats' to see available formats). The mapping text is parsed " +
						"according to the specified format and applied to all classes in the primary resource.")
				.inputSchema(createSchema(
						Map.of(
								"format", stringParam("Mapping format name (e.g. 'SRG', 'ProGuard', 'Enigma', 'Tiny-v1', 'Tiny-v2'). " +
										"Use 'mapping-list-formats' to see available formats."),
								"content", stringParam("The mapping file content as text")
						),
						List.of("format", "content")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			String format = getString(args, "format");
			String content = getString(args, "content");
			Workspace workspace = requireWorkspace();

			// Resolve the mapping format
			MappingFileFormat mappingFormat = mappingFormatManager.createFormatInstance(format);
			if (mappingFormat == null) {
				Set<String> available = mappingFormatManager.getMappingFileFormats();
				return createErrorResult("Unknown mapping format: '" + format + "'. Available formats: " + available);
			}

			// Parse the mapping text
			IntermediateMappings parsedMappings;
			try {
				parsedMappings = mappingFormat.parse(content);
			} catch (InvalidMappingException e) {
				return createErrorResult("Failed to parse mapping content as '" + format + "': " + e.getMessage());
			}
			if (parsedMappings.isEmpty()) {
				return createErrorResult("No mappings were parsed from the provided content. " +
						"Ensure the content matches the '" + format + "' format.");
			}

			// Apply the parsed mappings
			MappingResults results = mappingApplierService.inWorkspace(workspace).applyToPrimaryResource(parsedMappings);
			results.apply();
			markWorkspaceMutated(workspace);

			// Build summary of what was mapped
			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "applied");
			result.put("format", mappingFormat.implementationName());
			result.put("classMappings", parsedMappings.getClasses().size());
			result.put("fieldMappings", countFieldMappings(parsedMappings));
			result.put("methodMappings", countMethodMappings(parsedMappings));
			result.put("variableMappings", countVariableMappings(parsedMappings));
			return createJsonResult(result);
		});
	}

	private static int countFieldMappings(IntermediateMappings mappings) {
		return mappings.getFields().values().stream().mapToInt(List::size).sum();
	}

	private void markWorkspaceMutated(Workspace workspace) {
		revisionTracker.bump(workspace);
	}

	private static int countMethodMappings(IntermediateMappings mappings) {
		return mappings.getMethods().values().stream().mapToInt(List::size).sum();
	}

	private static int countVariableMappings(IntermediateMappings mappings) {
		return mappings.getVariables().values().stream().mapToInt(List::size).sum();
	}

	// ---- mapping-export ----

	private void registerMappingExport() {
		Tool tool = Tool.builder()
				.name("mapping-export")
				.description("Export the current aggregate mappings (all renames applied during this session) " +
						"as text in the specified format. Returns the mapping text content directly.")
				.inputSchema(createSchema(
						Map.of(
								"format", stringParam("Mapping format name for export (e.g. 'SRG', 'ProGuard', 'Simple'). " +
										"Use 'mapping-list-formats' to see available formats. Not all formats support export.")
						),
						List.of("format")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			String format = getString(args, "format");
			requireWorkspace();

			// Get current aggregate mappings
			AggregatedMappings aggregated = aggregateMappingManager.getAggregatedMappings();
			if (aggregated == null) {
				return createErrorResult("No aggregate mapping manager is available. " +
						"Ensure a workspace is open and mappings have been applied.");
			}

			IntermediateMappings intermediate = aggregated.exportIntermediate();
			if (intermediate.isEmpty()) {
				LinkedHashMap<String, Object> result = new LinkedHashMap<>();
				result.put("status", "empty");
				result.put("message", "No mappings have been applied in the current session. " +
						"Use rename-class, rename-method, rename-field, or mapping-apply first.");
				return createJsonResult(result);
			}

			// Resolve the export format
			MappingFileFormat mappingFormat = mappingFormatManager.createFormatInstance(format);
			if (mappingFormat == null) {
				Set<String> available = mappingFormatManager.getMappingFileFormats();
				return createErrorResult("Unknown mapping format: '" + format + "'. Available formats: " + available);
			}

			if (!mappingFormat.supportsExportText()) {
				return createErrorResult("The '" + format + "' format does not support text export. " +
						"Try a different format such as 'Simple' or 'SRG'.");
			}

			String exportedText;
			try {
				exportedText = mappingFormat.exportText(aggregated);
			} catch (InvalidMappingException e) {
				return createErrorResult("Failed to export mappings as '" + format + "': " + e.getMessage());
			}

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "exported");
			result.put("format", mappingFormat.implementationName());
			result.put("classMappings", intermediate.getClasses().size());
			result.put("fieldMappings", countFieldMappings(intermediate));
			result.put("methodMappings", countMethodMappings(intermediate));
			result.put("content", exportedText);
			return createJsonResult(result);
		});
	}

	// ---- mapping-list-formats ----

	private void registerMappingListFormats() {
		Tool tool = Tool.builder()
				.name("mapping-list-formats")
				.description("List all available mapping file formats supported by Recaf, including " +
						"their capabilities (field type differentiation, variable support, export support).")
				.inputSchema(createSchema(Map.of(), List.of()))
				.build();
		registerTool(tool, (exchange, args) -> {
			Set<String> formatNames = mappingFormatManager.getMappingFileFormats();

			List<Map<String, Object>> formats = new ArrayList<>();
			for (String name : formatNames) {
				MappingFileFormat format = mappingFormatManager.createFormatInstance(name);
				if (format == null) continue;

				LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
				entry.put("name", format.implementationName());
				entry.put("supportsFieldTypeDifferentiation", format.doesSupportFieldTypeDifferentiation());
				entry.put("supportsVariableTypeDifferentiation", format.doesSupportVariableTypeDifferentiation());
				entry.put("supportsExport", format.supportsExportText());
				formats.add(entry);
			}

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("formatCount", formats.size());
			result.put("formats", formats);
			return createJsonResult(result);
		});
	}
}
