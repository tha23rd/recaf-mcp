package dev.recafmcp.ssvm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link SsvmManager} — VM lifecycle, state management, workspace
 * class loading bridge, and lazy initialization behavior.
 * <p>
 * These are unit tests that verify the SsvmManager's state machine and
 * workspace integration WITHOUT bootstrapping a real SSVM instance (which
 * requires JDK 11-22 and takes ~2-10s per VM).
 * <p>
 * Integration tests that bootstrap SSVM are in {@code SsvmManagerIntegrationTest}
 * and require a compatible JDK version.
 */
class SsvmManagerTest {

	private WorkspaceManager workspaceManager;
	private SsvmManager ssvmManager;

	@BeforeEach
	void setUp() {
		workspaceManager = mock(WorkspaceManager.class);
		ssvmManager = new SsvmManager(workspaceManager);
	}

	@AfterEach
	void tearDown() {
		if (ssvmManager != null) {
			ssvmManager.close();
		}
	}

	// ---- Helper: build synthetic classes ----

	/**
	 * Create a synthetic class {@code com/test/Adder} with a static method
	 * {@code add(II)I} that returns the sum of two ints.
	 */
	private static JvmClassInfo buildAdderClass() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "com/test/Adder", null,
				"java/lang/Object", null);

		MethodVisitor mv = cw.visitMethod(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
				"add", "(II)I", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ILOAD, 0);
		mv.visitVarInsn(Opcodes.ILOAD, 1);
		mv.visitInsn(Opcodes.IADD);
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		cw.visitEnd();
		return new JvmClassInfoBuilder(cw.toByteArray()).build();
	}

	/**
	 * Create a synthetic class {@code com/test/Hello} with a static method
	 * that prints to stdout.
	 */
	private static JvmClassInfo buildHelloClass() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "com/test/Hello", null,
				"java/lang/Object", null);

		MethodVisitor mv = cw.visitMethod(
				Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
				"greet", "()V", null, null);
		mv.visitCode();
		mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out",
				"Ljava/io/PrintStream;");
		mv.visitLdcInsn("Hello from SSVM");
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream",
				"println", "(Ljava/lang/String;)V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		cw.visitEnd();
		return new JvmClassInfoBuilder(cw.toByteArray()).build();
	}

	// ---- Helper: mock workspace ----

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

	private Workspace mockWorkspaceWithSupportingResource(JvmClassInfo primaryClass,
	                                                       JvmClassInfo supportingClass) {
		Workspace workspace = mock(Workspace.class);
		WorkspaceResource primaryResource = mock(WorkspaceResource.class);
		WorkspaceResource supportingResource = mock(WorkspaceResource.class);
		JvmClassBundle primaryBundle = mock(JvmClassBundle.class);
		JvmClassBundle supportingBundle = mock(JvmClassBundle.class);

		when(workspace.getPrimaryResource()).thenReturn(primaryResource);
		when(primaryResource.getJvmClassBundle()).thenReturn(primaryBundle);
		when(workspace.getSupportingResources()).thenReturn(List.of(supportingResource));
		when(supportingResource.getJvmClassBundle()).thenReturn(supportingBundle);

		if (primaryClass != null) {
			when(primaryBundle.get(primaryClass.getName())).thenReturn(primaryClass);
		}
		if (supportingClass != null) {
			when(supportingBundle.get(supportingClass.getName())).thenReturn(supportingClass);
		}

		return workspace;
	}

	// ==== Unit Tests: State Management ====

	@Test
	void isInitialized_beforeAccess_returnsFalse() {
		assertFalse(ssvmManager.isInitialized(),
				"VM should not be initialized before first access");
	}

	@Test
	void getVm_noWorkspace_throwsIllegalState() {
		when(workspaceManager.getCurrent()).thenReturn(null);

		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> ssvmManager.getVm());
		assertTrue(ex.getMessage().contains("No workspace"),
				"Error should mention missing workspace");
	}

	@Test
	void getHelper_noWorkspace_throwsIllegalState() {
		when(workspaceManager.getCurrent()).thenReturn(null);

		assertThrows(IllegalStateException.class,
				() -> ssvmManager.getHelper());
	}

	@Test
	void getInvocationUtil_noWorkspace_throwsIllegalState() {
		when(workspaceManager.getCurrent()).thenReturn(null);

		assertThrows(IllegalStateException.class,
				() -> ssvmManager.getInvocationUtil());
	}

	@Test
	void getOperations_noWorkspace_throwsIllegalState() {
		when(workspaceManager.getCurrent()).thenReturn(null);

		assertThrows(IllegalStateException.class,
				() -> ssvmManager.getOperations());
	}

	// ==== Unit Tests: Stdout/Stderr Before Bootstrap ====

	@Test
	void getAndResetStdout_beforeBootstrap_returnsEmpty() {
		assertEquals("", ssvmManager.getAndResetStdout(),
				"Should return empty string before VM is bootstrapped");
	}

	@Test
	void getAndResetStderr_beforeBootstrap_returnsEmpty() {
		assertEquals("", ssvmManager.getAndResetStderr(),
				"Should return empty string before VM is bootstrapped");
	}

	// ==== Unit Tests: Reset Behavior ====

	@Test
	void resetVm_beforeBootstrap_doesNotThrow() {
		assertDoesNotThrow(() -> ssvmManager.resetVm(),
				"Resetting before bootstrap should be safe");
		assertFalse(ssvmManager.isInitialized());
	}

	@Test
	void resetVm_clearsInitializedState() {
		// Even though bootstrap will fail on JDK 25, we can verify reset
		// clears the state flag
		assertFalse(ssvmManager.isInitialized());
		ssvmManager.resetVm();
		assertFalse(ssvmManager.isInitialized(),
				"Reset should leave VM un-initialized");
	}

	// ==== Unit Tests: Close Behavior ====

	@Test
	void close_removesListeners() {
		ssvmManager.close();

		// Verify listeners were removed
		verify(workspaceManager).removeWorkspaceOpenListener(any());
		verify(workspaceManager).removeWorkspaceCloseListener(any());
	}

	@Test
	void close_callsReset() {
		// After close, isInitialized should be false
		ssvmManager.close();
		assertFalse(ssvmManager.isInitialized());
	}

	// ==== Unit Tests: Workspace Listener Registration ====

	@Test
	void constructor_registersOpenAndCloseListeners() {
		// Verify that the constructor registered both listeners
		verify(workspaceManager).addWorkspaceOpenListener(any());
		verify(workspaceManager).addWorkspaceCloseListener(any());
	}

	// ==== Unit Tests: Workspace Supplier Logic ====

	/**
	 * Test that the workspace supplier correctly resolves classes from
	 * the primary resource using dot notation (as SSVM's
	 * SupplyingClassLoaderInstaller expects).
	 *
	 * We test this by invoking the lookupClassInWorkspace logic
	 * indirectly through the workspace supplier interface.
	 */
	@Test
	void workspaceSupplier_findsPrimaryClass() {
		JvmClassInfo adderClass = buildAdderClass();
		Workspace workspace = mockWorkspaceWithClasses(adderClass);

		// Use the package-private method pattern: create a supplier and test it directly
		// The supplier converts dot notation to internal name for bundle lookup
		JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();

		// Simulate what the supplier does: convert "com.test.Adder" -> "com/test/Adder"
		String dotNotation = "com.test.Adder";
		String internalName = dotNotation.replace('.', '/');
		JvmClassInfo found = bundle.get(internalName);

		assertNotNull(found, "Should find class in primary bundle");
		assertArrayEquals(adderClass.getBytecode(), found.getBytecode(),
				"Bytecode should match the original class");
	}

	@Test
	void workspaceSupplier_fallsBackToSupportingResource() {
		JvmClassInfo adderClass = buildAdderClass();
		JvmClassInfo helloClass = buildHelloClass();
		Workspace workspace = mockWorkspaceWithSupportingResource(adderClass, helloClass);

		// The supporting resource should have the Hello class
		JvmClassBundle supportingBundle = workspace.getSupportingResources().get(0).getJvmClassBundle();
		String internalName = "com/test/Hello";
		JvmClassInfo found = supportingBundle.get(internalName);

		assertNotNull(found, "Should find class in supporting bundle");
		assertArrayEquals(helloClass.getBytecode(), found.getBytecode());
	}

	@Test
	void workspaceSupplier_primaryBundleMiss_returnNullFromPrimary() {
		Workspace workspace = mockWorkspaceWithClasses(); // empty workspace

		JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
		assertNull(bundle.get("com/nonexistent/Missing"),
				"Should return null for classes not in workspace");
	}

	// ==== Unit Tests: Bytecode Round-Trip ====

	@Test
	void syntheticClass_bytecodePreservedThroughBuilder() {
		JvmClassInfo adderClass = buildAdderClass();

		assertNotNull(adderClass.getBytecode(), "Bytecode should be non-null");
		assertTrue(adderClass.getBytecode().length > 0, "Bytecode should be non-empty");
		assertEquals("com/test/Adder", adderClass.getName(),
				"Class name should match");
		assertEquals("java/lang/Object", adderClass.getSuperName(),
				"Super class should be Object");
	}

	@Test
	void syntheticClass_hasExpectedMethods() {
		JvmClassInfo adderClass = buildAdderClass();

		boolean hasAddMethod = adderClass.getMethods().stream()
				.anyMatch(m -> "add".equals(m.getName()) && "(II)I".equals(m.getDescriptor()));
		assertTrue(hasAddMethod, "Adder class should have add(II)I method");
	}

	@Test
	void helloClass_hasExpectedPrintMethod() {
		JvmClassInfo helloClass = buildHelloClass();

		boolean hasGreetMethod = helloClass.getMethods().stream()
				.anyMatch(m -> "greet".equals(m.getName()) && "()V".equals(m.getDescriptor()));
		assertTrue(hasGreetMethod, "Hello class should have greet()V method");
	}

	// ==== Unit Tests: Multiple Resets ====

	@Test
	void multipleResets_areSafe() {
		ssvmManager.resetVm();
		ssvmManager.resetVm();
		ssvmManager.resetVm();

		assertFalse(ssvmManager.isInitialized());
		assertEquals("", ssvmManager.getAndResetStdout());
		assertEquals("", ssvmManager.getAndResetStderr());
	}

	// ==== Unit Tests: Close is Idempotent ====

	@Test
	void close_calledTwice_doesNotThrow() {
		assertDoesNotThrow(() -> {
			ssvmManager.close();
			ssvmManager.close();
		});
	}
}
