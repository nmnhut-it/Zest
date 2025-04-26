package com.zps.zest.mcp;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.AgentToolRegistry;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.InteractiveAgentService;
import com.zps.zest.ToolExecutor;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Handler for MCP agent interactions.
 * This class integrates MCP with the existing agent service.
 */
public class McpAgentHandler {
    private static final Logger LOG = Logger.getInstance(McpAgentHandler.class);
    
    private final Project project;
    private final McpService mcpService;
    private final ConfigurationManager configManager;
    private final InteractiveAgentService agentService;
    private final ToolExecutor toolExecutor;
    
    public McpAgentHandler(Project project) {
        this.project = project;
        this.mcpService = McpService.getInstance(project);
        this.configManager = new ConfigurationManager(project);
        this.agentService = InteractiveAgentService.getInstance(project);
        this.toolExecutor = new ToolExecutor(new AgentToolRegistry(project));
    }
    
    /**
     * Processes a user message using MCP.
     * 
     * @param userMessage The user message
     * @return A future with the assistant's response
     */
    public CompletableFuture<String> processMessage(String userMessage) {
        if (!configManager.isMcpEnabled()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("MCP is not enabled. Please enable it in the configuration."));
        }
        
        // Initialize client if not already initialized
        if (!mcpService.getClient().isInitialized()) {
            return mcpService.initializeClient()
                    .thenCompose(v -> sendMessageToMcp(userMessage));
        }
        
        return sendMessageToMcp(userMessage);
    }
    
    /**
     * Sends a message to the MCP server and processes the response.
     * 
     * @param message The message to send
     * @return A future with the processed response
     */
    private CompletableFuture<String> sendMessageToMcp(String message) {
        AtomicReference<StringBuilder> responseBuilder = new AtomicReference<>(new StringBuilder());
        
        // Process the message through MCP
        return mcpService.sendPrompt(message, true, chunk -> {
            // Handle streaming response
            responseBuilder.get().append(chunk);
            
            // Update UI with partial response
            ApplicationManager.getApplication().invokeLater(() -> {
                agentService.addAssistantMessage(responseBuilder.get().toString());
            });
        }).thenApply(fullResponse -> {
            // Process any tool invocations in the response
            String processedResponse = toolExecutor.processToolInvocations(fullResponse);
            
            // Notify service about response received
            agentService.notifyResponseReceived(message, processedResponse);
            
            return processedResponse;
        });
    }
    
    /**
     * Checks if MCP is available and properly configured.
     * 
     * @return true if MCP is available and configured, false otherwise
     */
    public boolean isMcpAvailable() {
        return configManager.isMcpEnabled() && (mcpService.getClient() != null);
    }
    
    /**
     * Gets the available tools from the MCP server.
     * 
     * @return A list of available tools
     */
    public List<Tool> getAvailableTools() {
        if (mcpService.getClient().isInitialized()) {
            return mcpService.getAvailableTools();
        }
        return List.of();
    }
    
    /**
     * Tests the connection to the MCP server.
     * 
     * @return A future with a success message or error
     */
    public CompletableFuture<String> testConnection() {
        if (!configManager.isMcpEnabled()) {
            return CompletableFuture.completedFuture("MCP is not enabled. Please enable it in the configuration.");
        }
        
        // Initialize client
        return mcpService.initializeClient()
                .thenApply(v -> {
                    List<Tool> tools = mcpService.getAvailableTools();
                    return "Successfully connected to MCP server. " +
                            "Available tools: " + tools.size();
                })
                .exceptionally(ex -> "Failed to connect to MCP server: " + ex.getMessage());
    }
    
    /**
     * Closes the MCP connection.
     */
    public void close() {
        if (mcpService != null) {
            mcpService.close();
        }
    }
}