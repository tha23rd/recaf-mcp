package dev.recafmcp.server;

/**
 * Pluggable serialization strategy for MCP tool responses.
 * <p>
 * Implementations convert arbitrary data objects into a string representation
 * suitable for returning to the MCP client. The default implementation uses JSON
 * (via Jackson), but alternative formats such as TOON can be swapped in to reduce
 * token consumption in LLM contexts.
 */
public interface ResponseSerializer {

	/**
	 * Serializes the given data object into a string.
	 *
	 * @param data The object to serialize.
	 * @return A string representation of the data.
	 */
	String serialize(Object data);

	/**
	 * @return The human-readable name of this serialization format (e.g. "json", "toon").
	 */
	String formatName();
}
