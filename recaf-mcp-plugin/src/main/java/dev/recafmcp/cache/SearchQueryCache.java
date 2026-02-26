package dev.recafmcp.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Shared cache for serialized search/xref result DTO lists.
 */
public final class SearchQueryCache {
	private final boolean enabled;
	private final Cache<Key, List<Map<String, Object>>> cache;

	public SearchQueryCache(CacheConfig config) {
		this.enabled = config.enabled();
		this.cache = Caffeine.newBuilder()
				.maximumSize(config.maxEntries())
				.expireAfterWrite(Duration.ofSeconds(config.ttlSeconds()))
				.build();
	}

	public List<Map<String, Object>> getOrLoad(Key key, Supplier<List<Map<String, Object>>> loader) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(loader, "loader");
		if (!enabled) {
			return freeze(loader.get());
		}
		return cache.get(key, ignored -> freeze(loader.get()));
	}

	public Key keyFor(long workspaceIdentity, long workspaceRevision, String queryType, String normalizedQuery) {
		Objects.requireNonNull(queryType, "queryType");
		Objects.requireNonNull(normalizedQuery, "normalizedQuery");
		return new Key(
				workspaceIdentity,
				workspaceRevision,
				queryType,
				normalizedQuery
		);
	}

	private static List<Map<String, Object>> freeze(List<Map<String, Object>> list) {
		List<Map<String, Object>> copy = new ArrayList<>(list.size());
		for (Map<String, Object> item : list) {
			copy.add(Collections.unmodifiableMap(new LinkedHashMap<>(item)));
		}
		return Collections.unmodifiableList(copy);
	}

	public record Key(
			long workspaceIdentity,
			long workspaceRevision,
			String queryType,
			String normalizedQuery
	) {
	}
}
