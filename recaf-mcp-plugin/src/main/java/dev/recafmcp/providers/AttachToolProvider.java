package dev.recafmcp.providers;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.workspace.WorkspaceManager;

import java.util.List;
import java.util.Map;

/**
 * MCP tool provider for JVM attach operations.
 * <p>
 * Provides tools for listing running JVMs, attaching to them, loading classes
 * from attached JVMs, and disconnecting. All tools are currently stubbed pending
 * full integration with Recaf's {@code AttachManager}.
 */
public class AttachToolProvider extends AbstractToolProvider {
	private static final Logger logger = Logging.get(AttachToolProvider.class);

	public AttachToolProvider(McpSyncServer server, WorkspaceManager workspaceManager) {
		super(server, workspaceManager);
	}

	@Override
	public void registerTools() {
		registerAttachListVms();
		registerAttachConnect();
		registerAttachLoadClasses();
		registerAttachDisconnect();
	}

	// ---- attach-list-vms ----

	private void registerAttachListVms() {
		Tool tool = Tool.builder()
				.name("attach-list-vms")
				.description("List all running JVMs on the local system with PID, display name, and main class")
				.inputSchema(createSchema(Map.of(), List.of()))
				.build();
		registerTool(tool, (exchange, args) -> {
			return createTextResult("Scans for running JVMs on the local system, returning PID, display name, " +
					"and main class for each. Uses Recaf's AttachManager. " +
					"This tool is not yet implemented — AttachManager integration is pending.");
		});
	}

	// ---- attach-connect ----

	private void registerAttachConnect() {
		Tool tool = Tool.builder()
				.name("attach-connect")
				.description("Attach to a running JVM by PID or display name. At least one of 'pid' or 'displayName' must be provided.")
				.inputSchema(createSchema(
						Map.of(
								"pid", intParam("Process ID of the target JVM"),
								"displayName", stringParam("Display name or main class of the target JVM")
						),
						List.of()
				))
				.build();
		registerTool(tool, (exchange, args) -> {
			int pid = getInt(args, "pid", -1);
			String displayName = getOptionalString(args, "displayName", null);

			if (pid < 0 && displayName == null) {
				return createErrorResult("At least one of 'pid' or 'displayName' must be provided.");
			}

			return createTextResult("Attaches to a running JVM by PID or display name. " +
					"This tool is not yet implemented — AttachManager integration is pending.");
		});
	}

	// ---- attach-load-classes ----

	private void registerAttachLoadClasses() {
		Tool tool = Tool.builder()
				.name("attach-load-classes")
				.description("Load classes from the attached JVM into the current workspace for analysis")
				.inputSchema(createSchema(Map.of(), List.of()))
				.build();
		registerTool(tool, (exchange, args) -> {
			return createTextResult("Loads classes from the attached JVM into the current workspace for analysis. " +
					"This tool is not yet implemented — AttachManager integration is pending.");
		});
	}

	// ---- attach-disconnect ----

	private void registerAttachDisconnect() {
		Tool tool = Tool.builder()
				.name("attach-disconnect")
				.description("Disconnect from the currently attached JVM")
				.inputSchema(createSchema(Map.of(), List.of()))
				.build();
		registerTool(tool, (exchange, args) -> {
			return createTextResult("Disconnects from the currently attached JVM. " +
					"This tool is not yet implemented — AttachManager integration is pending.");
		});
	}
}
