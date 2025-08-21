package com.zps.zest.git;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.zps.zest.browser.WebBrowserService;

/**
 * Helper class for Git UI notifications and status messages.
 * Centralizes all UI notification logic to avoid duplication.
 */
public class GitUINotificationHelper {
    private static final Logger LOG = Logger.getInstance(GitUINotificationHelper.class);
    
    /**
     * Shows a status message in the browser tool window.
     */
    public static void showStatusMessage(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                String script = String.format("if (window.showStatusMessage) { window.showStatusMessage('%s'); }",
                        GitServiceHelper.escapeJsString(message));
                WebBrowserService.getInstance(project).executeJavaScript(script);
                LOG.info("Sent status message to tool window: " + message);
            } catch (Exception e) {
                LOG.warn("Failed to show tool window message: " + message, e);
            }
        });
    }
    
    /**
     * Executes a JavaScript function in the browser UI.
     */
    public static void notifyUI(Project project, String jsFunction) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                WebBrowserService.getInstance(project).executeJavaScript(jsFunction);
                LOG.info("Sent UI notification: " + jsFunction);
            } catch (Exception e) {
                LOG.warn("Failed to notify UI: " + jsFunction, e);
            }
        });
    }
    
    /**
     * Shows commit in progress notification.
     */
    public static void showCommitInProgress(Project project) {
        showStatusMessage(project, "Committing changes...");
        notifyUI(project, "GitUI.showCommitInProgress()");
    }
    
    /**
     * Shows commit success notification.
     */
    public static void showCommitSuccess(Project project) {
        showStatusMessage(project, "Commit completed successfully!");
        notifyUI(project, "GitUI.showCommitSuccess()");
    }
    
    /**
     * Shows commit error notification.
     */
    public static void showCommitError(Project project, String errorMsg) {
        showStatusMessage(project, "Commit failed: " + errorMsg);
        notifyUI(project, "GitUI.showCommitError('" + GitServiceHelper.escapeJsString(errorMsg) + "')");
    }
    
    /**
     * Shows push in progress notification.
     */
    public static void showPushInProgress(Project project) {
        showStatusMessage(project, "Pushing changes to remote...");
        notifyUI(project, "GitUI.showPushInProgress()");
    }
    
    /**
     * Shows push success notification.
     */
    public static void showPushSuccess(Project project) {
        showStatusMessage(project, "Push completed successfully!");
        notifyUI(project, "GitUI.showPushSuccess()");
    }
    
    /**
     * Shows push error notification.
     */
    public static void showPushError(Project project, String errorMsg) {
        showStatusMessage(project, "Push failed: " + errorMsg);
        notifyUI(project, "GitUI.showPushError('" + GitServiceHelper.escapeJsString(errorMsg) + "')");
    }
    
    /**
     * Closes the Git modal if it's open.
     */
    public static void closeGitModal(Project project) {
        notifyUI(project, "if (window.GitModal && window.GitModal.hideModal) { window.GitModal.hideModal(); }");
    }
    
    /**
     * Shows a notification balloon with the specified title, content, and type.
     */
    public static void showNotification(Project project, String title, String content, NotificationType type) {
        ApplicationManager.getApplication().invokeLater(() -> {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Zest Code Health")
                .createNotification(title, content, type)
                .notify(project);
        });
    }
}