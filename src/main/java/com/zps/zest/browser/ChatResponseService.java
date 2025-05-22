package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Service for handling chat response operations from JavaScript bridge.
 * This manages asynchronous chat response handling and waiting.
 */
public class ChatResponseService {
    private static final Logger LOG = Logger.getInstance(ChatResponseService.class);
    
    private final Project project;
    private final Gson gson = new Gson();
    
    // Chat response handling fields
    private CompletableFuture<String> pendingResponseFuture = null;
    private String lastProcessedMessageId = null;
    
    public ChatResponseService(@NotNull Project project) {
        this.project = project;
    }
    
    /**
     * Handles chat response notifications from JavaScript.
     */
    public String notifyChatResponse(JsonObject data) {
        try {
            String content = data.get("content").getAsString();
            String messageId = data.has("id") ? data.get("id").getAsString() : "";
            
            // Notify any registered listeners
            notifyChatResponseReceived(content, messageId);
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            return gson.toJson(response);
        } catch (Exception e) {
            LOG.error("Error handling chat response notification", e);
            JsonObject response = new JsonObject();
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
            return gson.toJson(response);
        }
    }
    
    /**
     * Waits for the next chat response.
     * @param timeoutSeconds Maximum time to wait in seconds
     * @return CompletableFuture that will be completed when a response is received
     */
    public CompletableFuture<String> waitForChatResponse(int timeoutSeconds) {
        // Create a new future if there isn't one already or if the existing one is completed
        if (pendingResponseFuture == null || pendingResponseFuture.isDone()) {
            pendingResponseFuture = new CompletableFuture<>();
        }
        
        // Add timeout
        return pendingResponseFuture.orTimeout(timeoutSeconds, TimeUnit.SECONDS);
    }
    
    /**
     * Notifies about a new chat response
     * @param content The response content
     * @param messageId The message ID
     * ORIGINAL IMPLEMENTATION from JavaScriptBridge preserved.
     */
    private void notifyChatResponseReceived(String content, String messageId) {
        // Avoid processing the same message multiple times
        if (messageId != null && messageId.equals(lastProcessedMessageId)) {
            LOG.info("Ignoring duplicate message ID: " + messageId);
            return;
        }

        LOG.info("Received chat response with ID: " + messageId);

        // Update the last processed ID
        lastProcessedMessageId = messageId;

        // Complete the future if it exists
        if (pendingResponseFuture != null && !pendingResponseFuture.isDone()) {
            LOG.info("Completing pending response future with content length: " + (content != null ? content.length() : 0));
            pendingResponseFuture.complete(content);
        } else {
            LOG.info("No pending response future to complete");
        }
    }
    
    /**
     * Checks if there's a pending response waiting.
     */
    public boolean hasPendingResponse() {
        return pendingResponseFuture != null && !pendingResponseFuture.isDone();
    }
    
    /**
     * Cancels any pending response future.
     */
    public void cancelPendingResponse() {
        if (pendingResponseFuture != null && !pendingResponseFuture.isDone()) {
            pendingResponseFuture.cancel(true);
            LOG.info("Cancelled pending response future");
        }
    }
    
    /**
     * Gets the last processed message ID.
     */
    public String getLastProcessedMessageId() {
        return lastProcessedMessageId;
    }
    
    /**
     * Disposes of any resources.
     */
    public void dispose() {
        cancelPendingResponse();
    }
}
