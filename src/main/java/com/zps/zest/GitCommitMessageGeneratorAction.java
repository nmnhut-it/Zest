package com.zps.zest;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
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
 * Runs git status collection in background to avoid EDT freezes.
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

        String projectPath = project.getBasePath();
        if (projectPath == null) {
            Messages.showErrorDialog(project, "Project path not found", "Git Commit & Push");
            return;
        }

        new Task.Backgroundable(project, "Collecting Git Status...", true) {
            private String changes = null;
            private Exception error = null;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                indicator.setFraction(0.0);
                indicator.setText("Analyzing repository changes...");

                try {
                    GitStatusCollector statusCollector = new GitStatusCollector(projectPath);
                    indicator.setFraction(0.3);

                    changes = statusCollector.collectAllChanges();
                    indicator.setFraction(1.0);

                } catch (Exception ex) {
                    LOG.error("Error checking git status", ex);
                    error = ex;
                }
            }

            @Override
            public void onSuccess() {
                if (error != null) {
                    Messages.showErrorDialog(project,
                            "Failed to check git status: " + error.getMessage(),
                            "Git Commit & Push");
                    return;
                }

                if (changes == null || changes.trim().isEmpty()) {
                    Messages.showInfoMessage(project,
                            "No git changes found. Please make some changes before committing.",
                            "Git Commit & Push");
                    return;
                }

                ApplicationManager.getApplication().invokeLater(() -> {
                    GitUIDialogService.getInstance().openGitUI(project, true);
                });
            }

            @Override
            public void onCancel() {
                LOG.info("Git status collection cancelled by user");
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                LOG.error("Unexpected error during git status collection", error);
                Messages.showErrorDialog(project,
                        "Unexpected error: " + error.getMessage(),
                        "Git Commit & Push");
            }
        }.queue();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}