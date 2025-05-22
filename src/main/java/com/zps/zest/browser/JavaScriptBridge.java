package com.zps.zest.browser;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * Refactored Bridge for communication between JavaScript in the browser and Java code in IntelliJ.
 * This version uses chunked messaging to handle large messages and delegates actions to specialized services.
 */
public class JavaScriptBridge {
    private static final Logger LOG = Logger.getInstance(JavaScriptBridge.class);
    
    private final Project project;
    private final ChunkedMessageHandler chunkedHandler;
    private final JavaScriptBridgeActions actionHandler;
    
    /**
     * Creates a new JavaScript bridge with chunked messaging support.
     */
    public JavaScriptBridge(@NotNull Project project) {
        this.project = project;
        this.chunkedHandler = new ChunkedMessageHandler();
        this.actionHandler = new JavaScriptBridgeActions(project);
    }
    
    /**
     * Handles a JavaScript query from the browser.
     * Now supports chunked messaging for large messages that exceed JBCef/CEF limits.
     */
    public String handleJavaScriptQuery(String query) {
        LOG.info("Received query from JavaScript (length: " + query.length() + "): " + 
                query.substring(0, Math.min(200, query.length())) + "...");
        
        try {
            // 1. Process chunked message
            ChunkedMessageHandler.ProcessResult result = chunkedHandler.processChunkedMessage(query);
            
            if (!result.isComplete()) {
                // Still waiting for more chunks
                LOG.info("Waiting for more chunks: " + result.getAssembledMessage());
                return createWaitingResponse(result.getAssembledMessage());
            }
            
            // 2. Route to action handler with complete message
            LOG.info("Processing complete message (length: " + result.getAssembledMessage().length() + ")");
            String response = actionHandler.handleAction(result.getAssembledMessage());
            LOG.info("Bridge response: " + response.substring(0, Math.min(200, response.length())) + "...");
            return response;
            
        } catch (Exception e) {
            LOG.error("Error handling JavaScript query", e);
            return createErrorResponse(e.getMessage());
        }
    }
    
    /**
     * Creates a response indicating we're waiting for more chunks.
     */
    private String createWaitingResponse(String message) {
        return "{\"success\": true, \"waiting\": true, \"message\": \"" + 
               message.replace("\"", "\\\"") + "\"}";
    }
    
    /**
     * Creates an error response.
     */
    private String createErrorResponse(String error) {
        return "{\"success\": false, \"error\": \"" + 
               error.replace("\"", "\\\"") + "\"}";
    }
    
    /**
     * Waits for the next chat response.
     * Delegates to the chat response service.
     */
    public CompletableFuture<String> waitForChatResponse(int timeoutSeconds) {
        return actionHandler.getChatResponseService().waitForChatResponse(timeoutSeconds);
    }
    
    /**
     * Gets statistics about chunked message handling.
     */
    public String getChunkingStats() {
        // Could add statistics tracking to ChunkedMessageHandler if needed
        return "{\"chunking_enabled\": true, \"max_chunk_size\": 1400}";
    }
    
    /**
     * Disposes of all resources used by the bridge and its services.
     */
    public void dispose() {
        LOG.info("Disposing JavaScriptBridge and all services");
        
        if (chunkedHandler != null) {
            chunkedHandler.dispose();
        }
        
        if (actionHandler != null) {
            actionHandler.dispose();
        }
    }
}
