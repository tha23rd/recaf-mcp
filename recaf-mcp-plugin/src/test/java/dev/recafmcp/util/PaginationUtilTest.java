package dev.recafmcp.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PaginationUtilTest {

	private static final List<String> ITEMS = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j");

	// --- paginate(List) ---

	@Test
	void paginate_basicSublist() {
		List<String> result = PaginationUtil.paginate(ITEMS, 0, 3);
		assertEquals(List.of("a", "b", "c"), result);
	}

	@Test
	void paginate_withOffset() {
		List<String> result = PaginationUtil.paginate(ITEMS, 3, 3);
		assertEquals(List.of("d", "e", "f"), result);
	}

	@Test
	void paginate_beyondEnd() {
		List<String> result = PaginationUtil.paginate(ITEMS, 8, 5);
		assertEquals(List.of("i", "j"), result);
	}

	@Test
	void paginate_offsetBeyondSize() {
		List<String> result = PaginationUtil.paginate(ITEMS, 100, 5);
		assertTrue(result.isEmpty());
	}

	@Test
	void paginate_negativeOffsetClampedToZero() {
		List<String> result = PaginationUtil.paginate(ITEMS, -5, 3);
		assertEquals(List.of("a", "b", "c"), result);
	}

	@Test
	void paginate_nullList() {
		List<String> result = PaginationUtil.paginate((List<String>) null, 0, 10);
		assertTrue(result.isEmpty());
	}

	@Test
	void paginate_emptyList() {
		List<String> result = PaginationUtil.paginate(List.of(), 0, 10);
		assertTrue(result.isEmpty());
	}

	// --- paginatedResult ---

	@Test
	void paginatedResult_structureAndValues() {
		List<String> page = List.of("a", "b", "c");
		Map<String, Object> result = PaginationUtil.paginatedResult(page, 0, 3, 10);

		assertEquals(page, result.get("items"));
		assertEquals(0, result.get("offset"));
		assertEquals(3, result.get("limit"));
		assertEquals(3, result.get("count"));
		assertEquals(10, result.get("totalCount"));
		assertEquals(true, result.get("hasMore"));
	}

	@Test
	void paginatedResult_lastPage() {
		List<String> page = List.of("i", "j");
		Map<String, Object> result = PaginationUtil.paginatedResult(page, 8, 3, 10);

		assertEquals(2, result.get("count"));
		assertEquals(10, result.get("totalCount"));
		assertEquals(false, result.get("hasMore"));
	}

	@Test
	void paginatedResult_nullItems() {
		Map<String, Object> result = PaginationUtil.paginatedResult(null, 0, 10, 0);

		assertEquals(List.of(), result.get("items"));
		assertEquals(0, result.get("count"));
	}

	@Test
	void paginatedResult_containsAllExpectedKeys() {
		Map<String, Object> result = PaginationUtil.paginatedResult(List.of(), 0, 10, 0);

		assertTrue(result.containsKey("items"));
		assertTrue(result.containsKey("offset"));
		assertTrue(result.containsKey("limit"));
		assertTrue(result.containsKey("count"));
		assertTrue(result.containsKey("totalCount"));
		assertTrue(result.containsKey("hasMore"));
		assertEquals(6, result.size());
	}

	// --- Constants ---

	@Test
	void constants() {
		assertEquals(100, PaginationUtil.DEFAULT_LIMIT);
		assertEquals(1000, PaginationUtil.MAX_LIMIT);
	}
}
