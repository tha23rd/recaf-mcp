package dev.recafmcp.cache;

import software.coley.recaf.workspace.model.Workspace;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks monotonically increasing workspace revisions for cache invalidation.
 */
public final class WorkspaceRevisionTracker {
	private final ConcurrentMap<Workspace, AtomicLong> revisions = new ConcurrentHashMap<>();

	public long getRevision(Workspace workspace) {
		Objects.requireNonNull(workspace, "workspace");
		return revisions.computeIfAbsent(workspace, ignored -> new AtomicLong()).get();
	}

	public long bump(Workspace workspace) {
		Objects.requireNonNull(workspace, "workspace");
		return revisions.computeIfAbsent(workspace, ignored -> new AtomicLong()).incrementAndGet();
	}
}
