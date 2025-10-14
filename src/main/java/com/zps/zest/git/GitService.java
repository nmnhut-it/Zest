package com.zps.zest.git;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.notification.NotificationType;
import com.zps.zest.GitCommitMessageGeneratorAction;
import com.zps.zest.browser.utils.GitCommandExecutor;
import com.zps.zest.codehealth.CodeHealthAnalyzer;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.HashMap;

public class GitService {

    private static final Logger LOG = Logger.getInstance(GitService.class);
    private final Project project;
    private final Gson gson = new Gson();
    private final GitCommitMessageService commitMessageService;

    private static final ConcurrentHashMap<String, GitCommitContext> GLOBAL_CONTEXTS = new ConcurrentHashMap<>();
    private volatile String cachedGitStatus = null;
    private volatile long gitStatusCacheTime = 0;
    private static final long GIT_STATUS_CACHE_TTL_MS = 3000;
    
    
    public GitService(@NotNull Project project) {
        this.project = project;
        this.commitMessageService = new GitCommitMessageService(project, this);
    }
    
    /**
     * Invalidate cache for specific files
     */
    public void invalidateCacheForFiles(List<String> filePaths) {
        String projectPath = project.getBasePath();
        if (projectPath == null) return;
        
        // Clear cache entries for these files
        for (String filePath : filePaths) {
            String prefix = projectPath + ":" + filePath + ":";
            GitServiceHelper.clearDiffCache(); // TODO: Add selective clearing
        }
        LOG.info("Invalidated cache for " + filePaths.size() + " files");
    }
    
    /**
     * Handles commit with message from JavaScript bridge.
     * This updated version supports multi-line commit messages by chaining -m flags.
     * Operations are performed asynchronously to avoid blocking the UI.
     */
    public String handleCommitWithMessage(JsonObject data) {
        LOG.info("Processing commit with message: " + data.toString());

        try {
            // Parse commit message
            String commitMessage = data.get("message").getAsString();
            
            // Parse selected files from JSON
            JsonArray selectedFilesArray = data.getAsJsonArray("selectedFiles");
            List<GitCommitContext.SelectedFile> selectedFiles = new ArrayList<>();

            LOG.info("Parsing " + selectedFilesArray.size() + " selected files");

            for (int i = 0; i < selectedFilesArray.size(); i++) {
                JsonObject fileObj = selectedFilesArray.get(i).getAsJsonObject();
                String path = fileObj.get("path").getAsString();
                String status = fileObj.get("status").getAsString();

                selectedFiles.add(new GitCommitContext.SelectedFile(path, status));
            }

            // Get project path
            String projectPath = GitServiceHelper.getProjectPath(project);

            // Run commit operation in background
            new Task.Backgroundable(project, "Git Commit", false) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    try {
                        // Show status message
                        GitUINotificationHelper.showCommitInProgress(project);
                        
                        // Check if there are any actual changes to commit
                        boolean hasChanges = false;
                        
                        // Stage each selected file
                        for (GitCommitContext.SelectedFile file : selectedFiles) {
                            String cleanPath = GitServiceHelper.cleanFilePath(file.getPath(), project.getName());
                            String command;
                            
                            // Special handling for deleted files
                            if ("D".equals(file.getStatus())) {
                                // Use 'git rm' with the --ignore-unmatch flag to prevent errors for already deleted files
                                command = "git rm --ignore-unmatch -- " + GitCommandExecutor.escapeFilePath(cleanPath);
                                LOG.info("Removing deleted file: " + cleanPath);
                            } else {
                                command = "git add -- " + GitCommandExecutor.escapeFilePath(cleanPath);
                                LOG.info("Staging file: " + cleanPath);
                            }
                            
                            // Pre-check if file is ignored to avoid git command failures
                            if (GitServiceHelper.isFileIgnored(projectPath, cleanPath)) {
                                LOG.info("Skipping ignored file (pre-filtered): " + cleanPath);
                                continue;
                            }

                            try {
                                String result = executeGitCommand(projectPath, command);
                                // If command succeeded and produced output, we have changes
                                if (result != null && !result.isEmpty()) {
                                    hasChanges = true;
                                }
                            } catch (Exception e) {
                                String errorMessage = e.getMessage();
                                LOG.warn("Error staging file " + cleanPath + ": " + errorMessage);

                                // Check if it's an ignored file error (fallback check)
                                if (errorMessage != null && errorMessage.contains("ignored by one of your .gitignore files")) {
                                    LOG.info("File is ignored by .gitignore (detected via error): " + cleanPath);
                                    // Skip this file - it shouldn't be committed
                                    continue;
                                }

                                // Try alternative approach for deleted files that are giving trouble
                                if ("D".equals(file.getStatus())) {
                                    LOG.info("Trying alternative approach for deleted file: " + cleanPath);
                                    try {
                                        // Force path-spec to ensure git treats this as a path
                                        executeGitCommand(projectPath, "git rm -f -- " + GitCommandExecutor.escapeFilePath(cleanPath));
                                        LOG.info("Alternative approach succeeded");
                                        hasChanges = true;
                                    } catch (Exception e2) {
                                        // Check if the alternative also failed due to ignore
                                        if (e2.getMessage() != null && e2.getMessage().contains("ignored by one of your .gitignore files")) {
                                            LOG.info("Deleted file is ignored by .gitignore (alternative check): " + cleanPath);
                                            continue;
                                        }
                                        LOG.warn("Alternative approach also failed: " + e2.getMessage());
                                        // Continue with other files
                                    }
                                }
                            }
                        }
                        
                        // Check if we have anything staged
                        try {
                            String stagedStatus = executeGitCommand(projectPath, "git diff --cached --name-only");
                            if (stagedStatus == null || stagedStatus.trim().isEmpty()) {
                                hasChanges = false;
                                LOG.warn("No files were successfully staged");
                            } else {
                                hasChanges = true;
                                LOG.info("Files staged successfully: " + stagedStatus.split("\n").length + " files");
                            }
                        } catch (Exception e) {
                            LOG.warn("Could not check staged files: " + e.getMessage());
                        }
                        
                        if (!hasChanges) {
                            throw new Exception("No changes to commit. Files may have already been committed or are ignored by .gitignore.");
                        }

                        // Build git commit command with multiple -m flags for multiline message
                        String[] lines = commitMessage.split("\\r?\\n");
                        StringBuilder commitCommand = new StringBuilder("git commit");
                        for (String line : lines) {
                            commitCommand.append(" -m \"").append(GitServiceHelper.escapeForShell(line)).append("\"");
                        }

                        LOG.info("Committing with message: " + commitMessage);

                        String result = executeGitCommand(projectPath, commitCommand.toString());
                        LOG.info("Commit executed successfully: " + result);
                        
                        // Invalidate cache for committed files
                        List<String> committedPaths = selectedFiles.stream()
                            .map(file -> GitServiceHelper.cleanFilePath(file.getPath(), project.getName()))
                            .collect(java.util.stream.Collectors.toList());
                        invalidateCacheForFiles(committedPaths);
                        invalidateGitStatusCache();

                        // Show success message and notify UI
                        GitUINotificationHelper.showCommitSuccess(project);
                        
                        // Don't auto-refresh here - let the JavaScript handle it
                    } catch (Exception e) {
                        LOG.error("Error during commit operation", e);
                        
                        // Show error message and notify UI
                        String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                        
                        // Check for specific common error patterns
                        if (errorMsg.contains("nothing to commit") || errorMsg.contains("no changes added") || errorMsg.contains("Your branch is up to date")) {
                            errorMsg = "No changes to commit. All files may have already been committed or are ignored by .gitignore.";
                        } else if (errorMsg.contains("ignored by one of your .gitignore files")) {
                            errorMsg = "Selected files are ignored by .gitignore and cannot be committed.";
                        } else if (errorMsg.contains("no remote repository") || errorMsg.contains("does not appear to be a git repository")) {
                            errorMsg = "No remote repository configured. Please set up a remote first.";
                        } else if (errorMsg.contains("failed to push") || errorMsg.contains("rejected")) {
                            errorMsg = "Push rejected. You may need to pull latest changes first.";
                        }
                        
                        GitUINotificationHelper.showCommitError(project, errorMsg);
                    }
                }
            }.queue();

            return GitResponseBuilder.commitOperationStarted();

        } catch (Exception e) {
            LOG.error("Error handling commit with message", e);
            return GitServiceHelper.toJson(
                GitServiceHelper.createErrorResponse("Failed to commit: " + e.getMessage())
            );
        }
    }

    /**
     * Opens a file diff in the GitHub-style diff viewer
     */
    public String openFileDiffInIDE(JsonObject data) {
        LOG.info("Opening file diff in GitHub-style viewer: " + data.toString());
        
        try {
            JsonElement filePathElement = data.get("filePath");
            JsonElement statusElement = data.get("status");
            
            if (filePathElement == null || statusElement == null) {
                return GitServiceHelper.toJson(
                    GitServiceHelper.createErrorResponse("Missing required parameters: filePath and status")
                );
            }
            
            String filePath = filePathElement.getAsString();
            String status = statusElement.getAsString();
            
            LOG.info("Opening diff for file: " + filePath + " (status: " + status + ")");
            
            String projectPath = GitServiceHelper.getProjectPath(project);
            
            // Clean the file path - remove project name prefix if present
            String cleanedPath = GitServiceHelper.cleanFilePath(filePath, project.getName());
            LOG.info("Cleaned file path for diff: '" + filePath + "' -> '" + cleanedPath + "'");
            
            // Show the diff in our new FileDiffDialog (asynchronously loads the diff content)
            com.zps.zest.completion.diff.GitDiffUtil.INSTANCE.showGitDiff(
                project, 
                cleanedPath, 
                status
            );
            
            return GitResponseBuilder.successResponse("Opening GitHub-style diff for " + filePath);
            
        } catch (Exception e) {
            LOG.error("Error opening file diff", e);
            return GitServiceHelper.toJson(
                GitServiceHelper.createErrorResponse("Failed to open diff: " + e.getMessage())
            );
        }
    }

 /**
 * Handles git push operation asynchronously
 */
public String handleGitPush() {
    String projectPath;
    try {
        projectPath = GitServiceHelper.getProjectPath(project);
    } catch (Exception e) {
        return GitServiceHelper.toJson(
            GitServiceHelper.createErrorResponse("Project path not found")
        );
    }

    // Run push operation in background
    new Task.Backgroundable(project, "Git Push", false) {
        @Override
        public void run(@NotNull ProgressIndicator indicator) {
            try {
                // Show status message and notify UI
                GitUINotificationHelper.showPushInProgress(project);
                
                // First check if we have a remote configured
                try {
                    String remotes = executeGitCommand(projectPath, "git remote -v");
                    if (remotes == null || remotes.trim().isEmpty()) {
                        throw new Exception("No remote repository configured. Please add a remote first (e.g., git remote add origin <url>)");
                    }
                } catch (Exception e) {
                    throw new Exception("Failed to check remote configuration: " + e.getMessage());
                }
                
                // Check if we have commits to push
                try {
                    String unpushedCommits = executeGitCommand(projectPath, "git log @{u}.. --oneline");
                    if (unpushedCommits == null || unpushedCommits.trim().isEmpty()) {
                        throw new Exception("No commits to push. Your branch is up to date with the remote.");
                    }
                } catch (Exception e) {
                    // If @{u} fails, it might mean no upstream branch is set
                    if (e.getMessage().contains("@{u}") || e.getMessage().contains("upstream")) {
                        // Try to set upstream branch
                        try {
                            String currentBranch = executeGitCommand(projectPath, "git branch --show-current").trim();
                            LOG.info("Setting upstream branch for: " + currentBranch);
                            executeGitCommand(projectPath, "git push -u origin " + currentBranch);
                            LOG.info("Push with upstream set executed successfully");
                            GitUINotificationHelper.showPushSuccess(project);
                            GitUINotificationHelper.closeGitModal(project);
                            return;
                        } catch (Exception e2) {
                            throw new Exception("Failed to push: " + e2.getMessage());
                        }
                    }
                    // Otherwise, assume we have commits to push
                }

                String result = executeGitCommand(projectPath, "git push");
                LOG.info("Push executed successfully: " + result);

                // Show success message and notify UI
                GitUINotificationHelper.showPushSuccess(project);
                GitUINotificationHelper.closeGitModal(project);
            } catch (Exception e) {
                LOG.error("Error during push operation", e);

                // Show error message and notify UI
                String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                
                // Provide more helpful error messages
                if (errorMsg.contains("rejected") || errorMsg.contains("non-fast-forward")) {
                    errorMsg = "Push rejected. You need to pull remote changes first (git pull).";
                } else if (errorMsg.contains("Permission denied") || errorMsg.contains("authentication")) {
                    errorMsg = "Authentication failed. Please check your credentials or SSH keys.";
                } else if (errorMsg.contains("Could not read from remote")) {
                    errorMsg = "Cannot connect to remote repository. Check your network and repository URL.";
                }
                
                GitUINotificationHelper.showPushError(project, errorMsg);
            }
        }
    }.queue();

    return GitResponseBuilder.pushOperationStarted();
}
    
    
    /**
     * Handles files selected for commit from JavaScript bridge.
     */
    public String handleFilesSelected(JsonObject data, boolean shouldPush) {
        LOG.info("Processing files selected for commit: " + data.toString());
        
        try {
            // Parse selected files from JSON
            JsonArray selectedFilesArray = data.getAsJsonArray("selectedFiles");
            List<GitCommitContext.SelectedFile> selectedFiles = new ArrayList<>();
            
            LOG.info("Parsing " + selectedFilesArray.size() + " selected files from JavaScript:");
            
            for (int i = 0; i < selectedFilesArray.size(); i++) {
                JsonObject fileObj = selectedFilesArray.get(i).getAsJsonObject();
                String path = fileObj.get("path").getAsString();
                String status = fileObj.get("status").getAsString();
                
                LOG.info("  File " + i + ": path='" + path + "', status='" + status + "'");
                selectedFiles.add(new GitCommitContext.SelectedFile(path, status));
            }
            
            LOG.info("Parsed " + selectedFiles.size() + " selected files");
            
            // Find active context for this project using static method
            GitCommitContext context = getActiveContextStatic(project.getName());
            if (context == null) {
                LOG.error("No active git commit context found for project: " + project.getName());
                LOG.info("Available contexts: " + GLOBAL_CONTEXTS.keySet());
                return GitServiceHelper.toJson(
                    GitServiceHelper.createErrorResponse("No active commit context found")
                );
            }
            
            // Log what paths git originally gave us
            LOG.info("Original git diff --name-status from context: " + context.getChangedFiles());
            
            // Update context with selected files
            context.setSelectedFiles(selectedFiles);
            
            // Continue the git commit pipeline directly
//            GitCommitMessageGeneratorAction.continueWithSelectedFiles(context, shouldPush);
            
            // Clean up context
            removeActiveContextStatic(project.getName());
            
            return GitResponseBuilder.filesSelectedSuccess();
            
        } catch (Exception e) {
            LOG.error("Error handling files selected for commit", e);
            return GitServiceHelper.toJson(
                GitServiceHelper.createErrorResponse("Failed to process selected files: " + e.getMessage())
            );
        }
    }
    
    /**
     * Registers a git commit context for the project using static storage.
     */
    public static void registerContextStatic(@NotNull GitCommitContext context) {
        String projectKey = context.getProject().getName();
        GLOBAL_CONTEXTS.put(projectKey, context);
        LOG.info("Registered git commit context for project: " + projectKey);
        LOG.info("Total active contexts: " + GLOBAL_CONTEXTS.size());
    }
    
    /**
     * Gets the active context for a project using static storage.
     */
    public static GitCommitContext getActiveContextStatic(String projectName) {
        return GLOBAL_CONTEXTS.get(projectName);
    }
    
    /**
     * Removes the active context for a project using static storage.
     */
    public static void removeActiveContextStatic(String projectName) {
        GitCommitContext removed = GLOBAL_CONTEXTS.remove(projectName);
        if (removed != null) {
            LOG.info("Removed git commit context for project: " + projectName);
        }
        LOG.info("Remaining active contexts: " + GLOBAL_CONTEXTS.size());
    }
    
    /**
     * Registers a git commit context for the project (instance method for backward compatibility).
     */
    public void registerContext(@NotNull GitCommitContext context) {
        registerContextStatic(context);
    }
    
    /**
     * Gets the active context for a project (instance method for backward compatibility).
     */
    public GitCommitContext getActiveContext(String projectName) {
        return getActiveContextStatic(projectName);
    }
    
    /**
     * Removes the active context for a project (instance method for backward compatibility).
     */
    public void removeActiveContext(String projectName) {
        removeActiveContextStatic(projectName);
    }
    
    /**
     * Gets all active contexts count (for debugging/monitoring).
     */
    public static int getActiveContextCount() {
        return GLOBAL_CONTEXTS.size();
    }
    
    /**
     * Gets the diff content for a specific file (internal use).
     */
    String getFileDiffContent(String filePath, String status) {
        String projectPath = project.getBasePath();
        if (projectPath == null) return "";
        
        String cleanedPath = GitServiceHelper.cleanFilePath(filePath, project.getName());
        LOG.debug("Getting diff for: " + cleanedPath + " (status: " + status + ")");
        
        try {
            switch (status) {
                case "A": // Added/New file
                    if (GitServiceHelper.isNewFile(projectPath, cleanedPath)) {
                        return GitDiffHelper.getNewFileDiff(projectPath, cleanedPath);
                    } else {
                        return executeGitCommand(projectPath, "git diff --cached -- " + GitCommandExecutor.escapeFilePath(cleanedPath));
                    }
                    
                case "M": // Modified
                    String diff = executeGitCommand(projectPath, "git diff -- " + GitCommandExecutor.escapeFilePath(cleanedPath));
                    if (diff.trim().isEmpty()) {
                        diff = executeGitCommand(projectPath, "git diff --cached -- " + GitCommandExecutor.escapeFilePath(cleanedPath));
                    }
                    return diff;
                    
                case "D": // Deleted
                    return getDeletedFileDiffWithFallback(projectPath, cleanedPath);
                    
                case "R": // Renamed
                    return executeGitCommand(projectPath, "git diff --cached -- " + GitCommandExecutor.escapeFilePath(cleanedPath));
                    
                default:
                    return executeGitCommand(projectPath, "git diff -- " + GitCommandExecutor.escapeFilePath(cleanedPath));
            }
        } catch (Exception e) {
            LOG.error("Error getting diff for file: " + filePath, e);
            return "Error getting diff: " + e.getMessage();
        }
    }
    
    /**
     * Gets the diff for a specific file.
     */
    public String getFileDiff(JsonObject data) {
        LOG.info("Getting file diff: " + data.toString());
        
        try {
            String filePath = data.get("filePath").getAsString();
            String status = data.get("status").getAsString();
            
            LOG.info("Requesting diff for file: " + filePath + " (status: " + status + ")");
            
            // Use the internal method that has all the logic
            String diff = getFileDiffContent(filePath, status);
            
            LOG.info("Generated diff for " + filePath + " (length: " + diff.length() + ")");
            
            return GitResponseBuilder.diffResponse(diff);
            
        } catch (Exception e) {
            LOG.error("Error getting file diff", e);
            return GitServiceHelper.toJson(
                GitServiceHelper.createErrorResponse("Failed to get file diff: " + e.getMessage())
            );
        }
    }
    
    
    /**
     * Executes a git command using the shared utility
     */
    private String executeGitCommand(String workingDir, String command) throws Exception {
        return GitServiceHelper.executeGitCommand(workingDir, command);
    }


    /**
     * Gets the current git status for quick commit
     */
    public String getGitStatus() {
        LOG.info("Getting git status for quick commit");

        long now = System.currentTimeMillis();
        if (cachedGitStatus != null && (now - gitStatusCacheTime) < GIT_STATUS_CACHE_TTL_MS) {
            LOG.info("Returning cached git status (age: " + (now - gitStatusCacheTime) + "ms)");
            return cachedGitStatus;
        }

        try {
            String projectPath = GitServiceHelper.getProjectPath(project);

            GitStatusCollector collector = new GitStatusCollector(projectPath);
            GitStatusCollector.StatusSummary summary = collector.getStatusSummary();

            LOG.info("Git status collected: " +
                (summary.hasChanges() ? summary.fileCount + " files" : "No changes"));

            JsonObject response = GitServiceHelper.createSuccessResponse();
            response.addProperty("changedFiles", summary.changedFiles);
            String result = GitServiceHelper.toJson(response);

            cachedGitStatus = result;
            gitStatusCacheTime = now;
            LOG.info("Cached git status (TTL: " + GIT_STATUS_CACHE_TTL_MS + "ms)");

            return result;

        } catch (Exception e) {
            LOG.error("Error getting git status", e);
            return GitServiceHelper.toJson(
                GitServiceHelper.createErrorResponse("Failed to get git status: " + e.getMessage())
            );
        }
    }

    public void invalidateGitStatusCache() {
        cachedGitStatus = null;
        gitStatusCacheTime = 0;
        LOG.info("Git status cache invalidated");
    }
    
    /**
     * Gets diffs for multiple files in a single batch operation.
     * This dramatically improves performance by reducing git process spawns.
     */
    public String getBatchFileDiffs(JsonObject data) {
        LOG.info("Getting batch file diffs");
        long startTime = System.currentTimeMillis();
        
        try {
            JsonArray filesArray = data.getAsJsonArray("files");
            String projectPath = project.getBasePath();
            
            if (projectPath == null) {
                return GitServiceHelper.toJson(
            GitServiceHelper.createErrorResponse("Project path not found")
        );
            }
            
            // Prepare response
            JsonObject response = new JsonObject();
            JsonArray diffsArray = new JsonArray();
            
            // Performance tracking
            int totalFiles = filesArray.size();
            int cachedFiles = 0;
            int gitCalls = 0;
            long cacheTime = 0;
            long gitTime = 0;
            
            // Build list of files to process
            Map<String, String> filesToProcess = new HashMap<>();
            
            for (int i = 0; i < filesArray.size(); i++) {
                JsonObject fileObj = filesArray.get(i).getAsJsonObject();
                String filePath = fileObj.get("filePath").getAsString();
                String status = fileObj.get("status").getAsString();
                String cleanedPath = GitServiceHelper.cleanFilePath(filePath, project.getName());
                
                // Check cache first
                long cacheStart = System.currentTimeMillis();
                String cacheKey = projectPath + ":" + cleanedPath + ":" + status;
                GitServiceHelper.CachedDiff cached = GitServiceHelper.getCachedDiff(cacheKey);
                cacheTime += (System.currentTimeMillis() - cacheStart);
                
                if (cached != null) {
                    cachedFiles++;
                    LOG.debug("Using cached diff for: " + cleanedPath);
                    JsonObject diffResult = new JsonObject();
                    diffResult.addProperty("filePath", filePath);
                    diffResult.addProperty("diff", cached.diff);
                    diffResult.addProperty("cached", true);
                    diffsArray.add(diffResult);
                } else {
                    filesToProcess.put(cleanedPath, status);
                }
            }
            
            // Process uncached files in batch
            if (!filesToProcess.isEmpty()) {
                long gitStart = System.currentTimeMillis();
                Map<String, String> batchDiffs = getBatchDiffsFromGit(projectPath, filesToProcess);
                gitTime = System.currentTimeMillis() - gitStart;
                gitCalls = 1; // Single batch call
                
                for (Map.Entry<String, String> entry : batchDiffs.entrySet()) {
                    String filePath = entry.getKey();
                    String diff = entry.getValue();
                    String status = filesToProcess.get(filePath);
                    
                    // Cache the diff
                    String cacheKey = projectPath + ":" + filePath + ":" + status;
                    GitServiceHelper.cacheDiff(cacheKey, diff);
                    
                    // Add to response
                    JsonObject diffResult = new JsonObject();
                    diffResult.addProperty("filePath", filePath);
                    diffResult.addProperty("diff", diff);
                    diffResult.addProperty("cached", false);
                    diffsArray.add(diffResult);
                }
            }
            
            // Log performance metrics
            long totalTime = System.currentTimeMillis() - startTime;
            LOG.info(String.format(
                "Batch diff performance: %d files, %d cached (%.1f%%), %d git calls, " +
                "cache time: %dms, git time: %dms, total time: %dms, " +
                "avg per file: %.1fms, speedup: %.1fx",
                totalFiles, cachedFiles, (cachedFiles * 100.0 / totalFiles), gitCalls,
                cacheTime, gitTime, totalTime,
                (double) totalTime / totalFiles,
                totalFiles > 0 ? (double) (totalFiles * 50) / totalTime : 0 // Assuming 50ms per file with old method
            ));
            
            response.addProperty("success", true);
            response.add("diffs", diffsArray);
            response.addProperty("performance", String.format(
                "Retrieved %d diffs in %dms (%d cached, %.1fms avg)",
                totalFiles, totalTime, cachedFiles, (double) totalTime / totalFiles
            ));
            return gson.toJson(response);
            
        } catch (Exception e) {
            LOG.error("Error getting batch file diffs", e);
            return GitServiceHelper.toJson(
                GitServiceHelper.createErrorResponse("Failed to get batch diffs: " + e.getMessage())
            );
        }
    }
    
    /**
     * Gets diffs for multiple files using a single git command.
     * This is much more efficient than calling git multiple times.
     */
    private Map<String, String> getBatchDiffsFromGit(String projectPath, Map<String, String> files) throws Exception {
        Map<String, String> diffs = new HashMap<>();
        
        // Group files by status for efficient processing
        Map<String, List<String>> filesByStatus = new HashMap<>();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            filesByStatus.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }
        
        // Process each status group
        for (Map.Entry<String, List<String>> statusGroup : filesByStatus.entrySet()) {
            String status = statusGroup.getKey();
            List<String> filePaths = statusGroup.getValue();
            
            switch (status) {
                case "M": // Modified files - get all diffs in one command
                    String modifiedDiffs = GitDiffHelper.getModifiedFilesDiffs(projectPath, filePaths);
                    GitDiffHelper.parseBatchDiffOutput(modifiedDiffs, diffs);
                    break;
                    
                case "A": // Added files
                case "D": // Deleted files  
                case "R": // Renamed files
                default:
                    // Use the existing getFileDiffContent method for all non-modified files
                    for (String filePath : filePaths) {
                        String diff = getFileDiffContent(filePath, status);
                        diffs.put(filePath, diff);
                    }
            }
        }
        
        return diffs;
    }


    /**
     * Gets the diff for a deleted file with comprehensive fallback logic.
     */
    private String getDeletedFileDiffWithFallback(String projectPath, String filePath) {
        try {
            LOG.debug("Processing deleted file: " + filePath);
            
            // First, check if the file is already staged for deletion
            try {
                String diff = executeGitCommand(projectPath, "git diff --cached -- " + GitCommandExecutor.escapeFilePath(filePath));
                if (!diff.trim().isEmpty()) {
                    LOG.debug("Found staged deletion diff");
                    return diff;
                }
            } catch (Exception e) {
                LOG.debug("Failed to get staged diff: " + e.getMessage());
            }
            
            // Next, try to get the file content from HEAD
            try {
                String content = executeGitCommand(projectPath, "git show HEAD:" + GitCommandExecutor.escapeFilePath(filePath));
                if (!content.trim().isEmpty()) {
                    LOG.debug("Got content from HEAD, formatting as deletion diff");
                    return GitDiffHelper.getDeletedFileDiff(content, filePath);
                }
            } catch (Exception e) {
                LOG.debug("Failed to get content from HEAD: " + e.getMessage());
            }
            
            // If we can't get the content directly, try to find when it was last seen
            try {
                String history = executeGitCommand(projectPath, "git log --pretty=format:\"%H\" -n 1 -- " + GitCommandExecutor.escapeFilePath(filePath));
                if (!history.trim().isEmpty()) {
                    String commitHash = history.trim();
                    LOG.debug("Found file in commit: " + commitHash);
                    
                    String content = executeGitCommand(projectPath, "git show " + commitHash + ":" + GitCommandExecutor.escapeFilePath(filePath));
                    if (!content.trim().isEmpty()) {
                        LOG.debug("Got content from commit " + commitHash);
                        return GitDiffHelper.getDeletedFileDiff(content, filePath);
                    }
                }
            } catch (Exception e) {
                LOG.debug("Failed to get file history: " + e.getMessage());
            }
            
        } catch (Exception e) {
            LOG.warn("Error processing deleted file: " + e.getMessage(), e);
        }
        
        // If all else fails, provide a simple message
        return "File was deleted: " + filePath + "\n(Content not available)";
    }

    /**
     * Clear diff cache for a specific project.
     */
    public void clearDiffCache() {
        GitServiceHelper.clearDiffCache();
    }
    
    /**
     * Gets the content of a file for code review purposes.
     */
    public String getFileContent(JsonObject data) {
        try {
            String filePath = data.get("filePath").getAsString();
            String projectPath = project.getBasePath();
            
            if (projectPath == null) {
                return GitResponseBuilder.errorResponse("Project path not found");
            }
            
            Path fullPath = Paths.get(projectPath, filePath);
            
            if (Files.exists(fullPath)) {
                String content = Files.readString(fullPath);
                return GitResponseBuilder.fileContentResponse(content, filePath);
            } else {
                return GitResponseBuilder.errorResponse("File not found: " + filePath);
            }
            
        } catch (Exception e) {
            LOG.error("Error getting file content", e);
            return GitResponseBuilder.errorResponse("Error reading file: " + e.getMessage());
        }
    }

    /**
     * Triggers Code Guardian analysis for selected files.
     */
    public String triggerCodeGuardianAnalysis(JsonObject data) {
        try {
            JsonArray filesArray = data.getAsJsonArray("files");
            boolean isImmediate = data.has("immediate") && data.get("immediate").getAsBoolean();

            LOG.info("Triggering Code Health Review for " + filesArray.size() + " files" + 
                     (isImmediate ? " (immediate review)" : " (post-commit review)"));

            // For immediate review, use a dedicated reviewer
            if (isImmediate) {
                return triggerImmediateCodeReview(filesArray);
            }

            // Otherwise, use the regular tracking mechanism
            var tracker = com.zps.zest.codehealth.ProjectChangesTracker.Companion.getInstance(project);

            // Create modified method entries for the files
            List<com.zps.zest.codehealth.ProjectChangesTracker.ModifiedMethod> methods = new ArrayList<>();

            for (JsonElement fileElement : filesArray) {
                JsonObject fileObj = fileElement.getAsJsonObject();
                String filePath = fileObj.get("path").getAsString();

                // For JS/TS files, create a region entry
                String fqn;
                if (filePath.endsWith(".js") || filePath.endsWith(".ts") ||
                    filePath.endsWith(".jsx") || filePath.endsWith(".tsx")) {
                    // Add as JS/TS region at line 1
                    fqn = filePath + ":1";  // FQN format for JS/TS
                } else {
                    // For other files, add the whole file
                    fqn = filePath;
                }
                
                methods.add(new com.zps.zest.codehealth.ProjectChangesTracker.ModifiedMethod(
                    fqn,
                    1,  // modification count
                    System.currentTimeMillis(),  // lastModified
                    new java.util.HashSet<>()  // callers
                ));
            }

            // Track method modifications
            for (var method : methods) {
                tracker.trackMethodModification(method.getFqn());
            }

            // Trigger immediate analysis
            ApplicationManager.getApplication().invokeLater(() -> {
                tracker.checkAndNotify();
            });

            return GitResponseBuilder.successResponse(
                "Code Health Review started for " + filesArray.size() + " files from latest git commit"
            );

        } catch (Exception e) {
            LOG.error("Error triggering Code Guardian analysis", e);
            return GitResponseBuilder.errorResponse("Error: " + e.getMessage());
        }
    }
    
    /**
     * Triggers immediate code review for specific files without affecting the regular tracking.
     */
    private String triggerImmediateCodeReview(JsonArray filesArray) {
        LOG.info("Starting immediate code review for " + filesArray.size() + " files");
        
        // Run the review in background
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                // Show starting notification
                ApplicationManager.getApplication().invokeLater(() -> {
                    GitUINotificationHelper.showNotification(
                        project,
                        "🔍 Code Health Review Started",
                        "Analyzing " + filesArray.size() + " selected file(s)...",
                        NotificationType.INFORMATION
                    );
                });
                
                // Create a dedicated analyzer for these specific files
                List<String> filePaths = new ArrayList<>();
                String projectPath = project.getBasePath();
                for (JsonElement fileElement : filesArray) {
                    JsonObject fileObj = fileElement.getAsJsonObject();
                    String relativePath = fileObj.get("path").getAsString();
                    // Convert to absolute path
                    String absolutePath = Paths.get(projectPath, relativePath).toString();
                    filePaths.add(absolutePath);
                }
                
                // Perform the analysis
                var analyzer = CodeHealthAnalyzer.Companion.getInstance(project);
                var results = analyzer.analyzeFiles(filePaths);
                
                // Show results notification using the rich Code Guardian notification
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (results.isEmpty()) {
                        // Simple notification for no issues
                        GitUINotificationHelper.showNotification(
                            project,
                            "✅ Code Health Review Complete",
                            "Great job! No issues found in the latest git commit files.",
                            NotificationType.INFORMATION
                        );
                    } else {
                        // Use the rich CodeHealthNotification for showing results
                        // Pass true for isGitTriggered since this is from Git UI
                        com.zps.zest.codehealth.CodeHealthNotification.INSTANCE.showHealthReport(project, results, true);
                    }
                });
                
            } catch (Exception e) {
                LOG.error("Error during immediate code review", e);
                ApplicationManager.getApplication().invokeLater(() -> {
                    GitUINotificationHelper.showNotification(
                        project,
                        "❌ Code Health Review Failed",
                        "Error: " + e.getMessage(),
                        NotificationType.ERROR
                    );
                });
            }
        });
        
        return GitResponseBuilder.successResponse(
            "Code Health Review started for " + filesArray.size() + " files"
        );
    }

    /**
     * Generates a commit message for the selected files using streaming.
     */
    public String generateCommitMessage(JsonObject data) {
        return commitMessageService.generateCommitMessage(data);
    }

    /**
     * Streams the commit message generation response.
     */
    public String streamCommitMessage(JsonObject data) {
        return commitMessageService.streamCommitMessage(data);
    }

    /**
     * Estimates the size of changes for selected files.
     */
    public String estimateChangesSize(JsonObject data) {
        return commitMessageService.estimateChangesSize(data);
    }

    /**
     * Disposes of any resources and clears active contexts.
     */
    public void dispose() {
        LOG.info("Disposing GitService for project: " + project.getName());
        clearDiffCache();
        commitMessageService.dispose();
        // Don't clear all contexts, just log
        LOG.info("Active contexts remaining: " + GLOBAL_CONTEXTS.size());
    }
}
