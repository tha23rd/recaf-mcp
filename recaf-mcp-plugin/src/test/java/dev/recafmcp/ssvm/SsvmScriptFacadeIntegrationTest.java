package dev.recafmcp.ssvm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real-bootstrap integration test for {@link SsvmScriptFacade} (recaf-mcp-aox).
 * <p>
 * Builds a tiny synthetic class with ASM, mocks a {@link WorkspaceManager}
 * around it, and exercises the facade's four headline operations against a
 * real SSVM:
 * <ul>
 *     <li>{@code invokeStatic} on a primitive-arg / primitive-return method</li>
 *     <li>{@code invokeStatic} on a String-return method</li>
 *     <li>{@code getStaticField} on int + String fields</li>
 *     <li>{@code setStaticField} on int + String fields, round-tripped via {@code getStaticField}</li>
 *     <li>{@code runClinit} idempotency</li>
 * </ul>
 * The bootstrap is slow (~2-10s) and shares the {@link SsvmIntegrationTest} JDK
 * 11-22 prerequisite — there is no point duplicating that coverage in the fast
 * unit suite. Disabled in CI for the same reason: SSVM cannot bootstrap inside
 * a JDK 25 test JVM (Project Loom restructured {@code Thread}). To run locally:
 * <pre>
 * SSVM_BOOT_JDK=/path/to/jdk-11..22 ./gradlew test \
 *     --tests dev.recafmcp.ssvm.SsvmScriptFacadeIntegrationTest \
 *     -Dorg.gradle.java.home=/path/to/jdk-11..22
 * </pre>
 */
@Disabled("Requires JDK 11-22 for SSVM bootstrap -- run manually on a compatible JDK")
class SsvmScriptFacadeIntegrationTest {
	private static final String CLASS_INTERNAL = "test/Calculator";
	private static final String CLASS_DOT = "test.Calculator";

	private WorkspaceManager workspaceManager;
	private SsvmManager ssvmManager;
	private SsvmScriptFacade facade;

	@BeforeEach
	void setUp() {
		workspaceManager = mock(WorkspaceManager.class);
		Workspace workspace = mockWorkspaceWith(buildCalculatorClass());
		when(workspaceManager.getCurrent()).thenReturn(workspace);

		ssvmManager = new SsvmManager(workspaceManager, null);
		facade = new SsvmScriptFacade(ssvmManager);
	}

	@AfterEach
	void tearDown() {
		if (ssvmManager != null) {
			ssvmManager.close();
		}
	}

	@Test
	void invokeStaticPrimitiveArgsAndReturn() {
		// add(int, int) -> int : a contract a Groovy script can hit in one line.
		Object result = facade.invokeStatic(CLASS_DOT, "add", "(II)I", 7, 35);
		assertEquals(42, ((Number) result).intValue(),
				"invokeStatic should return the boxed primitive return value");
	}

	@Test
	void invokeStaticStringReturnIsDecodedToJavaString() {
		// label() -> String : facade should decode java.lang.String to a Java String,
		// not leave it as an opaque ObjectValue (the ergonomic win agents asked for).
		Object result = facade.invokeStatic(CLASS_DOT, "label", "()Ljava/lang/String;");
		assertEquals("calc-v1", result);
	}

	@Test
	void argumentCountMismatchProducesActionableError() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> facade.invokeStatic(CLASS_DOT, "add", "(II)I", 1));
		assertTrue(ex.getMessage().toLowerCase().contains("argument count"),
				"Expected actionable arg count error, got: " + ex.getMessage());
	}

	@Test
	void unknownMethodReportsHelpfully() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> facade.invokeStatic(CLASS_DOT, "doesNotExist", "(I)I", 1));
		assertTrue(ex.getMessage().toLowerCase().contains("not found"));
	}

	@Test
	void getAndSetStaticIntField() {
		// counter starts at 7 (initialized in <clinit>); we read it, write a new value,
		// and read again. Round-tripping verifies setStaticField actually mutates the VM.
		Object initial = facade.getStaticField(CLASS_DOT, "counter");
		assertEquals(7, ((Number) initial).intValue());

		facade.setStaticField(CLASS_DOT, "counter", 99);
		Object updated = facade.getStaticField(CLASS_DOT, "counter");
		assertEquals(99, ((Number) updated).intValue());
	}

	@Test
	void getAndSetStaticStringField() {
		Object initial = facade.getStaticField(CLASS_DOT, "tag");
		assertEquals("init", initial);

		facade.setStaticField(CLASS_DOT, "tag", "rewritten");
		Object updated = facade.getStaticField(CLASS_DOT, "tag");
		assertEquals("rewritten", updated);
	}

	@Test
	void runClinitIsIdempotent() {
		facade.runClinit(CLASS_DOT);
		facade.runClinit(CLASS_DOT); // no exception, no double-init
		// counter should still reflect the <clinit> value (7), not be re-zeroed
		assertEquals(7, ((Number) facade.getStaticField(CLASS_DOT, "counter")).intValue());
	}

	// ---- Synthetic class ----

	/**
	 * Build {@code test/Calculator} with ASM:
	 * <pre>
	 * class Calculator {
	 *     static int counter = 7;
	 *     static String tag = "init";
	 *     static int add(int a, int b) { return a + b; }
	 *     static String label() { return "calc-v1"; }
	 * }
	 * </pre>
	 */
	private static JvmClassInfo buildCalculatorClass() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
				CLASS_INTERNAL, null, "java/lang/Object", null);

		// static int counter
		cw.visitField(Opcodes.ACC_STATIC, "counter", "I", null, null).visitEnd();
		// static String tag
		cw.visitField(Opcodes.ACC_STATIC, "tag", "Ljava/lang/String;", null, null).visitEnd();

		// <clinit>: counter = 7; tag = "init";
		{
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
			mv.visitCode();
			mv.visitIntInsn(Opcodes.BIPUSH, 7);
			mv.visitFieldInsn(Opcodes.PUTSTATIC, CLASS_INTERNAL, "counter", "I");
			mv.visitLdcInsn("init");
			mv.visitFieldInsn(Opcodes.PUTSTATIC, CLASS_INTERNAL, "tag", "Ljava/lang/String;");
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

		// static int add(int a, int b) { return a + b; }
		{
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
					"add", "(II)I", null, null);
			mv.visitCode();
			mv.visitVarInsn(Opcodes.ILOAD, 0);
			mv.visitVarInsn(Opcodes.ILOAD, 1);
			mv.visitInsn(Opcodes.IADD);
			mv.visitInsn(Opcodes.IRETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

		// static String label() { return "calc-v1"; }
		{
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
					"label", "()Ljava/lang/String;", null, null);
			mv.visitCode();
			mv.visitLdcInsn("calc-v1");
			mv.visitInsn(Opcodes.ARETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

		return new JvmClassInfoBuilder(cw.toByteArray()).build();
	}

	private static Workspace mockWorkspaceWith(JvmClassInfo... classes) {
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
}
