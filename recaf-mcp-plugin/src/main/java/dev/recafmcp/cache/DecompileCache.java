package dev.recafmcp.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.workspace.model.Workspace;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Shared decompilation source cache keyed by workspace/class/decompiler identity.
 */
public final class DecompileCache {
	private final boolean enabled;
	private final Cache<Key, String> cache;

	public DecompileCache(CacheConfig config) {
		this.enabled = config.enabled();
		this.cache = Caffeine.newBuilder()
				.maximumSize(config.maxEntries())
				.expireAfterWrite(Duration.ofSeconds(config.ttlSeconds()))
				.build();
	}

	public String getOrLoad(Key key, Supplier<String> loader) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(loader, "loader");
		if (!enabled) {
			return loader.get();
		}
		return cache.get(key, ignored -> loader.get());
	}

	public Key keyFor(Workspace workspace,
	                  long workspaceRevision,
	                  JvmClassInfo classInfo,
	                  String decompilerName) {
		Objects.requireNonNull(workspace, "workspace");
		Objects.requireNonNull(classInfo, "classInfo");
		Objects.requireNonNull(decompilerName, "decompilerName");
		return new Key(
				Integer.toHexString(System.identityHashCode(workspace)),
				workspaceRevision,
				classInfo.getName(),
				Arrays.hashCode(classInfo.getBytecode()),
				decompilerName
		);
	}

	public record Key(
			String workspaceIdentity,
			long workspaceRevision,
			String className,
			int classBytecodeHash,
			String decompilerName
	) {
	}
}
