package com.zps.zest.mcp;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.AgentToolRegistry;
import com.zps.zest.ConfigurationManager;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Service for managing MCP client instances.
 * This is the main entry point for MCP functionality in Zest.
 */
@Service
public final class McpService {
    private static final Logger LOG = Logger.getInstance(McpService.class);
    
    private final Project project;
    private final McpClient mcpClient;
    private final McpToolAdapter toolAdapter;
    
    public McpService(Project project) {
        this.project = project;
        AgentToolRegistry toolRegistry = new AgentToolRegistry(project);
        this.toolAdapter = new McpToolAdapter(toolRegistry);
        this.mcpClient = new McpClient(project);
    }
    
    /**
     * Gets an instance of the MCP service for the given project.
     */
    public static McpService getInstance(Project project) {
        return project.getService(McpService.class);
    }
    
    /**
     * Initializes the MCP client with the server URI from configuration.
     * 
     * @return A CompletableFuture that completes when initialization is done
     */
    public CompletableFuture<Void> initializeClient() {
        ConfigurationManager configManager = new ConfigurationManager(project);
        String serverUri = configManager.getMcpServerUri();
        
        if (serverUri == null || serverUri.isEmpty()) {
            LOG.warn("No MCP server URI configured. Using default.");
            serverUri = "http://localhost:8080/mcp"; // Default server URI
        }
        
        return mcpClient.initialize(serverUri);
    }
    
    /**
     * Sends a prompt to the LLM via MCP.
     * 
     * @param prompt The prompt text
     * @param streaming Whether to stream the response
     * @param responseHandler Handler for streaming responses
     * @return A CompletableFuture with the complete response
     */
    public CompletableFuture<String> sendPrompt(String prompt, boolean streaming, Consumer<String> responseHandler) {
        return mcpClient.sendPrompt(prompt, streaming, responseHandler);
    }
    
    /**
     * Gets the list of available tools from the MCP server.
     */
    public List<Tool> getAvailableTools() {
        return mcpClient.getAvailableTools();
    }
    
    /**
     * Gets the MCP client instance.
     */
    public McpClient getClient() {
        return mcpClient;
    }
    
    /**
     * Gets the MCP tool adapter.
     */
    public McpToolAdapter getToolAdapter() {
        return toolAdapter;
    }
    
    /**
     * Closes the client session.
     */
    public CompletableFuture<Void> close() {
        return mcpClient.close();
    }
}