package com.zps.zest.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.zps.zest.browser.WebBrowserService;
import org.jetbrains.annotations.NotNull;

/**
 * Action to show the Agent Framework demo in the JCEF browser
 */
public class ShowAgentFrameworkAction extends AnAction {
    
    public ShowAgentFrameworkAction() {
        super("Show Agent Framework", "Open the Agent Framework demo page", null);
    }
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        
        // Get the browser service
        WebBrowserService browserService = WebBrowserService.getInstance(project);
        
        // Ensure browser panel is visible

        
        // Load the agent demo
        if (browserService.getBrowserPanel() != null && 
            browserService.getBrowserPanel().getBrowserManager() != null) {
            browserService.getBrowserPanel().getBrowserManager().loadAgentDemo();
        }
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        // Enable only when a project is open
        e.getPresentation().setEnabled(e.getProject() != null);
    }
}
