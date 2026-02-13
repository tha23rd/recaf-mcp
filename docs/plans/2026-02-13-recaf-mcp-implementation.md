# Recaf MCP Server — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build an MCP server embedded in a Recaf 4.x plugin that exposes 62+ tools for autonomous Java reverse engineering, plus a Python stdio bridge for Claude Code and structured Claude Code skills.

**Architecture:** A Recaf plugin embeds a Jetty HTTP server running the MCP Java SDK's Streamable HTTP transport. Tool providers organized by domain (workspace, decompilation, search, etc.) inject Recaf's CDI services and expose them as MCP tools. A Python stdio bridge proxies for Claude Code.

**Tech Stack:** Java 22+, Gradle, MCP Java SDK 0.17.2, Jetty 12.1 (ee11), Jackson, JToon, Recaf 4.x CDI, Python (mcp + httpx)

**Design Doc:** `docs/plans/2026-02-13-recaf-mcp-design.md`

---

## Phase 1: Project Scaffolding

### Task 1: Initialize Gradle project structure

**Files:**
- Create: `recaf-mcp-plugin/settings.gradle`
- Create: `recaf-mcp-plugin/build.gradle`
- Create: `recaf-mcp-plugin/gradle.properties`

**Step 1: Create settings.gradle**

```groovy
rootProject.name = 'recaf-mcp-plugin'
```

**Step 2: Create build.gradle**

```groovy
plugins {
    id 'java'
    id 'org.openjfx.javafxplugin' version '0.1.0'
    id 'top.hendrixshen.replace-token' version '1.1.2'
}

group = 'dev.recafmcp'
version = '0.1.0'

ext {
    recafVersion = 'd07958a5c7'
    recafSnapshots = true
    pluginMainClass = 'dev.recafmcp.RecafMcpPlugin'
    pluginName = 'Recaf MCP Server'
    pluginDesc = 'MCP server for autonomous Java reverse engineering via LLM agents'
    pluginId = group + '.' + project.name
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(22)
    }
}

repositories {
    mavenLocal()
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

configurations.configureEach {
    exclude group: 'org.checkerframework'
    exclude group: 'com.google.code.findbugs'
    exclude group: 'com.google.errorprone'
    exclude group: 'com.google.j2objc'
    exclude group: 'org.jetbrains', module: 'annotations'
    exclude group: 'com.android.tools'
    exclude group: 'com.ibm.icu'
}

dependencies {
    // Recaf
    if (recafSnapshots) {
        implementation "com.github.Col-E.Recaf:recaf-core:${recafVersion}"
        implementation "com.github.Col-E.Recaf:recaf-ui:${recafVersion}"
    } else {
        implementation "software.coley:recaf-core:${recafVersion}"
        implementation "software.coley:recaf-ui:${recafVersion}"
    }

    // MCP SDK (core + Jackson 2 for Recaf classpath compat)
    implementation platform('io.modelcontextprotocol.sdk:mcp-bom:0.17.2')
    implementation 'io.modelcontextprotocol.sdk:mcp'

    // Embedded HTTP server - Jetty 12.1 with ee11 (Jakarta Servlet 6.1)
    implementation 'org.eclipse.jetty:jetty-server:12.1.6'
    implementation 'org.eclipse.jetty.ee11:jetty-ee11-servlet:12.1.6'

    // TOON serialization (optional, for token-efficient responses)
    implementation 'dev.toonformat:jtoon:1.0.8'

    // Test
    testImplementation platform('org.junit:junit-bom:5.11.2')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testImplementation 'org.mockito:mockito-core:5.14.2'
}

test {
    useJUnitPlatform()
}

javafx {
    version = '22.0.1'
    modules = ['javafx.base', 'javafx.graphics', 'javafx.controls', 'javafx.media']
}

replaceToken {
    targetSourceSets.set([sourceSets.main])
    replace("##ID##", project.ext.pluginId)
    replace("##NAME##", project.ext.pluginName)
    replace("##DESC##", project.ext.pluginDesc)
    replace("##VERSION##", project.version)
}

// Generate ServiceLoader entry in output JAR
tasks.register('setupServiceEntry') {
    outputs.dir(temporaryDir)
    doFirst {
        new File(temporaryDir, "META-INF/services").mkdirs()
        new File(temporaryDir, "META-INF/services/software.coley.recaf.plugin.Plugin").text = project.ext.pluginMainClass
    }
}
jar.from(setupServiceEntry)

// Run Recaf with plugin loaded
tasks.register('runRecaf', JavaExec) {
    dependsOn 'build'
    classpath sourceSets.main.runtimeClasspath
    mainClass = "software.coley.recaf.Main"
    args("-r", "build/libs")
}
```

**Step 3: Create gradle.properties**

```properties
org.gradle.jvmargs=-Xmx2g
```

**Step 4: Initialize Gradle wrapper**

Run: `cd recaf-mcp-plugin && gradle wrapper --gradle-version 8.12`
Expected: `gradle/wrapper/` directory created, `gradlew` script created

**Step 5: Verify build resolves dependencies**

Run: `cd recaf-mcp-plugin && ./gradlew dependencies --configuration compileClasspath 2>&1 | head -50`
Expected: Dependencies resolve successfully (Recaf from JitPack, MCP SDK from Maven Central)

**Step 6: Commit**

```bash
git add recaf-mcp-plugin/
git commit -m "feat: initialize Gradle project with Recaf, MCP SDK, and Jetty deps"
```

---

### Task 2: Create plugin skeleton

**Files:**
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/RecafMcpPlugin.java`

**Step 1: Write RecafMcpPlugin**

```java
package dev.recafmcp;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.plugin.Plugin;
import software.coley.recaf.plugin.PluginInformation;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.workspace.WorkspaceManager;

@Dependent
@PluginInformation(id = "##ID##", version = "##VERSION##", name = "##NAME##", description = "##DESC##")
public class RecafMcpPlugin implements Plugin {
    private static final Logger logger = Logging.get(RecafMcpPlugin.class);

    private final WorkspaceManager workspaceManager;
    private final DecompilerManager decompilerManager;

    @Inject
    public RecafMcpPlugin(WorkspaceManager workspaceManager,
                          DecompilerManager decompilerManager) {
        this.workspaceManager = workspaceManager;
        this.decompilerManager = decompilerManager;
    }

    @Override
    public void onEnable() {
        logger.info("Recaf MCP Server plugin enabled");
        // MCP server will be started here in Task 4
    }

    @Override
    public void onDisable() {
        logger.info("Recaf MCP Server plugin disabled");
        // MCP server will be stopped here in Task 4
    }
}
```

**Step 2: Verify compilation**

Run: `cd recaf-mcp-plugin && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add recaf-mcp-plugin/src/
git commit -m "feat: add RecafMcpPlugin skeleton with CDI injection"
```

---

## Phase 2: Server Infrastructure

### Task 3: Create McpServerManager (Jetty + MCP SDK lifecycle)

**Files:**
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/server/McpServerManager.java`

**Step 1: Write McpServerManager**

This class manages the Jetty HTTP server and MCP sync server lifecycle. Reference: ReVa's `McpServerManager.java`.

```java
package dev.recafmcp.server;

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

import java.time.Duration;

public class McpServerManager {
    private static final Logger logger = Logging.get(McpServerManager.class);
    private static final String MCP_ENDPOINT = "/mcp/message";
    private static final String SERVER_NAME = "recaf-mcp";
    private static final String SERVER_VERSION = "0.1.0";
    private static final int DEFAULT_PORT = 8085;
    private static final String DEFAULT_HOST = "127.0.0.1";

    private Server jettyServer;
    private McpSyncServer mcpServer;
    private HttpServletStreamableServerTransportProvider transportProvider;

    public McpSyncServer start() throws Exception {
        return start(DEFAULT_HOST, DEFAULT_PORT);
    }

    public McpSyncServer start(String host, int port) throws Exception {
        // 1. Create transport provider
        transportProvider = HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint(MCP_ENDPOINT)
                .keepAliveInterval(Duration.ofSeconds(30))
                .build();

        // 2. Build MCP server
        mcpServer = McpServer.sync(transportProvider)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .capabilities(ServerCapabilities.builder()
                        .tools(true)
                        .resources(true, true)
                        .prompts(false)
                        .logging()
                        .build())
                .build();

        // 3. Set up Jetty with ee11 servlet support
        jettyServer = new Server();
        ServerConnector connector = new ServerConnector(jettyServer);
        connector.setHost(host);
        connector.setPort(port);
        jettyServer.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");

        ServletHolder servletHolder = new ServletHolder("mcp", transportProvider);
        servletHolder.setAsyncSupported(true);
        contextHandler.addServlet(servletHolder, "/*");

        jettyServer.setHandler(contextHandler);

        // 4. Start on a background thread
        Thread serverThread = new Thread(() -> {
            try {
                jettyServer.start();
                logger.info("MCP server started on {}:{}{}", host, port, MCP_ENDPOINT);
                jettyServer.join();
            } catch (Exception e) {
                logger.error("MCP server failed to start", e);
            }
        }, "recaf-mcp-server");
        serverThread.setDaemon(true);
        serverThread.start();

        // Wait briefly for server to be ready
        long deadline = System.currentTimeMillis() + 5000;
        while (!jettyServer.isStarted() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        if (!jettyServer.isStarted()) {
            throw new RuntimeException("MCP server failed to start within 5 seconds");
        }

        return mcpServer;
    }

    public void stop() {
        try {
            if (mcpServer != null) {
                mcpServer.close();
                mcpServer = null;
            }
            if (jettyServer != null) {
                jettyServer.stop();
                jettyServer = null;
            }
            logger.info("MCP server stopped");
        } catch (Exception e) {
            logger.error("Error stopping MCP server", e);
        }
    }

    public McpSyncServer getMcpServer() {
        return mcpServer;
    }

    public boolean isRunning() {
        return jettyServer != null && jettyServer.isStarted();
    }
}
```

**Step 2: Wire into RecafMcpPlugin**

Update `RecafMcpPlugin.java`:

```java
// Add field
private McpServerManager serverManager;

// Update onEnable:
@Override
public void onEnable() {
    logger.info("Recaf MCP Server plugin starting...");
    serverManager = new McpServerManager();
    try {
        McpSyncServer mcpServer = serverManager.start();
        // Tool providers will be registered here in later tasks
        logger.info("Recaf MCP Server plugin ready");
    } catch (Exception e) {
        logger.error("Failed to start MCP server", e);
    }
}

// Update onDisable:
@Override
public void onDisable() {
    if (serverManager != null) {
        serverManager.stop();
    }
    logger.info("Recaf MCP Server plugin disabled");
}
```

**Step 3: Verify compilation**

Run: `cd recaf-mcp-plugin && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add recaf-mcp-plugin/src/
git commit -m "feat: add McpServerManager with Jetty + Streamable HTTP transport"
```

---

### Task 4: Create AbstractToolProvider base class

**Files:**
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/providers/ToolProvider.java`
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/providers/AbstractToolProvider.java`

**Step 1: Write the ToolProvider interface**

```java
package dev.recafmcp.providers;

/**
 * Interface for MCP tool providers. Each provider registers tools for a specific domain.
 */
public interface ToolProvider {
    /** Register all tools this provider exposes. */
    void registerTools();

    /** Clean up resources when the server is shutting down. */
    default void cleanup() {}
}
```

**Step 2: Write AbstractToolProvider**

This provides the common boilerplate for all tool providers: schema creation, parameter extraction, error handling, JSON response formatting. Reference: ReVa's `AbstractToolProvider.java`.

```java
package dev.recafmcp.providers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.util.*;
import java.util.function.BiFunction;

public abstract class AbstractToolProvider implements ToolProvider {
    private static final Logger logger = Logging.get(AbstractToolProvider.class);
    protected static final ObjectMapper JSON = new ObjectMapper();

    protected final McpSyncServer server;
    protected final WorkspaceManager workspaceManager;

    protected AbstractToolProvider(McpSyncServer server, WorkspaceManager workspaceManager) {
        this.server = server;
        this.workspaceManager = workspaceManager;
    }

    // --- Tool Registration ---

    protected void registerTool(Tool tool,
                                BiFunction<McpSyncServerExchange, Map<String, Object>, CallToolResult> handler) {
        SyncToolSpecification spec = SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> args = request.params().arguments();
                        if (args == null) args = Map.of();
                        return handler.apply(exchange, args);
                    } catch (Exception e) {
                        logger.error("Tool '{}' failed: {}", tool.name(), e.getMessage(), e);
                        return createErrorResult(e.getMessage());
                    }
                })
                .build();
        server.addTool(spec);
    }

    // --- Schema Helpers ---

    protected McpSchema.JsonSchema createSchema(Map<String, Object> properties, List<String> required) {
        return new McpSchema.JsonSchema("object", properties, required, false);
    }

    protected Map<String, Object> stringParam(String description) {
        return Map.of("type", "string", "description", description);
    }

    protected Map<String, Object> intParam(String description) {
        return Map.of("type", "integer", "description", description);
    }

    protected Map<String, Object> boolParam(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    protected Map<String, Object> stringParam(String description, String defaultValue) {
        return Map.of("type", "string", "description", description, "default", defaultValue);
    }

    protected Map<String, Object> intParam(String description, int defaultValue) {
        return Map.of("type", "integer", "description", description, "default", defaultValue);
    }

    // --- Parameter Extraction ---

    protected String getString(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) throw new IllegalArgumentException("Missing required parameter: " + key);
        return val.toString();
    }

    protected String getOptionalString(Map<String, Object> args, String key, String defaultValue) {
        Object val = args.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    protected int getInt(Map<String, Object> args, String key, int defaultValue) {
        Object val = args.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Number n) return n.intValue();
        return Integer.parseInt(val.toString());
    }

    protected boolean getBoolean(Map<String, Object> args, String key, boolean defaultValue) {
        Object val = args.get(key);
        if (val == null) return defaultValue;
        if (val instanceof Boolean b) return b;
        return Boolean.parseBoolean(val.toString());
    }

    // --- Workspace Access ---

    protected Workspace requireWorkspace() {
        Workspace ws = workspaceManager.getCurrent();
        if (ws == null) {
            throw new IllegalStateException("No workspace is open. Use 'workspace-open' to load a JAR/APK first.");
        }
        return ws;
    }

    // --- Result Builders ---

    protected CallToolResult createTextResult(String text) {
        return new CallToolResult(List.of(new TextContent(text)), false);
    }

    protected CallToolResult createJsonResult(Object data) {
        try {
            String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            return new CallToolResult(List.of(new TextContent(json)), false);
        } catch (JsonProcessingException e) {
            return createErrorResult("Failed to serialize response: " + e.getMessage());
        }
    }

    protected CallToolResult createErrorResult(String message) {
        return new CallToolResult(List.of(new TextContent("Error: " + message)), true);
    }
}
```

**Step 3: Verify compilation**

Run: `cd recaf-mcp-plugin && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add recaf-mcp-plugin/src/
git commit -m "feat: add AbstractToolProvider with schema, param, and result helpers"
```

---

### Task 5: Create utility classes (AddressResolver, PaginationUtil, ErrorHelper)

**Files:**
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/util/ClassResolver.java`
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/util/PaginationUtil.java`
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/util/ErrorHelper.java`
- Create: `recaf-mcp-plugin/src/test/java/dev/recafmcp/util/ClassResolverTest.java`
- Create: `recaf-mcp-plugin/src/test/java/dev/recafmcp/util/PaginationUtilTest.java`

**Step 1: Write ClassResolver**

Handles flexible class/method/field name resolution — accepts `com.example.Foo`, `com/example/Foo`, partial names, method references like `Foo#bar` or `Foo.bar(I)V`.

```java
package dev.recafmcp.util;

import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves flexible class/method/field name inputs to Recaf internal names.
 */
public class ClassResolver {

    /**
     * Normalize a class name to internal form (slashes).
     * Accepts: "com.example.Foo", "com/example/Foo", "Foo" (partial)
     */
    public static String normalizeClassName(String input) {
        if (input == null) return null;
        return input.replace('.', '/').trim();
    }

    /**
     * Find a class in the workspace by flexible name.
     * Tries exact match first, then simple-name match if unambiguous.
     */
    public static ClassPathNode resolveClass(Workspace workspace, String name) {
        String normalized = normalizeClassName(name);
        if (normalized == null) return null;

        // Try exact match first
        ClassPathNode exact = workspace.findClass(normalized);
        if (exact != null) return exact;

        // Try partial match (simple name)
        String simpleName = normalized.contains("/")
                ? normalized.substring(normalized.lastIndexOf('/') + 1)
                : normalized;

        List<ClassPathNode> matches = new ArrayList<>();
        workspace.findClasses(info -> {
            String infoSimple = info.getName();
            if (infoSimple.contains("/")) {
                infoSimple = infoSimple.substring(infoSimple.lastIndexOf('/') + 1);
            }
            return infoSimple.equalsIgnoreCase(simpleName);
        }).forEach(matches::add);

        if (matches.size() == 1) return matches.getFirst();
        return null; // Ambiguous or not found
    }

    /**
     * Find classes similar to the given name (for error suggestions).
     */
    public static List<String> findSimilarClassNames(Workspace workspace, String name, int maxResults) {
        String normalized = normalizeClassName(name);
        String lower = normalized != null ? normalized.toLowerCase() : "";
        List<String> results = new ArrayList<>();

        workspace.findClasses(info -> {
            String infoName = info.getName().toLowerCase();
            return infoName.contains(lower) || levenshteinDistance(infoName, lower) <= 3;
        }).forEach(node -> {
            if (results.size() < maxResults) {
                results.add(node.getValue().getName());
            }
        });

        return results;
    }

    private static int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }
}
```

**Step 2: Write PaginationUtil**

```java
package dev.recafmcp.util;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Pagination helpers for list tools.
 */
public class PaginationUtil {
    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;

    public static <T> List<T> paginate(List<T> items, int offset, int limit) {
        if (offset < 0) offset = 0;
        if (limit <= 0) limit = DEFAULT_LIMIT;
        if (limit > MAX_LIMIT) limit = MAX_LIMIT;
        int end = Math.min(offset + limit, items.size());
        if (offset >= items.size()) return List.of();
        return items.subList(offset, end);
    }

    public static <T> List<T> paginate(Stream<T> stream, int offset, int limit) {
        if (offset < 0) offset = 0;
        if (limit <= 0) limit = DEFAULT_LIMIT;
        if (limit > MAX_LIMIT) limit = MAX_LIMIT;
        return stream.skip(offset).limit(limit).toList();
    }

    public static Map<String, Object> paginatedResult(List<?> items, int offset, int limit, int totalCount) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("offset", offset);
        result.put("limit", limit);
        result.put("count", items.size());
        result.put("totalCount", totalCount);
        result.put("hasMore", offset + items.size() < totalCount);
        return result;
    }
}
```

**Step 3: Write ErrorHelper**

```java
package dev.recafmcp.util;

import software.coley.recaf.workspace.model.Workspace;

import java.util.List;

/**
 * Generates helpful error messages with suggestions for the LLM.
 */
public class ErrorHelper {

    public static String classNotFound(String name, Workspace workspace) {
        List<String> suggestions = ClassResolver.findSimilarClassNames(workspace, name, 5);
        StringBuilder sb = new StringBuilder();
        sb.append("Class '").append(name).append("' not found.");
        if (!suggestions.isEmpty()) {
            sb.append(" Did you mean: ").append(String.join(", ", suggestions)).append("?");
        }
        sb.append(" Use 'class-search-by-name' to search for classes.");
        return sb.toString();
    }

    public static String noWorkspace() {
        return "No workspace is open. Use 'workspace-open' to load a JAR, APK, or class file first.";
    }
}
```

**Step 4: Write tests for ClassResolver**

```java
package dev.recafmcp.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClassResolverTest {
    @Test
    void normalizeClassName_dots_to_slashes() {
        assertEquals("com/example/Foo", ClassResolver.normalizeClassName("com.example.Foo"));
    }

    @Test
    void normalizeClassName_slashes_preserved() {
        assertEquals("com/example/Foo", ClassResolver.normalizeClassName("com/example/Foo"));
    }

    @Test
    void normalizeClassName_trims_whitespace() {
        assertEquals("com/example/Foo", ClassResolver.normalizeClassName("  com/example/Foo  "));
    }

    @Test
    void normalizeClassName_null_returns_null() {
        assertNull(ClassResolver.normalizeClassName(null));
    }
}
```

**Step 5: Write tests for PaginationUtil**

```java
package dev.recafmcp.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PaginationUtilTest {
    @Test
    void paginate_basic() {
        List<String> items = List.of("a", "b", "c", "d", "e");
        assertEquals(List.of("a", "b"), PaginationUtil.paginate(items, 0, 2));
    }

    @Test
    void paginate_with_offset() {
        List<String> items = List.of("a", "b", "c", "d", "e");
        assertEquals(List.of("c", "d"), PaginationUtil.paginate(items, 2, 2));
    }

    @Test
    void paginate_beyond_end() {
        List<String> items = List.of("a", "b");
        assertEquals(List.of(), PaginationUtil.paginate(items, 5, 2));
    }

    @Test
    void paginatedResult_structure() {
        Map<String, Object> result = PaginationUtil.paginatedResult(List.of("a", "b"), 0, 2, 5);
        assertEquals(2, result.get("count"));
        assertEquals(5, result.get("totalCount"));
        assertEquals(true, result.get("hasMore"));
    }
}
```

**Step 6: Run tests**

Run: `cd recaf-mcp-plugin && ./gradlew test`
Expected: All tests pass

**Step 7: Commit**

```bash
git add recaf-mcp-plugin/src/
git commit -m "feat: add ClassResolver, PaginationUtil, and ErrorHelper utilities"
```

---

### Task 6: Create ResponseSerializer (JSON/TOON pluggable)

**Files:**
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/server/ResponseSerializer.java`
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/server/JsonResponseSerializer.java`
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/server/ToonResponseSerializer.java`

**Step 1: Write the interface and implementations**

```java
// ResponseSerializer.java
package dev.recafmcp.server;

public interface ResponseSerializer {
    String serialize(Object data);
    String formatName();
}
```

```java
// JsonResponseSerializer.java
package dev.recafmcp.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonResponseSerializer implements ResponseSerializer {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String serialize(Object data) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }

    @Override
    public String formatName() {
        return "json";
    }
}
```

```java
// ToonResponseSerializer.java
package dev.recafmcp.server;

import dev.toonformat.jtoon.JToon;

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
```

**Step 2: Update AbstractToolProvider to use ResponseSerializer**

Add a `ResponseSerializer` field and update `createJsonResult` to use it. Default to `JsonResponseSerializer`.

**Step 3: Verify compilation**

Run: `cd recaf-mcp-plugin && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add recaf-mcp-plugin/src/
git commit -m "feat: add pluggable JSON/TOON response serialization"
```

---

## Phase 3: Core Tool Providers

Each tool provider follows the same pattern established in Task 4. From here on, I'll describe **what tools to register** and **key implementation details** rather than repeating the full boilerplate.

**Pattern for each tool provider:**
1. Create `dev.recafmcp.providers.XxxToolProvider extends AbstractToolProvider`
2. Constructor takes `McpSyncServer`, `WorkspaceManager`, and any domain-specific services
3. `registerTools()` method calls `registerTool()` for each tool
4. Wire into `RecafMcpPlugin.onEnable()` after server starts
5. Compile, test, commit

### Task 7: WorkspaceToolProvider

**Files:**
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/providers/WorkspaceToolProvider.java`
- Modify: `recaf-mcp-plugin/src/main/java/dev/recafmcp/RecafMcpPlugin.java`

**Services injected:** `WorkspaceManager`, `ResourceImporter`

**Tools to implement:**

1. **`workspace-open`** — Takes `path` (string, file path to JAR/APK/class). Uses `ResourceImporter.importResource(Path)` to import, then `WorkspaceManager.setCurrent(workspace)`. Returns workspace info.

2. **`workspace-close`** — Calls `WorkspaceManager.closeCurrent()`. Returns confirmation.

3. **`workspace-get-info`** — Gets current workspace, counts JVM classes, Android classes, files, embedded resources. Returns structured summary.

4. **`workspace-export`** — Takes `outputPath`. Uses `WorkspaceExportOptions` with `PathWorkspaceExportConsumer` to export. Returns confirmation.

5. **`workspace-list-resources`** — Lists primary and supporting resources with their bundle contents.

6. **`workspace-add-supporting`** — Takes `path`. Imports as supporting resource.

7. **`workspace-get-history`** — Reports undo history length for bundles.

**Key implementation detail:** `workspace-open` needs to handle both local file paths and URLs. The `ResourceImporter` supports `Path`, `URL`, and `URI` inputs.

**Wire into plugin:** Update `RecafMcpPlugin.onEnable()`:

```java
McpSyncServer mcpServer = serverManager.start();

// Register tool providers
new WorkspaceToolProvider(mcpServer, workspaceManager, resourceImporter).registerTools();
```

**Step: Verify compilation, commit**

```bash
git commit -m "feat: add WorkspaceToolProvider (7 tools)"
```

---

### Task 8: NavigationToolProvider

**Files:**
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/providers/NavigationToolProvider.java`

**Services injected:** `WorkspaceManager`

**Tools to implement:**

1. **`class-list`** — Paginated class listing. Params: `packageFilter` (optional), `offset`, `limit`. Streams `workspace.jvmClassesStream()`, filters by package, returns paginated result with class name, access flags, super class.

2. **`class-count`** — Count classes. Params: `packageFilter` (optional). Returns count.

3. **`class-get-info`** — Detailed class info. Params: `className`. Returns: name, access flags, superName, interfaces, fields (name, type, flags), methods (name, descriptor, flags), source file.

4. **`class-get-hierarchy`** — Uses `InheritanceGraphService` to walk the hierarchy. Params: `className`. Returns: ancestors list and direct implementors/subtypes.

5. **`method-list`** — List methods for a class. Params: `className`, `offset`, `limit`.

6. **`field-list`** — List fields for a class. Params: `className`, `offset`, `limit`.

7. **`package-list`** — Extract unique packages from all class names.

8. **`class-search-by-name`** — Regex search over class names. Params: `pattern`, `offset`, `limit`.

**Commit:**

```bash
git commit -m "feat: add NavigationToolProvider (8 tools)"
```

---

### Task 9: DecompilerToolProvider

**Files:**
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/providers/DecompilerToolProvider.java`

**Services injected:** `WorkspaceManager`, `DecompilerManager`

**Tools to implement:**

1. **`decompile-class`** — Params: `className`, `includeXrefs` (bool, default false). Calls `decompilerManager.decompile(workspace, classInfo).get()`. Returns decompiled source, optionally with incoming xref summary.

2. **`decompile-method`** — Params: `className`, `methodName`, `methodDescriptor` (optional for disambiguation). Decompiles full class, extracts the relevant method's source text. If descriptor is omitted and name is ambiguous, lists matches.

3. **`decompiler-list`** — Lists available decompiler backends from `decompilerManager`. Returns name and whether it's the active one.

4. **`decompiler-set`** — Params: `decompilerName`. Sets the target decompiler via `decompilerManager.setTargetJvmDecompiler(decompiler)`.

5. **`decompile-diff`** — Params: `className`, `before` (source text), `after` (optional — if omitted, decompiles fresh and diffs against `before`). Returns unified diff.

**Key note:** `decompilerManager.decompile()` returns `CompletableFuture<DecompileResult>`. Call `.get()` since we're in the sync API.

**Commit:**

```bash
git commit -m "feat: add DecompilerToolProvider (5 tools)"
```

---

### Task 10: SearchToolProvider

**Files:**
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/providers/SearchToolProvider.java`

**Services injected:** `WorkspaceManager`, `SearchService`

**Tools to implement:**

1. **`search-strings`** — Params: `query` (string pattern), `offset`, `limit`. Uses `StringQuery`.
2. **`search-strings-count`** — Same query, returns count only.
3. **`search-numbers`** — Params: `value` (number). Uses `NumberQuery`.
4. **`search-references`** — Params: `className` and/or `memberName`, `memberDescriptor`. Uses `ReferenceQuery`.
5. **`search-declarations`** — Params: `name` (pattern). Uses `DeclarationQuery`.
6. **`search-instructions`** — Params: `opcode` or instruction pattern. Uses `InstructionQuery` if available.
7. **`search-files`** — Params: `query`. Uses `FileQuery` to search non-class files.
8. **`search-text-in-decompilation`** — Params: `pattern` (regex), `offset`, `limit`. Iterates classes, decompiles each, regex-matches on source text. This is expensive; include a warning in the description and a `maxClasses` cap.

**Key note:** `searchService.search(workspace, query)` returns a `Results` object. Iterate results for matches.

**Commit:**

```bash
git commit -m "feat: add SearchToolProvider (8 tools)"
```

---

### Task 11: XRefToolProvider

**Files:**
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/providers/XRefToolProvider.java`

**Services injected:** `WorkspaceManager`, `SearchService` (reference queries serve as xrefs)

**Tools to implement:**

1. **`xrefs-to`** — Params: `className`, `memberName` (optional), `memberDescriptor` (optional), `includeContext` (bool), `offset`, `limit`. Uses reference search to find all locations referencing this class/member.

2. **`xrefs-from`** — Params: `className`, `methodName`, `methodDescriptor`. Finds all references made FROM within the given method.

3. **`xrefs-count`** — Count-only variant of `xrefs-to`.

**Commit:**

```bash
git commit -m "feat: add XRefToolProvider (3 tools)"
```

---

### Task 12: CallGraphToolProvider

**Files:**
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/providers/CallGraphToolProvider.java`

**Services injected:** `WorkspaceManager`, `CallGraphService`

**Tools to implement:**

1. **`callgraph-callers`** — Params: `className`, `methodName`, `methodDescriptor`, `depth` (int, default 1). Returns methods that call the target.

2. **`callgraph-callees`** — Params: same. Returns methods called by the target.

3. **`callgraph-path`** — Params: `fromClass`, `fromMethod`, `toClass`, `toMethod`. Finds a call chain between two methods (BFS/DFS with depth limit).

4. **`callgraph-build`** — Triggers call graph construction/refresh. Returns status.

**Commit:**

```bash
git commit -m "feat: add CallGraphToolProvider (4 tools)"
```

---

### Task 13: InheritanceToolProvider

**Files:**
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/providers/InheritanceToolProvider.java`

**Services injected:** `WorkspaceManager`, `InheritanceGraphService`

This is a small provider — the main hierarchy tool is already in `NavigationToolProvider.class-get-hierarchy`. This provider adds:

1. **`inheritance-subtypes`** — Params: `className`. Returns all direct and transitive subtypes.

2. **`inheritance-supertypes`** — Params: `className`. Returns the full chain to `java/lang/Object`.

3. **`inheritance-common-parent`** — Params: `classA`, `classB`. Finds the lowest common ancestor.

**Commit:**

```bash
git commit -m "feat: add InheritanceToolProvider (3 tools)"
```

---

## Phase 4: Modification Tool Providers

### Task 14: MappingToolProvider (renaming)

**Files:**
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/providers/MappingToolProvider.java`

**Services injected:** `WorkspaceManager`, `MappingApplier`, `AggregateMappingManager`

**Tools:**

1. **`rename-class`** — Params: `oldName`, `newName`. Creates an `IntermediateMappings` with a single class mapping, applies via `MappingApplier.applyToPrimaryResource(mappings)`.

2. **`rename-method`** — Params: `className`, `methodName`, `methodDescriptor`, `newName`.

3. **`rename-field`** — Params: `className`, `fieldName`, `fieldDescriptor`, `newName`.

4. **`rename-variable`** — Params: `className`, `methodName`, `methodDescriptor`, `oldVarName`, `newVarName`. This operates on decompiler output — may need to use a decompiler-specific API or comment system.

5. **`mapping-apply`** — Params: `filePath`, `format` (optional, auto-detect). Reads mapping file, applies to workspace.

6. **`mapping-export`** — Params: `outputPath`, `format`. Exports current mappings.

7. **`mapping-list-formats`** — Lists supported mapping formats with descriptions.

**Commit:**

```bash
git commit -m "feat: add MappingToolProvider with rename and mapping tools (7 tools)"
```

---

### Task 15: CommentToolProvider

**Files:**
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/providers/CommentToolProvider.java`

**Services injected:** `WorkspaceManager`, `CommentManager`

**Tools:**

1. **`comment-set`** — Params: `className`, `memberName` (optional), `comment`.
2. **`comment-get`** — Params: `className`, `memberName` (optional).
3. **`comment-search`** — Params: `query`, `offset`, `limit`.
4. **`comment-delete`** — Params: `className`, `memberName` (optional).

**Commit:**

```bash
git commit -m "feat: add CommentToolProvider (4 tools)"
```

---

### Task 16: CompilerToolProvider

**Files:**
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/providers/CompilerToolProvider.java`

**Services injected:** `WorkspaceManager`, `JavacCompiler`, `PhantomGenerator`

**Tools:**

1. **`compile-java`** — Params: `className`, `source` (Java source code). Compiles and replaces the class in the workspace. Returns success/errors.

2. **`compile-check`** — Params: `className`, `source`. Compiles but does not apply. Returns compiler diagnostics.

3. **`phantom-generate`** — Generates phantom classes for missing dependencies. Returns list of generated phantoms.

**Key note:** Use `JavacArgumentsBuilder` to construct arguments. The workspace provides classpath context for compilation.

**Commit:**

```bash
git commit -m "feat: add CompilerToolProvider (3 tools)"
```

---

### Task 17: AssemblerToolProvider

**Files:**
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/providers/AssemblerToolProvider.java`

**Services injected:** `WorkspaceManager`, `AssemblerPipelineManager`

**Tools:**

1. **`disassemble-method`** — Params: `className`, `methodName`, `methodDescriptor`. Uses `JvmAssemblerPipeline.disassemble()`.

2. **`assemble-method`** — Params: `className`, `methodName`, `jasm` (JASM bytecode text). Tokenizes, parses, assembles, replaces in workspace.

3. **`disassemble-class`** — Full class disassembly.

4. **`assemble-class`** — Full class assembly from JASM.

**Commit:**

```bash
git commit -m "feat: add AssemblerToolProvider (4 tools)"
```

---

### Task 18: TransformToolProvider

**Files:**
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/providers/TransformToolProvider.java`

**Services injected:** `WorkspaceManager`, `TransformationManager`, `TransformationApplier`

**Tools:**

1. **`transform-list`** — Lists all registered JVM class transformers with names and descriptions.

2. **`transform-apply`** — Params: `transformerName`, `targetClasses` (optional filter). Applies a single transformer.

3. **`transform-apply-batch`** — Params: `transformerNames` (array). Applies multiple transformers in sequence.

4. **`transform-preview`** — Params: `transformerName`, `className`. Shows what would change (decompile before, apply to copy, decompile after, diff).

5. **`transform-undo`** — Uses workspace history to revert the last transformation.

**Commit:**

```bash
git commit -m "feat: add TransformToolProvider (5 tools)"
```

---

### Task 19: AttachToolProvider

**Files:**
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/providers/AttachToolProvider.java`

**Services injected:** `AttachManager`

**Tools:**

1. **`attach-list-vms`** — Scans for running JVMs, returns PID, display name, main class.
2. **`attach-connect`** — Params: `pid` or `displayName`. Attaches to a JVM.
3. **`attach-load-classes`** — Loads classes from attached JVM into workspace.
4. **`attach-disconnect`** — Disconnects from attached JVM.

**Commit:**

```bash
git commit -m "feat: add AttachToolProvider (4 tools)"
```

---

## Phase 5: MCP Resources

### Task 20: MCP Resource Providers

**Files:**
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/resources/WorkspaceResourceProvider.java`
- Create: `recaf-mcp-plugin/src/main/java/dev/recafmcp/resources/ClassResourceProvider.java`

Register resources on the `McpSyncServer`:

1. **`recaf://workspace`** — Returns current workspace metadata as JSON.
2. **`recaf://classes`** — Returns list of all class names with basic info.
3. **`recaf://class/{name}`** — URI template. Returns full class info + decompiled source for the named class.

Use `server.addResource()` for static resources and resource templates.

**Commit:**

```bash
git commit -m "feat: add MCP resource providers for workspace and classes"
```

---

## Phase 6: Python Bridge

### Task 21: Create Python stdio bridge project

**Files:**
- Create: `recaf-mcp-bridge/pyproject.toml`
- Create: `recaf-mcp-bridge/src/recaf_mcp_bridge/__init__.py`
- Create: `recaf-mcp-bridge/src/recaf_mcp_bridge/bridge.py`

**Step 1: Create pyproject.toml**

```toml
[project]
name = "recaf-mcp-bridge"
version = "0.1.0"
description = "Stdio-to-HTTP MCP bridge for Recaf MCP Server"
requires-python = ">=3.11"
dependencies = [
    "mcp>=1.0.0",
    "httpx>=0.27.0",
]

[project.scripts]
recaf-mcp-bridge = "recaf_mcp_bridge.bridge:main"

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"
```

**Step 2: Write bridge.py**

Direct port of ReVa's `ReVaStdioBridge` pattern:

```python
"""Stdio-to-HTTP MCP bridge for Recaf MCP Server."""

import asyncio
import sys
from typing import Any

from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.client.streamable_http import streamablehttp_client
from mcp import ClientSession
from mcp.types import (
    Tool,
    Resource,
    Prompt,
    TextContent,
    ImageContent,
    EmbeddedResource,
)


class RecafMcpBridge:
    """MCP Server that bridges stdio to Recaf's Streamable HTTP endpoint."""

    def __init__(self, port: int = 8085):
        self.port = port
        self.url = f"http://localhost:{port}/mcp/message"
        self.server = Server("recaf-mcp-bridge")
        self.backend: ClientSession | None = None
        self._register_handlers()

    def _register_handlers(self):
        @self.server.list_tools()
        async def list_tools() -> list[Tool]:
            if not self.backend:
                raise RuntimeError("Backend not connected")
            result = await self.backend.list_tools()
            return result.tools

        @self.server.call_tool()
        async def call_tool(
            name: str, arguments: dict[str, Any]
        ) -> list[TextContent | ImageContent | EmbeddedResource]:
            if not self.backend:
                raise RuntimeError("Backend not connected")
            result = await self.backend.call_tool(name, arguments)
            return result.content

        @self.server.list_resources()
        async def list_resources() -> list[Resource]:
            if not self.backend:
                raise RuntimeError("Backend not connected")
            result = await self.backend.list_resources()
            return result.resources

        @self.server.read_resource()
        async def read_resource(uri: str) -> str | bytes:
            if not self.backend:
                raise RuntimeError("Backend not connected")
            result = await self.backend.read_resource(uri)
            if result.contents and len(result.contents) > 0:
                content = result.contents[0]
                if hasattr(content, "text") and content.text:
                    return content.text
                if hasattr(content, "blob") and content.blob:
                    return content.blob
            return ""

    async def run(self):
        print(f"Connecting to Recaf MCP at {self.url}...", file=sys.stderr)
        async with streamablehttp_client(
            self.url, timeout=300.0
        ) as (read_stream, write_stream, _):
            async with ClientSession(read_stream, write_stream) as session:
                self.backend = session
                init = await session.initialize()
                print(
                    f"Connected to {init.serverInfo.name} v{init.serverInfo.version}",
                    file=sys.stderr,
                )
                async with stdio_server() as (read_s, write_s):
                    await self.server.run(
                        read_s, write_s, self.server.create_initialization_options()
                    )


def main():
    import argparse

    parser = argparse.ArgumentParser(description="Recaf MCP stdio bridge")
    parser.add_argument("--port", type=int, default=8085, help="Recaf MCP port")
    args = parser.parse_args()

    bridge = RecafMcpBridge(port=args.port)
    asyncio.run(bridge.run())


if __name__ == "__main__":
    main()
```

**Step 3: Initialize with uv**

Run: `cd recaf-mcp-bridge && uv sync`
Expected: Dependencies install, `.venv` created

**Step 4: Commit**

```bash
git add recaf-mcp-bridge/
git commit -m "feat: add Python stdio-to-HTTP MCP bridge"
```

---

## Phase 7: Claude Code Skills

### Task 22: Write jar-triage skill

**Files:**
- Create: `skills/jar-triage.md`

Write a Claude Code skill that guides structured breadth-first analysis of a Java binary. The skill should:

- Open workspace if not already open
- Get class count and package structure
- Sample strings (first 50) for initial intelligence
- Identify entry points (search for `main(`, Servlet classes, Spring `@Controller`, Android `Activity`)
- Survey top-level packages
- Decompile 3-5 key classes (entry points, largest classes)
- Create a task list of areas to investigate deeper

Budget: ~20 tool calls

**Commit:**

```bash
git commit -m "feat: add jar-triage Claude Code skill"
```

---

### Task 23: Write deep-analysis skill

**Files:**
- Create: `skills/deep-analysis.md`

Depth-first investigation loop adapted for Java RE:

1. READ — `decompile-class` or `decompile-method`
2. UNDERSTAND — analyze control flow, data flow, patterns
3. IMPROVE — `rename-method`, `rename-variable`, `comment-set`
4. VERIFY — `decompile-class` again to confirm improvement
5. FOLLOW THREADS — `xrefs-to`, `callgraph-callers`, `callgraph-callees`
6. TRACK — `comment-set` with findings

Budget: ~15 tool calls per investigation cycle

**Commit:**

```bash
git commit -m "feat: add deep-analysis Claude Code skill"
```

---

### Task 24: Write deobfuscation-workflow skill

**Files:**
- Create: `skills/deobfuscation-workflow.md`

Java-specific deobfuscation pipeline:

1. IDENTIFY — `search-strings` for encrypted markers, `class-list` for naming patterns, `decompile-class` for control flow anomalies
2. SELECT — `transform-list` to find applicable transforms
3. PREVIEW — `transform-preview` on a sample class
4. APPLY — `transform-apply` or `transform-apply-batch`
5. VERIFY — `decompile-class` to check results
6. CLEAN UP — `rename-class`, `rename-method`, `comment-set`

**Commit:**

```bash
git commit -m "feat: add deobfuscation-workflow Claude Code skill"
```

---

## Phase 8: Integration & Polish

### Task 25: Wire all tool providers into RecafMcpPlugin

**Files:**
- Modify: `recaf-mcp-plugin/src/main/java/dev/recafmcp/RecafMcpPlugin.java`

Update the constructor to inject ALL required services and register ALL tool providers in `onEnable()`:

```java
@Inject
public RecafMcpPlugin(WorkspaceManager workspaceManager,
                      DecompilerManager decompilerManager,
                      SearchService searchService,
                      CallGraphService callGraphService,
                      InheritanceGraphService inheritanceGraphService,
                      MappingApplier mappingApplier,
                      AggregateMappingManager mappingManager,
                      CommentManager commentManager,
                      JavacCompiler compiler,
                      AssemblerPipelineManager assemblerManager,
                      TransformationManager transformManager,
                      TransformationApplier transformApplier,
                      AttachManager attachManager,
                      ResourceImporter resourceImporter) {
    // Store all references
}
```

Register all providers in `onEnable()`:

```java
McpSyncServer mcp = serverManager.start();

new WorkspaceToolProvider(mcp, workspaceManager, resourceImporter).registerTools();
new NavigationToolProvider(mcp, workspaceManager).registerTools();
new DecompilerToolProvider(mcp, workspaceManager, decompilerManager).registerTools();
new SearchToolProvider(mcp, workspaceManager, searchService).registerTools();
new XRefToolProvider(mcp, workspaceManager, searchService).registerTools();
new CallGraphToolProvider(mcp, workspaceManager, callGraphService).registerTools();
new InheritanceToolProvider(mcp, workspaceManager, inheritanceGraphService).registerTools();
new MappingToolProvider(mcp, workspaceManager, mappingApplier, mappingManager).registerTools();
new CommentToolProvider(mcp, workspaceManager, commentManager).registerTools();
new CompilerToolProvider(mcp, workspaceManager, compiler).registerTools();
new AssemblerToolProvider(mcp, workspaceManager, assemblerManager).registerTools();
new TransformToolProvider(mcp, workspaceManager, transformManager, transformApplier).registerTools();
new AttachToolProvider(mcp, attachManager).registerTools();

// Resources
new WorkspaceResourceProvider(mcp, workspaceManager).register();
new ClassResourceProvider(mcp, workspaceManager, decompilerManager).register();
```

**Commit:**

```bash
git commit -m "feat: wire all tool providers into RecafMcpPlugin"
```

---

### Task 26: Build and integration test

**Step 1: Full build**

Run: `cd recaf-mcp-plugin && ./gradlew clean build`
Expected: BUILD SUCCESSFUL, JAR produced in `build/libs/`

**Step 2: Test with Recaf**

Run: `cd recaf-mcp-plugin && ./gradlew runRecaf`
Expected: Recaf starts, plugin loads, MCP server starts on port 8085. Look for log line: "MCP server started on 127.0.0.1:8085/mcp/message"

**Step 3: Test MCP endpoint**

In another terminal:
```bash
curl -X POST http://localhost:8085/mcp/message \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"0.1"}}}'
```
Expected: JSON response with server capabilities

**Step 4: Test tool listing**

```bash
curl -X POST http://localhost:8085/mcp/message \
  -H "Content-Type: application/json" \
  -H "MCP-Session-Id: <session-id-from-init>" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
```
Expected: JSON response listing all 62+ tools

**Step 5: Test Python bridge**

```bash
cd recaf-mcp-bridge && uv run recaf-mcp-bridge --port 8085
```
Expected: "Connected to recaf-mcp v0.1.0" on stderr

**Step 6: Commit**

```bash
git commit -m "test: verify full build and MCP server startup"
```

---

### Task 27: Add Claude Code MCP configuration

**Files:**
- Create: `claude-code-config.json` (example configuration for Claude Code)

Create an example configuration showing how to add Recaf MCP to Claude Code:

```json
{
  "mcpServers": {
    "recaf": {
      "command": "uv",
      "args": ["run", "--directory", "/path/to/recaf-mcp-bridge", "recaf-mcp-bridge"],
      "env": {}
    }
  }
}
```

**Commit:**

```bash
git commit -m "docs: add Claude Code MCP configuration example"
```

---

## Summary

| Phase | Tasks | Tools Added | Running Total |
|-------|-------|-------------|---------------|
| 1. Scaffolding | 1-2 | 0 | 0 |
| 2. Infrastructure | 3-6 | 0 | 0 |
| 3. Core Providers | 7-13 | 31 | 31 |
| 4. Modification Providers | 14-19 | 23 | 54 |
| 5. Resources | 20 | 3 resources | 54 + 3r |
| 6. Python Bridge | 21 | 0 | 54 + 3r |
| 7. Skills | 22-24 | 3 skills | 54 + 3r + 3s |
| 8. Integration | 25-27 | 0 | **54 tools + 3 resources + 3 skills** |

**Note on reduced tool count:** During detailed planning, some tools from the design doc were consolidated (e.g., `inheritance-*` tools absorbed into NavigationToolProvider's `class-get-hierarchy`). The final count may vary as implementation reveals which tools are genuinely useful vs. redundant.

**Dependencies between tasks:** Tasks within each phase are sequential. Phases 3-5 depend on Phase 2. Phase 6 is independent of Phases 3-5 (only needs the server running). Phase 7 is independent of all Java code. Phase 8 ties everything together.
