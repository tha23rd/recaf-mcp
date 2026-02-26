package dev.recafmcp.cache;

import dev.recafmcp.providers.AssemblerToolProvider;
import dev.recafmcp.providers.CompilerToolProvider;
import dev.recafmcp.providers.MappingToolProvider;
import dev.recafmcp.providers.TransformToolProvider;
import dev.recafmcp.providers.WorkspaceToolProvider;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.compile.JavacCompiler;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.aggregate.AggregateMappingManager;
import software.coley.recaf.services.mapping.format.MappingFormatManager;
import software.coley.recaf.services.phantom.PhantomGenerator;
import software.coley.recaf.services.transform.TransformationApplierService;
import software.coley.recaf.services.transform.TransformationManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.workspace.model.Workspace;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class WorkspaceRevisionMutationHooksTest {
	@Test
	void workspaceToolProviderMutationHookBumpsRevision() throws Exception {
		WorkspaceRevisionTracker tracker = new WorkspaceRevisionTracker();
		Workspace workspace = mock(Workspace.class);
		WorkspaceToolProvider provider = new WorkspaceToolProvider(
				mock(McpSyncServer.class),
				mock(WorkspaceManager.class),
				mock(ResourceImporter.class),
				mock(AggregateMappingManager.class),
				tracker
		);

		invokeMarkWorkspaceMutated(provider, workspace);
		assertEquals(1L, tracker.getRevision(workspace));
	}

	@Test
	void compilerToolProviderMutationHookBumpsRevision() throws Exception {
		WorkspaceRevisionTracker tracker = new WorkspaceRevisionTracker();
		Workspace workspace = mock(Workspace.class);
		CompilerToolProvider provider = new CompilerToolProvider(
				mock(McpSyncServer.class),
				mock(WorkspaceManager.class),
				mock(JavacCompiler.class),
				mock(PhantomGenerator.class),
				tracker
		);

		invokeMarkWorkspaceMutated(provider, workspace);
		assertEquals(1L, tracker.getRevision(workspace));
	}

	@Test
	void assemblerToolProviderMutationHookBumpsRevision() throws Exception {
		WorkspaceRevisionTracker tracker = new WorkspaceRevisionTracker();
		Workspace workspace = mock(Workspace.class);
		AssemblerToolProvider provider = new AssemblerToolProvider(
				mock(McpSyncServer.class),
				mock(WorkspaceManager.class),
				mock(AssemblerPipelineManager.class),
				tracker
		);

		invokeMarkWorkspaceMutated(provider, workspace);
		assertEquals(1L, tracker.getRevision(workspace));
	}

	@Test
	void mappingToolProviderMutationHookBumpsRevision() throws Exception {
		WorkspaceRevisionTracker tracker = new WorkspaceRevisionTracker();
		Workspace workspace = mock(Workspace.class);
		MappingToolProvider provider = new MappingToolProvider(
				mock(McpSyncServer.class),
				mock(WorkspaceManager.class),
				mock(MappingApplierService.class),
				mock(AggregateMappingManager.class),
				mock(MappingFormatManager.class),
				tracker
		);

		invokeMarkWorkspaceMutated(provider, workspace);
		assertEquals(1L, tracker.getRevision(workspace));
	}

	@Test
	void transformToolProviderMutationHookBumpsRevision() throws Exception {
		WorkspaceRevisionTracker tracker = new WorkspaceRevisionTracker();
		Workspace workspace = mock(Workspace.class);
		TransformToolProvider provider = new TransformToolProvider(
				mock(McpSyncServer.class),
				mock(WorkspaceManager.class),
				mock(TransformationManager.class),
				mock(TransformationApplierService.class),
				tracker
		);

		invokeMarkWorkspaceMutated(provider, workspace);
		assertEquals(1L, tracker.getRevision(workspace));
	}

	private static void invokeMarkWorkspaceMutated(Object provider, Workspace workspace) throws Exception {
		Method method = provider.getClass().getDeclaredMethod("markWorkspaceMutated", Workspace.class);
		method.setAccessible(true);
		method.invoke(provider, workspace);
	}
}
