package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.serviceOrNull
import com.zps.zest.completion.ZestInlineCompletionService
import com.zps.zest.completion.ZestQuickActionService

/**
 * Simplified TAB action for accepting completions.
 * Works with the new self-managing state machine.
 */
class ZestTabAccept : AnAction() {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        
        // First check if we have an active method rewrite (separate feature)
        val methodRewriteService = project.serviceOrNull<ZestQuickActionService>()
        if (methodRewriteService?.isRewriteInProgress() == true) {
            methodRewriteService.acceptMethodRewrite(editor)
            return
        }
        
        // Accept inline completion
        val completionService = project.serviceOrNull<ZestInlineCompletionService>() ?: return
        completionService.acceptCompletion("TAB")
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        
        if (project == null || editor == null) {
            e.presentation.isEnabled = false
            return
        }
        
        // Check if method rewrite is active
        val methodRewriteService = project.serviceOrNull<ZestQuickActionService>()
        if (methodRewriteService?.isRewriteInProgress() == true) {
            e.presentation.isEnabled = true
            return
        }
        
        // Check if inline completion can be accepted
        val completionService = project.serviceOrNull<ZestInlineCompletionService>()
        e.presentation.isEnabled = completionService?.canAcceptCompletion() == true
    }
    
    override fun getActionUpdateThread(): com.intellij.openapi.actionSystem.ActionUpdateThread {
        return com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
    }
}