package dev.recafmcp.cache;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchQueryCacheTest {
	@Test
	void sameKeyReturnsCachedResults() {
		SearchQueryCache cache = new SearchQueryCache(new CacheConfig(true, 120, 1000));
		SearchQueryCache.Key key = new SearchQueryCache.Key(
				"workspace-1",
				5L,
				"search-strings",
				"query=hello"
		);

		AtomicInteger loads = new AtomicInteger();
		List<Map<String, Object>> first = cache.getOrLoad(key, () -> {
			loads.incrementAndGet();
			return List.of(Map.of("match", "a"));
		});
		List<Map<String, Object>> second = cache.getOrLoad(key, () -> {
			loads.incrementAndGet();
			return List.of(Map.of("match", "b"));
		});

		assertEquals(first, second);
		assertEquals(1, loads.get());
	}
}
