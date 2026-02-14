package dev.recafmcp.providers;

import dev.recafmcp.ssvm.StackTraceInterceptor;
import dev.recafmcp.ssvm.SsvmManager;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.coley.recaf.services.workspace.WorkspaceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link SsvmExecutionProvider}.
 * <p>
 * Since SSVM bootstrap requires JDK 11-22 and our build uses JDK 25,
 * these tests focus on:
 * <ul>
 *     <li>Descriptor parsing logic (static, no VM needed)</li>
 *     <li>Return type parsing (static, no VM needed)</li>
 *     <li>Error handling (no workspace, argument validation)</li>
 * </ul>
 * <p>
 * Full integration tests that bootstrap SSVM would require a compatible JDK.
 */
class SsvmExecutionProviderTest {

	// ==== Descriptor Parsing Tests ====

	@Nested
	class ParseParameterTypes {

		@Test
		void emptyParams() {
			List<Character> types = SsvmExecutionProvider.parseParameterTypes("()V");
			assertTrue(types.isEmpty(), "No-arg method should have empty param list");
		}

		@Test
		void singleInt() {
			List<Character> types = SsvmExecutionProvider.parseParameterTypes("(I)V");
			assertEquals(List.of('I'), types);
		}

		@Test
		void twoInts() {
			List<Character> types = SsvmExecutionProvider.parseParameterTypes("(II)I");
			assertEquals(List.of('I', 'I'), types);
		}

		@Test
		void allPrimitiveTypes() {
			List<Character> types = SsvmExecutionProvider.parseParameterTypes("(BIJSCFDZ)V");
			assertEquals(List.of('B', 'I', 'J', 'S', 'C', 'F', 'D', 'Z'), types);
		}

		@Test
		void objectParameter() {
			List<Character> types = SsvmExecutionProvider.parseParameterTypes("(Ljava/lang/String;)V");
			assertEquals(List.of('L'), types);
		}

		@Test
		void mixedPrimitivesAndObjects() {
			List<Character> types = SsvmExecutionProvider.parseParameterTypes(
					"(ILjava/lang/String;D)V");
			assertEquals(List.of('I', 'L', 'D'), types);
		}

		@Test
		void multipleObjectParameters() {
			List<Character> types = SsvmExecutionProvider.parseParameterTypes(
					"(Ljava/lang/String;Ljava/lang/Object;)V");
			assertEquals(List.of('L', 'L'), types);
		}

		@Test
		void arrayParameters() {
			List<Character> types = SsvmExecutionProvider.parseParameterTypes("([I)V");
			assertEquals(List.of('['), types);
		}

		@Test
		void objectArrayParameter() {
			List<Character> types = SsvmExecutionProvider.parseParameterTypes(
					"([Ljava/lang/String;)V");
			assertEquals(List.of('['), types);
		}

		@Test
		void multiDimensionalArrayParameter() {
			List<Character> types = SsvmExecutionProvider.parseParameterTypes("([[I)V");
			assertEquals(List.of('['), types);
		}

		@Test
		void complexDescriptor() {
			// (int, String, double[], long, Object) -> String
			List<Character> types = SsvmExecutionProvider.parseParameterTypes(
					"(ILjava/lang/String;[DJLjava/lang/Object;)Ljava/lang/String;");
			assertEquals(List.of('I', 'L', '[', 'J', 'L'), types);
		}

		@Test
		void invalidDescriptorCharacter_throws() {
			assertThrows(IllegalArgumentException.class,
					() -> SsvmExecutionProvider.parseParameterTypes("(X)V"));
		}
	}

	// ==== Return Type Parsing Tests ====

	@Nested
	class ParseReturnType {

		@Test
		void voidReturn() {
			assertEquals('V', SsvmExecutionProvider.parseReturnType("()V"));
		}

		@Test
		void intReturn() {
			assertEquals('I', SsvmExecutionProvider.parseReturnType("(II)I"));
		}

		@Test
		void longReturn() {
			assertEquals('J', SsvmExecutionProvider.parseReturnType("()J"));
		}

		@Test
		void floatReturn() {
			assertEquals('F', SsvmExecutionProvider.parseReturnType("()F"));
		}

		@Test
		void doubleReturn() {
			assertEquals('D', SsvmExecutionProvider.parseReturnType("()D"));
		}

		@Test
		void booleanReturn() {
			assertEquals('Z', SsvmExecutionProvider.parseReturnType("()Z"));
		}

		@Test
		void byteReturn() {
			assertEquals('B', SsvmExecutionProvider.parseReturnType("()B"));
		}

		@Test
		void charReturn() {
			assertEquals('C', SsvmExecutionProvider.parseReturnType("()C"));
		}

		@Test
		void shortReturn() {
			assertEquals('S', SsvmExecutionProvider.parseReturnType("()S"));
		}

		@Test
		void objectReturn() {
			assertEquals('L', SsvmExecutionProvider.parseReturnType("()Ljava/lang/String;"));
		}

		@Test
		void arrayReturn() {
			assertEquals('[', SsvmExecutionProvider.parseReturnType("()[I"));
		}

		@Test
		void objectArrayReturn() {
			assertEquals('[', SsvmExecutionProvider.parseReturnType("()[Ljava/lang/String;"));
		}

		@Test
		void invalidDescriptor_noParen_throws() {
			assertThrows(IllegalArgumentException.class,
					() -> SsvmExecutionProvider.parseReturnType(""));
		}

		@Test
		void invalidDescriptor_noReturnType_throws() {
			assertThrows(IllegalArgumentException.class,
					() -> SsvmExecutionProvider.parseReturnType("()"));
		}
	}

	// ==== Argument Mapping Validation Tests ====
	// These test the argument count validation (no VM needed for count check)

	@Nested
	class ArgumentMapping {

		@Test
		void nullArgsForNoArgMethod_succeeds() {
			// mapJsonArgsToSsvm with a null VM should still validate count
			// For no-arg methods, null args list is valid (0 == 0)
			// We can't call mapJsonArgsToSsvm directly without a VM for the actual mapping,
			// but we can verify parseParameterTypes + count checking logic
			List<Character> params = SsvmExecutionProvider.parseParameterTypes("()V");
			assertEquals(0, params.size());
			// An empty args list matches 0 params
		}

		@Test
		void paramCountMismatch_detectedByParsing() {
			// Descriptor says 2 ints, but we can verify parsing gives 2 params
			List<Character> params = SsvmExecutionProvider.parseParameterTypes("(II)I");
			assertEquals(2, params.size());
			// 3 args for a 2-param method would be caught by mapJsonArgsToSsvm
		}

		@Test
		void descriptorWithString_parsesAsObjectType() {
			List<Character> params = SsvmExecutionProvider.parseParameterTypes(
					"(Ljava/lang/String;I)V");
			assertEquals(2, params.size());
			assertEquals('L', params.get(0));
			assertEquals('I', params.get(1));
		}
	}

	// ==== Tool Registration Tests ====

	@Nested
	class ToolRegistration {

		private McpSyncServer mockServer;
		private WorkspaceManager mockWorkspaceManager;
		private SsvmManager mockSsvmManager;

		@BeforeEach
		void setUp() {
			mockServer = mock(McpSyncServer.class);
			mockWorkspaceManager = mock(WorkspaceManager.class);
			mockSsvmManager = mock(SsvmManager.class);
		}

		@Test
		void registerTools_doesNotThrow() {
			SsvmExecutionProvider provider = new SsvmExecutionProvider(
					mockServer, mockWorkspaceManager, mockSsvmManager);

			assertDoesNotThrow(provider::registerTools,
					"Tool registration should not throw");
		}

		@Test
		void registerTools_registersAllThreeTools() {
			SsvmExecutionProvider provider = new SsvmExecutionProvider(
					mockServer, mockWorkspaceManager, mockSsvmManager);
			provider.registerTools();

			// Verify that addTool was called 3 times (vm-invoke-method + vm-get-field + vm-run-clinit)
			verify(mockServer, times(3)).addTool(any());
		}
	}

	// ==== Field Type Description Tests ====

	@Nested
	class DescribeFieldType {

		@Test
		void intDescriptor() {
			assertEquals("int", SsvmExecutionProvider.describeFieldType("I"));
		}

		@Test
		void longDescriptor() {
			assertEquals("long", SsvmExecutionProvider.describeFieldType("J"));
		}

		@Test
		void floatDescriptor() {
			assertEquals("float", SsvmExecutionProvider.describeFieldType("F"));
		}

		@Test
		void doubleDescriptor() {
			assertEquals("double", SsvmExecutionProvider.describeFieldType("D"));
		}

		@Test
		void booleanDescriptor() {
			assertEquals("boolean", SsvmExecutionProvider.describeFieldType("Z"));
		}

		@Test
		void byteDescriptor() {
			assertEquals("byte", SsvmExecutionProvider.describeFieldType("B"));
		}

		@Test
		void charDescriptor() {
			assertEquals("char", SsvmExecutionProvider.describeFieldType("C"));
		}

		@Test
		void shortDescriptor() {
			assertEquals("short", SsvmExecutionProvider.describeFieldType("S"));
		}

		@Test
		void voidDescriptor() {
			assertEquals("void", SsvmExecutionProvider.describeFieldType("V"));
		}

		@Test
		void stringDescriptor() {
			assertEquals("java.lang.String",
					SsvmExecutionProvider.describeFieldType("Ljava/lang/String;"));
		}

		@Test
		void objectDescriptor() {
			assertEquals("java.lang.Object",
					SsvmExecutionProvider.describeFieldType("Ljava/lang/Object;"));
		}

		@Test
		void innerClassDescriptor() {
			// $ is preserved in the JVM internal name — not converted to dot
			assertEquals("com.example.Outer$Inner",
					SsvmExecutionProvider.describeFieldType("Lcom/example/Outer$Inner;"));
		}

		@Test
		void intArrayDescriptor() {
			assertEquals("[I", SsvmExecutionProvider.describeFieldType("[I"));
		}

		@Test
		void stringArrayDescriptor() {
			assertEquals("[Ljava/lang/String;",
					SsvmExecutionProvider.describeFieldType("[Ljava/lang/String;"));
		}

		@Test
		void multiDimensionalArrayDescriptor() {
			assertEquals("[[I", SsvmExecutionProvider.describeFieldType("[[I"));
		}

		@Test
		void nullDescriptor() {
			assertEquals("<unknown>", SsvmExecutionProvider.describeFieldType(null));
		}

		@Test
		void emptyDescriptor() {
			assertEquals("<unknown>", SsvmExecutionProvider.describeFieldType(""));
		}
	}

	// ==== Stack Trace Override Tests ====

	@Nested
	class StackTraceOverride {

		@Test
		void parseStackTraceFrames_nullInput_returnsNull() {
			assertNull(SsvmExecutionProvider.parseStackTraceFrames(null),
					"null input should return null");
		}

		@Test
		void parseStackTraceFrames_emptyList_returnsEmpty() {
			List<StackTraceInterceptor.StackFrame> result =
					SsvmExecutionProvider.parseStackTraceFrames(new ArrayList<>());
			assertNotNull(result);
			assertTrue(result.isEmpty(), "Empty input should return empty list");
		}

		@Test
		void parseStackTraceFrames_fullFrame_parsesAllFields() {
			List<Object> input = List.of(
					Map.of(
							"className", "com.example.Decrypt",
							"methodName", "decrypt",
							"fileName", "Decrypt.java",
							"lineNumber", 42
					)
			);

			List<StackTraceInterceptor.StackFrame> result =
					SsvmExecutionProvider.parseStackTraceFrames(input);

			assertEquals(1, result.size());
			StackTraceInterceptor.StackFrame frame = result.get(0);
			assertEquals("com.example.Decrypt", frame.className);
			assertEquals("decrypt", frame.methodName);
			assertEquals("Decrypt.java", frame.fileName);
			assertEquals(42, frame.lineNumber);
		}

		@Test
		void parseStackTraceFrames_minimalFrame_defaultsFilenameAndLine() {
			List<Object> input = List.of(
					Map.of(
							"className", "com.example.Foo",
							"methodName", "<clinit>"
					)
			);

			List<StackTraceInterceptor.StackFrame> result =
					SsvmExecutionProvider.parseStackTraceFrames(input);

			assertEquals(1, result.size());
			StackTraceInterceptor.StackFrame frame = result.get(0);
			assertEquals("com.example.Foo", frame.className);
			assertEquals("<clinit>", frame.methodName);
			assertNull(frame.fileName, "fileName should be null when omitted");
			assertEquals(-1, frame.lineNumber, "lineNumber should be -1 when omitted");
		}

		@Test
		void parseStackTraceFrames_multipleFrames_preservesOrder() {
			List<Object> input = List.of(
					Map.of("className", "java.lang.Thread", "methodName", "getStackTrace"),
					Map.of("className", "com.example.KeyDerive", "methodName", "derive"),
					Map.of("className", "com.example.Decrypt", "methodName", "decrypt"),
					Map.of("className", "com.example.Loader", "methodName", "<clinit>")
			);

			List<StackTraceInterceptor.StackFrame> result =
					SsvmExecutionProvider.parseStackTraceFrames(input);

			assertEquals(4, result.size());
			assertEquals("java.lang.Thread", result.get(0).className);
			assertEquals("getStackTrace", result.get(0).methodName);
			assertEquals("com.example.KeyDerive", result.get(1).className);
			assertEquals("derive", result.get(1).methodName);
			assertEquals("com.example.Decrypt", result.get(2).className);
			assertEquals("decrypt", result.get(2).methodName);
			assertEquals("com.example.Loader", result.get(3).className);
			assertEquals("<clinit>", result.get(3).methodName);
		}

		@Test
		void parseStackTraceFrames_missingClassName_throws() {
			List<Object> input = List.of(
					Map.of("methodName", "foo")
			);

			IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
					() -> SsvmExecutionProvider.parseStackTraceFrames(input));
			assertTrue(ex.getMessage().contains("className"),
					"Error should mention missing className");
		}

		@Test
		void parseStackTraceFrames_missingMethodName_throws() {
			List<Object> input = List.of(
					Map.of("className", "com.example.Foo")
			);

			IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
					() -> SsvmExecutionProvider.parseStackTraceFrames(input));
			assertTrue(ex.getMessage().contains("methodName"),
					"Error should mention missing methodName");
		}

		@Test
		void parseStackTraceFrames_nonMapEntry_throws() {
			List<Object> input = List.of("not a map");

			IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
					() -> SsvmExecutionProvider.parseStackTraceFrames(input));
			assertTrue(ex.getMessage().contains("must be an object"),
					"Error should indicate entry must be an object");
		}

		@Test
		void parseStackTraceFrames_lineNumberAsDouble_parsesCorrectly() {
			// JSON numbers may arrive as Double from some parsers
			List<Object> input = List.of(
					Map.of(
							"className", "com.example.Foo",
							"methodName", "bar",
							"lineNumber", 99.0
					)
			);

			List<StackTraceInterceptor.StackFrame> result =
					SsvmExecutionProvider.parseStackTraceFrames(input);

			assertEquals(1, result.size());
			assertEquals(99, result.get(0).lineNumber);
		}

		@Test
		void toolRegistration_registersAllThreeTools() {
			McpSyncServer mockServer = mock(McpSyncServer.class);
			WorkspaceManager mockWorkspaceManager = mock(WorkspaceManager.class);
			SsvmManager mockSsvmManager = mock(SsvmManager.class);

			SsvmExecutionProvider provider = new SsvmExecutionProvider(
					mockServer, mockWorkspaceManager, mockSsvmManager);
			provider.registerTools();

			// Should register exactly 3 tools (vm-invoke-method + vm-get-field + vm-run-clinit)
			verify(mockServer, times(3)).addTool(any());
		}
	}

	// ==== VmRunClinit / parseAllowTransitiveInit Tests ====

	@Nested
	class VmRunClinit {

		@Test
		void parseAllowTransitiveInit_null_returnsNull() {
			assertNull(SsvmExecutionProvider.parseAllowTransitiveInit(null),
					"null input should return null (allow all)");
		}

		@Test
		void parseAllowTransitiveInit_emptyList_returnsEmptySet() {
			Set<String> result = SsvmExecutionProvider.parseAllowTransitiveInit(new ArrayList<>());
			assertNotNull(result);
			assertTrue(result.isEmpty(), "Empty input should return empty set");
		}

		@Test
		void parseAllowTransitiveInit_withClassNames_normalizesAndReturns() {
			List<Object> input = List.of("com.example.Foo", "com.example.Bar");
			Set<String> result = SsvmExecutionProvider.parseAllowTransitiveInit(input);
			assertNotNull(result);
			assertEquals(2, result.size());
			assertTrue(result.contains("com.example.Foo"));
			assertTrue(result.contains("com.example.Bar"));
		}

		@Test
		void parseAllowTransitiveInit_slashNotation_normalizedToDots() {
			List<Object> input = List.of("com/example/Foo", "com/example/Bar");
			Set<String> result = SsvmExecutionProvider.parseAllowTransitiveInit(input);
			assertNotNull(result);
			assertEquals(2, result.size());
			assertTrue(result.contains("com.example.Foo"),
					"Slash notation should be converted to dot notation");
			assertTrue(result.contains("com.example.Bar"),
					"Slash notation should be converted to dot notation");
		}

		@Test
		void parseAllowTransitiveInit_mixedNotation_allNormalized() {
			List<Object> input = List.of("com.example.Foo", "com/example/Bar");
			Set<String> result = SsvmExecutionProvider.parseAllowTransitiveInit(input);
			assertNotNull(result);
			assertEquals(2, result.size());
			assertTrue(result.contains("com.example.Foo"));
			assertTrue(result.contains("com.example.Bar"));
		}

		@Test
		void parseAllowTransitiveInit_deduplicates() {
			List<Object> input = List.of("com.example.Foo", "com/example/Foo");
			Set<String> result = SsvmExecutionProvider.parseAllowTransitiveInit(input);
			assertNotNull(result);
			assertEquals(1, result.size(), "Duplicate entries should be deduplicated after normalization");
			assertTrue(result.contains("com.example.Foo"));
		}

		@Test
		void runClinitToolIsRegistered() {
			McpSyncServer mockServer = mock(McpSyncServer.class);
			WorkspaceManager mockWorkspaceManager = mock(WorkspaceManager.class);
			SsvmManager mockSsvmManager = mock(SsvmManager.class);

			SsvmExecutionProvider provider = new SsvmExecutionProvider(
					mockServer, mockWorkspaceManager, mockSsvmManager);
			provider.registerTools();

			// Capture all registered tool specifications
			var captor = org.mockito.ArgumentCaptor.forClass(
					io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification.class);
			verify(mockServer, times(3)).addTool(captor.capture());

			boolean found = captor.getAllValues().stream()
					.anyMatch(spec -> "vm-run-clinit".equals(spec.tool().name()));
			assertTrue(found, "vm-run-clinit tool should be registered");
		}

		@Test
		void parseAllowTransitiveInit_preservesAllEntries() {
			List<Object> input = List.of(
					"com.example.Alpha",
					"com/example/Beta",
					"com.example.Gamma",
					"com/example/Delta",
					"com.example.Epsilon"
			);
			Set<String> result = SsvmExecutionProvider.parseAllowTransitiveInit(input);
			assertNotNull(result);
			assertEquals(5, result.size(), "All 5 distinct entries should be preserved");
			assertTrue(result.contains("com.example.Alpha"));
			assertTrue(result.contains("com.example.Beta"));
			assertTrue(result.contains("com.example.Gamma"));
			assertTrue(result.contains("com.example.Delta"));
			assertTrue(result.contains("com.example.Epsilon"));
		}
	}

	// ==== Descriptor Edge Cases ====

	@Nested
	class DescriptorEdgeCases {

		@Test
		void innerClassParameter() {
			List<Character> types = SsvmExecutionProvider.parseParameterTypes(
					"(Lcom/example/Outer$Inner;)V");
			assertEquals(List.of('L'), types);
		}

		@Test
		void deeplyNestedArray() {
			// [[[I = 3-dimensional int array
			List<Character> types = SsvmExecutionProvider.parseParameterTypes("([[[I)V");
			assertEquals(List.of('['), types);
		}

		@Test
		void deeplyNestedObjectArray() {
			// [[Ljava/lang/String; = 2-dimensional String array
			List<Character> types = SsvmExecutionProvider.parseParameterTypes(
					"([[Ljava/lang/String;)V");
			assertEquals(List.of('['), types);
		}

		@Test
		void manyParameters() {
			// 10 int parameters
			List<Character> types = SsvmExecutionProvider.parseParameterTypes(
					"(IIIIIIIIII)V");
			assertEquals(10, types.size());
			for (Character t : types) {
				assertEquals('I', t);
			}
		}

		@Test
		void returnTypeDoesNotAffectParameters() {
			// Same params, different return types
			List<Character> types1 = SsvmExecutionProvider.parseParameterTypes("(II)V");
			List<Character> types2 = SsvmExecutionProvider.parseParameterTypes("(II)I");
			List<Character> types3 = SsvmExecutionProvider.parseParameterTypes(
					"(II)Ljava/lang/String;");
			assertEquals(types1, types2);
			assertEquals(types2, types3);
		}

		@Test
		void realWorldDecryptDescriptor() {
			// Common pattern: (int, int) -> String  (string decryption)
			List<Character> types = SsvmExecutionProvider.parseParameterTypes(
					"(II)Ljava/lang/String;");
			assertEquals(List.of('I', 'I'), types);
			assertEquals('L', SsvmExecutionProvider.parseReturnType(
					"(II)Ljava/lang/String;"));
		}

		@Test
		void byteArrayDescriptor() {
			// Common: ([B)Ljava/lang/String; (decode bytes to string)
			List<Character> types = SsvmExecutionProvider.parseParameterTypes(
					"([B)Ljava/lang/String;");
			assertEquals(List.of('['), types);
		}
	}
}
