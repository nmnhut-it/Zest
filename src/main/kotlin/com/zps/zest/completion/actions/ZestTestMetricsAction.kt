package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.zps.zest.completion.metrics.ZestInlineCompletionMetricsService
import java.util.UUID

/**
 * Test action to verify metrics service is working
 * For development/debugging only
 */
class ZestTestMetricsAction : AnAction("Test Inline Completion Metrics") {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val metricsService = ZestInlineCompletionMetricsService.getInstance(project)
        
        // Generate a test completion ID
        val completionId = UUID.randomUUID().toString()
        
        // Simulate a complete lifecycle
        Messages.showInfoMessage(project, "Sending test metrics...", "Metrics Test")
        
        // Track completion requested
        metricsService.trackCompletionRequested(
            completionId = completionId,
            strategy = "TEST_STRATEGY",
            fileType = "kotlin",
            contextInfo = mapOf(
                "test" to true,
                "source" to "manual_test"
            )
        )
        
        // Simulate some delay
        Thread.sleep(100)
        
        // Track completion viewed
        metricsService.trackCompletionViewed(
            completionId = completionId,
            completionLength = 42,
            confidence = 0.85f
        )
        
        // Simulate more delay
        Thread.sleep(200)
        
        // Track completion accepted
        metricsService.trackCompletionAccepted(
            completionId = completionId,
            completionContent = "test completion content",
            acceptType = "full",
            userAction = "test_action"
        )
        
        // Show current state
        val state = metricsService.getMetricsState()
        Messages.showInfoMessage(
            project,
            """Test metrics sent!
            |
            |Metrics State:
            |- Enabled: ${state.enabled}
            |- Active Completions: ${state.activeCompletions}
            |- Queued Events: ${state.queuedEvents}
            |
            |Check the logs for details.
            """.trimMargin(),
            "Metrics Test Complete"
        )
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
