package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.serviceOrNull
import com.zps.zest.completion.ZestInlineCompletionService

/**
 * Action to manually trigger inline completion.
 * This action can be invoked even when no completion is currently visible.
 */
class ZestTrigger : AnAction(), HasPriority {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getRequiredData(CommonDataKeys.PROJECT)
        val editor = e.getRequiredData(CommonDataKeys.EDITOR)
        val completionService = project.serviceOrNull<ZestInlineCompletionService>() ?: return
        
        val offset = editor.caretModel.primaryCaret.offset
        completionService.provideInlineCompletion(editor, offset, manually = true)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        
        e.presentation.isEnabled = project != null && editor != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    /**
     * High priority to ensure trigger action takes precedence.
     */
    override val priority: Int = 15
}
