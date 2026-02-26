package dev.recafmcp.providers;

import dev.recafmcp.cache.CacheConfig;
import dev.recafmcp.cache.DecompileCache;
import dev.recafmcp.cache.WorkspaceRevisionTracker;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.workspace.WorkspaceManager;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class DecompilerToolProviderCacheWiringTest {
	@Test
	void constructorAcceptsSharedCacheDependencies() {
		DecompilerToolProvider provider = new DecompilerToolProvider(
				mock(McpSyncServer.class),
				mock(WorkspaceManager.class),
				mock(DecompilerManager.class),
				new DecompileCache(new CacheConfig(true, 120, 1000)),
				new WorkspaceRevisionTracker()
		);

		assertNotNull(provider);
	}
}
