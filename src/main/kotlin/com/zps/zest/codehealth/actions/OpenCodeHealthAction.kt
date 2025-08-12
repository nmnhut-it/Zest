package com.zps.zest.codehealth.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.zps.zest.ZestIcons
import com.zps.zest.codehealth.ui.editor.CodeHealthOverviewVirtualFile

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
        
        val healthFile = CodeHealthOverviewVirtualFile()
        val editorManager = FileEditorManager.getInstance(project)
        
        // Open in preview/split mode on the right
        val editors = editorManager.openFile(healthFile, true, true)
        
        // Try to ensure it opens in split view
        if (editorManager is com.intellij.openapi.fileEditor.ex.FileEditorManagerEx) {
            val window = editorManager.currentWindow
            if (window != null && window.tabCount == 1) {
                // If it's the only tab, try to split
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    try {
                        val splitAction = com.intellij.openapi.actionSystem.ActionManager.getInstance()
                            .getAction("SplitVertically")
                        if (splitAction != null) {
                            splitAction.actionPerformed(e)
                        }
                    } catch (ex: Exception) {
                        // Ignore if split fails
                    }
                }
            }
        }
    }
}