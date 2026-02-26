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
	private final AtomicLong nextIdentity = new AtomicLong(1);
	private final ConcurrentMap<Workspace, WorkspaceState> states = new ConcurrentHashMap<>();

	public long getRevision(Workspace workspace) {
		Objects.requireNonNull(workspace, "workspace");
		return stateFor(workspace).revision().get();
	}

	public long bump(Workspace workspace) {
		Objects.requireNonNull(workspace, "workspace");
		return stateFor(workspace).revision().incrementAndGet();
	}

	public long getIdentity(Workspace workspace) {
		Objects.requireNonNull(workspace, "workspace");
		return stateFor(workspace).identity();
	}

	public void remove(Workspace workspace) {
		if (workspace == null) {
			return;
		}
		states.remove(workspace);
	}

	private WorkspaceState stateFor(Workspace workspace) {
		return states.computeIfAbsent(
				workspace,
				ignored -> new WorkspaceState(nextIdentity.getAndIncrement(), new AtomicLong())
		);
	}

	private record WorkspaceState(long identity, AtomicLong revision) {
	}
}
