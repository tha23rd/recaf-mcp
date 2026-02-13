package dev.recafmcp.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClassResolverTest {

	@Test
	void normalizeClassName_dotsToSlashes() {
		assertEquals("com/example/Foo", ClassResolver.normalizeClassName("com.example.Foo"));
	}

	@Test
	void normalizeClassName_slashesPreserved() {
		assertEquals("com/example/Foo", ClassResolver.normalizeClassName("com/example/Foo"));
	}

	@Test
	void normalizeClassName_trimsWhitespace() {
		assertEquals("com/example/Foo", ClassResolver.normalizeClassName("  com.example.Foo  "));
	}

	@Test
	void normalizeClassName_nullReturnsNull() {
		assertNull(ClassResolver.normalizeClassName(null));
	}

	@Test
	void normalizeClassName_emptyString() {
		assertEquals("", ClassResolver.normalizeClassName(""));
	}

	@Test
	void normalizeClassName_simpleNameUnchanged() {
		assertEquals("Foo", ClassResolver.normalizeClassName("Foo"));
	}

	@Test
	void levenshteinDistance_identicalStrings() {
		assertEquals(0, ClassResolver.levenshteinDistance("foo", "foo"));
	}

	@Test
	void levenshteinDistance_singleEdit() {
		assertEquals(1, ClassResolver.levenshteinDistance("foo", "fop"));
	}

	@Test
	void levenshteinDistance_insertion() {
		assertEquals(1, ClassResolver.levenshteinDistance("foo", "fooo"));
	}

	@Test
	void levenshteinDistance_deletion() {
		assertEquals(1, ClassResolver.levenshteinDistance("foo", "fo"));
	}

	@Test
	void levenshteinDistance_emptyStrings() {
		assertEquals(0, ClassResolver.levenshteinDistance("", ""));
		assertEquals(3, ClassResolver.levenshteinDistance("abc", ""));
		assertEquals(3, ClassResolver.levenshteinDistance("", "abc"));
	}

	@Test
	void levenshteinDistance_completelyDifferent() {
		assertEquals(3, ClassResolver.levenshteinDistance("abc", "xyz"));
	}
}
