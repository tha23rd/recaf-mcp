package dev.recafmcp.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Utility for paginating lists and streams of results.
 */
public final class PaginationUtil {

	/** Default number of items per page. */
	public static final int DEFAULT_LIMIT = 100;

	/** Maximum allowed items per page. */
	public static final int MAX_LIMIT = 1000;

	private PaginationUtil() {
		// Utility class
	}

	/**
	 * Return a sublist of the given items based on offset and limit.
	 * <p>
	 * Negative offsets are clamped to 0. Limits are clamped to
	 * [{@code 1}, {@link #MAX_LIMIT}].
	 *
	 * @param items  the full list
	 * @param offset zero-based starting index
	 * @param limit  maximum number of items to return
	 * @param <T>    element type
	 * @return the paginated sublist (may be empty, never null)
	 */
	public static <T> List<T> paginate(List<T> items, int offset, int limit) {
		if (items == null || items.isEmpty()) return List.of();

		int safeOffset = Math.max(0, offset);
		int safeLimit = Math.clamp(limit, 1, MAX_LIMIT);

		if (safeOffset >= items.size()) return List.of();

		int end = Math.min(safeOffset + safeLimit, items.size());
		return items.subList(safeOffset, end);
	}

	/**
	 * Apply skip/limit pagination to a stream.
	 * <p>
	 * Negative offsets are clamped to 0. Limits are clamped to
	 * [{@code 1}, {@link #MAX_LIMIT}].
	 *
	 * @param stream the source stream
	 * @param offset number of elements to skip
	 * @param limit  maximum number of elements to return
	 * @param <T>    element type
	 * @return the paginated stream
	 */
	public static <T> Stream<T> paginate(Stream<T> stream, int offset, int limit) {
		if (stream == null) return Stream.empty();

		int safeOffset = Math.max(0, offset);
		int safeLimit = Math.clamp(limit, 1, MAX_LIMIT);

		return stream.skip(safeOffset).limit(safeLimit);
	}

	/**
	 * Build a paginated result map containing items and pagination metadata.
	 *
	 * @param items      the page of items
	 * @param offset     the offset used
	 * @param limit      the limit used
	 * @param totalCount the total number of items (before pagination)
	 * @param <T>        element type
	 * @return an ordered map with keys: items, offset, limit, count, totalCount, hasMore
	 */
	public static <T> Map<String, Object> paginatedResult(List<T> items, int offset, int limit, int totalCount) {
		List<T> safeItems = (items != null) ? items : List.of();
		int safeOffset = Math.max(0, offset);
		int safeLimit = Math.clamp(limit, 1, MAX_LIMIT);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("items", safeItems);
		result.put("offset", safeOffset);
		result.put("limit", safeLimit);
		result.put("count", safeItems.size());
		result.put("totalCount", totalCount);
		result.put("hasMore", safeOffset + safeItems.size() < totalCount);
		return result;
	}
}
