package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.serviceOrNull
import com.zps.zest.completion.ZestInlineCompletionService

/**
 * Manual trigger action for the completion service.
 */
class ZestTrigger : AnAction() {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        
        val completionService = project.serviceOrNull<ZestInlineCompletionService>() ?: return
        val offset = editor.caretModel.offset
        
        // Manually trigger completion
        completionService.requestCompletion(editor, offset, manually = true)
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        
        e.presentation.isEnabled = project != null && editor != null
    }
    
    override fun getActionUpdateThread(): com.intellij.openapi.actionSystem.ActionUpdateThread {
        return com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
    }
}