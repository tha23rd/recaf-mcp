package dev.recafmcp.providers;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
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
import java.util.concurrent.TimeoutException;

/**
 * Groovy script tools for code-mode workflows.
 */
public class GroovyScriptingProvider extends AbstractToolProvider {
	private static final Logger logger = Logging.get(GroovyScriptingProvider.class);
	private static final int DEFAULT_TIMEOUT_MS = 5_000;
	private static final int MAX_TIMEOUT_MS = 30_000;
	private static final int MAX_OUTPUT_BYTES = 64 * 1024;

	private final DecompilerManager decompilerManager;
	private final SearchService searchService;
	private final CallGraphService callGraphService;
	private final InheritanceGraphService inheritanceGraphService;
	private final String apiReferenceText;

	public GroovyScriptingProvider(McpSyncServer server, WorkspaceManager workspaceManager) {
		this(server, workspaceManager, null, null, null, null);
	}

	public GroovyScriptingProvider(McpSyncServer server,
	                               WorkspaceManager workspaceManager,
	                               DecompilerManager decompilerManager,
	                               SearchService searchService,
	                               CallGraphService callGraphService,
	                               InheritanceGraphService inheritanceGraphService) {
		super(server, workspaceManager);
		this.decompilerManager = decompilerManager;
		this.searchService = searchService;
		this.callGraphService = callGraphService;
		this.inheritanceGraphService = inheritanceGraphService;
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
				return createTextResult(apiReferenceText);
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

			return createTextResult(String.join("\n\n---\n\n", matched));
		});
	}

	private void registerExecuteRecafScript() {
		Tool tool = Tool.builder()
				.name("execute-recaf-script")
				.description("Execute a Groovy script against the live Recaf workspace. " +
						"Available bindings include workspace, workspaceManager, decompilerManager, " +
						"searchService, callGraphService, and inheritanceGraphService. " +
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
		BoundedByteArrayOutputStream capturedOut = new BoundedByteArrayOutputStream(MAX_OUTPUT_BYTES);
		try (PrintWriter writer = new PrintWriter(capturedOut, true, StandardCharsets.UTF_8)) {
			Binding binding = new Binding();
			binding.setProperty("out", writer);
			binding.setProperty("workspaceManager", workspaceManager);
			binding.setProperty("decompilerManager", decompilerManager);
			binding.setProperty("searchService", searchService);
			binding.setProperty("callGraphService", callGraphService);
			binding.setProperty("inheritanceGraphService", inheritanceGraphService);
			binding.setProperty("workspace", resolveCurrentWorkspace());

			final Thread[] scriptThread = new Thread[1];
			ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
				Thread thread = new Thread(r, "recaf-mcp-groovy-script");
				thread.setDaemon(true);
				scriptThread[0] = thread;
				return thread;
			});

			Object returnValue;
			try {
				Future<Object> evaluation = executor.submit(() -> new GroovyShell(binding).evaluate(code));
				returnValue = evaluation.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				Thread thread = scriptThread[0];
				if (thread != null) {
					thread.interrupt();
				}
				return createErrorResult("Script timed out after " + timeoutMs + "ms");
			} catch (ExecutionException e) {
				Throwable cause = e.getCause() != null ? e.getCause() : e;
				logger.debug("Groovy script error", cause);
				return createErrorResult("Script error: " + cause.getMessage());
			} finally {
				executor.shutdownNow();
			}

			String printed = capturedOut.toString(StandardCharsets.UTF_8);
			StringBuilder result = new StringBuilder();
			if (!printed.isBlank()) {
				result.append(printed.strip());
			}
			if (capturedOut.isTruncated()) {
				if (!result.isEmpty()) {
					result.append('\n');
				}
				result.append("[stdout truncated at ").append(MAX_OUTPUT_BYTES).append(" bytes]");
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
			return createTextResult(output.isEmpty() ? "(script returned null/void)" : output);
		} catch (Exception e) {
			logger.debug("Groovy script error", e);
			String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
			return createErrorResult("Script error: " + message);
		}
	}

	private Workspace resolveCurrentWorkspace() {
		if (workspaceManager == null || !workspaceManager.hasCurrentWorkspace()) {
			return null;
		}
		return workspaceManager.getCurrent();
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
