package dev.recafmcp.ssvm;

import dev.recafmcp.providers.SsvmExecutionProvider;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration test for the full SSVM execution workflow against a realistic
 * obfuscation scenario: stack-trace-dependent XOR string decryption.
 * <p>
 * This test creates synthetic obfuscated classes with ASM ClassWriter and exercises
 * the full tool workflow (vm-run-clinit, vm-get-field, vm-invoke-method) with stack
 * trace overrides matching the RLPL sideload decryption pattern.
 * <p>
 * <b>SSVM 2.0.0 only supports JDK 11-22.</b> Our build JDK is 25, so this test is
 * disabled by default. Run it manually on a compatible JDK to validate the full workflow.
 */
@Disabled("Requires JDK 11-22 for SSVM bootstrap -- run manually on a compatible JDK")
class SsvmIntegrationTest {

	// ---- Known constants for the synthetic obfuscation scenario ----

	/**
	 * The "random" array values baked into DecryptionEngine's clinit.
	 */
	private static final int[] RANDOM_ARRAY = {42, 17, 99, 256, 8};

	/**
	 * Key argument passed to DecryptionEngine.decrypt(key, index).
	 */
	private static final int DECRYPT_KEY = 12345;

	/**
	 * Index argument passed to DecryptionEngine.decrypt(key, index).
	 */
	private static final int DECRYPT_INDEX = 0;

	/**
	 * Expected caller class name at stack frame index 3 when called from TargetClass.clinit.
	 * This is the XOR key source in the decryption: "test.TargetClass".hashCode() ^ key ^ randomArray[index].
	 */
	private static final String EXPECTED_CALLER = "test.TargetClass";

	// ---- Test infrastructure ----

	private WorkspaceManager workspaceManager;
	private SsvmManager ssvmManager;
	private McpSyncServer mockServer;
	private Map<String, BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult>> toolHandlers;

	@BeforeEach
	void setUp() {
		workspaceManager = mock(WorkspaceManager.class);

		// Build synthetic classes and mock workspace
		JvmClassInfo decryptionEngineClass = buildDecryptionEngineClass();
		JvmClassInfo targetClass = buildTargetClass();
		Workspace workspace = mockWorkspaceWithClasses(decryptionEngineClass, targetClass);
		when(workspaceManager.getCurrent()).thenReturn(workspace);

		// Create real SsvmManager (will bootstrap on first use)
		ssvmManager = new SsvmManager(workspaceManager);

		// Capture tool handlers registered by SsvmExecutionProvider
		mockServer = mock(McpSyncServer.class);
		toolHandlers = new HashMap<>();

		ArgumentCaptor<SyncToolSpecification> captor = ArgumentCaptor.forClass(SyncToolSpecification.class);
		SsvmExecutionProvider provider = new SsvmExecutionProvider(mockServer, workspaceManager, ssvmManager);
		provider.registerTools();

		verify(mockServer, atLeast(3)).addTool(captor.capture());
		for (SyncToolSpecification spec : captor.getAllValues()) {
			toolHandlers.put(spec.tool().name(), spec.callHandler());
		}
	}

	@AfterEach
	void tearDown() {
		if (ssvmManager != null) {
			ssvmManager.close();
		}
	}

	// ---- Full workflow test ----

	@Test
	void fullDecryptionWorkflow() throws Exception {
		McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);

		// Step 1: Initialize DecryptionEngine via vm-run-clinit
		CallToolResult clinitResult = callTool("vm-run-clinit", exchange, Map.of(
				"className", "test.DecryptionEngine"
		));
		assertFalse(isErrorResult(clinitResult), "clinit should succeed: " + getResultText(clinitResult));
		String clinitText = getResultText(clinitResult);
		assertTrue(clinitText.contains("test.DecryptionEngine"),
				"Result should mention the initialized class");

		// Step 2: Read the randomArray field
		CallToolResult arrayResult = callTool("vm-get-field", exchange, Map.of(
				"className", "test.DecryptionEngine",
				"fieldName", "randomArray"
		));
		assertFalse(isErrorResult(arrayResult), "get-field should succeed: " + getResultText(arrayResult));
		String arrayText = getResultText(arrayResult);
		// Verify the array contains our known values
		for (int val : RANDOM_ARRAY) {
			assertTrue(arrayText.contains(String.valueOf(val)),
					"Array result should contain value " + val + ": " + arrayText);
		}

		// Step 3: Initialize TargetClass WITH stack trace override
		// The override ensures Thread.getStackTrace()[3].getClassName() returns "test.TargetClass"
		List<Map<String, Object>> stackFrames = List.of(
				Map.of("className", "java.lang.Thread", "methodName", "getStackTrace"),
				Map.of("className", "test.DecryptionEngine", "methodName", "decrypt"),
				Map.of("className", "test.DecryptionEngine", "methodName", "decrypt"),
				Map.of("className", "test.TargetClass", "methodName", "<clinit>")
		);

		Map<String, Object> clinitArgs = new HashMap<>();
		clinitArgs.put("className", "test.TargetClass");
		clinitArgs.put("stackTraceOverride", stackFrames);
		clinitArgs.put("allowTransitiveInit", List.of("test.DecryptionEngine"));

		CallToolResult targetClinitResult = callTool("vm-run-clinit", exchange, clinitArgs);
		assertFalse(isErrorResult(targetClinitResult),
				"TargetClass clinit should succeed: " + getResultText(targetClinitResult));

		// Step 4: Read the decrypted value from TargetClass
		CallToolResult decryptedResult = callTool("vm-get-field", exchange, Map.of(
				"className", "test.TargetClass",
				"fieldName", "decryptedValue"
		));
		assertFalse(isErrorResult(decryptedResult),
				"get-field should succeed: " + getResultText(decryptedResult));

		String decryptedText = getResultText(decryptedResult);
		// Compute expected value: "test.TargetClass".hashCode() ^ 12345 ^ 42
		int expectedXorKey = EXPECTED_CALLER.hashCode() ^ DECRYPT_KEY;
		int expectedValue = expectedXorKey ^ RANDOM_ARRAY[DECRYPT_INDEX];
		assertTrue(decryptedText.contains(String.valueOf(expectedValue)),
				"Decrypted value should be " + expectedValue + " but got: " + decryptedText);

		// Step 5: Direct invocation with stack trace override
		Map<String, Object> invokeArgs = new HashMap<>();
		invokeArgs.put("className", "test.DecryptionEngine");
		invokeArgs.put("methodName", "decrypt");
		invokeArgs.put("methodDescriptor", "(II)Ljava/lang/String;");
		invokeArgs.put("args", List.of(DECRYPT_KEY, DECRYPT_INDEX));
		invokeArgs.put("stackTraceOverride", stackFrames);

		CallToolResult directResult = callTool("vm-invoke-method", exchange, invokeArgs);
		assertFalse(isErrorResult(directResult),
				"direct invocation should succeed: " + getResultText(directResult));

		String directText = getResultText(directResult);
		assertTrue(directText.contains(String.valueOf(expectedValue)),
				"Direct invocation should return " + expectedValue + " but got: " + directText);

		// Step 6: Invoke WITHOUT override -- result should differ
		Map<String, Object> noOverrideArgs = new HashMap<>();
		noOverrideArgs.put("className", "test.DecryptionEngine");
		noOverrideArgs.put("methodName", "decrypt");
		noOverrideArgs.put("methodDescriptor", "(II)Ljava/lang/String;");
		noOverrideArgs.put("args", List.of(DECRYPT_KEY, DECRYPT_INDEX));

		CallToolResult noOverrideResult = callTool("vm-invoke-method", exchange, noOverrideArgs);
		// The result may succeed or fail (interpreter stack frames produce different hashCode).
		// Either way, the value should differ from the override case.
		if (!isErrorResult(noOverrideResult)) {
			String noOverrideText = getResultText(noOverrideResult);
			// The XOR key is derived from a different caller class, so the result should differ
			// (unless by extreme coincidence the hashCodes collide)
			assertNotEquals(directText, noOverrideText,
					"Without stack trace override, the decrypted value should differ");
		}
		// If it errors (e.g. interpreter frame issues), that's acceptable too
	}

	// ---- Synthetic class builders ----

	/**
	 * Build {@code test/DecryptionEngine} with ASM:
	 * <pre>
	 * public class DecryptionEngine {
	 *     static int[] randomArray;
	 *
	 *     static {
	 *         randomArray = new int[]{42, 17, 99, 256, 8};
	 *     }
	 *
	 *     public static String decrypt(int key, int index) {
	 *         StackTraceElement[] stack = Thread.currentThread().getStackTrace();
	 *         String callerClass = stack[3].getClassName();
	 *         int xorKey = callerClass.hashCode() ^ key;
	 *         return String.valueOf(xorKey ^ randomArray[index]);
	 *     }
	 * }
	 * </pre>
	 */
	private static JvmClassInfo buildDecryptionEngineClass() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
				"test/DecryptionEngine", null, "java/lang/Object", null);

		// Field: static int[] randomArray
		cw.visitField(Opcodes.ACC_STATIC, "randomArray", "[I", null, null).visitEnd();

		// <clinit>: Initialize randomArray
		{
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
			mv.visitCode();

			// randomArray = new int[5]
			mv.visitIntInsn(Opcodes.BIPUSH, 5);
			mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);

			// Store values: {42, 17, 99, 256, 8}
			int[] values = RANDOM_ARRAY;
			for (int i = 0; i < values.length; i++) {
				mv.visitInsn(Opcodes.DUP);
				pushInt(mv, i);
				pushInt(mv, values[i]);
				mv.visitInsn(Opcodes.IASTORE);
			}

			mv.visitFieldInsn(Opcodes.PUTSTATIC, "test/DecryptionEngine", "randomArray", "[I");
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

		// decrypt(int key, int index) -> String
		{
			MethodVisitor mv = cw.visitMethod(
					Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
					"decrypt", "(II)Ljava/lang/String;", null, null);
			mv.visitCode();

			// StackTraceElement[] stack = Thread.currentThread().getStackTrace();
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread",
					"currentThread", "()Ljava/lang/Thread;", false);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread",
					"getStackTrace", "()[Ljava/lang/StackTraceElement;", false);
			mv.visitVarInsn(Opcodes.ASTORE, 2); // stack -> local 2

			// String callerClass = stack[3].getClassName();
			mv.visitVarInsn(Opcodes.ALOAD, 2);
			mv.visitInsn(Opcodes.ICONST_3);
			mv.visitInsn(Opcodes.AALOAD);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StackTraceElement",
					"getClassName", "()Ljava/lang/String;", false);
			mv.visitVarInsn(Opcodes.ASTORE, 3); // callerClass -> local 3

			// int xorKey = callerClass.hashCode() ^ key;
			mv.visitVarInsn(Opcodes.ALOAD, 3);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String",
					"hashCode", "()I", false);
			mv.visitVarInsn(Opcodes.ILOAD, 0); // key
			mv.visitInsn(Opcodes.IXOR);
			mv.visitVarInsn(Opcodes.ISTORE, 4); // xorKey -> local 4

			// return String.valueOf(xorKey ^ randomArray[index]);
			mv.visitVarInsn(Opcodes.ILOAD, 4); // xorKey
			mv.visitFieldInsn(Opcodes.GETSTATIC, "test/DecryptionEngine", "randomArray", "[I");
			mv.visitVarInsn(Opcodes.ILOAD, 1); // index
			mv.visitInsn(Opcodes.IALOAD);
			mv.visitInsn(Opcodes.IXOR);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String",
					"valueOf", "(I)Ljava/lang/String;", false);
			mv.visitInsn(Opcodes.ARETURN);

			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

		// Default constructor
		{
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
			mv.visitCode();
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

		cw.visitEnd();
		return new JvmClassInfoBuilder(cw.toByteArray()).build();
	}

	/**
	 * Build {@code test/TargetClass} with ASM:
	 * <pre>
	 * public class TargetClass {
	 *     public static String decryptedValue;
	 *
	 *     static {
	 *         decryptedValue = DecryptionEngine.decrypt(12345, 0);
	 *     }
	 * }
	 * </pre>
	 */
	private static JvmClassInfo buildTargetClass() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
				"test/TargetClass", null, "java/lang/Object", null);

		// Field: public static String decryptedValue
		cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
				"decryptedValue", "Ljava/lang/String;", null, null).visitEnd();

		// <clinit>: decryptedValue = DecryptionEngine.decrypt(12345, 0)
		{
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
			mv.visitCode();

			pushInt(mv, DECRYPT_KEY);
			pushInt(mv, DECRYPT_INDEX);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "test/DecryptionEngine",
					"decrypt", "(II)Ljava/lang/String;", false);
			mv.visitFieldInsn(Opcodes.PUTSTATIC, "test/TargetClass", "decryptedValue", "Ljava/lang/String;");
			mv.visitInsn(Opcodes.RETURN);

			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

		// Default constructor
		{
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
			mv.visitCode();
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

		cw.visitEnd();
		return new JvmClassInfoBuilder(cw.toByteArray()).build();
	}

	// ---- ASM helper ----

	/**
	 * Push an int constant onto the stack using the most compact bytecode.
	 */
	private static void pushInt(MethodVisitor mv, int value) {
		if (value >= -1 && value <= 5) {
			mv.visitInsn(Opcodes.ICONST_0 + value);
		} else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
			mv.visitIntInsn(Opcodes.BIPUSH, value);
		} else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
			mv.visitIntInsn(Opcodes.SIPUSH, value);
		} else {
			mv.visitLdcInsn(value);
		}
	}

	// ---- Workspace mock ----

	private Workspace mockWorkspaceWithClasses(JvmClassInfo... classes) {
		Workspace workspace = mock(Workspace.class);
		WorkspaceResource primaryResource = mock(WorkspaceResource.class);
		JvmClassBundle classBundle = mock(JvmClassBundle.class);

		when(workspace.getPrimaryResource()).thenReturn(primaryResource);
		when(primaryResource.getJvmClassBundle()).thenReturn(classBundle);
		when(workspace.getSupportingResources()).thenReturn(Collections.emptyList());

		for (JvmClassInfo classInfo : classes) {
			when(classBundle.get(classInfo.getName())).thenReturn(classInfo);
		}

		return workspace;
	}

	// ---- Tool invocation helpers ----

	private CallToolResult callTool(String toolName, McpSyncServerExchange exchange, Map<String, Object> args) {
		BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler = toolHandlers.get(toolName);
		assertNotNull(handler, "Tool '" + toolName + "' should be registered. Available: " + toolHandlers.keySet());

		CallToolRequest request = new CallToolRequest(toolName, args);
		return handler.apply(exchange, request);
	}

	private static String getResultText(CallToolResult result) {
		if (result.content() == null || result.content().isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (var content : result.content()) {
			if (content instanceof TextContent text) {
				sb.append(text.text());
			}
		}
		return sb.toString();
	}

	private static boolean isErrorResult(CallToolResult result) {
		return result.isError() != null && result.isError();
	}
}
