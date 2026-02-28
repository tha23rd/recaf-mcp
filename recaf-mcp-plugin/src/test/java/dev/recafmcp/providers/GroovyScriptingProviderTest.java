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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroovyScriptingProviderTest {
	private McpSyncServer mockServer;
	private WorkspaceManager mockWorkspaceManager;
	private GroovyScriptingProvider provider;

	@BeforeEach
	void setUp() {
		mockServer = mock(McpSyncServer.class);
		mockWorkspaceManager = mock(WorkspaceManager.class);
		provider = new GroovyScriptingProvider(mockServer, mockWorkspaceManager);
	}

	private Map<String, SyncToolSpecification> captureTools() {
		provider.registerTools();
		ArgumentCaptor<SyncToolSpecification> captor = ArgumentCaptor.forClass(SyncToolSpecification.class);
		verify(mockServer, atLeastOnce()).addTool(captor.capture());
		Map<String, SyncToolSpecification> byName = new LinkedHashMap<>();
		for (SyncToolSpecification spec : captor.getAllValues()) {
			byName.put(spec.tool().name(), spec);
		}
		return byName;
	}

	private CallToolResult callTool(SyncToolSpecification spec, Map<String, Object> args) {
		McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
		CallToolRequest request = mock(CallToolRequest.class);
		when(request.arguments()).thenReturn(args);
		return spec.callHandler().apply(exchange, request);
	}

	private String text(CallToolResult result) {
		return ((TextContent) result.content().getFirst()).text();
	}

	@Test
	void executeSimpleArithmeticScript() {
		Map<String, SyncToolSpecification> tools = captureTools();
		CallToolResult result = callTool(tools.get("execute-recaf-script"), Map.of("code", "return 2 + 2"));

		assertFalse(Boolean.TRUE.equals(result.isError()), "Should not be error");
		assertEquals("4", text(result).trim());
	}

	@Test
	void executeScriptWithPrintln() {
		Map<String, SyncToolSpecification> tools = captureTools();
		CallToolResult result = callTool(tools.get("execute-recaf-script"),
				Map.of("code", "println 'hello world'\nreturn 'done'"));

		assertFalse(Boolean.TRUE.equals(result.isError()));
		assertTrue(text(result).contains("hello world"), "Expected captured println output: " + text(result));
	}

	@Test
	void executeSyntaxErrorReturnsErrorResult() {
		Map<String, SyncToolSpecification> tools = captureTools();
		CallToolResult result = callTool(tools.get("execute-recaf-script"), Map.of("code", "def unclosed = {"));

		assertTrue(Boolean.TRUE.equals(result.isError()), "Should be error result");
		assertTrue(text(result).contains("Error") || text(result).contains("error"));
	}

	@Test
	void executeRuntimeExceptionReturnsErrorResult() {
		Map<String, SyncToolSpecification> tools = captureTools();
		CallToolResult result = callTool(tools.get("execute-recaf-script"),
				Map.of("code", "def x = 1 / 0"));

		assertTrue(Boolean.TRUE.equals(result.isError()));
		String output = text(result);
		assertTrue(output.contains("/ by zero") || output.toLowerCase().contains("arithmetic")
						|| output.toLowerCase().contains("division by zero"),
				"Expected arithmetic runtime error, got: " + output);
	}

	@Test
	void executeLongRunningScriptTimesOut() {
		Map<String, SyncToolSpecification> tools = captureTools();
		CallToolResult result = callTool(tools.get("execute-recaf-script"),
				Map.of("code", "while(true) { }", "timeoutMs", 100));

		assertTrue(Boolean.TRUE.equals(result.isError()));
		assertTrue(text(result).toLowerCase().contains("timed out"));
	}

	@Test
	void executeScriptOutputIsCapped() {
		Map<String, SyncToolSpecification> tools = captureTools();
		CallToolResult result = callTool(tools.get("execute-recaf-script"),
				Map.of("code", "for (int i=0; i<100000; i++) { print('x') }\nreturn 'done'"));

		assertFalse(Boolean.TRUE.equals(result.isError()));
		assertTrue(text(result).contains("stdout truncated"));
	}

	@Test
	void executeScriptCanAccessWorkspaceManagerBinding() {
		Map<String, SyncToolSpecification> tools = captureTools();
		CallToolResult result = callTool(tools.get("execute-recaf-script"),
				Map.of("code", "return workspaceManager != null ? 'bound' : 'unbound'"));

		assertFalse(Boolean.TRUE.equals(result.isError()));
		assertEquals("bound", text(result).trim());
	}

	@Test
	void describeApiReturnsContentForKnownKeyword() {
		Map<String, SyncToolSpecification> tools = captureTools();
		CallToolResult result = callTool(tools.get("describe-recaf-api"), Map.of("query", "decompile"));

		assertFalse(Boolean.TRUE.equals(result.isError()));
		assertTrue(text(result).toLowerCase().contains("decompil"), "Expected decompiler section: " + text(result));
	}

	@Test
	void describeApiEmptyQueryReturnsFullReference() {
		Map<String, SyncToolSpecification> tools = captureTools();
		CallToolResult result = callTool(tools.get("describe-recaf-api"), Map.of("query", ""));

		assertFalse(Boolean.TRUE.equals(result.isError()));
		assertTrue(text(result).length() > 500, "Expected full reference content");
	}

	@Test
	void describeApiNoMatchReturnsHelpMessage() {
		Map<String, SyncToolSpecification> tools = captureTools();
		CallToolResult result = callTool(tools.get("describe-recaf-api"),
				Map.of("query", "xyzzy-nonexistent-api-thing"));

		assertFalse(Boolean.TRUE.equals(result.isError()));
		String out = text(result);
		assertTrue(out.contains("No sections") || out.contains("not found") || out.contains("empty query"),
				"Expected not-found message: " + out);
	}
}
