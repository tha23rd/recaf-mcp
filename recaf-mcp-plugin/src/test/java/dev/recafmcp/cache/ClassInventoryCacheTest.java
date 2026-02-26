package dev.recafmcp.cache;

import dev.recafmcp.util.ClassResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassInventoryCacheTest {
	@Test
	void inventoryBuildIsReusedForNavigationResourcesAndResolverSuggestions() {
		ClassInventoryCache cache = new ClassInventoryCache(new CacheConfig(true, 120, 1000));
		ClassInventoryCache.Key key = new ClassInventoryCache.Key("workspace-1", 42L);
		AtomicInteger loads = new AtomicInteger();

		ClassInventoryCache.Inventory first = cache.getOrLoad(key, () -> {
			loads.incrementAndGet();
			return new ClassInventoryCache.Inventory(
					List.of(
							new ClassInventoryCache.JvmClassEntry("com/example/Foo", "java/lang/Object", 1, 1, 2, 3),
							new ClassInventoryCache.JvmClassEntry("org/demo/Bar", "java/lang/Object", 1, 0, 1, 1),
							new ClassInventoryCache.JvmClassEntry("Root", "java/lang/Object", 1, 0, 0, 0)
					),
					List.of("com/example", "org/demo"),
					true,
					List.of("com/example/Foo", "org/demo/Bar", "Root"),
					Map.of(
							"Foo", List.of("com/example/Foo"),
							"Bar", List.of("org/demo/Bar"),
							"Root", List.of("Root")
					)
			);
		});

		// class-list / recaf://classes consume the precomputed class metadata.
		assertEquals(List.of("com/example/Foo", "org/demo/Bar", "Root"),
				first.jvmClassEntries().stream().map(ClassInventoryCache.JvmClassEntry::name).toList());
		assertEquals(2, first.jvmClassEntries().getFirst().methodCount());

		// package-list consumes the precomputed package inventory.
		assertEquals(List.of("(default)", "com/example", "org/demo"), first.packageDisplayNames());

		// resolver suggestion base consumes the same inventory snapshot.
		List<String> suggestions = ClassResolver.findSimilarClassNames(first, "Foz", 5);
		assertEquals("com/example/Foo", suggestions.getFirst());
		assertTrue(suggestions.contains("com/example/Foo"));

		ClassInventoryCache.Inventory second = cache.getOrLoad(key, () -> {
			loads.incrementAndGet();
			return new ClassInventoryCache.Inventory(
					List.of(new ClassInventoryCache.JvmClassEntry("different/Value", null, 0, 0, 0, 0)),
					List.of("different"),
					false,
					List.of("different/Value"),
					Map.of("Value", List.of("different/Value"))
			);
		});

		assertEquals(first, second);
		assertEquals(1, loads.get());
	}
}
