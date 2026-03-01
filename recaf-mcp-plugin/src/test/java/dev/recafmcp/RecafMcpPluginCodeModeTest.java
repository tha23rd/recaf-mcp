package dev.recafmcp;

import dev.recafmcp.providers.ToolRegistry;
import dev.recafmcp.server.JsonResponseSerializer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.callgraph.CallGraphService;
import software.coley.recaf.services.comment.CommentManager;
import software.coley.recaf.services.compile.JavacCompiler;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.aggregate.AggregateMappingManager;
import software.coley.recaf.services.mapping.format.MappingFormatManager;
import software.coley.recaf.services.phantom.PhantomGenerator;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.transform.TransformationApplierService;
import software.coley.recaf.services.transform.TransformationManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.io.ResourceImporter;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RecafMcpPluginCodeModeTest {
	@Test
	void registerCodeModeProvidersOnlyRegistersThreeCodeModeTools() {
		RecafMcpPlugin plugin = new RecafMcpPlugin(
				mock(WorkspaceManager.class),
				mock(DecompilerManager.class),
				mock(SearchService.class),
				mock(CallGraphService.class),
				mock(InheritanceGraphService.class),
				mock(MappingApplierService.class),
				mock(AggregateMappingManager.class),
				mock(MappingFormatManager.class),
				mock(ResourceImporter.class),
				mock(JavacCompiler.class),
				mock(PhantomGenerator.class),
				mock(AssemblerPipelineManager.class),
				mock(CommentManager.class),
				mock(TransformationManager.class),
				mock(TransformationApplierService.class)
		);

		McpSyncServer mockServer = mock(McpSyncServer.class);
		ToolRegistry registry = new ToolRegistry();

		plugin.registerCodeModeProviders(mockServer, registry, new JsonResponseSerializer());

		ArgumentCaptor<SyncToolSpecification> captor = ArgumentCaptor.forClass(SyncToolSpecification.class);
		verify(mockServer, times(3)).addTool(captor.capture());

		Set<String> toolNames = captor.getAllValues().stream()
				.map(spec -> spec.tool().name())
				.collect(Collectors.toSet());

		assertEquals(Set.of("search-tools", "describe-recaf-api", "execute-recaf-script"), toolNames);
		assertEquals(3, registry.size());
	}
}
