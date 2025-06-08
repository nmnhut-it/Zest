package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.zps.zest.langchain4j.agent.ImprovedToolCallingAutonomousAgent;
import com.zps.zest.langchain4j.agent.ImprovedToolCallingAutonomousAgent.*;
import com.zps.zest.langchain4j.HybridIndexManager;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service for handling code exploration operations from JavaScript bridge.
 * This service manages autonomous code exploration and progress tracking.
 */
public class ExplorationService implements Disposable {
    private static final Logger LOG = Logger.getInstance(ExplorationService.class);

    // Configuration for context management
    private static final long CONTEXT_EXPIRY_HOURS = 24;
    private static final long CLEANUP_INTERVAL_MINUTES = 60;
    private static final int MAX_CONTEXTS = 100;

    private final Project project;
    private final Gson gson = new Gson();
    private final ImprovedToolCallingAutonomousAgent explorationAgent;
    private final Map<String, ExplorationSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, ConversationContext> conversationContexts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor();

    public ExplorationService(@NotNull Project project) {
        this.project = project;
        this.explorationAgent = project.getService(ImprovedToolCallingAutonomousAgent.class);

        // Register as disposable
        Disposer.register(project, this);

        // Schedule periodic cleanup
        cleanupExecutor.scheduleAtFixedRate(
            this::cleanupExpiredContexts,
            CLEANUP_INTERVAL_MINUTES,
            CLEANUP_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        );

        LOG.info("ExplorationService initialized with context cleanup every " + CLEANUP_INTERVAL_MINUTES + " minutes");
    }

    /**
     * Checks if the project has been indexed for code exploration.
     * This uses the HybridIndexManager which provides the indices for search tools.
     */
    private boolean checkHybridIndex() {
        HybridIndexManager hybridIndexManager = project.getService(HybridIndexManager.class);
        return hybridIndexManager.hasIndex();
    }

    /**
     * Starts a new exploration session.
     */
    public String startExploration(JsonObject data) {
        try {
            // Check if project has hybrid index for code exploration
            boolean hasIndex = checkHybridIndex();
            if (!hasIndex) {
                LOG.info("Project not indexed - triggering automatic hybrid indexing");

                // Notify browser that indexing is starting
                JsonObject indexingResponse = new JsonObject();
                indexingResponse.addProperty("success", false);
                indexingResponse.addProperty("indexing", true);
                indexingResponse.addProperty("message", "Building hybrid code index for exploration...");

                // Start hybrid indexing asynchronously
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    try {
                        // Trigger hybrid indexing (this builds the indices for search tools)
                        HybridIndexManager hybridIndexManager = project.getService(HybridIndexManager.class);
                        CompletableFuture<Boolean> indexingFuture = hybridIndexManager.indexProjectAsync(false);

                        indexingFuture.thenAccept(success -> {
                            if (success) {
                                LOG.info("Hybrid project index built successfully");

                                // Notify browser that indexing is complete
                                ApplicationManager.getApplication().invokeLater(() -> {
                                    WebBrowserPanel browserPanel = WebBrowserService.getInstance(project).getBrowserPanel();
                                    if (browserPanel != null) {
                                        String script = "if (window.handleIndexingComplete) { window.handleIndexingComplete(); }";
                                        browserPanel.executeJavaScript(script);
                                    }
                                });
                            } else {
                                LOG.error("Failed to build hybrid index");
                                // Notify browser of indexing failure
                                ApplicationManager.getApplication().invokeLater(() -> {
                                    WebBrowserPanel browserPanel = WebBrowserService.getInstance(project).getBrowserPanel();
                                    if (browserPanel != null) {
                                        String script = "if (window.handleIndexingError) { window.handleIndexingError('Failed to build hybrid index'); }";
                                        browserPanel.executeJavaScript(script);
                                    }
                                });
                            }
                        });

                    } catch (Exception e) {
                        LOG.error("Error during hybrid indexing", e);
                        // Notify browser of indexing error
                        ApplicationManager.getApplication().invokeLater(() -> {
                            WebBrowserPanel browserPanel = WebBrowserService.getInstance(project).getBrowserPanel();
                            if (browserPanel != null) {
                                String script = String.format(
                                    "if (window.handleIndexingError) { window.handleIndexingError('%s'); }",
                                    e.getMessage().replace("'", "\\'")
                                );
                                browserPanel.executeJavaScript(script);
                            }
                        });
                    }
                });

                return gson.toJson(indexingResponse);
            }

            String query = data.get("query").getAsString();
            String conversationId = null;
            if (data.has("conversationId") && !data.get("conversationId").isJsonNull()) {
                String id = data.get("conversationId").getAsString();
                if (!id.isEmpty()) {
                    conversationId = id;
                }
            }
            String sessionId = UUID.randomUUID().toString();

            LOG.info("Starting exploration session " + sessionId + " for query: " + query +
                     (conversationId != null ? ", conversation: " + conversationId : ""));

            // Create new session
            ExplorationSession session = new ExplorationSession(sessionId, query, conversationId);
            activeSessions.put(sessionId, session);

            // Start exploration asynchronously
            CompletableFuture<ExplorationResult> future = explorationAgent.exploreWithToolsAsync(
                query,
                new ProgressCallback() {
                    @Override
                    public void onToolExecution(ToolExecution execution) {
                        session.addToolExecution(execution);
                        notifyBrowserOfProgress(sessionId, "tool_execution", execution);
                    }

                    @Override
                    public void onRoundComplete(ExplorationRound round) {
                        session.addRound(round);
                        notifyBrowserOfProgress(sessionId, "round_complete", round);
                    }

                    @Override
                    public void onExplorationComplete(ExplorationResult result) {
                        session.setResult(result);
                        session.setCompleted(true);

                        // Store context for conversation if ID is provided
                        String convId = session.getConversationId();
                        if (convId != null && result != null && result.getSummary() != null) {
                            storeConversationContext(convId, query, result.getSummary());
                        }

                        notifyBrowserOfProgress(sessionId, "exploration_complete", result);

                    }
                }
            );

            session.setFuture(future);

            // Return session ID
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("sessionId", sessionId);
            return gson.toJson(response);

        } catch (Exception e) {
            LOG.error("Error starting exploration", e);
            JsonObject response = new JsonObject();
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
            return gson.toJson(response);
        }
    }

    /**
     * Gets the status of an exploration session.
     */
    public String getExplorationStatus(JsonObject data) {
        try {
            String sessionId = data.get("sessionId").getAsString();
            ExplorationSession session = activeSessions.get(sessionId);

            if (session == null) {
                JsonObject response = new JsonObject();
                response.addProperty("success", false);
                response.addProperty("error", "Session not found");
                return gson.toJson(response);
            }

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("completed", session.isCompleted());
            response.addProperty("query", session.getQuery());

            if (session.getResult() != null) {
                response.add("result", gson.toJsonTree(session.getResult()));
            }

            response.add("rounds", gson.toJsonTree(session.getRounds()));
            response.add("toolExecutions", gson.toJsonTree(session.getToolExecutions()));

            return gson.toJson(response);

        } catch (Exception e) {
            LOG.error("Error getting exploration status", e);
            JsonObject response = new JsonObject();
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
            return gson.toJson(response);
        }
    }

    /**
     * Notifies the browser of exploration progress.
     */
    private void notifyBrowserOfProgress(String sessionId, String eventType, Object data) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                WebBrowserPanel browserPanel = WebBrowserService.getInstance(project).getBrowserPanel();
                if (browserPanel != null) {
                    JsonObject event = new JsonObject();
                    event.addProperty("type", "explorationProgress");
                    event.addProperty("sessionId", sessionId);
                    event.addProperty("eventType", eventType);
                    event.add("data", gson.toJsonTree(data));

                    String script = String.format(
                        "if (window.handleExplorationProgress) { window.handleExplorationProgress(%s); }",
                        gson.toJson(event)
                    );

                    browserPanel.executeJavaScript(script);
                }
            } catch (Exception e) {
                LOG.error("Error notifying browser of progress", e);
            }
        });
    }



    /**
     * Gets exploration context by conversation ID.
     */
    public String getExplorationContext(JsonObject data) {
        try {
            if (!data.has("conversationId") || data.get("conversationId").isJsonNull()) {
                JsonObject response = new JsonObject();
                response.addProperty("success", false);
                response.addProperty("error", "No conversation ID provided");
                return gson.toJson(response);
            }
            
            String conversationId = data.get("conversationId").getAsString();
            if (conversationId.isEmpty()) {
                JsonObject response = new JsonObject();
                response.addProperty("success", false);
                response.addProperty("error", "Empty conversation ID");
                return gson.toJson(response);
            }
            
            ConversationContext context = conversationContexts.get(conversationId);
            
            JsonObject response = new JsonObject();
            if (context != null) {
                response.addProperty("success", true);
                response.addProperty("context", context.explorationSummary);
                response.addProperty("query", context.originalQuery);
                response.addProperty("timestamp", context.createdAt.toString());
                
                // Update last accessed time
                context.lastAccessedAt = Instant.now();
            } else {
                response.addProperty("success", false);
                response.addProperty("error", "No context found for conversation");
            }
            
            return gson.toJson(response);
            
        } catch (Exception e) {
            LOG.error("Error getting exploration context", e);
            JsonObject response = new JsonObject();
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
            return gson.toJson(response);
        }
    }
    
    /**
     * Stores exploration context for a conversation.
     */
    private void storeConversationContext(String conversationId, String query, String summary) {
        // Check if we need to evict old contexts
        if (conversationContexts.size() >= MAX_CONTEXTS) {
            evictOldestContext();
        }
        
        ConversationContext context = new ConversationContext(conversationId, query, summary);
        conversationContexts.put(conversationId, context);
        
        LOG.info("Stored exploration context for conversation " + conversationId);
    }
    
    /**
     * Evicts the oldest context when limit is reached.
     */
    private void evictOldestContext() {
        String oldestId = null;
        Instant oldestTime = Instant.now();
        
        for (Map.Entry<String, ConversationContext> entry : conversationContexts.entrySet()) {
            if (entry.getValue().lastAccessedAt.isBefore(oldestTime)) {
                oldestTime = entry.getValue().lastAccessedAt;
                oldestId = entry.getKey();
            }
        }
        
        if (oldestId != null) {
            conversationContexts.remove(oldestId);
            LOG.info("Evicted oldest context: " + oldestId);
        }
    }
    
    /**
     * Cleans up expired contexts periodically.
     */
    private void cleanupExpiredContexts() {
        Instant expiryTime = Instant.now().minusSeconds(CONTEXT_EXPIRY_HOURS * 3600);
        Iterator<Map.Entry<String, ConversationContext>> iterator = conversationContexts.entrySet().iterator();
        
        int removed = 0;
        while (iterator.hasNext()) {
            Map.Entry<String, ConversationContext> entry = iterator.next();
            if (entry.getValue().lastAccessedAt.isBefore(expiryTime)) {
                iterator.remove();
                removed++;
            }
        }
        
        if (removed > 0) {
            LOG.info("Cleaned up " + removed + " expired conversation contexts");
        }
    }

    /**
     * Disposes of resources.
     */
    @Override
    public void dispose() {
        // Shutdown cleanup executor
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Cancel any active sessions
        for (ExplorationSession session : activeSessions.values()) {
            if (session.getFuture() != null && !session.getFuture().isDone()) {
                session.getFuture().cancel(true);
            }
        }
        activeSessions.clear();
        conversationContexts.clear();
    }

    /**
     * Represents an active exploration session.
     */
    private static class ExplorationSession {
        private final String sessionId;
        private final String query;
        private final String conversationId;
        private final java.util.List<ExplorationRound> rounds = new java.util.ArrayList<>();
        private final java.util.List<ToolExecution> toolExecutions = new java.util.ArrayList<>();
        private ExplorationResult result;
        private CompletableFuture<ExplorationResult> future;
        private boolean completed = false;

        public ExplorationSession(String sessionId, String query, String conversationId) {
            this.sessionId = sessionId;
            this.query = query;
            this.conversationId = conversationId;
        }

        public void addRound(ExplorationRound round) {
            rounds.add(round);
        }

        public void addToolExecution(ToolExecution execution) {
            toolExecutions.add(execution);
        }

        // Getters and setters
        public String getSessionId() { return sessionId; }
        public String getQuery() { return query; }
        public String getConversationId() { return conversationId; }
        public java.util.List<ExplorationRound> getRounds() { return rounds; }
        public java.util.List<ToolExecution> getToolExecutions() { return toolExecutions; }
        public ExplorationResult getResult() { return result; }
        public void setResult(ExplorationResult result) { this.result = result; }
        public CompletableFuture<ExplorationResult> getFuture() { return future; }
        public void setFuture(CompletableFuture<ExplorationResult> future) { this.future = future; }
        public boolean isCompleted() { return completed; }
        public void setCompleted(boolean completed) { this.completed = completed; }
    }
    
    /**
     * Represents a stored conversation context.
     */
    private static class ConversationContext {
        private final String conversationId;
        private final String originalQuery;
        private final String explorationSummary;
        private final Instant createdAt;
        private Instant lastAccessedAt;
        
        public ConversationContext(String conversationId, String originalQuery, String explorationSummary) {
            this.conversationId = conversationId;
            this.originalQuery = originalQuery;
            this.explorationSummary = explorationSummary;
            this.createdAt = Instant.now();
            this.lastAccessedAt = Instant.now();
        }
    }
}
