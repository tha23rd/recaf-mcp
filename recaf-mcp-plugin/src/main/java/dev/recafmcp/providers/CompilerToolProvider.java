package dev.recafmcp.providers;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.compile.CompileMap;
import software.coley.recaf.services.compile.CompilerDiagnostic;
import software.coley.recaf.services.compile.CompilerResult;
import software.coley.recaf.services.compile.JavacArgumentsBuilder;
import software.coley.recaf.services.compile.JavacCompiler;
import software.coley.recaf.services.phantom.PhantomGenerationException;
import software.coley.recaf.services.phantom.PhantomGenerator;
import software.coley.recaf.services.phantom.GeneratedPhantomWorkspaceResource;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;

import java.util.ArrayList;
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

	private final JavacCompiler javacCompiler;
	private final PhantomGenerator phantomGenerator;

	public CompilerToolProvider(McpSyncServer server,
	                            WorkspaceManager workspaceManager,
	                            JavacCompiler javacCompiler,
	                            PhantomGenerator phantomGenerator) {
		super(server, workspaceManager);
		this.javacCompiler = javacCompiler;
		this.phantomGenerator = phantomGenerator;
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
			Workspace workspace = requireWorkspace();

			if (!JavacCompiler.isAvailable()) {
				return createErrorResult("Java compiler (javac) is not available in this runtime. " +
						"Ensure Recaf is running on a JDK, not a JRE.");
			}

			// Convert dot notation to internal format (slashes) for the compiler
			String internalName = className.replace('.', '/');

			CompilerResult compilerResult = compileSource(internalName, source, workspace);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			List<Map<String, Object>> diagnosticsList = formatDiagnostics(compilerResult.getDiagnostics());

			if (!compilerResult.wasSuccess()) {
				result.put("status", "error");
				result.put("className", className);
				result.put("diagnostics", diagnosticsList);
				if (compilerResult.getException() != null) {
					result.put("exception", compilerResult.getException().getMessage());
				}
				return createJsonResult(result);
			}

			// Apply compiled classes to workspace
			CompileMap compilations = compilerResult.getCompilations();
			JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
			List<String> appliedClasses = new ArrayList<>();

			for (Map.Entry<String, byte[]> entry : compilations.entrySet()) {
				String compiledName = entry.getKey();
				byte[] bytecode = entry.getValue();
				JvmClassInfo classInfo = new JvmClassInfoBuilder(bytecode).build();
				bundle.put(classInfo);
				appliedClasses.add(compiledName);
				logger.info("Applied compiled class: {}", compiledName);
			}

			result.put("status", "success");
			result.put("className", className);
			result.put("appliedClasses", appliedClasses);
			result.put("diagnostics", diagnosticsList);
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
			Workspace workspace = requireWorkspace();

			if (!JavacCompiler.isAvailable()) {
				return createErrorResult("Java compiler (javac) is not available in this runtime. " +
						"Ensure Recaf is running on a JDK, not a JRE.");
			}

			String internalName = className.replace('.', '/');
			CompilerResult compilerResult = compileSource(internalName, source, workspace);

			LinkedHashMap<String, Object> result = new LinkedHashMap<>();
			List<Map<String, Object>> diagnosticsList = formatDiagnostics(compilerResult.getDiagnostics());

			result.put("status", compilerResult.wasSuccess() ? "ok" : "error");
			result.put("className", className);
			result.put("diagnostics", diagnosticsList);
			if (compilerResult.getException() != null) {
				result.put("exception", compilerResult.getException().getMessage());
			}
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
			Workspace workspace = requireWorkspace();

			try {
				GeneratedPhantomWorkspaceResource phantoms =
						phantomGenerator.createPhantomsForWorkspace(workspace);
				workspace.addSupportingResource(phantoms);

				int phantomCount = 0;
				JvmClassBundle phantomBundle = phantoms.getJvmClassBundle();
				if (phantomBundle != null) {
					phantomCount = phantomBundle.size();
				}

				LinkedHashMap<String, Object> result = new LinkedHashMap<>();
				result.put("status", "success");
				result.put("phantomClassesGenerated", phantomCount);
				result.put("message", "Generated " + phantomCount + " phantom classes and added to workspace.");
				return createJsonResult(result);
			} catch (PhantomGenerationException e) {
				logger.error("Phantom generation failed", e);
				return createErrorResult("Phantom generation failed: " + e.getMessage());
			}
		});
	}

	// ---- Helpers ----

	private CompilerResult compileSource(String internalClassName, String source, Workspace workspace) {
		var arguments = new JavacArgumentsBuilder()
				.withClassName(internalClassName)
				.withClassSource(source)
				.withDebugVariables(true)
				.withDebugLineNumbers(true)
				.withDebugSourceName(true)
				.build();
		return javacCompiler.compile(arguments, workspace, null);
	}

	private static List<Map<String, Object>> formatDiagnostics(List<CompilerDiagnostic> diagnostics) {
		List<Map<String, Object>> list = new ArrayList<>();
		if (diagnostics == null) return list;
		for (CompilerDiagnostic d : diagnostics) {
			LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
			entry.put("level", d.level().name());
			entry.put("line", d.line());
			entry.put("column", d.column());
			entry.put("message", d.message());
			list.add(entry);
		}
		return list;
	}
}
