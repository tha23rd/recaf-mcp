package dev.recafmcp.providers;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import software.coley.recaf.services.workspace.WorkspaceManager;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AbstractToolProviderRegistryTest {
	@Test
	void registerToolAddsToRegistryWhenPresent() {
		ToolRegistry registry = new ToolRegistry();
		McpSyncServer mockServer = mock(McpSyncServer.class);

		TestProvider provider = new TestProvider(mockServer, mock(WorkspaceManager.class), registry);
		provider.registerTools();

		List<ToolRegistry.ToolEntry> entries = registry.search("my-test");
		assertEquals(1, entries.size());
		assertEquals("my-test-tool", entries.get(0).name());
		assertEquals("Test", entries.get(0).category());
	}

	@Test
	void registerToolWorksWithNullRegistry() {
		McpSyncServer mockServer = mock(McpSyncServer.class);
		TestProvider provider = new TestProvider(mockServer, mock(WorkspaceManager.class), null);
		assertDoesNotThrow(provider::registerTools);
	}

	private static final class TestProvider extends AbstractToolProvider {
		private TestProvider(McpSyncServer server, WorkspaceManager workspaceManager, ToolRegistry registry) {
			super(server, workspaceManager);
			setToolRegistry(registry);
		}

		@Override
		public void registerTools() {
			McpSchema.Tool tool = McpSchema.Tool.builder()
					.name("my-test-tool")
					.description("A test tool for my thing")
					.inputSchema(createSchema(Map.of(), List.of()))
					.build();
			registerTool(tool, (exchange, args) -> createTextResult("ok"));
		}
	}
}
