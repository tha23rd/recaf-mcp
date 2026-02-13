package dev.recafmcp.util;

import software.coley.recaf.workspace.model.Workspace;

import java.util.List;

/**
 * Utility for producing consistent, actionable error messages.
 */
public final class ErrorHelper {

	private static final int MAX_SUGGESTIONS = 5;

	private ErrorHelper() {
		// Utility class
	}

	/**
	 * Produce an error message for a class that could not be found,
	 * including suggestions of similar class names when available.
	 *
	 * @param name      the class name that was searched for
	 * @param workspace the workspace that was searched (may be null)
	 * @return a descriptive error message
	 */
	public static String classNotFound(String name, Workspace workspace) {
		StringBuilder sb = new StringBuilder();
		sb.append("Class '").append(name).append("' not found.");

		if (workspace != null && name != null) {
			List<String> suggestions = ClassResolver.findSimilarClassNames(workspace, name, MAX_SUGGESTIONS);
			if (!suggestions.isEmpty()) {
				sb.append(" Did you mean one of these?\n");
				for (String suggestion : suggestions) {
					sb.append("  - ").append(suggestion).append('\n');
				}
			}
		}

		return sb.toString();
	}

	/**
	 * Produce an error message indicating no workspace is currently open.
	 *
	 * @return the error message
	 */
	public static String noWorkspace() {
		return "No workspace is open. Use 'workspace-open' to load a JAR, APK, or class file first.";
	}
}
