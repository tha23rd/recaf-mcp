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
	void executeLargeReturnValueIsNotGloballyTruncated() {
		provider = createProvider(true);
		Map<String, SyncToolSpecification> tools = captureTools();
		CallToolResult result = callTool(tools.get("execute-recaf-script"),
				Map.of("code", "return 'x' * 70000"));

		assertFalse(Boolean.TRUE.equals(result.isError()));
		String output = text(result);
		assertFalse(output.toLowerCase(Locale.ROOT).contains("output truncated"),
				"Did not expect shared truncation marker for large return value");
		assertEquals(70000, output.length(), "Expected full return value output");
	}

	@Test
	void executeJdk25StdlibFeaturesUnderGroovy5() {
		// Regression test for recaf-mcp-pxl: under Groovy 4.0.24 the bundled
		// groovyjarjarasm could not read JDK 25 stdlib classes (class major 69),
		// so semantic analysis blew up the moment a script touched closures over
		// streams, lambdas, anonymous Predicate, FileOutputStream, TimeUnit, or
		// Math.min/max. Groovy 5.0.x ships a newer ASM that handles major 69, so
		// these scripts must now compile and run cleanly.
		provider = createProvider(true);
		Map<String, SyncToolSpecification> tools = captureTools();

		String script = String.join("\n",
				"import java.util.concurrent.TimeUnit",
				"import java.util.function.Predicate",
				"import java.util.stream.Collectors",
				"def numbers = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]",
				// closure + stream + lambda — historically broke under JDK 25
				"def evens = numbers.stream().filter({ n -> n % 2 == 0 }).collect(Collectors.toList())",
				// anonymous Predicate via java.util.function — historically broke under JDK 25
				"Predicate<Integer> bigEnough = new Predicate<Integer>() {",
				"    boolean test(Integer i) { return i >= 4 }",
				"}",
				"def filtered = evens.findAll { bigEnough.test(it) }",
				// Math.min/max + TimeUnit — historically broke under JDK 25
				"def smallest = Math.min(filtered[0], filtered[-1])",
				"def biggest = Math.max(filtered[0], filtered[-1])",
				"def micros = TimeUnit.SECONDS.toMicros(1L)",
				"return \"evens=${evens} filtered=${filtered} min=${smallest} max=${biggest} micros=${micros}\""
		);

		CallToolResult result = callTool(tools.get("execute-recaf-script"), Map.of("code", script));

		assertFalse(Boolean.TRUE.equals(result.isError()),
				"JDK 25 stdlib features should run on Groovy 5.x. Got: " + text(result));
		String body = text(result);
		assertTrue(body.contains("evens=[2, 4, 6, 8, 10]"), "stream filter result missing: " + body);
		assertTrue(body.contains("filtered=[4, 6, 8, 10]"), "Predicate filter result missing: " + body);
		assertTrue(body.contains("min=4"), "Math.min result missing: " + body);
		assertTrue(body.contains("max=10"), "Math.max result missing: " + body);
		assertTrue(body.contains("micros=1000000"), "TimeUnit conversion missing: " + body);
	}

	@Test
	void executeFileIoUnderJdk25() {
		// Companion regression for recaf-mcp-pxl: FileOutputStream / FileWriter
		// are common targets in RE workflows (dumping bytecode, writing reports).
		// Under Groovy 4.0.24 + JDK 25 these throw "Unsupported class file major
		// version 69" during semantic analysis. Groovy 5.x must compile them.
		provider = createProvider(true);
		Map<String, SyncToolSpecification> tools = captureTools();

		String script = String.join("\n",
				"import java.nio.file.Files",
				"def tmp = Files.createTempFile('recaf-mcp-pxl', '.txt')",
				"try {",
				"    new FileOutputStream(tmp.toFile()).withCloseable { fos ->",
				"        fos.write('hello jdk25'.getBytes('UTF-8'))",
				"    }",
				"    return new String(Files.readAllBytes(tmp), 'UTF-8')",
				"} finally {",
				"    Files.deleteIfExists(tmp)",
				"}"
		);

		CallToolResult result = callTool(tools.get("execute-recaf-script"), Map.of("code", script));

		assertFalse(Boolean.TRUE.equals(result.isError()),
				"FileOutputStream + closure should compile under Groovy 5.x. Got: " + text(result));
		assertEquals("hello jdk25", text(result).trim());
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
	void describeApiEmptyQueryReturnsFullReferenceWithoutGlobalTruncation() {
		Map<String, SyncToolSpecification> tools = captureTools();
		CallToolResult result = callTool(tools.get("describe-recaf-api"), Map.of("query", ""));

		assertFalse(Boolean.TRUE.equals(result.isError()));
		String output = text(result);
		assertFalse(output.toLowerCase(Locale.ROOT).contains("output truncated"),
				"Did not expect global truncation marker in API reference response");
		assertTrue(output.length() > 4096,
				"Expected full API reference output length over prior truncation threshold");
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
