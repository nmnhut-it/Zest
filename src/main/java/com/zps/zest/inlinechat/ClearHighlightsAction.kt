package com.zps.zest.inlinechat

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDocumentManager

/**
 * Action to clear all diff highlighting
 */
class ClearHighlightsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        
        // Get the InlineChatService
        val service = project.getService(InlineChatService::class.java)
        
        // Clear all diff segments
        WriteCommandAction.runWriteCommandAction(project) {
            service.diffSegments = ArrayList()
            service.originalCode = null
            
            // Force document update to refresh highlighting
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        }
        
        // Force editor highlighting refresh
        ApplicationManager.getApplication().invokeLater {
            editor.contentComponent.repaint()
            
            // Show a notification
            Messages.showInfoMessage(
                project,
                "All diff highlighting has been cleared.",
                "Highlights Cleared"
            )
        }
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        
        // Enable only if there's an editor and a project
        e.presentation.isEnabled = project != null && editor != null
    }
    
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}