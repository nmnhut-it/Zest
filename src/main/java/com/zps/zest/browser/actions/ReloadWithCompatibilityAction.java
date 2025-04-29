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
 * Action to reload the current page with enhanced compatibility fixes.
 */
public class ReloadWithCompatibilityAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(ReloadWithCompatibilityAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }

        LOG.info("Reloading page with compatibility mode");

        WebBrowserService browserService = WebBrowserService.getInstance(project);
        if (browserService == null || browserService.getBrowserPanel() == null) {
            LOG.warn("Cannot reload: Browser service or panel is null");
            return;
        }
        
        // Get current URL
        String currentUrl = browserService.getBrowserPanel().getCurrentUrl();
        if (currentUrl == null || currentUrl.isEmpty()) {
            LOG.warn("Cannot reload: No current URL");
            return;
        }
        
        // Load with compatibility
        browserService.getBrowserPanel().loadURLWithCompatibility(currentUrl);
        
        // Activate browser tool window
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("ZPS Chat");
        if (toolWindow != null) {
            toolWindow.activate(null);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // Enable only when project is available
        Project project = e.getProject();
        e.getPresentation().setEnabled(project != null);
    }
}
