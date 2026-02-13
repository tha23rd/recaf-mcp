package dev.recafmcp.providers;

import dev.recafmcp.util.ClassResolver;
import dev.recafmcp.util.ErrorHelper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.transform.ClassTransformer;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformResult;
import software.coley.recaf.services.transform.TransformationApplier;
import software.coley.recaf.services.transform.TransformationApplierService;
import software.coley.recaf.services.transform.TransformationException;
import software.coley.recaf.services.transform.TransformationManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP tool provider for class transformation operations.
 * <p>
 * Provides tools for listing, applying, previewing, and undoing JVM class
 * transformations through Recaf's {@link TransformationManager} and
 * {@link TransformationApplierService}.
 * <p>
 * Undo support is implemented via local bytecode snapshots taken before
 * each transform-apply or transform-apply-batch operation.
 */
public class TransformToolProvider extends AbstractToolProvider {
	private static final Logger logger = Logging.get(TransformToolProvider.class);

	private final TransformationManager transformationManager;
	private final TransformationApplierService transformationApplierService;

	/**
	 * Stores a snapshot of class bytecodes taken immediately before the last
	 * successful apply/batch operation. Maps internal class name to the
	 * original bytecode bytes. Cleared on each new apply so only the most
	 * recent operation can be undone.
	 */
	private final Map<String, byte[]> lastTransformSnapshot = new ConcurrentHashMap<>();

	public TransformToolProvider(McpSyncServer server,
	                             WorkspaceManager workspaceManager,
	                             TransformationManager transformationManager,
	                             TransformationApplierService transformationApplierService) {
		super(server, workspaceManager);
		this.transformationManager = transformationManager;
		this.transformationApplierService = transformationApplierService;
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

			Set<Class<? extends JvmClassTransformer>> transformerClasses =
					transformationManager.getJvmClassTransformers();

			List<Map<String, Object>> transformerList = new ArrayList<>();
			for (Class<? extends JvmClassTransformer> clazz : transformerClasses) {
				LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
				entry.put("className", clazz.getName());
				try {
					JvmClassTransformer instance = transformationManager.newJvmTransformer(clazz);
					entry.put("name", instance.name());

					// Include dependency/ordering info if present
					Set<Class<? extends ClassTransformer>> deps = instance.dependencies();
					if (deps != null && !deps.isEmpty()) {
						entry.put("dependencies", deps.stream()
								.map(Class::getSimpleName)
								.toList());
					}
					Set<Class<? extends ClassTransformer>> predecessors = instance.recommendedPredecessors();
					if (predecessors != null && !predecessors.isEmpty()) {
						entry.put("recommendedPredecessors", predecessors.stream()
								.map(Class::getSimpleName)
								.toList());
					}
					Set<Class<? extends ClassTransformer>> successors = instance.recommendedSuccessors();
					if (successors != null && !successors.isEmpty()) {
						entry.put("recommendedSuccessors", successors.stream()
								.map(Class::getSimpleName)
								.toList());
					}
				} catch (TransformationException e) {
					entry.put("name", clazz.getSimpleName());
					entry.put("error", "Could not instantiate: " + e.getMessage());
				}
				transformerList.add(entry);
			}

			// Also list third-party transformers
			Set<Class<? extends JvmClassTransformer>> thirdParty =
					transformationManager.getThirdPartyJvmTransformers();

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("transformers", transformerList);
			result.put("count", transformerList.size());
			result.put("thirdPartyCount", thirdParty != null ? thirdParty.size() : 0);
			return createJsonResult(result);
		});
	}

	// ---- transform-apply ----

	private void registerTransformApply() {
		Tool tool = Tool.builder()
				.name("transform-apply")
				.description("Apply a named transformer to all classes in the workspace. " +
						"Use transform-list to see available transformer names. " +
						"Optionally restrict to specific classes via targetClasses.")
				.inputSchema(createSchema(
						Map.of(
								"transformerName", stringParam("Name of the transformer to apply (as shown by transform-list)"),
								"targetClasses", stringParam("Optional comma-separated list of fully qualified class names to restrict the transform to (omit to transform all)")
						),
						List.of("transformerName")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();
			String transformerName = getString(args, "transformerName");
			String targetClasses = getOptionalString(args, "targetClasses", null);

			Class<? extends JvmClassTransformer> transformerClass = resolveTransformerByName(transformerName);
			if (transformerClass == null) {
				return createErrorResult(transformerNotFound(transformerName));
			}

			// Snapshot bytecodes before applying
			snapshotBytecodes(workspace, targetClasses);

			try {
				TransformationApplier applier = transformationApplierService.newApplierForCurrentWorkspace();
				JvmTransformResult transformResult = applier.transformJvm(List.of(transformerClass));

				// Apply the result to the workspace
				transformResult.apply();

				return buildApplyResult(transformerName, transformResult);
			} catch (TransformationException e) {
				logger.error("Transform '{}' failed", transformerName, e);
				return createErrorResult("Transform '" + transformerName + "' failed: " + e.getMessage());
			}
		});
	}

	// ---- transform-apply-batch ----

	private void registerTransformApplyBatch() {
		Tool tool = Tool.builder()
				.name("transform-apply-batch")
				.description("Apply multiple transformers in sequence to all workspace classes. " +
						"Transformers are automatically ordered based on their dependency/ordering declarations.")
				.inputSchema(createSchema(
						Map.of("transformerNames", stringParam("Comma-separated list of transformer names to apply (as shown by transform-list)")),
						List.of("transformerNames")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();
			String transformerNames = getString(args, "transformerNames");
			List<String> names = Arrays.stream(transformerNames.split(","))
					.map(String::trim)
					.filter(s -> !s.isEmpty())
					.toList();

			if (names.isEmpty()) {
				return createErrorResult("No transformer names provided.");
			}

			// Resolve all transformer classes by name
			List<Class<? extends JvmClassTransformer>> transformerClasses = new ArrayList<>();
			List<String> notFound = new ArrayList<>();
			for (String name : names) {
				Class<? extends JvmClassTransformer> clazz = resolveTransformerByName(name);
				if (clazz == null) {
					notFound.add(name);
				} else {
					transformerClasses.add(clazz);
				}
			}

			if (!notFound.isEmpty()) {
				return createErrorResult("Transformer(s) not found: " + notFound +
						". Use transform-list to see available transformers.");
			}

			// Snapshot bytecodes before applying
			snapshotBytecodes(workspace, null);

			try {
				TransformationApplier applier = transformationApplierService.newApplierForCurrentWorkspace();
				JvmTransformResult transformResult = applier.transformJvm(transformerClasses);

				// Apply the result to the workspace
				transformResult.apply();

				LinkedHashMap<String, Object> result = new LinkedHashMap<>();
				result.put("status", "success");
				result.put("transformersApplied", names);
				result.put("transformerCount", names.size());

				// Summarize per-transformer modifications
				Map<Class<? extends JvmClassTransformer>, Collection<ClassPathNode>> modPerTransformer =
						transformResult.getModifiedClassesPerTransformer();
				List<Map<String, Object>> perTransformer = new ArrayList<>();
				for (var entry : modPerTransformer.entrySet()) {
					LinkedHashMap<String, Object> tEntry = new LinkedHashMap<>();
					tEntry.put("transformer", entry.getKey().getSimpleName());
					tEntry.put("classesModified", entry.getValue().size());
					perTransformer.add(tEntry);
				}
				result.put("perTransformerResults", perTransformer);

				// Total modified
				result.put("totalClassesModified", transformResult.getTransformedClasses().size());

				// Failures
				Map<ClassPathNode, Map<Class<? extends JvmClassTransformer>, Throwable>> failures =
						transformResult.getTransformerFailures();
				if (failures != null && !failures.isEmpty()) {
					result.put("failureCount", failures.size());
					List<Map<String, Object>> failureList = buildFailureList(failures);
					result.put("failures", failureList);
				}

				// Classes removed
				Set<ClassPathNode> removed = transformResult.getClassesToRemove();
				if (removed != null && !removed.isEmpty()) {
					result.put("classesRemoved", removed.stream()
							.map(cpn -> cpn.getValue().getName())
							.toList());
				}

				result.put("undoAvailable", !lastTransformSnapshot.isEmpty());
				return createJsonResult(result);
			} catch (TransformationException e) {
				logger.error("Batch transform failed", e);
				return createErrorResult("Batch transform failed: " + e.getMessage());
			}
		});
	}

	// ---- transform-preview ----

	private void registerTransformPreview() {
		Tool tool = Tool.builder()
				.name("transform-preview")
				.description("Preview the effect of a transformer without applying changes. " +
						"Shows which classes would be modified, any mappings generated, and failure details.")
				.inputSchema(createSchema(
						Map.of(
								"transformerName", stringParam("Name of the transformer to preview"),
								"className", stringParam("Optional fully qualified class name to focus the preview on (omit to preview all)")
						),
						List.of("transformerName")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			requireWorkspace();
			String transformerName = getString(args, "transformerName");
			String className = getOptionalString(args, "className", null);

			Class<? extends JvmClassTransformer> transformerClass = resolveTransformerByName(transformerName);
			if (transformerClass == null) {
				return createErrorResult(transformerNotFound(transformerName));
			}

			// If a specific class was requested, verify it exists
			if (className != null) {
				Workspace workspace = requireWorkspace();
				ClassPathNode pathNode = ClassResolver.resolveClass(workspace, className);
				if (pathNode == null) {
					return createErrorResult(ErrorHelper.classNotFound(className, workspace));
				}
			}

			try {
				TransformationApplier applier = transformationApplierService.newApplierForCurrentWorkspace();
				JvmTransformResult transformResult = applier.transformJvm(List.of(transformerClass));

				// Do NOT apply -- this is preview only

				LinkedHashMap<String, Object> result = new LinkedHashMap<>();
				result.put("status", "preview");
				result.put("transformer", transformerName);

				// Modified classes
				Map<ClassPathNode, JvmClassInfo> transformed = transformResult.getTransformedClasses();
				List<String> modifiedClassNames = new ArrayList<>();
				for (var entry : transformed.entrySet()) {
					modifiedClassNames.add(entry.getValue().getName());
				}

				// If a specific class filter was given, filter results
				if (className != null) {
					String normalizedFilter = ClassResolver.normalizeClassName(className);
					modifiedClassNames = modifiedClassNames.stream()
							.filter(n -> n.equals(normalizedFilter))
							.toList();
				}

				result.put("classesModified", modifiedClassNames);
				result.put("classesModifiedCount", modifiedClassNames.size());

				// Mappings that would be applied
				var mappings = transformResult.getMappingsToApply();
				if (mappings != null && !mappings.isEmpty()) {
					LinkedHashMap<String, Object> mappingInfo = new LinkedHashMap<>();
					mappingInfo.put("classRenames", mappings.getClasses().size());
					mappingInfo.put("fieldRenames", mappings.getFields().size());
					mappingInfo.put("methodRenames", mappings.getMethods().size());
					result.put("mappings", mappingInfo);
				}

				// Classes to remove
				Set<ClassPathNode> toRemove = transformResult.getClassesToRemove();
				if (toRemove != null && !toRemove.isEmpty()) {
					result.put("classesToRemove", toRemove.stream()
							.map(cpn -> cpn.getValue().getName())
							.toList());
				}

				// Failures
				Map<ClassPathNode, Map<Class<? extends JvmClassTransformer>, Throwable>> failures =
						transformResult.getTransformerFailures();
				if (failures != null && !failures.isEmpty()) {
					result.put("failureCount", failures.size());
					List<Map<String, Object>> failureList = buildFailureList(failures);
					result.put("failures", failureList);
				}

				result.put("note", "This is a preview only. No changes have been applied. Use transform-apply to apply.");
				return createJsonResult(result);
			} catch (TransformationException e) {
				logger.error("Transform preview '{}' failed", transformerName, e);
				return createErrorResult("Transform preview failed: " + e.getMessage());
			}
		});
	}

	// ---- transform-undo ----

	private void registerTransformUndo() {
		Tool tool = Tool.builder()
				.name("transform-undo")
				.description("Undo the last transformation applied via transform-apply or transform-apply-batch. " +
						"Restores classes to the bytecodes captured before the most recent apply operation.")
				.inputSchema(createSchema(Map.of(), List.of()))
				.build();
		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();

			if (lastTransformSnapshot.isEmpty()) {
				return createErrorResult("No transform to undo. Undo is only available immediately after " +
						"a transform-apply or transform-apply-batch operation.");
			}

			JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
			List<String> restoredClasses = new ArrayList<>();
			List<String> failedClasses = new ArrayList<>();

			for (Map.Entry<String, byte[]> entry : lastTransformSnapshot.entrySet()) {
				String internalName = entry.getKey();
				byte[] originalBytecode = entry.getValue();
				try {
					JvmClassInfo restored = new JvmClassInfoBuilder(originalBytecode).build();
					bundle.put(restored);
					restoredClasses.add(internalName);
				} catch (Exception e) {
					logger.error("Failed to restore class '{}' during undo", internalName, e);
					failedClasses.add(internalName);
				}
			}

			// Clear the snapshot after undo
			lastTransformSnapshot.clear();

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", failedClasses.isEmpty() ? "success" : "partial");
			result.put("restoredClasses", restoredClasses);
			result.put("restoredCount", restoredClasses.size());
			if (!failedClasses.isEmpty()) {
				result.put("failedClasses", failedClasses);
				result.put("failedCount", failedClasses.size());
			}
			result.put("undoAvailable", false);
			return createJsonResult(result);
		});
	}

	// ---- Helper methods ----

	/**
	 * Resolve a transformer class by its human-readable name (from {@link ClassTransformer#name()})
	 * or by its simple class name.
	 *
	 * @param name the transformer name to look up
	 * @return the matching transformer class, or {@code null} if not found
	 */
	private Class<? extends JvmClassTransformer> resolveTransformerByName(String name) {
		if (name == null || name.isBlank()) return null;

		Set<Class<? extends JvmClassTransformer>> allTransformers =
				transformationManager.getJvmClassTransformers();

		// First pass: match by ClassTransformer.name() (case-insensitive)
		for (Class<? extends JvmClassTransformer> clazz : allTransformers) {
			try {
				JvmClassTransformer instance = transformationManager.newJvmTransformer(clazz);
				if (instance.name().equalsIgnoreCase(name)) {
					return clazz;
				}
			} catch (TransformationException e) {
				// Skip transformers that fail to instantiate
				logger.debug("Skipping transformer '{}' during name resolution: {}", clazz.getSimpleName(), e.getMessage());
			}
		}

		// Second pass: match by simple class name (case-insensitive)
		for (Class<? extends JvmClassTransformer> clazz : allTransformers) {
			if (clazz.getSimpleName().equalsIgnoreCase(name)) {
				return clazz;
			}
		}

		// Third pass: match by full class name (case-insensitive)
		for (Class<? extends JvmClassTransformer> clazz : allTransformers) {
			if (clazz.getName().equalsIgnoreCase(name)) {
				return clazz;
			}
		}

		return null;
	}

	/**
	 * Snapshot the bytecodes of classes in the primary bundle before a transform.
	 * If {@code targetClasses} is non-null, only snapshot those classes;
	 * otherwise snapshot all JVM classes.
	 */
	private void snapshotBytecodes(Workspace workspace, String targetClasses) {
		lastTransformSnapshot.clear();
		JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();

		if (targetClasses != null) {
			// Snapshot only specified classes
			for (String raw : targetClasses.split(",")) {
				String name = ClassResolver.normalizeClassName(raw.trim());
				if (name == null || name.isEmpty()) continue;
				ClassPathNode cpn = workspace.findJvmClass(name);
				if (cpn != null) {
					ClassInfo ci = cpn.getValue();
					if (ci.isJvmClass()) {
						lastTransformSnapshot.put(ci.getName(), ci.asJvmClass().getBytecode());
					}
				}
			}
		} else {
			// Snapshot all JVM classes in the primary resource
			bundle.forEach(cls -> lastTransformSnapshot.put(cls.getName(), cls.getBytecode()));
		}
		logger.debug("Snapshotted {} classes before transform", lastTransformSnapshot.size());
	}

	/**
	 * Build the JSON result for a single-transformer apply operation.
	 */
	private LinkedHashMap<String, Object> buildApplyResultMap(String transformerName,
	                                                          JvmTransformResult transformResult) {
		LinkedHashMap<String, Object> result = new LinkedHashMap<>();
		result.put("status", "success");
		result.put("transformer", transformerName);

		// Modified classes
		Map<ClassPathNode, JvmClassInfo> transformed = transformResult.getTransformedClasses();
		List<String> modifiedNames = transformed.values().stream()
				.map(JvmClassInfo::getName)
				.toList();
		result.put("classesModified", modifiedNames);
		result.put("classesModifiedCount", modifiedNames.size());

		// Mappings
		var mappings = transformResult.getMappingsToApply();
		if (mappings != null && !mappings.isEmpty()) {
			LinkedHashMap<String, Object> mappingInfo = new LinkedHashMap<>();
			mappingInfo.put("classRenames", mappings.getClasses().size());
			mappingInfo.put("fieldRenames", mappings.getFields().size());
			mappingInfo.put("methodRenames", mappings.getMethods().size());
			result.put("mappings", mappingInfo);
		}

		// Classes removed
		Set<ClassPathNode> removed = transformResult.getClassesToRemove();
		if (removed != null && !removed.isEmpty()) {
			result.put("classesRemoved", removed.stream()
					.map(cpn -> cpn.getValue().getName())
					.toList());
		}

		// Failures
		Map<ClassPathNode, Map<Class<? extends JvmClassTransformer>, Throwable>> failures =
				transformResult.getTransformerFailures();
		if (failures != null && !failures.isEmpty()) {
			result.put("failureCount", failures.size());
			result.put("failures", buildFailureList(failures));
		}

		result.put("undoAvailable", !lastTransformSnapshot.isEmpty());
		return result;
	}

	/**
	 * Build and return the apply result as a CallToolResult.
	 */
	private io.modelcontextprotocol.spec.McpSchema.CallToolResult buildApplyResult(
			String transformerName, JvmTransformResult transformResult) {
		return createJsonResult(buildApplyResultMap(transformerName, transformResult));
	}

	/**
	 * Build a list of failure detail maps from the transformer failure map.
	 */
	private static List<Map<String, Object>> buildFailureList(
			Map<ClassPathNode, Map<Class<? extends JvmClassTransformer>, Throwable>> failures) {
		List<Map<String, Object>> failureList = new ArrayList<>();
		for (var entry : failures.entrySet()) {
			String failedClass = entry.getKey().getValue().getName();
			for (var tEntry : entry.getValue().entrySet()) {
				LinkedHashMap<String, Object> failMap = new LinkedHashMap<>();
				failMap.put("className", failedClass);
				failMap.put("transformer", tEntry.getKey().getSimpleName());
				failMap.put("error", tEntry.getValue().getMessage());
				failureList.add(failMap);
			}
		}
		return failureList;
	}

	/**
	 * Build an error message when a transformer name is not found,
	 * listing available transformers.
	 */
	private String transformerNotFound(String name) {
		List<String> available = new ArrayList<>();
		for (Class<? extends JvmClassTransformer> clazz : transformationManager.getJvmClassTransformers()) {
			try {
				JvmClassTransformer instance = transformationManager.newJvmTransformer(clazz);
				available.add(instance.name());
			} catch (TransformationException e) {
				available.add(clazz.getSimpleName() + " (instantiation failed)");
			}
		}
		return "Transformer '" + name + "' not found. Available transformers: " + available;
	}
}
