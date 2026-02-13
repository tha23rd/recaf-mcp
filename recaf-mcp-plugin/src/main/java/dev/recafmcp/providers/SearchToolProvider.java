package dev.recafmcp.providers;

import dev.recafmcp.util.PaginationUtil;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.search.match.NumberPredicate;
import software.coley.recaf.services.search.match.StringPredicate;
import software.coley.recaf.services.search.query.DeclarationQuery;
import software.coley.recaf.services.search.query.NumberQuery;
import software.coley.recaf.services.search.query.ReferenceQuery;
import software.coley.recaf.services.search.query.StringQuery;
import software.coley.recaf.services.search.result.Result;
import software.coley.recaf.services.search.result.Results;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.ClassReader;
import software.coley.recaf.util.AsmInsnUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * MCP tool provider for search operations across the workspace.
 * <p>
 * Provides tools for searching strings, numbers, references, declarations,
 * files, and decompiled source code within the current Recaf workspace.
 */
public class SearchToolProvider extends AbstractToolProvider {
	private static final Logger logger = Logging.get(SearchToolProvider.class);

	private final SearchService searchService;
	private final DecompilerManager decompilerManager;

	public SearchToolProvider(McpSyncServer server,
	                          WorkspaceManager workspaceManager,
	                          SearchService searchService,
	                          DecompilerManager decompilerManager) {
		super(server, workspaceManager);
		this.searchService = searchService;
		this.decompilerManager = decompilerManager;
	}

	@Override
	public void registerTools() {
		registerSearchStrings();
		registerSearchStringsCount();
		registerSearchNumbers();
		registerSearchReferences();
		registerSearchDeclarations();
		registerSearchInstructions();
		registerSearchFiles();
		registerSearchTextInDecompilation();
	}

	// ---- search-strings ----

	private void registerSearchStrings() {
		Tool tool = Tool.builder()
				.name("search-strings")
				.description("Search for string constants in all classes within the workspace. " +
						"Finds string literals matching the query (case-insensitive contains match).")
				.inputSchema(createSchema(
						Map.of(
								"query", stringParam("String value to search for (case-insensitive contains match)"),
								"offset", intParam("Pagination offset (default: 0)"),
								"limit", intParam("Maximum results to return (default: 100, max: 1000)")
						),
						List.of("query")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			String query = getString(args, "query");
			int offset = getInt(args, "offset", 0);
			int limit = getInt(args, "limit", PaginationUtil.DEFAULT_LIMIT);

			Workspace workspace = requireWorkspace();
			StringPredicate predicate = new StringPredicate("contains-ignore-case",
					s -> s != null && s.toLowerCase().contains(query.toLowerCase()));
			StringQuery stringQuery = new StringQuery(predicate);
			Results results = searchService.search(workspace, stringQuery);

			List<Map<String, Object>> allItems = collectResults(results);
			List<Map<String, Object>> page = PaginationUtil.paginate(allItems, offset, limit);
			return createJsonResult(PaginationUtil.paginatedResult(page, offset, limit, allItems.size()));
		});
	}

	// ---- search-strings-count ----

	private void registerSearchStringsCount() {
		Tool tool = Tool.builder()
				.name("search-strings-count")
				.description("Count string constant matches in the workspace without returning full results. " +
						"Useful for gauging result set size before fetching.")
				.inputSchema(createSchema(
						Map.of("query", stringParam("String value to search for (case-insensitive contains match)")),
						List.of("query")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			String query = getString(args, "query");

			Workspace workspace = requireWorkspace();
			StringPredicate predicate = new StringPredicate("contains-ignore-case",
					s -> s != null && s.toLowerCase().contains(query.toLowerCase()));
			StringQuery stringQuery = new StringQuery(predicate);
			Results results = searchService.search(workspace, stringQuery);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("query", query);
			result.put("count", results.size());
			return createJsonResult(result);
		});
	}

	// ---- search-numbers ----

	private void registerSearchNumbers() {
		Tool tool = Tool.builder()
				.name("search-numbers")
				.description("Search for numeric constants (int, long, float, double) in all classes. " +
						"Provide the value as a string; it will be parsed as a number.")
				.inputSchema(createSchema(
						Map.of(
								"value", stringParam("Numeric value to search for (e.g., '42', '3.14')"),
								"offset", intParam("Pagination offset (default: 0)"),
								"limit", intParam("Maximum results to return (default: 100, max: 1000)")
						),
						List.of("value")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			String valueStr = getString(args, "value");
			int offset = getInt(args, "offset", 0);
			int limit = getInt(args, "limit", PaginationUtil.DEFAULT_LIMIT);

			Number targetNumber = parseNumber(valueStr);
			Workspace workspace = requireWorkspace();

			NumberPredicate predicate = new NumberPredicate("equals",
					n -> n != null && n.doubleValue() == targetNumber.doubleValue());
			NumberQuery numberQuery = new NumberQuery(predicate);
			Results results = searchService.search(workspace, numberQuery);

			List<Map<String, Object>> allItems = collectResults(results);
			List<Map<String, Object>> page = PaginationUtil.paginate(allItems, offset, limit);
			return createJsonResult(PaginationUtil.paginatedResult(page, offset, limit, allItems.size()));
		});
	}

	// ---- search-references ----

	private void registerSearchReferences() {
		Tool tool = Tool.builder()
				.name("search-references")
				.description("Search for references to classes, methods, or fields. At least one parameter must be provided. " +
						"Uses contains matching on each provided parameter.")
				.inputSchema(createSchema(
						Map.of(
								"className", stringParam("Owner class name to match (optional, contains match)"),
								"memberName", stringParam("Member name to match (optional, contains match)"),
								"memberDescriptor", stringParam("Member descriptor to match (optional, contains match)"),
								"offset", intParam("Pagination offset (default: 0)"),
								"limit", intParam("Maximum results to return (default: 100, max: 1000)")
						),
						List.of()
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			String className = getOptionalString(args, "className", null);
			String memberName = getOptionalString(args, "memberName", null);
			String memberDescriptor = getOptionalString(args, "memberDescriptor", null);
			int offset = getInt(args, "offset", 0);
			int limit = getInt(args, "limit", PaginationUtil.DEFAULT_LIMIT);

			if (className == null && memberName == null && memberDescriptor == null) {
				throw new IllegalArgumentException(
						"At least one of 'className', 'memberName', or 'memberDescriptor' must be provided");
			}

			Workspace workspace = requireWorkspace();

			// Build predicates for each provided parameter
			StringPredicate ownerPred = className != null
					? new StringPredicate("contains", s -> s != null && s.contains(className))
					: null;
			StringPredicate namePred = memberName != null
					? new StringPredicate("contains", s -> s != null && s.contains(memberName))
					: null;
			StringPredicate descPred = memberDescriptor != null
					? new StringPredicate("contains", s -> s != null && s.contains(memberDescriptor))
					: null;

			ReferenceQuery refQuery;
			if (namePred == null && descPred == null) {
				// Class-only reference query
				refQuery = new ReferenceQuery(ownerPred);
			} else {
				refQuery = new ReferenceQuery(ownerPred, namePred, descPred);
			}

			Results results = searchService.search(workspace, refQuery);
			List<Map<String, Object>> allItems = collectResults(results);
			List<Map<String, Object>> page = PaginationUtil.paginate(allItems, offset, limit);
			return createJsonResult(PaginationUtil.paginatedResult(page, offset, limit, allItems.size()));
		});
	}

	// ---- search-declarations ----

	private void registerSearchDeclarations() {
		Tool tool = Tool.builder()
				.name("search-declarations")
				.description("Search for class, method, and field declarations matching a regex pattern. " +
						"Matches against declaration names in the workspace.")
				.inputSchema(createSchema(
						Map.of(
								"name", stringParam("Regex pattern to match against declaration names"),
								"offset", intParam("Pagination offset (default: 0)"),
								"limit", intParam("Maximum results to return (default: 100, max: 1000)")
						),
						List.of("name")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			String namePattern = getString(args, "name");
			int offset = getInt(args, "offset", 0);
			int limit = getInt(args, "limit", PaginationUtil.DEFAULT_LIMIT);

			Workspace workspace = requireWorkspace();

			// Use regex predicate for name matching
			Pattern compiledPattern = Pattern.compile(namePattern, Pattern.CASE_INSENSITIVE);
			StringPredicate namePred = new StringPredicate("regex-partial",
					s -> s != null && compiledPattern.matcher(s).find());

			// DeclarationQuery needs owner, name, desc predicates — we only filter by name
			DeclarationQuery declQuery = new DeclarationQuery(null, namePred, null);
			Results results = searchService.search(workspace, declQuery);

			List<Map<String, Object>> allItems = collectResults(results);
			List<Map<String, Object>> page = PaginationUtil.paginate(allItems, offset, limit);
			return createJsonResult(PaginationUtil.paginatedResult(page, offset, limit, allItems.size()));
		});
	}

	// ---- search-instructions ----

	private void registerSearchInstructions() {
		Tool tool = Tool.builder()
				.name("search-instructions")
				.description("Search for bytecode instructions matching a pattern across all classes. " +
						"Matches against opcode names (e.g. 'INVOKEVIRTUAL', 'GETSTATIC', 'LDC') and " +
						"operand text (class/method/field references, constants). " +
						"The pattern is a case-insensitive regex matched against a text representation " +
						"of each instruction.")
				.inputSchema(createSchema(
						Map.of(
								"pattern", stringParam("Regex pattern to match against instruction text " +
										"(e.g. 'INVOKEVIRTUAL.*println', 'GETSTATIC.*System/out', 'LDC.*password')"),
								"maxClasses", intParam("Maximum number of classes to search (default: 200)"),
								"maxResultsPerClass", intParam("Maximum matching instructions per class (default: 20)")
						),
						List.of("pattern")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			String patternStr = getString(args, "pattern");
			int maxClasses = getInt(args, "maxClasses", 200);
			int maxResultsPerClass = getInt(args, "maxResultsPerClass", 20);
			maxClasses = Math.clamp(maxClasses, 1, 1000);
			maxResultsPerClass = Math.clamp(maxResultsPerClass, 1, 100);

			Workspace workspace = requireWorkspace();
			Pattern compiledPattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);

			List<Map<String, Object>> classMatches = new ArrayList<>();
			int totalSearched = 0;
			int totalMatches = 0;

			List<ClassPathNode> classNodes = workspace.jvmClassesStream()
					.limit(maxClasses)
					.toList();

			for (ClassPathNode cpn : classNodes) {
				ClassInfo classInfo = cpn.getValue();
				if (!classInfo.isJvmClass()) continue;

				JvmClassInfo jvmClass = classInfo.asJvmClass();
				totalSearched++;

				try {
					ClassReader reader = jvmClass.getClassReader();
					ClassNode classNode = new ClassNode();
					reader.accept(classNode, ClassReader.SKIP_FRAMES);

					List<Map<String, Object>> methodMatches = new ArrayList<>();

					for (MethodNode methodNode : classNode.methods) {
						InsnList instructions = methodNode.instructions;
						if (instructions == null) continue;

						int matchesInMethod = 0;
						List<Map<String, Object>> insnMatches = new ArrayList<>();

						for (int i = 0; i < instructions.size(); i++) {
							AbstractInsnNode insn = instructions.get(i);
							if (insn.getOpcode() < 0) continue; // Skip labels, frames, line numbers

							String insnText = formatInstruction(insn);
							if (compiledPattern.matcher(insnText).find()) {
								if (matchesInMethod < maxResultsPerClass) {
									LinkedHashMap<String, Object> match = new LinkedHashMap<>();
									match.put("index", i);
									match.put("instruction", insnText);
									insnMatches.add(match);
								}
								matchesInMethod++;
								totalMatches++;
							}
						}

						if (!insnMatches.isEmpty()) {
							LinkedHashMap<String, Object> methodMatch = new LinkedHashMap<>();
							methodMatch.put("methodName", methodNode.name);
							methodMatch.put("methodDescriptor", methodNode.desc);
							methodMatch.put("matchCount", matchesInMethod);
							methodMatch.put("matches", insnMatches);
							methodMatches.add(methodMatch);
						}
					}

					if (!methodMatches.isEmpty()) {
						LinkedHashMap<String, Object> classMatch = new LinkedHashMap<>();
						classMatch.put("className", classInfo.getName());
						classMatch.put("methods", methodMatches);
						classMatches.add(classMatch);
					}
				} catch (Exception e) {
					logger.debug("Failed to analyze instructions for class '{}'", classInfo.getName(), e);
				}
			}

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("pattern", patternStr);
			result.put("classesSearched", totalSearched);
			result.put("classesWithMatches", classMatches.size());
			result.put("totalInstructionMatches", totalMatches);
			result.put("results", classMatches);
			if (totalSearched >= maxClasses) {
				result.put("warning", "Search was limited to " + maxClasses +
						" classes. Increase 'maxClasses' to search more.");
			}
			return createJsonResult(result);
		});
	}

	/**
	 * Format a bytecode instruction as a human-readable string for pattern matching.
	 *
	 * @param insn The ASM instruction node.
	 * @return A string representation including opcode name and operands.
	 */
	private static String formatInstruction(AbstractInsnNode insn) {
		String opcodeName = AsmInsnUtil.getInsnName(insn.getOpcode());
		if (opcodeName == null) opcodeName = "UNKNOWN(" + insn.getOpcode() + ")";

		return switch (insn) {
			case MethodInsnNode min ->
					opcodeName + " " + min.owner + "." + min.name + min.desc;
			case FieldInsnNode fin ->
					opcodeName + " " + fin.owner + "." + fin.name + " " + fin.desc;
			case TypeInsnNode tin ->
					opcodeName + " " + tin.desc;
			case LdcInsnNode ldc ->
					opcodeName + " " + ldc.cst;
			case IntInsnNode iin ->
					opcodeName + " " + iin.operand;
			case VarInsnNode vin ->
					opcodeName + " " + vin.var;
			default -> opcodeName;
		};
	}

	// ---- search-files ----

	private void registerSearchFiles() {
		Tool tool = Tool.builder()
				.name("search-files")
				.description("Search within non-class files (resources) in the workspace. " +
						"Searches file names and text content of text-based files for the query string.")
				.inputSchema(createSchema(
						Map.of(
								"query", stringParam("Search query (case-insensitive, matches file names and text content)"),
								"offset", intParam("Pagination offset (default: 0)"),
								"limit", intParam("Maximum results to return (default: 100, max: 1000)")
						),
						List.of("query")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			String query = getString(args, "query");
			int offset = getInt(args, "offset", 0);
			int limit = getInt(args, "limit", PaginationUtil.DEFAULT_LIMIT);

			Workspace workspace = requireWorkspace();
			String queryLower = query.toLowerCase();

			// Search through file bundles in the workspace
			List<Map<String, Object>> allItems = new ArrayList<>();
			workspace.filesStream().forEach(filePathNode -> {
				try {
					FileInfo fileInfo = filePathNode.getValue();
					String fileName = fileInfo.getName();
					boolean nameMatch = fileName.toLowerCase().contains(queryLower);
					boolean contentMatch = false;
					String matchSnippet = null;

					// Check text content for text files
					if (fileInfo.isTextFile()) {
						String text = fileInfo.asTextFile().getText();
						if (text != null && text.toLowerCase().contains(queryLower)) {
							contentMatch = true;
							// Extract a snippet around the match
							int idx = text.toLowerCase().indexOf(queryLower);
							int start = Math.max(0, idx - 50);
							int end = Math.min(text.length(), idx + query.length() + 50);
							matchSnippet = text.substring(start, end);
						}
					}

					if (nameMatch || contentMatch) {
						LinkedHashMap<String, Object> item = new LinkedHashMap<>();
						item.put("fileName", fileName);
						item.put("nameMatch", nameMatch);
						item.put("contentMatch", contentMatch);
						if (matchSnippet != null) {
							item.put("snippet", matchSnippet);
						}
						item.put("path", filePathNode.toString());
						allItems.add(item);
					}
				} catch (Exception e) {
					logger.debug("Error searching file: {}", filePathNode, e);
				}
			});

			List<Map<String, Object>> page = PaginationUtil.paginate(allItems, offset, limit);
			return createJsonResult(PaginationUtil.paginatedResult(page, offset, limit, allItems.size()));
		});
	}

	// ---- search-text-in-decompilation ----

	private void registerSearchTextInDecompilation() {
		Tool tool = Tool.builder()
				.name("search-text-in-decompilation")
				.description("Search decompiled source code for a regex pattern. WARNING: This is an expensive operation " +
						"as it decompiles each class before searching. Use 'maxClasses' to limit scope. " +
						"Prefer 'search-strings' or 'search-references' when possible.")
				.inputSchema(createSchema(
						Map.of(
								"pattern", stringParam("Regex pattern to search for in decompiled source"),
								"maxClasses", intParam("Maximum number of classes to decompile and search (default: 50)")
						),
						List.of("pattern")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			String patternStr = getString(args, "pattern");
			int maxClasses = getInt(args, "maxClasses", 50);
			maxClasses = Math.clamp(maxClasses, 1, 500);

			Workspace workspace = requireWorkspace();
			Pattern compiledPattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);

			List<Map<String, Object>> matches = new ArrayList<>();
			int searched = 0;
			int failed = 0;

			// Iterate over JVM classes in the workspace
			List<ClassPathNode> classNodes = workspace.jvmClassesStream()
					.limit(maxClasses)
					.toList();

			for (ClassPathNode cpn : classNodes) {
				ClassInfo classInfo = cpn.getValue();
				if (!classInfo.isJvmClass()) continue;

				JvmClassInfo jvmClass = classInfo.asJvmClass();
				searched++;

				try {
					DecompileResult decompResult = decompilerManager
							.decompile(workspace, jvmClass)
							.get(10, TimeUnit.SECONDS);

					String source = decompResult.getText();
					if (source == null || source.isEmpty()) {
						failed++;
						continue;
					}

					List<Map<String, Object>> lineMatches = new ArrayList<>();
					String[] lines = source.split("\n");

					for (int i = 0; i < lines.length; i++) {
						if (compiledPattern.matcher(lines[i]).find()) {
							LinkedHashMap<String, Object> lineMatch = new LinkedHashMap<>();
							lineMatch.put("line", i + 1);
							lineMatch.put("text", lines[i].strip());
							lineMatches.add(lineMatch);
						}
					}

					if (!lineMatches.isEmpty()) {
						LinkedHashMap<String, Object> match = new LinkedHashMap<>();
						match.put("className", classInfo.getName());
						match.put("matchCount", lineMatches.size());
						match.put("matches", lineMatches);
						matches.add(match);
					}
				} catch (Exception e) {
					failed++;
					logger.debug("Failed to decompile class '{}' for search", classInfo.getName(), e);
				}
			}

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("pattern", patternStr);
			result.put("classesSearched", searched);
			result.put("classesWithMatches", matches.size());
			result.put("decompileFailures", failed);
			result.put("results", matches);
			if (searched >= maxClasses) {
				result.put("warning", "Search was limited to " + maxClasses +
						" classes. Increase 'maxClasses' to search more.");
			}
			return createJsonResult(result);
		});
	}

	// ---- Helper methods ----

	/**
	 * Collect search results into a list of maps with path and result info.
	 *
	 * @param results The search results from the search service.
	 * @return A list of maps, each containing location and match info.
	 */
	private List<Map<String, Object>> collectResults(Results results) {
		List<Map<String, Object>> items = new ArrayList<>();
		for (Result<?> result : results) {
			try {
				LinkedHashMap<String, Object> item = new LinkedHashMap<>();
				PathNode<?> path = result.getPath();

				// Build location info from the path chain
				item.put("path", path.toString());

				// Extract class name if available in path
				ClassPathNode classNode = path.getPathOfType(ClassInfo.class);
				if (classNode != null) {
					item.put("className", classNode.getValue().getName());
				}

				// Use toString() for match value since getValue() is protected
				item.put("match", result.toString());

				items.add(item);
			} catch (Exception e) {
				logger.debug("Error collecting search result: {}", result, e);
			}
		}
		return items;
	}

	/**
	 * Parse a string into a Number, trying integer first, then long, then double.
	 *
	 * @param value The string representation of a number.
	 * @return The parsed number.
	 * @throws IllegalArgumentException if the value cannot be parsed.
	 */
	private static Number parseNumber(String value) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException ignored) {}
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException ignored) {}
		try {
			return Double.parseDouble(value);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Cannot parse '" + value + "' as a number");
		}
	}
}
