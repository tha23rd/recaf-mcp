package dev.recafmcp.providers;

import dev.recafmcp.util.ClassResolver;
import dev.recafmcp.util.ErrorHelper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import me.darknet.assembler.error.Error;
import me.darknet.assembler.error.Result;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.assembler.JvmAssemblerPipeline;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;

import java.util.ArrayList;
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
	private static final Logger logger = Logging.get(AssemblerToolProvider.class);

	private final AssemblerPipelineManager pipelineManager;

	public AssemblerToolProvider(McpSyncServer server,
	                             WorkspaceManager workspaceManager,
	                             AssemblerPipelineManager pipelineManager) {
		super(server, workspaceManager);
		this.pipelineManager = pipelineManager;
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
			Workspace workspace = requireWorkspace();
			String className = getString(args, "className");
			String methodName = getString(args, "methodName");
			String methodDescriptor = getString(args, "methodDescriptor");

			// Resolve the class
			ClassPathNode classPath = ClassResolver.resolveClass(workspace, className);
			if (classPath == null) {
				return createErrorResult(ErrorHelper.classNotFound(className, workspace));
			}

			ClassInfo classInfo = classPath.getValue();
			if (!classInfo.isJvmClass()) {
				return createErrorResult("Class '" + className + "' is not a JVM class and cannot be disassembled.");
			}

			// Find the method
			MethodMember method = classInfo.getDeclaredMethod(methodName, methodDescriptor);
			if (method == null) {
				return createErrorResult("Method '" + methodName + methodDescriptor +
						"' not found in class '" + classInfo.getName() + "'." +
						formatAvailableMethods(classInfo));
			}

			// Build a ClassMemberPathNode for the method
			ClassMemberPathNode memberPath = classPath.child(method);

			// Create a pipeline and disassemble the method
			JvmAssemblerPipeline pipeline = pipelineManager.newJvmAssemblerPipeline(workspace);
			Result<String> disasmResult = pipeline.disassemble(memberPath);

			if (disasmResult.hasErr()) {
				return createErrorResult("Disassembly failed: " + formatErrors(disasmResult.errors()));
			}

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "success");
			result.put("className", classInfo.getName());
			result.put("methodName", methodName);
			result.put("methodDescriptor", methodDescriptor);
			result.put("assembly", disasmResult.get());
			if (disasmResult.hasWarn()) {
				result.put("warnings", formatErrorMessages(disasmResult.getWarns()));
			}
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
			Workspace workspace = requireWorkspace();
			String className = getString(args, "className");
			String methodName = getString(args, "methodName");
			String jasm = getString(args, "jasm");

			// Resolve the class
			ClassPathNode classPath = ClassResolver.resolveClass(workspace, className);
			if (classPath == null) {
				return createErrorResult(ErrorHelper.classNotFound(className, workspace));
			}

			ClassInfo classInfo = classPath.getValue();
			if (!classInfo.isJvmClass()) {
				return createErrorResult("Class '" + className + "' is not a JVM class and cannot be assembled.");
			}

			// Find the method (try exact match first by iterating methods with the given name)
			ClassMember targetMember = null;
			for (MethodMember m : classInfo.getMethods()) {
				if (m.getName().equals(methodName)) {
					targetMember = m;
					break;
				}
			}
			if (targetMember == null) {
				return createErrorResult("Method '" + methodName +
						"' not found in class '" + classInfo.getName() + "'." +
						formatAvailableMethods(classInfo));
			}

			// Build the path node for the method context
			ClassMemberPathNode memberPath = classPath.child(targetMember);

			// Create pipeline and run tokenize -> parse -> assemble pipeline
			JvmAssemblerPipeline pipeline = pipelineManager.newJvmAssemblerPipeline(workspace);

			// Tokenize
			Result<List<me.darknet.assembler.parser.Token>> tokenResult =
					pipeline.tokenize(jasm, classInfo.getName() + "#" + methodName);
			if (tokenResult.hasErr()) {
				return createErrorResult("Tokenization failed: " + formatErrors(tokenResult.errors()));
			}

			// Full parse (rough parse + concrete parse)
			Result<List<me.darknet.assembler.ast.ASTElement>> parseResult =
					pipeline.fullParse(tokenResult.get());
			if (parseResult.hasErr()) {
				return createErrorResult("Parsing failed: " + formatErrors(parseResult.errors()));
			}

			// Assemble and wrap into JvmClassInfo
			Result<? extends ClassInfo> assembleResult =
					pipeline.assembleAndWrap(parseResult.get(), memberPath);
			if (assembleResult.hasErr()) {
				return createErrorResult("Assembly failed: " + formatErrors(assembleResult.errors()));
			}

			// Apply the assembled class to the workspace
			ClassInfo assembledClass = assembleResult.get();
			if (!assembledClass.isJvmClass()) {
				return createErrorResult("Assembly produced a non-JVM class unexpectedly.");
			}

			JvmClassInfo assembledJvmClass = assembledClass.asJvmClass();
			JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
			bundle.put(assembledJvmClass);
			logger.info("Applied assembled method '{}' in class '{}'", methodName, classInfo.getName());

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "success");
			result.put("className", classInfo.getName());
			result.put("methodName", methodName);
			result.put("message", "Method bytecode assembled and applied to workspace.");
			if (assembleResult.hasWarn()) {
				result.put("warnings", formatErrorMessages(assembleResult.getWarns()));
			}
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
			Workspace workspace = requireWorkspace();
			String className = getString(args, "className");

			// Resolve the class
			ClassPathNode classPath = ClassResolver.resolveClass(workspace, className);
			if (classPath == null) {
				return createErrorResult(ErrorHelper.classNotFound(className, workspace));
			}

			ClassInfo classInfo = classPath.getValue();
			if (!classInfo.isJvmClass()) {
				return createErrorResult("Class '" + className + "' is not a JVM class and cannot be disassembled.");
			}

			// Create a pipeline and disassemble the full class
			JvmAssemblerPipeline pipeline = pipelineManager.newJvmAssemblerPipeline(workspace);
			Result<String> disasmResult = pipeline.disassemble(classPath);

			if (disasmResult.hasErr()) {
				return createErrorResult("Disassembly failed: " + formatErrors(disasmResult.errors()));
			}

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "success");
			result.put("className", classInfo.getName());
			result.put("assembly", disasmResult.get());
			if (disasmResult.hasWarn()) {
				result.put("warnings", formatErrorMessages(disasmResult.getWarns()));
			}
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
			Workspace workspace = requireWorkspace();
			String className = getString(args, "className");
			String jasm = getString(args, "jasm");

			// Resolve the class to use as the context path for assembly
			ClassPathNode classPath = ClassResolver.resolveClass(workspace, className);
			if (classPath == null) {
				return createErrorResult(ErrorHelper.classNotFound(className, workspace));
			}

			ClassInfo classInfo = classPath.getValue();
			if (!classInfo.isJvmClass()) {
				return createErrorResult("Class '" + className + "' is not a JVM class and cannot be assembled.");
			}

			// Create pipeline and run tokenize -> parse -> assembleAndWrap pipeline
			JvmAssemblerPipeline pipeline = pipelineManager.newJvmAssemblerPipeline(workspace);

			// Tokenize
			Result<List<me.darknet.assembler.parser.Token>> tokenResult =
					pipeline.tokenize(jasm, classInfo.getName());
			if (tokenResult.hasErr()) {
				return createErrorResult("Tokenization failed: " + formatErrors(tokenResult.errors()));
			}

			// Full parse (rough parse + concrete parse)
			Result<List<me.darknet.assembler.ast.ASTElement>> parseResult =
					pipeline.fullParse(tokenResult.get());
			if (parseResult.hasErr()) {
				return createErrorResult("Parsing failed: " + formatErrors(parseResult.errors()));
			}

			// Assemble and wrap into JvmClassInfo
			Result<? extends ClassInfo> assembleResult =
					pipeline.assembleAndWrap(parseResult.get(), classPath);
			if (assembleResult.hasErr()) {
				return createErrorResult("Assembly failed: " + formatErrors(assembleResult.errors()));
			}

			// Apply assembled class to workspace
			ClassInfo assembledClass = assembleResult.get();
			if (!assembledClass.isJvmClass()) {
				return createErrorResult("Assembly produced a non-JVM class unexpectedly.");
			}

			JvmClassInfo assembledJvmClass = assembledClass.asJvmClass();
			JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
			bundle.put(assembledJvmClass);
			logger.info("Applied assembled class '{}'", assembledJvmClass.getName());

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			result.put("status", "success");
			result.put("className", assembledJvmClass.getName());
			result.put("message", "Class assembled and applied to workspace.");
			if (assembleResult.hasWarn()) {
				result.put("warnings", formatErrorMessages(assembleResult.getWarns()));
			}
			return createJsonResult(result);
		});
	}

	// ---- Helpers ----

	/**
	 * Format a list of JASM errors into a single human-readable string.
	 */
	private static String formatErrors(List<Error> errors) {
		if (errors == null || errors.isEmpty()) return "unknown error";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < errors.size(); i++) {
			Error err = errors.get(i);
			if (i > 0) sb.append("; ");
			if (err.getLocation() != null && err.getLocation().line() >= 0) {
				sb.append("line ").append(err.getLocation().line()).append(": ");
			}
			sb.append(err.getMessage());
		}
		return sb.toString();
	}

	/**
	 * Format a list of JASM errors/warnings into a list of message strings for JSON output.
	 */
	private static List<String> formatErrorMessages(List<? extends Error> errors) {
		List<String> messages = new ArrayList<>();
		if (errors == null) return messages;
		for (Error err : errors) {
			StringBuilder sb = new StringBuilder();
			if (err.getLocation() != null && err.getLocation().line() >= 0) {
				sb.append("line ").append(err.getLocation().line()).append(": ");
			}
			sb.append(err.getMessage());
			messages.add(sb.toString());
		}
		return messages;
	}

	/**
	 * Format a list of available methods in a class for error messages.
	 */
	private static String formatAvailableMethods(ClassInfo classInfo) {
		List<MethodMember> methods = classInfo.getMethods();
		if (methods == null || methods.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder(" Available methods:\n");
		for (MethodMember m : methods) {
			sb.append("  - ").append(m.getName()).append(m.getDescriptor()).append('\n');
		}
		return sb.toString();
	}
}
