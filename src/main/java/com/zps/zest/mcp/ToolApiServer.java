package com.zps.zest.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.zps.zest.chatui.CodeModificationTools;
import com.zps.zest.explanation.tools.RipgrepCodeTool;
import com.zps.zest.testgen.tools.*;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Simple HTTP server for exposing Zest tools via REST API with OpenAPI schema.
 * Uses Java's built-in HttpServer - no frameworks needed.
 */
public class ToolApiServer {
    private static final Logger LOG = Logger.getInstance(ToolApiServer.class);
    private static final Gson GSON = new Gson();

    private final Project project;
    private final HttpServer server;
    private final int port;

    // Tool instances
    private final ReadFileTool readFileTool;
    private final RipgrepCodeTool ripgrepTool;
    private final AnalyzeClassTool analyzeClassTool;
    private final ListFilesTool listFilesTool;
    private final LookupMethodTool lookupMethodTool;
    private final LookupClassTool lookupClassTool;
    private final CodeModificationTools codeModificationTools;

    public ToolApiServer(Project project, int port) throws IOException {
        this.project = project;
        this.port = port;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);

        // Initialize tools
        Map<String, String> readFiles = new HashMap<>();
        Set<String> relatedFiles = new HashSet<>();
        List<String> usagePatterns = new ArrayList<>();

        this.readFileTool = new ReadFileTool(project, readFiles);
        this.ripgrepTool = new RipgrepCodeTool(project, relatedFiles, usagePatterns);
        this.analyzeClassTool = new AnalyzeClassTool(project, new HashMap<>());
        this.listFilesTool = new ListFilesTool(project);
        this.lookupMethodTool = new LookupMethodTool(project);
        this.lookupClassTool = new LookupClassTool(project);
        this.codeModificationTools = new CodeModificationTools(project, null);

        setupEndpoints();
    }

    private void setupEndpoints() {
        // Add CORS filter for all requests
        server.createContext("/", exchange -> {
            // Handle CORS preflight
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                setCorsHeaders(exchange);
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            // Let other handlers process the request
            exchange.sendResponseHeaders(404, -1);
        });

        // Tool endpoints
        server.createContext("/api/tools/readFile", new ReadFileHandler());
        server.createContext("/api/tools/searchCode", new SearchCodeHandler());
        server.createContext("/api/tools/findFiles", new FindFilesHandler());
        server.createContext("/api/tools/analyzeClass", new AnalyzeClassHandler());
        server.createContext("/api/tools/listFiles", new ListFilesHandler());
        server.createContext("/api/tools/lookupMethod", new LookupMethodHandler());
        server.createContext("/api/tools/lookupClass", new LookupClassHandler());
        server.createContext("/api/tools/replaceCodeInFile", new ReplaceCodeHandler());
        server.createContext("/api/tools/createNewFile", new CreateFileHandler());

        // OpenAPI schema endpoint
        server.createContext("/openapi.json", new OpenAPISchemaHandler());

        // Health check
        server.createContext("/api/health", new HealthHandler());

        server.setExecutor(Executors.newFixedThreadPool(10));
    }

    public void start() {
        server.start();
        LOG.info("Tool API Server started on port " + port);
        LOG.info("OpenAPI schema: http://localhost:" + port + "/openapi.json");
    }

    public void stop() {
        server.stop(0);
        LOG.info("Tool API Server stopped");
    }

    // OpenAPI Schema Handler
    private class OpenAPISchemaHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Handle OPTIONS preflight
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                setCorsHeaders(exchange);
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            try {
                // Extract tool specifications from each tool instance
                List<ToolSpecification> toolSpecs = new ArrayList<>();
                toolSpecs.addAll(ToolSpecifications.toolSpecificationsFrom(readFileTool));
                toolSpecs.addAll(ToolSpecifications.toolSpecificationsFrom(ripgrepTool));
                toolSpecs.addAll(ToolSpecifications.toolSpecificationsFrom(analyzeClassTool));
                toolSpecs.addAll(ToolSpecifications.toolSpecificationsFrom(listFilesTool));
                toolSpecs.addAll(ToolSpecifications.toolSpecificationsFrom(lookupMethodTool));
                toolSpecs.addAll(ToolSpecifications.toolSpecificationsFrom(lookupClassTool));
                toolSpecs.addAll(ToolSpecifications.toolSpecificationsFrom(codeModificationTools));

                // Generate OpenAPI schema
                String schema = SimpleOpenAPIGenerator.generateSchema(project.getName(), toolSpecs);

                // Send response
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                setCorsHeaders(exchange);
                byte[] response = schema.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            } catch (Exception e) {
                LOG.error("Error generating OpenAPI schema", e);
                sendError(exchange, 500, "Error generating schema: " + e.getMessage());
            }
        }
    }

    // Tool Handler Implementations
    private class ReadFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handlePost(exchange, body -> {
                JsonObject request = GSON.fromJson(body, JsonObject.class);
                String filePath = request.get("filePath").getAsString();
                String result = readFileTool.readFile(filePath);
                return createSuccessResponse(result);
            });
        }
    }

    private class SearchCodeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handlePost(exchange, body -> {
                JsonObject request = GSON.fromJson(body, JsonObject.class);
                String query = request.get("query").getAsString();
                String filePattern = getStringOrNull(request, "filePattern");
                String excludePattern = getStringOrNull(request, "excludePattern");
                Integer beforeLines = getIntOrDefault(request, "beforeLines", 0);
                Integer afterLines = getIntOrDefault(request, "afterLines", 0);

                String result = ripgrepTool.searchCode(query, filePattern, excludePattern, beforeLines, afterLines);
                return createSuccessResponse(result);
            });
        }
    }

    private class FindFilesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handlePost(exchange, body -> {
                JsonObject request = GSON.fromJson(body, JsonObject.class);
                String pattern = request.get("pattern").getAsString();
                String result = ripgrepTool.findFiles(pattern);
                return createSuccessResponse(result);
            });
        }
    }

    private class AnalyzeClassHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handlePost(exchange, body -> {
                JsonObject request = GSON.fromJson(body, JsonObject.class);
                String filePathOrClassName = request.get("filePathOrClassName").getAsString();
                String result = analyzeClassTool.analyzeClass(filePathOrClassName);
                return createSuccessResponse(result);
            });
        }
    }

    private class ListFilesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handlePost(exchange, body -> {
                JsonObject request = GSON.fromJson(body, JsonObject.class);
                String directoryPath = request.get("directoryPath").getAsString();
                Integer recursiveLevel = getIntOrDefault(request, "recursiveLevel", 1);
                String result = listFilesTool.listFiles(directoryPath, recursiveLevel);
                return createSuccessResponse(result);
            });
        }
    }

    private class LookupMethodHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handlePost(exchange, body -> {
                JsonObject request = GSON.fromJson(body, JsonObject.class);
                String className = request.get("className").getAsString();
                String methodName = request.get("methodName").getAsString();
                String result = lookupMethodTool.lookupMethod(className, methodName);
                return createSuccessResponse(result);
            });
        }
    }

    private class LookupClassHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handlePost(exchange, body -> {
                JsonObject request = GSON.fromJson(body, JsonObject.class);
                String className = request.get("className").getAsString();
                String result = lookupClassTool.lookupClass(className);
                return createSuccessResponse(result);
            });
        }
    }

    private class ReplaceCodeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handlePost(exchange, body -> {
                JsonObject request = GSON.fromJson(body, JsonObject.class);
                String filePath = request.get("filePath").getAsString();
                String searchPattern = request.get("searchPattern").getAsString();
                String replacement = request.get("replacement").getAsString();
                Boolean useRegex = request.has("useRegex") ? request.get("useRegex").getAsBoolean() : false;

                String result = codeModificationTools.replaceCodeInFile(
                    filePath, searchPattern, replacement, useRegex
                );
                return createSuccessResponse(result);
            });
        }
    }

    private class CreateFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handlePost(exchange, body -> {
                JsonObject request = GSON.fromJson(body, JsonObject.class);
                String filePath = request.get("filePath").getAsString();
                String content = request.get("content").getAsString();

                String result = codeModificationTools.createNewFile(filePath, content);
                return createSuccessResponse(result);
            });
        }
    }

    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            JsonObject response = new JsonObject();
            response.addProperty("status", "healthy");
            response.addProperty("service", "zest-tool-api");
            response.addProperty("project", project.getName());
            sendJsonResponse(exchange, 200, GSON.toJson(response));
        }
    }

    // Helper methods
    private void setCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "content-type, Content-Type, Authorization");
    }

    private void handlePost(HttpExchange exchange, RequestHandler handler) throws IOException {
        // Handle OPTIONS preflight
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            setCorsHeaders(exchange);
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method not allowed");
            return;
        }

        try {
            String requestBody = readRequestBody(exchange);
            String responseJson = handler.handle(requestBody);
            sendJsonResponse(exchange, 200, responseJson);
        } catch (Exception e) {
            LOG.error("Error handling request", e);
            sendError(exchange, 500, e.getMessage());
        }
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        setCorsHeaders(exchange);
        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        sendJsonResponse(exchange, statusCode, createErrorResponse(message));
    }

    private String createSuccessResponse(String result) {
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("result", result);
        return GSON.toJson(response);
    }

    private String createErrorResponse(String error) {
        JsonObject response = new JsonObject();
        response.addProperty("success", false);
        response.addProperty("error", error);
        return GSON.toJson(response);
    }

    private String getStringOrNull(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }

    private Integer getIntOrDefault(JsonObject json, String key, int defaultValue) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsInt() : defaultValue;
    }

    @FunctionalInterface
    private interface RequestHandler {
        String handle(String requestBody) throws Exception;
    }
}
