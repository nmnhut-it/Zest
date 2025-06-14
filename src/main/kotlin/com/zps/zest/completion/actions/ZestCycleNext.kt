package com.zps.zest.completion.actions

import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.zps.zest.completion.ZestInlineCompletionService

/**
 * Action to cycle to the next completion suggestion.
 * Currently disabled in simplified mode.
 */
class ZestCycleNext : ZestInlineCompletionAction(object : ZestInlineCompletionActionHandler {
    
    override fun doExecute(editor: Editor, caret: Caret?, service: ZestInlineCompletionService) {
        // Cycling disabled in simplified mode - just trigger new completion
        service.provideInlineCompletion(editor, caret?.offset ?: editor.caretModel.offset, manually = true)
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, service: ZestInlineCompletionService): Boolean {
        // Disabled in simplified mode
        return false
    }
}) {
    
    /**
     * Standard priority for cycle actions.
     */
    override val priority: Int = 10
}
