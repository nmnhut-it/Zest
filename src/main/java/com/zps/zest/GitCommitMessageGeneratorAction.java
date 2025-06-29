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
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.zps.zest.browser.GitCommitContext;
import com.zps.zest.browser.WebBrowserService;
import com.zps.zest.browser.JavaScriptBridge;
import com.zps.zest.browser.utils.ChatboxUtilities;
import com.zps.zest.browser.utils.GitCommandExecutor;
import com.zps.zest.ConfigurationManager;
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
    private static final String DEFAULT_MODEL_NAME = "Qwen2.5-Coder-7B";

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
                    // Collect git changes using existing stage
                    GitChangesCollectionStage changesStage = new GitChangesCollectionStage();
                    changesStage.process(context);

                    // Show file selection modal on EDT
                    ApplicationManager.getApplication().invokeLater(() -> {
                        showFileSelectionModal(context);
                    });

                } catch (Exception e) {
                    showError(project, new PipelineExecutionException("Failed to collect git changes", e));
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

        // Show modal via JavaScript - proper string escaping
        try {
            String escapedFiles = changedFiles.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");

            String script = "if (window.showFileSelectionModal) { window.showFileSelectionModal(\"" + escapedFiles + "\"); }";

            LOG.info("Showing file selection modal for files: " + changedFiles.substring(0, Math.min(100, changedFiles.length())));
            WebBrowserService.getInstance(context.getProject()).executeJavaScript(script);

        } catch (Exception e) {
            LOG.error("Error showing file selection modal", e);
            Messages.showErrorDialog(context.getProject(), "Failed to show file selection: " + e.getMessage(), "Error");
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

                    // Generate prompt for selected files
                    CommitPromptGenerationStage promptStage = new CommitPromptGenerationStage();
                    promptStage.process(context);

                    String prompt = context.getPrompt();
                    if (prompt == null || prompt.isEmpty()) {
                        throw new PipelineExecutionException("Failed to generate commit message prompt");
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
                        throw new PipelineExecutionException("Failed to send prompt to chat box");
                    }

                    indicator.setText("Waiting for LLM response...");
                    indicator.setFraction(0.7);

                    // STEP 3: Wait for response (like ChatboxLlmApiCallStage)
                    String response = responseFuture.get(); // This blocks until response or timeout

                    if (response == null || response.trim().isEmpty()) {
                        throw new PipelineExecutionException("No response received from chat");
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
                    showError(project, new PipelineExecutionException("Commit failed: " + e.getMessage(), e));
                }
            }
        });
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
    private void stageAndCommit(GitCommitContext context, CommitMessage commitMessage, boolean shouldPush) throws IOException, InterruptedException, PipelineExecutionException {
        Project project = context.getProject();
        String projectPath = project.getBasePath();

        if (projectPath == null) {
            throw new PipelineExecutionException("Project path not found");
        }

        List<GitCommitContext.SelectedFile> selectedFiles = context.getSelectedFiles();
        if (selectedFiles == null || selectedFiles.isEmpty()) {
            throw new PipelineExecutionException("No files selected for commit");
        }

        // Validate commit message
        if (!commitMessage.isValid()) {
            throw new PipelineExecutionException("Invalid commit message: subject cannot be empty");
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
     * Sends the commit message prompt to the chat box using newChat and activates the browser window.
     *
     * @param modelName the name of the model to use (e.g., "Qwen2.5-Coder-7B")
     */
    private boolean sendPromptToChatBox(Project project, String prompt, String modelName) {
        LOG.info("Sending commit message prompt to chat box using new chat and model: " + modelName);

        // Start a new chat with the desired model and prompt
        ChatboxUtilities.clickNewChatButton(project);
        // Use browserService.executeJavaScript() before creating the new chat
        String script = "window.__selected_model_name__ = '" + modelName + "';";
        WebBrowserService.getInstance(project).executeJavaScript(script);
        ChatboxUtilities.sendTextAndSubmit(project, prompt, false, null, false, ChatboxUtilities.EnumUsage.CHAT_GIT_COMMIT_MESSAGE);

        // Activate browser tool window on EDT
        ApplicationManager.getApplication().invokeLater(() -> {
            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("ZPS Chat");
            if (toolWindow != null) {
                toolWindow.activate(null);
            }
        });

        return true;
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
     * Shows an error message on the UI thread.
     */
    private void showError(Project project, PipelineExecutionException e) {
        e.printStackTrace();
        LOG.error("Error in GitCommitMessageGeneratorAction: " + e.getMessage(), e);

        ApplicationManager.getApplication().invokeLater(() -> {
            Messages.showErrorDialog(project, "Error: " + e.getMessage(), "Commit Message Generation Failed");
        });
    }
}

/**
 * Pipeline for generating git commit messages.
 */
class GitCommitPipeline {
    private final List<PipelineStage> stages = new ArrayList<>();

    /**
     * Adds a stage to the pipeline.
     *
     * @param stage The stage to add
     * @return This pipeline instance for method chaining
     */
    public GitCommitPipeline addStage(PipelineStage stage) {
        stages.add(stage);
        return this;
    }

    /**
     * Gets the number of stages in the pipeline.
     *
     * @return The number of stages
     */
    public int getStageCount() {
        return stages.size();
    }

    /**
     * Gets a stage by index.
     *
     * @param index The index of the stage
     * @return The stage at the given index
     */
    public PipelineStage getStage(int index) {
        return stages.get(index);
    }
}

/**
 * Stage that collects git changes by executing git commands.
 */
class GitChangesCollectionStage implements PipelineStage {
    private static final Logger LOG = Logger.getInstance(GitChangesCollectionStage.class);

    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        LOG.info("Collecting git changes with enhanced debugging");

        if (!(context instanceof GitCommitContext)) {
            throw new PipelineExecutionException("Invalid context type");
        }

        GitCommitContext gitContext = (GitCommitContext) context;
        Project project = gitContext.getProject();

        try {
            String projectPath = project.getBasePath();
            if (projectPath == null) {
                throw new PipelineExecutionException("Project path not found");
            }

            LOG.info("Project base path: " + projectPath);

            // Get the current branch name
            String branchName = executeGitCommand(projectPath, "git rev-parse --abbrev-ref HEAD");
            gitContext.setBranchName(branchName.trim());
            LOG.info("Current branch: " + branchName.trim());

            // Try multiple methods to get changed files
            String changedFiles = getChangedFilesRobust(projectPath);

            LOG.info("=== CHANGED FILES DEBUG ===");
            LOG.info("Raw output length: " + (changedFiles != null ? changedFiles.length() : "null"));
            LOG.info("Is empty: " + (changedFiles == null || changedFiles.trim().isEmpty()));

            if (changedFiles != null && !changedFiles.trim().isEmpty()) {
                LOG.info("Raw content preview: [" + changedFiles.substring(0, Math.min(200, changedFiles.length())) + "]");

                // Log each line separately
                String[] lines = changedFiles.split("\n");
                LOG.info("Split into " + lines.length + " lines:");
                for (int i = 0; i < Math.min(lines.length, 10); i++) { // Log first 10 lines
                    LOG.info("Line " + i + ": [" + lines[i] + "] (length: " + lines[i].length() + ")");
                }
            } else {
                LOG.warn("No changed files found!");
            }
            LOG.info("===========================");

            gitContext.setChangedFiles(changedFiles);

            // Get the git diff
            String gitDiff = executeGitCommand(projectPath, "git diff");
            gitContext.setGitDiff(gitDiff);
            LOG.info("Git diff length: " + (gitDiff != null ? gitDiff.length() : "null"));

            LOG.info("Git changes collected successfully");
        } catch (Exception e) {
            LOG.error("Error collecting git changes", e);
            throw new PipelineExecutionException("Failed to collect git changes: " + e.getMessage(), e);
        }
    }

    /**
     * Executes a git command using the shared utility.
     */
    private String executeGitCommand(String workingDir, String command) throws IOException, InterruptedException {
        return GitCommandExecutor.execute(workingDir, command);
    }

    // Add this new robust method to get changed files INCLUDING untracked files
    private String getChangedFilesRobust(String projectPath) throws IOException, InterruptedException {
        LOG.info("Trying multiple git commands to get changed files (including untracked)...");

        // Strategy 1: git status --porcelain --untracked-files=all (BEST for new files)
        try {
            String porcelainResult = executeGitCommand(projectPath, "git status --porcelain --untracked-files=all");
            LOG.info("git status --porcelain --untracked-files=all result: [" + porcelainResult + "]");
            if (porcelainResult != null && !porcelainResult.trim().isEmpty()) {
                LOG.info("Using git status --porcelain --untracked-files=all (length: " + porcelainResult.length() + ")");
                return convertPorcelainToNameStatus(porcelainResult);
            }
        } catch (Exception e) {
            LOG.warn("git status --porcelain --untracked-files=all failed: " + e.getMessage());
        }

        // Strategy 2: Combine tracked changes + untracked files
        try {
            StringBuilder combined = new StringBuilder();

            // Get tracked changes
            String trackedChanges = executeGitCommand(projectPath, "git diff --name-status");
            if (trackedChanges != null && !trackedChanges.trim().isEmpty()) {
                combined.append(trackedChanges);
                LOG.info("Found tracked changes: " + trackedChanges.length() + " chars");
            }

            // Get staged changes
            String stagedChanges = executeGitCommand(projectPath, "git diff --cached --name-status");
            if (stagedChanges != null && !stagedChanges.trim().isEmpty()) {
                if (combined.length() > 0) combined.append("\n");
                combined.append(stagedChanges);
                LOG.info("Found staged changes: " + stagedChanges.length() + " chars");
            }

            // Get untracked files using ls-files
            String untrackedFiles = executeGitCommand(projectPath, "git ls-files --others --exclude-standard");
            if (untrackedFiles != null && !untrackedFiles.trim().isEmpty()) {
                String[] files = untrackedFiles.split("\n");
                for (String file : files) {
                    if (file.trim().isEmpty()) continue;
                    if (combined.length() > 0) combined.append("\n");
                    combined.append("A\t").append(file.trim()); // Mark untracked as Added
                }
                LOG.info("Found untracked files: " + files.length);
            }

            String result = combined.toString();
            if (!result.trim().isEmpty()) {
                LOG.info("Using combined approach (length: " + result.length() + ")");
                return result;
            }

        } catch (Exception e) {
            LOG.warn("Combined approach failed: " + e.getMessage());
        }

        // Strategy 3: git status --short --untracked-files=all (fallback)
        try {
            String shortResult = executeGitCommand(projectPath, "git status --short --untracked-files=all");
            LOG.info("git status --short --untracked-files=all result: [" + shortResult + "]");
            if (shortResult != null && !shortResult.trim().isEmpty()) {
                LOG.info("Using git status --short --untracked-files=all (length: " + shortResult.length() + ")");
                return convertShortStatusToNameStatus(shortResult);
            }
        } catch (Exception e) {
            LOG.warn("git status --short --untracked-files=all failed: " + e.getMessage());
        }

        // Strategy 4: Basic git status --porcelain (without untracked flag)
        try {
            String porcelainResult = executeGitCommand(projectPath, "git status --porcelain");
            LOG.info("git status --porcelain result: [" + porcelainResult + "]");
            if (porcelainResult != null && !porcelainResult.trim().isEmpty()) {
                LOG.info("Using git status --porcelain (length: " + porcelainResult.length() + ")");
                return convertPorcelainToNameStatus(porcelainResult);
            }
        } catch (Exception e) {
            LOG.warn("git status --porcelain failed: " + e.getMessage());
        }

        LOG.warn("All git commands failed or returned empty results");

        // Debug: Let's also try to show what git sees
        try {
            String debugStatus = executeGitCommand(projectPath, "git status");
            LOG.info("=== FULL GIT STATUS DEBUG ===");
            LOG.info("Full git status output:");
            LOG.info(debugStatus.substring(0, Math.min(500, debugStatus.length())) + (debugStatus.length() > 500 ? "..." : ""));
            LOG.info("==============================");
        } catch (Exception e) {
            LOG.warn("Could not get full git status for debugging: " + e.getMessage());
        }

        return "";
    }

    // Convert git status --porcelain output to name-status format
    private String convertPorcelainToNameStatus(String porcelainOutput) {
        StringBuilder result = new StringBuilder();
        String[] lines = porcelainOutput.split("\n");

        for (String line : lines) {
            if (line.trim().isEmpty()) continue;

            // Porcelain format: XY filename
            // X = staged status, Y = unstaged status
            // ?? = untracked file
            // Special case: renamed files may have format "R  old_name -> new_name"
            if (line.length() >= 3) {
                String statusChars = line.substring(0, 2);
                String filenamePart = line.substring(3); // Skip XY and space

                char status;
                String filename;

                // Handle untracked files (marked as ??)
                if (statusChars.equals("??")) {
                    status = 'A'; // Treat untracked as "Added" for UI purposes
                    filename = filenamePart;
                    LOG.info("Found untracked file: " + filename);
                } else {
                    // Handle tracked files
                    char staged = statusChars.charAt(0);
                    char unstaged = statusChars.charAt(1);

                    // Determine the primary status (prefer unstaged, then staged)
                    if (unstaged != ' ' && unstaged != '?') {
                        status = unstaged;
                    } else if (staged != ' ' && staged != '?') {
                        status = staged;
                    } else {
                        continue; // Skip if no meaningful status
                    }

                    // Special handling for renamed files
                    if (status == 'R' || status == 'C') {
                        // Renamed/copied files may have format "old_name -> new_name"
                        if (filenamePart.contains(" -> ")) {
                            // For renames, we want to show the new name in the UI
                            String[] renameParts = filenamePart.split(" -> ");
                            if (renameParts.length == 2) {
                                filename = renameParts[1].trim(); // Use new name
                                LOG.info("Found renamed file: " + renameParts[0].trim() + " -> " + filename);
                            } else {
                                filename = filenamePart; // Fallback to original
                            }
                        } else {
                            filename = filenamePart;
                        }
                    } else {
                        filename = filenamePart;
                    }
                }

                // Convert to name-status format
                if (status != ' ') {
                    result.append(status).append("\t").append(filename).append("\n");
                    LOG.info("Converted: " + statusChars + " " + filenamePart + " -> " + status + "\t" + filename);
                }
            }
        }

        String converted = result.toString();
        LOG.info("Converted porcelain to name-status (" + lines.length + " lines -> " + converted.split("\n").length + " entries)");
        LOG.info("Converted result preview: [" + converted.substring(0, Math.min(200, converted.length())) + "]");
        return converted;
    }

    // Convert git status --short output to name-status format
    private String convertShortStatusToNameStatus(String shortOutput) {
        // Short format is similar to porcelain, so reuse the conversion
        return convertPorcelainToNameStatus(shortOutput);
    }
}

/**
 * Stage that generates a prompt for the commit message based on the collected git changes.
 */
class CommitPromptGenerationStage implements PipelineStage {
    private static final Logger LOG = Logger.getInstance(CommitPromptGenerationStage.class);

    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        LOG.info("Generating commit message prompt");

        if (!(context instanceof GitCommitContext)) {
            throw new PipelineExecutionException("Invalid context type");
        }

        GitCommitContext gitContext = (GitCommitContext) context;

        // Get the git diff information
        String branchName = gitContext.getBranchName();
        String changedFiles = gitContext.getChangedFiles();
        String gitDiff = gitContext.getGitDiff();
        List<GitCommitContext.SelectedFile> selectedFiles = gitContext.getSelectedFiles();

        // If files are selected, filter to only those files
        if (selectedFiles != null && !selectedFiles.isEmpty()) {
            changedFiles = filterChangedFiles(changedFiles, selectedFiles);
            gitDiff = filterGitDiff(gitContext, selectedFiles);
        }

        if (changedFiles == null || changedFiles.isEmpty()) {
            throw new PipelineExecutionException("No changed files detected");
        }

        // Get the configurable commit prompt template
        ConfigurationManager config = ConfigurationManager.getInstance(gitContext.getProject());
        String template = config.getCommitPromptTemplate();
        
        // Build files list
        StringBuilder filesList = new StringBuilder();
        
        // Group files by status if selectedFiles is available
        if (selectedFiles != null && !selectedFiles.isEmpty()) {
            // Group files by status
            java.util.Map<String, java.util.List<String>> filesByStatus = new java.util.HashMap<>();
            filesByStatus.put("M", new ArrayList<>());
            filesByStatus.put("A", new ArrayList<>());
            filesByStatus.put("D", new ArrayList<>());
            filesByStatus.put("R", new ArrayList<>());
            filesByStatus.put("C", new ArrayList<>());
            filesByStatus.put("U", new ArrayList<>());
            
            for (GitCommitContext.SelectedFile file : selectedFiles) {
                String status = file.getStatus();
                if (filesByStatus.containsKey(status)) {
                    filesByStatus.get(status).add(file.getPath());
                } else {
                    filesByStatus.get("U").add(file.getPath());
                }
            }
            
            java.util.Map<String, String> statusMap = new java.util.HashMap<>();
            statusMap.put("M", "Modified");
            statusMap.put("A", "Added");
            statusMap.put("D", "Deleted");
            statusMap.put("R", "Renamed");
            statusMap.put("C", "Copied");
            statusMap.put("U", "Other");
            
            // Output files grouped by status
            for (java.util.Map.Entry<String, java.util.List<String>> entry : filesByStatus.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    filesList.append("\n### ").append(statusMap.get(entry.getKey())).append(" files:\n");
                    for (String path : entry.getValue()) {
                        filesList.append("- ").append(path).append("\n");
                    }
                }
            }
        } else {
            // Use raw changed files
            filesList.append(changedFiles);
        }
        
        // Build diffs section
        StringBuilder diffsSection = new StringBuilder();
        
        // If we have selected files with individual diffs
        if (selectedFiles != null && !selectedFiles.isEmpty() && gitDiff != null && !gitDiff.isEmpty()) {
            // Simple approach - just format the entire diff
            String[] lines = gitDiff.split("\n");
            int lineCount = Math.min(lines.length, 75);
            
            for (int i = 0; i < lineCount; i++) {
                diffsSection.append(lines[i]).append("\n");
            }
            
            if (lines.length > 75) {
                diffsSection.append("... (diff truncated for brevity)\n");
            }
        } else if (gitDiff != null && !gitDiff.isEmpty()) {
            // Use the full diff if available
            diffsSection.append(gitDiff);
        }
        
        // Replace placeholders in template
        String prompt = template
            .replace("{FILES_LIST}", filesList.toString().trim())
            .replace("{DIFFS}", diffsSection.toString().trim());

        // Store the prompt in the context
        gitContext.setPrompt(prompt);

        LOG.info("Commit message prompt created successfully using configurable template");
    }

    /**
     * Filters changed files to only include selected files
     */
    private String filterChangedFiles(String changedFiles, List<GitCommitContext.SelectedFile> selectedFiles) {
        if (changedFiles == null || selectedFiles == null) return changedFiles;

        StringBuilder filtered = new StringBuilder();
        String[] fileLines = changedFiles.split("\n");

        LOG.info("Filtering changed files. Original lines: " + fileLines.length);
        for (GitCommitContext.SelectedFile selectedFile : selectedFiles) {
            LOG.info("Selected file path: '" + selectedFile.getPath() + "'");
        }

        for (String line : fileLines) {
            if (line.trim().isEmpty()) continue;

            // Check if this line represents a selected file
            for (GitCommitContext.SelectedFile selectedFile : selectedFiles) {
                String originalPath = selectedFile.getPath();

                // Try both original path and various cleaned versions
                if (line.contains(originalPath)) {
                    LOG.info("Matched line with original path: " + line);
                    filtered.append(line).append("\n");
                    break;
                }

                // Try removing project name prefix
                String[] pathParts = originalPath.split("/");
                if (pathParts.length > 1) {
                    String withoutFirstPart = String.join("/", java.util.Arrays.copyOfRange(pathParts, 1, pathParts.length));
                    if (line.contains(withoutFirstPart)) {
                        LOG.info("Matched line with cleaned path: " + line + " (cleaned: " + withoutFirstPart + ")");
                        filtered.append(line).append("\n");
                        break;
                    }
                }
            }
        }

        LOG.info("Filtered result: " + filtered.toString());
        return filtered.toString();
    }

    /**
     * Filters git diff to only include selected files
     */
    private String filterGitDiff(GitCommitContext gitContext, List<GitCommitContext.SelectedFile> selectedFiles) {
        Project project = gitContext.getProject();
        String projectPath = project.getBasePath();

        if (projectPath == null) return gitContext.getGitDiff();

        try {
            // Generate diff for only selected files
            StringBuilder diffCommand = new StringBuilder("git diff");
            for (GitCommitContext.SelectedFile file : selectedFiles) {
                // Clean the file path - remove project name prefix if present
                String cleanPath = cleanFilePath(file.getPath(), project.getName());
                diffCommand.append(" \"").append(cleanPath).append("\"");
            }

            LOG.info("Executing git diff command: " + diffCommand.toString());
            return executeGitCommand(projectPath, diffCommand.toString());
        } catch (Exception e) {
            LOG.warn("Failed to filter git diff, using original", e);
            return gitContext.getGitDiff();
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
     * Executes a git command using the shared utility
     */
    private String executeGitCommand(String workingDir, String command) throws IOException, InterruptedException {
        return GitCommandExecutor.execute(workingDir, command);
    }
}