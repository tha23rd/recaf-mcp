package dev.recafmcp.providers;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

	@Test
	void registeredToolIsReturnedBySearch() {
		ToolRegistry registry = new ToolRegistry();
		registry.register("decompile-class", "Decompile a Java class to source code", "Decompiler");

		List<ToolRegistry.ToolEntry> results = registry.search("decompile");

		assertEquals(1, results.size());
		assertEquals("decompile-class", results.get(0).name());
	}

	@Test
	void searchIsCaseInsensitive() {
		ToolRegistry registry = new ToolRegistry();
		registry.register("search-strings", "Search for string constants", "Search");

		List<ToolRegistry.ToolEntry> results = registry.search("STRING");

		assertEquals(1, results.size());
	}

	@Test
	void searchMatchesDescription() {
		ToolRegistry registry = new ToolRegistry();
		registry.register("workspace-list-classes", "List all classes in the workspace", "Workspace");

		List<ToolRegistry.ToolEntry> results = registry.search("list all classes");

		assertEquals(1, results.size());
	}

	@Test
	void nonMatchingQueryReturnsEmpty() {
		ToolRegistry registry = new ToolRegistry();
		registry.register("decompile-class", "Decompile a Java class", "Decompiler");

		List<ToolRegistry.ToolEntry> results = registry.search("network socket");

		assertTrue(results.isEmpty());
	}

	@Test
	void allToolsReturnedWhenQueryIsEmpty() {
		ToolRegistry registry = new ToolRegistry();
		registry.register("tool-a", "Description A", "Cat");
		registry.register("tool-b", "Description B", "Cat");

		List<ToolRegistry.ToolEntry> results = registry.search("");

		assertEquals(2, results.size());
	}

	@Test
	void multipleTermsAllMustMatch() {
		ToolRegistry registry = new ToolRegistry();
		registry.register("search-strings", "Search for string constants in classes", "Search");
		registry.register("search-numbers", "Search for numeric constants in classes", "Search");

		List<ToolRegistry.ToolEntry> results = registry.search("string");
		assertEquals(1, results.size());
		assertEquals("search-strings", results.get(0).name());
	}
}
