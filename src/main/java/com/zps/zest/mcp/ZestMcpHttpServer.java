package com.zps.zest.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiFunction;

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
    private final ToolRegistry toolRegistry;
    private final Gson gson;

    public ZestMcpHttpServer(int port) {
        this.port = port;
        this.toolRegistry = new ToolRegistry();
        this.gson = new GsonBuilder().setPrettyPrinting().create();

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
        // Populate the tool registry with all available tools
        populateToolRegistry();

        // Register only 3 meta-tools with MCP (for lazy loading)
        registerMetaTools();

        LOG.info("Registered " + toolRegistry.size() + " tools in registry, exposed via 3 meta-tools");
    }

    /**
     * Populate the ToolRegistry with all tool definitions.
     * Tools are NOT registered with MCP directly - they're invoked via callTool meta-tool.
     */
    private void populateToolRegistry() {
        // ========== Core IDE Tools ==========
        toolRegistry.register(
                "getCurrentFile",
                ToolRegistry.Category.IDE,
                "Get currently open file in editor",
                buildGetCurrentFileSchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    return handleGetCurrentFile(projectPath);
                }
        );

        toolRegistry.register(
                "lookupClass",
                ToolRegistry.Category.IDE,
                "Look up class/method signatures from project, JARs, JDK",
                buildLookupClassSchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String methodName = (String) arguments.get("methodName");
                    return handleLookupClass(projectPath, className, methodName);
                }
        );

        toolRegistry.register(
                "getJavaCodeUnderTest",
                ToolRegistry.Category.TESTING,
                "Get Java class analysis for test generation",
                buildGetJavaCodeUnderTestSchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String testType = (String) arguments.get("testType");
                    String methodFilter = (String) arguments.get("methodFilter");
                    return handleGetJavaCodeUnderTest(projectPath, className, testType, methodFilter);
                }
        );

        toolRegistry.register(
                "showFile",
                ToolRegistry.Category.IDE,
                "Open a file in IntelliJ editor",
                buildShowFileSchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String filePath = (String) arguments.get("filePath");
                    return handleShowFile(projectPath, filePath);
                }
        );

        // ========== Testing & Analysis Tools ==========
        toolRegistry.register(
                "validateCode",
                ToolRegistry.Category.TESTING,
                "Validate Java code for compilation errors",
                buildValidateCodeSchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String code = (String) arguments.get("code");
                    String className = (String) arguments.get("className");
                    return handleValidateCode(projectPath, code, className);
                }
        );

        toolRegistry.register(
                "analyzeMethodUsage",
                ToolRegistry.Category.TESTING,
                "Analyze method usage patterns in codebase",
                buildAnalyzeMethodUsageSchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String memberName = (String) arguments.get("memberName");
                    return handleAnalyzeMethodUsage(projectPath, className, memberName);
                }
        );

        // ========== User Interaction Tools ==========
        toolRegistry.register(
                "askUser",
                ToolRegistry.Category.INTERACTION,
                "Ask user a question via IntelliJ dialog",
                buildAskUserSchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String questionText = (String) arguments.get("questionText");
                    String questionType = (String) arguments.get("questionType");
                    @SuppressWarnings("unchecked")
                    List<Map<String, String>> options = (List<Map<String, String>>) arguments.get("options");
                    String header = (String) arguments.get("header");
                    return handleAskUser(projectPath, questionText, questionType, options, header);
                }
        );

        // ========== Refactoring Tools ==========
        toolRegistry.register(
                "analyzeRefactorability",
                ToolRegistry.Category.REFACTORING,
                "Analyze code for refactoring opportunities",
                buildAnalyzeRefactorabilitySchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String focusArea = (String) arguments.get("focusArea");
                    return handleAnalyzeRefactorability(projectPath, className, focusArea);
                }
        );

        // ========== Test Coverage Tools ==========
        toolRegistry.register(
                "getCoverageData",
                ToolRegistry.Category.COVERAGE,
                "Get test coverage data for a class",
                buildGetCoverageSchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    return handleGetCoverageData(projectPath, className);
                }
        );

        toolRegistry.register(
                "analyzeCoverage",
                ToolRegistry.Category.COVERAGE,
                "Analyze coverage and get improvement suggestions",
                buildGetCoverageSchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    return handleAnalyzeCoverage(projectPath, className);
                }
        );

        toolRegistry.register(
                "getTestInfo",
                ToolRegistry.Category.TESTING,
                "Get test class info: framework, test methods",
                buildGetCoverageSchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    return handleGetTestInfo(projectPath, className);
                }
        );

        // ========== Build Tools ==========
        toolRegistry.register(
                "getBuildInfo",
                ToolRegistry.Category.BUILD,
                "Get build system info: Gradle/Maven, SDK paths",
                buildProjectPathSchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    return handleGetBuildInfo(projectPath);
                }
        );

        // ========== PSI Navigation Tools ==========
        toolRegistry.register(
                "findUsages",
                ToolRegistry.Category.IDE,
                "Find all usages of a class/method/field",
                buildFindUsagesSchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String memberName = (String) arguments.get("memberName");
                    return handleFindUsages(projectPath, className, memberName);
                }
        );

        toolRegistry.register(
                "findImplementations",
                ToolRegistry.Category.IDE,
                "Find implementations of interface/abstract method",
                buildFindImplementationsSchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String methodName = (String) arguments.get("methodName");
                    return handleFindImplementations(projectPath, className, methodName);
                }
        );

        toolRegistry.register(
                "getTypeHierarchy",
                ToolRegistry.Category.IDE,
                "Get type hierarchy: supers, interfaces, subclasses",
                buildGetTypeHierarchySchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    return handleGetTypeHierarchy(projectPath, className);
                }
        );

        toolRegistry.register(
                "getCallHierarchy",
                ToolRegistry.Category.IDE,
                "Get call hierarchy: callers or callees of a method",
                buildGetCallHierarchySchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String methodName = (String) arguments.get("methodName");
                    Boolean callers = (Boolean) arguments.getOrDefault("callers", true);
                    return handleGetCallHierarchy(projectPath, className, methodName, callers);
                }
        );

        // ========== Refactoring Tools ==========
        toolRegistry.register(
                "rename",
                ToolRegistry.Category.REFACTORING,
                "Rename class/method/field with usage updates",
                buildRenameSchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String memberName = (String) arguments.get("memberName");
                    String newName = (String) arguments.get("newName");
                    return handleRename(projectPath, className, memberName, newName);
                }
        );

        toolRegistry.register(
                "getMethodBody",
                ToolRegistry.Category.REFACTORING,
                "Get method body with line numbers for refactoring",
                buildGetMethodBodySchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String methodName = (String) arguments.get("methodName");
                    return handleGetMethodBody(projectPath, className, methodName);
                }
        );

        toolRegistry.register(
                "extractConstant",
                ToolRegistry.Category.REFACTORING,
                "Extract literal to static final constant",
                buildExtractConstantSchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String methodName = (String) arguments.get("methodName");
                    int lineNumber = ((Number) arguments.get("lineNumber")).intValue();
                    String constantName = (String) arguments.get("constantName");
                    String targetValue = (String) arguments.get("targetValue");
                    return handleExtractConstant(projectPath, className, methodName, lineNumber, constantName, targetValue);
                }
        );

        toolRegistry.register(
                "extractMethod",
                ToolRegistry.Category.REFACTORING,
                "Extract code lines into a new method",
                buildExtractMethodSchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String sourceMethodName = (String) arguments.get("sourceMethodName");
                    int startLine = ((Number) arguments.get("startLine")).intValue();
                    int endLine = ((Number) arguments.get("endLine")).intValue();
                    String newMethodName = (String) arguments.get("newMethodName");
                    return handleExtractMethod(projectPath, className, sourceMethodName, startLine, endLine, newMethodName);
                }
        );

        toolRegistry.register(
                "safeDelete",
                ToolRegistry.Category.REFACTORING,
                "Safely delete unused method/field",
                buildSafeDeleteSchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String memberName = (String) arguments.get("memberName");
                    return handleSafeDelete(projectPath, className, memberName);
                }
        );

        toolRegistry.register(
                "findDeadCode",
                ToolRegistry.Category.REFACTORING,
                "Find unused methods and fields in a class",
                buildFindDeadCodeSchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    return handleFindDeadCode(projectPath, className);
                }
        );

        toolRegistry.register(
                "moveClass",
                ToolRegistry.Category.REFACTORING,
                "Move class to different package",
                buildMoveClassSchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String targetPackage = (String) arguments.get("targetPackage");
                    return handleMoveClass(projectPath, className, targetPackage);
                }
        );

        // ========== Build Tools ==========
        toolRegistry.register(
                "checkCompileJava",
                ToolRegistry.Category.BUILD,
                "Check Java file for compilation errors",
                buildCheckCompileJavaSchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String filePath = (String) arguments.get("filePath");
                    return handleCheckCompileJava(projectPath, filePath);
                }
        );

        toolRegistry.register(
                "optimizeImports",
                ToolRegistry.Category.BUILD,
                "Auto-fix imports in a Java file",
                buildCheckCompileJavaSchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String filePath = (String) arguments.get("filePath");
                    return handleOptimizeImports(projectPath, filePath);
                }
        );

        toolRegistry.register(
                "getProjectDependencies",
                ToolRegistry.Category.BUILD,
                "Get project dependencies from build files",
                buildGetProjectDependenciesSchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    return handleGetProjectDependencies(projectPath);
                }
        );

        toolRegistry.register(
                "getProjectJdk",
                ToolRegistry.Category.BUILD,
                "Get JDK path configured for the project",
                buildGetProjectDependenciesSchema(),
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    return handleGetProjectJdk(projectPath);
                }
        );

        // ========== Prompt Tools ==========
        toolRegistry.register(
                "readPrompt",
                ToolRegistry.Category.INTERACTION,
                "Read prompt file content from ~/.zest/prompts/",
                buildReadPromptSchema(),
                (exchange, arguments) -> {
                    String promptName = (String) arguments.get("promptName");
                    return handleReadPrompt(promptName);
                }
        );
    }

    /**
     * Register the 3 meta-tools with MCP for lazy loading.
     * These are the only tools visible to clients - all other tools are invoked via callTool.
     */
    private void registerMetaTools() {
        ObjectMapper mapper = new ObjectMapper();
        JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(mapper);

        // Meta-tool 1: Get available tools (compact index)
        McpSchema.Tool getAvailableToolsTool = McpSchema.Tool.builder()
                .name("getAvailableTools")
                .description("Get list of all available tools grouped by category. " +
                        "Returns compact JSON with tool names and summaries (~200 tokens vs ~13,500 for full schemas). " +
                        "Call getToolSchema(toolName) to get full parameter schema for a specific tool.")
                .inputSchema(jsonMapper, buildEmptySchema())
                .build();

        addToolToBoth(new McpServerFeatures.SyncToolSpecification(
                getAvailableToolsTool,
                (exchange, arguments) -> {
                    String json = toolRegistry.getAvailableToolsJson();
                    return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(json)), false);
                }
        ));

        // Meta-tool 2: Get tool schema (full schema on-demand)
        McpSchema.Tool getToolSchemaTool = McpSchema.Tool.builder()
                .name("getToolSchema")
                .description("Get full JSON schema for a specific tool. " +
                        "Use after calling getAvailableTools to see what tools exist. " +
                        "Returns: name, category, summary, and full inputSchema with all parameters.")
                .inputSchema(jsonMapper, buildGetToolSchemaSchema())
                .build();

        addToolToBoth(new McpServerFeatures.SyncToolSpecification(
                getToolSchemaTool,
                (exchange, arguments) -> {
                    String toolName = (String) arguments.get("toolName");
                    String json = toolRegistry.getToolSchema(toolName);
                    return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(json)), false);
                }
        ));

        // Meta-tool 3: Call tool (invoke any registered tool)
        McpSchema.Tool callToolTool = McpSchema.Tool.builder()
                .name("callTool")
                .description("Invoke any registered tool by name. " +
                        "WORKFLOW: 1) getAvailableTools → see tools, 2) getToolSchema → get params, 3) callTool → execute. " +
                        "Pass toolName and arguments object matching the tool's schema.")
                .inputSchema(jsonMapper, buildCallToolSchema())
                .build();

        addToolToBoth(new McpServerFeatures.SyncToolSpecification(
                callToolTool,
                (exchange, arguments) -> {
                    String toolName = (String) arguments.get("toolName");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> toolArgs = (Map<String, Object>) arguments.get("arguments");
                    if (toolArgs == null) {
                        toolArgs = new HashMap<>();
                    }
                    return toolRegistry.invoke(toolName, exchange, toolArgs);
                }
        ));

        LOG.info("Registered 3 meta-tools: getAvailableTools, getToolSchema, callTool");
    }

    private void registerPrompts() {
        // Prompts are lazy-loaded: we return file path, agent reads on-demand
        // Files stored in ~/.zest/prompts/ (initialized from bundled resources)

        // Ensure prompt files exist in ~/.zest/prompts/
        initializePromptFiles();

        registerLazyPrompt("review", "Code review prompt - reads from file",
                List.of(), PromptLoader.PromptName.REVIEW);

        registerLazyPrompt("explain", "Code explainer prompt - reads from file",
                List.of(), PromptLoader.PromptName.EXPLAIN);

        registerLazyPrompt("commit", "Git commit prompt - reads from file",
                List.of(), PromptLoader.PromptName.COMMIT);

        registerLazyPrompt("zest-test", "Test generation prompt - reads from file",
                List.of(), PromptLoader.PromptName.TEST_GENERATION);

        LOG.info("Registered 4 MCP prompts (lazy-loaded from ~/.zest/prompts/)");
    }

    /**
     * Initialize prompt files in ~/.zest/prompts/ from bundled resources.
     */
    private void initializePromptFiles() {
        Path promptsDir = Paths.get(System.getProperty("user.home"), ".zest", "prompts");
        try {
            Files.createDirectories(promptsDir);

            for (PromptLoader.PromptName name : PromptLoader.PromptName.values()) {
                Path targetPath = promptsDir.resolve(name.getFileName());
                if (!Files.exists(targetPath)) {
                    String content = PromptLoader.getPromptStatic(name);
                    Files.writeString(targetPath, content);
                    LOG.info("Initialized prompt file: " + targetPath);
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to initialize prompt files", e);
        }
    }

    /**
     * Register prompt that instructs agent to load full content via readPrompt tool.
     * This reduces initial token usage - full prompt loaded on-demand.
     */
    private void registerLazyPrompt(String name, String description, List<McpSchema.PromptArgument> arguments, PromptLoader.PromptName promptName) {
        McpSchema.Prompt prompt = new McpSchema.Prompt(name, description, arguments);

        addPromptToBoth(new McpServerFeatures.SyncPromptSpecification(
                prompt,
                (exchange, request) -> {
                    // Return strict instruction to load prompt via tool
                    String lazyContent = String.format("""
                            # CRITICAL: Load Full Prompt First

                            **You MUST call the readPrompt tool before proceeding.**

                            ## Step 1: Load the prompt (REQUIRED)
                            ```
                            callTool("readPrompt", {"promptName": "%s"})
                            ```

                            ## Step 2: Follow instructions strictly
                            After loading, you MUST follow the prompt instructions exactly as written.
                            Do NOT improvise or skip steps. The prompt file contains critical workflow details.

                            ---
                            **DO NOT PROCEED WITHOUT READING THE FULL PROMPT FILE.**
                            **DO NOT MAKE ASSUMPTIONS ABOUT WHAT THE PROMPT CONTAINS.**
                            """,
                            name);

                    McpSchema.TextContent textContent = new McpSchema.TextContent(lazyContent);
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
        LOG.info("🔧 Available tools (19): getCurrentFile, lookupClass, getJavaCodeUnderTest, showFile, findUsages, findImplementations, getTypeHierarchy, getCallHierarchy, rename, getMethodBody, extractConstant, extractMethod, safeDelete, findDeadCode, moveClass, checkCompileJava, optimizeImports, getProjectDependencies, getProjectJdk");
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

            // Initialize agent prompts in project (copies from bundled resources if not present)
            PromptLoader.getInstance(project).initializeProjectAgentPrompts();

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
            response.append("## ⚠️ WRITE TEST TO THIS PATH\n\n");
            response.append("```\n").append(testFilePath).append("\n```\n\n");
            response.append("## Files Already Created (DO NOT recreate)\n\n");
            response.append("| File | Status |\n");
            response.append("|------|--------|\n");
            response.append("| `").append(filePath).append("` | ✅ Context file (read this) |\n");
            response.append("| `.zest/prompts/agents/*.md` | ✅ Agent prompts (read-only) |\n");
            response.append("| `.zest/agents/` | 📁 Output directory for agents |\n\n");
            response.append("## Settings\n\n");
            response.append("- Test type: ").append(effectiveTestType).append("\n");
            response.append("- Methods: ").append(methodsToInclude.isEmpty() ? "all public" : String.join(", ", methodsToInclude)).append("\n\n");
            response.append("## ⚠️ IMPORTANT\n\n");
            response.append("**DO NOT create .md files** - they are already created above.\n");
            response.append("**DO** spawn sub-agents as per `zest-test` instructions.\n");
            response.append("**DO** read the context file for source code and imports.\n");

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

    private String buildValidateCodeSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to IntelliJ project"
                    },
                    "code": {
                      "type": "string",
                      "description": "Java code to validate"
                    },
                    "className": {
                      "type": "string",
                      "description": "Expected class name in the code"
                    }
                  },
                  "required": ["projectPath", "code", "className"]
                }
                """;
    }

    private String buildAnalyzeMethodUsageSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to IntelliJ project"
                    },
                    "className": {
                      "type": "string",
                      "description": "Fully qualified class name"
                    },
                    "memberName": {
                      "type": "string",
                      "description": "Method or field name to analyze"
                    }
                  },
                  "required": ["projectPath", "className", "memberName"]
                }
                """;
    }

    private String buildAskUserSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to IntelliJ project"
                    },
                    "questionText": {
                      "type": "string",
                      "description": "Question to ask the user"
                    },
                    "questionType": {
                      "type": "string",
                      "enum": ["SINGLE_CHOICE", "MULTI_CHOICE", "FREE_TEXT"],
                      "description": "Type of question"
                    },
                    "options": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "label": { "type": "string" },
                          "description": { "type": "string" }
                        }
                      },
                      "description": "Options for choice questions"
                    },
                    "header": {
                      "type": "string",
                      "description": "Optional header/title for the dialog"
                    }
                  },
                  "required": ["projectPath", "questionText", "questionType"]
                }
                """;
    }

    private String buildAnalyzeRefactorabilitySchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to IntelliJ project"
                    },
                    "className": {
                      "type": "string",
                      "description": "Fully qualified class name to analyze"
                    },
                    "focusArea": {
                      "type": "string",
                      "enum": ["ALL", "TESTABILITY", "COMPLEXITY", "CODE_SMELLS"],
                      "description": "Focus area for analysis"
                    }
                  },
                  "required": ["projectPath", "className"]
                }
                """;
    }

    private String buildGetCoverageSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to IntelliJ project"
                    },
                    "className": {
                      "type": "string",
                      "description": "Fully qualified class name to get coverage for"
                    }
                  },
                  "required": ["projectPath", "className"]
                }
                """;
    }

    private String buildProjectPathSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Absolute path to IntelliJ project"
                    }
                  },
                  "required": ["projectPath"]
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

    // ========== Testgen & Analysis Handlers ==========

    private McpSchema.CallToolResult handleValidateCode(String projectPath, String code, String className) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            TestCodeValidator.ValidationResult validation =
                    TestCodeValidator.validate(project, code, className);

            StringBuilder result = new StringBuilder();
            result.append("## Validation Result: ").append(className).append("\n\n");

            if (validation.compiles()) {
                result.append("**Status:** Code compiles successfully\n");
                result.append("**Errors:** 0\n");
            } else {
                result.append("**Status:** Compilation errors found\n");
                result.append("**Errors:** ").append(validation.getErrorCount()).append("\n\n");
                result.append("### Error Details\n\n");
                for (TestCodeValidator.CompilationError error : validation.getErrors()) {
                    result.append("- Line ").append(error.getStartLine())
                          .append(": ").append(error.getMessage()).append("\n");
                }
            }

            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(result.toString())), false);

        } catch (Exception e) {
            LOG.error("Error validating code", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleAnalyzeMethodUsage(String projectPath, String className, String memberName) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            if (memberName == null || memberName.trim().isEmpty()) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("ERROR: memberName is required for usage analysis")),
                        true
                );
            }

            String result = ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
                PsiClass psiClass = facade.findClass(className, GlobalSearchScope.allScope(project));

                if (psiClass == null) {
                    return "Class not found: " + className;
                }

                PsiMethod[] methods = psiClass.findMethodsByName(memberName, false);
                if (methods.length > 0) {
                    UsageAnalyzer analyzer = new UsageAnalyzer(project);
                    UsageContext usageContext = analyzer.analyzeMethod(methods[0]);
                    return usageContext.formatForLLM();
                }

                PsiField field = psiClass.findFieldByName(memberName, false);
                if (field != null) {
                    return "Field found: " + field.getName() + " (type: " + field.getType().getPresentableText() + ")\n" +
                           "Note: Usage analysis is currently only available for methods.";
                }

                return "Member not found: " + memberName + " in class " + className;
            });

            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(result)), false);

        } catch (Exception e) {
            LOG.error("Error analyzing method usage", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    // ========== Refactoring Handlers ==========

    private McpSchema.CallToolResult handleAskUser(
            String projectPath,
            String questionText,
            String questionType,
            List<Map<String, String>> options,
            String header
    ) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            com.google.gson.JsonObject result = com.zps.zest.mcp.refactor.AskUserToolHandler.askUser(
                    project, questionText, questionType, options, header
            );

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toString())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error in askUser", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: Failed to ask user: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleAnalyzeRefactorability(
            String projectPath,
            String className,
            String focusArea
    ) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            com.google.gson.JsonObject result = com.zps.zest.mcp.refactor.RefactorabilityAnalyzer.analyze(
                    project, className, focusArea
            );

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toString())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error in analyzeRefactorability", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: Failed to analyze refactorability: " + e.getMessage())),
                    true
            );
        }
    }

    // ========== Test Coverage Handlers ==========

    private McpSchema.CallToolResult handleGetCoverageData(String projectPath, String className) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            com.google.gson.JsonObject result = com.zps.zest.mcp.refactor.TestCoverageToolHandler.getCoverageData(
                    project, className
            );

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toString())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error in getCoverageData", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: Failed to get coverage data: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleAnalyzeCoverage(String projectPath, String className) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            com.google.gson.JsonObject result = com.zps.zest.mcp.refactor.TestCoverageToolHandler.analyzeCoverage(
                    project, className
            );

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toString())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error in analyzeCoverage", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: Failed to analyze coverage: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleGetTestInfo(String projectPath, String className) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            com.google.gson.JsonObject result = com.zps.zest.mcp.refactor.TestCoverageToolHandler.getTestInfo(
                    project, className
            );

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toString())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error in getTestInfo", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: Failed to get test info: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleGetBuildInfo(String projectPath) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            com.google.gson.JsonObject result = com.zps.zest.mcp.refactor.BuildToolDetector.detectBuildInfo(project);

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result.toString())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error in getBuildInfo", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: Failed to get build info: " + e.getMessage())),
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
        String fqn = psiClass.getQualifiedName() != null ? psiClass.getQualifiedName() : psiClass.getName();
        PsiFile containingFile = psiClass.getContainingFile();
        String simpleClassName = psiClass.getName() != null ? psiClass.getName() : "Unknown";
        String packageName = fqn != null && fqn.contains(".") ? fqn.substring(0, fqn.lastIndexOf('.')) : "";
        String testRoot = detectTestRoot(project);
        String testFilePath = testRoot + "/" + packageName.replace('.', '/') + "/" + simpleClassName + "Test.java";

        // === HEADER: Target class FQN ===
        result.append("# Test Context: `").append(fqn).append("`\n\n");
        result.append("> Pre-computed via static analysis. Context Agent: find what's MISSING (config, SQL, scripts).\n\n");

        // === SECTION 1: Test File Location - IMPORTANT ===
        result.append("## **OUTPUT: Write Test File To**\n\n");
        result.append("**You MUST write the generated test to this exact path:**\n\n");
        result.append("```\n").append(testFilePath).append("\n```\n\n");
        result.append("- Package: `").append(packageName).append("`\n");
        result.append("- Test class name: `").append(simpleClassName).append("Test`\n\n");

        // === SECTION 2: Imports (prompt references this name) ===
        result.append("## Imports\n\n```java\n");
        result.append("import ").append(fqn).append(";\n");
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

        // === SECTION 3: Methods to Test ===
        result.append("## Methods\n\n");
        for (PsiMethod method : psiClass.getMethods()) {
            if (method.hasModifierProperty(PsiModifier.PUBLIC) && !method.isConstructor()) {
                boolean include = methodsToInclude.isEmpty() || methodsToInclude.contains(method.getName());
                if (include) {
                    result.append("- `").append(getMethodSignature(method)).append("`\n");
                }
            }
        }
        result.append("\n");

        // === SECTION 4: Dependencies (FQN + signatures) ===
        result.append("## Dependencies\n\n");
        List<PsiClass> depsToShow = new ArrayList<>();
        if (includedDependencies != null && !includedDependencies.isEmpty()) {
            JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
            for (String depName : includedDependencies) {
                PsiClass depClass = facade.findClass(depName, GlobalSearchScope.allScope(project));
                if (depClass != null) depsToShow.add(depClass);
            }
        } else {
            Set<PsiClass> relatedClasses = new HashSet<>();
            com.zps.zest.core.ClassAnalyzer.collectRelatedClasses(psiClass, relatedClasses);
            int count = 0;
            for (PsiClass related : relatedClasses) {
                if (related.equals(psiClass)) continue;
                String qualifiedName = related.getQualifiedName();
                if (qualifiedName == null || isLibraryClass(qualifiedName)) continue;
                depsToShow.add(related);
                if (++count >= 10) break;
            }
        }

        if (depsToShow.isEmpty()) {
            result.append("_No project dependencies detected._\n");
        } else {
            for (PsiClass dep : depsToShow) {
                String depFqn = dep.getQualifiedName();
                result.append("### `").append(depFqn).append("`\n");
                appendDependencyDetails(result, dep);
                result.append("\n");
            }
        }
        result.append("\n");

        // === SECTION 5: External Dependencies ===
        Set<String> extDeps = com.zps.zest.core.ClassAnalyzer.detectExternalDependencies(psiClass);
        if (!extDeps.isEmpty()) {
            result.append("## External\n\n");
            result.append(String.join(", ", extDeps)).append("\n\n");
        }

        // === SECTION 6: Reference Files ===
        boolean hasReferences = (rulesFile != null && !rulesFile.isEmpty()) ||
                                (exampleFiles != null && !exampleFiles.isEmpty());
        if (hasReferences) {
            result.append("## References\n\n");
            if (rulesFile != null && !rulesFile.isEmpty()) {
                result.append("- Rules: `").append(rulesFile).append("`\n");
            }
            if (exampleFiles != null && !exampleFiles.isEmpty()) {
                for (String exPath : exampleFiles) {
                    result.append("- Example: `").append(exPath).append("`\n");
                }
            }
            result.append("\n");
        }

        // === SECTION 7: Additional Context Files ===
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

        // === SECTION 8: Source Code (at end - reference material) ===
        result.append("## Source\n\n```java\n");
        if (containingFile != null) {
            result.append(containingFile.getText());
            if (!containingFile.getText().endsWith("\n")) {
                result.append("\n");
            }
        }
        result.append("```\n\n");

        // === END ANCHOR ===
        result.append("---\n");
        result.append("**TEST FILE:** `").append(testFilePath).append("`\n");

        return result.toString();
    }

    /**
     * Appends detailed dependency info: constructors and key public methods.
     */
    private void appendDependencyDetails(StringBuilder sb, PsiClass depClass) {
        // Constructors
        PsiMethod[] constructors = depClass.getConstructors();
        if (constructors.length > 0) {
            sb.append("- Constructors: ");
            int count = 0;
            for (PsiMethod ctor : constructors) {
                if (ctor.hasModifierProperty(PsiModifier.PUBLIC)) {
                    if (count > 0) sb.append(", ");
                    sb.append("`new ").append(depClass.getName()).append("(");
                    PsiParameter[] params = ctor.getParameterList().getParameters();
                    for (int i = 0; i < params.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(params[i].getType().getPresentableText());
                    }
                    sb.append(")`");
                    if (++count >= 2) break;
                }
            }
            sb.append("\n");
        }
        // Key methods
        sb.append("- Methods: ");
        appendCompactMethods(sb, depClass, 5);
        sb.append("\n");
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

    private String buildCheckCompileJavaSchema() {
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

    // ========== Meta-Tool Schemas (for lazy loading) ==========

    private String buildEmptySchema() {
        return """
                {
                  "type": "object",
                  "properties": {},
                  "required": []
                }
                """;
    }

    private String buildGetToolSchemaSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "toolName": {
                      "type": "string",
                      "description": "Name of the tool to get schema for (from getAvailableTools)"
                    }
                  },
                  "required": ["toolName"]
                }
                """;
    }

    private String buildCallToolSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "toolName": {
                      "type": "string",
                      "description": "Name of the tool to invoke"
                    },
                    "arguments": {
                      "type": "object",
                      "description": "Arguments object matching the tool's schema (get via getToolSchema)"
                    }
                  },
                  "required": ["toolName", "arguments"]
                }
                """;
    }

    private String buildReadPromptSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "promptName": {
                      "type": "string",
                      "description": "Name of the prompt to read: review, explain, commit, or test-generation"
                    }
                  },
                  "required": ["promptName"]
                }
                """;
    }

    // ========== Prompt Handler ==========

    private McpSchema.CallToolResult handleReadPrompt(String promptName) {
        try {
            // Map prompt name to file name
            String fileName = switch (promptName.toLowerCase()) {
                case "review" -> "review.md";
                case "explain" -> "explain.md";
                case "commit" -> "commit.md";
                case "test-generation", "zest-test", "test" -> "test-generation.md";
                default -> promptName.endsWith(".md") ? promptName : promptName + ".md";
            };

            Path promptPath = Paths.get(System.getProperty("user.home"), ".zest", "prompts", fileName);

            if (!Files.exists(promptPath)) {
                // Try to initialize from bundled
                initializePromptFiles();
            }

            if (!Files.exists(promptPath)) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Prompt not found: " + promptName +
                                "\nAvailable: review, explain, commit, test-generation")),
                        true
                );
            }

            String content = Files.readString(promptPath);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(content)),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error reading prompt: " + promptName, e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("Error reading prompt: " + e.getMessage())),
                    true
            );
        }
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

    private McpSchema.CallToolResult handleCheckCompileJava(String projectPath, String filePath) {
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

    private McpSchema.CallToolResult handleOptimizeImports(String projectPath, String filePath) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        true
                );
            }

            // Resolve file path
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

            // Find the PsiFile
            com.intellij.openapi.vfs.VirtualFile virtualFile =
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(file);
            if (virtualFile == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Cannot find virtual file: " + file.getAbsolutePath())),
                        true
                );
            }

            final java.io.File finalFile = file;
            String result = com.intellij.openapi.application.ApplicationManager.getApplication()
                    .runReadAction((com.intellij.openapi.util.Computable<String>) () -> {
                        com.intellij.psi.PsiFile psiFile =
                                com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile);
                        if (psiFile == null || !(psiFile instanceof com.intellij.psi.PsiJavaFile)) {
                            return "ERROR: Not a Java file: " + finalFile.getAbsolutePath();
                        }

                        com.intellij.psi.PsiJavaFile javaFile = (com.intellij.psi.PsiJavaFile) psiFile;

                        // Count imports before
                        int importsBefore = javaFile.getImportList() != null
                                ? javaFile.getImportList().getAllImportStatements().length : 0;

                        return "BEFORE:" + importsBefore;
                    });

            if (result.startsWith("ERROR:")) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent(result)),
                        true
                );
            }

            int importsBefore = Integer.parseInt(result.replace("BEFORE:", ""));

            // Run optimize imports in write action
            final com.intellij.openapi.vfs.VirtualFile vf = virtualFile;
            java.util.concurrent.atomic.AtomicReference<String> optimizeResult = new java.util.concurrent.atomic.AtomicReference<>();

            com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait(() -> {
                WriteCommandAction.runWriteCommandAction(project, () -> {
                    try {
                        com.intellij.psi.PsiFile psiFile =
                                com.intellij.psi.PsiManager.getInstance(project).findFile(vf);
                        if (psiFile instanceof com.intellij.psi.PsiJavaFile) {
                            com.intellij.psi.PsiJavaFile javaFile = (com.intellij.psi.PsiJavaFile) psiFile;

                            // Use JavaCodeStyleManager to optimize imports
                            com.intellij.psi.codeStyle.JavaCodeStyleManager styleManager =
                                    com.intellij.psi.codeStyle.JavaCodeStyleManager.getInstance(project);

                            // This adds missing imports and removes unused ones
                            styleManager.optimizeImports(javaFile);
                            styleManager.shortenClassReferences(javaFile);

                            // Save the file
                            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments();

                            // Count imports after
                            int importsAfter = javaFile.getImportList() != null
                                    ? javaFile.getImportList().getAllImportStatements().length : 0;

                            optimizeResult.set("SUCCESS:" + importsAfter);
                        }
                    } catch (Exception e) {
                        optimizeResult.set("ERROR:" + e.getMessage());
                    }
                });
            });

            String optimizeResultStr = optimizeResult.get();
            if (optimizeResultStr == null || optimizeResultStr.startsWith("ERROR:")) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Failed to optimize imports: " +
                                (optimizeResultStr != null ? optimizeResultStr : "unknown error"))),
                        true
                );
            }

            int importsAfter = Integer.parseInt(optimizeResultStr.replace("SUCCESS:", ""));
            int diff = importsAfter - importsBefore;

            StringBuilder sb = new StringBuilder();
            sb.append("# Import Optimization Complete\n\n");
            sb.append("- **File**: ").append(file.getName()).append("\n");
            sb.append("- **Imports before**: ").append(importsBefore).append("\n");
            sb.append("- **Imports after**: ").append(importsAfter).append("\n");
            sb.append("- **Change**: ").append(diff >= 0 ? "+" : "").append(diff).append("\n\n");

            if (diff > 0) {
                sb.append("✅ Added missing imports. Run `checkCompileJava` to check for remaining errors.\n");
            } else if (diff < 0) {
                sb.append("✅ Removed unused imports.\n");
            } else {
                sb.append("ℹ️ No import changes needed.\n");
            }

            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(sb.toString())),
                    false
            );

        } catch (Exception e) {
            LOG.error("Error optimizing imports", e);
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