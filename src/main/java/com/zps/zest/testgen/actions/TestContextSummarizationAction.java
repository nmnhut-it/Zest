package com.zps.zest.testgen.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.zps.zest.testgen.ui.ContextSummarizationTestDialog;
import org.jetbrains.annotations.NotNull;

/**
 * Action to open the context summarization test dialog.
 * This is for testing and debugging the summarization system.
 */
public class TestContextSummarizationAction extends AnAction {

    public TestContextSummarizationAction() {
        super("Test Context Summarization",
              "Test the context summarization system with real classes",
              null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        ContextSummarizationTestDialog dialog = new ContextSummarizationTestDialog(project);
        dialog.show();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // Only enable if we have a project
        e.getPresentation().setEnabled(e.getProject() != null);
    }
}
