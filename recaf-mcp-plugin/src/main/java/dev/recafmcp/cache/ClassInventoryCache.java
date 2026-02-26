package dev.recafmcp.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.workspace.model.Workspace;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Shared cache of class inventory snapshots used by navigation/resources/resolver paths.
 */
public final class ClassInventoryCache {
	private final boolean enabled;
	private final Cache<Key, Inventory> cache;

	public ClassInventoryCache(CacheConfig config) {
		this.enabled = config.enabled();
		this.cache = Caffeine.newBuilder()
				.maximumSize(config.maxEntries())
				.expireAfterWrite(Duration.ofSeconds(config.ttlSeconds()))
				.build();
	}

	public Inventory getOrLoad(Key key, Supplier<Inventory> loader) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(loader, "loader");
		if (!enabled) {
			return loader.get();
		}
		return cache.get(key, ignored -> loader.get());
	}

	public Key keyFor(Workspace workspace, long workspaceRevision) {
		Objects.requireNonNull(workspace, "workspace");
		return new Key(
				Integer.toHexString(System.identityHashCode(workspace)),
				workspaceRevision
		);
	}

	public static Inventory buildInventory(Workspace workspace) {
		Objects.requireNonNull(workspace, "workspace");

		List<JvmClassEntry> jvmClassEntries = workspace.jvmClassesStream()
				.map(cpn -> (JvmClassInfo) cpn.getValue())
				.map(cls -> new JvmClassEntry(
						cls.getName(),
						cls.getSuperName(),
						cls.getAccess(),
						cls.getInterfaces().size(),
						cls.getMethods().size(),
						cls.getFields().size()
				))
				.sorted((a, b) -> a.name().compareTo(b.name()))
				.toList();

		TreeSet<String> packageNames = new TreeSet<>();
		boolean hasDefaultPackage = false;
		for (JvmClassEntry classEntry : jvmClassEntries) {
			int lastSlash = classEntry.name().lastIndexOf('/');
			if (lastSlash > 0) {
				packageNames.add(classEntry.name().substring(0, lastSlash));
			} else {
				hasDefaultPackage = true;
			}
		}

		List<String> allClassNames = workspace.classesStream()
				.map(cpn -> cpn.getValue().getName())
				.sorted()
				.toList();
		Map<String, List<String>> classNamesBySimpleName = buildSimpleNameIndex(allClassNames);

		return new Inventory(
				jvmClassEntries,
				List.copyOf(packageNames),
				hasDefaultPackage,
				allClassNames,
				classNamesBySimpleName
		);
	}

	private static Map<String, List<String>> buildSimpleNameIndex(List<String> classNames) {
		LinkedHashMap<String, List<String>> simpleNameMap = new LinkedHashMap<>();
		for (String className : classNames) {
			String simple = simpleName(className);
			simpleNameMap.computeIfAbsent(simple, ignored -> new ArrayList<>()).add(className);
		}
		LinkedHashMap<String, List<String>> frozen = new LinkedHashMap<>();
		for (Map.Entry<String, List<String>> entry : simpleNameMap.entrySet()) {
			frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
		}
		return Collections.unmodifiableMap(frozen);
	}

	private static String simpleName(String className) {
		int lastSlash = className.lastIndexOf('/');
		return lastSlash >= 0 ? className.substring(lastSlash + 1) : className;
	}

	public record Key(
			String workspaceIdentity,
			long workspaceRevision
	) {
	}

	public record JvmClassEntry(
			String name,
			String superName,
			int access,
			int interfaceCount,
			int methodCount,
			int fieldCount
	) {
	}

	public record Inventory(
			List<JvmClassEntry> jvmClassEntries,
			List<String> packageNames,
			boolean hasDefaultPackage,
			List<String> allClassNames,
			Map<String, List<String>> classNamesBySimpleName
	) {
		public Inventory {
			Objects.requireNonNull(jvmClassEntries, "jvmClassEntries");
			Objects.requireNonNull(packageNames, "packageNames");
			Objects.requireNonNull(allClassNames, "allClassNames");
			Objects.requireNonNull(classNamesBySimpleName, "classNamesBySimpleName");

			jvmClassEntries = List.copyOf(jvmClassEntries);
			packageNames = List.copyOf(packageNames);
			allClassNames = List.copyOf(allClassNames);

			LinkedHashMap<String, List<String>> frozen = new LinkedHashMap<>();
			for (Map.Entry<String, List<String>> entry : classNamesBySimpleName.entrySet()) {
				frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
			}
			classNamesBySimpleName = Collections.unmodifiableMap(frozen);
		}

		public List<String> packageDisplayNames() {
			List<String> names = new ArrayList<>(packageNames.size() + (hasDefaultPackage ? 1 : 0));
			if (hasDefaultPackage) {
				names.add("(default)");
			}
			names.addAll(packageNames);
			return List.copyOf(names);
		}
	}
}
