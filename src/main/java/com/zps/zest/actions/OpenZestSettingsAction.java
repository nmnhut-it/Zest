package com.zps.zest.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Action to quickly open Zest Plugin settings.
 */
public class OpenZestSettingsAction extends AnAction {
    
    public OpenZestSettingsAction() {
        super("Zest Plugin Settings", "Open Zest Plugin configuration settings", null);
    }
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        
        // Open the settings dialog directly to Zest Plugin page
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "Zest Plugin");
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null);
    }
}
