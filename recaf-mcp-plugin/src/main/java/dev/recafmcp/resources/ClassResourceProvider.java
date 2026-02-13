package dev.recafmcp.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.recafmcp.util.ClassResolver;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * MCP resource provider for individual class data endpoints using URI templates.
 * <p>
 * Provides a parameterized resource:
 * <ul>
 *   <li>{@code recaf://class/{name}} - Full class info and decompiled source for the named class.</li>
 * </ul>
 */
public class ClassResourceProvider {
	private static final Logger logger = Logging.get(ClassResourceProvider.class);
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private static final String CLASS_URI_PREFIX = "recaf://class/";

	private final McpSyncServer server;
	private final WorkspaceManager workspaceManager;
	private final DecompilerManager decompilerManager;

	public ClassResourceProvider(McpSyncServer server,
	                             WorkspaceManager workspaceManager,
	                             DecompilerManager decompilerManager) {
		this.server = server;
		this.workspaceManager = workspaceManager;
		this.decompilerManager = decompilerManager;
	}

	/**
	 * Register all class resources with the MCP server.
	 */
	public void register() {
		registerClassTemplate();
	}

	// ---- recaf://class/{name} ----

	private void registerClassTemplate() {
		ResourceTemplate template = ResourceTemplate.builder()
				.uriTemplate("recaf://class/{name}")
				.name("class")
				.description("Full class info and decompiled source for a specific class. " +
						"Use the fully qualified class name in dot notation (e.g. com.example.MyClass).")
				.mimeType("application/json")
				.build();

		server.addResourceTemplate(new SyncResourceTemplateSpecification(template, (exchange, request) -> {
			try {
				String className = extractClassName(request.uri());
				String json = buildClassJson(className);
				return new ReadResourceResult(List.of(
						new TextResourceContents(request.uri(), "application/json", json)
				));
			} catch (Exception e) {
				logger.error("Failed to read class resource: {}", request.uri(), e);
				return new ReadResourceResult(List.of(
						new TextResourceContents(request.uri(), "application/json",
								"{\"error\": \"" + escapeJson(e.getMessage()) + "\"}")
				));
			}
		}));

		logger.debug("Registered resource template: recaf://class/{{name}}");
	}

	// ---- Data builders ----

	/**
	 * Build JSON string containing full class info and decompiled source.
	 *
	 * @param className Class name to resolve and decompile.
	 * @return Serialized JSON with class details.
	 */
	private String buildClassJson(String className) {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			return "{\"error\": \"No workspace is currently open in Recaf.\"}";
		}

		ClassPathNode pathNode = ClassResolver.resolveClass(workspace, className);
		if (pathNode == null) {
			LinkedHashMap<String, Object> error = new LinkedHashMap<>();
			error.put("error", "Class not found: " + className);
			error.put("suggestions", ClassResolver.findSimilarClassNames(workspace, className, 5));
			return serializeJson(error);
		}

		ClassInfo classInfo = pathNode.getValue();

		LinkedHashMap<String, Object> result = new LinkedHashMap<>();
		result.put("name", classInfo.getName());
		result.put("access", classInfo.getAccess());
		result.put("superName", classInfo.getSuperName());
		result.put("interfaces", classInfo.getInterfaces());
		result.put("sourceFileName", classInfo.getSourceFileName());

		// Fields
		List<Map<String, Object>> fields = classInfo.getFields().stream()
				.map(field -> {
					LinkedHashMap<String, Object> f = new LinkedHashMap<>();
					f.put("name", field.getName());
					f.put("descriptor", field.getDescriptor());
					f.put("access", field.getAccess());
					return (Map<String, Object>) f;
				})
				.collect(Collectors.toList());
		result.put("fields", fields);

		// Methods
		List<Map<String, Object>> methods = classInfo.getMethods().stream()
				.map(method -> {
					LinkedHashMap<String, Object> m = new LinkedHashMap<>();
					m.put("name", method.getName());
					m.put("descriptor", method.getDescriptor());
					m.put("access", method.getAccess());
					return (Map<String, Object>) m;
				})
				.collect(Collectors.toList());
		result.put("methods", methods);

		// Decompiled source (JVM classes only)
		if (classInfo.isJvmClass()) {
			JvmClassInfo jvmClassInfo = classInfo.asJvmClass();
			try {
				DecompileResult decompileResult = decompilerManager
						.decompile(workspace, jvmClassInfo)
						.get();

				if (decompileResult.getType() == DecompileResult.ResultType.SUCCESS) {
					result.put("decompiler", decompilerManager.getTargetJvmDecompiler().getName());
					result.put("source", decompileResult.getText());
				} else {
					result.put("decompileError", "Decompilation failed");
					if (decompileResult.getException() != null) {
						result.put("decompileErrorDetail", decompileResult.getException().getMessage());
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				result.put("decompileError", "Decompilation interrupted");
			} catch (ExecutionException e) {
				result.put("decompileError", "Decompilation failed: " +
						(e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
			}
		} else {
			result.put("decompileError", "Not a JVM class; decompilation not available");
		}

		return serializeJson(result);
	}

	// ---- Helpers ----

	/**
	 * Extract the class name from a resource URI of the form {@code recaf://class/{name}}.
	 *
	 * @param uri The full resource URI.
	 * @return The extracted class name.
	 * @throws IllegalArgumentException if the URI is malformed.
	 */
	private static String extractClassName(String uri) {
		if (uri == null || !uri.startsWith(CLASS_URI_PREFIX)) {
			throw new IllegalArgumentException("Invalid class resource URI: " + uri);
		}
		String name = uri.substring(CLASS_URI_PREFIX.length());
		if (name.isEmpty()) {
			throw new IllegalArgumentException("Class name is empty in URI: " + uri);
		}
		return name;
	}

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
