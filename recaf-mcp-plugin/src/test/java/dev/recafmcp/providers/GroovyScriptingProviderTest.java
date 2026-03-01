package dev.recafmcp.providers;

import dev.recafmcp.server.CodeModeOutputTruncator;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroovyScriptingProviderTest {
	private static final String SCRIPT_THREAD_PREFIX = "recaf-mcp-groovy-script-";

	private McpSyncServer mockServer;
	private WorkspaceManager mockWorkspaceManager;
	private GroovyScriptingProvider provider;

	@BeforeEach
	void setUp() {
		mockServer = mock(McpSyncServer.class);
		mockWorkspaceManager = mock(WorkspaceManager.class);
		provider = createProvider(false);
	}

	private GroovyScriptingProvider createProvider(boolean scriptExecutionEnabled) {
		return new GroovyScriptingProvider(
				mockServer,
				mockWorkspaceManager,
				null,
				null,
				null,
				null,
				() -> scriptExecutionEnabled
		);
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
	void executeScriptBlockedByPolicyByDefault() {
		Map<String, SyncToolSpecification> tools = captureTools();
		CallToolResult result = callTool(tools.get("execute-recaf-script"), Map.of("code", "return 2 + 2"));

		assertTrue(Boolean.TRUE.equals(result.isError()), "Disabled-by-default policy should return an error");
		assertTrue(text(result).toLowerCase(Locale.ROOT).contains("disabled by policy"));
		assertTrue(text(result).contains("recaf.mcp.script.execution.enabled"));
	}

	@Test
	void executeSimpleArithmeticScriptWhenEnabled() {
		provider = createProvider(true);
		Map<String, SyncToolSpecification> tools = captureTools();
		CallToolResult result = callTool(tools.get("execute-recaf-script"), Map.of("code", "return 2 + 2"));

		assertFalse(Boolean.TRUE.equals(result.isError()), "Should not be error");
		assertEquals("4", text(result).trim());
	}

	@Test
	void executeScriptWithPrintln() {
		provider = createProvider(true);
		Map<String, SyncToolSpecification> tools = captureTools();
		CallToolResult result = callTool(tools.get("execute-recaf-script"),
				Map.of("code", "println 'hello world'\nreturn 'done'"));

		assertFalse(Boolean.TRUE.equals(result.isError()));
		assertTrue(text(result).contains("hello world"), "Expected captured println output: " + text(result));
	}

	@Test
	void executeSyntaxErrorReturnsErrorResult() {
		provider = createProvider(true);
		Map<String, SyncToolSpecification> tools = captureTools();
		CallToolResult result = callTool(tools.get("execute-recaf-script"), Map.of("code", "def unclosed = {"));

		assertTrue(Boolean.TRUE.equals(result.isError()), "Should be error result");
		assertTrue(text(result).contains("Error") || text(result).contains("error"));
	}

	@Test
	void executeRuntimeExceptionReturnsErrorResult() {
		provider = createProvider(true);
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
	void executeLongRunningScriptTimesOutAndIsInterrupted() throws InterruptedException {
		provider = createProvider(true);
		Map<String, SyncToolSpecification> tools = captureTools();
		long baselineThreadCount = activeScriptThreadCount();
		CallToolResult result = callTool(tools.get("execute-recaf-script"),
				Map.of("code", "while(true) { }", "timeoutMs", 100));

		assertTrue(Boolean.TRUE.equals(result.isError()));
		String message = text(result).toLowerCase(Locale.ROOT);
		assertTrue(message.contains("timed out"));
		assertTrue(message.contains("interrupted") || message.contains("terminated"));
		awaitScriptThreadCount(baselineThreadCount, TimeUnit.SECONDS.toMillis(2));
		assertEquals(baselineThreadCount, activeScriptThreadCount(), "Timed out script thread should terminate");
	}

	@Test
	void executeScriptOutputIsCapped() {
		provider = createProvider(true);
		Map<String, SyncToolSpecification> tools = captureTools();
		CallToolResult result = callTool(tools.get("execute-recaf-script"),
				Map.of("code", "for (int i=0; i<100000; i++) { print('x') }\nreturn 'done'"));

		assertFalse(Boolean.TRUE.equals(result.isError()));
		assertTrue(text(result).contains("stdout truncated"));
	}

	@Test
	void executeLargeReturnValueIsTruncated() {
		provider = createProvider(true);
		Map<String, SyncToolSpecification> tools = captureTools();
		CallToolResult result = callTool(tools.get("execute-recaf-script"),
				Map.of("code", "return 'x' * 70000"));

		assertFalse(Boolean.TRUE.equals(result.isError()));
		String output = text(result);
		assertTrue(output.toLowerCase(Locale.ROOT).contains("truncated"),
				"Expected truncation marker for large return value: " + output);
		assertTrue(output.length() <= CodeModeOutputTruncator.MAX_OUTPUT_CHARS,
				"Expected bounded output length");
	}

	@Test
	void executeScriptDoesNotExposeWorkspaceManagerBinding() {
		provider = createProvider(true);
		Map<String, SyncToolSpecification> tools = captureTools();
		CallToolResult result = callTool(tools.get("execute-recaf-script"),
				Map.of("code", "return workspaceManager"));

		assertTrue(Boolean.TRUE.equals(result.isError()));
		assertTrue(text(result).contains("workspaceManager"));
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
	void describeApiEmptyQueryIsTruncatedWhenOverLimit() {
		Map<String, SyncToolSpecification> tools = captureTools();
		CallToolResult result = callTool(tools.get("describe-recaf-api"), Map.of("query", ""));

		assertFalse(Boolean.TRUE.equals(result.isError()));
		String output = text(result);
		assertTrue(output.toLowerCase(Locale.ROOT).contains("truncated"),
				"Expected truncation marker in full API reference response");
		assertTrue(output.length() <= CodeModeOutputTruncator.MAX_OUTPUT_CHARS,
				"Expected bounded output length");
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

	private static long activeScriptThreadCount() {
		return Thread.getAllStackTraces()
				.keySet()
				.stream()
				.filter(Thread::isAlive)
				.filter(thread -> thread.getName().startsWith(SCRIPT_THREAD_PREFIX))
				.count();
	}

	private static void awaitScriptThreadCount(long expectedCount, long timeoutMs) throws InterruptedException {
		long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
		while (System.nanoTime() < deadlineNanos) {
			if (activeScriptThreadCount() <= expectedCount) {
				return;
			}
			Thread.sleep(25);
		}
	}
}
