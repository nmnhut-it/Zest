package com.zps.zest.codehealth

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType

/**
 * Manual trigger action for code health check
 */
class CheckHealthAction : AnAction() {
    init {
        templatePresentation.text = "🏥 Code Health Check / Kiểm Tra Sức Khỏe Code"
        templatePresentation.description = "AI analysis for bugs, performance & security / Phân tích AI tìm lỗi, hiệu năng & bảo mật"
    }
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        val tracker = ProjectChangesTracker.getInstance(project)
        
        // Check if analysis is already running
        if (tracker.isAnalysisRunning.get()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Zest Code Health")
                .createNotification(
                    "⚡ Code Health Check Running",
                    "🔍 Analysis in progress... Results coming soon!",
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
        
        // Set tooltip
        e.presentation.description = "AI-powered code analysis to find bugs, performance issues, and security risks"
        
        // Disable if analysis is running
        if (project != null) {
            val tracker = ProjectChangesTracker.getInstance(project)
            e.presentation.isEnabled = !tracker.isAnalysisRunning.get()
            if (tracker.isAnalysisRunning.get()) {
                e.presentation.text = "🔄 Code Health Analyzing..."
            } else {
                e.presentation.text = "🏥 Code Health Check / Kiểm Tra Sức Khỏe Code"
            }
        }
    }
}
