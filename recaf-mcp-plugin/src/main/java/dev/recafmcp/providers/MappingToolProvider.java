package dev.recafmcp.providers;

import dev.recafmcp.util.ClassResolver;
import dev.recafmcp.util.ErrorHelper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.MappingApplier;
import software.coley.recaf.services.mapping.MappingResults;
import software.coley.recaf.services.mapping.aggregate.AggregateMappingManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool provider for mapping and renaming operations.
 * <p>
 * Provides tools for renaming classes, methods, and fields via Recaf's
 * mapping API, as well as stub tools for mapping file import/export and
 * format listing.
 */
public class MappingToolProvider extends AbstractToolProvider {
	private static final Logger logger = Logging.get(MappingToolProvider.class);

	private final MappingApplier mappingApplier;
	private final AggregateMappingManager aggregateMappingManager;

	public MappingToolProvider(McpSyncServer server,
	                           WorkspaceManager workspaceManager,
	                           MappingApplier mappingApplier,
	                           AggregateMappingManager aggregateMappingManager) {
		super(server, workspaceManager);
		this.mappingApplier = mappingApplier;
		this.aggregateMappingManager = aggregateMappingManager;
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

			MappingResults results = mappingApplier.applyToPrimaryResource(mappings);
			results.apply();

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

			MappingResults results = mappingApplier.applyToPrimaryResource(mappings);
			results.apply();

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

			MappingResults results = mappingApplier.applyToPrimaryResource(mappings);
			results.apply();

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
				.description("Rename a local variable in a method. Not yet implemented.")
				.inputSchema(createSchema(Map.of(), List.of()))
				.build();
		registerTool(tool, (exchange, args) ->
				createTextResult("Variable renaming operates on decompiler output and is not yet implemented. " +
						"Use rename-field or rename-method for bytecode-level renaming.")
		);
	}

	// ---- mapping-apply ----

	private void registerMappingApply() {
		Tool tool = Tool.builder()
				.name("mapping-apply")
				.description("Import and apply a mapping file to the current workspace. Supports various formats (SRG, ProGuard, Enigma, Tiny, etc.).")
				.inputSchema(createSchema(
						Map.of(
								"filePath", stringParam("Path to the mapping file to import"),
								"format", stringParam("Optional mapping format name. If omitted, the format will be auto-detected.")
						),
						List.of("filePath")
				))
				.build();
		registerTool(tool, (exchange, args) ->
				createTextResult("Mapping file import is not yet implemented. " +
						"This tool will support importing mapping files in formats such as SRG, ProGuard, Enigma, Tiny v1, Tiny v2, and JADX. " +
						"For now, use rename-class, rename-method, and rename-field to apply individual mappings.")
		);
	}

	// ---- mapping-export ----

	private void registerMappingExport() {
		Tool tool = Tool.builder()
				.name("mapping-export")
				.description("Export the current aggregate mappings to a file in the specified format.")
				.inputSchema(createSchema(
						Map.of(
								"outputPath", stringParam("File path where the exported mappings will be written"),
								"format", stringParam("Optional mapping format name for export (e.g. 'SRG', 'ProGuard', 'Tiny v2')")
						),
						List.of("outputPath")
				))
				.build();
		registerTool(tool, (exchange, args) ->
				createTextResult("Mapping export is not yet implemented. " +
						"This tool will support exporting the current aggregate mappings to formats such as SRG, ProGuard, Enigma, Tiny v1, Tiny v2, and JADX. " +
						"The aggregate mappings track all rename operations applied during the current session.")
		);
	}

	// ---- mapping-list-formats ----

	private void registerMappingListFormats() {
		Tool tool = Tool.builder()
				.name("mapping-list-formats")
				.description("List all available mapping file formats supported by Recaf.")
				.inputSchema(createSchema(Map.of(), List.of()))
				.build();
		registerTool(tool, (exchange, args) ->
				createTextResult("Mapping format listing is not yet implemented. " +
						"Recaf supports the following mapping formats: " +
						"Simple, SRG, ProGuard, Enigma, Tiny v1, Tiny v2, and JADX. " +
						"Each format has different capabilities for field type differentiation and variable mapping support.")
		);
	}
}
