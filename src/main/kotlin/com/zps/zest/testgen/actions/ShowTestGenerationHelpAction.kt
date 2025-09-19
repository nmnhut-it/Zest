package com.zps.zest.testgen.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.zps.zest.update.VersionUpdateNotifier

/**
 * Action to show test generation documentation
 */
class ShowTestGenerationHelpAction : AnAction(
    "📖 Test Generation Guide",
    "View comprehensive test generation documentation",
    null
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        VersionUpdateNotifier.showTestGenerationOverview(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}