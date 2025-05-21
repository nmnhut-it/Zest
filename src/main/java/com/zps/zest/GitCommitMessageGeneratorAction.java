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
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.zps.zest.browser.utils.ChatboxUtilities;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Action that generates a commit message based on the current git changes
 * and sends it to the chat box for further refinement.
 */
public class GitCommitMessageGeneratorAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(GitCommitMessageGeneratorAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        // Create the context to maintain state
        GitCommitContext context = new GitCommitContext();
        context.setEvent(e);
        context.setProject(e.getProject());

        // Execute the pipeline
        executeGitCommitPipeline(context);
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
                            .addStage(new GitChangesCollectionStage())
                            .addStage(new CommitPromptGenerationStage());

                    // Execute each stage with progress updates
                    int totalStages = pipeline.getStageCount();
                    for (int i = 0; i < totalStages; i++) {
                        PipelineStage stage = pipeline.getStage(i);
                        String stageName = stage.getClass().getSimpleName()
                                .replace("Stage", "")
                                .replaceAll("([A-Z])", " $1").trim();

                        indicator.setText("Stage " + (i+1) + "/" + totalStages + ": " + stageName);
                        indicator.setFraction((double) i / totalStages);

                        // Process the current stage
                        stage.process(context);

                        // Update progress
                        indicator.setFraction((double) (i+1) / totalStages);
                    }

                    // Get the analysis result from the context
                    String prompt = context.getPrompt();
                    if (prompt == null || prompt.isEmpty()) {
                        throw new PipelineExecutionException("Failed to generate commit message prompt");
                    }

                    // Send the analysis to the chat box
                    boolean success = sendPromptToChatBox(project, prompt);
                    if (!success) {
                        throw new PipelineExecutionException("Failed to send commit prompt to chat box");
                    }

                    indicator.setText("Commit message prompt sent to chat box successfully!");
                    indicator.setFraction(1.0);

                } catch (PipelineExecutionException e) {
                    showError(project, e);
                } catch (Exception e) {
                    showError(project, new PipelineExecutionException("Unexpected error", e));
                }
            }
        });
    }

    /**
     * Sends the commit message prompt to the chat box and activates the browser window.
     */
    private boolean sendPromptToChatBox(Project project, String prompt) {
        LOG.info("Sending commit message prompt to chat box");

        // Send the prompt to the chat box
        ChatboxUtilities.newChat(project, "Qwen2.5-Coder-7B");
        boolean success = ChatboxUtilities.sendTextAndSubmit(project, prompt, false, ConfigurationManager.getInstance(project).getCodeSystemPrompt(), false);

        // Activate browser tool window on EDT
        ApplicationManager.getApplication().invokeLater(() -> {
            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("ZPS Chat");
            if (toolWindow != null) {
                toolWindow.activate(null);
            }
        });

        return success;
    }

    /**
     * Shows an error message on the UI thread.
     */
    private void showError(Project project, PipelineExecutionException e) {
        e.printStackTrace();
        LOG.error("Error in GitCommitMessageGeneratorAction: " + e.getMessage(), e);

        ApplicationManager.getApplication().invokeLater(()->{
            Messages.showErrorDialog(project, "Error: " + e.getMessage(), "Commit Message Generation Failed");
        });
    }
}

/**
 * Context class for git commit message generation.
 */
class GitCommitContext extends CodeContext {
    private String gitDiff;
    private String changedFiles;
    private String branchName;

    public String getGitDiff() {
        return gitDiff;
    }

    public void setGitDiff(String gitDiff) {
        this.gitDiff = gitDiff;
    }

    public String getChangedFiles() {
        return changedFiles;
    }

    public void setChangedFiles(String changedFiles) {
        this.changedFiles = changedFiles;
    }

    public String getBranchName() {
        return branchName;
    }

    public void setBranchName(String branchName) {
        this.branchName = branchName;
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
        prompt.append("```\n").append(changedFiles).append("```\n\n");

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
}