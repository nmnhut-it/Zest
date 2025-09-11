package com.zps.zest.browser.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

/**
 * Base class for actions that send prompts to the chat dialog.
 */
public abstract class BaseChatAction extends AnAction {
    protected static final Logger LOG = Logger.getInstance(BaseChatAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        if (!isActionAvailable(e)) {
            showUnavailableMessage(project);
            return;
        }

        // Execute in background task
        ProgressManager.getInstance().run(new Task.Backgroundable(project, getTaskTitle(), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                
                try {
                    indicator.setText("Analyzing...");
                    indicator.setFraction(0.3);
                    
                    String prompt = createPrompt(e);
                    if (prompt == null || prompt.isEmpty()) {
                        throw new Exception("Failed to generate prompt");
                    }
                    
                    indicator.setText("Opening chat...");
                    indicator.setFraction(0.8);
                    
                    boolean success = sendToChat(project, prompt);
                    if (!success) {
                        throw new Exception("Failed to send to chat");
                    }
                    
                    indicator.setText("Complete!");
                    indicator.setFraction(1.0);
                    
                } catch (Exception ex) {
                    LOG.error("Failed to execute chat action", ex);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        Messages.showErrorDialog(project, 
                            "Error: " + ex.getMessage(), 
                            getErrorTitle());
                    });
                }
            }
        });
    }

    /**
     * Check if the action is available in the current context
     */
    protected abstract boolean isActionAvailable(@NotNull AnActionEvent e);

    /**
     * Show message when action is not available
     */
    protected abstract void showUnavailableMessage(Project project);

    /**
     * Get the title for the background task
     */
    protected abstract String getTaskTitle();

    /**
     * Get the title for error dialogs
     */
    protected abstract String getErrorTitle();

    /**
     * Create the prompt to send to chat
     */
    protected abstract String createPrompt(@NotNull AnActionEvent e) throws Exception;

    /**
     * Get the chat preparation method name
     */
    protected abstract String getChatPreparationMethod();

    /**
     * Get the notification title
     */
    protected abstract String getNotificationTitle();

    /**
     * Get the notification message
     */
    protected abstract String getNotificationMessage();

    /**
     * Send prompt to chat dialog
     */
    protected boolean sendToChat(Project project, String prompt) {
        LOG.info("Sending prompt to chat dialog");

        try {
            ApplicationManager.getApplication().invokeLater(() -> {
                com.zps.zest.chatui.ChatUIService chatService = project.getService(com.zps.zest.chatui.ChatUIService.class);
                
                // Clear previous conversation to start fresh
                chatService.clearConversation();
                
                // Call the appropriate preparation method
                String prepMethod = getChatPreparationMethod();
                if ("prepareForCodeReview".equals(prepMethod)) {
                    chatService.prepareForCodeReview();
                } else if ("prepareForCommitMessage".equals(prepMethod)) {
                    chatService.prepareForCommitMessage();
                }
                
                chatService.openChatWithMessage(prompt, true);
                
                // Show notification
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup("Zest LLM")
                    .createNotification(
                        getNotificationTitle(),
                        getNotificationMessage(),
                        com.intellij.notification.NotificationType.INFORMATION
                    )
                    .notify(project);
                
                LOG.info("Prompt sent to chat dialog successfully");
            });
            
            return true;
            
        } catch (Exception e) {
            LOG.error("Failed to open chat dialog", e);
            return false;
        }
    }
}