package com.zps.zest.completion.metrics

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import java.awt.event.KeyEvent

/**
 * Helper utility for tracking feature usage from IntelliJ actions.
 * Makes it easy to add metrics tracking to any action.
 */
object ActionMetricsHelper {

    /**
     * Determine how the action was triggered based on AnActionEvent
     */
    fun determineTriggerMethod(event: AnActionEvent): TriggerMethod {
        val inputEvent = event.inputEvent
        val place = event.place

        return when {
            // Keyboard shortcut
            inputEvent is KeyEvent -> TriggerMethod.KEYBOARD_SHORTCUT

            // Toolbar click
            place == "MainToolbar" || place.contains("Toolbar") -> TriggerMethod.TOOLBAR_CLICK

            // Editor popup (right-click context menu)
            place == "EditorPopup" -> TriggerMethod.EDITOR_POPUP

            // Menu click
            place.contains("Menu") -> TriggerMethod.MENU_CLICK

            // Programmatic or unknown
            else -> TriggerMethod.PROGRAMMATIC
        }
    }

    /**
     * Track action usage with automatic trigger method detection
     */
    fun trackAction(
        project: Project,
        featureType: FeatureType,
        actionId: String,
        event: AnActionEvent,
        contextInfo: Map<String, String> = emptyMap()
    ) {
        try {
            val metricsService = project.getService(ZestInlineCompletionMetricsService::class.java)
            val triggerMethod = determineTriggerMethod(event)

            metricsService.trackFeatureUsage(
                featureType = featureType,
                actionId = actionId,
                triggeredBy = triggerMethod,
                contextInfo = contextInfo
            )
        } catch (e: Exception) {
            // Silent failure - metrics are non-critical
        }
    }

    /**
     * Track action usage with explicit trigger method
     */
    fun trackAction(
        project: Project,
        featureType: FeatureType,
        actionId: String,
        triggeredBy: TriggerMethod,
        contextInfo: Map<String, String> = emptyMap()
    ) {
        try {
            val metricsService = project.getService(ZestInlineCompletionMetricsService::class.java)

            metricsService.trackFeatureUsage(
                featureType = featureType,
                actionId = actionId,
                triggeredBy = triggeredBy,
                contextInfo = contextInfo
            )
        } catch (e: Exception) {
            // Silent failure - metrics are non-critical
        }
    }
}
