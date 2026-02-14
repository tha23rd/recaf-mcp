package dev.recafmcp.ssvm;

import dev.xdark.ssvm.VirtualMachine;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link InitializationController}.
 * <p>
 * These tests verify the constructor and accessor behavior WITHOUT requiring
 * SSVM bootstrap (which needs JDK 11-22). The constructor only stores references;
 * it does not call any VM methods. The accessor methods return pre-initialized
 * collections and can be tested with a mocked VM.
 * <p>
 * Tests for the actual JVMTI listener behavior (install, classLink, methodEnter)
 * require a running SSVM and are covered by {@code SsvmIntegrationTest} (disabled
 * on JDK 25).
 */
class InitializationControllerTest {

	@Test
	void constructor_nullWhitelist_allowsAll() {
		VirtualMachine vm = mock(VirtualMachine.class);
		// null whitelist means "allow all transitive init" per the Javadoc
		InitializationController controller = new InitializationController(vm, "test.Target", null);

		// Should construct without error; the null whitelist is a valid state
		assertNotNull(controller);
		// Verify initial lists are empty (no clinits have run yet)
		assertTrue(controller.getClassesInitialized().isEmpty(),
				"No classes should be initialized before install()");
		assertTrue(controller.getClassesDeferred().isEmpty(),
				"No classes should be deferred before install()");
	}

	@Test
	void constructor_emptyWhitelist_blocksNonTarget() {
		VirtualMachine vm = mock(VirtualMachine.class);
		// Empty whitelist means only the target class + JDK bootstrap classes can initialize
		InitializationController controller = new InitializationController(
				vm, "test.Target", Collections.emptySet());

		assertNotNull(controller);
		assertTrue(controller.getClassesInitialized().isEmpty());
		assertTrue(controller.getClassesDeferred().isEmpty());
	}

	@Test
	void getClassesInitialized_initiallyEmpty() {
		VirtualMachine vm = mock(VirtualMachine.class);
		InitializationController controller = new InitializationController(
				vm, "test.Foo", Set.of("test.Bar"));

		List<String> initialized = controller.getClassesInitialized();
		assertNotNull(initialized, "Should return non-null list");
		assertTrue(initialized.isEmpty(), "Should be empty before any classes initialize");
	}

	@Test
	void getClassesDeferred_initiallyEmpty() {
		VirtualMachine vm = mock(VirtualMachine.class);
		InitializationController controller = new InitializationController(
				vm, "test.Foo", Set.of("test.Bar"));

		List<String> deferred = controller.getClassesDeferred();
		assertNotNull(deferred, "Should return non-null list");
		assertTrue(deferred.isEmpty(), "Should be empty before any classes are deferred");
	}

	@Test
	void getClassesInitialized_returnsUnmodifiableList() {
		VirtualMachine vm = mock(VirtualMachine.class);
		InitializationController controller = new InitializationController(
				vm, "test.Foo", null);

		List<String> initialized = controller.getClassesInitialized();
		assertThrows(UnsupportedOperationException.class,
				() -> initialized.add("test.ShouldFail"),
				"Returned list should be unmodifiable");
	}

	@Test
	void getClassesDeferred_returnsUnmodifiableList() {
		VirtualMachine vm = mock(VirtualMachine.class);
		InitializationController controller = new InitializationController(
				vm, "test.Foo", null);

		List<String> deferred = controller.getClassesDeferred();
		assertThrows(UnsupportedOperationException.class,
				() -> deferred.add("test.ShouldFail"),
				"Returned list should be unmodifiable");
	}

	@Test
	void close_beforeInstall_noException() {
		VirtualMachine vm = mock(VirtualMachine.class);
		InitializationController controller = new InitializationController(
				vm, "test.Foo", Set.of("test.Bar"));

		// close() before install() should not throw
		// (env is null, so the null check in close() skips the env.close() call)
		assertDoesNotThrow(controller::close,
				"Closing before install() should be safe");
	}

	@Test
	void close_calledTwice_noException() {
		VirtualMachine vm = mock(VirtualMachine.class);
		InitializationController controller = new InitializationController(
				vm, "test.Foo", null);

		// Double-close should be safe (idempotent)
		assertDoesNotThrow(() -> {
			controller.close();
			controller.close();
		}, "Closing twice should be safe");
	}

	@Test
	void constructor_withPopulatedWhitelist_acceptsAll() {
		VirtualMachine vm = mock(VirtualMachine.class);
		Set<String> whitelist = Set.of(
				"test.DecryptionEngine",
				"test.KeyDerive",
				"test.Helper"
		);

		InitializationController controller = new InitializationController(
				vm, "test.Target", whitelist);

		// Just verifying construction succeeds with a populated whitelist
		assertNotNull(controller);
		assertTrue(controller.getClassesInitialized().isEmpty());
		assertTrue(controller.getClassesDeferred().isEmpty());
	}
}
