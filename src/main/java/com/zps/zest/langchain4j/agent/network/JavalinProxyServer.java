package com.zps.zest.langchain4j.agent.network;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.tools.CodeExplorationTool;
import com.zps.zest.langchain4j.tools.CodeExplorationToolRegistry;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.plugin.bundled.CorsPluginConfig;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Javalin-based proxy server that matches mcpo format exactly.
 */
public class JavalinProxyServer {
    private static final Logger LOG = Logger.getInstance(JavalinProxyServer.class);
    private static final Gson GSON = new Gson();
    
    private final Project project;
    private final int port;
    private final AgentProxyConfiguration config;
    private Javalin app;
    
    // For notifications (reuse from original)
    private final AgentProxyServer delegateServer;
    
    public JavalinProxyServer(Project project, int port, AgentProxyConfiguration config) {
        this.project = project;
        this.port = port;
        this.config = config != null ? config : AgentProxyConfiguration.getDefault();
        // Create delegate for reusing exploration logic
        // Note: The delegate doesn't actually start its own HTTP server when used this way
        this.delegateServer = new AgentProxyServer(project, port, config);
    }
    
    public AgentProxyServer getDelegateServer() {
        return delegateServer;
    }
    
    public void start() {
        try {
            app = Javalin.create(config -> {
                // Enable CORS
                config.plugins.enableCors(cors -> {
                    cors.add(CorsPluginConfig::anyHost);
                });
                
                // Set max request size if needed for large code explorations
                config.http.maxRequestSize = ProxyServerConstants.MAX_REQUEST_SIZE;
                
                // Disable Jackson to avoid classloader conflicts
                config.jsonMapper(new GsonMapper());
            });
            
            // Add global exception handler
            app.exception(Exception.class, (e, ctx) -> {
                LOG.error("Unhandled exception in request: " + ctx.path(), e);
                sendJson(ctx, 500, Map.of(
                    "message", "Internal server error",
                    "error", e.getMessage(),
                    "path", ctx.path()
                ));
            });
            
            setupRoutes();
            
            app.start(port);
            LOG.info("Javalin Proxy Server started on port " + port);
            LOG.info("OpenAPI spec available at: http://localhost:" + port + ProxyServerConstants.OPENAPI_PATH);
            LOG.info("Swagger UI available at: http://localhost:" + port + ProxyServerConstants.SWAGGER_UI_PATH);
            LOG.info("ReDoc available at: http://localhost:" + port + ProxyServerConstants.REDOC_PATH);
        } catch (Exception e) {
            LOG.error("Failed to start Javalin server", e);
            throw new RuntimeException("Failed to start server: " + e.getMessage(), e);
        }
    }
    
    private void setupRoutes() {
        // Add request logging
        app.before(ctx -> {
            LOG.info("Request: " + ctx.method() + " " + ctx.path());
            if (!ctx.body().isEmpty()) {
                LOG.debug("Request body: " + ctx.body());
            }
        });
        
        // OpenAPI endpoint
        app.get(ProxyServerConstants.OPENAPI_PATH, this::handleOpenApi);
        
        // Interactive API documentation
        app.get(ProxyServerConstants.SWAGGER_UI_PATH, this::handleSwaggerUI);
        app.get(ProxyServerConstants.REDOC_PATH, this::handleReDoc);
        
        // Health check
        app.get(ProxyServerConstants.HEALTH_PATH, ctx -> {
            Map<String, Object> health = new HashMap<>();
            health.put("status", "ok");
            health.put("service", ProxyServerConstants.SERVICE_NAME);
            health.put("port", port);
            health.put("project", project.getName());
            sendJson(ctx, health);
        });
        
        // Debug endpoint
        app.get(ProxyServerConstants.DEBUG_PATH, ctx -> {
            Map<String, Object> debug = new HashMap<>();
            debug.put("project", project != null ? project.getName() : "null");
            debug.put("projectPath", project != null ? project.getBasePath() : "null");
            debug.put("delegateServer", delegateServer != null ? "initialized" : "null");
            
            try {
                CodeExplorationToolRegistry registry = project != null ? 
                    project.getService(CodeExplorationToolRegistry.class) : null;
                debug.put("toolRegistry", registry != null ? "available" : "null");
                
                if (registry != null) {
                    List<String> toolNames = new ArrayList<>();
                    for (CodeExplorationTool tool : registry.getAllTools()) {
                        toolNames.add(tool.getName());
                    }
                    debug.put("availableTools", toolNames);
                }
            } catch (Exception e) {
                debug.put("toolRegistryError", e.getMessage());
            }
            
            sendJson(ctx, debug);
        });
        
        // Orchestration endpoints (mcpo format)
        app.post("/zest/explore_code", this::handleExploreCode);
        app.post("/zest/execute_tool", this::handleExecuteTool);
        app.post("/zest/list_tools", this::handleListTools);
        app.post("/zest/augment_query", this::handleAugmentQuery);
        app.post("/zest/get_config", this::handleGetConfig);
        app.post("/zest/update_config", this::handleUpdateConfig);
        app.post("/zest/status", this::handleStatus);
        app.post("/zest/get_current_context", this::handleGetCurrentContext);
        
        // Register all tool endpoints dynamically
        CodeExplorationToolRegistry registry = project.getService(CodeExplorationToolRegistry.class);
        Set<String> orchestrationEndpoints = ProxyServerConstants.ORCHESTRATION_ENDPOINTS;
        
        for (CodeExplorationTool tool : registry.getAllTools()) {
            // Skip if this tool name conflicts with an orchestration endpoint
            if (orchestrationEndpoints.contains(tool.getName())) {
                LOG.warn("Skipping tool '" + tool.getName() + "' as it conflicts with orchestration endpoint");
                continue;
            }
            
            String path = ProxyServerConstants.API_BASE_PATH + "/" + tool.getName();
            app.post(path, ctx -> handleToolExecution(ctx, tool));
            LOG.info("Registered tool endpoint: " + path);
        }
    }
    
    public void stop() {
        if (app != null) {
            app.stop();
            LOG.info("Javalin Proxy Server stopped");
        }
    }
    
    // Helper methods for JSON responses
    private void sendJson(Context ctx, Object response) {
        ctx.result(GSON.toJson(response));
        ctx.contentType("application/json");
    }
    
    private void sendJson(Context ctx, int status, Object response) {
        ctx.status(status);
        sendJson(ctx, response);
    }
    
    // OpenAPI spec handler
    private void handleOpenApi(Context ctx) {
        try {
            handleCors(ctx);
            OpenApiGenerator generator = new OpenApiGenerator(project, port);
            JsonObject spec = generator.generateOpenApiSpec();
            
            ctx.result(spec.toString());
            ctx.contentType("application/json");
        } catch (Exception e) {
            LOG.error("Error generating OpenAPI spec", e);
            sendJson(ctx, 500, Map.of("message", "Error generating OpenAPI spec: " + e.getMessage()));
        }
    }
    
    private void handleCors(Context ctx) {
        ctx.header("Access-Control-Allow-Origin", "*");
        ctx.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }
    
    // Swagger UI handler
    private void handleSwaggerUI(Context ctx) {
        ctx.html(ProxyServerConstants.getSwaggerUIHtml());
    }
    
    // ReDoc handler
    private void handleReDoc(Context ctx) {
        ctx.html(ProxyServerConstants.getReDocHtml());
    }
    
    // Explore code handler
    private void handleExploreCode(Context ctx) {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        
        try {
            // Check if delegate server is available
            if (delegateServer == null) {
                sendJson(ctx, 503, Map.of("message", "Exploration service not available"));
                return;
            }
            
            // Parse request body
            JsonObject request;
            try {
                request = JsonParser.parseString(ctx.body()).getAsJsonObject();
            } catch (Exception e) {
                sendJson(ctx, 400, Map.of("message", "Invalid JSON in request body"));
                return;
            }
            
            if (!request.has("query")) {
                sendJson(ctx, 400, Map.of("message", "Missing required field: query"));
                return;
            }
            
            // Validate project name if provided
            if (request.has("projectNameCamelCase")) {
                String providedProjectName = request.get("projectNameCamelCase").getAsString();
                String expectedProjectName = toCamelCase(project.getName());
                
                if (!providedProjectName.equals(expectedProjectName)) {
                    LOG.warn("Project name mismatch: provided=" + providedProjectName + 
                             ", expected=" + expectedProjectName);
                    // For now, log the mismatch but don't reject the request
                    // This allows for gradual migration
                }
            }
            
            String query = request.get("query").getAsString();
            boolean generateReport = request.has("generateReport") && 
                                   request.get("generateReport").getAsBoolean();
            
            // Get optional configuration overrides
            AgentProxyConfiguration requestConfig = config;
            if (request.has("config")) {
                requestConfig = mergeConfig(config, request.getAsJsonObject("config"));
            }
            
            // Notify request started
            if (delegateServer != null) {
                for (AgentProxyServer.RequestListener listener : delegateServer.getRequestListeners()) {
                    listener.onRequestStarted(requestId, "/zest/explore_code", "POST");
                }
            }
            
            // Reuse the exploration logic from AgentProxyServer
            CompletableFuture<JsonObject> future = delegateServer.exploreAsync(query, generateReport, requestConfig, requestId);
            JsonObject result = future.get(requestConfig.getTimeoutSeconds(), TimeUnit.SECONDS);
            
            // Format response for mcpo compatibility
            Map<String, Object> response = new HashMap<>();
            
            if (result.get("success").getAsBoolean()) {
                if (result.has("report")) {
                    // Convert report to expected format
                    JsonObject report = result.getAsJsonObject("report");
                    response.put("summary", report.get("summary").getAsString());
                    response.put("discovered_elements", GSON.fromJson(report.get("discoveredElements"), List.class));
                    response.put("timestamp", report.get("timestamp").getAsString());
                    response.put("original_query", report.get("originalQuery").getAsString());
                    
                    if (report.has("structuredContext")) {
                        response.put("structured_context", report.get("structuredContext").getAsString());
                    }
                    
                    if (report.has("codePieces")) {
                        response.put("code_pieces", GSON.fromJson(report.get("codePieces"), Map.class));
                    }
                } else {
                    response.put("summary", result.get("summary").getAsString());
                    response.put("rounds", result.get("rounds").getAsInt());
                    
                    if (result.has("toolSummary")) {
                        response.put("tool_summary", GSON.fromJson(result.get("toolSummary"), Map.class));
                    }
                }
                
                sendJson(ctx, response);
                
                // Notify request completed
                if (delegateServer != null) {
                    for (AgentProxyServer.RequestListener listener : delegateServer.getRequestListeners()) {
                        listener.onRequestCompleted(requestId, 200, GSON.toJson(response));
                    }
                }
            } else {
                String error = result.get("error").getAsString();
                sendJson(ctx, 500, Map.of("message", error));
                
                // Notify request failed
                if (delegateServer != null) {
                    for (AgentProxyServer.RequestListener listener : delegateServer.getRequestListeners()) {
                        listener.onRequestFailed(requestId, error);
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Error in explore_code", e);
            sendJson(ctx, 500, Map.of("message", e.getMessage()));
            
            // Notify request failed
            if (delegateServer != null) {
                for (AgentProxyServer.RequestListener listener : delegateServer.getRequestListeners()) {
                    listener.onRequestFailed(requestId, e.getMessage());
                }
            }
        }
    }
    
    // Helper method from AgentProxyServer
    private AgentProxyConfiguration mergeConfig(AgentProxyConfiguration base, JsonObject updates) {
        AgentProxyConfiguration merged = base.copy();
        merged.updateFromJson(updates);
        return merged;
    }
    
    /**
     * Converts a string to camelCase format.
     */
    private String toCamelCase(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        
        // Replace non-alphanumeric characters with spaces
        String cleaned = input.replaceAll("[^a-zA-Z0-9]", " ");
        
        // Split by spaces and process
        String[] words = cleaned.split("\\s+");
        StringBuilder result = new StringBuilder();
        
        for (int i = 0; i < words.length; i++) {
            String word = words[i].trim();
            if (!word.isEmpty()) {
                if (i == 0) {
                    // First word is lowercase
                    result.append(word.substring(0, 1).toLowerCase());
                    if (word.length() > 1) {
                        result.append(word.substring(1).toLowerCase());
                    }
                } else {
                    // Subsequent words have first letter uppercase
                    result.append(word.substring(0, 1).toUpperCase());
                    if (word.length() > 1) {
                        result.append(word.substring(1).toLowerCase());
                    }
                }
            }
        }
        
        return result.toString();
    }
    
    // Execute tool handler
    private void handleExecuteTool(Context ctx) {
        try {
            JsonObject request = JsonParser.parseString(ctx.body()).getAsJsonObject();
            String toolName = request.get("tool").getAsString();
            JsonObject parameters = request.has("parameters") ? 
                request.getAsJsonObject("parameters") : new JsonObject();
            
            CodeExplorationToolRegistry registry = project.getService(CodeExplorationToolRegistry.class);
            CodeExplorationTool tool = registry.getTool(toolName);
            
            if (tool == null) {
                sendJson(ctx, 404, Map.of("message", "Tool not found: " + toolName));
                return;
            }
            
            CodeExplorationTool.ToolResult result = tool.execute(parameters);
            
            Map<String, Object> response = new HashMap<>();
            if (result.isSuccess()) {
                response.put("result", result.getContent());
                if (result.getMetadata() != null) {
                    response.put("metadata", GSON.fromJson(result.getMetadata().toString(), Map.class));
                }
            } else {
                response.put("error", result.getError());
            }
            
            sendJson(ctx, response);
        } catch (Exception e) {
            sendJson(ctx, 500, Map.of("message", e.getMessage()));
        }
    }
    
    // List tools handler
    private void handleListTools(Context ctx) {
        try {
            CodeExplorationToolRegistry registry = project.getService(CodeExplorationToolRegistry.class);
            
            if (registry == null) {
                sendJson(ctx, 503, Map.of("message", "Tool registry not available"));
                return;
            }
            
            JsonArray toolsArray = new JsonArray();
            for (CodeExplorationTool tool : registry.getAllTools()) {
                JsonObject toolInfo = new JsonObject();
                toolInfo.addProperty("name", tool.getName());
                toolInfo.addProperty("description", tool.getDescription());
                toolInfo.add("inputSchema", tool.getParameterSchema());
                toolsArray.add(toolInfo);
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("tools", GSON.fromJson(toolsArray.toString(), Object.class));
            
            sendJson(ctx, response);
        } catch (Exception e) {
            LOG.error("Error listing tools", e);
            sendJson(ctx, 500, Map.of("message", "Error listing tools: " + e.getMessage()));
        }
    }
    
    // Augment query handler
    private void handleAugmentQuery(Context ctx) {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        
        try {
            JsonObject request = JsonParser.parseString(ctx.body()).getAsJsonObject();
            String query = request.get("query").getAsString();
            
            // Execute augmentation using delegate
            CompletableFuture<JsonObject> future = delegateServer.augmentQueryAsync(query, requestId);
            JsonObject result = future.get(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            
            // Format response for mcpo compatibility
            Map<String, Object> response = new HashMap<>();
            response.put("augmented_query", result.get("augmentedQuery").getAsString());
            response.put("original_query", result.get("originalQuery").getAsString());
            response.put("context", GSON.fromJson(result.get("explorationSummary").toString(), Map.class));
            
            sendJson(ctx, response);
        } catch (Exception e) {
            sendJson(ctx, 500, Map.of("message", e.getMessage()));
        }
    }
    
    // Get config handler
    private void handleGetConfig(Context ctx) {
        sendJson(ctx, GSON.fromJson(config.toJson().toString(), Map.class));
    }
    
    // Update config handler
    private void handleUpdateConfig(Context ctx) {
        try {
            JsonObject updates = JsonParser.parseString(ctx.body()).getAsJsonObject();
            config.updateFromJson(updates);
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "updated");
            response.put("config", GSON.fromJson(config.toJson().toString(), Map.class));
            
            sendJson(ctx, response);
        } catch (Exception e) {
            sendJson(ctx, 400, Map.of("message", "Invalid configuration: " + e.getMessage()));
        }
    }
    
    // Status handler
    private void handleStatus(Context ctx) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "running");
        response.put("project", project.getName());
        response.put("projectPath", project.getBasePath());
        response.put("indexed", project.isInitialized() && !project.isDisposed());
        response.put("config", GSON.fromJson(config.toJson().toString(), Map.class));
        
        sendJson(ctx, response);
    }
    
    // Get current context handler
    private void handleGetCurrentContext(Context ctx) {
        CodeExplorationToolRegistry registry = project.getService(CodeExplorationToolRegistry.class);
        
        // First try to find a tool named "get_current_context"
        CodeExplorationTool tool = registry.getTool("get_current_context");
        
        if (tool != null) {
            CodeExplorationTool.ToolResult result = tool.execute(new JsonObject());
            
            if (result.isSuccess()) {
                try {
                    JsonObject response = JsonParser.parseString(result.getContent()).getAsJsonObject();
                    sendJson(ctx, GSON.fromJson(response.toString(), Map.class));
                } catch (Exception e) {
                    sendJson(ctx, Map.of("context", result.getContent()));
                }
            } else {
                sendJson(ctx, Map.of("error", result.getError()));
            }
        } else {
            // Fallback: Try to get current file info
            Map<String, Object> context = new HashMap<>();
            context.put("project", project.getName());
            context.put("projectPath", project.getBasePath());
            context.put("message", "No current context tool available");
            sendJson(ctx, context);
        }
    }
    
    // Generic tool execution handler
    private void handleToolExecution(Context ctx, CodeExplorationTool tool) {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        
        // Notify request started if delegate is available
        if (delegateServer != null) {
            for (AgentProxyServer.RequestListener listener : delegateServer.getRequestListeners()) {
                listener.onRequestStarted(requestId, "/zest/" + tool.getName(), "POST");
            }
        }
        
        try {
            JsonObject parameters = new JsonObject();
            
            if (!ctx.body().isEmpty()) {
                try {
                    parameters = JsonParser.parseString(ctx.body()).getAsJsonObject();
                } catch (Exception e) {
                    sendJson(ctx, 400, Map.of("message", "Invalid JSON in request body"));
                    return;
                }
            }
            
            LOG.info("Executing tool via Javalin: " + tool.getName() + " with parameters: " + parameters);
            CodeExplorationTool.ToolResult result = tool.execute(parameters);
            
            // Notify tool execution if delegate is available
            if (delegateServer != null) {
                for (AgentProxyServer.RequestListener listener : delegateServer.getRequestListeners()) {
                    listener.onToolExecuted(requestId, tool.getName(), result.isSuccess(), 
                        result.isSuccess() ? result.getContent() : result.getError());
                }
            }
            
            if (result.isSuccess()) {
                // Try to parse content as JSON for cleaner response
                try {
                    String content = result.getContent().trim();
                    if (content.startsWith("{") || content.startsWith("[")) {
                        // Parse as JSON and return as map/list
                        Object parsed = GSON.fromJson(content, Object.class);
                        sendJson(ctx, parsed);
                    } else {
                        // Return as simple result object
                        sendJson(ctx, Map.of("result", result.getContent()));
                    }
                } catch (Exception e) {
                    // Fallback to wrapped response
                    Map<String, Object> response = new HashMap<>();
                    response.put("result", result.getContent());
                    if (result.getMetadata() != null) {
                        response.put("metadata", GSON.fromJson(result.getMetadata().toString(), Map.class));
                    }
                    sendJson(ctx, response);
                }
                
                // Notify request completed
                if (delegateServer != null) {
                    for (AgentProxyServer.RequestListener listener : delegateServer.getRequestListeners()) {
                        listener.onRequestCompleted(requestId, 200, result.getContent());
                    }
                }
            } else {
                sendJson(ctx, 500, Map.of("message", result.getError()));
                
                // Notify request failed
                if (delegateServer != null) {
                    for (AgentProxyServer.RequestListener listener : delegateServer.getRequestListeners()) {
                        listener.onRequestFailed(requestId, result.getError());
                    }
                }
            }
            
        } catch (Exception e) {
            LOG.error("Error executing tool: " + tool.getName(), e);
            sendJson(ctx, 500, Map.of("message", "Internal error: " + e.getMessage()));
            
            // Notify request failed
            if (delegateServer != null) {
                for (AgentProxyServer.RequestListener listener : delegateServer.getRequestListeners()) {
                    listener.onRequestFailed(requestId, e.getMessage());
                }
            }
        }
    }
}
