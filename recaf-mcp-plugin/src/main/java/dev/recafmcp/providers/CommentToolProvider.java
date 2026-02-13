package dev.recafmcp.providers;

import dev.recafmcp.util.ClassResolver;
import dev.recafmcp.util.ErrorHelper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.comment.ClassComments;
import software.coley.recaf.services.comment.CommentManager;
import software.coley.recaf.services.comment.DelegatingClassComments;
import software.coley.recaf.services.comment.WorkspaceComments;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * MCP tool provider for comment management operations.
 * <p>
 * Provides tools for setting, getting, searching, and deleting comments
 * on classes and members within the current Recaf workspace.
 * <p>
 * Uses Recaf's {@link CommentManager} service for persistent comment storage.
 */
public class CommentToolProvider extends AbstractToolProvider {
	private static final Logger logger = Logging.get(CommentToolProvider.class);

	private final CommentManager commentManager;

	public CommentToolProvider(McpSyncServer server, WorkspaceManager workspaceManager, CommentManager commentManager) {
		super(server, workspaceManager);
		this.commentManager = commentManager;
	}

	@Override
	public void registerTools() {
		registerCommentSet();
		registerCommentGet();
		registerCommentSearch();
		registerCommentDelete();
	}

	// ---- comment-set ----

	private void registerCommentSet() {
		Tool tool = Tool.builder()
				.name("comment-set")
				.description("Set a comment on a class or class member (field/method) in the current workspace. " +
						"For members, provide memberName plus memberDescriptor and memberType.")
				.inputSchema(createSchema(
						Map.of(
								"className", stringParam("Fully qualified class name (e.g., 'com/example/MyClass' or 'com.example.MyClass')"),
								"memberName", stringParam("Member name (field or method) within the class. Omit to comment on the class itself."),
								"memberDescriptor", stringParam("JVM descriptor of the member (e.g., 'I' for an int field, '(Ljava/lang/String;)V' for a method). Required when memberName is provided."),
								"memberType", stringEnumParam("Whether the member is a 'field' or 'method'. Required when memberName is provided.", List.of("field", "method")),
								"comment", stringParam("The comment text to set")
						),
						List.of("className", "comment")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();
			String className = getString(args, "className");
			String memberName = getOptionalString(args, "memberName", null);
			String memberDescriptor = getOptionalString(args, "memberDescriptor", null);
			String memberType = getOptionalString(args, "memberType", null);
			String comment = getString(args, "comment");

			String normalized = ClassResolver.normalizeClassName(className);
			ClassPathNode classPath = ClassResolver.resolveClass(workspace, normalized);
			if (classPath == null) {
				return createErrorResult(ErrorHelper.classNotFound(normalized, workspace));
			}

			WorkspaceComments workspaceComments = commentManager.getOrCreateWorkspaceComments(workspace);
			ClassComments classComments = workspaceComments.getOrCreateClassComments(classPath);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("className", classPath.getValue().getName());

			if (memberName != null) {
				// Setting a member comment requires descriptor and type
				if (memberDescriptor == null) {
					return createErrorResult("'memberDescriptor' is required when 'memberName' is provided.");
				}
				if (memberType == null) {
					// Try to infer type from class info
					memberType = inferMemberType(classPath.getValue(), memberName, memberDescriptor);
					if (memberType == null) {
						return createErrorResult("'memberType' is required when 'memberName' is provided. " +
								"Specify 'field' or 'method'.");
					}
				}

				result.put("memberName", memberName);
				result.put("memberDescriptor", memberDescriptor);
				result.put("memberType", memberType);

				if ("field".equals(memberType)) {
					classComments.setFieldComment(memberName, memberDescriptor, comment);
				} else if ("method".equals(memberType)) {
					classComments.setMethodComment(memberName, memberDescriptor, comment);
				} else {
					return createErrorResult("Invalid memberType '" + memberType + "'. Must be 'field' or 'method'.");
				}
			} else {
				// Setting a class-level comment
				classComments.setClassComment(comment);
			}

			result.put("comment", comment);
			result.put("status", "success");
			logger.info("Set comment on class='{}', member='{}': {}", normalized, memberName, comment);
			return createJsonResult(result);
		});
	}

	// ---- comment-get ----

	private void registerCommentGet() {
		Tool tool = Tool.builder()
				.name("comment-get")
				.description("Get the comment on a class or class member in the current workspace. " +
						"Omit memberName to get the class-level comment. " +
						"If memberName is provided without memberDescriptor, returns comments for all members with that name.")
				.inputSchema(createSchema(
						Map.of(
								"className", stringParam("Fully qualified class name (e.g., 'com/example/MyClass' or 'com.example.MyClass')"),
								"memberName", stringParam("Member name (field or method). Omit to get the class-level comment."),
								"memberDescriptor", stringParam("JVM descriptor of the member. If omitted with memberName, returns all members matching the name."),
								"memberType", stringEnumParam("Whether the member is a 'field' or 'method'. Helps narrow results when memberName is given without descriptor.", List.of("field", "method"))
						),
						List.of("className")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();
			String className = getString(args, "className");
			String memberName = getOptionalString(args, "memberName", null);
			String memberDescriptor = getOptionalString(args, "memberDescriptor", null);
			String memberType = getOptionalString(args, "memberType", null);

			String normalized = ClassResolver.normalizeClassName(className);
			ClassPathNode classPath = ClassResolver.resolveClass(workspace, normalized);
			if (classPath == null) {
				return createErrorResult(ErrorHelper.classNotFound(normalized, workspace));
			}

			WorkspaceComments workspaceComments = commentManager.getOrCreateWorkspaceComments(workspace);
			ClassComments classComments = workspaceComments.getClassComments(classPath);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("className", classPath.getValue().getName());

			if (memberName == null) {
				// Get class-level comment
				String comment = classComments != null ? classComments.getClassComment() : null;
				result.put("comment", comment);
				result.put("found", comment != null);
			} else if (memberDescriptor != null) {
				// Get specific member comment
				result.put("memberName", memberName);
				result.put("memberDescriptor", memberDescriptor);

				String comment = null;
				if (classComments != null) {
					if (memberType == null) {
						memberType = inferMemberType(classPath.getValue(), memberName, memberDescriptor);
					}
					if ("field".equals(memberType)) {
						comment = classComments.getFieldComment(memberName, memberDescriptor);
					} else if ("method".equals(memberType)) {
						comment = classComments.getMethodComment(memberName, memberDescriptor);
					} else {
						// Try both
						comment = classComments.getFieldComment(memberName, memberDescriptor);
						if (comment == null) {
							comment = classComments.getMethodComment(memberName, memberDescriptor);
						}
					}
				}
				if (memberType != null) {
					result.put("memberType", memberType);
				}
				result.put("comment", comment);
				result.put("found", comment != null);
			} else {
				// memberName given but no descriptor - search all members with this name
				result.put("memberName", memberName);
				List<Map<String, Object>> comments = new ArrayList<>();

				ClassInfo classInfo = classPath.getValue();
				boolean filterFields = memberType == null || "field".equals(memberType);
				boolean filterMethods = memberType == null || "method".equals(memberType);

				if (filterFields) {
					for (FieldMember field : classInfo.getFields()) {
						if (field.getName().equals(memberName) && classComments != null) {
							String comment = classComments.getFieldComment(field.getName(), field.getDescriptor());
							if (comment != null) {
								LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
								entry.put("memberType", "field");
								entry.put("memberName", field.getName());
								entry.put("memberDescriptor", field.getDescriptor());
								entry.put("comment", comment);
								comments.add(entry);
							}
						}
					}
				}
				if (filterMethods) {
					for (MethodMember method : classInfo.getMethods()) {
						if (method.getName().equals(memberName) && classComments != null) {
							String comment = classComments.getMethodComment(method.getName(), method.getDescriptor());
							if (comment != null) {
								LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
								entry.put("memberType", "method");
								entry.put("memberName", method.getName());
								entry.put("memberDescriptor", method.getDescriptor());
								entry.put("comment", comment);
								comments.add(entry);
							}
						}
					}
				}

				result.put("comments", comments);
				result.put("found", !comments.isEmpty());
			}

			return createJsonResult(result);
		});
	}

	// ---- comment-search ----

	private void registerCommentSearch() {
		Tool tool = Tool.builder()
				.name("comment-search")
				.description("Search comments across all classes and members in the current workspace by text pattern (regex supported).")
				.inputSchema(createSchema(
						Map.of(
								"query", stringParam("Search query to match against comment text (supports regex)"),
								"offset", intParam("Starting offset for paginated results (default: 0)"),
								"limit", intParam("Maximum number of results to return (default: 100)")
						),
						List.of("query")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();
			String query = getString(args, "query");
			int offset = getInt(args, "offset", 0);
			int limit = getInt(args, "limit", 100);

			Pattern pattern;
			try {
				pattern = Pattern.compile(query, Pattern.CASE_INSENSITIVE);
			} catch (PatternSyntaxException e) {
				return createErrorResult("Invalid regex pattern: " + e.getMessage());
			}

			WorkspaceComments workspaceComments = commentManager.getOrCreateWorkspaceComments(workspace);

			List<Map<String, Object>> matches = new ArrayList<>();

			// Iterate all class comment containers in the workspace
			for (ClassComments classComments : workspaceComments) {
				if (!classComments.hasComments()) {
					continue;
				}

				// Determine class name from the container
				String commentClassName = null;
				if (classComments instanceof DelegatingClassComments delegating) {
					commentClassName = delegating.getPath().getValue().getName();
				}

				// Check class-level comment
				String classComment = classComments.getClassComment();
				if (classComment != null && pattern.matcher(classComment).find()) {
					LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
					entry.put("className", commentClassName);
					entry.put("type", "class");
					entry.put("comment", classComment);
					matches.add(entry);
				}

				// Check field and method comments by iterating class members
				if (commentClassName != null) {
					ClassPathNode classPath = workspace.findClass(commentClassName);
					if (classPath != null) {
						ClassInfo classInfo = classPath.getValue();

						for (FieldMember field : classInfo.getFields()) {
							String comment = classComments.getFieldComment(field.getName(), field.getDescriptor());
							if (comment != null && pattern.matcher(comment).find()) {
								LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
								entry.put("className", commentClassName);
								entry.put("type", "field");
								entry.put("memberName", field.getName());
								entry.put("memberDescriptor", field.getDescriptor());
								entry.put("comment", comment);
								matches.add(entry);
							}
						}

						for (MethodMember method : classInfo.getMethods()) {
							String comment = classComments.getMethodComment(method.getName(), method.getDescriptor());
							if (comment != null && pattern.matcher(comment).find()) {
								LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
								entry.put("className", commentClassName);
								entry.put("type", "method");
								entry.put("memberName", method.getName());
								entry.put("memberDescriptor", method.getDescriptor());
								entry.put("comment", comment);
								matches.add(entry);
							}
						}
					}
				}
			}

			// Apply pagination
			int total = matches.size();
			int fromIndex = Math.min(offset, total);
			int toIndex = Math.min(fromIndex + limit, total);
			List<Map<String, Object>> page = matches.subList(fromIndex, toIndex);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("query", query);
			result.put("results", page);
			result.put("offset", offset);
			result.put("limit", limit);
			result.put("total", total);
			return createJsonResult(result);
		});
	}

	// ---- comment-delete ----

	private void registerCommentDelete() {
		Tool tool = Tool.builder()
				.name("comment-delete")
				.description("Delete the comment on a class or class member in the current workspace. " +
						"Omit memberName to delete the class-level comment.")
				.inputSchema(createSchema(
						Map.of(
								"className", stringParam("Fully qualified class name (e.g., 'com/example/MyClass' or 'com.example.MyClass')"),
								"memberName", stringParam("Member name (field or method). Omit to delete the class-level comment."),
								"memberDescriptor", stringParam("JVM descriptor of the member. Required when memberName is provided."),
								"memberType", stringEnumParam("Whether the member is a 'field' or 'method'. Required when memberName is provided.", List.of("field", "method"))
						),
						List.of("className")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			Workspace workspace = requireWorkspace();
			String className = getString(args, "className");
			String memberName = getOptionalString(args, "memberName", null);
			String memberDescriptor = getOptionalString(args, "memberDescriptor", null);
			String memberType = getOptionalString(args, "memberType", null);

			String normalized = ClassResolver.normalizeClassName(className);
			ClassPathNode classPath = ClassResolver.resolveClass(workspace, normalized);
			if (classPath == null) {
				return createErrorResult(ErrorHelper.classNotFound(normalized, workspace));
			}

			WorkspaceComments workspaceComments = commentManager.getOrCreateWorkspaceComments(workspace);
			ClassComments classComments = workspaceComments.getClassComments(classPath);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("className", classPath.getValue().getName());

			if (memberName == null) {
				// Delete class-level comment
				if (classComments != null) {
					String existing = classComments.getClassComment();
					classComments.setClassComment(null);
					result.put("deleted", existing != null);
					result.put("previousComment", existing);
				} else {
					result.put("deleted", false);
					result.put("previousComment", null);
				}
			} else {
				// Delete member comment
				if (memberDescriptor == null) {
					return createErrorResult("'memberDescriptor' is required when 'memberName' is provided.");
				}
				if (memberType == null) {
					memberType = inferMemberType(classPath.getValue(), memberName, memberDescriptor);
					if (memberType == null) {
						return createErrorResult("'memberType' is required when 'memberName' is provided. " +
								"Specify 'field' or 'method'.");
					}
				}

				result.put("memberName", memberName);
				result.put("memberDescriptor", memberDescriptor);
				result.put("memberType", memberType);

				String existing = null;
				if (classComments != null) {
					if ("field".equals(memberType)) {
						existing = classComments.getFieldComment(memberName, memberDescriptor);
						classComments.setFieldComment(memberName, memberDescriptor, null);
					} else if ("method".equals(memberType)) {
						existing = classComments.getMethodComment(memberName, memberDescriptor);
						classComments.setMethodComment(memberName, memberDescriptor, null);
					} else {
						return createErrorResult("Invalid memberType '" + memberType + "'. Must be 'field' or 'method'.");
					}
				}

				result.put("deleted", existing != null);
				result.put("previousComment", existing);
			}

			result.put("status", "success");
			logger.info("Deleted comment on class='{}', member='{}'", normalized, memberName);
			return createJsonResult(result);
		});
	}

	// ---- Helpers ----

	/**
	 * Try to infer whether a member is a field or method by looking it up in the class info.
	 *
	 * @param classInfo       The class containing the member.
	 * @param memberName      The member name.
	 * @param memberDescriptor The member descriptor.
	 * @return "field", "method", or {@code null} if the member cannot be found.
	 */
	private static String inferMemberType(ClassInfo classInfo, String memberName, String memberDescriptor) {
		FieldMember field = classInfo.getDeclaredField(memberName, memberDescriptor);
		if (field != null) {
			return "field";
		}
		MethodMember method = classInfo.getDeclaredMethod(memberName, memberDescriptor);
		if (method != null) {
			return "method";
		}
		return null;
	}
}
