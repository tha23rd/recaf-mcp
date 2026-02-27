package dev.recafmcp.cache;

/**
 * Cache configuration loaded from system properties.
 */
public record CacheConfig(
		boolean enabled,
		int ttlSeconds,
		int maxEntries
) {
	private static final boolean DEFAULT_ENABLED = true;
	private static final int DEFAULT_TTL_SECONDS = 120;
	private static final int DEFAULT_MAX_ENTRIES = 1000;

	public static CacheConfig fromSystemProperties() {
		boolean enabled = Boolean.parseBoolean(
				System.getProperty("recaf.mcp.cache.enabled", Boolean.toString(DEFAULT_ENABLED))
		);
		int ttlSeconds = parsePositiveIntProperty(
				"recaf.mcp.cache.ttl.seconds",
				DEFAULT_TTL_SECONDS
		);
		int maxEntries = parsePositiveIntProperty(
				"recaf.mcp.cache.max.entries",
				DEFAULT_MAX_ENTRIES
		);
		return new CacheConfig(enabled, ttlSeconds, maxEntries);
	}

	private static int parsePositiveIntProperty(String key, int defaultValue) {
		String value = System.getProperty(key);
		if (value == null || value.isBlank()) {
			return defaultValue;
		}
		try {
			int parsed = Integer.parseInt(value);
			return parsed > 0 ? parsed : defaultValue;
		} catch (NumberFormatException ignored) {
			return defaultValue;
		}
	}
}
