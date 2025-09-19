package com.zps.zest.chatui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * Action to open the simple chat dialog
 */
class OpenChatDialogAction : AnAction() {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        val chatService = project.getService(ChatUIService::class.java)
        chatService.openChat()
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}