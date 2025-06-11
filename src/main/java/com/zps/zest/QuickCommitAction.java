package com.zps.zest;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.zps.zest.browser.WebBrowserService;
import org.jetbrains.annotations.NotNull;

/**
 * Quick Commit & Push action that can be triggered globally via Ctrl+Shift+Z,C
 */
public class QuickCommitAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(QuickCommitAction.class);
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        
        LOG.info("Quick Commit action triggered");
        
        // Ensure the browser tool window is available
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("ZPS Chat");
        if (toolWindow != null) {
            // Activate the tool window first
            toolWindow.activate(() -> {
                // Execute the quick commit pipeline
                WebBrowserService browserService = WebBrowserService.getInstance(project);
                if (browserService != null && browserService.getBrowserPanel() != null) {
                    browserService.executeJavaScript("if (window.QuickCommitPipeline) { window.QuickCommitPipeline.execute(); }");
                }
            });
        } else {
            LOG.warn("ZPS Chat tool window not found");
            
            // Show notification to help user
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Zest LLM")
                .createNotification(
                    "ZPS Chat Required",
                    "Please open the ZPS Chat tool window first to use Quick Commit",
                    NotificationType.WARNING
                )
                .notify(project);
        }
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        // Enable the action only when a project is open
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null);
        
        // Update the description to show the keyboard shortcut
        if (project != null) {
            e.getPresentation().setDescription("Quick commit and push with auto-generated message (Ctrl+Shift+Z, C)");
        }
    }
}
