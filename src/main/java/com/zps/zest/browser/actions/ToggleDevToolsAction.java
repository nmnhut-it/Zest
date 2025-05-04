package com.zps.zest.browser.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.zps.zest.browser.WebBrowserService;
import org.jetbrains.annotations.NotNull;

/**
 * Action to toggle the visibility of developer tools in the integrated browser.
 * Based on the official IntelliJ Platform documentation for JCEF.
 */
public class ToggleDevToolsAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(ToggleDevToolsAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        LOG.info("Toggling developer tools");

        // Get browser service
        WebBrowserService browserService = WebBrowserService.getInstance(project);
        
        // Toggle dev tools via the browser panel
        if (browserService.getBrowserPanel() != null) {
            boolean isVisible = browserService.getBrowserPanel().toggleDevTools();
            LOG.info("Developer tools visibility: " + isVisible);
            
            // Update action presentation
            e.getPresentation().setText(isVisible ? "Hide ZPS Chat Developer Tools" : "Show ZPS Chat Developer Tools");
        } else {
            LOG.warn("Cannot toggle developer tools: Browser panel not available");
        }

        // Activate browser tool window
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("ZPS Chat");
        if (toolWindow != null) {
            toolWindow.activate(null);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabled(project != null);
        
        // Update text based on current dev tools state
        if (project != null) {
            WebBrowserService browserService = WebBrowserService.getInstance(project);
//            if (browserService.getBrowserPanel() != null && browserService.getBrowserPanel().isDevToolsVisible()) {
//                e.getPresentation().setText("Hide ZPS Chat Developer Tools");
//            } else {
//                e.getPresentation().setText("Show ZPS Chat Developer Tools");
//            }
        }
    }
}
