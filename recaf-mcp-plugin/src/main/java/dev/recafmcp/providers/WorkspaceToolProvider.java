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

	public WorkspaceToolProvider(McpSyncServer server,
	                             WorkspaceManager workspaceManager,
	                             ResourceImporter resourceImporter) {
		super(server, workspaceManager);
		this.resourceImporter = resourceImporter;
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
				.description("Export the current workspace to a file")
				.inputSchema(createSchema(
						Map.of("outputPath", stringParam("Absolute path for the exported file")),
						List.of("outputPath")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			requireWorkspace();
			getString(args, "outputPath");
			return createTextResult("Export is not yet implemented. The workspace export API requires additional integration work.");
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
				.description("Get undo/redo history information for the current workspace")
				.inputSchema(createSchema(Map.of(), List.of()))
				.build();
		registerTool(tool, (exchange, args) -> {
			requireWorkspace();
			return createTextResult("Workspace history tracking is not yet implemented. " +
					"This will provide undo/redo information once integrated with Recaf's change tracking API.");
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
