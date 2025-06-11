package com.zps.zest.completion.actions

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.zps.zest.completion.ZestInlineCompletionService
import com.zps.zest.completion.behavior.ZestTabBehaviorManager

/**
 * Special action for accepting completions with the TAB key.
 * This action uses advanced context analysis to determine when to accept completions
 * vs. allowing normal TAB behavior for indentation.
 * 
 * The decision is made by ZestTabBehaviorManager which analyzes:
 * - Current editing context (line position, indentation level)
 * - Completion content (code vs indentation)
 * - Language-specific indentation rules
 * - User's code style preferences
 */
class ZestTabAccept : ZestInlineCompletionAction(object : ZestInlineCompletionActionHandler {
    
    override fun doExecute(editor: Editor, caret: Caret?, service: ZestInlineCompletionService) {
        service.accept(editor, caret?.offset, ZestInlineCompletionService.AcceptType.FULL_COMPLETION)
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, service: ZestInlineCompletionService): Boolean {
        // Check if completion is visible
        if (!service.isInlineCompletionVisibleAt(editor, caret.offset)) {
            return false
        }
        
        // Use advanced tab behavior analysis
        val project = editor.project ?: return false
        val tabBehaviorManager = project.service<ZestTabBehaviorManager>()
        val currentCompletion = service.getCurrentCompletion() ?: return false
        
        return tabBehaviorManager.shouldAcceptCompletionOnTab(
            editor = editor,
            offset = caret.offset,
            completion = currentCompletion
        )
    }
}) {
    
    /**
     * High priority for tab accept, but lower than explicit accept action.
     */
    override val priority: Int = 11
}
