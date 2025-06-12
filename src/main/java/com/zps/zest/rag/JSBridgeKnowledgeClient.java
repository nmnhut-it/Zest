package com.zps.zest.rag;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.application.ApplicationManager;
import com.zps.zest.browser.WebBrowserPanel;
import com.zps.zest.browser.WebBrowserService;
import com.zps.zest.browser.WebBrowserToolWindow;
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
 * Enhanced version with panel loading state checks.
 */
public class JSBridgeKnowledgeClient implements KnowledgeApiClient {
    private static final Logger LOG = Logger.getInstance(JSBridgeKnowledgeClient.class);
    private static final int TIMEOUT_SECONDS = 60; // Increased from 30
    private static final int PANEL_LOAD_TIMEOUT_SECONDS = 30; // Timeout for waiting for panel to load
    private static final AtomicLong callbackCounter = new AtomicLong(0);
    private static final ConcurrentHashMap<String, CompletableFuture<JsonObject>> pendingCallbacks = new ConcurrentHashMap<>();

    private final Project project;
    private final Gson gson = new Gson();

    public JSBridgeKnowledgeClient(Project project) {
        this.project = project;
    }

    /**
     * Checks if the browser panel is ready for JavaScript execution.
     * @return true if panel is loaded and ready, false otherwise
     */
    private boolean isPanelReady() {
        try {
            WebBrowserService browserService = WebBrowserService.getInstance(project);
            if (browserService == null) {
                LOG.debug("Browser service not available");
                return false;
            }

            WebBrowserPanel browserPanel = browserService.getBrowserPanel();
            if (browserPanel == null) {
                LOG.debug("Browser panel not initialized");
                return false;
            }

            JCEFBrowserManager browserManager = browserPanel.getBrowserManager();
            if (browserManager == null) {
                LOG.debug("Browser manager not available");
                return false;
            }

            String currentUrl = browserPanel.getCurrentUrl();
            if (currentUrl == null || currentUrl.isEmpty() || "about:blank".equals(currentUrl)) {
                LOG.debug("Browser panel has no valid URL loaded");
                return false;
            }

            // Check if the page is actually loaded
            boolean pageLoaded = WebBrowserToolWindow.isPageLoaded(project, currentUrl);
            if (!pageLoaded) {
                LOG.debug("Browser page not fully loaded yet: " + currentUrl);
                return false;
            }

            LOG.debug("Browser panel is ready for JavaScript execution");
            return true;
        } catch (Exception e) {
            LOG.warn("Error checking panel readiness", e);
            return false;
        }
    }

    /**
     * Waits for the browser panel to be ready for JavaScript execution.
     * @param timeoutSeconds Maximum time to wait
     * @return CompletableFuture that completes when panel is ready or times out
     */
    private CompletableFuture<Boolean> waitForPanelReady(int timeoutSeconds) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        // Check immediately first
        if (isPanelReady()) {
            future.complete(true);
            return future;
        }

        // If not ready, wait for page to load
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                long startTime = System.currentTimeMillis();
                long timeoutMs = timeoutSeconds * 1000L;

                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    if (isPanelReady()) {
                        future.complete(true);
                        return;
                    }

                    // Check if we can wait for page load event
                    try {
                        WebBrowserService browserService = WebBrowserService.getInstance(project);
                        if (browserService != null) {
                            WebBrowserPanel browserPanel = browserService.getBrowserPanel();
                            if (browserPanel != null) {
                                String currentUrl = browserPanel.getCurrentUrl();
                                if (currentUrl != null && !currentUrl.isEmpty() && !"about:blank".equals(currentUrl)) {
                                    // Wait for page load with remaining timeout
                                    long remainingMs = timeoutMs - (System.currentTimeMillis() - startTime);
                                    if (remainingMs > 0) {
                                        CompletableFuture<Boolean> pageLoadFuture = WebBrowserToolWindow.waitForPageToLoad(project, currentUrl);
                                        try {
                                            Boolean loaded = pageLoadFuture.get(Math.min(remainingMs, 5000), TimeUnit.MILLISECONDS);
                                            if (Boolean.TRUE.equals(loaded) && isPanelReady()) {
                                                future.complete(true);
                                                return;
                                            }
                                        } catch (Exception e) {
                                            // Continue polling if page load wait fails
                                            LOG.debug("Page load wait failed, continuing to poll", e);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOG.debug("Error during page load wait", e);
                    }

                    // Polling interval
                    Thread.sleep(500);
                }

                // Timeout reached
                LOG.warn("Timeout waiting for browser panel to be ready");
                future.complete(false);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.completeExceptionally(e);
            } catch (Exception e) {
                LOG.error("Error waiting for panel to be ready", e);
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    /**
     * Find knowledge base by name pattern using JS bridge
     */
    public String findKnowledgeByName(String namePattern) throws IOException {
        LOG.info("Finding knowledge base by name: " + namePattern);

        try {
            // Wait for panel to be ready first
            Boolean panelReady = waitForPanelReady(PANEL_LOAD_TIMEOUT_SECONDS)
                    .get(PANEL_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!Boolean.TRUE.equals(panelReady)) {
                throw new IOException("Browser panel is not ready. Please wait for the page to load completely.");
            }

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
            if (e.getMessage().contains("Browser panel is not ready")) {
                throw new IOException(e.getMessage(), e);
            }
            return null;
        }
    }

    @Override
    public String createKnowledgeBase(String name, String description) throws IOException {
        LOG.info("Creating knowledge base via JS bridge: " + name);

        try {
            // Wait for panel to be ready first
            Boolean panelReady = waitForPanelReady(PANEL_LOAD_TIMEOUT_SECONDS)
                    .get(PANEL_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!Boolean.TRUE.equals(panelReady)) {
                throw new IOException("Browser panel is not ready. Please wait for the page to load completely.");
            }

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
            if (e.getMessage().contains("Browser panel is not ready")) {
                throw new IOException(e.getMessage(), e);
            }
            throw new IOException("Failed to create knowledge base", e);
        }
    }

    @Override
    public String uploadFile(String fileName, String content) throws IOException {
        LOG.info("Uploading file via JS bridge: " + fileName);

        try {
            // Wait for panel to be ready first
            Boolean panelReady = waitForPanelReady(PANEL_LOAD_TIMEOUT_SECONDS)
                    .get(PANEL_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!Boolean.TRUE.equals(panelReady)) {
                throw new IOException("Browser panel is not ready. Please wait for the page to load completely.");
            }

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
            if (e.getMessage().contains("Browser panel is not ready")) {
                throw new IOException(e.getMessage(), e);
            }
            throw new IOException("Failed to upload file", e);
        }
    }

    @Override
    public void addFileToKnowledge(String knowledgeId, String fileId) throws IOException {
        LOG.info("Adding file to knowledge base: " + fileId + " -> " + knowledgeId);

        try {
            // Wait for panel to be ready first
            Boolean panelReady = waitForPanelReady(PANEL_LOAD_TIMEOUT_SECONDS)
                    .get(PANEL_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!Boolean.TRUE.equals(panelReady)) {
                throw new IOException("Browser panel is not ready. Please wait for the page to load completely.");
            }

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
            if (e.getMessage().contains("Browser panel is not ready")) {
                throw new IOException(e.getMessage(), e);
            }
            throw new IOException("Failed to add file to knowledge", e);
        }
    }

    @Override
    public void removeFileFromKnowledge(String knowledgeId, String fileId) throws IOException {
        LOG.info("Removing file from knowledge base: " + fileId + " from " + knowledgeId);

        try {
            // Wait for panel to be ready first
            Boolean panelReady = waitForPanelReady(PANEL_LOAD_TIMEOUT_SECONDS)
                    .get(PANEL_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!Boolean.TRUE.equals(panelReady)) {
                throw new IOException("Browser panel is not ready. Please wait for the page to load completely.");
            }

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
            if (e.getMessage().contains("Browser panel is not ready")) {
                throw new IOException(e.getMessage(), e);
            }
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
            // Wait for panel to be ready first
            Boolean panelReady = waitForPanelReady(PANEL_LOAD_TIMEOUT_SECONDS)
                    .get(PANEL_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!Boolean.TRUE.equals(panelReady)) {
                throw new IOException("Browser panel is not ready. Please wait for the page to load completely.");
            }

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
            if (e.getMessage().contains("Browser panel is not ready")) {
                throw new IOException(e.getMessage(), e);
            }
            throw new IOException("Failed to get knowledge collection", e);
        }
    }

    @Override
    public boolean knowledgeExists(String knowledgeId) throws IOException {
        try {
            // Wait for panel to be ready first
            Boolean panelReady = waitForPanelReady(PANEL_LOAD_TIMEOUT_SECONDS)
                    .get(PANEL_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!Boolean.TRUE.equals(panelReady)) {
                LOG.warn("Browser panel is not ready for knowledge existence check");
                return false;
            }

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
     * Execute a Knowledge API call and return the result.
     * This method assumes the panel is already ready.
     */
    private CompletableFuture<JsonObject> executeKnowledgeApiCall(String apiCall) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                WebBrowserService browserService = WebBrowserService.getInstance(project);
                if (browserService == null) {
                    future.completeExceptionally(new IOException("Browser service not available"));
                    return;
                }

                WebBrowserPanel browserPanel = browserService.getBrowserPanel();
                if (browserPanel == null) {
                    future.completeExceptionally(new IOException("Browser panel not initialized"));
                    return;
                }

                JCEFBrowserManager browserManager = browserPanel.getBrowserManager();
                if (browserManager == null) {
                    future.completeExceptionally(new IOException("Browser manager not available"));
                    return;
                }

                // Enhanced check for KnowledgeAPI availability
                String checkScript =
                        "if (typeof window.KnowledgeAPI === 'undefined') { " +
                                "  console.error('KnowledgeAPI not loaded yet'); " +
                                "  'API_NOT_READY'; " +
                                "} else { " +
                                "  'API_READY'; " +
                                "}";

                // Execute API availability check first
                browserManager.executeJavaScript(checkScript);

                // Small delay to ensure scripts are loaded
                try {
                    Thread.sleep(200);
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
                                "    if (typeof window.KnowledgeAPI === 'undefined') {" +
                                "      throw new Error('KnowledgeAPI is not available');" +
                                "    }" +
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