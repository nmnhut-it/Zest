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

        // For now, select all changed files automatically
        // TODO: Implement proper file selection dialog
        List<GitCommitContext.SelectedFile> selectedFiles = new ArrayList<>();
        String[] files = changedFiles.split("\n");
        for (String file : files) {
            if (file.trim().isEmpty()) continue;
            // Parse file status and path (assume format: "M file.txt" or "A file.txt")
            String trimmed = file.trim();
            String status = "M"; // Default status
            String path = trimmed;
            if (trimmed.length() > 2 && trimmed.charAt(1) == ' ') {
                status = trimmed.substring(0, 1);
                path = trimmed.substring(2);
            }
            selectedFiles.add(new GitCommitContext.SelectedFile(path, status));
        }
        context.setSelectedFiles(selectedFiles);
        
        // Continue with commit message generation
        continueWithSelectedFiles(context, false);
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
     * Executes commit pipeline using ChatUIService
     */
    private void executeCommitWithResponse(GitCommitContext context, boolean shouldPush) {
        Project project = context.getProject();
        if (project == null) return;

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Generating Commit Message", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                
                try {
                    indicator.setText("Generating commit message prompt...");
                    indicator.setFraction(0.3);

                    // Generate prompt for selected files directly
                    String prompt = createCommitPrompt(context);

                    if (prompt == null || prompt.isEmpty()) {
                        throw new Exception("Failed to generate commit message prompt");
                    }

                    indicator.setText("Opening commit message dialog...");
                    indicator.setFraction(0.6);

                    // Send to chat using ChatUIService
                    boolean success = sendPromptToChatBox(project, prompt);
                    if (!success) {
                        throw new Exception("Failed to send commit message request to chat");
                    }

                    indicator.setText("Commit message dialog opened successfully!");
                    indicator.setFraction(1.0);
                    
                } catch (Exception ex) {
                    LOG.error("Failed to generate commit message", ex);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        Messages.showErrorDialog(project, 
                            "Error: " + ex.getMessage(), 
                            "Commit Message Generation Failed");
                    });
                }
            }
        });
    }

    /**
     * Creates commit message prompt from selected files
     */
    private String createCommitPrompt(GitCommitContext context) {
        StringBuilder promptBuilder = new StringBuilder();
        
        // Add header with clear instructions
        promptBuilder.append("# GIT COMMIT MESSAGE REQUEST\n\n");
        promptBuilder.append("Please generate a commit message for the following changed files:\n\n");
        
        // Include file list
        promptBuilder.append("## Changed Files\n\n");
        List<GitCommitContext.SelectedFile> selectedFiles = context.getSelectedFiles();
        if (selectedFiles != null && !selectedFiles.isEmpty()) {
            for (GitCommitContext.SelectedFile file : selectedFiles) {
                promptBuilder.append("- ").append(file.getPath()).append("\n");
            }
        }
        promptBuilder.append("\n");
        
        // Request specific format
        promptBuilder.append("## Requested Format\n\n");
        promptBuilder.append("Please provide a well-structured commit message following Git conventions:\n");
        promptBuilder.append("- **Subject line**: Concise summary (≤50 characters preferred)\n");
        promptBuilder.append("- **Body**: Detailed explanation if needed (wrap at 72 characters)\n\n");
        promptBuilder.append("## Guidelines\n\n");
        promptBuilder.append("1. Use imperative mood in subject line (\"Add\", \"Fix\", \"Update\")\n");
        promptBuilder.append("2. Separate subject and body with blank line\n");
        promptBuilder.append("3. Explain what and why, not how\n");
        promptBuilder.append("4. Reference issue numbers if applicable\n\n");
        
        return promptBuilder.toString();
    }

    /**
     * Sends the commit message prompt to the chat dialog.
     */
    private boolean sendPromptToChatBox(Project project, String prompt) {
        LOG.info("Sending commit message prompt to chat dialog");

        try {
            ApplicationManager.getApplication().invokeLater(() -> {
                // Get the ChatUIService
                com.zps.zest.chatui.ChatUIService chatService = project.getService(com.zps.zest.chatui.ChatUIService.class);
                
                // Prepare for commit message generation (adds system prompt if needed)
                chatService.prepareForCommitMessage();
                
                // Open chat with the commit message prompt and auto-send it
                chatService.openChatWithMessage(prompt, true);
                
                // Show success notification
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup("Zest LLM")
                    .createNotification(
                        "Commit Message Generation Started",
                        "Commit message request has been sent to the AI chat.",
                        com.intellij.notification.NotificationType.INFORMATION
                    )
                    .notify(project);
                
                LOG.info("Commit message prompt sent to chat dialog successfully");
            });
            
            return true;
            
        } catch (Exception e) {
            LOG.error("Failed to open chat dialog", e);
            return false;
        }
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