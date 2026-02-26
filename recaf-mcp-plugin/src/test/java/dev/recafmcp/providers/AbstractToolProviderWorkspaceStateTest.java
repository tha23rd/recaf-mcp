package dev.recafmcp.providers;

import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbstractToolProviderWorkspaceStateTest {
	@Test
	void requireWorkspaceThrowsWhenManagerReportsNoCurrentWorkspace() {
		WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
		Workspace staleWorkspace = mock(Workspace.class);
		when(workspaceManager.getCurrent()).thenReturn(staleWorkspace);
		when(workspaceManager.hasCurrentWorkspace()).thenReturn(false);

		TestProvider provider = new TestProvider(mock(McpSyncServer.class), workspaceManager);
		assertThrows(IllegalStateException.class, provider::readWorkspace);
	}

	@Test
	void requireWorkspaceReturnsCurrentWorkspaceWhenOpen() {
		WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
		Workspace workspace = mock(Workspace.class);
		when(workspaceManager.getCurrent()).thenReturn(workspace);
		when(workspaceManager.hasCurrentWorkspace()).thenReturn(true);

		TestProvider provider = new TestProvider(mock(McpSyncServer.class), workspaceManager);
		assertSame(workspace, provider.readWorkspace());
	}

	private static final class TestProvider extends AbstractToolProvider {
		private TestProvider(McpSyncServer server, WorkspaceManager workspaceManager) {
			super(server, workspaceManager);
		}

		@Override
		public void registerTools() {
		}

		private Workspace readWorkspace() {
			return requireWorkspace();
		}
	}
}
