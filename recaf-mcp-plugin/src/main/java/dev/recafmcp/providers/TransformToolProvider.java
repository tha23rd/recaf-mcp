package dev.recafmcp.providers;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.workspace.WorkspaceManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool provider for class transformation operations.
 * <p>
 * Provides tools for listing, applying, previewing, and undoing JVM class
 * transformations through Recaf's transformation pipeline.
 * <p>
 * All tools are currently stubbed pending integration with
 * {@code TransformationManager} and {@code TransformationApplier}.
 */
public class TransformToolProvider extends AbstractToolProvider {
	private static final Logger logger = Logging.get(TransformToolProvider.class);

	public TransformToolProvider(McpSyncServer server, WorkspaceManager workspaceManager) {
		super(server, workspaceManager);
	}

	@Override
	public void registerTools() {
		registerTransformList();
		registerTransformApply();
		registerTransformApplyBatch();
		registerTransformPreview();
		registerTransformUndo();
	}

	// ---- transform-list ----

	private void registerTransformList() {
		Tool tool = Tool.builder()
				.name("transform-list")
				.description("List all registered JVM class transformers available in the workspace")
				.inputSchema(createSchema(Map.of(), List.of()))
				.build();
		registerTool(tool, (exchange, args) -> {
			requireWorkspace();
			return createTextResult("Lists all registered JVM class transformers with their names and descriptions. " +
					"This will query Recaf's TransformationManager for available transformers.");
		});
	}

	// ---- transform-apply ----

	private void registerTransformApply() {
		Tool tool = Tool.builder()
				.name("transform-apply")
				.description("Apply a single transformer to workspace classes")
				.inputSchema(createSchema(
						Map.of(
								"transformerName", stringParam("Name of the transformer to apply"),
								"targetClasses", stringParam("Optional comma-separated list of fully qualified class names to transform (omit to transform all)")
						),
						List.of("transformerName")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			requireWorkspace();
			String transformerName = getString(args, "transformerName");
			String targetClasses = getOptionalString(args, "targetClasses", null);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("transformer", transformerName);
			result.put("targetClasses", targetClasses);
			result.put("status", "stub");
			result.put("message", "Applies a single transformer to the workspace classes. " +
					"This will be implemented once TransformationManager and TransformationApplier are integrated.");
			return createJsonResult(result);
		});
	}

	// ---- transform-apply-batch ----

	private void registerTransformApplyBatch() {
		Tool tool = Tool.builder()
				.name("transform-apply-batch")
				.description("Apply multiple transformers in sequence to workspace classes")
				.inputSchema(createSchema(
						Map.of("transformerNames", stringParam("Comma-separated list of transformer names to apply in order")),
						List.of("transformerNames")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			requireWorkspace();
			String transformerNames = getString(args, "transformerNames");

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("transformerNames", transformerNames);
			result.put("status", "stub");
			result.put("message", "Applies multiple transformers in sequence. " +
					"This will be implemented once TransformationManager and TransformationApplier are integrated.");
			return createJsonResult(result);
		});
	}

	// ---- transform-preview ----

	private void registerTransformPreview() {
		Tool tool = Tool.builder()
				.name("transform-preview")
				.description("Preview the effect of a transformer on a single class by showing a before/after diff")
				.inputSchema(createSchema(
						Map.of(
								"transformerName", stringParam("Name of the transformer to preview"),
								"className", stringParam("Fully qualified name of the class to preview the transformation on")
						),
						List.of("transformerName", "className")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			requireWorkspace();
			String transformerName = getString(args, "transformerName");
			String className = getString(args, "className");

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("transformer", transformerName);
			result.put("className", className);
			result.put("status", "stub");
			result.put("message", "Previews the effect of a transformer on a class by decompiling before and after, " +
					"returning a unified diff. This will be implemented once the transformation and decompilation APIs are integrated.");
			return createJsonResult(result);
		});
	}

	// ---- transform-undo ----

	private void registerTransformUndo() {
		Tool tool = Tool.builder()
				.name("transform-undo")
				.description("Revert the last transformation applied to the workspace")
				.inputSchema(createSchema(Map.of(), List.of()))
				.build();
		registerTool(tool, (exchange, args) -> {
			requireWorkspace();
			return createTextResult("Reverts the last transformation using workspace history. " +
					"This will be implemented once integrated with Recaf's workspace history/undo API.");
		});
	}
}
