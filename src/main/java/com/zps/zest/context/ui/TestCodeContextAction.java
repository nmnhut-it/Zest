package com.zps.zest.context.ui;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Action to open CodeContextAgent test dialog.
 */
public class TestCodeContextAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(TestCodeContextAction.class);

    public TestCodeContextAction() {
        super("Test Code Context Agent",
              "Open test dialog for Code Context Agent exploration",
              null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            Messages.showErrorDialog("No project is currently open", "Error");
            return;
        }

        LOG.info("Opening CodeContextAgent test dialog for project: " + project.getName());

        try {
            CodeContextTestDialog dialog = new CodeContextTestDialog(project);

            SwingUtilities.invokeLater(() -> {
                dialog.show();

                if (dialog.isOK()) {
                    LOG.info("CodeContextAgent test completed");
                } else {
                    LOG.info("CodeContextAgent test cancelled");
                }
            });

        } catch (Exception ex) {
            LOG.error("Error opening CodeContextAgent test dialog", ex);
            Messages.showErrorDialog(
                project,
                "Failed to open test dialog: " + ex.getMessage(),
                "Error"
            );
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabled(project != null);
        e.getPresentation().setVisible(true);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}