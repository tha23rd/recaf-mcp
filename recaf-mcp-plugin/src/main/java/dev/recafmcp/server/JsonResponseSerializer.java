package dev.recafmcp.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Serializes MCP tool responses as pretty-printed JSON using Jackson.
 */
public class JsonResponseSerializer implements ResponseSerializer {

	private final ObjectMapper objectMapper;

	public JsonResponseSerializer() {
		this.objectMapper = new ObjectMapper()
				.enable(SerializationFeature.INDENT_OUTPUT);
	}

	@Override
	public String serialize(Object data) {
		try {
			return objectMapper.writeValueAsString(data);
		} catch (JsonProcessingException e) {
			throw new RuntimeException("Failed to serialize response as JSON", e);
		}
	}

	@Override
	public String formatName() {
		return "json";
	}
}
