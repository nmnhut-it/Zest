package com.zps.zest.testgen.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.zps.zest.testgen.snapshot.ui.SnapshotManagerDialog;
import org.jetbrains.annotations.NotNull;

/**
 * Action to open the Snapshot Manager Dialog for viewing, resuming, and managing test generation snapshots.
 */
public class OpenSnapshotManagerAction extends AnAction {

    public OpenSnapshotManagerAction() {
        super("Manage Test Generation Snapshots",
              "Open the Snapshot Manager to view, resume, and manage saved test generation checkpoints",
              null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        // Create and show the snapshot manager dialog
        SnapshotManagerDialog dialog = new SnapshotManagerDialog(project);
        dialog.show();
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // Enable the action only when a project is open
        Project project = e.getProject();
        e.getPresentation().setEnabled(project != null);
    }
}
