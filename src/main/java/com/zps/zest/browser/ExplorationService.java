package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.agent.ImprovedToolCallingAutonomousAgent;
import com.zps.zest.langchain4j.agent.ImprovedToolCallingAutonomousAgent.*;
import com.zps.zest.rag.RagAgent;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for handling code exploration operations from JavaScript bridge.
 * This service manages autonomous code exploration and progress tracking.
 */
public class ExplorationService {
    private static final Logger LOG = Logger.getInstance(ExplorationService.class);
    
    private final Project project;
    private final Gson gson = new Gson();
    private final ImprovedToolCallingAutonomousAgent explorationAgent;
    private final Map<String, ExplorationSession> activeSessions = new ConcurrentHashMap<>();
    
    public ExplorationService(@NotNull Project project) {
        this.project = project;
        this.explorationAgent = project.getService(ImprovedToolCallingAutonomousAgent.class);
    }
    
    /**
     * Checks if the project has a local RAG index (separate from OpenWebUI knowledge).
     * This is used by the exploration agent's tools.
     */
    private boolean checkLocalRagIndex() {
        // Check if the RagAgent has built a local index
        // This could be checking for cached signatures or a local index file
        RagAgent ragAgent = RagAgent.getInstance(project);
        return ragAgent.hasLocalIndex();
    }
    
    /**
     * Starts a new exploration session.
     */
    public String startExploration(JsonObject data) {
        try {
            // Check if project has local RAG index (not OpenWebUI knowledge)
            boolean hasLocalIndex = checkLocalRagIndex();
            if (!hasLocalIndex) {
                LOG.info("Project not indexed locally - triggering automatic local indexing");
                
                // Notify browser that indexing is starting
                JsonObject indexingResponse = new JsonObject();
                indexingResponse.addProperty("success", false);
                indexingResponse.addProperty("indexing", true);
                indexingResponse.addProperty("message", "Building local code index for exploration...");
                
                // Start local indexing asynchronously
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    try {
                        // Trigger local indexing (this builds the local index for agent tools)
                        RagAgent ragAgent = RagAgent.getInstance(project);
                        ragAgent.buildLocalIndex(); // New method for local-only indexing
                        
                        LOG.info("Local project index built successfully");
                        
                        // Notify browser that indexing is complete
                        ApplicationManager.getApplication().invokeLater(() -> {
                            WebBrowserPanel browserPanel = WebBrowserService.getInstance(project).getBrowserPanel();
                            if (browserPanel != null) {
                                String script = "if (window.handleIndexingComplete) { window.handleIndexingComplete(); }";
                                browserPanel.executeJavaScript(script);
                            }
                        });
                        
                    } catch (Exception e) {
                        LOG.error("Error during local indexing", e);
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
            String sessionId = UUID.randomUUID().toString();
            
            LOG.info("Starting exploration session " + sessionId + " for query: " + query);
            
            // Create new session
            ExplorationSession session = new ExplorationSession(sessionId, query);
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
     * Disposes of resources.
     */
    public void dispose() {
        // Cancel any active sessions
        for (ExplorationSession session : activeSessions.values()) {
            if (session.getFuture() != null && !session.getFuture().isDone()) {
                session.getFuture().cancel(true);
            }
        }
        activeSessions.clear();
    }
    
    /**
     * Represents an active exploration session.
     */
    private static class ExplorationSession {
        private final String sessionId;
        private final String query;
        private final java.util.List<ExplorationRound> rounds = new java.util.ArrayList<>();
        private final java.util.List<ToolExecution> toolExecutions = new java.util.ArrayList<>();
        private ExplorationResult result;
        private CompletableFuture<ExplorationResult> future;
        private boolean completed = false;
        
        public ExplorationSession(String sessionId, String query) {
            this.sessionId = sessionId;
            this.query = query;
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
        public java.util.List<ExplorationRound> getRounds() { return rounds; }
        public java.util.List<ToolExecution> getToolExecutions() { return toolExecutions; }
        public ExplorationResult getResult() { return result; }
        public void setResult(ExplorationResult result) { this.result = result; }
        public CompletableFuture<ExplorationResult> getFuture() { return future; }
        public void setFuture(CompletableFuture<ExplorationResult> future) { this.future = future; }
        public boolean isCompleted() { return completed; }
        public void setCompleted(boolean completed) { this.completed = completed; }
    }
}
