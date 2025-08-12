package com.zps.zest.browser.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.zps.zest.browser.editor.ZestChatVirtualFileSystem

/**
 * Toolbar action to open ZPS Chat in editor tab
 */
class OpenZestChatAction : AnAction() {
    
    init {
        templatePresentation.icon = com.zps.zest.ZestIcons.ZPS_CHAT
        templatePresentation.text = "💬 Chat"
        templatePresentation.description = "Open ZPS AI Chat"
    }
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        // Use the same split logic from OpenChatInEditorAction
        OpenChatInEditorAction.openChatInSplitEditor(project)
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}