package dev.recafmcp.cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DecompileCacheTest {
	@Test
	void cacheHitAvoidsSecondLoad() {
		DecompileCache cache = new DecompileCache(new CacheConfig(true, 120, 1000));
		DecompileCache.Key key = new DecompileCache.Key(
				1L,
				1L,
				"com/example/Test",
				12345,
				"CFR"
		);

		AtomicInteger loads = new AtomicInteger();
		String first = cache.getOrLoad(key, () -> {
			loads.incrementAndGet();
			return "src";
		});
		String second = cache.getOrLoad(key, () -> {
			loads.incrementAndGet();
			return "src2";
		});

		assertEquals("src", first);
		assertEquals("src", second);
		assertEquals(1, loads.get());
	}
}
