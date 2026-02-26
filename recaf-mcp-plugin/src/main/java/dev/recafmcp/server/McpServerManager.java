package dev.recafmcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.recafmcp.BuildConfig;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.jackson.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;

/**
 * Manages the lifecycle of the embedded Jetty HTTP server and MCP sync server.
 */
public class McpServerManager {
	private static final Logger logger = Logging.get(McpServerManager.class);

	private static final String DEFAULT_HOST = "127.0.0.1";
	private static final int DEFAULT_PORT = 8085;
	private static final String MCP_ENDPOINT = "/mcp";
	private static final long STARTUP_TIMEOUT_MS = 5000;

	/**
	 * Resolves the MCP server host from env var or system property.
	 * Priority: RECAF_MCP_HOST env > recaf.mcp.host sysprop > default
	 */
	public static String resolveHost() {
		String env = System.getenv("RECAF_MCP_HOST");
		if (env != null && !env.isBlank()) return env;
		return System.getProperty("recaf.mcp.host", DEFAULT_HOST);
	}

	/**
	 * Resolves the MCP server port from env var or system property.
	 * Priority: RECAF_MCP_PORT env > recaf.mcp.port sysprop > default
	 */
	public static int resolvePort() {
		String env = System.getenv("RECAF_MCP_PORT");
		if (env != null && !env.isBlank()) {
			try { return Integer.parseInt(env); }
			catch (NumberFormatException e) {
				logger.warn("Invalid RECAF_MCP_PORT '{}', using default {}", env, DEFAULT_PORT);
			}
		}
		String prop = System.getProperty("recaf.mcp.port");
		if (prop != null && !prop.isBlank()) {
			try { return Integer.parseInt(prop); }
			catch (NumberFormatException e) {
				logger.warn("Invalid recaf.mcp.port '{}', using default {}", prop, DEFAULT_PORT);
			}
		}
		return DEFAULT_PORT;
	}

	private Server jettyServer;
	private McpSyncServer mcpServer;
	private Thread serverThread;
	private volatile boolean running;

	/**
	 * Starts the MCP server on the default host and port.
	 *
	 * @return The running MCP sync server instance.
	 */
	public McpSyncServer start() {
		return start(DEFAULT_HOST, DEFAULT_PORT);
	}

	/**
	 * Starts the MCP server on the specified host and port.
	 *
	 * @param host The host address to bind to.
	 * @param port The port to listen on.
	 * @return The running MCP sync server instance.
	 */
	public McpSyncServer start(String host, int port) {
		if (running) {
			logger.warn("MCP server is already running");
			return mcpServer;
		}

		// Explicitly construct the Jackson mapper and schema validator to avoid
		// ServiceLoader failures in Recaf's plugin classloader environment.
		McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());

		// Build the streamable HTTP transport provider.
		HttpServletStreamableServerTransportProvider transportProvider =
				HttpServletStreamableServerTransportProvider.builder()
						.jsonMapper(jsonMapper)
						.build();

		// Build the MCP sync server with capabilities
		mcpServer = McpServer.sync(transportProvider)
				.serverInfo("recaf-mcp", BuildConfig.PLUGIN_VERSION)
				.jsonMapper(jsonMapper)
				.jsonSchemaValidator(new DefaultJsonSchemaValidator())
				.capabilities(ServerCapabilities.builder()
						.tools(true)
						.resources(false, true)
						.build())
				.build();

		// Set up Jetty with ee11 servlet support
		jettyServer = new Server();
		ServerConnector connector = new ServerConnector(jettyServer);
		connector.setHost(host);
		connector.setPort(port);
		jettyServer.addConnector(connector);

		ServletContextHandler contextHandler = new ServletContextHandler();
		contextHandler.setContextPath("/");
		contextHandler.addServlet(new ServletHolder(transportProvider), "/*");
		jettyServer.setHandler(contextHandler);

		// Start Jetty on a daemon thread so it doesn't block Recaf shutdown
		serverThread = new Thread(() -> {
			try {
				jettyServer.start();
				jettyServer.join();
			} catch (Exception e) {
				logger.error("Jetty server error", e);
			}
		}, "recaf-mcp-jetty");
		serverThread.setDaemon(true);
		serverThread.start();

		// Wait for Jetty to become available
		if (!waitForStartup()) {
			logger.error("MCP server failed to start within {}ms", STARTUP_TIMEOUT_MS);
			stop();
			throw new RuntimeException("MCP server failed to start within " + STARTUP_TIMEOUT_MS + "ms");
		}

		running = true;
		logger.info("MCP server started on {}:{}{}", host, port, MCP_ENDPOINT);
		return mcpServer;
	}

	/**
	 * Stops both the MCP server and the Jetty HTTP server.
	 */
	public void stop() {
		running = false;

		if (mcpServer != null) {
			try {
				mcpServer.close();
			} catch (Exception e) {
				logger.error("Error closing MCP server", e);
			}
			mcpServer = null;
		}

		if (jettyServer != null) {
			try {
				jettyServer.stop();
			} catch (Exception e) {
				logger.error("Error stopping Jetty server", e);
			}
			jettyServer = null;
		}

		if (serverThread != null) {
			serverThread.interrupt();
			serverThread = null;
		}

		logger.info("MCP server stopped");
	}

	/**
	 * @return {@code true} if the MCP server is currently running.
	 */
	public boolean isRunning() {
		return running;
	}

	/**
	 * @return The MCP sync server instance, or {@code null} if not running.
	 */
	public McpSyncServer getMcpServer() {
		return mcpServer;
	}

	/**
	 * Blocks until Jetty reports it is started, or the timeout expires.
	 *
	 * @return {@code true} if the server started successfully.
	 */
	private boolean waitForStartup() {
		long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
		while (System.currentTimeMillis() < deadline) {
			if (jettyServer.isStarted()) {
				return true;
			}
			if (jettyServer.isFailed()) {
				return false;
			}
			try {
				//noinspection BusyWait
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}
}
