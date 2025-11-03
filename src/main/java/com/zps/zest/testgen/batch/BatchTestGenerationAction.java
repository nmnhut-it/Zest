package com.zps.zest.testgen.batch;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Action to open the Batch Test Generation dialog.
 * Allows running test generation on multiple files for evaluation purposes.
 */
public class BatchTestGenerationAction extends AnAction {

    public BatchTestGenerationAction() {
        super("Batch Test Generation (Evaluation Mode)",
              "Generate tests for multiple files to evaluate feature effectiveness",
              null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        BatchTestGenerationDialog dialog = new BatchTestGenerationDialog(project);
        dialog.show();
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabled(project != null);
    }
}
