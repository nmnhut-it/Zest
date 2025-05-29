package com.zps.zest.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.zps.zest.browser.WebBrowserService;
import org.jetbrains.annotations.NotNull;

/**
 * Action to show the visual workflow builder for the Agent Framework
 */
public class ShowWorkflowBuilderAction extends AnAction {
    
    public ShowWorkflowBuilderAction() {
        super("Show Workflow Builder", "Open the visual workflow builder for creating agent workflows", null);
    }
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        
        // Get the browser service
        WebBrowserService browserService = WebBrowserService.getInstance(project);
        
        // Load the workflow builder page
        browserService.loadResource("/html/workflowBuilder.html");
        
        // Show the browser panel
        browserService.showBrowserPanel();
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        // Action is available when a project is open
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null);
    }
}
