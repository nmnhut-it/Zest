package com.zps.zest.explanation.tools.testing;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.explanation.tools.testing.ui.RipgrepTestDialog;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * Action to open the RipgrepCodeTool testing dialog for manual regression testing.
 * This provides a comprehensive UI for testing all features of the RipgrepCodeTool.
 */
public class TestRipgrepToolAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(TestRipgrepToolAction.class);

    public TestRipgrepToolAction() {
        super("Test Ripgrep Tool",
              "Open manual testing dialog for RipgrepCodeTool",
              null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            Messages.showErrorDialog(
                "No project is currently open",
                "Error"
            );
            return;
        }

        LOG.info("Opening RipgrepCodeTool test dialog for project: " + project.getName());

        try {
            // Open the test dialog
            RipgrepTestDialog dialog = new RipgrepTestDialog(project);

            SwingUtilities.invokeLater(() -> {
                dialog.show();

                if (dialog.isOK()) {
                    // Test completed successfully
                    LOG.info("RipgrepCodeTool test completed");
                } else {
                    // Test cancelled
                    LOG.info("RipgrepCodeTool test cancelled");
                }
            });

        } catch (Exception ex) {
            LOG.error("Error opening RipgrepCodeTool test dialog", ex);
            Messages.showErrorDialog(
                project,
                "Failed to open test dialog: " + ex.getMessage(),
                "Error"
            );
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // Action is available when a project is open
        Project project = e.getProject();
        e.getPresentation().setEnabled(project != null);

        // Show in Tools menu
        e.getPresentation().setVisible(true);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}