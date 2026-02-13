package dev.recafmcp.providers;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import software.coley.recaf.services.workspace.WorkspaceManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool provider for assembler/disassembler operations.
 * <p>
 * Provides tools for disassembling and assembling methods and classes using
 * Recaf's JASM (Java Assembly) pipeline.
 */
public class AssemblerToolProvider extends AbstractToolProvider {

	public AssemblerToolProvider(McpSyncServer server, WorkspaceManager workspaceManager) {
		super(server, workspaceManager);
	}

	@Override
	public void registerTools() {
		registerDisassembleMethod();
		registerAssembleMethod();
		registerDisassembleClass();
		registerAssembleClass();
	}

	// ---- disassemble-method ----

	private void registerDisassembleMethod() {
		Tool tool = Tool.builder()
				.name("disassemble-method")
				.description("Disassemble a specific method to JASM bytecode representation")
				.inputSchema(createSchema(
						Map.of(
								"className", stringParam("Fully qualified class name (e.g. com/example/MyClass)"),
								"methodName", stringParam("Name of the method to disassemble"),
								"methodDescriptor", stringParam("Method descriptor (e.g. (Ljava/lang/String;)V)")
						),
						List.of("className", "methodName", "methodDescriptor")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			requireWorkspace();
			String className = getString(args, "className");
			String methodName = getString(args, "methodName");
			String methodDescriptor = getString(args, "methodDescriptor");

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "stub");
			result.put("className", className);
			result.put("methodName", methodName);
			result.put("methodDescriptor", methodDescriptor);
			result.put("message", "Method disassembly produces JASM bytecode representation. " +
					"This tool will use Recaf's JvmAssemblerPipeline to disassemble a specific method.");
			return createJsonResult(result);
		});
	}

	// ---- assemble-method ----

	private void registerAssembleMethod() {
		Tool tool = Tool.builder()
				.name("assemble-method")
				.description("Assemble JASM bytecode text and replace a method's bytecode in the workspace")
				.inputSchema(createSchema(
						Map.of(
								"className", stringParam("Fully qualified class name (e.g. com/example/MyClass)"),
								"methodName", stringParam("Name of the method to replace"),
								"jasm", stringParam("JASM bytecode text to assemble")
						),
						List.of("className", "methodName", "jasm")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			requireWorkspace();
			String className = getString(args, "className");
			String methodName = getString(args, "methodName");
			String jasm = getString(args, "jasm");

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "stub");
			result.put("className", className);
			result.put("methodName", methodName);
			result.put("jasmLength", jasm.length());
			result.put("message", "Method assembly parses JASM text and replaces the method bytecode in the workspace.");
			return createJsonResult(result);
		});
	}

	// ---- disassemble-class ----

	private void registerDisassembleClass() {
		Tool tool = Tool.builder()
				.name("disassemble-class")
				.description("Disassemble an entire class to JASM format")
				.inputSchema(createSchema(
						Map.of("className", stringParam("Fully qualified class name (e.g. com/example/MyClass)")),
						List.of("className")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			requireWorkspace();
			String className = getString(args, "className");

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "stub");
			result.put("className", className);
			result.put("message", "Full class disassembly to JASM format.");
			return createJsonResult(result);
		});
	}

	// ---- assemble-class ----

	private void registerAssembleClass() {
		Tool tool = Tool.builder()
				.name("assemble-class")
				.description("Assemble a full class from JASM text, replacing the class in the workspace")
				.inputSchema(createSchema(
						Map.of(
								"className", stringParam("Fully qualified class name (e.g. com/example/MyClass)"),
								"jasm", stringParam("JASM class text to assemble")
						),
						List.of("className", "jasm")
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			requireWorkspace();
			String className = getString(args, "className");
			String jasm = getString(args, "jasm");

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "stub");
			result.put("className", className);
			result.put("jasmLength", jasm.length());
			result.put("message", "Full class assembly from JASM text, replacing the class in the workspace.");
			return createJsonResult(result);
		});
	}
}
