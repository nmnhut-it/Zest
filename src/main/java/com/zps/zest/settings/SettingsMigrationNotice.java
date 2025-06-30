package com.zps.zest.settings;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.ide.util.PropertiesComponent;
import org.jetbrains.annotations.NotNull;

import java.io.File;

/**
 * Shows a one-time notification about settings migration.
 */
public class SettingsMigrationNotice implements StartupActivity {
    private static final String MIGRATION_NOTICE_SHOWN = "zest.plugin.migration.notice.shown";
    
    @Override
    public void runActivity(@NotNull Project project) {
        PropertiesComponent properties = PropertiesComponent.getInstance(project);
        
        // Check if we've already shown the notice
        if (properties.getBoolean(MIGRATION_NOTICE_SHOWN, false)) {
            return;
        }
        
        // Check if legacy properties files exist
        File legacyFile1 = new File(project.getBasePath(), "ollama-plugin.properties");
        File legacyFile2 = new File(project.getBasePath(), "zest-plugin.properties");
        
        if (legacyFile1.exists() || legacyFile2.exists()) {
            showMigrationNotification(project);
            properties.setValue(MIGRATION_NOTICE_SHOWN, true);
        }
    }
    
    private void showMigrationNotification(Project project) {
        Notification notification = new Notification(
            "Zest Plugin",
            "Zest Plugin Settings Update",
            "Zest Plugin now uses IDE settings instead of properties files. " +
            "Your settings have been automatically migrated. " +
            "You can now safely delete the old properties files from your project root.",
            NotificationType.INFORMATION
        );
        
        // Add action to open settings
        notification.addAction(new AnAction("Open Settings") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "Zest Plugin");
            }
        });
        
        // Add action to delete old files
        notification.addAction(new AnAction("Delete Old Files") {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                deleteOldPropertiesFiles(project);
                notification.expire();
            }
        });
        
        Notifications.Bus.notify(notification, project);
    }
    
    private void deleteOldPropertiesFiles(Project project) {
        File legacyFile1 = new File(project.getBasePath(), "ollama-plugin.properties");
        File legacyFile2 = new File(project.getBasePath(), "zest-plugin.properties");
        
        boolean deleted = false;
        if (legacyFile1.exists() && legacyFile1.delete()) {
            deleted = true;
        }
        if (legacyFile2.exists() && legacyFile2.delete()) {
            deleted = true;
        }
        
        if (deleted) {
            Notification success = new Notification(
                "Zest Plugin",
                "Files Deleted",
                "Old properties files have been deleted successfully.",
                NotificationType.INFORMATION
            );
            Notifications.Bus.notify(success, project);
        }
    }
}
