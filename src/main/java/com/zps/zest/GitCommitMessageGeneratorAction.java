package com.zps.zest;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.browser.actions.BaseChatAction;
import com.zps.zest.git.GitStatusCollector;
import org.jetbrains.annotations.NotNull;

/**
 * Action that shows a git commit dialog for selecting files and entering commit messages.
 * The actual commit message generation is handled via LLM integration.
 */
public class GitCommitMessageGeneratorAction extends BaseChatAction {


    @Override
    protected boolean isActionAvailable(@NotNull AnActionEvent e) {
        return e.getProject() != null;
    }

    @Override
    protected void showUnavailableMessage(Project project) {
        Messages.showWarningDialog(project, "No project available", "Git Commit");
    }

    @Override
    protected String getTaskTitle() {
        return "Generating Commit Message";
    }

    @Override
    protected String getErrorTitle() {
        return "Commit Message Generation Failed";
    }

    @Override
    protected String getChatPreparationMethod() {
        return "prepareForCommitMessage";
    }

    @Override
    protected String getNotificationTitle() {
        return "Commit Message Generation Started";
    }

    @Override
    protected String getNotificationMessage() {
        return "Commit message request has been sent to the AI chat.";
    }

    @Override
    protected String createPrompt(@NotNull AnActionEvent e) throws Exception {
        Project project = e.getProject();
        if (project == null) throw new Exception("No project available");
        
        String projectPath = project.getBasePath();
        if (projectPath == null) throw new Exception("Project path not found");
        
        GitStatusCollector statusCollector = new GitStatusCollector(projectPath);
        String changedFiles = statusCollector.collectAllChanges();
        
        if (changedFiles == null || changedFiles.trim().isEmpty()) {
            throw new Exception("No changed files found");
        }
        
        return createCommitPrompt(changedFiles);
    }


    private String createCommitPrompt(String changedFiles) {
        return "Generate a Git commit message for these changes:\n\n" +
               changedFiles + "\n\n" +
               "Style: Professional, clear, conventional format\n" +
               "Requirements:\n" +
               "- Use conventional commits (feat:, fix:, docs:, refactor:, test:)\n" +
               "- Summary ≤50 chars, imperative mood\n" +
               "- Explain what and why (not how)\n" +
               "- Add body with details if significant changes";
    }

}