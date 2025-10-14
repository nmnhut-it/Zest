package com.zps.zest;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.completion.metrics.ActionMetricsHelper;
import com.zps.zest.completion.metrics.FeatureType;
import com.zps.zest.git.GitStatusCollector;
import com.zps.zest.git.GitUIDialogService;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;

/**
 * Action that opens Git UI with auto-selected changes and auto-generated commit message.
 * Provides streamlined workflow for commit and push operations.
 */
public class GitCommitMessageGeneratorAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(GitCommitMessageGeneratorAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            Messages.showWarningDialog((Project)null, "No project available", "Git Commit & Push");
            return;
        }

        ActionMetricsHelper.INSTANCE.trackAction(
                project,
                FeatureType.GIT_COMMIT_AND_PUSH,
                "Zest.GitCommitMessageGeneratorAction",
                e,
                Collections.emptyMap()
        );

        try {
            String projectPath = project.getBasePath();
            if (projectPath == null) {
                Messages.showErrorDialog(project, "Project path not found", "Git Commit & Push");
                return;
            }

            GitStatusCollector statusCollector = new GitStatusCollector(projectPath);
            String changes = statusCollector.collectAllChanges();

            if (changes == null || changes.trim().isEmpty()) {
                Messages.showInfoMessage(project,
                    "No git changes found. Please make some changes before committing.",
                    "Git Commit & Push");
                return;
            }

            GitUIDialogService.getInstance().openGitUI(project, true);

        } catch (Exception ex) {
            LOG.error("Error checking git status", ex);
            Messages.showErrorDialog(project,
                "Failed to check git status: " + ex.getMessage(),
                "Git Commit & Push");
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}