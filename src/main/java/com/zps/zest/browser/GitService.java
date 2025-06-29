package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.ide.DataManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.zps.zest.GitCommitMessageGeneratorAction;
import com.zps.zest.CodeContext;
import com.zps.zest.browser.utils.GitCommandExecutor;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class GitService {

    private static final Logger LOG = Logger.getInstance(GitService.class);
    private final Project project;
    private final Gson gson = new Gson();

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
                        showStatusMessage(project, "Committing changes...");
                        // Notify UI about commit in progress
                        notifyUI(project, "GitUI.showCommitInProgress()");
                        
                        // Check if there are any actual changes to commit
                        boolean hasChanges = false;
                        
                        // Stage each selected file
                        for (GitCommitContext.SelectedFile file : selectedFiles) {
                            String cleanPath = cleanFilePath(file.getPath(), project.getName());
                            String command;
                            
                            // Special handling for deleted files
                            if ("D".equals(file.getStatus())) {
                                // Use 'git rm' with the --ignore-unmatch flag to prevent errors for already deleted files
                                command = "git rm --ignore-unmatch -- \"" + cleanPath + "\"";
                                LOG.info("Removing deleted file: " + cleanPath);
                            } else {
                                command = "git add -- \"" + cleanPath + "\"";
                                LOG.info("Staging file: " + cleanPath);
                            }
                            
                            try {
                                String result = executeGitCommand(projectPath, command);
                                // If command succeeded and produced output, we have changes
                                if (result != null && !result.isEmpty()) {
                                    hasChanges = true;
                                }
                            } catch (Exception e) {
                                LOG.warn("Error staging file " + cleanPath + ": " + e.getMessage());
                                // Try alternative approach for deleted files that are giving trouble
                                if ("D".equals(file.getStatus())) {
                                    LOG.info("Trying alternative approach for deleted file: " + cleanPath);
                                    try {
                                        // Force path-spec to ensure git treats this as a path
                                        executeGitCommand(projectPath, "git rm -f -- \"" + cleanPath + "\"");
                                        LOG.info("Alternative approach succeeded");
                                        hasChanges = true;
                                    } catch (Exception e2) {
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
                            throw new Exception("No changes to commit. Files may have already been committed.");
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
                        
                        // Show success message and notify UI
                        showStatusMessage(project, "Commit completed successfully!");
                        notifyUI(project, "GitUI.showCommitSuccess()");
                        
                        // Don't auto-refresh here - let the JavaScript handle it
                    } catch (Exception e) {
                        LOG.error("Error during commit operation", e);
                        
                        // Show error message and notify UI
                        String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                        
                        // Check for specific common error patterns
                        if (errorMsg.contains("nothing to commit") || errorMsg.contains("no changes added") || errorMsg.contains("Your branch is up to date")) {
                            errorMsg = "No changes to commit. All files may have already been committed.";
                        } else if (errorMsg.contains("no remote repository") || errorMsg.contains("does not appear to be a git repository")) {
                            errorMsg = "No remote repository configured. Please set up a remote first.";
                        } else if (errorMsg.contains("failed to push") || errorMsg.contains("rejected")) {
                            errorMsg = "Push rejected. You may need to pull latest changes first.";
                        }
                        
                        showStatusMessage(project, "Commit failed: " + errorMsg);
                        notifyUI(project, "GitUI.showCommitError('" + escapeJsString(errorMsg) + "')");
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
     * Opens a file diff in the GitHub-style diff viewer
     */
    public String openFileDiffInIDE(JsonObject data) {
        LOG.info("Opening file diff in GitHub-style viewer: " + data.toString());
        
        try {
            String filePath = data.get("filePath").getAsString();
            String status = data.get("status").getAsString();
            
            LOG.info("Opening diff for file: " + filePath + " (status: " + status + ")");
            
            String projectPath = project.getBasePath();
            if (projectPath == null) {
                return createErrorResponse("Project path not found");
            }
            
            // Clean the file path - remove project name prefix if present
            String cleanedPath = cleanFilePath(filePath, project.getName());
            LOG.info("Cleaned file path for diff: '" + filePath + "' -> '" + cleanedPath + "'");
            
            // Show the diff in our custom viewer (asynchronously loads the diff content)
            ApplicationManager.getApplication().invokeLater(() -> {
                com.zps.zest.diff.GitHubStyleDiffViewer.showDiff(
                    project, 
                    cleanedPath, 
                    status
                );
            });
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("message", "Opening GitHub-style diff for " + filePath);
            return gson.toJson(response);
            
        } catch (Exception e) {
            LOG.error("Error opening file diff", e);
            return createErrorResponse("Failed to open diff: " + e.getMessage());
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
                // Show status message and notify UI
                showStatusMessage(project, "Pushing changes to remote...");
                notifyUI(project, "GitUI.showPushInProgress()");
                
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
                            showStatusMessage(project, "Push completed successfully!");
                            notifyUI(project, "GitUI.showPushSuccess()");
                            notifyUI(project, "if (window.GitModal && window.GitModal.hideModal) { window.GitModal.hideModal(); }");
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
                showStatusMessage(project, "Push completed successfully!");
                notifyUI(project, "GitUI.showPushSuccess()");

                // Close the Git modal after successful push
                notifyUI(project, "if (window.GitModal && window.GitModal.hideModal) { window.GitModal.hideModal(); }");
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
                
                showStatusMessage(project, "Push failed: " + errorMsg);
                notifyUI(project, "GitUI.showPushError('" + escapeJsString(errorMsg) + "')");
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
    private void showStatusMessage(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                // Send message to the browser tool window via JavaScript
                String script = String.format("if (window.showStatusMessage) { window.showStatusMessage('%s'); }",
                        escapeJsString(message));
                WebBrowserService.getInstance(project).executeJavaScript(script);
                LOG.info("Sent status message to tool window: " + message);
            } catch (Exception e) {
                LOG.warn("Failed to show tool window message: " + message, e);
            }
        });
    }
    
    /**
     * Notifies the UI about git operations
     */
    private void notifyUI(Project project, String jsFunction) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                WebBrowserService.getInstance(project).executeJavaScript(jsFunction);
                LOG.info("Sent UI notification: " + jsFunction);
            } catch (Exception e) {
                LOG.warn("Failed to notify UI: " + jsFunction, e);
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
     * Escapes strings for JavaScript string literals
     */
    private String escapeJsString(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "\\r").replace("\n", "\\n");
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
                        diff = executeGitCommand(projectPath, "git diff --cached -- \"" + cleanedPath + "\"");
                    }
                    break;
                    
                case "M": // Modified
                    // Get unstaged changes first, then staged if no unstaged
                    diff = executeGitCommand(projectPath, "git diff -- \"" + cleanedPath + "\"");
                    if (diff.trim().isEmpty()) {
                        diff = executeGitCommand(projectPath, "git diff --cached -- \"" + cleanedPath + "\"");
                    }
                    break;
                    
                case "D": // Deleted
                    // For deleted files that may not exist in the working tree anymore, we need to use more robust commands
                    try {
                        LOG.info("Processing deleted file: " + cleanedPath);
                        
                        // First, check if the file is already staged for deletion
                        try {
                            diff = executeGitCommand(projectPath, "git diff --cached -- \"" + cleanedPath + "\"");
                            if (!diff.trim().isEmpty()) {
                                LOG.info("Found staged deletion diff, length: " + diff.length());
                                break; // Use this diff if available
                            }
                        } catch (Exception e) {
                            LOG.info("Failed to get staged diff for deleted file: " + e.getMessage());
                        }
                        
                        // Next, try to get the file content from the last commit
                        try {
                            LOG.info("Trying to get file content from HEAD");
                            String content = executeGitCommand(projectPath, "git show HEAD:\"" + cleanedPath + "\"");
                            if (!content.trim().isEmpty()) {
                                LOG.info("Got content from HEAD, formatting as deletion diff");
                                diff = formatDeletedFileDiff(cleanedPath, content);
                                break; // Use this formatted diff
                            }
                        } catch (Exception e) {
                            LOG.info("Failed to get content from HEAD: " + e.getMessage());
                        }
                        
                        // If we can't get the content directly, try to find when it was last seen
                        try {
                            LOG.info("Trying to find file in Git history");
                            // List commits that affected this file
                            String history = executeGitCommand(projectPath, "git log --pretty=format:\"%H\" -n 1 -- \"" + cleanedPath + "\"");
                            if (!history.trim().isEmpty()) {
                                String commitHash = history.trim();
                                LOG.info("Found file in commit: " + commitHash);
                                
                                // Get the file content from that commit
                                String content = executeGitCommand(projectPath, "git show " + commitHash + ":\"" + cleanedPath + "\"");
                                if (!content.trim().isEmpty()) {
                                    LOG.info("Got content from commit " + commitHash);
                                    diff = formatDeletedFileDiff(cleanedPath, content);
                                    break; // Use this formatted diff
                                }
                            }
                        } catch (Exception e) {
                            LOG.info("Failed to get file history: " + e.getMessage());
                        }
                        
                        // If all else fails, provide a simple message
                        diff = "File was deleted: " + cleanedPath + "\n(Content not available)";
                        LOG.info("Using fallback message for deleted file");
                        
                    } catch (Exception e) {
                        LOG.warn("Error processing deleted file: " + e.getMessage(), e);
                        diff = "Deleted file: " + cleanedPath + "\n(Error retrieving content: " + e.getMessage() + ")";
                    }
                    break;
                    
                case "R": // Renamed
                    diff = executeGitCommand(projectPath, "git diff --cached -- \"" + cleanedPath + "\"");
                    break;
                    
                default:
                    diff = executeGitCommand(projectPath, "git diff -- \"" + cleanedPath + "\"");
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
     * Formats the content of a deleted file as a diff.
     * This is used when we can only get the file content but need to show it as a deletion.
     */
    private String formatDeletedFileDiff(String filePath, String content) {
        StringBuilder diff = new StringBuilder();
        diff.append("diff --git a/").append(filePath).append(" b/").append(filePath).append("\n");
        diff.append("deleted file mode 100644\n");
        diff.append("index 1234567..0000000\n");
        diff.append("--- a/").append(filePath).append("\n");
        diff.append("+++ /dev/null\n");
        
        String[] lines = content.split("\n");
        diff.append("@@ -1,").append(lines.length).append(" +0,0 @@\n");
        
        for (String line : lines) {
            diff.append("-").append(line).append("\n");
        }
        
        return diff.toString();
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
            // Use -- to ensure proper path-spec handling
            String result = executeGitCommand(projectPath, "git ls-files -- \"" + filePath + "\"");
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
     * Refreshes the git file list in the browser UI after a delay to ensure git operations are complete
     */
    private void refreshGitFileListDelayed() {
        // Use a timer to delay the refresh to ensure git operations are fully completed
        new java.util.Timer().schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                refreshGitFileList();
            }
        }, 1000); // 1 second delay
    }
    
    /**
     * Refreshes the git file list in the browser UI
     */
    private void refreshGitFileList() {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                String projectPath = project.getBasePath();
                if (projectPath == null) {
                    LOG.warn("Project path not found for git refresh");
                    return;
                }
                
                // Get the updated list of changed files using the same format as the original
                // This ensures compatibility with existing GitUtils.parseChangedFiles
                String changedFiles = "";
                String stagedFiles = "";
                String untrackedFiles = "";
                
                try {
                    changedFiles = executeGitCommand(projectPath, "git diff --name-status");
                } catch (Exception e) {
                    LOG.warn("Error getting unstaged changes: " + e.getMessage());
                }
                
                try {
                    stagedFiles = executeGitCommand(projectPath, "git diff --cached --name-status");
                } catch (Exception e) {
                    LOG.warn("Error getting staged changes: " + e.getMessage());
                }
                
                try {
                    untrackedFiles = executeGitCommand(projectPath, "git ls-files --others --exclude-standard");
                } catch (Exception e) {
                    LOG.warn("Error getting untracked files: " + e.getMessage());
                }
                
                // Combine all changes in the same format expected by GitUtils.parseChangedFiles
                StringBuilder allChanges = new StringBuilder();
                
                // Add staged files first (they take precedence)
                if (!stagedFiles.trim().isEmpty()) {
                    allChanges.append(stagedFiles);
                    if (!allChanges.toString().endsWith("\n")) {
                        allChanges.append("\n");
                    }
                }
                
                // Add unstaged files
                if (!changedFiles.trim().isEmpty()) {
                    // Filter out files that are already in staged
                    String[] changedLines = changedFiles.split("\n");
                    for (String line : changedLines) {
                        if (!line.trim().isEmpty() && !stagedFiles.contains(line.substring(2))) {
                            allChanges.append(line).append("\n");
                        }
                    }
                }
                
                // Add untracked files with 'A' status (using tab separator as expected)
                if (!untrackedFiles.trim().isEmpty()) {
                    String[] untracked = untrackedFiles.split("\n");
                    for (String file : untracked) {
                        if (!file.trim().isEmpty()) {
                            allChanges.append("A\t").append(file).append("\n");
                        }
                    }
                }
                
                String updatedFileList = allChanges.toString();
                
                // Log in the same format for debugging
                LOG.info("Refreshed git file list:");
                if (updatedFileList.trim().isEmpty()) {
                    LOG.info("  No files found");
                } else {
                    String[] lines = updatedFileList.split("\n");
                    LOG.info("  Total lines: " + lines.length);
                    for (int i = 0; i < Math.min(5, lines.length); i++) {
                        LOG.info("  Line " + i + ": " + lines[i]);
                    }
                    if (lines.length > 5) {
                        LOG.info("  ... and " + (lines.length - 5) + " more lines");
                    }
                }
                
                // Build JavaScript to refresh the UI
                String jsCommand;
                if (updatedFileList.trim().isEmpty()) {
                    // No more files - hide the modal
                    jsCommand = "if (window.GitModal && window.GitModal.hideModal) { " +
                               "  window.GitModal.hideModal(); " +
                               "} else if (document.getElementById('git-file-selection-modal')) { " +
                               "  document.getElementById('git-file-selection-modal').remove(); " +
                               "}";
                    LOG.info("No more files to commit - closing modal");
                } else {
                    // Update the file list - ensure we escape the string properly
                    // Make sure the file list string is properly escaped for JavaScript
                    String escapedFileList = escapeJsString(updatedFileList);
                    
                    jsCommand = String.format(
                        "(function() { " +
                        "  console.log('Refreshing git file list...'); " +
                        "  var fileList = '%s'; " +
                        "  console.log('File list to refresh:', fileList); " +
                        "  if (window.GitModal && window.GitModal.populateFileList) { " +
                        "    window.GitModal.populateFileList(fileList); " +
                        "  } else if (window.GitModal && window.GitModal.showFileSelectionModal) { " +
                        "    console.log('populateFileList not found, refreshing entire modal'); " +
                        "    window.GitModal.showFileSelectionModal(fileList); " +
                        "  } else { " +
                        "    console.error('GitModal not found or does not have required methods'); " +
                        "  } " +
                        "})();",
                        escapedFileList
                    );
                }
                
                WebBrowserService.getInstance(project).executeJavaScript(jsCommand);
                LOG.info("Sent refresh command to browser UI");
                
            } catch (Exception e) {
                LOG.error("Error refreshing git file list in browser", e);
            }
        });
    }
    
    /**
     * Gets the current git status for quick commit
     */
    public String getGitStatus() {
        LOG.info("Getting git status for quick commit");
        
        try {
            String projectPath = project.getBasePath();
            if (projectPath == null) {
                return createErrorResponse("Project path not found");
            }
            
            // Get all changed files including untracked
            String changedFiles = "";
            String stagedFiles = "";
            String untrackedFiles = "";
            
            try {
                // Get unstaged changes
                changedFiles = executeGitCommand(projectPath, "git diff --name-status");
            } catch (Exception e) {
                LOG.warn("Error getting unstaged changes: " + e.getMessage());
            }
            
            try {
                // Get staged changes
                stagedFiles = executeGitCommand(projectPath, "git diff --cached --name-status");
            } catch (Exception e) {
                LOG.warn("Error getting staged changes: " + e.getMessage());
            }
            
            try {
                // Get untracked files
                untrackedFiles = executeGitCommand(projectPath, "git ls-files --others --exclude-standard");
            } catch (Exception e) {
                LOG.warn("Error getting untracked files: " + e.getMessage());
            }
            
            // Combine all changes
            StringBuilder allChanges = new StringBuilder();
            
            // Add staged files first
            if (!stagedFiles.trim().isEmpty()) {
                allChanges.append(stagedFiles);
                if (!allChanges.toString().endsWith("\n")) {
                    allChanges.append("\n");
                }
            }
            
            // Add unstaged files
            if (!changedFiles.trim().isEmpty()) {
                String[] changedLines = changedFiles.split("\n");
                for (String line : changedLines) {
                    if (!line.trim().isEmpty() && !stagedFiles.contains(line.substring(2))) {
                        allChanges.append(line).append("\n");
                    }
                }
            }
            
            // Add untracked files with 'A' status
            if (!untrackedFiles.trim().isEmpty()) {
                String[] untracked = untrackedFiles.split("\n");
                for (String file : untracked) {
                    if (!file.trim().isEmpty()) {
                        allChanges.append("A\t").append(file).append("\n");
                    }
                }
            }
            
            String result = allChanges.toString();
            LOG.info("Git status collected: " + (result.isEmpty() ? "No changes" : result.split("\n").length + " files"));
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("changedFiles", result);
            return gson.toJson(response);
            
        } catch (Exception e) {
            LOG.error("Error getting git status", e);
            return createErrorResponse("Failed to get git status: " + e.getMessage());
        }
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
