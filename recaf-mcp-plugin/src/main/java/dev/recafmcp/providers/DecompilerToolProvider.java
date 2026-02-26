package dev.recafmcp.providers;

import dev.recafmcp.cache.DecompileCache;
import dev.recafmcp.cache.WorkspaceRevisionTracker;
import dev.recafmcp.util.ClassResolver;
import dev.recafmcp.util.ErrorHelper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP tool provider for decompilation operations.
 * <p>
 * Provides tools for decompiling classes and methods, listing and switching
 * decompilers, and computing diffs between decompilation outputs.
 */
public class DecompilerToolProvider extends AbstractToolProvider {
	private static final Logger logger = Logging.get(DecompilerToolProvider.class);

	private final DecompilerManager decompilerManager;
	private final DecompileCache decompileCache;
	private final WorkspaceRevisionTracker revisionTracker;

	public DecompilerToolProvider(McpSyncServer server,
	                              WorkspaceManager workspaceManager,
	                              DecompilerManager decompilerManager,
	                              DecompileCache decompileCache,
	                              WorkspaceRevisionTracker revisionTracker) {
		super(server, workspaceManager);
		this.decompilerManager = decompilerManager;
		this.decompileCache = decompileCache;
		this.revisionTracker = revisionTracker;
	}

	@Override
	public void registerTools() {
		registerDecompileClass();
		registerDecompileMethod();
		registerDecompilerList();
		registerDecompilerSet();
		registerDecompileDiff();
	}

	// ---- decompile-class ----

	private void registerDecompileClass() {
		Tool tool = Tool.builder()
				.name("decompile-class")
				.description("Decompile a JVM class to Java source code using the active decompiler")
				.inputSchema(createSchema(
						Map.of("className", stringParam("Fully qualified class name (dot or slash notation)")),
						List.of("className")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			String className = getString(args, "className");
			Workspace workspace = requireWorkspace();

			ClassPathNode pathNode = ClassResolver.resolveClass(workspace, className);
			if (pathNode == null) {
				return createErrorResult(ErrorHelper.classNotFound(className, workspace));
			}

			ClassInfo classInfo = pathNode.getValue();
			if (!classInfo.isJvmClass()) {
				return createErrorResult("Class '" + className + "' is not a JVM class and cannot be decompiled with the JVM decompiler.");
			}

			JvmClassInfo jvmClassInfo = classInfo.asJvmClass();
			String source;
			try {
				source = decompileSource(workspace, jvmClassInfo);
			} catch (RuntimeException e) {
				return createErrorResult("Decompilation failed for class '" + className + "': " + e.getMessage());
			}

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("className", jvmClassInfo.getName());
			result.put("decompiler", decompilerManager.getTargetJvmDecompiler().getName());
			result.put("source", source);
			return createJsonResult(result);
		});
	}

	// ---- decompile-method ----

	private void registerDecompileMethod() {
		Tool tool = Tool.builder()
				.name("decompile-method")
				.description("Decompile a specific method from a JVM class. Decompiles the full class then extracts the method source.")
				.inputSchema(createSchema(
						Map.of(
								"className", stringParam("Fully qualified class name (dot or slash notation)"),
								"methodName", stringParam("Name of the method to extract"),
								"methodDescriptor", stringParam("Optional JVM method descriptor to disambiguate overloads (e.g. '(Ljava/lang/String;)V')")
						),
						List.of("className", "methodName")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			String className = getString(args, "className");
			String methodName = getString(args, "methodName");
			String methodDescriptor = getOptionalString(args, "methodDescriptor", null);
			Workspace workspace = requireWorkspace();

			ClassPathNode pathNode = ClassResolver.resolveClass(workspace, className);
			if (pathNode == null) {
				return createErrorResult(ErrorHelper.classNotFound(className, workspace));
			}

			ClassInfo classInfo = pathNode.getValue();
			if (!classInfo.isJvmClass()) {
				return createErrorResult("Class '" + className + "' is not a JVM class and cannot be decompiled with the JVM decompiler.");
			}

			JvmClassInfo jvmClassInfo = classInfo.asJvmClass();
			String source;
			try {
				source = decompileSource(workspace, jvmClassInfo);
			} catch (RuntimeException e) {
				return createErrorResult("Decompilation failed for class '" + className + "': " + e.getMessage());
			}
			List<String> methodSources = extractMethodSources(source, methodName);

			if (methodSources.isEmpty()) {
				return createErrorResult("Method '" + methodName + "' not found in decompiled source of '" + className + "'.");
			}

			// If descriptor is provided, try to narrow down by matching the descriptor pattern in source
			if (methodDescriptor != null && methodSources.size() > 1) {
				List<String> filtered = filterByDescriptor(methodSources, methodDescriptor);
				if (!filtered.isEmpty()) {
					methodSources = filtered;
				}
			}

			if (methodSources.size() > 1 && methodDescriptor == null) {
				LinkedHashMap<String, Object> result = new LinkedHashMap<>();
				result.put("className", jvmClassInfo.getName());
				result.put("methodName", methodName);
				result.put("ambiguous", true);
				result.put("matchCount", methodSources.size());
				result.put("message", "Multiple methods named '" + methodName + "' found. Provide a methodDescriptor to disambiguate.");
				result.put("matches", methodSources);
				return createJsonResult(result);
			}

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("className", jvmClassInfo.getName());
			result.put("methodName", methodName);
			result.put("decompiler", decompilerManager.getTargetJvmDecompiler().getName());
			result.put("source", methodSources.getFirst());
			return createJsonResult(result);
		});
	}

	// ---- decompiler-list ----

	private void registerDecompilerList() {
		Tool tool = Tool.builder()
				.name("decompiler-list")
				.description("List all available JVM decompilers with their name, version, and active status")
				.inputSchema(createSchema(Map.of(), List.of()))
				.build();
		registerTool(tool, (exchange, args) -> {
			JvmDecompiler active = decompilerManager.getTargetJvmDecompiler();
			String activeName = active != null ? active.getName() : null;

			List<Map<String, Object>> decompilers = new ArrayList<>();
			for (JvmDecompiler decompiler : decompilerManager.getJvmDecompilers()) {
				LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
				entry.put("name", decompiler.getName());
				entry.put("version", decompiler.getVersion());
				entry.put("active", decompiler.getName().equals(activeName));
				decompilers.add(entry);
			}

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("decompilers", decompilers);
			result.put("count", decompilers.size());
			return createJsonResult(result);
		});
	}

	// ---- decompiler-set ----

	private void registerDecompilerSet() {
		Tool tool = Tool.builder()
				.name("decompiler-set")
				.description("Set the active JVM decompiler by name")
				.inputSchema(createSchema(
						Map.of("decompilerName", stringParam("Name of the decompiler to activate")),
						List.of("decompilerName")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			String decompilerName = getString(args, "decompilerName");

			// Find the decompiler by name (case-insensitive)
			JvmDecompiler target = null;
			for (JvmDecompiler decompiler : decompilerManager.getJvmDecompilers()) {
				if (decompiler.getName().equalsIgnoreCase(decompilerName)) {
					target = decompiler;
					break;
				}
			}

			if (target == null) {
				List<String> available = decompilerManager.getJvmDecompilers().stream()
						.map(JvmDecompiler::getName)
						.toList();
				return createErrorResult("Decompiler '" + decompilerName + "' not found. Available: " + available);
			}

			// Set the preferred decompiler via the config observable
			decompilerManager.getServiceConfig().getPreferredJvmDecompiler().setValue(target.getName());

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "updated");
			result.put("activeDecompiler", target.getName());
			result.put("version", target.getVersion());
			return createJsonResult(result);
		});
	}

	// ---- decompile-diff ----

	private void registerDecompileDiff() {
		Tool tool = Tool.builder()
				.name("decompile-diff")
				.description("Decompile a class and compute a unified diff against previously captured source code")
				.inputSchema(createSchema(
						Map.of(
								"className", stringParam("Fully qualified class name (dot or slash notation)"),
								"beforeSource", stringParam("The previous decompilation source to diff against")
						),
						List.of("className", "beforeSource")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			String className = getString(args, "className");
			String beforeSource = getString(args, "beforeSource");
			Workspace workspace = requireWorkspace();

			ClassPathNode pathNode = ClassResolver.resolveClass(workspace, className);
			if (pathNode == null) {
				return createErrorResult(ErrorHelper.classNotFound(className, workspace));
			}

			ClassInfo classInfo = pathNode.getValue();
			if (!classInfo.isJvmClass()) {
				return createErrorResult("Class '" + className + "' is not a JVM class and cannot be decompiled with the JVM decompiler.");
			}

			JvmClassInfo jvmClassInfo = classInfo.asJvmClass();
			String afterSource;
			try {
				afterSource = decompileSource(workspace, jvmClassInfo);
			} catch (RuntimeException e) {
				return createErrorResult("Decompilation failed for class '" + className + "': " + e.getMessage());
			}
			String diff = computeUnifiedDiff(beforeSource, afterSource, className);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("className", jvmClassInfo.getName());
			result.put("decompiler", decompilerManager.getTargetJvmDecompiler().getName());
			result.put("hasChanges", !beforeSource.equals(afterSource));
			result.put("diff", diff);
			return createJsonResult(result);
		});
	}

	// ---- Decompilation helper ----

	/**
	 * Decompile a class, blocking until the result is available.
	 * Checked exceptions from {@code CompletableFuture.get()} are
	 * re-thrown as unchecked so they are caught by the
	 * {@link #registerTool} error wrapper.
	 */
	private String decompileSource(Workspace workspace, JvmClassInfo jvmClassInfo) {
		String decompilerName = decompilerManager.getTargetJvmDecompiler().getName();
		long revision = revisionTracker.getRevision(workspace);
		DecompileCache.Key key = decompileCache.keyFor(workspace, revision, jvmClassInfo, decompilerName);
		return decompileCache.getOrLoad(key, () -> loadDecompileSource(workspace, jvmClassInfo));
	}

	private String loadDecompileSource(Workspace workspace, JvmClassInfo jvmClassInfo) {
		DecompileResult decompileResult = decompileResult(workspace, jvmClassInfo);
		if (decompileResult.getType() != DecompileResult.ResultType.SUCCESS || decompileResult.getText() == null) {
			String message = "Decompilation did not produce source output";
			if (decompileResult.getException() != null && decompileResult.getException().getMessage() != null) {
				message = decompileResult.getException().getMessage();
			}
			throw new RuntimeException(message);
		}
		return decompileResult.getText();
	}

	private DecompileResult decompileResult(Workspace workspace, JvmClassInfo jvmClassInfo) {
		try {
			return decompilerManager.decompile(workspace, jvmClassInfo).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Decompilation interrupted", e);
		} catch (ExecutionException e) {
			throw new RuntimeException("Decompilation failed", e.getCause());
		}
	}

	// ---- Helper methods ----

	/**
	 * Extract method source blocks from decompiled source by searching for
	 * method declarations matching the given name.
	 * <p>
	 * Uses brace counting to find the full method body.
	 *
	 * @param source     The full decompiled source of the class.
	 * @param methodName The method name to search for.
	 * @return A list of extracted method source blocks.
	 */
	private static List<String> extractMethodSources(String source, String methodName) {
		List<String> results = new ArrayList<>();
		String[] lines = source.split("\n");

		// Pattern matches method declarations: modifiers, return type, method name, opening paren
		Pattern methodPattern = Pattern.compile(
				"\\b" + Pattern.quote(methodName) + "\\s*\\("
		);

		for (int i = 0; i < lines.length; i++) {
			Matcher matcher = methodPattern.matcher(lines[i]);
			if (!matcher.find()) continue;

			// Check this looks like a method declaration (not a method call)
			// Method declarations typically have modifiers/return type before the name
			String trimmed = lines[i].trim();
			if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
				continue; // Skip comments
			}

			// Look for method declaration characteristics:
			// Should not start with a dot (method call on object)
			// Should not be inside a string or simple statement
			int nameIdx = lines[i].indexOf(methodName);
			if (nameIdx > 0) {
				char before = lines[i].charAt(nameIdx - 1);
				if (before == '.') continue; // This is a method call, not declaration
			}

			// Extract the method body by counting braces
			StringBuilder methodSource = new StringBuilder();
			int braceCount = 0;
			boolean foundOpenBrace = false;
			boolean started = false;

			// Include any annotations/comments above the method declaration
			int startLine = i;
			while (startLine > 0) {
				String prev = lines[startLine - 1].trim();
				if (prev.startsWith("@") || prev.startsWith("//") || prev.startsWith("/*")
						|| prev.startsWith("*") || prev.startsWith("*/") || prev.isEmpty()) {
					startLine--;
				} else {
					break;
				}
			}

			for (int j = startLine; j < lines.length; j++) {
				String line = lines[j];
				methodSource.append(line).append('\n');

				for (int c = 0; c < line.length(); c++) {
					char ch = line.charAt(c);
					if (ch == '{') {
						braceCount++;
						foundOpenBrace = true;
						started = true;
					} else if (ch == '}') {
						braceCount--;
					}
				}

				if (started && foundOpenBrace && braceCount == 0) {
					results.add(methodSource.toString().stripTrailing());
					break;
				}

				// Handle abstract/interface methods with no body (ends with ';')
				if (!foundOpenBrace && j >= i) {
					String t = line.trim();
					if (t.endsWith(";")) {
						results.add(methodSource.toString().stripTrailing());
						break;
					}
				}
			}
		}

		return results;
	}

	/**
	 * Filter extracted method sources by attempting to match a JVM descriptor
	 * pattern against parameter types in the source.
	 *
	 * @param methodSources    The method source blocks to filter.
	 * @param methodDescriptor The JVM method descriptor (e.g., "(Ljava/lang/String;)V").
	 * @return Filtered list of method sources that appear to match the descriptor.
	 */
	private static List<String> filterByDescriptor(List<String> methodSources, String methodDescriptor) {
		// Extract parameter types from descriptor for basic matching
		// e.g. "(Ljava/lang/String;I)V" -> ["String", "int"]
		List<String> paramTypes = parseDescriptorParamTypes(methodDescriptor);

		List<String> filtered = new ArrayList<>();
		for (String source : methodSources) {
			// Extract the parameter list from the first line containing '('
			int parenOpen = source.indexOf('(');
			int parenClose = source.indexOf(')', parenOpen);
			if (parenOpen < 0 || parenClose < 0) continue;

			String paramList = source.substring(parenOpen + 1, parenClose).trim();

			if (paramTypes.isEmpty() && paramList.isEmpty()) {
				filtered.add(source);
			} else if (!paramTypes.isEmpty()) {
				// Check if all expected param type simple names appear in the source param list
				boolean allMatch = true;
				for (String paramType : paramTypes) {
					if (!paramList.contains(paramType)) {
						allMatch = false;
						break;
					}
				}
				if (allMatch) {
					filtered.add(source);
				}
			}
		}
		return filtered;
	}

	/**
	 * Parse a JVM method descriptor's parameter section into simple type names.
	 *
	 * @param descriptor A JVM descriptor like "(Ljava/lang/String;I)V".
	 * @return List of simple type names like ["String", "int"].
	 */
	private static List<String> parseDescriptorParamTypes(String descriptor) {
		List<String> types = new ArrayList<>();
		if (descriptor == null || !descriptor.startsWith("(")) return types;

		int endParams = descriptor.indexOf(')');
		if (endParams < 0) return types;

		String params = descriptor.substring(1, endParams);
		int i = 0;
		while (i < params.length()) {
			char c = params.charAt(i);
			switch (c) {
				case 'B' -> { types.add("byte"); i++; }
				case 'C' -> { types.add("char"); i++; }
				case 'D' -> { types.add("double"); i++; }
				case 'F' -> { types.add("float"); i++; }
				case 'I' -> { types.add("int"); i++; }
				case 'J' -> { types.add("long"); i++; }
				case 'S' -> { types.add("short"); i++; }
				case 'Z' -> { types.add("boolean"); i++; }
				case '[' -> {
					// Array - skip brackets, the base type will be captured next iteration
					i++;
				}
				case 'L' -> {
					int semi = params.indexOf(';', i);
					if (semi < 0) { i = params.length(); break; }
					String fullName = params.substring(i + 1, semi);
					String simpleName = fullName.contains("/")
							? fullName.substring(fullName.lastIndexOf('/') + 1)
							: fullName;
					types.add(simpleName);
					i = semi + 1;
				}
				default -> i++;
			}
		}
		return types;
	}

	/**
	 * Compute a simple unified diff between two source texts.
	 *
	 * @param before    The original source text.
	 * @param after     The new source text.
	 * @param fileName  The file name to show in the diff header.
	 * @return A unified diff string.
	 */
	private static String computeUnifiedDiff(String before, String after, String fileName) {
		String[] beforeLines = before.split("\n", -1);
		String[] afterLines = after.split("\n", -1);

		if (before.equals(after)) {
			return "--- a/" + fileName + "\n+++ b/" + fileName + "\n(no changes)\n";
		}

		StringBuilder diff = new StringBuilder();
		diff.append("--- a/").append(fileName).append('\n');
		diff.append("+++ b/").append(fileName).append('\n');

		// Simple line-by-line diff using longest common subsequence approach
		int[][] lcs = computeLcsTable(beforeLines, afterLines);
		List<DiffLine> diffLines = buildDiffLines(beforeLines, afterLines, lcs);

		// Group into hunks with context
		int contextSize = 3;
		List<List<DiffLine>> hunks = groupIntoHunks(diffLines, contextSize);

		for (List<DiffLine> hunk : hunks) {
			if (hunk.isEmpty()) continue;

			int beforeStart = hunk.getFirst().beforeLineNum;
			int afterStart = hunk.getFirst().afterLineNum;
			int beforeCount = 0;
			int afterCount = 0;

			for (DiffLine dl : hunk) {
				switch (dl.type) {
					case CONTEXT -> { beforeCount++; afterCount++; }
					case REMOVED -> beforeCount++;
					case ADDED -> afterCount++;
				}
			}

			diff.append("@@ -").append(Math.max(beforeStart, 1))
					.append(',').append(beforeCount)
					.append(" +").append(Math.max(afterStart, 1))
					.append(',').append(afterCount)
					.append(" @@\n");

			for (DiffLine dl : hunk) {
				switch (dl.type) {
					case CONTEXT -> diff.append(' ').append(dl.text).append('\n');
					case REMOVED -> diff.append('-').append(dl.text).append('\n');
					case ADDED -> diff.append('+').append(dl.text).append('\n');
				}
			}
		}

		return diff.toString();
	}

	/**
	 * Compute the LCS length table for two arrays of lines.
	 */
	private static int[][] computeLcsTable(String[] a, String[] b) {
		int m = a.length;
		int n = b.length;
		int[][] table = new int[m + 1][n + 1];

		for (int i = 1; i <= m; i++) {
			for (int j = 1; j <= n; j++) {
				if (a[i - 1].equals(b[j - 1])) {
					table[i][j] = table[i - 1][j - 1] + 1;
				} else {
					table[i][j] = Math.max(table[i - 1][j], table[i][j - 1]);
				}
			}
		}
		return table;
	}

	/**
	 * Build a list of diff lines by backtracking through the LCS table.
	 */
	private static List<DiffLine> buildDiffLines(String[] before, String[] after, int[][] lcs) {
		List<DiffLine> result = new ArrayList<>();
		int i = before.length;
		int j = after.length;

		// Backtrack through LCS table to build diff
		List<DiffLine> reversed = new ArrayList<>();
		while (i > 0 || j > 0) {
			if (i > 0 && j > 0 && before[i - 1].equals(after[j - 1])) {
				reversed.add(new DiffLine(DiffType.CONTEXT, before[i - 1], i, j));
				i--;
				j--;
			} else if (j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j])) {
				reversed.add(new DiffLine(DiffType.ADDED, after[j - 1], i + 1, j));
				j--;
			} else {
				reversed.add(new DiffLine(DiffType.REMOVED, before[i - 1], i, j + 1));
				i--;
			}
		}

		// Reverse to get correct order
		for (int k = reversed.size() - 1; k >= 0; k--) {
			result.add(reversed.get(k));
		}
		return result;
	}

	/**
	 * Group diff lines into hunks, including context lines around changes.
	 */
	private static List<List<DiffLine>> groupIntoHunks(List<DiffLine> diffLines, int contextSize) {
		List<List<DiffLine>> hunks = new ArrayList<>();
		if (diffLines.isEmpty()) return hunks;

		// Find indices of changed lines
		List<Integer> changeIndices = new ArrayList<>();
		for (int i = 0; i < diffLines.size(); i++) {
			if (diffLines.get(i).type != DiffType.CONTEXT) {
				changeIndices.add(i);
			}
		}

		if (changeIndices.isEmpty()) return hunks;

		// Build hunks by grouping nearby changes
		List<DiffLine> currentHunk = new ArrayList<>();
		int hunkStart = Math.max(0, changeIndices.getFirst() - contextSize);
		int hunkEnd = Math.min(diffLines.size() - 1, changeIndices.getFirst() + contextSize);

		for (int ci = 1; ci < changeIndices.size(); ci++) {
			int nextChangeStart = changeIndices.get(ci) - contextSize;
			if (nextChangeStart <= hunkEnd + 1) {
				// Merge with current hunk
				hunkEnd = Math.min(diffLines.size() - 1, changeIndices.get(ci) + contextSize);
			} else {
				// Flush current hunk
				for (int k = hunkStart; k <= hunkEnd && k < diffLines.size(); k++) {
					currentHunk.add(diffLines.get(k));
				}
				hunks.add(currentHunk);
				currentHunk = new ArrayList<>();
				hunkStart = nextChangeStart;
				hunkEnd = Math.min(diffLines.size() - 1, changeIndices.get(ci) + contextSize);
			}
		}

		// Flush last hunk
		for (int k = hunkStart; k <= hunkEnd && k < diffLines.size(); k++) {
			currentHunk.add(diffLines.get(k));
		}
		if (!currentHunk.isEmpty()) {
			hunks.add(currentHunk);
		}

		return hunks;
	}

	private enum DiffType {
		CONTEXT, ADDED, REMOVED
	}

	private record DiffLine(DiffType type, String text, int beforeLineNum, int afterLineNum) {}
}
