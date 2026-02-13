package dev.recafmcp.util;

import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Utility for resolving class names within a Recaf workspace.
 * <p>
 * Handles normalization between dot-notation ({@code com.example.Foo}) and
 * internal notation ({@code com/example/Foo}), exact lookups, simple-name
 * disambiguation, and fuzzy matching.
 */
public final class ClassResolver {

	private ClassResolver() {
		// Utility class
	}

	/**
	 * Normalize a class name to internal format (slashes instead of dots).
	 *
	 * @param input class name in dot or slash notation
	 * @return normalized name with slashes, or {@code null} if input is null
	 */
	public static String normalizeClassName(String input) {
		if (input == null) return null;
		return input.trim().replace('.', '/');
	}

	/**
	 * Resolve a class within the given workspace.
	 * <p>
	 * First attempts an exact match via {@link Workspace#findClass(String)}.
	 * If that fails, tries a simple-name match (the part after the last {@code /}).
	 * Simple-name matching only returns a result when exactly one class matches.
	 *
	 * @param workspace the workspace to search
	 * @param name      the class name (dot or slash notation)
	 * @return the resolved {@link ClassPathNode}, or {@code null} if not found or ambiguous
	 */
	public static ClassPathNode resolveClass(Workspace workspace, String name) {
		if (workspace == null || name == null) return null;

		String normalized = normalizeClassName(name);
		if (normalized.isEmpty()) return null;

		// Try exact match first
		ClassPathNode exact = workspace.findClass(normalized);
		if (exact != null) return exact;

		// Try simple name match (unqualified class name)
		String simpleName = normalized.contains("/")
				? normalized.substring(normalized.lastIndexOf('/') + 1)
				: normalized;

		List<ClassPathNode> matches = workspace.classesStream()
				.filter(cpn -> {
					String fullName = cpn.getValue().getName();
					String candidateSimple = fullName.contains("/")
							? fullName.substring(fullName.lastIndexOf('/') + 1)
							: fullName;
					return candidateSimple.equals(simpleName);
				})
				.toList();

		// Only return if unambiguous
		if (matches.size() == 1) return matches.getFirst();
		return null;
	}

	/**
	 * Find class names similar to the given name within the workspace.
	 * <p>
	 * Uses substring matching and Levenshtein distance (threshold of 3)
	 * to find similar classes, sorted by relevance.
	 *
	 * @param workspace  the workspace to search
	 * @param name       the class name to search for
	 * @param maxResults maximum number of results to return
	 * @return list of similar class name strings in internal notation
	 */
	public static List<String> findSimilarClassNames(Workspace workspace, String name, int maxResults) {
		if (workspace == null || name == null || maxResults <= 0) return List.of();

		String normalized = normalizeClassName(name);
		if (normalized.isEmpty()) return List.of();

		String normalizedLower = normalized.toLowerCase();
		String simpleName = normalized.contains("/")
				? normalized.substring(normalized.lastIndexOf('/') + 1)
				: normalized;
		String simpleNameLower = simpleName.toLowerCase();

		record Scored(String className, int score) {}

		List<Scored> scored = new ArrayList<>();

		workspace.classesStream().forEach(cpn -> {
			String fullName = cpn.getValue().getName();
			String fullNameLower = fullName.toLowerCase();
			String candidateSimple = fullName.contains("/")
					? fullName.substring(fullName.lastIndexOf('/') + 1)
					: fullName;
			String candidateSimpleLower = candidateSimple.toLowerCase();

			// Substring match on full name or simple name
			if (fullNameLower.contains(normalizedLower) || candidateSimpleLower.contains(simpleNameLower)) {
				scored.add(new Scored(fullName, 0));
				return;
			}

			// Levenshtein distance on simple names
			int distance = levenshteinDistance(simpleNameLower, candidateSimpleLower);
			if (distance <= 3) {
				scored.add(new Scored(fullName, distance));
			}
		});

		return scored.stream()
				.sorted(Comparator.comparingInt(Scored::score).thenComparing(Scored::className))
				.limit(maxResults)
				.map(Scored::className)
				.toList();
	}

	/**
	 * Compute the Levenshtein (edit) distance between two strings.
	 *
	 * @param a first string
	 * @param b second string
	 * @return the minimum number of single-character edits to transform a into b
	 */
	static int levenshteinDistance(String a, String b) {
		if (a == null || b == null) return Integer.MAX_VALUE;

		int lenA = a.length();
		int lenB = b.length();

		// Quick exits
		if (lenA == 0) return lenB;
		if (lenB == 0) return lenA;

		int[] prev = new int[lenB + 1];
		int[] curr = new int[lenB + 1];

		for (int j = 0; j <= lenB; j++) {
			prev[j] = j;
		}

		for (int i = 1; i <= lenA; i++) {
			curr[0] = i;
			for (int j = 1; j <= lenB; j++) {
				int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
				curr[j] = Math.min(
						Math.min(curr[j - 1] + 1, prev[j] + 1),
						prev[j - 1] + cost
				);
			}
			// Swap
			int[] tmp = prev;
			prev = curr;
			curr = tmp;
		}

		return prev[lenB];
	}
}
