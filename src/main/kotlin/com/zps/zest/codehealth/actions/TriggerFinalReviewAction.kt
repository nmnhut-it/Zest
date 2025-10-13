package com.zps.zest.codehealth.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.zps.zest.codehealth.ProjectChangesTracker
import com.zps.zest.completion.metrics.ActionMetricsHelper
import com.zps.zest.completion.metrics.FeatureType

/**
 * Test action to trigger the final 13h review and report
 */
class TriggerFinalReviewAction : AnAction() {
    
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        ActionMetricsHelper.trackAction(
            project,
            FeatureType.DAILY_HEALTH_REPORT,
            "Zest.TriggerFinalReview",
            e,
            emptyMap()
        )

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
        
        // Trigger the final review
        tracker.checkAndNotify()
        
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Zest Code Health")
            .createNotification(
                "🚀 Daily Report Started",
                "📊 Analyzing all today's code changes... Report incoming!",
                NotificationType.INFORMATION
            )
            .notify(project)
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null

        if (project != null) {
            val tracker = ProjectChangesTracker.getInstance(project)
            e.presentation.isEnabled = !tracker.isAnalysisRunning.get()
        }
    }
}
