package dev.recafmcp.providers;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import groovy.transform.ThreadInterrupt;
import dev.recafmcp.server.CodeModeOutputTruncator;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.callgraph.CallGraphService;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;

/**
 * Groovy script tools for code-mode workflows.
 */
public class GroovyScriptingProvider extends AbstractToolProvider {
	private static final Logger logger = Logging.get(GroovyScriptingProvider.class);
	private static final int DEFAULT_TIMEOUT_MS = 5_000;
	private static final int MAX_TIMEOUT_MS = 30_000;
	private static final int MAX_OUTPUT_BYTES = 64 * 1024;
	private static final int SCRIPT_INTERRUPT_GRACE_MS = 250;
	private static final int EXECUTOR_SHUTDOWN_WAIT_MS = 1_000;
	private static final AtomicInteger SCRIPT_THREAD_COUNTER = new AtomicInteger();
	private static final String SCRIPT_EXECUTION_ENABLED_ENV = "RECAF_MCP_SCRIPT_EXECUTION_ENABLED";
	private static final String SCRIPT_EXECUTION_ENABLED_PROPERTY = "recaf.mcp.script.execution.enabled";

	private final DecompilerManager decompilerManager;
	private final SearchService searchService;
	private final CallGraphService callGraphService;
	private final InheritanceGraphService inheritanceGraphService;
	private final String apiReferenceText;
	private final BooleanSupplier scriptExecutionEnabledSupplier;

	public GroovyScriptingProvider(McpSyncServer server, WorkspaceManager workspaceManager) {
		this(server, workspaceManager, null, null, null, null, GroovyScriptingProvider::resolveScriptExecutionEnabled);
	}

	public GroovyScriptingProvider(McpSyncServer server,
	                               WorkspaceManager workspaceManager,
	                               DecompilerManager decompilerManager,
	                               SearchService searchService,
	                               CallGraphService callGraphService,
	                               InheritanceGraphService inheritanceGraphService) {
		this(server,
				workspaceManager,
				decompilerManager,
				searchService,
				callGraphService,
				inheritanceGraphService,
				GroovyScriptingProvider::resolveScriptExecutionEnabled);
	}

	GroovyScriptingProvider(McpSyncServer server,
	                        WorkspaceManager workspaceManager,
	                        DecompilerManager decompilerManager,
	                        SearchService searchService,
	                        CallGraphService callGraphService,
	                        InheritanceGraphService inheritanceGraphService,
	                        BooleanSupplier scriptExecutionEnabledSupplier) {
		super(server, workspaceManager);
		this.decompilerManager = decompilerManager;
		this.searchService = searchService;
		this.callGraphService = callGraphService;
		this.inheritanceGraphService = inheritanceGraphService;
		this.scriptExecutionEnabledSupplier = scriptExecutionEnabledSupplier != null ?
				scriptExecutionEnabledSupplier :
				() -> false;
		this.apiReferenceText = loadApiReference();
	}

	@Override
	public void registerTools() {
		registerDescribeRecafApi();
		registerExecuteRecafScript();
	}

	private void registerDescribeRecafApi() {
		Tool tool = Tool.builder()
				.name("describe-recaf-api")
				.description("Return the Recaf API reference for writing execute-recaf-script code. " +
						"Pass a keyword to filter relevant sections. Empty query returns the full reference.")
				.inputSchema(createSchema(
						Map.of("query", stringParam(
								"Keyword to filter API sections (for example: decompile, search, inheritance). " +
										"Empty string returns all sections."
						)),
						List.of()
				))
				.build();

		registerTool(tool, (exchange, args) -> {
			String query = getOptionalString(args, "query", "").trim().toLowerCase(Locale.ROOT);
			if (query.isEmpty()) {
				return createTextResult(CodeModeOutputTruncator.truncate(apiReferenceText));
			}

			String[] sections = apiReferenceText.split("(?m)^---\\s*$|(?=^## )", -1);
			List<String> matched = new ArrayList<>();
			for (String section : sections) {
				if (section.toLowerCase(Locale.ROOT).contains(query)) {
					matched.add(section.strip());
				}
			}

			if (matched.isEmpty()) {
				return createTextResult("No sections matched \"" + query + "\". " +
						"Try an empty query to see the full reference, or use terms like " +
						"decompile, search, class, workspace, inheritance, callgraph.");
			}

			return createTextResult(CodeModeOutputTruncator.truncate(String.join("\n\n---\n\n", matched)));
		});
	}

	private void registerExecuteRecafScript() {
			Tool tool = Tool.builder()
					.name("execute-recaf-script")
					.description("Execute a Groovy script against the live Recaf workspace. " +
							"Available bindings include workspace, decompilerManager, searchService, " +
							"callGraphService, and inheritanceGraphService. " +
							"Execution is disabled by default and must be explicitly enabled via " +
							SCRIPT_EXECUTION_ENABLED_ENV + "=true or -D" + SCRIPT_EXECUTION_ENABLED_PROPERTY + "=true. " +
							"Execution is time bounded and stdout is capped.")
				.inputSchema(createSchema(
						Map.of(
								"code", stringParam("Groovy script to execute."),
								"timeoutMs", intParam("Optional timeout in milliseconds (default: 5000, max: 30000).")
						),
						List.of("code")
				))
				.build();

		registerTool(tool, (exchange, args) -> {
			String code = getString(args, "code");
			int timeoutMs = clamp(getInt(args, "timeoutMs", DEFAULT_TIMEOUT_MS), 1, MAX_TIMEOUT_MS);
			return executeGroovyScript(code, timeoutMs);
		});
	}

	private CallToolResult executeGroovyScript(String code, int timeoutMs) {
		if (!isScriptExecutionEnabled()) {
			return createErrorResult(disabledByPolicyMessage());
		}

		BoundedByteArrayOutputStream capturedOut = new BoundedByteArrayOutputStream(MAX_OUTPUT_BYTES);
		try (PrintWriter writer = new PrintWriter(capturedOut, true, StandardCharsets.UTF_8)) {
			Binding binding = createScriptBinding(writer);
			AtomicReference<Thread> scriptThread = new AtomicReference<>();
			ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
				Thread thread = new Thread(r, "recaf-mcp-groovy-script-" + SCRIPT_THREAD_COUNTER.incrementAndGet());
				thread.setDaemon(true);
				scriptThread.set(thread);
				return thread;
			});

			Object returnValue;
			Future<Object> evaluation = null;
			try {
				evaluation = executor.submit(() -> createInterruptibleGroovyShell(binding).evaluate(code));
				returnValue = evaluation.get(timeoutMs, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				String timeoutMessage = handleTimeout(timeoutMs, scriptThread.get(), evaluation);
				return createErrorResult(timeoutMessage);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return createErrorResult("Script execution interrupted while waiting for completion");
			} catch (ExecutionException e) {
				Throwable cause = e.getCause() != null ? e.getCause() : e;
				logger.debug("Groovy script error", cause);
				return createErrorResult("Script error: " + cause.getMessage());
			} finally {
				shutdownExecutor(executor);
			}

			String printed = capturedOut.toString(StandardCharsets.UTF_8);
			StringBuilder result = new StringBuilder();
			if (capturedOut.isTruncated()) {
				result.append("[stdout truncated at ").append(MAX_OUTPUT_BYTES).append(" bytes]");
			}
			if (!printed.isBlank()) {
				if (!result.isEmpty()) {
					result.append('\n');
				}
				result.append(printed.strip());
			}
			if (returnValue != null) {
				String returnText = returnValue.toString();
				if (!returnText.isBlank()) {
					if (!result.isEmpty()) {
						result.append('\n');
					}
					result.append(returnText);
				}
			}

			String output = result.toString().strip();
			String normalized = output.isEmpty() ? "(script returned null/void)" : output;
			return createTextResult(CodeModeOutputTruncator.truncate(normalized));
		} catch (Exception e) {
			logger.debug("Groovy script error", e);
			String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
			return createErrorResult("Script error: " + message);
		}
	}

	private Binding createScriptBinding(PrintWriter writer) {
		Binding binding = new Binding();
		binding.setProperty("out", writer);
		binding.setProperty("workspace", resolveCurrentWorkspace());
		if (decompilerManager != null) {
			binding.setProperty("decompilerManager", decompilerManager);
		}
		if (searchService != null) {
			binding.setProperty("searchService", searchService);
		}
		if (callGraphService != null) {
			binding.setProperty("callGraphService", callGraphService);
		}
		if (inheritanceGraphService != null) {
			binding.setProperty("inheritanceGraphService", inheritanceGraphService);
		}
		return binding;
	}

	private boolean isScriptExecutionEnabled() {
		try {
			return scriptExecutionEnabledSupplier.getAsBoolean();
		} catch (RuntimeException e) {
			logger.warn("Failed to resolve script execution policy, defaulting to disabled", e);
			return false;
		}
	}

	private static String disabledByPolicyMessage() {
		return "Script execution is disabled by policy. " +
				"To enable it explicitly, set " + SCRIPT_EXECUTION_ENABLED_ENV + "=true " +
				"or JVM property -D" + SCRIPT_EXECUTION_ENABLED_PROPERTY + "=true.";
	}

	private String handleTimeout(int timeoutMs, Thread scriptThread, Future<?> evaluation) {
		if (evaluation != null) {
			evaluation.cancel(true);
		}
		if (scriptThread == null) {
			return "Script timed out after " + timeoutMs + "ms and interruption was requested.";
		}

		scriptThread.interrupt();
		if (waitForThreadExit(scriptThread, SCRIPT_INTERRUPT_GRACE_MS)) {
			return "Script timed out after " + timeoutMs + "ms and was interrupted.";
		}

		return "Script timed out after " + timeoutMs + "ms and could not be terminated cleanly. " +
				"Execution remains blocked by policy until explicitly enabled.";
	}

	private static GroovyShell createInterruptibleGroovyShell(Binding binding) {
		CompilerConfiguration config = new CompilerConfiguration();
		config.addCompilationCustomizers(new ASTTransformationCustomizer(ThreadInterrupt.class));
		return new GroovyShell(GroovyScriptingProvider.class.getClassLoader(), binding, config);
	}

	private static boolean waitForThreadExit(Thread thread, long waitMs) {
		try {
			thread.join(waitMs);
			return !thread.isAlive();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return !thread.isAlive();
		}
	}

	private static void shutdownExecutor(ExecutorService executor) {
		executor.shutdownNow();
		try {
			if (!executor.awaitTermination(EXECUTOR_SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)) {
				logger.warn("Timed out waiting for Groovy script executor shutdown");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private Workspace resolveCurrentWorkspace() {
		if (workspaceManager == null || !workspaceManager.hasCurrentWorkspace()) {
			return null;
		}
		return workspaceManager.getCurrent();
	}

	static boolean resolveScriptExecutionEnabled() {
		String env = System.getenv(SCRIPT_EXECUTION_ENABLED_ENV);
		if (env != null && !env.isBlank()) {
			return Boolean.parseBoolean(env);
		}

		String prop = System.getProperty(SCRIPT_EXECUTION_ENABLED_PROPERTY);
		if (prop != null && !prop.isBlank()) {
			return Boolean.parseBoolean(prop);
		}

		return false;
	}

	private static String loadApiReference() {
		try (InputStream in = GroovyScriptingProvider.class.getResourceAsStream("/recaf-api-reference.md")) {
			if (in == null) {
				return "# Recaf API Reference\n\n(reference file not found)";
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			return "# Recaf API Reference\n\n(failed to load: " + e.getMessage() + ")";
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.min(max, Math.max(min, value));
	}

	private static final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
		private final int maxBytes;
		private boolean truncated;

		private BoundedByteArrayOutputStream(int maxBytes) {
			this.maxBytes = maxBytes;
		}

		@Override
		public synchronized void write(int b) {
			if (count < maxBytes) {
				super.write(b);
			} else {
				truncated = true;
			}
		}

		@Override
		public synchronized void write(byte[] b, int off, int len) {
			if (len <= 0) {
				return;
			}
			int remaining = maxBytes - count;
			if (remaining <= 0) {
				truncated = true;
				return;
			}
			int toWrite = Math.min(len, remaining);
			super.write(b, off, toWrite);
			if (toWrite < len) {
				truncated = true;
			}
		}

		private boolean isTruncated() {
			return truncated;
		}
	}
}
