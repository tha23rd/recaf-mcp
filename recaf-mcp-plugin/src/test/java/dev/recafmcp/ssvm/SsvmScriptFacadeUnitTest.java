package dev.recafmcp.ssvm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SsvmScriptFacade} behaviour that does not require an SSVM bootstrap.
 * <p>
 * The slow integration round-trip (real VM, real ASM-built class) lives in
 * {@code SsvmScriptFacadeIntegrationTest}. This test class focuses on the parts that
 * historically broke or surprised script authors: descriptor parsing edge cases,
 * argument-count mismatch, and friendly error messages on bad input.
 */
class SsvmScriptFacadeUnitTest {

	@Test
	void parseSimplePrimitiveDescriptor() {
		List<Character> types = SsvmScriptFacade.parseParameterTypes("(II)I");
		assertEquals(List.of('I', 'I'), types);
		assertEquals('I', SsvmScriptFacade.parseReturnType("(II)I"));
	}

	@Test
	void parseDescriptorWithReferenceAndArray() {
		// A method like (int, String, byte[]) -> Object
		List<Character> types =
				SsvmScriptFacade.parseParameterTypes("(ILjava/lang/String;[B)Ljava/lang/Object;");
		assertEquals(List.of('I', 'L', '['), types);
		assertEquals('L', SsvmScriptFacade.parseReturnType("(ILjava/lang/String;[B)Ljava/lang/Object;"));
	}

	@Test
	void parseVoidReturn() {
		assertEquals('V', SsvmScriptFacade.parseReturnType("()V"));
		assertEquals(List.of(), SsvmScriptFacade.parseParameterTypes("()V"));
	}

	@Test
	void parseMultiDimensionalArray() {
		// e.g. (int[][], Object) -> long
		List<Character> types =
				SsvmScriptFacade.parseParameterTypes("([[ILjava/lang/Object;)J");
		assertEquals(List.of('[', 'L'), types);
		assertEquals('J', SsvmScriptFacade.parseReturnType("([[ILjava/lang/Object;)J"));
	}

	@Test
	void invalidDescriptorRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> SsvmScriptFacade.parseReturnType("not-a-descriptor"));
		assertThrows(IllegalArgumentException.class,
				() -> SsvmScriptFacade.parseParameterTypes("(Q)V"));
	}

	@Test
	void constructorRejectsNullManager() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> new SsvmScriptFacade(null));
		assertTrue(ex.getMessage().toLowerCase().contains("ssvmmanager"));
	}
}
