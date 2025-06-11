package com.zps.zest.completion.actions

import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.zps.zest.completion.ZestInlineCompletionService

/**
 * Handler interface for inline completion actions.
 * Defines the contract for executing completion-related actions.
 */
interface ZestInlineCompletionActionHandler {
    
    /**
     * Execute the action with the given editor, caret, and completion service.
     * 
     * @param editor The editor where the action is executed
     * @param caret The caret position (may be null for actions that don't require specific caret)
     * @param service The inline completion service
     */
    fun doExecute(editor: Editor, caret: Caret?, service: ZestInlineCompletionService)
    
    /**
     * Check if this action is enabled for the given caret position.
     * 
     * @param editor The editor to check
     * @param caret The caret position to check
     * @param service The inline completion service
     * @return true if the action should be enabled, false otherwise
     */
    fun isEnabledForCaret(editor: Editor, caret: Caret, service: ZestInlineCompletionService): Boolean {
        return true
    }
}
