package dev.recafmcp.server;

import dev.toonformat.jtoon.JToon;

/**
 * Serializes MCP tool responses using the TOON format for token-efficient output.
 * <p>
 * TOON produces a more compact representation than JSON, reducing token usage
 * when responses are consumed by LLM agents.
 */
public class ToonResponseSerializer implements ResponseSerializer {

	@Override
	public String serialize(Object data) {
		return JToon.encode(data);
	}

	@Override
	public String formatName() {
		return "toon";
	}
}
