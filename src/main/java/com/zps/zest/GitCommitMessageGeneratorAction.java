package com.zps.zest;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.git.GitCommitContext;
import com.zps.zest.browser.WebBrowserService;
import com.zps.zest.browser.JavaScriptBridge;
import com.zps.zest.browser.actions.OpenChatInEditorAction;
import com.zps.zest.browser.utils.ChatboxUtilities;
import com.zps.zest.browser.utils.GitCommandExecutor;
import com.zps.zest.git.GitStatusCollector;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Action that shows a git commit dialog for selecting files and entering commit messages.
 * The actual commit message generation is handled via LLM integration.
 */
public class GitCommitMessageGeneratorAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(GitCommitMessageGeneratorAction.class);

    // Hardcoded or configurable model name for new chat; can be refactored to support user selection.
    private static final String DEFAULT_MODEL_NAME = "local-model-mini";

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        // Create the context to maintain state
        GitCommitContext context = new GitCommitContext();
        context.setEvent(e);
        context.setProject(project);

        // Start with collecting git changes and showing file selection
        collectChangesAndShowFileSelection(context);
    }

    /**
     * Collects git changes and shows file selection modal
     */
    private void collectChangesAndShowFileSelection(GitCommitContext context) {
        Project project = context.getProject();

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Collecting Git Changes", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    // Collect git changes directly using GitStatusCollector
                    String projectPath = project.getBasePath();
                    if (projectPath == null) {
                        throw new RuntimeException("Project path not found");
                    }
                    GitStatusCollector statusCollector = new GitStatusCollector(projectPath);
                    String changedFiles = statusCollector.collectAllChanges();
                    context.setChangedFiles(changedFiles);

                    // Show file selection modal on EDT
                    ApplicationManager.getApplication().invokeLater(() -> {
                        showFileSelectionModal(context);
                    });

                } catch (Exception e) {
                    showError(project, new RuntimeException("Failed to collect git changes", e));
                }
            }
        });
    }

    /**
     * Shows file selection modal with simplified flow
     */
    private void showFileSelectionModal(GitCommitContext context) {
        String changedFiles = context.getChangedFiles();
        if (changedFiles == null || changedFiles.trim().isEmpty()) {
            Messages.showInfoMessage(context.getProject(), "No changed files found", "Git Commit");
            return;
        }

        // Open git UI chat editor and show file selection
        try {
            ApplicationManager.getApplication().invokeLater(() -> {
                // Open chat editor for git commit workflow
                OpenChatInEditorAction.Companion.openChatInSplitEditor(context.getProject(), "git-commit-session");
                
                // Wait for editor to load, then show file selection in git UI
                javax.swing.Timer timer = new javax.swing.Timer(1500, e -> {
                    try {
                        String escapedFiles = changedFiles.replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "\\r")
                                .replace("\t", "\\t");

                        // Try git UI specific file selection, fallback to generic modal
                        String script = 
                            "if (window.showGitFileSelection) { " +
                            "  window.showGitFileSelection(\"" + escapedFiles + "\"); " +
                            "} else if (window.showFileSelectionModal) { " +
                            "  window.showFileSelectionModal(\"" + escapedFiles + "\"); " +
                            "} else { " +
                            "  console.log('No file selection handler available'); " +
                            "  alert('Git file selection not available. Please use git UI directly.'); " +
                            "}";

                        WebBrowserService.getInstance(context.getProject()).executeJavaScript(script);
                        LOG.info("File selection shown in git UI chat editor");
                        
                    } catch (Exception ex) {
                        LOG.error("Error showing file selection in git UI", ex);
                    }
                });
                timer.setRepeats(false);
                timer.start();
            });

        } catch (Exception e) {
            LOG.error("Error opening git UI for file selection", e);
            Messages.showErrorDialog(context.getProject(), "Failed to open git UI: " + e.getMessage(), "Error");
        }
    }

    /**
     * Continues with selected files (called by GitService)
     * Following the pattern from ChatboxLlmApiCallStage
     */
    public static void continueWithSelectedFiles(GitCommitContext context, boolean shouldPush) {
        GitCommitMessageGeneratorAction action = new GitCommitMessageGeneratorAction();
        action.executeCommitWithResponse(context, shouldPush);
    }

    /**
     * Executes commit pipeline with proper response handling
     * Following the pattern from ChatboxLlmApiCallStage
     */
    private void executeCommitWithResponse(GitCommitContext context, boolean shouldPush) {
        Project project = context.getProject();
        if (project == null) return;

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Processing Commit", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setText("Generating commit message prompt...");
                    indicator.setFraction(0.1);

                    // Generate prompt for selected files directly
                    String prompt = generateCommitPrompt(context);

                    if (prompt == null || prompt.isEmpty()) {
                        throw new RuntimeException("Failed to generate commit message prompt");
                    }

                    indicator.setText("Setting up response listener...");
                    indicator.setFraction(0.3);

                    // STEP 1: Set up response listener FIRST (like ChatboxLlmApiCallStage)
                    WebBrowserService browserService = WebBrowserService.getInstance(project);
                    JavaScriptBridge jsBridge = browserService.getBrowserPanel().getBrowserManager().getJavaScriptBridge();
                    java.util.concurrent.CompletableFuture<String> responseFuture = jsBridge.waitForChatResponse(300); // 5 minutes

                    indicator.setText("Sending prompt to chat...");
                    indicator.setFraction(0.5);

                    // STEP 2: Send prompt (like ChatboxLlmApiCallStage)
                    boolean sent = sendPromptToChatBox(project, prompt, DEFAULT_MODEL_NAME);
                    if (!sent) {
                        throw new RuntimeException("Failed to send prompt to chat box");
                    }

                    indicator.setText("Waiting for LLM response...");
                    indicator.setFraction(0.7);

                    // STEP 3: Wait for response (like ChatboxLlmApiCallStage)
                    String response = responseFuture.get(); // This blocks until response or timeout

                    if (response == null || response.trim().isEmpty()) {
                        throw new RuntimeException("No response received from chat");
                    }

                    indicator.setText("Processing response and committing...");
                    indicator.setFraction(0.9);

                    // Extract commit message and commit
                    CommitMessage commitMessage = extractCommitMessage(response);
                    stageAndCommit(context, commitMessage, shouldPush);

                    indicator.setText("Commit completed successfully!");
                    indicator.setFraction(1.0);

                } catch (Exception e) {
                    LOG.error("Error in commit pipeline", e);
                    showError(project, new RuntimeException("Commit failed: " + e.getMessage(), e));
                }
            }
        });
    }

    /**
     * Generates commit message prompt from selected files
     */
    private String generateCommitPrompt(GitCommitContext context) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("Generate a comprehensive git commit message for the following changed files:\n\n");
        
        List<GitCommitContext.SelectedFile> selectedFiles = context.getSelectedFiles();
        if (selectedFiles != null && !selectedFiles.isEmpty()) {
            for (GitCommitContext.SelectedFile file : selectedFiles) {
                promptBuilder.append("- ").append(file.getPath()).append("\n");
            }
        }
        
        promptBuilder.append("\nPlease analyze the changes and provide:\n");
        promptBuilder.append("1. A concise subject line (max 72 characters)\n");
        promptBuilder.append("2. A detailed description explaining the changes\n\n");
        promptBuilder.append("Format your response with:\n");
        promptBuilder.append("```commit-short\n");
        promptBuilder.append("[Your subject line here]\n");
        promptBuilder.append("```\n\n");
        promptBuilder.append("```commit-long\n");
        promptBuilder.append("[Your subject line here]\n\n");
        promptBuilder.append("[Your detailed description here]\n");
        promptBuilder.append("```");
        
        return promptBuilder.toString();
    }

    /**
     * Extracts commit message from LLM response and returns both subject and description.
     * Strategy:
     * - Subject: Content from commit-short block (first line only)
     * - Description: Content from commit-long block (skip first line, preserve ALL remaining lines)
     * <p>
     * Multi-line Handling:
     * - Preserves empty lines, indentation, and formatting in commit-long body
     * - Does NOT trim trailing/leading spaces from individual lines
     * - Maintains original line structure for proper Git commit message format
     */
    private CommitMessage extractCommitMessage(String response) {
        LOG.info("Extracting commit message from LLM response");

        String subject = "";
        String description = "";

        // Extract SHORT MESSAGE from commit-short block
        String shortPattern = "```commit-short";
        int shortStartIdx = response.indexOf(shortPattern);
        if (shortStartIdx != -1) {
            shortStartIdx += shortPattern.length();
            int shortEndIdx = response.indexOf("```", shortStartIdx);
            if (shortEndIdx != -1) {
                String shortMessage = response.substring(shortStartIdx, shortEndIdx).trim();
                LOG.info("Found commit-short message: " + shortMessage);
                // Take only the first line as subject
                subject = shortMessage.split("\n")[0].trim();
            }
        }

        // Extract LONG MESSAGE from commit-long block (skip first line)
        String longPattern = "```commit-long";
        int longStartIdx = response.indexOf(longPattern);
        if (longStartIdx != -1) {
            longStartIdx += longPattern.length();
            int longEndIdx = response.indexOf("```", longStartIdx);
            if (longEndIdx != -1) {
                String longMessage = response.substring(longStartIdx, longEndIdx).trim();
                LOG.info("Found commit-long message: " + longMessage.substring(0, Math.min(100, longMessage.length())) + "...");

                // Skip the first line (redundant with commit-short), preserve all remaining lines
                String[] lines = longMessage.split("\n");
                if (lines.length > 1) {
                    StringBuilder descBuilder = new StringBuilder();

                    // Start from line 1 (skip line 0 which is the subject)
                    for (int i = 1; i < lines.length; i++) {
                        String line = lines[i];

                        // Add line to description (preserve original formatting including empty lines)
                        if (i > 1) { // Add newline before each line except the first description line
                            descBuilder.append("\n");
                        }
                        descBuilder.append(line); // Keep original line content (including spaces/tabs)
                    }

                    description = descBuilder.toString();
                    LOG.info("Extracted multi-line description (" + (lines.length - 1) + " lines):");
                    LOG.info("Description preview: " + description.substring(0, Math.min(200, description.length())) +
                            (description.length() > 200 ? "..." : ""));
                }
            }
        }

        // If we found a subject from commit-short, use it
        if (!subject.isEmpty()) {
            LOG.info("Using commit-short as subject: '" + subject + "'");
            LOG.info("Using commit-long body as description (length: " + description.length() + ")");
            return new CommitMessage(subject, description);
        }

        // Fallback: if no commit-short found, parse commit-long normally
        if (longStartIdx != -1) {
            longStartIdx += longPattern.length();
            int longEndIdx = response.indexOf("```", longStartIdx);
            if (longEndIdx != -1) {
                String longMessage = response.substring(longStartIdx, longEndIdx).trim();
                LOG.info("No commit-short found, parsing commit-long normally");
                return parseCommitMessage(longMessage);
            }
        }

        // Last resort: extract first meaningful line as subject, rest as description
        String[] lines = response.split("\n");
        String fallbackSubject = "";
        StringBuilder fallbackDescription = new StringBuilder();

        boolean foundSubject = false;
        boolean foundDescription = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // Skip empty lines, markdown headers, and code block markers
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("```") || line.startsWith("##")) {
                continue;
            }

            if (!foundSubject) {
                // First meaningful line becomes the subject
                fallbackSubject = line.length() > 72 ? line.substring(0, 72) : line;
                foundSubject = true;
                LOG.info("Using fallback subject: " + fallbackSubject);
            } else {
                // Subsequent meaningful lines become description
                if (foundDescription) {
                    fallbackDescription.append("\n");
                }
                fallbackDescription.append(lines[i]); // Keep original line (with potential indentation)
                foundDescription = true;
            }
        }

        String fallbackDescriptionStr = fallbackDescription.toString().trim();
        if (foundSubject) {
            LOG.info("Using fallback parsing - Subject: '" + fallbackSubject + "'");
            LOG.info("Description length: " + fallbackDescriptionStr.length() + " characters");
            if (!fallbackDescriptionStr.isEmpty()) {
                LOG.info("Description preview: " + fallbackDescriptionStr.substring(0, Math.min(100, fallbackDescriptionStr.length())) +
                        (fallbackDescriptionStr.length() > 100 ? "..." : ""));
            }
            return new CommitMessage(fallbackSubject, fallbackDescriptionStr);
        }

        LOG.warn("No commit message found, using default");
        return new CommitMessage("Update selected files", "");
    }

    /**
     * Parses a commit message string into subject (first line) and description (rest of lines).
     * This ensures proper Git commit message format where:
     * - First line = short summary (subject)
     * - Rest of lines = detailed description (body)
     */
    private CommitMessage parseCommitMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return new CommitMessage("Update files", "");
        }

        // Normalize line endings
        message = message.replace("\r\n", "\n").replace("\r", "\n");

        // Split into lines to handle first line vs rest
        String[] lines = message.split("\n");

        if (lines.length == 0) {
            return new CommitMessage("Update files", "");
        }

        // First line becomes the subject (short message)
        String subject = lines[0].trim();

        // If subject is empty, use a default
        if (subject.isEmpty()) {
            subject = "Update files";
        }

        // Everything after the first line becomes the description (long message)
        StringBuilder descriptionBuilder = new StringBuilder();
        boolean foundNonEmptyLine = false;

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];

            // Skip the first empty line after subject (conventional Git format)
            if (i == 1 && line.trim().isEmpty()) {
                continue;
            }

            // Add line to description
            if (foundNonEmptyLine || !line.trim().isEmpty()) {
                if (foundNonEmptyLine) {
                    descriptionBuilder.append("\n");
                }
                descriptionBuilder.append(line);
                foundNonEmptyLine = true;
            }
        }

        String description = descriptionBuilder.toString().trim();

        // Log the parsing result
        LOG.info("Parsed commit message:");
        LOG.info("  Subject (first line): '" + subject + "'");
        LOG.info("  Description length: " + description.length() + " characters");
        if (!description.isEmpty()) {
            LOG.info("  Description preview: " + description.substring(0, Math.min(100, description.length())) +
                    (description.length() > 100 ? "..." : ""));
        }

        return new CommitMessage(subject, description);
    }

    /**
     * Data class to hold commit subject and description.
     * Follows Git commit message convention:
     * - Subject: First line, should be ≤50 characters, summarizes the change
     * - Description: Remaining lines, provides detailed explanation
     */
    private static class CommitMessage {
        private final String subject;
        private final String description;

        public CommitMessage(String subject, String description) {
            this.subject = subject != null ? subject : "";
            this.description = description != null ? description : "";
        }

        public String getSubject() {
            return subject;
        }

        public String getDescription() {
            return description;
        }

        public boolean hasDescription() {
            return !description.isEmpty();
        }

        /**
         * Returns the complete commit message formatted for Git
         */
        public String getFullMessage() {
            if (hasDescription()) {
                return subject + "\n\n" + description;
            }
            return subject;
        }

        /**
         * Validates the commit message structure
         */
        public boolean isValid() {
            return !subject.trim().isEmpty();
        }

        @Override
        public String toString() {
            return "CommitMessage{subject='" + subject + "', hasDescription=" + hasDescription() + "}";
        }
    }

    /**
     * Stages selected files and commits with properly formatted message.
     * Uses Git's standard commit format:
     * - First -m flag contains the subject (first line)
     * - Second -m flag contains the description (remaining lines)
     */
    private void stageAndCommit(GitCommitContext context, CommitMessage commitMessage, boolean shouldPush) throws IOException, InterruptedException {
        Project project = context.getProject();
        String projectPath = project.getBasePath();

        if (projectPath == null) {
            throw new RuntimeException("Project path not found");
        }

        List<GitCommitContext.SelectedFile> selectedFiles = context.getSelectedFiles();
        if (selectedFiles == null || selectedFiles.isEmpty()) {
            throw new RuntimeException("No files selected for commit");
        }

        // Validate commit message
        if (!commitMessage.isValid()) {
            throw new RuntimeException("Invalid commit message: subject cannot be empty");
        }

        LOG.info("Staging and committing with message: " + commitMessage.toString());

        // Stage each selected file
        for (GitCommitContext.SelectedFile file : selectedFiles) {
            // Clean the file path - remove project name prefix if present
            String cleanPath = cleanFilePath(file.getPath(), project.getName());
            String command = "git add \"" + cleanPath + "\"";

            LOG.info("Staging file with command: " + command);
            executeGitCommand(projectPath, command);
            LOG.info("Staged file: " + cleanPath);
        }

        // Commit with proper Git message format using multiple -m flags for multi-line descriptions
        String subject = commitMessage.getSubject();
        String description = commitMessage.getDescription();

        if (commitMessage.hasDescription()) {
            // Split description into lines and use separate -m flag for each line
            // This preserves proper line breaks without over-escaping
            String[] descriptionLines = description.split("\n");

            StringBuilder commitCommand = new StringBuilder();
            commitCommand.append("git commit -m \"").append(escapeForShell(subject)).append("\"");

            // Add each description line as a separate -m flag
            for (String line : descriptionLines) {
                commitCommand.append(" -m \"").append(escapeForShell(line)).append("\"");
            }

            LOG.info("Committing with subject and multi-line description:");
            LOG.info("  Subject: " + subject);
            LOG.info("  Description lines: " + descriptionLines.length);
            LOG.info("  Command: " + commitCommand.toString().substring(0, Math.min(150, commitCommand.length())) + "...");

            String result = executeGitCommand(projectPath, commitCommand.toString());
            LOG.info("Commit executed successfully: " + result);

            // Show commit success message in tool window
            showToolWindowMessage(project, "Commit " + selectedFiles.size() + " files");

            if (shouldPush){
                executeGitCommand(projectPath, "git push");
                LOG.info("Push executed successfully");
                // Show push success message in tool window
                showToolWindowMessage(project, "Push " + selectedFiles.size() + " files");
            }
        } else {
            // Use single -m for subject-only commits
            String commitCommand = String.format("git commit -m \"%s\"", escapeForShell(subject));
            LOG.info("Committing with subject only: " + subject);

            String result = executeGitCommand(projectPath, commitCommand);
            LOG.info("Commit executed successfully: " + result);

            // Show commit success message in tool window
            showToolWindowMessage(project, "Commit " + selectedFiles.size() + " files");

            if (shouldPush){
                executeGitCommand(projectPath, "git push");
                LOG.info("Push executed successfully");
                // Show push success message in tool window
                showToolWindowMessage(project, "Push " + selectedFiles.size() + " files");
            }
        }

        LOG.info("Commit completed successfully for " + selectedFiles.size() + " file(s)");
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
     * Executes a git command using the shared utility
     */
    private String executeGitCommand(String workingDir, String command) throws IOException, InterruptedException {
        return GitCommandExecutor.execute(workingDir, command);
    }

    /**
     * Sends the commit message prompt to the git UI chat editor instead of tool window.
     */
    private boolean sendPromptToChatBox(Project project, String prompt, String modelName) {
        LOG.info("Sending commit message prompt to git UI chat editor with model: " + modelName);

        try {
            // Open the chat editor in split view
            ApplicationManager.getApplication().invokeLater(() -> {
                OpenChatInEditorAction.Companion.openChatInSplitEditor(project, "git-commit-session");
                
                // Wait briefly for editor to open, then send prompt
                javax.swing.Timer timer = new javax.swing.Timer(1000, e -> {
                    try {
                        // Set the model for git UI
                        String setModelScript = "if (window.setModel) { window.setModel('" + modelName + "'); }";
                        WebBrowserService.getInstance(project).executeJavaScript(setModelScript);
                        
                        // Send prompt to the chat editor
                        String sendPromptScript = "if (window.sendMessage || window.submitMessage) { " +
                            "var textarea = document.querySelector('textarea, .chat-input, #message-input'); " +
                            "if (textarea) { " +
                                "textarea.value = " + escapeJavaScriptString(prompt) + "; " +
                                "var submitBtn = document.querySelector('button[type=\"submit\"], .submit-btn, .send-btn'); " +
                                "if (submitBtn) submitBtn.click(); " +
                            "} }";
                        WebBrowserService.getInstance(project).executeJavaScript(sendPromptScript);
                        
                        LOG.info("Prompt sent to chat editor successfully");
                        
                    } catch (Exception ex) {
                        LOG.warn("Failed to send prompt to chat editor", ex);
                    }
                });
                timer.setRepeats(false);
                timer.start();
            });
            
            return true;
            
        } catch (Exception e) {
            LOG.error("Failed to open chat editor", e);
            return false;
        }
    }
    
    /**
     * Escape string for JavaScript - handles quotes and newlines properly
     */
    private String escapeJavaScriptString(String input) {
        if (input == null) return "null";
        
        return "\"" + input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            + "\"";
    }

    /**
     * Shows a status message in the git UI chat editor
     */
    private void showToolWindowMessage(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                // Send status message to the git UI chat editor via JavaScript
                String script = String.format(
                    "if (window.showGitStatusMessage || window.addChatMessage) { " +
                    "  var func = window.showGitStatusMessage || window.addChatMessage; " +
                    "  func('✅ %s'); " +
                    "} else if (document.querySelector('.chat-messages, .git-status')) { " +
                    "  var container = document.querySelector('.chat-messages, .git-status'); " +
                    "  var msgDiv = document.createElement('div'); " +
                    "  msgDiv.textContent = '✅ %s'; " +
                    "  msgDiv.style.color = 'green'; " +
                    "  msgDiv.style.fontWeight = 'bold'; " +
                    "  container.appendChild(msgDiv); " +
                    "}",
                    message.replace("'", "\\'"), message.replace("'", "\\'")
                );
                WebBrowserService.getInstance(project).executeJavaScript(script);
                LOG.info("Sent status message to git UI chat editor: " + message);
            } catch (Exception e) {
                LOG.warn("Failed to show git UI status message: " + message, e);
            }
        });
    }

    /**
     * Shows an error message on the UI thread.
     */
    private void showError(Project project, Exception e) {
        e.printStackTrace();
        LOG.error("Error in GitCommitMessageGeneratorAction: " + e.getMessage(), e);

        ApplicationManager.getApplication().invokeLater(() -> {
            Messages.showErrorDialog(project, "Error: " + e.getMessage(), "Commit Message Generation Failed");
        });
    }
}