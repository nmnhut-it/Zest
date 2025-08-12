package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.serviceOrNull
import com.zps.zest.completion.ZestInlineCompletionService

/**
 * Simplified dismiss action for the completion service.
 */
class ZestDismiss : AnAction() {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val completionService = project.serviceOrNull<ZestInlineCompletionService>() ?: return
        completionService.dismiss()
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        
        if (project == null || editor == null) {
            e.presentation.isEnabled = false
            return
        }
        
        val completionService = project.serviceOrNull<ZestInlineCompletionService>()
        e.presentation.isEnabled = completionService?.getCurrentCompletion() != null
    }
    
    override fun getActionUpdateThread(): com.intellij.openapi.actionSystem.ActionUpdateThread {
        return com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
    }
}