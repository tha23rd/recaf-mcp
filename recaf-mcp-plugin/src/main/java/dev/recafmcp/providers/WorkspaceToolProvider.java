package dev.recafmcp.providers;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import software.coley.recaf.services.mapping.aggregate.AggregateMappingManager;
import software.coley.recaf.services.mapping.aggregate.AggregatedMappings;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.data.ClassMapping;
import software.coley.recaf.services.mapping.data.FieldMapping;
import software.coley.recaf.services.mapping.data.MethodMapping;
import software.coley.recaf.services.workspace.io.PathWorkspaceExportConsumer;
import software.coley.recaf.services.workspace.io.WorkspaceCompressType;
import software.coley.recaf.services.workspace.io.WorkspaceExportOptions;
import software.coley.recaf.services.workspace.io.WorkspaceExporter;
import software.coley.recaf.services.workspace.io.WorkspaceOutputType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool provider for workspace management operations.
 * <p>
 * Provides tools for opening, closing, inspecting, and managing Recaf workspaces
 * including primary and supporting resources.
 */
public class WorkspaceToolProvider extends AbstractToolProvider {
	private static final Logger logger = Logging.get(WorkspaceToolProvider.class);

	private final ResourceImporter resourceImporter;
	private final AggregateMappingManager aggregateMappingManager;

	public WorkspaceToolProvider(McpSyncServer server,
	                             WorkspaceManager workspaceManager,
	                             ResourceImporter resourceImporter,
	                             AggregateMappingManager aggregateMappingManager) {
		super(server, workspaceManager);
		this.resourceImporter = resourceImporter;
		this.aggregateMappingManager = aggregateMappingManager;
	}

	@Override
	public void registerTools() {
		registerWorkspaceOpen();
		registerWorkspaceClose();
		registerWorkspaceGetInfo();
		registerWorkspaceExport();
		registerWorkspaceListResources();
		registerWorkspaceAddSupporting();
		registerWorkspaceGetHistory();
	}

	// ---- workspace-open ----

	private void registerWorkspaceOpen() {
		Tool tool = Tool.builder()
				.name("workspace-open")
				.description("Open a file (JAR, APK, class, etc.) as the current Recaf workspace")
				.inputSchema(createSchema(
						Map.of("path", stringParam("Absolute path to the file to open")),
						List.of("path")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			String path = getString(args, "path");
			logger.info("Opening workspace from: {}", path);

			WorkspaceResource resource = importResourceFromPath(path);
			Workspace workspace = new BasicWorkspace(resource);
			workspaceManager.setCurrent(workspace);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "opened");
			result.put("path", path);
			result.put("jvmClassCount", resource.getJvmClassBundle().size());
			result.put("fileCount", resource.getFileBundle().size());
			result.put("androidBundleCount", resource.getAndroidClassBundles().size());
			return createJsonResult(result);
		});
	}

	// ---- workspace-close ----

	private void registerWorkspaceClose() {
		Tool tool = Tool.builder()
				.name("workspace-close")
				.description("Close the current Recaf workspace")
				.inputSchema(createSchema(Map.of(), List.of()))
				.build();
		registerTool(tool, (exchange, args) -> {
			requireWorkspace();
			workspaceManager.closeCurrent();
			return createTextResult("Workspace closed successfully.");
		});
	}

	// ---- workspace-get-info ----

	private void registerWorkspaceGetInfo() {
		Tool tool = Tool.builder()
				.name("workspace-get-info")
				.description("Get information about the current workspace including class and file counts")
				.inputSchema(createSchema(Map.of(), List.of()))
				.build();
		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();
			WorkspaceResource primary = workspace.getPrimaryResource();

			LinkedHashMap<String, Object> info = new LinkedHashMap<>();
			info.put("jvmClassCount", primary.getJvmClassBundle().size());
			info.put("fileCount", primary.getFileBundle().size());
			info.put("androidBundleCount", primary.getAndroidClassBundles().size());
			info.put("supportingResourceCount", workspace.getSupportingResources().size());
			return createJsonResult(info);
		});
	}

	// ---- workspace-export ----

	private void registerWorkspaceExport() {
		Tool tool = Tool.builder()
				.name("workspace-export")
				.description("Export the current workspace to a file. Produces a JAR/ZIP or directory " +
						"containing all classes and resources from the primary resource.")
				.inputSchema(createSchema(
						Map.of(
								"outputPath", stringParam("Absolute path for the exported file (e.g. '/tmp/output.jar')"),
								"outputType", stringParam("Output type: 'FILE' for JAR/ZIP or 'DIRECTORY' for exploded output (default: FILE)"),
								"compress", stringParam("Compression mode: 'MATCH_ORIGINAL', 'SMART', 'ALWAYS', or 'NEVER' (default: MATCH_ORIGINAL)"),
								"bundleSupporting", boolParam("Include supporting resources in the export (default: false)")
						),
						List.of("outputPath")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			String outputPath = getString(args, "outputPath");
			String outputTypeStr = getOptionalString(args, "outputType", "FILE");
			String compressStr = getOptionalString(args, "compress", "MATCH_ORIGINAL");
			boolean bundleSupporting = getBoolean(args, "bundleSupporting", false);
			Workspace workspace = requireWorkspace();

			WorkspaceOutputType outputType;
			try {
				outputType = WorkspaceOutputType.valueOf(outputTypeStr.toUpperCase());
			} catch (IllegalArgumentException e) {
				return createErrorResult("Invalid output type: '" + outputTypeStr + "'. Use 'FILE' or 'DIRECTORY'.");
			}

			WorkspaceCompressType compressType;
			try {
				compressType = WorkspaceCompressType.valueOf(compressStr.toUpperCase());
			} catch (IllegalArgumentException e) {
				return createErrorResult("Invalid compression type: '" + compressStr +
						"'. Use 'MATCH_ORIGINAL', 'SMART', 'ALWAYS', or 'NEVER'.");
			}

			Path output = Path.of(outputPath);
			PathWorkspaceExportConsumer consumer = new PathWorkspaceExportConsumer(output);
			WorkspaceExportOptions options = new WorkspaceExportOptions(compressType, outputType, consumer);
			options.setBundleSupporting(bundleSupporting);

			WorkspaceExporter exporter = options.create();
			try {
				exporter.export(workspace);
			} catch (IOException e) {
				throw new RuntimeException("Failed to export workspace to '" + outputPath + "': " + e.getMessage(), e);
			}

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "exported");
			result.put("outputPath", outputPath);
			result.put("outputType", outputType.name());
			result.put("compression", compressType.name());
			result.put("bundleSupporting", bundleSupporting);
			return createJsonResult(result);
		});
	}

	// ---- workspace-list-resources ----

	private void registerWorkspaceListResources() {
		Tool tool = Tool.builder()
				.name("workspace-list-resources")
				.description("List all resources (primary and supporting) in the current workspace with bundle sizes")
				.inputSchema(createSchema(Map.of(), List.of()))
				.build();
		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("primary", buildResourceInfo(workspace.getPrimaryResource()));

			List<Map<String, Object>> supportingList = new ArrayList<>();
			for (WorkspaceResource supporting : workspace.getSupportingResources()) {
				supportingList.add(buildResourceInfo(supporting));
			}
			result.put("supporting", supportingList);

			return createJsonResult(result);
		});
	}

	// ---- workspace-add-supporting ----

	private void registerWorkspaceAddSupporting() {
		Tool tool = Tool.builder()
				.name("workspace-add-supporting")
				.description("Import a file and add it as a supporting resource to the current workspace")
				.inputSchema(createSchema(
						Map.of("path", stringParam("Absolute path to the supporting resource file")),
						List.of("path")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			String path = getString(args, "path");
			Workspace workspace = requireWorkspace();
			logger.info("Adding supporting resource from: {}", path);

			WorkspaceResource resource = importResourceFromPath(path);
			workspace.getSupportingResources().add(resource);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "added");
			result.put("path", path);
			result.put("jvmClassCount", resource.getJvmClassBundle().size());
			result.put("fileCount", resource.getFileBundle().size());
			result.put("totalSupportingResources", workspace.getSupportingResources().size());
			return createJsonResult(result);
		});
	}

	// ---- workspace-get-history ----

	private void registerWorkspaceGetHistory() {
		Tool tool = Tool.builder()
				.name("workspace-get-history")
				.description("Get modification history for the current workspace session. Shows all " +
						"mapping operations (class/method/field/variable renames) applied via the " +
						"aggregate mapping manager, plus workspace resource information.")
				.inputSchema(createSchema(Map.of(), List.of()))
				.build();
		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();

			// Workspace resource summary
			WorkspaceResource primary = workspace.getPrimaryResource();
			result.put("primaryJvmClassCount", primary.getJvmClassBundle().size());
			result.put("primaryFileCount", primary.getFileBundle().size());
			result.put("supportingResourceCount", workspace.getSupportingResources().size());

			// Aggregate mappings (all renames applied during this session)
			AggregatedMappings aggregated = aggregateMappingManager.getAggregatedMappings();
			if (aggregated != null) {
				IntermediateMappings intermediate = aggregated.exportIntermediate();

				// Class renames
				List<Map<String, Object>> classRenames = new ArrayList<>();
				for (Map.Entry<String, ClassMapping> entry : intermediate.getClasses().entrySet()) {
					LinkedHashMap<String, Object> rename = new LinkedHashMap<>();
					ClassMapping cm = entry.getValue();
					rename.put("oldName", cm.getOldName());
					rename.put("newName", cm.getNewName());
					classRenames.add(rename);
				}

				// Field renames
				List<Map<String, Object>> fieldRenames = new ArrayList<>();
				for (Map.Entry<String, List<FieldMapping>> entry : intermediate.getFields().entrySet()) {
					for (FieldMapping fm : entry.getValue()) {
						LinkedHashMap<String, Object> rename = new LinkedHashMap<>();
						rename.put("owner", fm.getOwnerName());
						rename.put("oldName", fm.getOldName());
						rename.put("newName", fm.getNewName());
						rename.put("descriptor", fm.getDesc());
						fieldRenames.add(rename);
					}
				}

				// Method renames
				List<Map<String, Object>> methodRenames = new ArrayList<>();
				for (Map.Entry<String, List<MethodMapping>> entry : intermediate.getMethods().entrySet()) {
					for (MethodMapping mm : entry.getValue()) {
						LinkedHashMap<String, Object> rename = new LinkedHashMap<>();
						rename.put("owner", mm.getOwnerName());
						rename.put("oldName", mm.getOldName());
						rename.put("newName", mm.getNewName());
						rename.put("descriptor", mm.getDesc());
						methodRenames.add(rename);
					}
				}

				LinkedHashMap<String, Object> mappingHistory = new LinkedHashMap<>();
				mappingHistory.put("classRenames", classRenames);
				mappingHistory.put("fieldRenames", fieldRenames);
				mappingHistory.put("methodRenames", methodRenames);
				mappingHistory.put("totalMappings",
						classRenames.size() + fieldRenames.size() + methodRenames.size());
				result.put("mappingHistory", mappingHistory);
			} else {
				result.put("mappingHistory", "No aggregate mappings available.");
			}

			return createJsonResult(result);
		});
	}

	// ---- Helper methods ----

	/**
	 * Import a resource from the given file path, wrapping checked {@link IOException}
	 * as an unchecked exception so it can propagate through the lambda-based tool handlers
	 * and be caught by the {@link #registerTool} error wrapper.
	 *
	 * @param filePath Absolute path to the resource file.
	 * @return The imported {@link WorkspaceResource}.
	 */
	private WorkspaceResource importResourceFromPath(String filePath) {
		try {
			return resourceImporter.importResource(Path.of(filePath));
		} catch (IOException e) {
			throw new RuntimeException("Failed to import resource from '" + filePath + "': " + e.getMessage(), e);
		}
	}

	/**
	 * Build an info map describing a single workspace resource's bundle sizes.
	 *
	 * @param resource The workspace resource to describe.
	 * @return A map containing bundle size information.
	 */
	private static Map<String, Object> buildResourceInfo(WorkspaceResource resource) {
		LinkedHashMap<String, Object> info = new LinkedHashMap<>();
		info.put("jvmClassCount", resource.getJvmClassBundle().size());
		info.put("fileCount", resource.getFileBundle().size());

		LinkedHashMap<String, Object> androidBundles = new LinkedHashMap<>();
		resource.getAndroidClassBundles().forEach(
				(name, bundle) -> androidBundles.put(name, bundle.size())
		);
		info.put("androidClassBundles", androidBundles);
		return info;
	}
}
