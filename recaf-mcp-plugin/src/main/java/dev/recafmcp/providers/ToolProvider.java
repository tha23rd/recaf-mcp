package dev.recafmcp.providers;

/**
 * Interface for MCP tool providers that register tools with the server.
 * Each implementation groups related tools (e.g., workspace tools, class tools).
 */
public interface ToolProvider {
	/**
	 * Register all tools provided by this provider with the MCP server.
	 */
	void registerTools();

	/**
	 * Clean up resources when the provider is no longer needed.
	 * Default implementation is a no-op.
	 */
	default void cleanup() {}
}
