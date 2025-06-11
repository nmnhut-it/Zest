package com.zps.zest.completion.actions

import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.zps.zest.completion.ZestInlineCompletionService

/**
 * Action to cycle to the previous completion suggestion.
 * This allows users to navigate backward through multiple completion options.
 */
class ZestCyclePrevious : ZestInlineCompletionAction(object : ZestInlineCompletionActionHandler {
    
    override fun doExecute(editor: Editor, caret: Caret?, service: ZestInlineCompletionService) {
        service.cycle(editor, caret?.offset, ZestInlineCompletionService.CycleDirection.PREVIOUS)
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, service: ZestInlineCompletionService): Boolean {
        return service.isInlineCompletionVisibleAt(editor, caret.offset)
    }
}) {
    
    /**
     * Standard priority for cycle actions.
     */
    override val priority: Int = 10
}
