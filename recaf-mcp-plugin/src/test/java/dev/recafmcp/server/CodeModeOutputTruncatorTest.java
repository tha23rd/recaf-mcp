package dev.recafmcp.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeModeOutputTruncatorTest {
	@Test
	void underLimitResponseIsUnchanged() {
		String input = "short output";
		assertEquals(input, CodeModeOutputTruncator.truncate(input, 64));
	}

	@Test
	void overLimitResponseIsTruncatedWithMarker() {
		String input = "x".repeat(256);
		String output = CodeModeOutputTruncator.truncate(input, 80);

		assertTrue(output.length() <= 80, "Expected bounded output length");
		assertTrue(output.contains("output truncated"), "Expected truncation marker");
	}
}
