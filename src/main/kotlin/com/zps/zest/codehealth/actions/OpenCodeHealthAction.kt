package com.zps.zest.codehealth.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.zps.zest.ZestIcons
import com.zps.zest.codehealth.ui.editor.CodeHealthOverviewVirtualFile
import com.zps.zest.completion.metrics.ActionMetricsHelper
import com.zps.zest.completion.metrics.FeatureType

/**
 * Toolbar action to open Code Health Overview in editor tab
 */
class OpenCodeHealthAction : AnAction() {
    
    init {
        templatePresentation.icon = ZestIcons.CODE_HEALTH
        templatePresentation.text = "🛡️ Health"
        templatePresentation.description = "Open Code Health Overview"
    }
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        ActionMetricsHelper.trackAction(
            project,
            FeatureType.CODE_HEALTH_OVERVIEW,
            "Zest.OpenCodeHealth",
            e,
            emptyMap()
        )

        val healthFile = CodeHealthOverviewVirtualFile()
        val editorManager = FileEditorManager.getInstance(project)

        // Open the Code Health Editor in a new tab (normal behavior)
        editorManager.openFile(healthFile, true)
    }
}