package com.zps.zest.git;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.langchain4j.naive_service.NaiveStreamingLLMService;

import java.util.List;
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

    private static final java.util.Set<String> BINARY_EXTENSIONS = java.util.Set.of(
        "png", "jpg", "jpeg", "gif", "svg", "ico", "webp", "bmp",
        "zip", "jar", "war", "tar", "gz", "rar", "7z",
        "exe", "dll", "so", "dylib", "class", "o", "pyc",
        "mp4", "mp3", "wav", "avi", "mov", "pdf",
        "db", "sqlite", "dat", "bin"
    );

    private final Project project;
    private final GitService gitService;
    private final Map<String, CommitMessageSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, PreviewSession> previewSessions = new ConcurrentHashMap<>();

    private boolean isBinaryFile(String filePath) {
        int lastDot = filePath.lastIndexOf('.');
        if (lastDot == -1) return false;
        String ext = filePath.substring(lastDot + 1).toLowerCase();
        return BINARY_EXTENSIONS.contains(ext);
    }

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

            int binaryFilesSkipped = 0;
            int filesAnalyzed = 0;
            java.util.List<String> skippedBinaryNames = new java.util.ArrayList<>();

            // Collect diffs for each file
            for (JsonElement fileElement : filesArray) {
                JsonObject fileObj = fileElement.getAsJsonObject();
                String filePath = fileObj.get("path").getAsString();
                String status = fileObj.get("status").getAsString();

                if (isBinaryFile(filePath)) {
                    LOG.info("Skipping binary file: " + filePath);
                    binaryFilesSkipped++;
                    skippedBinaryNames.add(filePath);
                    continue;
                }

                String diff = gitService.getFileDiffContent(filePath, status);
                if (diff != null && !diff.isEmpty()) {
                    prompt.append("File: ").append(filePath).append("\n");
                    prompt.append("Status: ").append(status).append("\n");
                    prompt.append("Changes:\n").append(diff).append("\n\n");
                    filesAnalyzed++;
                }
            }

            String promptText = prompt.toString();
            int promptSize = promptText.length();

            LOG.info("Commit message generation: " + filesAnalyzed + " files analyzed, " +
                     binaryFilesSkipped + " binary files skipped, prompt size: " + promptSize + " chars");

            // Check if prompt exceeds reasonable size - switch to hierarchical generation
            if (promptSize > MAX_PROMPT_SIZE) {
                LOG.warn("Prompt size (" + promptSize + ") exceeds maximum (" + MAX_PROMPT_SIZE + "), switching to hierarchical generation");

                JsonObject response = GitServiceHelper.createSuccessResponse();
                response.addProperty("requiresHierarchical", true);
                response.addProperty("promptSize", promptSize);
                response.addProperty("promptSizeKB", promptSize / 1000);
                response.addProperty("filesAnalyzed", filesAnalyzed);
                response.addProperty("binaryFilesSkipped", binaryFilesSkipped);
                response.addProperty("message", "Changeset is too large for single generation. Use hierarchical generation.");
                return GitServiceHelper.toJson(response);
            }

            // Log warning if approaching limit
            if (promptSize > WARNING_PROMPT_SIZE) {
                LOG.warn("Prompt size (" + promptSize + ") is large and may take longer to process");
            }

            // Add context note for large changesets
            if (promptSize > WARNING_PROMPT_SIZE) {
                String contextNote = "\n\nIMPORTANT: This is a large changeset (" + (promptSize / 1000) + "KB). " +
                                    "Prioritize main changes and provide a high-level summary covering key themes.\n\n";
                promptText = contextNote + promptText;
            }

            // Store the session for streaming
            CommitMessageSession session = new CommitMessageSession(sessionId, promptText, project);
            sessions.put(sessionId, session);

            JsonObject response = GitServiceHelper.createSuccessResponse();
            response.addProperty("sessionId", sessionId);
            response.addProperty("message", "Streaming session created");
            response.addProperty("filesAnalyzed", filesAnalyzed);
            response.addProperty("binaryFilesSkipped", binaryFilesSkipped);
            response.addProperty("isLargeChangeset", promptSize > WARNING_PROMPT_SIZE);
            response.addProperty("promptSizeKB", promptSize / 1000);
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

            int totalSize = 0;
            int binaryFilesSkipped = 0;
            int filesAnalyzed = 0;

            for (JsonElement fileElement : filesArray) {
                JsonObject fileObj = fileElement.getAsJsonObject();
                String filePath = fileObj.get("path").getAsString();
                String status = fileObj.get("status").getAsString();

                if (isBinaryFile(filePath)) {
                    binaryFilesSkipped++;
                    continue;
                }

                String diff = gitService.getFileDiffContent(filePath, status);
                if (diff != null) {
                    totalSize += diff.length();
                    filesAnalyzed++;
                }
            }

            JsonObject response = GitServiceHelper.createSuccessResponse();
            response.addProperty("estimatedSize", totalSize);
            response.addProperty("sizeKB", totalSize / 1000);
            response.addProperty("isLarge", totalSize > WARNING_PROMPT_SIZE);
            response.addProperty("tooLarge", totalSize > MAX_PROMPT_SIZE);
            response.addProperty("warningThreshold", WARNING_PROMPT_SIZE);
            response.addProperty("maxThreshold", MAX_PROMPT_SIZE);
            response.addProperty("filesAnalyzed", filesAnalyzed);
            response.addProperty("binaryFilesSkipped", binaryFilesSkipped);
            return GitServiceHelper.toJson(response);

        } catch (Exception e) {
            return GitServiceHelper.toJson(GitServiceHelper.createErrorResponse(e));
        }
    }

    /**
     * Gets the current progress of a hierarchical generation session.
     */
    public String getGenerationProgress(JsonObject data) {
        try {
            String sessionId = data.get("sessionId").getAsString();
            CommitMessageSession session = sessions.get(sessionId);

            if (session == null) {
                return GitServiceHelper.toJson(
                        GitServiceHelper.createErrorResponse("Session not found")
                );
            }

            GenerationProgress progress = session.getProgress();
            if (progress != null) {
                JsonObject response = GitServiceHelper.createSuccessResponse();
                response.addProperty("currentGroup", progress.currentGroupName);
                response.addProperty("currentGroupDescription", progress.currentGroupDescription);
                response.addProperty("groupIndex", progress.currentGroupIndex);
                response.addProperty("totalGroups", progress.totalGroups);
                response.addProperty("percentage", progress.getPercentage());
                response.addProperty("isHierarchical", progress.isHierarchical);

                JsonArray completedArray = new JsonArray();
                progress.completedGroups.forEach(completedArray::add);
                response.add("completedGroups", completedArray);

                return GitServiceHelper.toJson(response);
            }

            return GitServiceHelper.toJson(GitServiceHelper.createSuccessResponse());

        } catch (Exception e) {
            return GitServiceHelper.toJson(GitServiceHelper.createErrorResponse(e));
        }
    }

    /**
     * Generates preview of how files will be grouped for hierarchical generation.
     * Uses fast file size estimation instead of full diff generation for performance.
     * For 150 files: ~2 seconds (fast estimation) vs ~60 seconds (full diffs).
     */
    public String previewFileGroups(JsonObject data) {
        try {
            JsonArray filesArray = data.getAsJsonArray("files");
            LOG.info("Generating group preview for " + filesArray.size() + " files (fast mode)");

            long startTime = System.currentTimeMillis();
            String projectPath = project.getBasePath();

            // Collect file information with ESTIMATED sizes (no full diffs!)
            List<FileGroup.FileInfo> fileInfos = new java.util.ArrayList<>();
            for (JsonElement fileElement : filesArray) {
                JsonObject fileObj = fileElement.getAsJsonObject();
                String filePath = fileObj.get("path").getAsString();
                String status = fileObj.get("status").getAsString();

                if (isBinaryFile(filePath)) {
                    continue;
                }

                // Fast: estimate size from file stats instead of getting full diff
                int estimatedSize = estimateFileDiffSize(projectPath, filePath, status);
                fileInfos.add(new FileGroup.FileInfo(filePath, status, estimatedSize));
            }

            // Group files using directory-based strategy
            FileGroupStrategy strategy = new DirectoryBasedGrouping();
            List<FileGroup> groups = strategy.groupFiles(fileInfos);

            long elapsed = System.currentTimeMillis() - startTime;
            LOG.info("Group preview generated in " + elapsed + "ms for " +
                    fileInfos.size() + " files into " + groups.size() + " groups");

            // Build response
            JsonObject response = GitServiceHelper.createSuccessResponse();
            response.addProperty("totalGroups", groups.size());
            response.addProperty("totalFiles", fileInfos.size());
            response.addProperty("strategy", strategy.getStrategyName());
            response.addProperty("previewTimeMs", elapsed);

            JsonArray groupsArray = new JsonArray();
            for (FileGroup group : groups) {
                JsonObject groupObj = new JsonObject();
                groupObj.addProperty("name", group.getName());
                groupObj.addProperty("description", group.getDescription());
                groupObj.addProperty("fileCount", group.getFileCount());
                groupObj.addProperty("sizeKB", group.getEstimatedSizeKB());

                JsonArray filesInGroup = new JsonArray();
                group.getFiles().forEach(f -> filesInGroup.add(f.getPath()));
                groupObj.add("files", filesInGroup);

                groupsArray.add(groupObj);
            }
            response.add("groups", groupsArray);

            return GitServiceHelper.toJson(response);

        } catch (Exception e) {
            LOG.error("Error generating group preview", e);
            return GitServiceHelper.toJson(GitServiceHelper.createErrorResponse(e));
        }
    }

    /**
     * Quickly estimates diff size without getting the full diff.
     * Uses file size for new files or git diff --stat for changed files.
     * Much faster than getFileDiffContent: ~10ms vs ~500ms per file.
     */
    private int estimateFileDiffSize(String projectPath, String filePath, String status) {
        try {
            if ("A".equals(status)) {
                // New file - use actual file size
                java.io.File file = new java.io.File(projectPath, filePath);
                if (file.exists()) {
                    return (int) file.length();
                }
                return 1000; // Default estimate
            } else if ("D".equals(status)) {
                // Deleted file - estimate from git show
                return estimateSizeFromGitStat(projectPath, filePath);
            } else {
                // Modified file - use git diff --stat (fast!)
                return estimateSizeFromGitStat(projectPath, filePath);
            }

        } catch (Exception e) {
            LOG.debug("Error estimating file size for " + filePath + ": " + e.getMessage());
            return 2000; // Conservative default estimate
        }
    }

    /**
     * Gets quick size estimate from git diff --stat (one-line output).
     * Example output: " src/Main.java | 25 +++++++++++-"
     */
    private int estimateSizeFromGitStat(String projectPath, String filePath) {
        try {
            // --stat gives quick summary: " file | 25 ++++--"
            String statOutput = GitServiceHelper.executeGitCommand(projectPath,
                    "git diff --stat -- " + com.zps.zest.browser.utils.GitCommandExecutor.escapeFilePath(filePath));

            // Parse number of changes from stat output
            int changes = parseChangesFromStat(statOutput);
            return changes * 50; // Estimate ~50 bytes per changed line

        } catch (Exception e) {
            LOG.debug("Stat estimation failed for " + filePath + ", using default");
            return 2000; // Default estimate on error
        }
    }

    /**
     * Parses insertion/deletion count from git diff --stat output.
     * Example: " src/Main.java | 25 +++++++++++-" → returns 25
     */
    private int parseChangesFromStat(String statOutput) {
        try {
            // Format: " file.java | 25 +++++++++++-"
            String[] parts = statOutput.split("\\|");
            if (parts.length > 1) {
                String changesPart = parts[1].trim();
                // Extract first number (total changes)
                String[] tokens = changesPart.split("\\s+");
                if (tokens.length > 0) {
                    return Integer.parseInt(tokens[0]);
                }
            }
        } catch (Exception e) {
            LOG.debug("Failed to parse stat output: " + statOutput);
        }
        return 40; // Default estimate: ~40 changed lines
    }

    /**
     * Starts preview file groups generation asynchronously with progress tracking.
     * Returns immediately with a session ID that can be polled for progress.
     */
    public String startPreviewFileGroups(JsonObject data) {
        try {
            JsonArray filesArray = data.getAsJsonArray("files");
            String sessionId = "preview-" + System.currentTimeMillis();

            LOG.info("Starting preview generation for " + filesArray.size() + " files, session: " + sessionId);

            // Create session
            PreviewSession session = new PreviewSession(sessionId, filesArray.size());
            previewSessions.put(sessionId, session);

            // Process files in background thread
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    String projectPath = project.getBasePath();
                    List<FileGroup.FileInfo> fileInfos = new java.util.ArrayList<>();

                    for (int i = 0; i < filesArray.size(); i++) {
                        JsonObject fileObj = filesArray.get(i).getAsJsonObject();
                        String filePath = fileObj.get("path").getAsString();
                        String status = fileObj.get("status").getAsString();

                        // Update progress
                        session.filesProcessed = i + 1;
                        session.currentFile = filePath;

                        if (isBinaryFile(filePath)) {
                            continue;
                        }

                        // Fast estimation
                        int estimatedSize = estimateFileDiffSize(projectPath, filePath, status);
                        fileInfos.add(new FileGroup.FileInfo(filePath, status, estimatedSize));
                    }

                    // Group files
                    FileGroupStrategy strategy = new DirectoryBasedGrouping();
                    List<FileGroup> groups = strategy.groupFiles(fileInfos);

                    // Mark as complete
                    session.groups = groups;
                    session.status = PreviewSessionStatus.COMPLETE;

                    LOG.info("Preview generation complete for session " + sessionId +
                            ": " + groups.size() + " groups from " + fileInfos.size() + " files");

                } catch (Exception e) {
                    LOG.error("Preview generation failed for session " + sessionId, e);
                    session.status = PreviewSessionStatus.ERROR;
                    session.error = e.getMessage();
                }
            });

            // Return session info immediately
            JsonObject response = GitServiceHelper.createSuccessResponse();
            response.addProperty("sessionId", sessionId);
            response.addProperty("status", "started");
            response.addProperty("totalFiles", filesArray.size());

            return GitServiceHelper.toJson(response);

        } catch (Exception e) {
            LOG.error("Error starting preview generation", e);
            return GitServiceHelper.toJson(GitServiceHelper.createErrorResponse(e));
        }
    }

    /**
     * Gets the current progress of an ongoing preview generation.
     */
    public String getPreviewProgress(JsonObject data) {
        try {
            String sessionId = data.get("sessionId").getAsString();
            PreviewSession session = previewSessions.get(sessionId);

            if (session == null) {
                return GitServiceHelper.toJson(
                        GitServiceHelper.createErrorResponse("Preview session not found")
                );
            }

            JsonObject response = GitServiceHelper.createSuccessResponse();
            response.addProperty("sessionId", sessionId);
            response.addProperty("status", session.status.toString().toLowerCase());
            response.addProperty("filesProcessed", session.filesProcessed);
            response.addProperty("totalFiles", session.totalFiles);
            response.addProperty("currentFile", session.currentFile);

            if (session.status == PreviewSessionStatus.COMPLETE && session.groups != null) {
                // Include groups in response
                response.addProperty("totalGroups", session.groups.size());

                JsonArray groupsArray = new JsonArray();
                for (FileGroup group : session.groups) {
                    JsonObject groupObj = new JsonObject();
                    groupObj.addProperty("name", group.getName());
                    groupObj.addProperty("description", group.getDescription());
                    groupObj.addProperty("fileCount", group.getFileCount());
                    groupObj.addProperty("sizeKB", group.getEstimatedSizeKB());

                    JsonArray filesInGroup = new JsonArray();
                    group.getFiles().forEach(f -> filesInGroup.add(f.getPath()));
                    groupObj.add("files", filesInGroup);

                    groupsArray.add(groupObj);
                }
                response.add("groups", groupsArray);

                // Clean up session after returning results
                previewSessions.remove(sessionId);

            } else if (session.status == PreviewSessionStatus.ERROR) {
                response.addProperty("error", session.error);
                // Clean up failed session
                previewSessions.remove(sessionId);
            }

            return GitServiceHelper.toJson(response);

        } catch (Exception e) {
            LOG.error("Error getting preview progress", e);
            return GitServiceHelper.toJson(GitServiceHelper.createErrorResponse(e));
        }
    }

    /**
     * Cleans up all sessions.
     */
    public void dispose() {
        sessions.clear();
        previewSessions.clear();
    }

    /**
     * Tracks progress of hierarchical commit message generation.
     */
    private static class GenerationProgress {
        String currentGroupName;
        String currentGroupDescription;
        int currentGroupIndex;
        int totalGroups;
        boolean isHierarchical;
        List<String> completedGroups = new java.util.ArrayList<>();

        int getPercentage() {
            if (totalGroups == 0) return 0;
            return (completedGroups.size() * 100) / totalGroups;
        }
    }
    
    /**
     * Inner class to manage commit message streaming sessions.
     */
    private static class CommitMessageSession {
        private static final long TIMEOUT_MS = 600000; // 10 minutes timeout - matches frontend, allows for slow LLM and hierarchical generation

        private final String sessionId;
        private final String prompt;
        private final Project project;
        private final StringBuilder fullMessage = new StringBuilder();
        private final BlockingQueue<String> chunks = new LinkedBlockingQueue<>();
        private volatile boolean started = false;
        private volatile boolean completed = false;
        private final long createdAt = System.currentTimeMillis();
        private volatile long lastActivityAt = System.currentTimeMillis();
        private volatile GenerationProgress progress;

        CommitMessageSession(String sessionId, String prompt, Project project) {
            this.sessionId = sessionId;
            this.prompt = prompt;
            this.project = project;
            this.progress = null;
        }

        boolean isStarted() {
            return started;
        }

        boolean isTimedOut() {
            return (System.currentTimeMillis() - lastActivityAt) > TIMEOUT_MS;
        }

        GenerationProgress getProgress() {
            return progress;
        }

        void setProgress(GenerationProgress progress) {
            this.progress = progress;
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

    /**
     * Session for tracking preview generation progress.
     */
    private static class PreviewSession {
        final String sessionId;
        final int totalFiles;
        volatile int filesProcessed = 0;
        volatile String currentFile = "";
        volatile PreviewSessionStatus status = PreviewSessionStatus.PROCESSING;
        volatile List<FileGroup> groups = null;
        volatile String error = null;
        final long createdAt = System.currentTimeMillis();

        PreviewSession(String sessionId, int totalFiles) {
            this.sessionId = sessionId;
            this.totalFiles = totalFiles;
        }

        int getPercentage() {
            if (totalFiles == 0) return 0;
            return (filesProcessed * 100) / totalFiles;
        }
    }

    /**
     * Status enum for preview generation sessions.
     */
    private enum PreviewSessionStatus {
        PROCESSING,
        COMPLETE,
        ERROR
    }
}