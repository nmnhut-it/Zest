package com.zps.zest.git;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.langchain4j.util.StreamingLLMService;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Service for handling git commit message generation with streaming support.
 */
public class GitCommitMessageService {
    private static final Logger LOG = Logger.getInstance(GitCommitMessageService.class);
    
    private final Project project;
    private final GitService gitService;
    private final Map<String, CommitMessageSession> sessions = new ConcurrentHashMap<>();
    
    public GitCommitMessageService(Project project, GitService gitService) {
        this.project = project;
        this.gitService = gitService;
    }
    
    /**
     * Generates a commit message for the selected files using streaming.
     */
    public String generateCommitMessage(JsonObject data) {
        try {
            JsonArray filesArray = data.getAsJsonArray("files");
            String sessionId = data.has("sessionId") ? data.get("sessionId").getAsString() : 
                              "git-commit-" + System.currentTimeMillis();
            
            LOG.info("Generating commit message for " + filesArray.size() + " files");
            
            // Get the commit prompt template
            ConfigurationManager config = ConfigurationManager.getInstance(project);
            String template = config.getCommitPromptTemplate();
            
            // Build the prompt using the template
            StringBuilder prompt = new StringBuilder(template);
            prompt.append("\n\nChanges to commit:\n\n");
            
            // Collect diffs for each file
            for (JsonElement fileElement : filesArray) {
                JsonObject fileObj = fileElement.getAsJsonObject();
                String filePath = fileObj.get("path").getAsString();
                String status = fileObj.get("status").getAsString();
                
                // Get the diff for this file
                String diff = gitService.getFileDiffContent(filePath, status);
                if (diff != null && !diff.isEmpty()) {
                    prompt.append("File: ").append(filePath).append("\n");
                    prompt.append("Status: ").append(status).append("\n");
                    prompt.append("Changes:\n").append(diff).append("\n\n");
                }
            }
            
            // Store the session for streaming
            CommitMessageSession session = new CommitMessageSession(sessionId, prompt.toString(), project);
            sessions.put(sessionId, session);
            
            JsonObject response = GitServiceHelper.createSuccessResponse();
            response.addProperty("sessionId", sessionId);
            response.addProperty("message", "Streaming session created");
            return GitServiceHelper.toJson(response);
            
        } catch (Exception e) {
            return GitServiceHelper.toJson(GitServiceHelper.createErrorResponse(e));
        }
    }
    
    /**
     * Streams the commit message generation response.
     */
    public String streamCommitMessage(JsonObject data) {
        try {
            String sessionId = data.get("sessionId").getAsString();
            CommitMessageSession session = sessions.get(sessionId);
            
            if (session == null) {
                return GitServiceHelper.toJson(
                    GitServiceHelper.createErrorResponse("Session not found")
                );
            }
            
            // Start streaming if not already started
            if (!session.isStarted()) {
                session.start();
            }
            
            // Get the next chunk
            String chunk = session.getNextChunk();
            if (chunk != null) {
                JsonObject response = GitServiceHelper.createSuccessResponse();
                response.addProperty("chunk", chunk);
                response.addProperty("done", false);
                return GitServiceHelper.toJson(response);
            } else {
                // Streaming is complete
                JsonObject response = GitServiceHelper.createSuccessResponse();
                response.addProperty("done", true);
                response.addProperty("fullMessage", session.getFullMessage());
                
                // Clean up the session
                sessions.remove(sessionId);
                return GitServiceHelper.toJson(response);
            }
            
        } catch (Exception e) {
            return GitServiceHelper.toJson(GitServiceHelper.createErrorResponse(e));
        }
    }
    
    /**
     * Cleans up all sessions.
     */
    public void dispose() {
        sessions.clear();
    }
    
    /**
     * Inner class to manage commit message streaming sessions.
     */
    private static class CommitMessageSession {
        private final String sessionId;
        private final String prompt;
        private final Project project;
        private final StringBuilder fullMessage = new StringBuilder();
        private final BlockingQueue<String> chunks = new LinkedBlockingQueue<>();
        private volatile boolean started = false;
        private volatile boolean completed = false;
        
        CommitMessageSession(String sessionId, String prompt, Project project) {
            this.sessionId = sessionId;
            this.prompt = prompt;
            this.project = project;
        }
        
        boolean isStarted() {
            return started;
        }
        
        void start() {
            if (started) return;
            started = true;
            
            // Start streaming in background
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    StreamingLLMService streamingService = project.getService(StreamingLLMService.class);
                    streamingService.streamQuery(prompt, chunk -> {
                        fullMessage.append(chunk);
                        try {
                            chunks.put(chunk);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).thenAccept(result -> {
                        completed = true;
                        try {
                            chunks.put("__END__");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).exceptionally(ex -> {
                        LOG.error("Streaming failed", ex);
                        completed = true;
                        try {
                            chunks.put("__ERROR__:" + ex.getMessage());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    });
                } catch (Exception e) {
                    LOG.error("Failed to start streaming", e);
                    completed = true;
                }
            });
        }
        
        String getNextChunk() {
            try {
                String chunk = chunks.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (chunk != null) {
                    if (chunk.equals("__END__")) {
                        return null;
                    }
                    if (chunk.startsWith("__ERROR__:")) {
                        throw new RuntimeException(chunk.substring(10));
                    }
                    return chunk;
                }
                
                if (completed && chunks.isEmpty()) {
                    return null;
                }
                
                return "";
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        
        String getFullMessage() {
            return fullMessage.toString();
        }
    }
}