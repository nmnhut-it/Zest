package com.zps.zest.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.zps.zest.browser.WebBrowserService;
import com.zps.zest.browser.WebBrowserPanel;
import org.jetbrains.annotations.NotNull;

/**
 * Action to test JCEF resource loading functionality
 */
public class TestResourceLoadingAction extends AnAction {
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        
        // Get the web browser service
        WebBrowserService browserService = WebBrowserService.getInstance(project);
        WebBrowserPanel browserPanel = browserService.getBrowserPanel();
        
        if (browserPanel != null && browserPanel.getBrowserManager() != null) {
            // Switch to Agent mode
            browserPanel.switchToAgentMode();
            
            // Load the resource test page
            browserPanel.getBrowserManager().loadResourceTest();
            
            // Open the tool window
            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("ZPS Chat");
            if (toolWindow != null) {
                toolWindow.show();
            }
        }
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        // Only enable if a project is open
        e.getPresentation().setEnabled(e.getProject() != null);
    }
}
