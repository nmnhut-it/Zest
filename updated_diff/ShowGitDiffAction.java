package com.zps.zest.diff;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Action to show the Git Diff Viewer dialog
 */
public class ShowGitDiffAction extends AnAction {
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project != null) {
            SimpleGitDiffViewer.showProjectChanges(project);
        }
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        // Enable the action only if a project is open
        Project project = e.getProject();
        e.getPresentation().setEnabled(project != null);
    }
}
