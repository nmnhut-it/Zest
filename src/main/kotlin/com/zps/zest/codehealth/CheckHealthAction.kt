package com.zps.zest.codehealth

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType

/**
 * Manual trigger action for code health check
 */
class CheckHealthAction : AnAction("Check Code Health Now") {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        val tracker = CodeHealthTracker.getInstance(project)
        
        // Check if analysis is already running
        if (tracker.isAnalysisRunning.get()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Zest Code Health")
                .createNotification(
                    "Code Health Analysis In Progress",
                    "Another analysis is already running. Please wait for it to complete.",
                    NotificationType.WARNING
                )
                .notify(project)
            return
        }
        
        // Trigger immediate health check
        tracker.checkAndNotify()
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
        
        // Disable if analysis is running
        if (project != null) {
            val tracker = CodeHealthTracker.getInstance(project)
            e.presentation.isEnabled = !tracker.isAnalysisRunning.get()
            if (tracker.isAnalysisRunning.get()) {
                e.presentation.text = "Code Health Analysis Running..."
            } else {
                e.presentation.text = "Check Code Health Now"
            }
        }
    }
}
