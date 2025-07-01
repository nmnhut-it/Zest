package com.zps.zest.codehealth

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType

/**
 * Debug action for testing code health
 */
class DebugHealthAction : AnAction("Debug Code Health") {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tracker = CodeHealthTracker.getInstance(project)
        
        // Clear all tracked methods
        tracker.clearAllTrackedMethods()
        
        // Add a test method
        tracker.addTestMethod()
        
        // Show notification
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Zest Code Health")
            .createNotification(
                "Debug Action Complete",
                "Cleared all methods and added test method. Try running analysis now.",
                NotificationType.INFORMATION
            )
            .notify(project)
        
        println("[DebugHealthAction] Debug action complete")
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
