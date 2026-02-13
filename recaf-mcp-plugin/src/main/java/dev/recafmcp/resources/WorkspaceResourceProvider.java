package dev.recafmcp.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP resource provider for workspace-level read-only data endpoints.
 * <p>
 * Provides two static resources:
 * <ul>
 *   <li>{@code recaf://workspace} - Current workspace metadata (class count, file count, supporting resources).</li>
 *   <li>{@code recaf://classes} - List of all class names with basic info.</li>
 * </ul>
 */
public class WorkspaceResourceProvider {
	private static final Logger logger = Logging.get(WorkspaceResourceProvider.class);
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private final McpSyncServer server;
	private final WorkspaceManager workspaceManager;

	public WorkspaceResourceProvider(McpSyncServer server, WorkspaceManager workspaceManager) {
		this.server = server;
		this.workspaceManager = workspaceManager;
	}

	/**
	 * Register all workspace resources with the MCP server.
	 */
	public void register() {
		registerWorkspaceResource();
		registerClassesResource();
	}

	// ---- recaf://workspace ----

	private void registerWorkspaceResource() {
		Resource resource = Resource.builder()
				.uri("recaf://workspace")
				.name("workspace")
				.description("Current workspace metadata including class count, file count, and supporting resources")
				.mimeType("application/json")
				.build();

		server.addResource(new SyncResourceSpecification(resource, (exchange, request) -> {
			try {
				String json = buildWorkspaceJson();
				return new ReadResourceResult(List.of(
						new TextResourceContents(request.uri(), "application/json", json)
				));
			} catch (Exception e) {
				logger.error("Failed to read workspace resource", e);
				return new ReadResourceResult(List.of(
						new TextResourceContents(request.uri(), "application/json",
								"{\"error\": \"" + escapeJson(e.getMessage()) + "\"}")
				));
			}
		}));

		logger.debug("Registered resource: recaf://workspace");
	}

	// ---- recaf://classes ----

	private void registerClassesResource() {
		Resource resource = Resource.builder()
				.uri("recaf://classes")
				.name("classes")
				.description("List of all class names in the current workspace with basic info")
				.mimeType("application/json")
				.build();

		server.addResource(new SyncResourceSpecification(resource, (exchange, request) -> {
			try {
				String json = buildClassesJson();
				return new ReadResourceResult(List.of(
						new TextResourceContents(request.uri(), "application/json", json)
				));
			} catch (Exception e) {
				logger.error("Failed to read classes resource", e);
				return new ReadResourceResult(List.of(
						new TextResourceContents(request.uri(), "application/json",
								"{\"error\": \"" + escapeJson(e.getMessage()) + "\"}")
				));
			}
		}));

		logger.debug("Registered resource: recaf://classes");
	}

	// ---- Data builders ----

	/**
	 * Build JSON string containing workspace metadata.
	 *
	 * @return Serialized JSON with workspace info.
	 */
	private String buildWorkspaceJson() {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			return "{\"status\": \"no_workspace\", \"message\": \"No workspace is currently open in Recaf.\"}";
		}

		WorkspaceResource primary = workspace.getPrimaryResource();

		LinkedHashMap<String, Object> info = new LinkedHashMap<>();
		info.put("status", "open");
		info.put("jvmClassCount", primary.getJvmClassBundle().size());
		info.put("fileCount", primary.getFileBundle().size());
		info.put("androidBundleCount", primary.getAndroidClassBundles().size());
		info.put("supportingResourceCount", workspace.getSupportingResources().size());

		// Include supporting resource details
		List<Map<String, Object>> supportingList = new ArrayList<>();
		for (WorkspaceResource supporting : workspace.getSupportingResources()) {
			LinkedHashMap<String, Object> supportingInfo = new LinkedHashMap<>();
			supportingInfo.put("jvmClassCount", supporting.getJvmClassBundle().size());
			supportingInfo.put("fileCount", supporting.getFileBundle().size());
			supportingInfo.put("androidBundleCount", supporting.getAndroidClassBundles().size());
			supportingList.add(supportingInfo);
		}
		info.put("supportingResources", supportingList);

		return serializeJson(info);
	}

	/**
	 * Build JSON string containing list of all classes with basic info.
	 *
	 * @return Serialized JSON with class listing.
	 */
	private String buildClassesJson() {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			return "{\"error\": \"No workspace is currently open in Recaf.\"}";
		}

		List<Map<String, Object>> classes = workspace.jvmClassesStream()
				.map(cpn -> (JvmClassInfo) cpn.getValue())
				.sorted((a, b) -> a.getName().compareTo(b.getName()))
				.map(cls -> {
					LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
					entry.put("name", cls.getName());
					entry.put("superName", cls.getSuperName());
					entry.put("access", cls.getAccess());
					entry.put("interfaceCount", cls.getInterfaces().size());
					entry.put("methodCount", cls.getMethods().size());
					entry.put("fieldCount", cls.getFields().size());
					return (Map<String, Object>) entry;
				})
				.collect(Collectors.toList());

		LinkedHashMap<String, Object> result = new LinkedHashMap<>();
		result.put("totalClasses", classes.size());
		result.put("classes", classes);

		return serializeJson(result);
	}

	// ---- Helpers ----

	private String serializeJson(Object data) {
		try {
			return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data);
		} catch (Exception e) {
			logger.error("JSON serialization failed", e);
			return "{\"error\": \"JSON serialization failed: " + escapeJson(e.getMessage()) + "\"}";
		}
	}

	private static String escapeJson(String text) {
		if (text == null) return "null";
		return text.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}
}
