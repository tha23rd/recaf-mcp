package dev.recafmcp.providers;

import dev.recafmcp.server.JsonResponseSerializer;
import dev.recafmcp.server.ResponseSerializer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Base class for MCP tool providers. Provides common utilities for building
 * JSON schemas, extracting parameters, creating results, and registering tools
 * with standardized error handling.
 */
public abstract class AbstractToolProvider implements ToolProvider {
	private static final Logger logger = Logging.get(AbstractToolProvider.class);

	protected final McpSyncServer server;
	protected final WorkspaceManager workspaceManager;
	protected ResponseSerializer responseSerializer;

	protected AbstractToolProvider(McpSyncServer server, WorkspaceManager workspaceManager) {
		this.server = server;
		this.workspaceManager = workspaceManager;
		this.responseSerializer = new JsonResponseSerializer();
	}

	// ---- Schema helpers ----

	/**
	 * Create a JSON Schema for tool input parameters.
	 *
	 * @param properties Map of property name to property schema map.
	 * @param required   List of required property names.
	 * @return A {@link JsonSchema} suitable for use in a {@link Tool}.
	 */
	protected static JsonSchema createSchema(Map<String, Object> properties, List<String> required) {
		return new JsonSchema("object", properties, required, false, null, null);
	}

	/**
	 * Create a schema entry for a string parameter.
	 *
	 * @param description Description of the parameter.
	 * @return Property schema map with type "string".
	 */
	protected static Map<String, Object> stringParam(String description) {
		return Map.of("type", "string", "description", description);
	}

	/**
	 * Create a schema entry for a string parameter with an enumeration of allowed values.
	 *
	 * @param description Description of the parameter.
	 * @param values      Allowed values.
	 * @return Property schema map with type "string" and enum constraint.
	 */
	protected static Map<String, Object> stringEnumParam(String description, List<String> values) {
		return Map.of("type", "string", "description", description, "enum", values);
	}

	/**
	 * Create a schema entry for an integer parameter.
	 *
	 * @param description Description of the parameter.
	 * @return Property schema map with type "integer".
	 */
	protected static Map<String, Object> intParam(String description) {
		return Map.of("type", "integer", "description", description);
	}

	/**
	 * Create a schema entry for a boolean parameter.
	 *
	 * @param description Description of the parameter.
	 * @return Property schema map with type "boolean".
	 */
	protected static Map<String, Object> boolParam(String description) {
		return Map.of("type", "boolean", "description", description);
	}

	/**
	 * Create a schema entry for an array parameter with items of the given type.
	 *
	 * @param description Description of the parameter.
	 * @param itemType    JSON schema type of each array element (e.g., "string").
	 * @return Property schema map with type "array".
	 */
	protected static Map<String, Object> arrayParam(String description, String itemType) {
		return Map.of(
				"type", "array",
				"description", description,
				"items", Map.of("type", itemType)
		);
	}

	// ---- Parameter extraction ----

	/**
	 * Get a required string parameter from the tool arguments.
	 *
	 * @param args Argument map provided by the MCP client.
	 * @param key  Parameter name.
	 * @return The string value.
	 * @throws IllegalArgumentException if the parameter is missing.
	 */
	protected static String getString(Map<String, Object> args, String key) {
		Object value = args.get(key);
		if (value == null) {
			throw new IllegalArgumentException("Missing required parameter: " + key);
		}
		return value.toString();
	}

	/**
	 * Get an optional string parameter with a default value.
	 *
	 * @param args         Argument map provided by the MCP client.
	 * @param key          Parameter name.
	 * @param defaultValue Value to return if the parameter is absent.
	 * @return The string value, or the default.
	 */
	protected static String getOptionalString(Map<String, Object> args, String key, String defaultValue) {
		Object value = args.get(key);
		return value != null ? value.toString() : defaultValue;
	}

	/**
	 * Get an optional integer parameter with a default value.
	 *
	 * @param args         Argument map provided by the MCP client.
	 * @param key          Parameter name.
	 * @param defaultValue Value to return if the parameter is absent.
	 * @return The integer value, or the default.
	 */
	protected static int getInt(Map<String, Object> args, String key, int defaultValue) {
		Object value = args.get(key);
		if (value == null) {
			return defaultValue;
		}
		if (value instanceof Number number) {
			return number.intValue();
		}
		try {
			return Integer.parseInt(value.toString());
		} catch (NumberFormatException e) {
			logger.warn("Invalid integer for parameter '{}': {}", key, value);
			return defaultValue;
		}
	}

	/**
	 * Get an optional boolean parameter with a default value.
	 *
	 * @param args         Argument map provided by the MCP client.
	 * @param key          Parameter name.
	 * @param defaultValue Value to return if the parameter is absent.
	 * @return The boolean value, or the default.
	 */
	protected static boolean getBoolean(Map<String, Object> args, String key, boolean defaultValue) {
		Object value = args.get(key);
		if (value == null) {
			return defaultValue;
		}
		if (value instanceof Boolean bool) {
			return bool;
		}
		return Boolean.parseBoolean(value.toString());
	}

	/**
	 * Get a required string list parameter from the tool arguments.
	 * The MCP protocol delivers JSON arrays as {@code List<String>}.
	 *
	 * @param args Argument map provided by the MCP client.
	 * @param key  Parameter name.
	 * @return The list of strings.
	 * @throws IllegalArgumentException if the parameter is missing or not a list.
	 */
	@SuppressWarnings("unchecked")
	protected static List<String> getStringList(Map<String, Object> args, String key) {
		Object value = args.get(key);
		if (value == null) {
			throw new IllegalArgumentException("Missing required parameter: " + key);
		}
		if (value instanceof List<?> list) {
			List<String> result = new java.util.ArrayList<>(list.size());
			for (Object item : list) {
				if (item == null) {
					throw new IllegalArgumentException("Parameter '" + key + "' contains null elements");
				}
				result.add(item.toString());
			}
			return result;
		}
		throw new IllegalArgumentException("Parameter '" + key + "' must be an array of strings");
	}

	// ---- Workspace access ----

	/**
	 * Get the current workspace, throwing a descriptive error if none is open.
	 *
	 * @return The current {@link Workspace}.
	 * @throws IllegalStateException if no workspace is currently open.
	 */
	protected Workspace requireWorkspace() {
		Workspace workspace = workspaceManager.getCurrent();
		if (workspace == null) {
			throw new IllegalStateException(
					"No workspace is currently open in Recaf. " +
					"Open a JAR, APK, or other supported file before using this tool."
			);
		}
		return workspace;
	}

	// ---- Result builders ----

	/**
	 * Create a successful text result.
	 *
	 * @param text Text content for the response.
	 * @return A {@link CallToolResult} containing the text.
	 */
	protected static CallToolResult createTextResult(String text) {
		return CallToolResult.builder()
				.addTextContent(text)
				.build();
	}

	/**
	 * Serialize an object using the configured {@link ResponseSerializer} and
	 * return it as a tool result.
	 *
	 * @param data Object to serialize.
	 * @return A {@link CallToolResult} containing the serialized data.
	 */
	protected CallToolResult createJsonResult(Object data) {
		try {
			String serialized = responseSerializer.serialize(data);
			return CallToolResult.builder()
					.addTextContent(serialized)
					.build();
		} catch (Exception e) {
			logger.error("Failed to serialize response", e);
			return createErrorResult("Serialization error: " + e.getMessage());
		}
	}

	/**
	 * Create an error result.
	 *
	 * @param message Human-readable error message.
	 * @return A {@link CallToolResult} flagged as an error.
	 */
	protected static CallToolResult createErrorResult(String message) {
		return CallToolResult.builder()
				.addTextContent("Error: " + message)
				.isError(true)
				.build();
	}

	// ---- Tool registration ----

	/**
	 * Register a tool with the MCP server, wrapping the handler with
	 * standardized error handling. Exceptions thrown by the handler are
	 * caught and returned as error results rather than propagating.
	 *
	 * @param tool    The tool definition (name, description, schema).
	 * @param handler The handler function that processes tool calls.
	 *                Receives the exchange and the arguments map extracted
	 *                from the {@link CallToolRequest}.
	 */
	protected void registerTool(Tool tool,
	                            BiFunction<McpSyncServerExchange, Map<String, Object>, CallToolResult> handler) {
		BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> safeHandler =
				(exchange, request) -> {
					try {
						Map<String, Object> args = request.arguments();
						if (args == null) {
							args = Collections.emptyMap();
						}
						return handler.apply(exchange, args);
					} catch (IllegalArgumentException e) {
						logger.debug("Bad request for tool '{}': {}", tool.name(), e.getMessage());
						return createErrorResult(e.getMessage());
					} catch (IllegalStateException e) {
						logger.debug("State error for tool '{}': {}", tool.name(), e.getMessage());
						return createErrorResult(e.getMessage());
					} catch (Exception e) {
						logger.error("Unexpected error in tool '{}'", tool.name(), e);
						return createErrorResult("Internal error: " + e.getMessage());
					}
				};

		server.addTool(SyncToolSpecification.builder()
				.tool(tool)
				.callHandler(safeHandler)
				.build());
		logger.debug("Registered tool: {}", tool.name());
	}

	/**
	 * Override the response serialization format used by this provider.
	 *
	 * @param serializer The serializer to use.
	 */
	public void setResponseSerializer(ResponseSerializer serializer) {
		this.responseSerializer = serializer;
	}
}
