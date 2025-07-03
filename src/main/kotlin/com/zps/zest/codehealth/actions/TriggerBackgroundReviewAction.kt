package com.zps.zest.codehealth.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.zps.zest.codehealth.BackgroundHealthReviewer

/**
 * Test action to trigger background review of all pending methods
 */
class TriggerBackgroundReviewAction : AnAction() {
    
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val reviewer = BackgroundHealthReviewer.getInstance(project)
        
        // Get stats before review
        val statsBefore = reviewer.getQueueStats()
        
        // Trigger review
        reviewer.reviewAllPendingMethods()
        
        // Show notification with stats
        val message = buildString {
            appendLine("Background Review Triggered")
            appendLine("Pending: ${statsBefore.pendingCount} methods")
            appendLine("Ready for review: ${statsBefore.readyForReviewCount} methods")
            appendLine("Already reviewed: ${statsBefore.reviewedCount} methods")
            appendLine("Total issues found: ${statsBefore.totalIssuesFound}")
        }
        
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Zest Code Guardian")
            .createNotification("Background Health Review", message, NotificationType.INFORMATION)
            .notify(project)
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
