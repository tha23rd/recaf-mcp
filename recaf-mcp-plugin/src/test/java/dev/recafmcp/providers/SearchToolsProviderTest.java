package dev.recafmcp.providers;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.coley.recaf.services.workspace.WorkspaceManager;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchToolsProviderTest {
	private ToolRegistry registry;
	private McpSyncServer mockServer;
	private SearchToolsProvider provider;

	@BeforeEach
	void setUp() {
		registry = new ToolRegistry();
		registry.register("decompile-class", "Decompile a Java class to source code", "Decompiler");
		registry.register("search-strings", "Search for string constants in classes", "Search");
		registry.register("workspace-list-classes", "List all classes in the loaded workspace", "Workspace");

		mockServer = mock(McpSyncServer.class);
		provider = new SearchToolsProvider(mockServer, mock(WorkspaceManager.class), registry);
	}

	private SyncToolSpecification captureRegisteredTool() {
		provider.registerTools();
		ArgumentCaptor<SyncToolSpecification> captor = ArgumentCaptor.forClass(SyncToolSpecification.class);
		verify(mockServer).addTool(captor.capture());
		return captor.getValue();
	}

	@Test
	void toolIsNamedSearchTools() {
		SyncToolSpecification spec = captureRegisteredTool();
		assertEquals("search-tools", spec.tool().name());
	}

	@Test
	void searchByKeywordReturnsMatchingTools() {
		SyncToolSpecification spec = captureRegisteredTool();
		McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);

		CallToolResult result = spec.callHandler().apply(exchange, mockRequest(Map.of("query", "decompile")));

		String text = ((TextContent) result.content().getFirst()).text();
		assertTrue(text.contains("decompile-class"), "Expected decompile-class in results: " + text);
		assertFalse(text.contains("search-strings"), "search-strings should not appear: " + text);
	}

	@Test
	void emptyQueryReturnsAllTools() {
		SyncToolSpecification spec = captureRegisteredTool();
		McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);

		CallToolResult result = spec.callHandler().apply(exchange, mockRequest(Map.of("query", "")));

		String text = ((TextContent) result.content().getFirst()).text();
		assertTrue(text.contains("decompile-class"));
		assertTrue(text.contains("search-strings"));
		assertTrue(text.contains("workspace-list-classes"));
	}

	@Test
	void noMatchReturnsHelpfulMessage() {
		SyncToolSpecification spec = captureRegisteredTool();
		McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);

		CallToolResult result = spec.callHandler().apply(exchange, mockRequest(Map.of("query", "xyzzy-nonexistent-thing")));

		String text = ((TextContent) result.content().getFirst()).text();
		assertTrue(text.contains("No tools found") || text.contains("0 tools"),
				"Expected no-match message, got: " + text);
	}

	private CallToolRequest mockRequest(Map<String, Object> args) {
		CallToolRequest request = mock(CallToolRequest.class);
		when(request.arguments()).thenReturn(args);
		return request;
	}
}
