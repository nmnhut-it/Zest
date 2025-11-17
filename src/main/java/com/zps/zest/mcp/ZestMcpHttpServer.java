package com.zps.zest.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.zps.zest.testgen.tools.LookupClassTool;
import com.zps.zest.testgen.tools.LookupMethodTool;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.jackson.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * MCP HTTP Server for Zest using SSE transport over HTTP.
 * Provides IntelliJ project tools via HTTP endpoints using MCP protocol.
 *
 * The server creates two endpoints:
 * - Message endpoint (POST): Receives JSON-RPC requests from clients
 * - SSE endpoint (GET): Streams server-to-client events and notifications
 *
 * Per MCP spec, the HttpServletSseServerTransport handles both endpoints internally.
 */
public class ZestMcpHttpServer {
    private static final Logger LOG = Logger.getInstance(ZestMcpHttpServer.class);
    private static final String MESSAGE_ENDPOINT = "/mcp";

    private final McpSyncServer mcpServer;
    private final Server jettyServer;
    private final HttpServletSseServerTransportProvider transport;
    private final int port;

    public ZestMcpHttpServer(int port) {
        this.port = port;

        ObjectMapper objectMapper = new ObjectMapper();
        JacksonMcpJsonMapper mcpJsonMapper = new JacksonMcpJsonMapper(objectMapper);

        this.transport = HttpServletSseServerTransportProvider.builder()
                .jsonMapper(mcpJsonMapper)
                .messageEndpoint(MESSAGE_ENDPOINT)
                .build();

        this.mcpServer = McpServer.sync(transport)
                .jsonMapper(mcpJsonMapper)
                .serverInfo("zest-intellij-http-tools", "1.0.0")
                .jsonSchemaValidator(new DefaultJsonSchemaValidator())
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .logging()
                        .build())
                .build();

        registerTools();

        this.jettyServer = createJettyServer();

        LOG.info("Zest MCP HTTP Server created on port " + port);
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

        ServletHolder transportServlet = new ServletHolder(transport);
        context.addServlet(transportServlet, "/*");

        server.setHandler(context);

        return server;
    }

    private void registerTools() {
        ObjectMapper mapper = new ObjectMapper();
        JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(mapper);

        McpSchema.Tool currentFileTool = McpSchema.Tool.builder()
                .name("getCurrentFile")
                .description("Get the currently open file in the editor for a specific IntelliJ project")
                .inputSchema(jsonMapper, buildGetCurrentFileSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                currentFileTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    return handleGetCurrentFile(projectPath);
                }
        ));

        McpSchema.Tool lookupMethodTool = McpSchema.Tool.builder()
                .name("lookupMethod")
                .description("Look up method signatures using fully qualified class name and method name. Works with project classes, library JARs, and JDK classes.")
                .inputSchema(jsonMapper, buildLookupMethodSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                lookupMethodTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    String methodName = (String) arguments.get("methodName");
                    return handleLookupMethod(projectPath, className, methodName);
                }
        ));

        McpSchema.Tool lookupClassTool = McpSchema.Tool.builder()
                .name("lookupClass")
                .description("Look up class implementation using fully qualified class name. Works with project classes, library JARs, and JDK classes. For inner classes, use $ separator.")
                .inputSchema(jsonMapper, buildLookupClassSchema())
                .build();

        mcpServer.addTool(new McpServerFeatures.SyncToolSpecification(
                lookupClassTool,
                (exchange, arguments) -> {
                    String projectPath = (String) arguments.get("projectPath");
                    String className = (String) arguments.get("className");
                    return handleLookupClass(projectPath, className);
                }
        ));

        LOG.info("Registered 3 MCP tools: getCurrentFile, lookupMethod, lookupClass");
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

    private String buildLookupMethodSchema() {
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
                      "description": "Method name to find"
                    }
                  },
                  "required": ["projectPath", "className", "methodName"]
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

    private McpSchema.CallToolResult handleLookupMethod(String projectPath, String className, String methodName) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            LookupMethodTool tool = new LookupMethodTool(project);
            String result = tool.lookupMethod(className, methodName);

            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(result)), false);

        } catch (Exception e) {
            LOG.error("Error looking up method", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private McpSchema.CallToolResult handleLookupClass(String projectPath, String className) {
        try {
            Project project = findProject(projectPath);
            if (project == null) {
                return new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("Project not found: " + projectPath)),
                        false
                );
            }

            LookupClassTool tool = new LookupClassTool(project);
            String result = tool.lookupClass(className);

            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(result)), false);

        } catch (Exception e) {
            LOG.error("Error looking up class", e);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent("ERROR: " + e.getMessage())),
                    true
            );
        }
    }

    private Project findProject(String projectPath) {
        if (projectPath == null || projectPath.trim().isEmpty()) {
            return null;
        }

        try {
            Path requestedPath = Paths.get(projectPath).toAbsolutePath().normalize();

            Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
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
            LOG.warn("Invalid project path: " + projectPath, e);
        }

        return null;
    }

    public void start() throws Exception {
        jettyServer.start();
        LOG.info("✅ Zest MCP HTTP Server started successfully");
        LOG.info("📋 MCP endpoint: http://localhost:" + port + MESSAGE_ENDPOINT);
        LOG.info("🔧 Available tools: getCurrentFile, lookupMethod, lookupClass");
    }

    public void stop() throws Exception {
        if (jettyServer != null) {
            jettyServer.stop();
        }
        if (mcpServer != null) {
            mcpServer.close();
        }
        LOG.info("Zest MCP HTTP Server stopped");
    }

    public boolean isRunning() {
        return jettyServer != null && jettyServer.isRunning();
    }

    public int getPort() {
        return port;
    }
}