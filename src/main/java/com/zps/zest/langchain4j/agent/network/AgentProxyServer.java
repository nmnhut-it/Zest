package com.zps.zest.langchain4j.agent.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.zps.zest.langchain4j.agent.CodeExplorationReport;
import com.zps.zest.langchain4j.agent.ImprovedToolCallingAutonomousAgent;
import com.zps.zest.langchain4j.util.LLMService;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * HTTP server that exposes the ImprovedToolCallingAutonomousAgent as a network service.
 * This allows external processes (like Node.js MCP server) to use the agent.
 */
public class AgentProxyServer {
    private static final Logger LOG = Logger.getInstance(AgentProxyServer.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    private HttpServer server;
    private final int port;
    private final Project project;
    private final AgentProxyConfiguration config;
    
    public AgentProxyServer(Project project, int port, AgentProxyConfiguration config) {
        this.project = project;
        this.port = port;
        this.config = config != null ? config : AgentProxyConfiguration.getDefault();
    }
    
    /**
     * Starts the HTTP server.
     */
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        
        // Configure endpoints
        server.createContext("/health", new HealthCheckHandler());
        server.createContext("/explore", new ExploreHandler());
        server.createContext("/augment", new AugmentHandler());
        server.createContext("/config", new ConfigHandler());
        server.createContext("/status", new StatusHandler());
        
        // Use a thread pool for handling requests
        server.setExecutor(Executors.newFixedThreadPool(10));
        
        server.start();
        LOG.info("Agent Proxy Server started on port " + port);
    }
    
    /**
     * Stops the HTTP server.
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
            LOG.info("Agent Proxy Server stopped");
        }
    }
    
    /**
     * Health check endpoint handler.
     */
    private class HealthCheckHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            JsonObject response = new JsonObject();
            response.addProperty("status", "ok");
            response.addProperty("service", "agent-proxy");
            response.addProperty("port", port);
            response.addProperty("project", project.getName());
            
            sendJsonResponse(exchange, 200, response);
        }
    }
    
    /**
     * Main exploration endpoint handler.
     */
    private class ExploreHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }
            
            try {
                String requestBody = readRequestBody(exchange);
                JsonObject request = JsonParser.parseString(requestBody).getAsJsonObject();
                
                String query = request.get("query").getAsString();
                boolean generateReport = request.has("generateReport") && 
                                       request.get("generateReport").getAsBoolean();
                
                // Get optional configuration overrides
                AgentProxyConfiguration requestConfig = config;
                if (request.has("config")) {
                    requestConfig = mergeConfig(config, request.getAsJsonObject("config"));
                }
                
                // Execute exploration
                CompletableFuture<JsonObject> future = exploreAsync(query, generateReport, requestConfig);
                
                // Wait for completion with timeout
                JsonObject result = future.get(requestConfig.getTimeoutSeconds(), TimeUnit.SECONDS);
                sendJsonResponse(exchange, 200, result);
                
            } catch (Exception e) {
                LOG.error("Error handling explore request", e);
                sendError(exchange, 500, "Internal error: " + e.getMessage());
            }
        }
    }
    
    /**
     * Query augmentation endpoint handler.
     */
    private class AugmentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method not allowed");
                return;
            }
            
            try {
                String requestBody = readRequestBody(exchange);
                JsonObject request = JsonParser.parseString(requestBody).getAsJsonObject();
                
                String query = request.get("query").getAsString();
                
                // Execute augmentation
                CompletableFuture<JsonObject> future = augmentQueryAsync(query);
                
                // Wait for completion with timeout
                JsonObject result = future.get(config.getTimeoutSeconds(), TimeUnit.SECONDS);
                sendJsonResponse(exchange, 200, result);
                
            } catch (Exception e) {
                LOG.error("Error handling augment request", e);
                sendError(exchange, 500, "Internal error: " + e.getMessage());
            }
        }
    }
    
    /**
     * Configuration endpoint handler.
     */
    private class ConfigHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equals(exchange.getRequestMethod())) {
                // Return current configuration
                JsonObject response = config.toJson();
                sendJsonResponse(exchange, 200, response);
            } else if ("POST".equals(exchange.getRequestMethod())) {
                // Update configuration
                try {
                    String requestBody = readRequestBody(exchange);
                    JsonObject updates = JsonParser.parseString(requestBody).getAsJsonObject();
                    
                    config.updateFromJson(updates);
                    
                    JsonObject response = new JsonObject();
                    response.addProperty("status", "updated");
                    response.add("config", config.toJson());
                    
                    sendJsonResponse(exchange, 200, response);
                } catch (Exception e) {
                    sendError(exchange, 400, "Invalid configuration: " + e.getMessage());
                }
            } else {
                sendError(exchange, 405, "Method not allowed");
            }
        }
    }
    
    /**
     * Status endpoint handler.
     */
    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            JsonObject response = new JsonObject();
            response.addProperty("status", "running");
            response.addProperty("project", project.getName());
            response.addProperty("projectPath", project.getBasePath());
            
            // Add index status
            try {
                ApplicationManager.getApplication().runReadAction(() -> {
                    // Check if project is indexed
                    response.addProperty("indexed", isProjectIndexed());
                });
            } catch (Exception e) {
                response.addProperty("indexed", false);
            }
            
            // Add configuration info
            response.add("config", config.toJson());
            
            sendJsonResponse(exchange, 200, response);
        }
    }
    
    /**
     * Performs code exploration asynchronously.
     */
    private CompletableFuture<JsonObject> exploreAsync(String query, boolean generateReport, 
                                                       AgentProxyConfiguration requestConfig) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Create a configured agent
                ImprovedToolCallingAutonomousAgent agent = createConfiguredAgent(requestConfig);
                
                JsonObject result = new JsonObject();
                result.addProperty("query", query);
                
                if (generateReport) {
                    // Generate full report
                    CodeExplorationReport report = agent.exploreAndGenerateReport(query);
                    
                    result.addProperty("success", true);
                    result.add("report", reportToJson(report));
                } else {
                    // Just explore without report
                    ImprovedToolCallingAutonomousAgent.ExplorationResult exploration = 
                        agent.exploreWithTools(query);
                    
                    result.addProperty("success", exploration.isSuccess());
                    result.addProperty("summary", exploration.getSummary());
                    result.addProperty("rounds", exploration.getRounds().size());
                    
                    // Add tool execution summary
                    JsonObject toolSummary = new JsonObject();
                    int totalTools = exploration.getRounds().stream()
                        .mapToInt(r -> r.getToolExecutions().size())
                        .sum();
                    toolSummary.addProperty("totalExecutions", totalTools);
                    result.add("toolSummary", toolSummary);
                }
                
                return result;
                
            } catch (Exception e) {
                LOG.error("Error in exploration", e);
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("error", e.getMessage());
                return error;
            }
        });
    }
    
    /**
     * Augments a query with code context and rewrites it.
     */
    private CompletableFuture<JsonObject> augmentQueryAsync(String query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ImprovedToolCallingAutonomousAgent agent = createConfiguredAgent(config);
                
                // First, explore the code
                CodeExplorationReport report = agent.exploreAndGenerateReport(query);
                
                // Then use LLM to rewrite the query with context
                String augmentedQuery = rewriteQueryWithContext(query, report);
                
                JsonObject result = new JsonObject();
                result.addProperty("success", true);
                result.addProperty("originalQuery", query);
                result.addProperty("augmentedQuery", augmentedQuery);
                result.add("explorationSummary", reportToJson(report));
                
                return result;
                
            } catch (Exception e) {
                LOG.error("Error in query augmentation", e);
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("error", e.getMessage());
                return error;
            }
        });
    }
    
    /**
     * Creates a configured agent with custom limits.
     */
    private ImprovedToolCallingAutonomousAgent createConfiguredAgent(AgentProxyConfiguration config) {
        // Create a new agent instance with custom configuration
        // Note: This would require modifying ImprovedToolCallingAutonomousAgent to accept config
        // For now, we'll use the standard agent
        return project.getService(ImprovedToolCallingAutonomousAgent.class);
    }
    
    /**
     * Rewrites the query with code context using LLM.
     */
    private String rewriteQueryWithContext(String originalQuery, CodeExplorationReport report) {
        LLMService llmService = project.getService(LLMService.class);
        
        String prompt = String.format("""
            You are helping to augment a user's query with relevant code context from their project.
            
            Original Query: %s
            
            Code Exploration Summary:
            %s
            
            Key Code Elements Found:
            %s
            
            Rewrite the user's query to include the specific code context, class names, methods, and relationships 
            that were discovered. The rewritten query should:
            1. Reference specific classes and methods by name
            2. Include relevant code snippets where helpful
            3. Mention important relationships and dependencies
            4. Be self-contained so the LLM has all necessary context
            5. Maintain the original intent while being more specific
            
            Rewritten Query:
            """, 
            originalQuery,
            report.getSummary(),
            String.join("\n", report.getDiscoveredElements())
        );
        
        String rewritten = llmService.query(prompt);
        return rewritten != null ? rewritten : originalQuery;
    }
    
    /**
     * Converts CodeExplorationReport to JSON.
     */
    private JsonObject reportToJson(CodeExplorationReport report) {
        JsonObject json = new JsonObject();
        json.addProperty("summary", report.getSummary());
        json.addProperty("timestamp", report.getTimestamp().toString());
        json.addProperty("originalQuery", report.getOriginalQuery());
        
        // Add discovered elements
        json.add("discoveredElements", GSON.toJsonTree(report.getDiscoveredElements()));
        
        // Add code pieces summary
        JsonObject codePieces = new JsonObject();
        codePieces.addProperty("count", report.getCodePieces().size());
        json.add("codePieces", codePieces);
        
        // Add structured context if available
        if (report.getStructuredContext() != null) {
            json.addProperty("structuredContext", report.getStructuredContext());
        }
        
        return json;
    }
    
    /**
     * Checks if the project is properly indexed.
     */
    private boolean isProjectIndexed() {
        // This is a simplified check - you might want to add more sophisticated checks
        return project.isInitialized() && !project.isDisposed();
    }
    
    /**
     * Merges configuration from request with base config.
     */
    private AgentProxyConfiguration mergeConfig(AgentProxyConfiguration base, JsonObject updates) {
        AgentProxyConfiguration merged = base.copy();
        merged.updateFromJson(updates);
        return merged;
    }
    
    /**
     * Helper method to read request body.
     */
    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    
    /**
     * Helper method to send JSON response.
     */
    private void sendJsonResponse(HttpExchange exchange, int statusCode, JsonObject response) 
            throws IOException {
        String responseBody = GSON.toJson(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, responseBody.getBytes().length);
        
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBody.getBytes());
        }
    }
    
    /**
     * Helper method to send error response.
     */
    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        JsonObject error = new JsonObject();
        error.addProperty("error", message);
        sendJsonResponse(exchange, statusCode, error);
    }
    
    /**
     * Factory method to create and start a proxy server.
     */
    public static AgentProxyServer startServer(Project project, int port) throws IOException {
        AgentProxyServer server = new AgentProxyServer(project, port, AgentProxyConfiguration.getDefault());
        server.start();
        return server;
    }
    
    /**
     * Finds an available port and starts the server.
     */
    public static AgentProxyServer startServerAutoPort(Project project, int startPort, int endPort) 
            throws IOException {
        for (int port = startPort; port <= endPort; port++) {
            try {
                return startServer(project, port);
            } catch (IOException e) {
                // Port in use, try next
                if (port == endPort) {
                    throw new IOException("No available ports in range " + startPort + "-" + endPort);
                }
            }
        }
        throw new IOException("Failed to start server");
    }
}
