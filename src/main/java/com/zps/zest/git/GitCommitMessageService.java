package com.zps.zest.git;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.langchain4j.naive_service.NaiveStreamingLLMService;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Service for handling git commit message generation with streaming support.
 */
public class GitCommitMessageService {
    private static final Logger LOG = Logger.getInstance(GitCommitMessageService.class);
    private static final int MAX_PROMPT_SIZE = 100000; // ~25K tokens for most models
    private static final int WARNING_PROMPT_SIZE = 50000; // Warn at 50K chars

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

            String promptText = prompt.toString();
            int promptSize = promptText.length();

            // Check if prompt exceeds reasonable size
            if (promptSize > MAX_PROMPT_SIZE) {
                LOG.warn("Prompt size (" + promptSize + ") exceeds maximum (" + MAX_PROMPT_SIZE + ")");
                JsonObject errorResponse = GitServiceHelper.createErrorResponse(
                    "Changes are too large (" + (promptSize / 1000) + "KB). " +
                    "Maximum supported size is " + (MAX_PROMPT_SIZE / 1000) + "KB. " +
                    "Please select fewer files or files with smaller changes."
                );
                errorResponse.addProperty("promptSize", promptSize);
                errorResponse.addProperty("maxSize", MAX_PROMPT_SIZE);
                return GitServiceHelper.toJson(errorResponse);
            }

            // Log warning if approaching limit
            if (promptSize > WARNING_PROMPT_SIZE) {
                LOG.warn("Prompt size (" + promptSize + ") is large and may take longer to process");
            }

            // Store the session for streaming
            CommitMessageSession session = new CommitMessageSession(sessionId, promptText, project);
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
     * Estimates the size of changes for selected files (for UI warning)
     */
    public String estimateChangesSize(JsonObject data) {
        try {
            JsonArray filesArray = data.getAsJsonArray("files");

            // Estimate size by summing diff sizes
            int totalSize = 0;
            for (JsonElement fileElement : filesArray) {
                JsonObject fileObj = fileElement.getAsJsonObject();
                String filePath = fileObj.get("path").getAsString();
                String status = fileObj.get("status").getAsString();

                String diff = gitService.getFileDiffContent(filePath, status);
                if (diff != null) {
                    totalSize += diff.length();
                }
            }

            JsonObject response = GitServiceHelper.createSuccessResponse();
            response.addProperty("estimatedSize", totalSize);
            response.addProperty("sizeKB", totalSize / 1000);
            response.addProperty("isLarge", totalSize > WARNING_PROMPT_SIZE);
            response.addProperty("tooLarge", totalSize > MAX_PROMPT_SIZE);
            response.addProperty("warningThreshold", WARNING_PROMPT_SIZE);
            response.addProperty("maxThreshold", MAX_PROMPT_SIZE);
            return GitServiceHelper.toJson(response);

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
        private static final long TIMEOUT_MS = 120000; // 2 minutes timeout

        private final String sessionId;
        private final String prompt;
        private final Project project;
        private final StringBuilder fullMessage = new StringBuilder();
        private final BlockingQueue<String> chunks = new LinkedBlockingQueue<>();
        private volatile boolean started = false;
        private volatile boolean completed = false;
        private final long createdAt = System.currentTimeMillis();
        private volatile long lastActivityAt = System.currentTimeMillis();

        CommitMessageSession(String sessionId, String prompt, Project project) {
            this.sessionId = sessionId;
            this.prompt = prompt;
            this.project = project;
        }

        boolean isStarted() {
            return started;
        }

        boolean isTimedOut() {
            return (System.currentTimeMillis() - lastActivityAt) > TIMEOUT_MS;
        }
        
        void start() {
            if (started) return;
            started = true;
            
            // Start streaming in background
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    NaiveStreamingLLMService streamingService = project.getService(NaiveStreamingLLMService.class);
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

                        // Provide user-friendly error messages
                        String errorMessage;
                        if (ex.getCause() != null) {
                            String causeMessage = ex.getCause().getMessage();
                            if (causeMessage != null && (causeMessage.contains("timeout") || causeMessage.contains("timed out"))) {
                                errorMessage = "Request timed out - changes may be too large for the model. Please select fewer files.";
                            } else if (causeMessage != null && (causeMessage.contains("context length") || causeMessage.contains("maximum context"))) {
                                errorMessage = "Changes exceed model's context limit. Please select fewer files or files with smaller changes.";
                            } else {
                                errorMessage = "Failed to generate commit message: " + (causeMessage != null ? causeMessage : ex.getMessage());
                            }
                        } else {
                            errorMessage = "Failed to generate commit message: " + ex.getMessage();
                        }

                        try {
                            chunks.put("__ERROR__:" + errorMessage);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    });
                } catch (Exception e) {
                    LOG.error("Failed to start streaming", e);
                    completed = true;
                    // Put error in queue so frontend gets notified
                    try {
                        chunks.put("__ERROR__:Failed to start LLM streaming: " + e.getMessage());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }
        
        String getNextChunk() {
            try {
                // Check for timeout
                if (isTimedOut() && !completed) {
                    LOG.warn("Session timed out: " + sessionId);
                    completed = true;
                    throw new RuntimeException("Session timed out after " + (TIMEOUT_MS / 1000) + " seconds. The request may be too large or the model is unresponsive.");
                }

                String chunk = chunks.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (chunk != null) {
                    lastActivityAt = System.currentTimeMillis(); // Update activity timestamp

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