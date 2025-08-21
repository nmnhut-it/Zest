package com.zps.zest.codehealth

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType

/**
 * Debug action for testing code health
 */
class DebugHealthAction : AnAction("🧪 Test Mode / Chế Độ Thử Nghiệm") {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tracker = ProjectChangesTracker.getInstance(project)
        
        // Clear all tracked methods
        tracker.clearAllTrackedMethods()
        
        // Add a test method
        tracker.addTestMethod()
        
        // Show notification
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Zest Code Health")
            .createNotification(
                "🧪 Test Mode Activated",
                "✅ Test method added. Click 'Code Health Check' to analyze!",
                NotificationType.INFORMATION
            )
            .notify(project)
        
        println("[DebugHealthAction] Debug action complete")
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
