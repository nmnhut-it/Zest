package com.zps.zest.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.InitializeRequest;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.SamplingMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.ModelPreferences;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * MCP client implementation for Zest.
 * This class handles communication with MCP-compatible LLM servers.
 */
public class McpClient {
    private static final Logger LOG = Logger.getInstance(McpClient.class);
    
    private final Project project;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private String serverUri;
    private boolean initialized = false;
    private List<Tool> availableTools = new ArrayList<>();
    
    public McpClient(Project project) {
        this.project = project;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder().build();
    }
    
    /**
     * Initializes the MCP client with the specified server URI.
     * 
     * @param serverUri The URI of the MCP server
     * @return A CompletableFuture that completes when initialization is done
     */
    public CompletableFuture<Void> initialize(String serverUri) {
        this.serverUri = serverUri;
        
        try {
            // Create initialization request
            InitializeRequest initRequest = new InitializeRequest(
                    McpSchema.LATEST_PROTOCOL_VERSION,
                    createClientCapabilities(),
                    new Implementation("ZestIDE", "1.0.0")
            );
            
            // Create JSON-RPC request
            JSONRPCRequest rpcRequest = new JSONRPCRequest(
                    McpSchema.JSONRPC_VERSION,
                    McpSchema.METHOD_INITIALIZE,
                    generateRequestId(),
                    initRequest
            );
            
            return sendRequest(rpcRequest)
                    .thenApply(response -> {
                        if (response.error() != null) {
                            throw new RuntimeException("Initialization failed: " + response.error().message());
                        }
                        
                        try {
                            // Parse initialization result
                            InitializeResult result = objectMapper.convertValue(
                                    response.result(), InitializeResult.class);
                            
                            LOG.info("MCP initialized with protocol version: " + result.protocolVersion());
                            
                            // Get available tools
                            return fetchTools();
                        }
                        catch (Exception e) {
                            throw new RuntimeException("Failed to parse initialization result", e);
                        }
                    })
                    .thenRun(() -> {
                        initialized = true;
                        LOG.info("MCP client fully initialized");
                    });
            
        }
        catch (Exception e) {
            LOG.error("Failed to initialize MCP client", e);
            return CompletableFuture.failedFuture(e);
        }
    }
    
    /**
     * Fetches available tools from the server.
     */
    private CompletableFuture<Void> fetchTools() {
        JSONRPCRequest rpcRequest = new JSONRPCRequest(
                McpSchema.JSONRPC_VERSION,
                McpSchema.METHOD_TOOLS_LIST,
                generateRequestId(),
                Map.of()
        );
        
        return sendRequest(rpcRequest)
                .thenAccept(response -> {
                    if (response.error() != null) {
                        LOG.warn("Failed to fetch tools: " + response.error().message());
                        return;
                    }
                    
                    try {
                        ObjectNode resultNode = objectMapper.convertValue(response.result(), ObjectNode.class);
                        if (resultNode.has("tools")) {
                            availableTools = objectMapper.convertValue(
                                    resultNode.get("tools"),
                                    objectMapper.getTypeFactory().constructCollectionType(List.class, Tool.class)
                            );
                            LOG.info("Fetched " + availableTools.size() + " tools from MCP server");
                        }
                    }
                    catch (Exception e) {
                        LOG.error("Failed to parse tools list", e);
                    }
                });
    }
    
    /**
     * Creates client capabilities for initialization.
     */
    private ClientCapabilities createClientCapabilities() {
        return ClientCapabilities.builder()
                .sampling()
                .build();
    }
    
    /**
     * Sends a prompt to the model with tool capabilities.
     * 
     * @param prompt The prompt text to send
     * @param streaming Whether to stream the response
     * @param responseHandler Handler for streaming responses
     * @return A CompletableFuture with the full response
     */
    public CompletableFuture<String> sendPrompt(String prompt, boolean streaming, Consumer<String> responseHandler) {
        if (!isInitialized()) {
            return CompletableFuture.failedFuture(new IllegalStateException("MCP client not initialized"));
        }
        
        try {
            // Create message request
            List<SamplingMessage> messages = new ArrayList<>();
            messages.add(new SamplingMessage(
                    Role.USER,
                    new TextContent(null, null, prompt)
            ));
            
            ModelPreferences modelPrefs = ModelPreferences.builder().build();
            
            CreateMessageRequest messageRequest = CreateMessageRequest.builder()
                    .messages(messages)
                    .modelPreferences(modelPrefs)
                    .includeContext(CreateMessageRequest.ContextInclusionStrategy.NONE)
                    .build();
            
            // Create JSON-RPC request
            JSONRPCRequest rpcRequest = new JSONRPCRequest(
                    McpSchema.JSONRPC_VERSION,
                    McpSchema.METHOD_SAMPLING_CREATE_MESSAGE,
                    generateRequestId(),
                    messageRequest
            );
            
            return sendRequest(rpcRequest)
                    .thenApply(response -> {
                        if (response.error() != null) {
                            throw new RuntimeException("Sampling failed: " + response.error().message());
                        }
                        
                        try {
                            // Parse sampling result
                            CreateMessageResult result = objectMapper.convertValue(
                                    response.result(), CreateMessageResult.class);
                            
                            // Extract text from content
                            if (result.content() instanceof TextContent) {
                                TextContent textContent = (TextContent) result.content();
                                return textContent.text();
                            }
                            else {
                                return "Received non-text response";
                            }
                        }
                        catch (Exception e) {
                            throw new RuntimeException("Failed to parse sampling result", e);
                        }
                    });
        }
        catch (Exception e) {
            LOG.error("Failed to send prompt", e);
            return CompletableFuture.failedFuture(e);
        }
    }
    
    /**
     * Sends a JSON-RPC request to the server.
     */
    private CompletableFuture<JSONRPCResponse> sendRequest(JSONRPCRequest request) {
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(serverUri))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();
            
            return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        try {
                            String responseBody = response.body();
                            LOG.debug("Received MCP response: " + responseBody);
                            
                            JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(
                                    objectMapper, responseBody);
                            
                            if (message instanceof JSONRPCResponse) {
                                return (JSONRPCResponse) message;
                            }
                            else {
                                throw new RuntimeException("Expected JSON-RPC response, got: " + message.getClass().getSimpleName());
                            }
                        }
                        catch (IOException e) {
                            throw new RuntimeException("Failed to parse response", e);
                        }
                    });
        }
        catch (Exception e) {
            LOG.error("Failed to send request", e);
            return CompletableFuture.failedFuture(e);
        }
    }
    
    /**
     * Generates a unique request ID.
     */
    private Object generateRequestId() {
        return "req_" + new Random().nextInt(10000);
    }
    
    /**
     * Checks if the client has been initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Gets the list of available tools.
     */
    public List<Tool> getAvailableTools() {
        return availableTools;
    }
    
    /**
     * Closes the client.
     */
    public CompletableFuture<Void> close() {
        // No specific close operation needed for HTTP client
        return CompletableFuture.completedFuture(null);
    }
}