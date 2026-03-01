package dev.recafmcp.server;

/**
 * Shared text truncation for Code Mode tools to keep response size bounded.
 */
public final class CodeModeOutputTruncator {
	public static final int MAX_OUTPUT_CHARS = 4_096;

	private CodeModeOutputTruncator() {
	}

	/**
	 * Truncate text to a deterministic maximum character count.
	 *
	 * @param text Text content to truncate.
	 * @return Original text if under the limit, otherwise a truncated result with marker.
	 */
	public static String truncate(String text) {
		return truncate(text, MAX_OUTPUT_CHARS);
	}

	static String truncate(String text, int maxChars) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		if (maxChars < 1) {
			throw new IllegalArgumentException("maxChars must be >= 1");
		}
		if (text.length() <= maxChars) {
			return text;
		}

		String marker = buildMarker(text.length() - maxChars, text.length());
		if (marker.length() >= maxChars) {
			return marker.substring(0, maxChars);
		}
		int keepChars = maxChars - marker.length();
		if (keepChars < 0) {
			keepChars = 0;
		}

		// Recompute marker once after keepChars is known so omitted count is accurate.
		marker = buildMarker(text.length() - keepChars, text.length());
		if (marker.length() >= maxChars) {
			return marker.substring(0, maxChars);
		}
		keepChars = Math.max(0, maxChars - marker.length());
		return text.substring(0, keepChars) + marker;
	}

	private static String buildMarker(int omittedChars, int totalChars) {
		return "\n...[output truncated: omitted " + omittedChars +
				" of " + totalChars + " characters]";
	}
}
