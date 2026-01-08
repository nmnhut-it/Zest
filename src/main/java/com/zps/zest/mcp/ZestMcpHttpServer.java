package com.zps.zest.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vcs.CodeSmellDetector;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.zps.zest.testgen.evaluation.TestCodeValidator;
import com.zps.zest.testgen.tools.LookupClassTool;
import com.zps.zest.testgen.tools.LookupMethodTool;
import com.zps.zest.testgen.analysis.UsageAnalyzer;
import com.zps.zest.testgen.analysis.UsageContext;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.jackson.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MCP HTTP Server for Zest with dual transport support.
 * Provides IntelliJ project tools via HTTP endpoints using MCP protocol.
 *
 * Supports two transports for maximum client compatibility:
 * - /mcp: Streamable HTTP (MCP 2025-03-26 spec, recommended)
 * - /sse: Server-Sent Events (legacy, for older clients)
 *
 * Streamable HTTP (/mcp):
 * - POST: JSON-RPC requests (returns JSON or SSE stream)
 * - GET: Server-Sent Events for notifications
 * - DELETE: Session cleanup
 *
 * SSE (/sse):
 * - GET /sse: SSE stream for server events
 * - POST /message: JSON-RPC messages
 */
public class ZestMcpHttpServer {
    private static final Logger LOG = Logger.getInstance(ZestMcpHttpServer.class);
    private static final String STREAMABLE_ENDPOINT = "/mcp";
    private static final String SSE_ENDPOINT = "/sse";
    private static final String SSE_MESSAGE_ENDPOINT = "/message";

    private final McpSyncServer mcpServerStreamable;
    private final McpSyncServer mcpServerSse;
    private final Server jettyServer;
    private final HttpServletStreamableServerTransportProvider streamableTransport;
    private final HttpServletSseServerTransportProvider sseTransport;
    private final int port;

    public ZestMcpHttpServer(int port) {
        this.port = port;

        ObjectMapper objectMapper = new ObjectMapper();
        JacksonMcpJsonMapper mcpJsonMapper = new JacksonMcpJsonMapper(objectMapper);

        // Streamable HTTP transport (new, recommended)
        this.streamableTransport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(mcpJsonMapper)
                .mcpEndpoint(STREAMABLE_ENDPOINT)
                .build();

        this.mcpServerStreamable = McpServer.sync(streamableTransport)
                .jsonMapper(mcpJsonMapper)
                .serverInfo("zest-intellij-http-tools", "1.0.0")
                .jsonSchemaValidator(new DefaultJsonSchemaValidator())
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .prompts(true)
                        .logging()
                        .build())
                .build();

        // SSE transport (legacy, for backward compatibility)
        this.sseTransport = HttpServletSseServerTransportProvider.builder()
                .jsonMapper(mcpJsonMapper)
                .messageEndpoint(SSE_MESSAGE_ENDPOINT)
                .build();

        this.mcpServerSse = McpServer.sync(sseTransport)
                .jsonMapper(mcpJsonMapper)
                .serverInfo("zest-intellij-http-tools", "1.0.0")
                .jsonSchemaValidator(new DefaultJsonSchemaValidator())
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .prompts(true)
                        .logging()
                        .build())
                .build();

        // Register tools and prompts on both servers
        registerTools();
        registerPrompts();

        this.jettyServer = createJettyServer();

        LOG.info("Zest MCP HTTP Server created on port " + port + " with dual transport support");
    }

    private Server createJettyServer() {
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName("mcp-http-server");
        threadPool.setMinThreads(2);
        threadPool.setMaxThreads(10);

        Server server = new Server(threadPool);

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        // Streamable HTTP transport at /mcp
        ServletHolder streamableServlet = new ServletHolder(streamableTransport);
        context.addServlet(streamableServlet, STREAMABLE_ENDPOINT);
        context.addServlet(streamableServlet, STREAMABLE_ENDPOINT + "/*");

        // SSE transport at /sse and /message
        ServletHolder sseServlet = new ServletHolder(sseTransport);
        context.addServlet(sseServlet, SSE_ENDPOINT);
        context.addServlet(sseServlet, SSE_MESSAGE_ENDPOINT);

        server.setHandler(context);

        return server;
    }

    /**
     * Add a tool to both MCP servers (Streamable HTTP and SSE).
     */
    private void addToolToBoth(McpServerFeatures.SyncToolSpecification tool) {
        mcpServerStreamable.addTool(tool);
        mcpServerSse.addTool(tool);
    }

    /**
     * Add a prompt to both MCP servers (Streamable HTTP and SSE).
     */
    private void addPromptToBoth(McpServerFeatures.SyncPromptSpecification prompt) {
        mcpServerStreamable.addPrompt(prompt);
        mcpServerSse.addPrompt(prompt);
    }

    private void registerTools() {
        ObjectMapper mapper = new ObjectMapper();
        JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(mapper);

        // ========== Core IDE Tools ==========

        McpSchema.Tool currentFileTool = McpSchema.Tool.builder()
                .name("getCurrentFile")
                .description("Get the currently open file in the editor")
                .inputSchema(jsonMapper, buildGetCurrentFileSchema())
                .build();

        addToolToBoth(new McpServerFeatures.SyncToolSpecification(
                currentFileTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    return handleGetCurrentFile(projectPath);
                }
        ));

        McpSchema.Tool lookupClassTool = McpSchema.Tool.builder()
                .name("lookupClass")
                .description("Look up class/method signatures. Works with project classes, JARs, and JDK. Use methodName param to filter specific method.")
                .inputSchema(jsonMapper, buildLookupClassSchema())
                .build();

        addToolToBoth(new McpServerFeatures.SyncToolSpecification(
                lookupClassTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String methodName = (String) arguments.get("methodName");
                    return handleLookupClass(projectPath, className, methodName);
                }
        ));

        McpSchema.Tool getJavaCodeUnderTestTool = McpSchema.Tool.builder()
                .name("getJavaCodeUnderTest")
                .description("Get Java class analysis for test generation. " +
                        "AUTOMATION: Pass 'className' to skip GUI and analyze directly. " +
                        "INTERACTIVE: Omit 'className' to show GUI for class selection. " +
                        "Returns source code + static analysis + usage patterns. " +
                        "Pass testType ('unit'/'integration'/'both') and methodFilter ('save,delete') " +
                        "to focus the analysis.")
                .inputSchema(jsonMapper, buildGetJavaCodeUnderTestSchema())
                .build();

        addToolToBoth(new McpServerFeatures.SyncToolSpecification(
                getJavaCodeUnderTestTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String testType = (String) arguments.get("testType");
                    String methodFilter = (String) arguments.get("methodFilter");
                    return handleGetJavaCodeUnderTest(projectPath, className, testType, methodFilter);
                }
        ));

        McpSchema.Tool showFileTool = McpSchema.Tool.builder()
                .name("showFile")
                .description("Open a file in IntelliJ editor")
                .inputSchema(jsonMapper, buildShowFileSchema())
                .build();

        addToolToBoth(new McpServerFeatures.SyncToolSpecification(
                showFileTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String filePath = (String) arguments.get("filePath");
                    return handleShowFile(projectPath, filePath);
                }
        ));

        // ========== PSI Navigation Tools ==========

        McpSchema.Tool findUsagesTool = McpSchema.Tool.builder()
                .name("findUsages")
                .description("Find all usages of a class, method, or field in the project")
                .inputSchema(jsonMapper, buildFindUsagesSchema())
                .build();

        addToolToBoth(new McpServerFeatures.SyncToolSpecification(
                findUsagesTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String memberName = (String) arguments.get("memberName");
                    return handleFindUsages(projectPath, className, memberName);
                }
        ));

        McpSchema.Tool findImplementationsTool = McpSchema.Tool.builder()
                .name("findImplementations")
                .description("Find implementations of an interface or abstract method")
                .inputSchema(jsonMapper, buildFindImplementationsSchema())
                .build();

        addToolToBoth(new McpServerFeatures.SyncToolSpecification(
                findImplementationsTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String methodName = (String) arguments.get("methodName");
                    return handleFindImplementations(projectPath, className, methodName);
                }
        ));

        McpSchema.Tool getTypeHierarchyTool = McpSchema.Tool.builder()
                .name("getTypeHierarchy")
                .description("Get type hierarchy - superclasses, interfaces, and subclasses")
                .inputSchema(jsonMapper, buildGetTypeHierarchySchema())
                .build();

        addToolToBoth(new McpServerFeatures.SyncToolSpecification(
                getTypeHierarchyTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    return handleGetTypeHierarchy(projectPath, className);
                }
        ));

        McpSchema.Tool getCallHierarchyTool = McpSchema.Tool.builder()
                .name("getCallHierarchy")
                .description("Get call hierarchy - callers or callees of a method")
                .inputSchema(jsonMapper, buildGetCallHierarchySchema())
                .build();

        addToolToBoth(new McpServerFeatures.SyncToolSpecification(
                getCallHierarchyTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String methodName = (String) arguments.get("methodName");
                    Boolean callers = (Boolean) arguments.getOrDefault("callers", true);
                    return handleGetCallHierarchy(projectPath, className, methodName, callers);
                }
        ));

        // ========== Refactoring Tools ==========

        McpSchema.Tool renameTool = McpSchema.Tool.builder()
                .name("rename")
                .description("Rename a CLASS-LEVEL member (class, method, or field). Updates all usages. NOTE: Cannot rename local variables - only class/method/field declarations.")
                .inputSchema(jsonMapper, buildRenameSchema())
                .build();

        addToolToBoth(new McpServerFeatures.SyncToolSpecification(
                renameTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String memberName = (String) arguments.get("memberName");
                    String newName = (String) arguments.get("newName");
                    return handleRename(projectPath, className, memberName, newName);
                }
        ));

        McpSchema.Tool getMethodBodyTool = McpSchema.Tool.builder()
                .name("getMethodBody")
                .description("Get method body with code blocks for refactoring analysis")
                .inputSchema(jsonMapper, buildGetMethodBodySchema())
                .build();

        addToolToBoth(new McpServerFeatures.SyncToolSpecification(
                getMethodBodyTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String methodName = (String) arguments.get("methodName");
                    return handleGetMethodBody(projectPath, className, methodName);
                }
        ));

        // ========== New Refactoring Tools ==========

        // Extract Constant tool - fully automated with string extraction support
        McpSchema.Tool extractConstantTool = McpSchema.Tool.builder()
                .name("extractConstant")
                .description("Extract a literal (string/number) to a static final constant. " +
                        "FIRST: Call getMethodBody to see line numbers. " +
                        "className: Supports simple name (e.g., 'MyClass') or fully qualified (e.g., 'com.example.MyClass'). " +
                        "lineNumber: 1-based (from getMethodBody), tolerates ±1 offset. " +
                        "targetValue (optional): Extract substring from within a string literal.")
                .inputSchema(jsonMapper, buildExtractConstantSchema())
                .build();

        addToolToBoth(new McpServerFeatures.SyncToolSpecification(
                extractConstantTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String methodName = (String) arguments.get("methodName");
                    int lineNumber = ((Number) arguments.get("lineNumber")).intValue();
                    String constantName = (String) arguments.get("constantName");
                    String targetValue = (String) arguments.get("targetValue"); // Optional
                    return handleExtractConstant(projectPath, className, methodName, lineNumber, constantName, targetValue);
                }
        ));

        // Extract Method tool - uses IntelliJ's native ExtractMethodProcessor
        McpSchema.Tool extractMethodTool = McpSchema.Tool.builder()
                .name("extractMethod")
                .description("Extract code lines into a new method (IntelliJ's native refactoring). " +
                        "FIRST: Call getMethodBody to see line numbers for each statement. " +
                        "className: Supports simple name or fully qualified. " +
                        "startLine/endLine: 1-based line numbers (from getMethodBody). " +
                        "AUTO-HANDLES: return values, parameters, exceptions. " +
                        "If extraction fails, error message lists available methods.")
                .inputSchema(jsonMapper, buildExtractMethodSchema())
                .build();

        addToolToBoth(new McpServerFeatures.SyncToolSpecification(
                extractMethodTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String sourceMethodName = (String) arguments.get("sourceMethodName");
                    int startLine = ((Number) arguments.get("startLine")).intValue();
                    int endLine = ((Number) arguments.get("endLine")).intValue();
                    String newMethodName = (String) arguments.get("newMethodName");
                    return handleExtractMethod(projectPath, className, sourceMethodName, startLine, endLine, newMethodName);
                }
        ));

        // Safe Delete tool
        McpSchema.Tool safeDeleteTool = McpSchema.Tool.builder()
                .name("safeDelete")
                .description("Safely delete a class-level field or method if unused. " +
                        "RECOMMENDED: Call findDeadCode first to identify unused members. " +
                        "className: Supports simple name or fully qualified. " +
                        "memberName: Name of method or field (not local variables). " +
                        "Returns: Success if deleted, or list of usages preventing deletion.")
                .inputSchema(jsonMapper, buildSafeDeleteSchema())
                .build();

        addToolToBoth(new McpServerFeatures.SyncToolSpecification(
                safeDeleteTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String memberName = (String) arguments.get("memberName");
                    return handleSafeDelete(projectPath, className, memberName);
                }
        ));

        // Find Dead Code tool
        McpSchema.Tool findDeadCodeTool = McpSchema.Tool.builder()
                .name("findDeadCode")
                .description("Find unused methods and fields in a class. " +
                        "className: Supports simple name or fully qualified. " +
                        "Returns: List of private methods/fields with zero usages. " +
                        "Use with safeDelete to clean up dead code.")
                .inputSchema(jsonMapper, buildFindDeadCodeSchema())
                .build();

        addToolToBoth(new McpServerFeatures.SyncToolSpecification(
                findDeadCodeTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    return handleFindDeadCode(projectPath, className);
                }
        ));

        // Move Class tool
        McpSchema.Tool moveClassTool = McpSchema.Tool.builder()
                .name("moveClass")
                .description("Move a class to a different package (updates all imports and references). " +
                        "className: Supports simple name or fully qualified. " +
                        "targetPackage: Full package name (e.g., 'com.example.newpackage'). " +
                        "Creates target package if it doesn't exist.")
                .inputSchema(jsonMapper, buildMoveClassSchema())
                .build();

        addToolToBoth(new McpServerFeatures.SyncToolSpecification(
                moveClassTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String targetPackage = (String) arguments.get("targetPackage");
                    return handleMoveClass(projectPath, className, targetPackage);
                }
        ));

        // Validate Code tool - uses IntelliJ's CodeSmellDetector for real compilation checking
        McpSchema.Tool validateCodeTool = McpSchema.Tool.builder()
                .name("validateCode")
                .description("Validate Java file for compilation errors. " +
                        "Uses IntelliJ's CodeSmellDetector - finds real errors like missing imports, " +
                        "type mismatches, syntax errors. " +
                        "WORKFLOW: 1) Save code to file, 2) Call validateCode with filePath, 3) Fix errors if any. " +
                        "Returns: ✅ if compiles, or list of errors with line numbers.")
                .inputSchema(jsonMapper, buildValidateCodeSchema())
                .build();

        addToolToBoth(new McpServerFeatures.SyncToolSpecification(
                validateCodeTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String filePath = (String) arguments.get("filePath");
                    return handleValidateCode(projectPath, filePath);
                }
        ));

        // Get Project Dependencies tool - reads build.gradle/pom.xml to detect available libraries
        McpSchema.Tool getProjectDependenciesTool = McpSchema.Tool.builder()
                .name("getProjectDependencies")
                .description("Get project dependencies from build.gradle, pom.xml, or .idea/libraries (local JARs). " +
                        "CRITICAL: Call this BEFORE writing tests to know what libraries are available. " +
                        "Returns: build system type, all dependencies, and test library availability " +
                        "(JUnit 5, Testcontainers, WireMock, AssertJ, etc.). " +
                        "If required libraries are missing, STOP and tell user to add them - don't try to fix imports!")
                .inputSchema(jsonMapper, buildGetProjectDependenciesSchema())
                .build();

        addToolToBoth(new McpServerFeatures.SyncToolSpecification(
                getProjectDependenciesTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    return handleGetProjectDependencies(projectPath);
                }
        ));

        // Get Project JDK tool - returns JDK path for shell commands
        McpSchema.Tool getProjectJdkTool = McpSchema.Tool.builder()
                .name("getProjectJdk")
                .description("Get the JDK configured for the project in IntelliJ. " +
                        "Returns JAVA_HOME path that can be used for shell commands. " +
                        "Use this when you need to run java, javac, mvn, or gradle commands. " +
                        "Example: export JAVA_HOME=\"<path>\" && mvn test")
                .inputSchema(jsonMapper, buildGetProjectDependenciesSchema())
                .build();

        addToolToBoth(new McpServerFeatures.SyncToolSpecification(
                getProjectJdkTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    return handleGetProjectJdk(projectPath);
                }
        ));

        LOG.info("Registered 18 MCP tools: getCurrentFile, lookupClass, getJavaCodeUnderTest, showFile, findUsages, findImplementations, getTypeHierarchy, getCallHierarchy, rename, getMethodBody, extractConstant, extractMethod, safeDelete, findDeadCode, moveClass, validateCode, getProjectDependencies, getProjectJdk");
    }

    private void registerPrompts() {
        // All prompts loaded dynamically from resources/prompts/*.md
        // Override by placing files in ~/.zest/dev-prompts/ or ${PROJECT}/.zest/prompts/

        registerDynamicPrompt("review", "Smart code review with focus options",
                List.of(), PromptLoader.PromptName.REVIEW);

        registerDynamicPrompt("explain", "Smart code explainer with auto-detection",
                List.of(), PromptLoader.PromptName.EXPLAIN);

        registerDynamicPrompt("commit", "Smart hands-free git commit assistant",
                List.of(), PromptLoader.PromptName.COMMIT);

        registerDynamicPrompt("zest-test", "Smart test generation with sub-agents",
                List.of(), PromptLoader.PromptName.TEST_GENERATION);

        LOG.info("Registered 4 MCP prompts (all dynamic): review, explain, commit, zest-test");
    }

    /**
     * Register prompt with dynamic loading from PromptLoader.
     * Prompts are loaded at request time, not at registration time.
     * Uses static method since MCP prompts don't have project context.
     */
    private void registerDynamicPrompt(String name, String description, List<McpSchema.PromptArgument> arguments, PromptLoader.PromptName promptName) {
        McpSchema.Prompt prompt = new McpSchema.Prompt(name, description, arguments);

        addPromptToBoth(new McpServerFeatures.SyncPromptSpecification(
                prompt,
                (exchange, request) -> {
                    // Load prompt dynamically from ~/.zest/dev-prompts/ or bundled
                    String promptTemplate = PromptLoader.getPromptStatic(promptName);
                    String filledPrompt = promptTemplate;

                    if (request.arguments() != null) {
                        for (var entry : request.arguments().entrySet()) {
                            String placeholder = "{{" + entry.getKey() + "}}";
                            String value = String.valueOf(entry.getValue());
                            filledPrompt = filledPrompt.replace(placeholder, value);
                        }
                    }

                    McpSchema.TextContent textContent = new McpSchema.TextContent(filledPrompt);
                    McpSchema.PromptMessage message = new McpSchema.PromptMessage(McpSchema.Role.USER, textContent);

                    return new McpSchema.GetPromptResult(description, List.of(message));
                }
        ));
    }

    private String buildGetCurrentFileSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    }
                  },
                  "required": ["projectPath"]
                }
                """;
    }

    private String buildLookupClassSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    },
                    "className": {
                      "type": "string",
                      "description": "Fully qualified class name. For inner classes use $ separator."
                    },
                    "methodName": {
                      "type": "string",
                      "description": "Optional method name to filter. If provided, returns only matching method signatures."
                    }
                  },
                  "required": ["projectPath", "className"]
                }
                """;
    }

    private McpSchema.CallToolResult handleGetCurrentFile(String projectPath) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            String result = ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                FileEditorManager editorManager = FileEditorManager.getInstance(project);
                VirtualFile[] selectedFiles = editorManager.getSelectedFiles();

                if (selectedFiles.length == 0) {
                    return "No file is currently open in the editor";
                }

                VirtualFile currentFile = selectedFiles[0];
                PsiFile psiFile = PsiManager.getInstance(project).findFile(currentFile);

                if (psiFile == null) {
                    return "Could not read file: " + currentFile.getPath();
                }

                return "File: " + currentFile.getPath() + "\n" +
                       "Language: " + psiFile.getLanguage().getDisplayName() + "\n\n" +
                       psiFile.getText();
            });

            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(result)), false);

        } catch (Exception e) {
            LOG.error("Error getting current file", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleLookupClass(String projectPath, String className, String methodName) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            LookupClassTool tool = new LookupClassTool(project);
            String result;

            if (methodName != null && !methodName.trim().isEmpty()) {
                // Filter by method name - use LookupMethodTool for specific method
                LookupMethodTool methodTool = new LookupMethodTool(project);
                result = methodTool.lookupMethod(className, methodName);
            } else {
                result = tool.lookupClass(className);
            }

            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(result)), false);

        } catch (Exception e) {
            LOG.error("Error looking up class", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    /**
     * Find project by path, or return the first open project if path is invalid/empty.
     * This makes tools more resilient when LLMs pass incorrect paths like "/workspace/project".
     */
    private Project findProject(String projectPath) {
        Project[] openProjects = ProjectManager.getInstance().getOpenProjects();

        // If no projects are open, return null
        if (openProjects.length == 0) {
            return null;
        }

        // If projectPath is empty or null, return the first open project
        if (projectPath == null || projectPath.trim().isEmpty()) {
            return openProjects[0];
        }

        try {
            Path requestedPath = Paths.get(projectPath).toAbsolutePath().normalize();

            for (Project project : openProjects) {
                String basePath = project.getBasePath();
                if (basePath != null) {
                    Path projectBasePath = Paths.get(basePath).toAbsolutePath().normalize();
                    if (requestedPath.equals(projectBasePath)) {
                        return project;
                    }
                }
            }
        } catch (Exception e) {
            // Path parsing failed (e.g., Linux path on Windows) - fall through to default
            LOG.debug("Could not parse project path '" + projectPath + "', using default project");
        }

        // If no match found or path was invalid, return the first open project
        LOG.info("Project path '" + projectPath + "' not found, using: " + openProjects[0].getBasePath());
        return openProjects[0];
    }

    public void start() throws Exception {
        jettyServer.start();
        LOG.info("✅ Zest MCP HTTP Server started with dual transport support");
        LOG.info("📋 Streamable HTTP (new): http://localhost:" + port + STREAMABLE_ENDPOINT);
        LOG.info("📋 SSE (legacy): http://localhost:" + port + SSE_ENDPOINT + " + " + SSE_MESSAGE_ENDPOINT);
        LOG.info("🔧 Available tools (18): getCurrentFile, lookupClass, getJavaCodeUnderTest, showFile, findUsages, findImplementations, getTypeHierarchy, getCallHierarchy, rename, getMethodBody, extractConstant, extractMethod, safeDelete, findDeadCode, moveClass, validateCode, getProjectDependencies, getProjectJdk");
        LOG.info("💬 Available prompts (4): review, explain, commit, zest-test");
    }

    public void stop() throws Exception {
        if (jettyServer != null) {
            jettyServer.stop();
        }
        if (mcpServerStreamable != null) {
            mcpServerStreamable.close();
        }
        if (mcpServerSse != null) {
            mcpServerSse.close();
        }
        LOG.info("Zest MCP HTTP Server stopped");
    }

    public boolean isRunning() {
        return jettyServer != null && jettyServer.isRunning();
    }

    public int getPort() {
        return port;
    }

    // ========== New Testgen MCP Tools ==========

    private String buildGetJavaCodeUnderTestSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    },
                    "className": {
                      "type": "string",
                      "description": "Fully qualified class name (e.g., 'com.example.MyService'). AUTOMATION: Provide this to skip GUI. INTERACTIVE: Omit to show class picker dialog."
                    },
                    "testType": {
                      "type": "string",
                      "enum": ["unit", "integration", "both"],
                      "description": "Type of tests to generate: 'unit' (fast, isolated), 'integration' (with real dependencies like DB/HTTP), or 'both'. Default: 'both'"
                    },
                    "methodFilter": {
                      "type": "string",
                      "description": "Comma-separated list of method names to test (e.g., 'save,delete,findById'). If empty, all public methods are included."
                    }
                  },
                  "required": ["projectPath"]
                }
                """;
    }

    private McpSchema.CallToolResult handleGetJavaCodeUnderTest(String projectPath, String className,
                                                                   String testType, String methodFilter) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            String effectiveTestType;
            Set<String> methodsToInclude;
            List<String> includedDependencies = new ArrayList<>();
            List<String> additionalFiles = new ArrayList<>();
            String rulesFile = null;
            List<String> exampleFiles = new ArrayList<>();

            // AUTOMATION MODE: className provided, skip GUI
            if (className != null && !className.isBlank()) {
                effectiveTestType = (testType != null && !testType.isBlank()) ? testType : "both";
                methodsToInclude = new HashSet<>();
                if (methodFilter != null && !methodFilter.isBlank()) {
                    for (String m : methodFilter.split(",")) {
                        methodsToInclude.add(m.trim());
                    }
                }
                // In automation mode, auto-detect rules and examples
                String basePath = project.getBasePath();
                if (basePath != null) {
                    java.nio.file.Path rulesPath = java.nio.file.Paths.get(basePath, ".zest", "test-examples", "rules.md");
                    if (java.nio.file.Files.exists(rulesPath)) {
                        rulesFile = rulesPath.toString();
                    }
                }
            } else {
                // INTERACTIVE MODE: Show wizard to select Java class with test options
                TestGenerationWizard.SelectionResult selection = showTestGenerationWizard(project);
                if (selection == null) {
                    return new McpSchema.CallToolResult(
                            List.of(new McpSchema.TextContent("No selection made (user cancelled)")),
                            false
                    );
                }

                className = selection.className;

                // Use GUI selections, with API params as fallback
                effectiveTestType = selection.testType;
                if (testType != null && !testType.isEmpty()) {
                    effectiveTestType = testType; // API param overrides GUI
                }

                methodsToInclude = selection.selectedMethods;
                if (methodFilter != null && !methodFilter.isBlank()) {
                    // API param overrides GUI
                    methodsToInclude = new HashSet<>();
                    for (String m : methodFilter.split(",")) {
                        methodsToInclude.add(m.trim());
                    }
                }

                // Use new context configuration from GUI
                includedDependencies = selection.includedDependencies;
                additionalFiles = selection.additionalFiles;
                rulesFile = selection.rulesFile;
                exampleFiles = selection.exampleFiles;
            }

            // Find class and generate context
            String[] result = new String[2]; // [0] = content, [1] = filePath
            Set<String> finalMethodsToInclude = methodsToInclude;
            String finalTestType = effectiveTestType;
            String finalClassName = className; // Capture for lambda
            List<String> finalIncludedDependencies = includedDependencies;
            List<String> finalAdditionalFiles = additionalFiles;
            String finalRulesFile = rulesFile;
            List<String> finalExampleFiles = exampleFiles;

            ApplicationManager.getApplication().runReadAction(() -> {
                JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
                PsiClass psiClass = facade.findClass(finalClassName, GlobalSearchScope.allScope(project));

                if (psiClass == null) {
                    result[0] = "Class not found: " + finalClassName;
                    return;
                }

                // Generate comprehensive context with filters and user-selected files
                result[0] = formatCodeUnderTest(psiClass, project, finalTestType, finalMethodsToInclude,
                        finalIncludedDependencies, finalAdditionalFiles, finalRulesFile, finalExampleFiles);
            });

            if (result[0] == null || result[0].startsWith("Class not found")) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent(result[0] != null ? result[0] : "Error generating context")),
                        false
                );
            }

            // Write to .zest folder
            String filePath = writeContextToTempFile(result[0], className, project);

            // Extract simple class name for file naming convention
            String simpleClassName = className.contains(".")
                    ? className.substring(className.lastIndexOf('.') + 1)
                    : className;

            // Return only file path + summary (content is in file - saves tokens!)
            String testRoot = detectTestRoot(project);
            String packageName = "";
            int lastDot = className.lastIndexOf('.');
            if (lastDot > 0) {
                packageName = className.substring(0, lastDot);
            }
            String testFilePath = testRoot + "/" + packageName.replace('.', '/') + "/" + simpleClassName + "Test.java";

            StringBuilder response = new StringBuilder();
            response.append("## ⚠️ TEST FILE LOCATION\n\n");
            response.append("```\n").append(testFilePath).append("\n```\n\n");
            response.append("## Context\n\n");
            response.append("| Key | Value |\n");
            response.append("|-----|-------|\n");
            response.append("| Context file | `").append(filePath).append("` |\n");
            response.append("| Test type | ").append(effectiveTestType).append(" |\n");
            response.append("| Methods | ").append(methodsToInclude.isEmpty() ? "all public" : String.join(", ", methodsToInclude)).append(" |\n\n");
            response.append("## Workflow\n\n");
            response.append("1. Read context file (DATA: imports, source, dependencies)\n");
            response.append("2. Read Reference Files if listed in context\n");
            response.append("3. Follow methodology from `zest-test` prompt\n");

            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(response.toString())), false);

        } catch (Exception e) {
            LOG.error("Error getting Java code under test", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private TestGenerationWizard.SelectionResult showTestGenerationWizard(Project project) {
        // Show wizard on EDT thread
        TestGenerationWizard.SelectionResult[] result = new TestGenerationWizard.SelectionResult[1];
        ApplicationManager.getApplication().invokeAndWait(() -> {
            result[0] = TestGenerationWizard.showAndGetSelection(project);
        });
        return result[0];
    }

    private String buildShowFileSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    },
                    "filePath": {
                      "type": "string",
                      "description": "Absolute path to the file to open in the editor"
                    }
                  },
                  "required": ["projectPath", "filePath"]
                }
                """;
    }

    private McpSchema.CallToolResult handleShowFile(String projectPath, String filePath) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            File file = new File(filePath);
            if (!file.exists()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("File not found: " + filePath)),
                        false
                );
            }

            // Open file in editor on EDT
            ApplicationManager.getApplication().invokeLater(() -> {
                VirtualFile virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                        .refreshAndFindFileByIoFile(file);
                if (virtualFile != null) {
                    FileEditorManager.getInstance(project).openFile(virtualFile, true);
                }
            });

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("Opened file in editor: " + filePath)),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error opening file", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    /**
     * Formats comprehensive test context for a class.
     * Includes: source code, user-selected dependencies, additional files, rules, examples.
     */
    private String formatCodeUnderTest(PsiClass psiClass, Project project, String testType, Set<String> methodsToInclude,
                                        List<String> includedDependencies, List<String> additionalFiles,
                                        String rulesFile, List<String> exampleFiles) {
        StringBuilder result = new StringBuilder();
        String className = psiClass.getQualifiedName() != null ? psiClass.getQualifiedName() : psiClass.getName();
        PsiFile containingFile = psiClass.getContainingFile();
        String simpleClassName = psiClass.getName() != null ? psiClass.getName() : "Unknown";
        String packageName = psiClass.getQualifiedName() != null && psiClass.getQualifiedName().contains(".")
                ? psiClass.getQualifiedName().substring(0, psiClass.getQualifiedName().lastIndexOf('.')) : "";
        String testRoot = detectTestRoot(project);

        // Header: DATA ONLY file - methodology is in prompt
        result.append("# ").append(simpleClassName).append(" (Context Data)\n\n");
        result.append("> **This file contains DATA only.** Methodology/patterns are in the `zest-test` prompt.\n\n");
        String testFilePath = testRoot + "/" + packageName.replace('.', '/') + "/" + simpleClassName + "Test.java";
        result.append("## Test Location\n\n");
        result.append("```\n");
        result.append(testFilePath).append("\n");
        result.append("```\n\n");

        // Imports (essential for compilation)
        result.append("## Imports\n\n```java\n");
        if (psiClass.getQualifiedName() != null) {
            result.append("import ").append(psiClass.getQualifiedName()).append(";\n");
        }
        if (containingFile instanceof PsiJavaFile) {
            PsiImportList importList = ((PsiJavaFile) containingFile).getImportList();
            if (importList != null) {
                for (PsiImportStatement imp : importList.getImportStatements()) {
                    if (imp.getQualifiedName() != null) {
                        result.append("import ").append(imp.getQualifiedName()).append(";\n");
                    }
                }
            }
        }
        result.append("```\n\n");

        // Source Code (no line numbers - cleaner for LLM parsing)
        result.append("## Source\n\n```java\n");
        if (containingFile != null) {
            result.append(containingFile.getText());
            if (!containingFile.getText().endsWith("\n")) {
                result.append("\n");
            }
        }
        result.append("```\n\n");

        // Public Methods to test
        result.append("## Methods");
        if (!methodsToInclude.isEmpty()) {
            result.append(" (").append(String.join(", ", methodsToInclude)).append(")");
        }
        result.append("\n\n");
        for (PsiMethod method : psiClass.getMethods()) {
            if (method.hasModifierProperty(PsiModifier.PUBLIC) && !method.isConstructor()) {
                boolean include = methodsToInclude.isEmpty() || methodsToInclude.contains(method.getName());
                if (include) {
                    result.append("- `").append(getMethodSignature(method)).append("`\n");
                }
            }
        }
        result.append("\n");

        // User-selected dependencies (or auto-detected if empty)
        result.append("## Dependencies\n\n");
        if (includedDependencies != null && !includedDependencies.isEmpty()) {
            // Use user-selected dependencies
            JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
            for (String depName : includedDependencies) {
                PsiClass depClass = facade.findClass(depName, GlobalSearchScope.allScope(project));
                if (depClass != null) {
                    result.append("**").append(depClass.getName()).append("**: ");
                    appendCompactMethods(result, depClass, 5);
                    result.append("\n");
                }
            }
        } else {
            // Auto-detect dependencies
            Set<PsiClass> relatedClasses = new HashSet<>();
            com.zps.zest.core.ClassAnalyzer.collectRelatedClasses(psiClass, relatedClasses);
            int depCount = 0;
            for (PsiClass related : relatedClasses) {
                if (related.equals(psiClass)) continue;
                String qualifiedName = related.getQualifiedName();
                if (qualifiedName == null || isLibraryClass(qualifiedName)) continue;
                result.append("**").append(related.getName()).append("**: ");
                appendCompactMethods(result, related, 5);
                result.append("\n");
                if (++depCount >= 10) break;
            }
            if (depCount == 0) {
                result.append("_No project dependencies._\n");
            }
        }
        result.append("\n");

        // Additional context files (user-selected)
        if (additionalFiles != null && !additionalFiles.isEmpty()) {
            result.append("## Additional Context\n\n");
            for (String filePath : additionalFiles) {
                try {
                    String content = java.nio.file.Files.readString(java.nio.file.Paths.get(filePath));
                    String fileName = java.nio.file.Paths.get(filePath).getFileName().toString();
                    result.append("### ").append(fileName).append("\n\n```java\n");
                    result.append(content).append("\n```\n\n");
                } catch (Exception e) {
                    result.append("_Could not read: ").append(filePath).append("_\n\n");
                }
            }
        }

        // External deps (brief)
        Set<String> extDeps = com.zps.zest.core.ClassAnalyzer.detectExternalDependencies(psiClass);
        if (!extDeps.isEmpty()) {
            result.append("## External: ").append(String.join(", ", extDeps)).append("\n\n");
        }

        // Reference files (not embedded - sub-agents read on-demand to save tokens)
        boolean hasReferences = (rulesFile != null && !rulesFile.isEmpty()) ||
                                (exampleFiles != null && !exampleFiles.isEmpty());
        if (hasReferences) {
            result.append("## Reference Files (Read on-demand)\n\n");
            if (rulesFile != null && !rulesFile.isEmpty()) {
                result.append("- **Rules**: `").append(rulesFile).append("`\n");
            }
            if (exampleFiles != null && !exampleFiles.isEmpty()) {
                result.append("- **Examples**:\n");
                for (String exPath : exampleFiles) {
                    String fileName = java.nio.file.Paths.get(exPath).getFileName().toString();
                    result.append("  - `").append(exPath).append("` (").append(fileName).append(")\n");
                }
            }
            result.append("\n");
        }

        // REMINDER at end - repeat test location
        result.append("---\n\n");
        result.append("**SAVE TEST TO:** `").append(testFilePath).append("`\n");

        return result.toString();
    }

    private boolean isLibraryClass(String qualifiedName) {
        return qualifiedName.startsWith("java.") || qualifiedName.startsWith("javax.") ||
               qualifiedName.startsWith("org.slf4j.") || qualifiedName.startsWith("org.apache.") ||
               qualifiedName.startsWith("com.google.") || qualifiedName.startsWith("org.jetbrains.");
    }

    private void appendCompactMethods(StringBuilder sb, PsiClass psiClass, int maxMethods) {
        int count = 0;
        for (PsiMethod method : psiClass.getMethods()) {
            if (method.hasModifierProperty(PsiModifier.PUBLIC) && !method.isConstructor()) {
                if (count > 0) sb.append(", ");
                sb.append(method.getName()).append("()");
                if (++count >= maxMethods) {
                    sb.append(", ...");
                    break;
                }
            }
        }
    }

    /**
     * Loads test examples from project's .zest/test-examples/ directory.
     * This is framework-agnostic - each project provides its own examples.
     */
    /**
     * Detects the test source root for the project.
     * Checks common locations: src/test/java, test, tests
     */
    private String detectTestRoot(Project project) {
        if (project == null || project.getBasePath() == null) {
            return "src/test/java"; // default
        }
        String basePath = project.getBasePath();
        // Priority: project-specific dirs first, then Maven/Gradle default
        String[] testDirs = {"test", "tests", "src/test", "src/test/java"};

        // First pass: find first non-empty test directory
        java.nio.file.Path firstExisting = null;
        for (String dir : testDirs) {
            java.nio.file.Path testPath = java.nio.file.Paths.get(basePath, dir);
            if (java.nio.file.Files.exists(testPath) && java.nio.file.Files.isDirectory(testPath)) {
                if (firstExisting == null) {
                    firstExisting = testPath;
                }
                // Check if directory has any .java files (non-empty)
                try (var files = java.nio.file.Files.walk(testPath, 3)) {
                    boolean hasJavaFiles = files.anyMatch(p -> p.toString().endsWith(".java"));
                    if (hasJavaFiles) {
                        // Return absolute path to avoid ambiguity
                        return testPath.toAbsolutePath().toString().replace('\\', '/');
                    }
                } catch (Exception e) {
                    // Ignore errors, continue checking
                }
            }
        }

        // If all empty but one exists, use that
        if (firstExisting != null) {
            return firstExisting.toAbsolutePath().toString().replace('\\', '/');
        }

        // Default: return absolute path
        return java.nio.file.Paths.get(basePath, "src/test/java").toAbsolutePath().toString().replace('\\', '/');
    }

    private String loadProjectTestExamples(Project project) {
        if (project == null || project.getBasePath() == null) return null;

        java.nio.file.Path examplesDir = java.nio.file.Paths.get(project.getBasePath(), ".zest", "test-examples");
        if (!java.nio.file.Files.exists(examplesDir)) return null;

        StringBuilder examples = new StringBuilder();

        try {
            // Load rules.md if exists
            java.nio.file.Path rulesFile = examplesDir.resolve("rules.md");
            if (java.nio.file.Files.exists(rulesFile)) {
                examples.append(java.nio.file.Files.readString(rulesFile));
                examples.append("\n\n");
            }

            // Load all .java example files
            try (var files = java.nio.file.Files.list(examplesDir)) {
                files.filter(p -> p.toString().endsWith(".java"))
                     .sorted()
                     .forEach(file -> {
                         try {
                             String fileName = file.getFileName().toString();
                             examples.append("### Example: ").append(fileName).append("\n\n");
                             examples.append("```java\n");
                             examples.append(java.nio.file.Files.readString(file));
                             examples.append("\n```\n\n");
                         } catch (java.io.IOException e) {
                             // Skip unreadable files
                         }
                     });
            }
        } catch (java.io.IOException e) {
            return null;
        }

        return examples.length() > 0 ? examples.toString() : null;
    }

    /**
     * Gets method signature as a string.
     */
    private String getMethodSignature(PsiMethod method) {
        StringBuilder sig = new StringBuilder();
        PsiType returnType = method.getReturnType();
        if (returnType != null) {
            sig.append(returnType.getPresentableText()).append(" ");
        }
        sig.append(method.getName()).append("(");
        PsiParameter[] params = method.getParameterList().getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(params[i].getType().getPresentableText()).append(" ").append(params[i].getName());
        }
        sig.append(")");
        return sig.toString();
    }

    /**
     * Appends class signature (fields + method signatures, no bodies).
     */
    private void appendClassSignature(StringBuilder sb, PsiClass cls) {
        // Class declaration
        if (cls.isInterface()) {
            sb.append("interface ");
        } else {
            sb.append("class ");
        }
        sb.append(cls.getName());

        PsiClass superClass = cls.getSuperClass();
        if (superClass != null && !"Object".equals(superClass.getName())) {
            sb.append(" extends ").append(superClass.getName());
        }

        PsiClassType[] interfaces = cls.getImplementsListTypes();
        if (interfaces.length > 0) {
            sb.append(" implements ");
            for (int i = 0; i < interfaces.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(interfaces[i].getClassName());
            }
        }
        sb.append(" {\n");

        // Fields
        for (PsiField field : cls.getFields()) {
            if (field.getName().contains("$")) continue;
            sb.append("    ");
            if (field.hasModifierProperty(PsiModifier.PRIVATE)) sb.append("private ");
            if (field.hasModifierProperty(PsiModifier.PROTECTED)) sb.append("protected ");
            if (field.hasModifierProperty(PsiModifier.PUBLIC)) sb.append("public ");
            if (field.hasModifierProperty(PsiModifier.STATIC)) sb.append("static ");
            if (field.hasModifierProperty(PsiModifier.FINAL)) sb.append("final ");
            sb.append(field.getType().getPresentableText()).append(" ").append(field.getName()).append(";\n");
        }

        sb.append("\n");

        // Method signatures only
        for (PsiMethod method : cls.getMethods()) {
            if (method.isConstructor()) continue;
            sb.append("    ");
            if (method.hasModifierProperty(PsiModifier.PUBLIC)) sb.append("public ");
            if (method.hasModifierProperty(PsiModifier.PROTECTED)) sb.append("protected ");
            if (method.hasModifierProperty(PsiModifier.STATIC)) sb.append("static ");
            PsiType returnType = method.getReturnType();
            if (returnType != null) {
                sb.append(returnType.getPresentableText()).append(" ");
            }
            sb.append(method.getName()).append("(");
            PsiParameter[] params = method.getParameterList().getParameters();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i].getType().getPresentableText());
            }
            sb.append(");\n");
        }
        sb.append("}\n");
    }

    /**
     * Writes context to a temp file and returns the file path.
     */
    private String writeContextToTempFile(String content, String className, Project project) {
        try {
            // Use project's .zest directory
            String basePath = project.getBasePath();
            if (basePath == null) {
                basePath = System.getProperty("java.io.tmpdir");
            }
            File zestDir = new File(basePath, ".zest");
            if (!zestDir.exists()) {
                zestDir.mkdirs();
            }

            String simpleClassName = className.contains(".")
                    ? className.substring(className.lastIndexOf('.') + 1)
                    : className;
            File contextFile = new File(zestDir, simpleClassName + "-context.md");

            try (FileWriter writer = new FileWriter(contextFile)) {
                writer.write(content);
            }

            return contextFile.getAbsolutePath();
        } catch (IOException e) {
            LOG.error("Failed to write context file", e);
            return null;
        }
    }

    // ========== PSI Navigation Tool Schemas ==========

    private String buildFindUsagesSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    },
                    "className": {
                      "type": "string",
                      "description": "Fully qualified class name"
                    },
                    "memberName": {
                      "type": "string",
                      "description": "Optional method or field name. If omitted, finds usages of the class itself."
                    }
                  },
                  "required": ["projectPath", "className"]
                }
                """;
    }

    private String buildFindImplementationsSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    },
                    "className": {
                      "type": "string",
                      "description": "Fully qualified interface or class name"
                    },
                    "methodName": {
                      "type": "string",
                      "description": "Optional method name to find implementations of"
                    }
                  },
                  "required": ["projectPath", "className"]
                }
                """;
    }

    private String buildGetTypeHierarchySchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    },
                    "className": {
                      "type": "string",
                      "description": "Fully qualified class name"
                    }
                  },
                  "required": ["projectPath", "className"]
                }
                """;
    }

    private String buildGetCallHierarchySchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    },
                    "className": {
                      "type": "string",
                      "description": "Fully qualified class name"
                    },
                    "methodName": {
                      "type": "string",
                      "description": "Method name to analyze"
                    },
                    "callers": {
                      "type": "boolean",
                      "description": "If true, shows who calls this method. If false, shows what this method calls. Default: true"
                    }
                  },
                  "required": ["projectPath", "className", "methodName"]
                }
                """;
    }

    private String buildRenameSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    },
                    "className": {
                      "type": "string",
                      "description": "Fully qualified class name"
                    },
                    "memberName": {
                      "type": "string",
                      "description": "Optional method or field name to rename. If omitted, renames the class."
                    },
                    "newName": {
                      "type": "string",
                      "description": "The new name for the symbol"
                    }
                  },
                  "required": ["projectPath", "className", "newName"]
                }
                """;
    }

    private String buildGetMethodBodySchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    },
                    "className": {
                      "type": "string",
                      "description": "Fully qualified class name"
                    },
                    "methodName": {
                      "type": "string",
                      "description": "Method name to analyze"
                    }
                  },
                  "required": ["projectPath", "className", "methodName"]
                }
                """;
    }

    private String buildExtractConstantSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    },
                    "className": {
                      "type": "string",
                      "description": "Class name (simple like 'MyClass' or fully qualified like 'com.example.MyClass')"
                    },
                    "methodName": {
                      "type": "string",
                      "description": "Method containing the literal (call getMethodBody first to see available methods)"
                    },
                    "lineNumber": {
                      "type": "integer",
                      "description": "1-based line number from getMethodBody output (tolerates ±1 offset)"
                    },
                    "constantName": {
                      "type": "string",
                      "description": "Name for the new constant (UPPER_SNAKE_CASE, e.g., DEFAULT_TIMEOUT)"
                    },
                    "targetValue": {
                      "type": "string",
                      "description": "Optional: extract this substring from within a string literal"
                    }
                  },
                  "required": ["projectPath", "className", "methodName", "lineNumber", "constantName"]
                }
                """;
    }

    private String buildExtractMethodSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    },
                    "className": {
                      "type": "string",
                      "description": "Class name (simple like 'MyClass' or fully qualified like 'com.example.MyClass')"
                    },
                    "sourceMethodName": {
                      "type": "string",
                      "description": "Method containing the code (call getMethodBody first to see line numbers)"
                    },
                    "startLine": {
                      "type": "integer",
                      "description": "First line to extract (1-based, from getMethodBody output)"
                    },
                    "endLine": {
                      "type": "integer",
                      "description": "Last line to extract (1-based, inclusive)"
                    },
                    "newMethodName": {
                      "type": "string",
                      "description": "Name for the new method (camelCase, e.g., validateInput)"
                    }
                  },
                  "required": ["projectPath", "className", "sourceMethodName", "startLine", "endLine", "newMethodName"]
                }
                """;
    }

    private String buildSafeDeleteSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    },
                    "className": {
                      "type": "string",
                      "description": "Class name (simple like 'MyClass' or fully qualified like 'com.example.MyClass')"
                    },
                    "memberName": {
                      "type": "string",
                      "description": "Field or method name to delete (call findDeadCode first to see candidates)"
                    }
                  },
                  "required": ["projectPath", "className"]
                }
                """;
    }

    private String buildFindDeadCodeSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    },
                    "className": {
                      "type": "string",
                      "description": "Class name (simple like 'MyClass' or fully qualified like 'com.example.MyClass')"
                    }
                  },
                  "required": ["projectPath", "className"]
                }
                """;
    }

    private String buildMoveClassSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    },
                    "className": {
                      "type": "string",
                      "description": "Class name (simple like 'MyClass' or fully qualified like 'com.example.MyClass')"
                    },
                    "targetPackage": {
                      "type": "string",
                      "description": "Target package name (e.g., com.example.newpackage)"
                    }
                  },
                  "required": ["projectPath", "className", "targetPackage"]
                }
                """;
    }

    private String buildValidateCodeSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    },
                    "filePath": {
                      "type": "string",
                      "description": "Absolute path to the Java file to validate (e.g., 'src/test/java/com/example/MyServiceTest.java')"
                    }
                  },
                  "required": ["projectPath", "filePath"]
                }
                """;
    }

    private String buildGetProjectDependenciesSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to the IntelliJ project"
                    }
                  },
                  "required": ["projectPath"]
                }
                """;
    }

    // ========== PSI Navigation Tool Handlers ==========

    private McpSchema.CallToolResult handleFindUsages(String projectPath, String className, String memberName) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            PsiToolsService service = new PsiToolsService(project);
            PsiToolsService.UsagesResult result = service.findUsages(className, memberName);

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getError())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error finding usages", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleFindImplementations(String projectPath, String className, String methodName) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            PsiToolsService service = new PsiToolsService(project);
            PsiToolsService.ImplementationsResult result = service.findImplementations(className, methodName);

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getError())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error finding implementations", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleGetTypeHierarchy(String projectPath, String className) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            PsiToolsService service = new PsiToolsService(project);
            PsiToolsService.TypeHierarchyResult result = service.getTypeHierarchy(className);

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getError())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error getting type hierarchy", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleGetCallHierarchy(String projectPath, String className,
                                                             String methodName, boolean callers) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            PsiToolsService service = new PsiToolsService(project);
            PsiToolsService.CallHierarchyResult result = service.getCallHierarchy(className, methodName, callers);

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getError())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error getting call hierarchy", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    // ========== Refactoring Tool Handlers ==========

    private McpSchema.CallToolResult handleRename(String projectPath, String className,
                                                   String memberName, String newName) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            PsiToolsService service = new PsiToolsService(project);
            PsiToolsService.RefactoringResult result = service.rename(className, memberName, newName);

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getError())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error renaming", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleGetMethodBody(String projectPath, String className, String methodName) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            PsiToolsService service = new PsiToolsService(project);
            PsiToolsService.MethodBodyResult result = service.getMethodBody(className, methodName);

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getError())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error getting method body", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    // ========== New Refactoring Tool Handlers ==========

    private McpSchema.CallToolResult handleExtractConstant(String projectPath, String className,
                                                            String methodName, int lineNumber, String constantName,
                                                            String targetValue) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            PsiToolsService service = new PsiToolsService(project);
            PsiToolsService.RefactoringResult result = service.extractConstant(className, methodName, lineNumber, constantName, targetValue);

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getError())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error extracting constant", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleExtractMethod(String projectPath, String className,
                                                          String sourceMethodName, int startLine, int endLine, String newMethodName) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            PsiToolsService service = new PsiToolsService(project);
            PsiToolsService.RefactoringResult result = service.extractMethod(className, sourceMethodName, startLine, endLine, newMethodName);

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getError())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error extracting method", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleSafeDelete(String projectPath, String className, String memberName) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            PsiToolsService service = new PsiToolsService(project);
            PsiToolsService.SafeDeleteResult result = service.safeDelete(className, memberName);

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getError())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error in safe delete", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleFindDeadCode(String projectPath, String className) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            PsiToolsService service = new PsiToolsService(project);
            PsiToolsService.DeadCodeResult result = service.findDeadCode(className);

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getError())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error finding dead code", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleMoveClass(String projectPath, String className, String targetPackage) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            PsiToolsService service = new PsiToolsService(project);
            PsiToolsService.MoveClassResult result = service.moveClass(className, targetPackage);

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getError())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error moving class", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleValidateCode(String projectPath, String filePath) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            // Resolve file path (can be relative to project or absolute)
            java.io.File file = new java.io.File(filePath);
            if (!file.isAbsolute()) {
                file = new java.io.File(projectPath, filePath);
            }

            if (!file.exists()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("File not found: " + file.getAbsolutePath())),
                        true
                );
            }

            // Read file content
            String code = java.nio.file.Files.readString(file.toPath());

            // Extract class name from file name
            String fileName = file.getName();
            String className = fileName.endsWith(".java")
                    ? fileName.substring(0, fileName.length() - 5)
                    : fileName;

            TestCodeValidator.ValidationResult result = TestCodeValidator.validate(project, code, className);

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getErrorMessage())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error validating code", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleGetProjectDependencies(String projectPath) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            PsiToolsService service = new PsiToolsService(project);
            PsiToolsService.ProjectDependenciesResult result = service.getProjectDependencies();

            if (!result.isSuccess()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("❌ " + result.getError())),
                        true
                );
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toMarkdown())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error getting project dependencies", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleGetProjectJdk(String projectPath) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            // Get project SDK from IntelliJ
            Sdk sdk = ApplicationManager.getApplication().runReadAction(
                    (Computable<Sdk>) () -> ProjectRootManager.getInstance(project).getProjectSdk()
            );

            if (sdk == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent(
                                "# No JDK Configured\n\n" +
                                "No SDK is configured for this project in IntelliJ.\n\n" +
                                "**To fix:**\n" +
                                "1. Open Project Structure (Ctrl+Alt+Shift+S)\n" +
                                "2. Go to Project Settings → Project\n" +
                                "3. Set the Project SDK"
                        )),
                        true
                );
            }

            String jdkPath = sdk.getHomePath();
            String jdkVersion = sdk.getVersionString();
            String jdkName = sdk.getName();

            StringBuilder result = new StringBuilder();
            result.append("# Project JDK\n\n");
            result.append("| Property | Value |\n");
            result.append("|----------|-------|\n");
            result.append("| **Name** | ").append(jdkName != null ? jdkName : "Unknown").append(" |\n");
            result.append("| **Version** | ").append(jdkVersion != null ? jdkVersion : "Unknown").append(" |\n");
            result.append("| **JAVA_HOME** | `").append(jdkPath != null ? jdkPath : "Unknown").append("` |\n");
            result.append("\n## Shell Command Usage\n\n");

            if (jdkPath != null) {
                // Detect OS for proper command syntax
                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");
                String escapedPath = jdkPath.replace("\\", "/");

                if (isWindows) {
                    result.append("**Windows (PowerShell):**\n");
                    result.append("```powershell\n");
                    result.append("$env:JAVA_HOME=\"").append(jdkPath).append("\"; mvn test\n");
                    result.append("```\n\n");
                    result.append("**Windows (CMD):**\n");
                    result.append("```cmd\n");
                    result.append("set \"JAVA_HOME=").append(jdkPath).append("\" && mvn test\n");
                    result.append("```\n\n");
                }

                result.append("**Bash/Unix:**\n");
                result.append("```bash\n");
                result.append("JAVA_HOME=\"").append(escapedPath).append("\" mvn test\n");
                result.append("```\n\n");

                result.append("**Gradle:**\n");
                result.append("```bash\n");
                result.append("JAVA_HOME=\"").append(escapedPath).append("\" ./gradlew test\n");
                result.append("```\n");
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toString())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error getting project JDK", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

}