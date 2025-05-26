package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.zps.zest.GitCommitMessageGeneratorAction;
import com.zps.zest.CodeContext;
import com.zps.zest.browser.utils.GitCommandExecutor;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for handling git commit operations from JavaScript bridge.
 * This manages git commit contexts and file selection workflows.
 */
public class GitService {
    private static final Logger LOG = Logger.getInstance(GitService.class);
    
    private final Project project;
    private final Gson gson = new Gson();
    
    // Static map to store contexts across all instances
    private static final ConcurrentHashMap<String, GitCommitContext> GLOBAL_CONTEXTS = new ConcurrentHashMap<>();
    
    public GitService(@NotNull Project project) {
        this.project = project;
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
            String projectPath = project.getBasePath();
            if (projectPath == null) {
                return createErrorResponse("Project path not found");
            }

            // Run commit operation in background
            new Task.Backgroundable(project, "Git Commit", false) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    try {
                        // Show status message
                        showToolWindowMessage(project, "Committing changes...");
                        
                        // Stage each selected file
                        for (GitCommitContext.SelectedFile file : selectedFiles) {
                            String cleanPath = cleanFilePath(file.getPath(), project.getName());
                            String command = "git add \"" + cleanPath + "\"";

                            LOG.info("Staging file: " + cleanPath);
                            executeGitCommand(projectPath, command);
                        }

                        // Build git commit command with multiple -m flags for multiline message
                        String[] lines = commitMessage.split("\\r?\\n");
                        StringBuilder commitCommand = new StringBuilder("git commit");
                        for (String line : lines) {
                            commitCommand.append(" -m \"").append(escapeForShell(line)).append("\"");
                        }

                        LOG.info("Committing with message: " + commitMessage);

                        String result = executeGitCommand(projectPath, commitCommand.toString());
                        LOG.info("Commit executed successfully: " + result);
                        
                        // Show success message
                        showToolWindowMessage(project, "Commit completed successfully!");
                    } catch (Exception e) {
                        LOG.error("Error during commit operation", e);
                        showToolWindowMessage(project, "Commit failed: " + e.getMessage());
                    }
                }
            }.queue();

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("message", "Commit operation started");
            return gson.toJson(response);

        } catch (Exception e) {
            LOG.error("Error handling commit with message", e);
            return createErrorResponse("Failed to commit: " + e.getMessage());
        }
    }

    /**
     * Handles git push operation asynchronously
     */
    public String handleGitPush() {
        String projectPath = project.getBasePath();
        if (projectPath == null) {
            return createErrorResponse("Project path not found");
        }
        
        // Run push operation in background
        new Task.Backgroundable(project, "Git Push", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    showToolWindowMessage(project, "Pushing changes to remote...");
                    String result = executeGitCommand(projectPath, "git push");
                    LOG.info("Push executed successfully: " + result);
                    showToolWindowMessage(project, "Push completed successfully!");
                } catch (Exception e) {
                    LOG.error("Error during push operation", e);
                    showToolWindowMessage(project, "Push failed: " + e.getMessage());
                }
            }
        }.queue();
        
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", "Push operation started");
        return gson.toJson(response);
    }
    
    /**
     * Shows a simple status message in the tool window
     */
    private void showToolWindowMessage(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                // Send message to the browser tool window via JavaScript
                String script = String.format("if (window.showStatusMessage) { window.showStatusMessage('%s'); }",
                        message.replace("'", "\\'"));
                WebBrowserService.getInstance(project).executeJavaScript(script);
                LOG.info("Sent status message to tool window: " + message);
            } catch (Exception e) {
                LOG.warn("Failed to show tool window message: " + message, e);
            }
        });
    }
    
    /**
     * Escapes strings for shell commands
     */
    private String escapeForShell(String input) {
        if (input == null) return "";
        // Escape double quotes and backslashes for shell command
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
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
                return createErrorResponse("No active commit context found");
            }
            
            // Log what paths git originally gave us
            LOG.info("Original git diff --name-status from context: " + context.getChangedFiles());
            
            // Update context with selected files
            context.setSelectedFiles(selectedFiles);
            
            // Continue the git commit pipeline directly
            GitCommitMessageGeneratorAction.continueWithSelectedFiles(context, shouldPush);
            
            // Clean up context
            removeActiveContextStatic(project.getName());
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("message", "Files selected and commit pipeline continued");
            return gson.toJson(response);
            
        } catch (Exception e) {
            LOG.error("Error handling files selected for commit", e);
            return createErrorResponse("Failed to process selected files: " + e.getMessage());
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
     * Gets the diff for a specific file.
     */
    public String getFileDiff(JsonObject data) {
        LOG.info("Getting file diff: " + data.toString());
        
        try {
            String filePath = data.get("filePath").getAsString();
            String status = data.get("status").getAsString();
            
            LOG.info("Requesting diff for file: " + filePath + " (status: " + status + ")");
            
            String projectPath = project.getBasePath();
            if (projectPath == null) {
                return createErrorResponse("Project path not found");
            }

            // Clean the file path - remove project name prefix if present  
            String cleanedPath = cleanFilePath(filePath, project.getName());
            LOG.info("Cleaned file path: '" + filePath + "' -> '" + cleanedPath + "'");
            
            String diff = "";
            
            // Handle different file statuses
            switch (status) {
                case "A": // Added/New file
                    if (isNewFile(projectPath, cleanedPath)) {
                        // For truly new files, show the entire content as added
                        diff = getNewFileContent(projectPath, cleanedPath);
                    } else {
                        // For staged files, get cached diff
                        diff = executeGitCommand(projectPath, "git diff --cached \"" + cleanedPath + "\"");
                    }
                    break;
                    
                case "M": // Modified
                    // Get unstaged changes first, then staged if no unstaged
                    diff = executeGitCommand(projectPath, "git diff \"" + cleanedPath + "\"");
                    if (diff.trim().isEmpty()) {
                        diff = executeGitCommand(projectPath, "git diff --cached \"" + cleanedPath + "\"");
                    }
                    break;
                    
                case "D": // Deleted
                    diff = executeGitCommand(projectPath, "git diff \"" + cleanedPath + "\"");
                    if (diff.trim().isEmpty()) {
                        diff = executeGitCommand(projectPath, "git diff --cached \"" + cleanedPath + "\"");
                    }
                    break;
                    
                case "R": // Renamed
                    diff = executeGitCommand(projectPath, "git diff --cached \"" + cleanedPath + "\"");
                    break;
                    
                default:
                    diff = executeGitCommand(projectPath, "git diff \"" + cleanedPath + "\"");
                    break;
            }
            
            LOG.info("Generated diff for " + cleanedPath + " (length: " + diff.length() + ")");
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("diff", diff);
            return gson.toJson(response);
            
        } catch (Exception e) {
            LOG.error("Error getting file diff", e);
            return createErrorResponse("Failed to get file diff: " + e.getMessage());
        }
    }
    
    /**
     * Cleans file path by removing project name prefix if present
     */
    private String cleanFilePath(String filePath, String projectName) {
        if (filePath == null || filePath.isEmpty()) return "";

        LOG.info("Cleaning file path: '" + filePath + "' for project: '" + projectName + "'");

        // Remove project name prefix if present
        if (projectName != null && !projectName.isEmpty()) {
            String prefix = projectName + "/";
            if (filePath.startsWith(prefix)) {
                String cleaned = filePath.substring(prefix.length());
                LOG.info("Removed project prefix: '" + filePath + "' -> '" + cleaned + "'");
                return cleaned;
            }
        }

        // Also try to handle cases where the path might have been duplicated
        // e.g., "5-lite/5-lite/src/..." -> "src/..."
        String[] parts = filePath.split("/");
        if (parts.length > 1 && parts[0].equals(parts[1])) {
            String cleaned = String.join("/", java.util.Arrays.copyOfRange(parts, 1, parts.length));
            LOG.info("Removed duplicate prefix: '" + filePath + "' -> '" + cleaned + "'");
            return cleaned;
        }

        // If path starts with project name, remove it
        if (projectName != null && parts.length > 0 && parts[0].equals(projectName)) {
            String cleaned = String.join("/", java.util.Arrays.copyOfRange(parts, 1, parts.length));
            LOG.info("Removed project name from path: '" + filePath + "' -> '" + cleaned + "'");
            return cleaned;
        }

        LOG.info("Path unchanged: '" + filePath + "'");
        return filePath;
    }
    
    /**
     * Checks if a file is truly new (untracked)
     */
    private boolean isNewFile(String projectPath, String filePath) {
        try {
            String result = executeGitCommand(projectPath, "git ls-files \"" + filePath + "\"");
            return result.trim().isEmpty(); // If empty, file is not tracked
        } catch (Exception e) {
            LOG.warn("Error checking if file is new: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Gets the content of a new file formatted as a diff
     */
    private String getNewFileContent(String projectPath, String filePath) {
        try {
            java.io.File file = new java.io.File(projectPath, filePath);
            if (!file.exists()) {
                return "File not found: " + filePath;
            }
            
            java.nio.file.Path path = file.toPath();
            String content = java.nio.file.Files.readString(path);
            
            // Format as a diff showing all lines as added
            StringBuilder diff = new StringBuilder();
            diff.append("diff --git a/").append(filePath).append(" b/").append(filePath).append("\n");
            diff.append("new file mode 100644\n");
            diff.append("index 0000000..1234567\n");
            diff.append("--- /dev/null\n");
            diff.append("+++ b/").append(filePath).append("\n");
            
            String[] lines = content.split("\n");
            diff.append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
            
            for (String line : lines) {
                diff.append("+").append(line).append("\n");
            }
            
            return diff.toString();
            
        } catch (Exception e) {
            LOG.error("Error reading new file content", e);
            return "Error reading file: " + e.getMessage();
        }
    }
    
    /**
     * Executes a git command using the shared utility
     */
    private String executeGitCommand(String workingDir, String command) throws Exception {
        return GitCommandExecutor.executeWithGenericException(workingDir, command);
    }
    
    /**
     * Creates an error response in JSON format.
     */
    private String createErrorResponse(String errorMessage) {
        JsonObject response = new JsonObject();
        response.addProperty("success", false);
        response.addProperty("error", errorMessage);
        return gson.toJson(response);
    }
    
    /**
     * Disposes of any resources and clears active contexts.
     */
    public void dispose() {
        LOG.info("Disposing GitService for project: " + project.getName());
        // Don't clear all contexts, just log
        LOG.info("Active contexts remaining: " + GLOBAL_CONTEXTS.size());
    }
}
