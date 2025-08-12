package com.zps.zest.browser.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.zps.zest.browser.editor.ZestChatVirtualFileSystem

/**
 * Action to open Zest Chat in a split editor view alongside the current code
 */
class OpenChatInEditorAction : AnAction(
    "Open Chat in Editor",
    "Open ZPS Chat in a split editor view alongside your code",
    AllIcons.Toolwindows.ToolWindowMessages
) {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        ApplicationManager.getApplication().invokeLater {
            openChatInSplitEditor(project)
        }
    }
    
    companion object {
        /**
         * Opens the chat session in a split editor view
         */
        fun openChatInSplitEditor(project: Project, sessionId: String = "main") {
            val chatFile = ZestChatVirtualFileSystem.createChatFile(sessionId)
            val editorManager = FileEditorManager.getInstance(project) as FileEditorManagerEx
            
            // Get the current editor window
            val currentWindow = editorManager.currentWindow
            if (currentWindow != null) {
                // Create a splitter (vertical split by default)
                currentWindow.split(
                    javax.swing.SwingConstants.VERTICAL,  // Split vertically (side by side)
                    true,  // requestFocus
                    chatFile,  // virtualFile to open
                    true  // focusNew
                )
            } else {
                // Fallback to regular open if no current window
                editorManager.openFile(chatFile, true)
            }
        }
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}