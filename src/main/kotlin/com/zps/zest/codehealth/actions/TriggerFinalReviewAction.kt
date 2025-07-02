package com.zps.zest.codehealth.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.zps.zest.codehealth.CodeHealthTracker

/**
 * Test action to trigger the final 13h review and report
 */
class TriggerFinalReviewAction : AnAction("Trigger Final Health Review (13h)") {
    
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tracker = CodeHealthTracker.getInstance(project)
        
        // Check if analysis is already running
        if (tracker.isAnalysisRunning.get()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Zest Code Health")
                .createNotification(
                    "Analysis Already Running",
                    "Please wait for the current analysis to complete.",
                    NotificationType.WARNING
                )
                .notify(project)
            return
        }
        
        // Trigger the final review
        tracker.checkAndNotify()
        
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Zest Code Health")
            .createNotification(
                "Final Review Triggered",
                "The daily health check has been triggered. Results will appear shortly.",
                NotificationType.INFORMATION
            )
            .notify(project)
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
        
        if (project != null) {
            val tracker = CodeHealthTracker.getInstance(project)
            e.presentation.isEnabled = !tracker.isAnalysisRunning.get()
            e.presentation.text = if (tracker.isAnalysisRunning.get()) {
                "Final Review Running..."
            } else {
                "Trigger Final Health Review (13h)"
            }
        }
    }
}
