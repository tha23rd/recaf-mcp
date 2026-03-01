package dev.recafmcp.providers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * In-memory registry of MCP tools used by the search-tools discovery endpoint.
 */
public class ToolRegistry {
	public record ToolEntry(String name, String description, String category) {
		String searchableText() {
			return (name + " " + description).toLowerCase(Locale.ROOT);
		}
	}

	private final List<ToolEntry> entries = new ArrayList<>();

	public synchronized void register(String name, String description, String category) {
		entries.add(new ToolEntry(name, description, category));
	}

	public synchronized List<ToolEntry> search(String query) {
		if (query == null || query.isBlank()) {
			return Collections.unmodifiableList(new ArrayList<>(entries));
		}

		String[] terms = query.toLowerCase(Locale.ROOT).split("\\s+");
		List<ToolEntry> matches = new ArrayList<>();
		for (ToolEntry entry : entries) {
			String searchableText = entry.searchableText();
			boolean allMatch = true;
			for (String term : terms) {
				if (!searchableText.contains(term)) {
					allMatch = false;
					break;
				}
			}
			if (allMatch) {
				matches.add(entry);
			}
		}
		return matches;
	}

	public synchronized int size() {
		return entries.size();
	}
}
