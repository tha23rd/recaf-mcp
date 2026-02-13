package dev.recafmcp.providers;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.workspace.WorkspaceManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool provider for compilation operations.
 * <p>
 * Provides tools for compiling Java source code, checking compilation
 * diagnostics, and generating phantom classes for missing dependencies.
 */
public class CompilerToolProvider extends AbstractToolProvider {
	private static final Logger logger = Logging.get(CompilerToolProvider.class);

	public CompilerToolProvider(McpSyncServer server, WorkspaceManager workspaceManager) {
		super(server, workspaceManager);
	}

	@Override
	public void registerTools() {
		registerCompileJava();
		registerCompileCheck();
		registerPhantomGenerate();
	}

	// ---- compile-java ----

	private void registerCompileJava() {
		Tool tool = Tool.builder()
				.name("compile-java")
				.description("Compile Java source code and replace the class in the current workspace")
				.inputSchema(createSchema(
						Map.of(
								"className", stringParam("Fully qualified class name (dot notation, e.g. 'com.example.MyClass')"),
								"source", stringParam("The Java source code to compile")
						),
						List.of("className", "source")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			String className = getString(args, "className");
			String source = getString(args, "source");
			requireWorkspace();

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "stub");
			result.put("className", className);
			result.put("message", "Java compilation requires integration with Recaf's JavacCompiler. " +
					"This tool will compile the provided source and replace the class in the workspace.");
			return createJsonResult(result);
		});
	}

	// ---- compile-check ----

	private void registerCompileCheck() {
		Tool tool = Tool.builder()
				.name("compile-check")
				.description("Compile Java source code without applying changes, returning only compiler diagnostics for validation")
				.inputSchema(createSchema(
						Map.of(
								"className", stringParam("Fully qualified class name (dot notation, e.g. 'com.example.MyClass')"),
								"source", stringParam("The Java source code to check")
						),
						List.of("className", "source")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			String className = getString(args, "className");
			String source = getString(args, "source");
			requireWorkspace();

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "stub");
			result.put("className", className);
			result.put("message", "This tool will compile without applying, returning only compiler diagnostics for validation.");
			return createJsonResult(result);
		});
	}

	// ---- phantom-generate ----

	private void registerPhantomGenerate() {
		Tool tool = Tool.builder()
				.name("phantom-generate")
				.description("Generate phantom (stub) classes for missing dependencies to resolve compilation classpath gaps")
				.inputSchema(createSchema(Map.of(), List.of()))
				.build();
		registerTool(tool, (exchange, args) -> {
			requireWorkspace();

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "stub");
			result.put("message", "Phantom class generation creates stub classes for missing dependencies. " +
					"This will use Recaf's PhantomGenerator to resolve compilation classpath gaps.");
			return createJsonResult(result);
		});
	}
}
