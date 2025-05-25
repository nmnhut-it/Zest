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
import com.zps.zest.browser.utils.ChatboxUtilities;
import com.zps.zest.browser.ChatResponseService;
import com.zps.zest.browser.GitService;
 import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Action that generates a commit message based on the current git changes
 * and sends it to the chat box for further refinement.
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
     * Shows file selection modal
     */
    private void showFileSelectionModal(GitCommitContext context) {
        String changedFiles = context.getChangedFiles();
        if (changedFiles == null || changedFiles.trim().isEmpty()) {
            Messages.showInfoMessage(context.getProject(), "No changed files found", "Git Commit");
            return;
        }

        // Register context with GitService for callback handling
        GitService gitService = new GitService(context.getProject());
        gitService.registerContext(context);

        // Show modal via JavaScript
        WebBrowserService.getInstance(context.getProject())
            .executeJavaScript("if (window.showFileSelectionModal) window.showFileSelectionModal('" + 
                escapeJavaScript(changedFiles) + "');");
    }

    /**
     * Continues with selected files (called by GitService)
     */
    public static void continueWithSelectedFiles(GitCommitContext context) {
        // Create a temporary instance to access the pipeline method
        GitCommitMessageGeneratorAction action = new GitCommitMessageGeneratorAction();
        action.executeGitCommitPipeline(context);
    }

    private String escapeJavaScript(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                   .replace("'", "\\'")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r");
    }

    /**
     * Executes the git commit message generator pipeline.
     */
    private void executeGitCommitPipeline(GitCommitContext context) {
        Project project = context.getProject();
        if (project == null) return;

        // Use a background task with progress indicators
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Generating Commit Message", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);

                try {
                    // Create and execute the git commit pipeline
                    GitCommitPipeline pipeline = new GitCommitPipeline()
                            .addStage(new CommitPromptGenerationStage());

                    // Execute each stage with progress updates
                    int totalStages = pipeline.getStageCount();
                    for (int i = 0; i < totalStages; i++) {
                        PipelineStage stage = pipeline.getStage(i);
                        String stageName = stage.getClass().getSimpleName()
                                .replace("Stage", "")
                                .replaceAll("([A-Z])", " $1").trim();

                        indicator.setText("Stage " + (i + 1) + "/" + totalStages + ": " + stageName);
                        indicator.setFraction((double) i / totalStages);

                        // Process the current stage
                        stage.process(context);

                        // Update progress
                        indicator.setFraction((double) (i + 1) / totalStages);
                    }

                    // Get the analysis result from the context
                    String prompt = context.getPrompt();
                    if (prompt == null || prompt.isEmpty()) {
                        throw new PipelineExecutionException("Failed to generate commit message prompt");
                    }

                    indicator.setText("Sending prompt to chat...");
                    indicator.setFraction(0.8);

                    // Send the analysis to the chat box using newChat with the chosen model
                    boolean success = sendPromptToChatBox(project, prompt, DEFAULT_MODEL_NAME);
                    if (!success) {
                        throw new PipelineExecutionException("Failed to send commit prompt to chat box");
                    }

                    indicator.setText("Waiting for LLM response...");
                    indicator.setFraction(0.9);

                    // Wait for chat response and commit
                    waitForResponseAndCommit(context, indicator);

                } catch (PipelineExecutionException e) {
                    showError(project, e);
                } catch (Exception e) {
                    showError(project, new PipelineExecutionException("Unexpected error", e));
                }
            }
        });
    }

    /**
     * Waits for chat response and executes git commit
     */
    private void waitForResponseAndCommit(GitCommitContext context, ProgressIndicator indicator) {
        Project project = context.getProject();
        ChatResponseService responseService = new ChatResponseService(project);
        
        try {
            // Wait for chat response (30 second timeout)
            String response = responseService.waitForChatResponse(30).get();
            
            if (response == null || response.trim().isEmpty()) {
                throw new PipelineExecutionException("No response received from chat");
            }

            indicator.setText("Processing response and committing...");
            
            // Extract commit message from response
            String commitMessage = extractCommitMessage(response);
            
            // Stage selected files and commit
            stageAndCommit(context, commitMessage);
            
            indicator.setText("Commit completed successfully!");
            indicator.setFraction(1.0);
            
        } catch (Exception e) {
            LOG.error("Error waiting for response or committing", e);
            throw new RuntimeException("Failed to complete commit: " + e.getMessage(), e);
        } finally {
            responseService.dispose();
        }
    }

    /**
     * Extracts commit message from LLM response
     */
    private String extractCommitMessage(String response) {
        // Look for commit-short block first
        String shortPattern = "```commit-short";
        int startIdx = response.indexOf(shortPattern);
        if (startIdx != -1) {
            startIdx += shortPattern.length();
            int endIdx = response.indexOf("```", startIdx);
            if (endIdx != -1) {
                return response.substring(startIdx, endIdx).trim();
            }
        }
        
        // Fallback: extract first line if no code block found
        String[] lines = response.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#") && !line.startsWith("```")) {
                return line.length() > 72 ? line.substring(0, 72) : line;
            }
        }
        
        return "Update selected files";
    }

    /**
     * Stages selected files and commits with message
     */
    private void stageAndCommit(GitCommitContext context, String commitMessage) throws IOException, InterruptedException, PipelineExecutionException {
        Project project = context.getProject();
        String projectPath = project.getBasePath();
        
        if (projectPath == null) {
            throw new PipelineExecutionException("Project path not found");
        }

        List<GitCommitContext.SelectedFile> selectedFiles = context.getSelectedFiles();
        if (selectedFiles == null || selectedFiles.isEmpty()) {
            throw new PipelineExecutionException("No files selected for commit");
        }

        // Stage each selected file
        for (GitCommitContext.SelectedFile file : selectedFiles) {
            String command = "git add \"" + file.getPath() + "\"";
            executeGitCommand(projectPath, command);
            LOG.info("Staged file: " + file.getPath());
        }

        // Commit with the extracted message
        String commitCommand = "git commit -m \"" + commitMessage.replace("\"", "\\\"") + "\"";
        String result = executeGitCommand(projectPath, commitCommand);
        
        LOG.info("Commit executed successfully: " + result);
    }

    /**
     * Executes a git command (reused from existing stage)
     */
    private String executeGitCommand(String workingDir, String command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.directory(new java.io.File(workingDir));

        if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
            processBuilder.command("cmd.exe", "/c", command);
        } else {
            processBuilder.command("sh", "-c", command);
        }

        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            StringBuilder error = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    error.append(line).append("\n");
                }
            }
            throw new IOException("Command exited with code " + exitCode + ": " + error.toString());
        }

        return output.toString();
    }

    /**
     * Sends the commit message prompt to the chat box using newChat and activates the browser window.
     * @param modelName the name of the model to use (e.g., "Qwen2.5-Coder-7B")
     */
    private boolean sendPromptToChatBox(Project project, String prompt, String modelName) {
        LOG.info("Sending commit message prompt to chat box using new chat and model: " + modelName);

        // Start a new chat with the desired model and prompt

        ChatboxUtilities.clickNewChatButton(project);
// Use browserService.executeJavaScript() before creating the new chat
        String script = "window.__selected_model_name__ = '" + modelName + "';";
        WebBrowserService.getInstance(project).executeJavaScript(script);
        ChatboxUtilities.sendTextAndSubmit(project, prompt,false, null, false, ChatboxUtilities.EnumUsage.CHAT_GIT_COMMIT_MESSAGE);
// Then call newChat as usual
//        ChatboxUtilities.newChat(project, modelName, prompt);
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
        LOG.info("Collecting git changes");

        if (!(context instanceof GitCommitContext)) {
            throw new PipelineExecutionException("Invalid context type");
        }

        GitCommitContext gitContext = (GitCommitContext) context;
        Project project = gitContext.getProject();

        try {
            // Get the project base path
            String projectPath = project.getBasePath();
            if (projectPath == null) {
                throw new PipelineExecutionException("Project path not found");
            }

            // Get the current branch name
            String branchName = executeGitCommand(projectPath, "git rev-parse --abbrev-ref HEAD");
            gitContext.setBranchName(branchName.trim());

            // Get list of changed files
            String changedFiles = executeGitCommand(projectPath, "git diff --name-status");
            gitContext.setChangedFiles(changedFiles);

            // Get the git diff
            String gitDiff = executeGitCommand(projectPath, "git diff");
            gitContext.setGitDiff(gitDiff);

            LOG.info("Git changes collected successfully");
        } catch (Exception e) {
            LOG.error("Error collecting git changes", e);
            throw new PipelineExecutionException("Failed to collect git changes: " + e.getMessage(), e);
        }
    }

    /**
     * Executes a git command and returns the output.
     */
    private String executeGitCommand(String workingDir, String command) throws IOException, InterruptedException {
        LOG.info("Executing git command: " + command);

        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.directory(new java.io.File(workingDir));

        // Set up the command based on OS
        if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
            processBuilder.command("cmd.exe", "/c", command);
        } else {
            processBuilder.command("sh", "-c", command);
        }

        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();

        // Read the output
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        // Wait for the process to complete
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            // Read error stream if the command failed
            StringBuilder error = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    error.append(line).append("\n");
                }
            }

            throw new IOException("Command exited with code " + exitCode + ": " + error.toString());
        }

        return output.toString();
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

        // Build the prompt for the LLM
        StringBuilder prompt = new StringBuilder();
        prompt.append("# Git Commit Message Generator\n\n");

        // Add instructions for the LLM
        prompt.append("## Instructions\n");
        prompt.append("Generate a well-structured, concise commit message based on the changes below. ");
        prompt.append("Focus on the WHY and WHAT, not just the how.\n\n");

        prompt.append("## Current Branch\n");
        prompt.append("`").append(branchName).append("`\n\n");

        prompt.append("## Changed Files\n");
        prompt.append("```").append("\n").append(changedFiles).append("```\n\n");

        prompt.append("## Git Diff\n");
        prompt.append("```diff\n").append(gitDiff).append("```\n\n");

        prompt.append("## Output Format\n");
        prompt.append("Please format your response with TWO separate code blocks as follows:\n\n");

        prompt.append("### Short Message (for -m flag)\n");
        prompt.append("```commit-short\n");
        prompt.append("<short summary>\n");
        prompt.append("```\n\n");
        prompt.append("The short message **should not exceed 50 characters** and should be a summary of the changes.\n\n");

        prompt.append("### Long Message (for commit template)\n");
        prompt.append("```commit-long\n");
        prompt.append("<type>(<scope>): <short summary>\n\n");
        prompt.append("<longer description explaining the changes in detail>\n\n");
        prompt.append("<any breaking changes or issues closed>\n");
        prompt.append("```\n\n");

        prompt.append("The commit messages should be clear, concise, and follow best practices. ");
        prompt.append("Keep both blocks formatted for easy copying and pasting./no_think");

        // Store the prompt in the context
        gitContext.setPrompt(prompt.toString());

        LOG.info("Commit message prompt created successfully");
    }

    /**
     * Filters changed files to only include selected files
     */
    private String filterChangedFiles(String changedFiles, List<GitCommitContext.SelectedFile> selectedFiles) {
        if (changedFiles == null || selectedFiles == null) return changedFiles;
        
        StringBuilder filtered = new StringBuilder();
        String[] fileLines = changedFiles.split("\n");
        
        for (String line : fileLines) {
            if (line.trim().isEmpty()) continue;
            
            // Check if this line represents a selected file
            for (GitCommitContext.SelectedFile selectedFile : selectedFiles) {
                if (line.contains(selectedFile.getPath())) {
                    filtered.append(line).append("\n");
                    break;
                }
            }
        }
        
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
                diffCommand.append(" \"").append(file.getPath()).append("\"");
            }
            
            return executeGitCommand(projectPath, diffCommand.toString());
        } catch (Exception e) {
            LOG.warn("Failed to filter git diff, using original", e);
            return gitContext.getGitDiff();
        }
    }

    private String executeGitCommand(String workingDir, String command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.directory(new java.io.File(workingDir));

        if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
            processBuilder.command("cmd.exe", "/c", command);
        } else {
            processBuilder.command("sh", "-c", command);
        }

        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            StringBuilder error = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    error.append(line).append("\n");
                }
            }
            throw new IOException("Command exited with code " + exitCode + ": " + error.toString());
        }

        return output.toString();
    }
}
