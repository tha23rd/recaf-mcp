package dev.recafmcp.cache;

import org.junit.jupiter.api.Test;
import software.coley.recaf.workspace.model.Workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;

class WorkspaceRevisionTrackerTest {
	@Test
	void bumpIncrementsRevision() {
		WorkspaceRevisionTracker tracker = new WorkspaceRevisionTracker();
		Workspace workspace = mock(Workspace.class);

		long revision1 = tracker.getRevision(workspace);
		tracker.bump(workspace);
		long revision2 = tracker.getRevision(workspace);

		assertEquals(revision1 + 1, revision2);
	}

	@Test
	void identityIsStableUntilWorkspaceIsRemoved() {
		WorkspaceRevisionTracker tracker = new WorkspaceRevisionTracker();
		Workspace workspace = mock(Workspace.class);

		long firstIdentity = tracker.getIdentity(workspace);
		long secondIdentity = tracker.getIdentity(workspace);
		assertEquals(firstIdentity, secondIdentity);

		tracker.remove(workspace);
		long identityAfterRemove = tracker.getIdentity(workspace);
		assertNotEquals(firstIdentity, identityAfterRemove);
	}
}
