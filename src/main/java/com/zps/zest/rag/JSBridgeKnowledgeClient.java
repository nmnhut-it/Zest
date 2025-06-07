package com.zps.zest.rag;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.application.ApplicationManager;
import com.zps.zest.browser.WebBrowserService;
import com.zps.zest.browser.JCEFBrowserManager;
import com.zps.zest.rag.models.KnowledgeCollection;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Knowledge API client that uses JavaScript bridge to make API calls through the browser.
 * This avoids CORS issues by making requests from the same origin.
 */
public class JSBridgeKnowledgeClient implements KnowledgeApiClient {
    private static final Logger LOG = Logger.getInstance(JSBridgeKnowledgeClient.class);
    private static final int TIMEOUT_SECONDS = 60; // Increased from 30
    private static final AtomicLong callbackCounter = new AtomicLong(0);
    private static final ConcurrentHashMap<String, CompletableFuture<JsonObject>> pendingCallbacks = new ConcurrentHashMap<>();
    
    private final Project project;
    private final Gson gson = new Gson();
    
    public JSBridgeKnowledgeClient(Project project) {
        this.project = project;
    }
    
    /**
     * Find knowledge base by name pattern using JS bridge
     */
    public String findKnowledgeByName(String namePattern) throws IOException {
        LOG.info("Finding knowledge base by name: " + namePattern);
        
        try {
            JsonObject result = executeKnowledgeApiCall(
                String.format("KnowledgeAPI.findKnowledgeByName('%s')", 
                    escapeJs(namePattern))
            ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            
            if (result.has("success") && result.get("success").getAsBoolean()) {
                if (result.has("knowledgeId") && !result.get("knowledgeId").isJsonNull()) {
                    return result.get("knowledgeId").getAsString();
                }
            }
            return null;
        } catch (Exception e) {
            LOG.error("Error finding knowledge by name via JS bridge", e);
            return null;
        }
    }
    
    @Override
    public String createKnowledgeBase(String name, String description) throws IOException {
        LOG.info("Creating knowledge base via JS bridge: " + name);
        
        try {
            JsonObject result = executeKnowledgeApiCall(
                String.format("KnowledgeAPI.createKnowledgeBase('%s', '%s')", 
                    escapeJs(name), escapeJs(description))
            ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            
            if (result.has("success") && result.get("success").getAsBoolean()) {
                if (result.has("knowledgeId") && !result.get("knowledgeId").isJsonNull()) {
                    String knowledgeId = result.get("knowledgeId").getAsString();
                    LOG.info("Knowledge base created successfully with ID: " + knowledgeId);
                    return knowledgeId;
                } else {
                    throw new IOException("No knowledge ID returned");
                }
            } else {
                String error = result.has("error") ? result.get("error").getAsString() : "Unknown error";
                throw new IOException("Failed to create knowledge base: " + error);
            }
        } catch (Exception e) {
            LOG.error("Error creating knowledge base via JS bridge", e);
            throw new IOException("Failed to create knowledge base", e);
        }
    }
    
    @Override
    public String uploadFile(String fileName, String content) throws IOException {
        LOG.info("Uploading file via JS bridge: " + fileName);
        
        try {
            JsonObject result = executeKnowledgeApiCall(
                String.format("KnowledgeAPI.uploadFile('%s', '%s')", 
                    escapeJs(fileName), escapeJs(content))
            ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            
            if (result.has("success") && result.get("success").getAsBoolean()) {
                if (result.has("fileId") && !result.get("fileId").isJsonNull()) {
                    return result.get("fileId").getAsString();
                } else {
                    throw new IOException("No file ID returned");
                }
            } else {
                String error = result.has("error") ? result.get("error").getAsString() : "Unknown error";
                throw new IOException("Failed to upload file: " + error);
            }
        } catch (Exception e) {
            LOG.error("Error uploading file via JS bridge", e);
            throw new IOException("Failed to upload file", e);
        }
    }
    
    @Override
    public void addFileToKnowledge(String knowledgeId, String fileId) throws IOException {
        LOG.info("Adding file to knowledge base: " + fileId + " -> " + knowledgeId);
        
        try {
            JsonObject result = executeKnowledgeApiCall(
                String.format("KnowledgeAPI.addFileToKnowledge('%s', '%s')", 
                    escapeJs(knowledgeId), escapeJs(fileId))
            ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            
            if (!result.has("success") || !result.get("success").getAsBoolean()) {
                String error = result.has("error") ? result.get("error").getAsString() : "Unknown error";
                throw new IOException("Failed to add file to knowledge: " + error);
            }
        } catch (Exception e) {
            LOG.error("Error adding file to knowledge via JS bridge", e);
            throw new IOException("Failed to add file to knowledge", e);
        }
    }
    
    @Override
    public void removeFileFromKnowledge(String knowledgeId, String fileId) throws IOException {
        LOG.info("Removing file from knowledge base: " + fileId + " from " + knowledgeId);
        
        try {
            JsonObject result = executeKnowledgeApiCall(
                String.format("KnowledgeAPI.removeFileFromKnowledge('%s', '%s')", 
                    escapeJs(knowledgeId), escapeJs(fileId))
            ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            
            if (!result.has("success") || !result.get("success").getAsBoolean()) {
                String error = result.has("error") ? result.get("error").getAsString() : "Unknown error";
                throw new IOException("Failed to remove file from knowledge: " + error);
            }
        } catch (Exception e) {
            LOG.error("Error removing file from knowledge via JS bridge", e);
            throw new IOException("Failed to remove file from knowledge", e);
        }
    }
    
    @Override
    public List<String> queryKnowledge(String knowledgeId, String query) throws IOException {
        // Not implemented for MVP
        return List.of();
    }
    
    @Override
    public KnowledgeCollection getKnowledgeCollection(String knowledgeId) throws IOException {
        LOG.info("Getting knowledge collection via JS bridge: " + knowledgeId);
        
        try {
            JsonObject result = executeKnowledgeApiCall(
                String.format("KnowledgeAPI.getKnowledgeCollection('%s')", 
                    escapeJs(knowledgeId))
            ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            
            if (result.has("success") && result.get("success").getAsBoolean()) {
                if (result.has("collection") && !result.get("collection").isJsonNull()) {
                    JsonElement collectionElement = result.get("collection");
                    return gson.fromJson(collectionElement, KnowledgeCollection.class);
                }
                return null;
            } else {
                String error = result.has("error") ? result.get("error").getAsString() : "Unknown error";
                throw new IOException("Failed to get knowledge collection: " + error);
            }
        } catch (Exception e) {
            LOG.error("Error getting knowledge collection via JS bridge", e);
            throw new IOException("Failed to get knowledge collection", e);
        }
    }
    
    @Override
    public boolean knowledgeExists(String knowledgeId) throws IOException {
        try {
            JsonObject result = executeKnowledgeApiCall(
                String.format("KnowledgeAPI.knowledgeExists('%s')", 
                    escapeJs(knowledgeId))
            ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            
            if (result.has("success") && result.get("success").getAsBoolean()) {
                return true;
            }
            return false;
        } catch (Exception e) {
            LOG.warn("Error checking if knowledge exists via JS bridge", e);
            return false;
        }
    }
    
    /**
     * Execute a Knowledge API call and return the result
     */
    private CompletableFuture<JsonObject> executeKnowledgeApiCall(String apiCall) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                WebBrowserService browserService = WebBrowserService.getInstance(project);
                JCEFBrowserManager browserManager = browserService != null ? browserService.getBrowserPanel().getBrowserManager() : null;
                
                if (browserManager == null) {
                    future.completeExceptionally(new IOException("Browser not available"));
                    return;
                }
                
                // Ensure the KnowledgeAPI is loaded
                String checkScript = "typeof window.KnowledgeAPI !== 'undefined'";
                browserManager.executeJavaScript(
                    "if (!(" + checkScript + ")) { " +
                    "  console.error('KnowledgeAPI not loaded yet'); " +
                    "}"
                );
                
                // Small delay to ensure scripts are loaded
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                // Generate unique callback ID
                String callbackId = "kb_" + callbackCounter.incrementAndGet();
                
                // Store the future for this callback
                pendingCallbacks.put(callbackId, future);
                
                LOG.info("Executing Knowledge API call with callback ID: " + callbackId);
                
                // Build the JavaScript that will execute the API call and send result back
                String script = String.format(
                    "(async () => {" +
                    "  console.log('Executing Knowledge API call...');" +
                    "  try {" +
                    "    const result = await %s;" +
                    "    console.log('Knowledge API call completed:', result);" +
                    "    window.intellijBridge.callIDE('knowledgeApiResult', {" +
                    "      callbackId: '%s'," +
                    "      success: result.success || false," +
                    "      knowledgeId: result.knowledgeId," +
                    "      fileId: result.fileId," +
                    "      collection: result.collection," +
                    "      result: result.result," +
                    "      error: result.error" +
                    "    });" +
                    "  } catch (error) {" +
                    "    console.error('Knowledge API call failed:', error);" +
                    "    window.intellijBridge.callIDE('knowledgeApiResult', {" +
                    "      callbackId: '%s'," +
                    "      success: false," +
                    "      error: error.message || error.toString()" +
                    "    });" +
                    "  }" +
                    "})()",
                    apiCall, callbackId, callbackId
                );
                
                // Execute the script
                browserManager.executeJavaScript(script);
                
                // Set a timeout to clean up if no response
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    try {
                        Thread.sleep(TIMEOUT_SECONDS * 1000);
                        CompletableFuture<JsonObject> pendingFuture = pendingCallbacks.remove(callbackId);
                        if (pendingFuture != null && !pendingFuture.isDone()) {
                            LOG.error("Knowledge API call timed out for callback: " + callbackId);
                            pendingFuture.completeExceptionally(
                                new IOException("Knowledge API call timed out")
                            );
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                
            } catch (Exception e) {
                LOG.error("Error executing Knowledge API call", e);
                future.completeExceptionally(e);
            }
        });
        
        return future;
    }
    
    /**
     * Handle a callback from the JavaScript Knowledge API
     * This should be called from JavaScriptBridgeActions
     */
    public static void handleCallback(String callbackId, JsonObject result) {
        LOG.info("Handling callback for ID: " + callbackId + ", result: " + result);
        
        CompletableFuture<JsonObject> future = pendingCallbacks.remove(callbackId);
        if (future != null) {
            future.complete(result);
        } else {
            LOG.warn("Received callback for unknown ID: " + callbackId);
        }
    }
    
    /**
     * Escape string for JavaScript
     */
    private String escapeJs(String str) {
        if (str == null) return "";
        
        return str.replace("\\", "\\\\")
                  .replace("'", "\\'")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}
