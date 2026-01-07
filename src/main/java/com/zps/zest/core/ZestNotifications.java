package com.zps.zest.core;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NlsContexts;
import org.jetbrains.annotations.NotNull;

/**
 * Helper class for managing notifications in the Zest plugin.
 * Uses the modern notification system introduced in IntelliJ IDEA 2020.2+.
 */
public class ZestNotifications {
    // Notification group ID - must match the one defined in plugin.xml
    public static final String NOTIFICATION_GROUP_ID = "Zest LLM";

    /**
     * Shows a notification in the specified project.
     */
    public static void showNotification(
            @NotNull Project project,
            @NlsContexts.NotificationTitle String title,
            @NlsContexts.NotificationContent String content,
            @NotNull NotificationType type) {

        ApplicationManager.getApplication().invokeLater(() -> {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(title, content, type)
                    .notify(project);
        });
    }

    /** Shows an information notification. */
    public static void showInfo(@NotNull Project project, String title, String content) {
        showNotification(project, title, content, NotificationType.INFORMATION);
    }

    /** Shows a warning notification. */
    public static void showWarning(@NotNull Project project, String title, String content) {
        showNotification(project, title, content, NotificationType.WARNING);
    }

    /** Shows an error notification. */
    public static void showError(@NotNull Project project, String title, String content) {
        showNotification(project, title, content, NotificationType.ERROR);
    }
}
