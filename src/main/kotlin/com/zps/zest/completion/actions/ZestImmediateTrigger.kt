package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.diagnostic.Logger
import com.zps.zest.completion.ZestInlineCompletionService

/**
 * Action to immediately trigger inline completion with high priority.
 * This action bypasses debouncing and triggers completion instantly.
 * Uses Ctrl+Alt+Space to avoid conflicts with IntelliJ's basic completion (Ctrl+Space)
 * and Zest's inline chat (Ctrl+Shift+Space).
 */
class ZestImmediateTrigger : AnAction(), HasPriority {
    private val logger = Logger.getInstance(ZestImmediateTrigger::class.java)
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getRequiredData(CommonDataKeys.PROJECT)
        val editor = e.getRequiredData(CommonDataKeys.EDITOR)
        val completionService = project.serviceOrNull<ZestInlineCompletionService>()
        
        if (completionService == null) {
            logger.warn("ZestInlineCompletionService not available")
            return
        }
        
        val offset = editor.caretModel.primaryCaret.offset
        
        System.out.println("Immediately triggering inline completion at offset $offset")
        
        // Trigger completion manually with immediate flag
        completionService.provideInlineCompletion(editor, offset, manually = true)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val completionService = project?.serviceOrNull<ZestInlineCompletionService>()
        
        // Enable only when we have a project, editor, and completion service
        e.presentation.isEnabledAndVisible = project != null && 
                                           editor != null && 
                                           completionService != null &&
                                           !editor.isDisposed
        
        // Now safe to access editor state since we're on EDT
        if (editor != null && completionService != null) {
            val hasVisibleCompletion = completionService.isInlineCompletionVisibleAt(
                editor, 
                editor.caretModel.primaryCaret.offset
            )
            
            e.presentation.text = if (hasVisibleCompletion) {
                "Refresh Inline Completion"
            } else {
                "Trigger Inline Completion"
            }
        } else {
            e.presentation.text = "Trigger Inline Completion"
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.EDT
    }

    /**
     * Highest priority to ensure this action takes precedence over other completion triggers.
     */
    override val priority: Int = 20
}