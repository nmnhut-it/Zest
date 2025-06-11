package com.zps.zest.completion.actions

import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.zps.zest.completion.ZestInlineCompletionService

/**
 * Special action for accepting completions with the TAB key.
 * This action is smart about when to accept completions vs. allowing normal TAB behavior.
 * It will NOT accept completions that start with indentation to avoid interfering with
 * normal indentation workflow.
 */
class ZestTabAccept : ZestInlineCompletionAction(object : ZestInlineCompletionActionHandler {
    
    override fun doExecute(editor: Editor, caret: Caret?, service: ZestInlineCompletionService) {
        service.accept(editor, caret?.offset, ZestInlineCompletionService.AcceptType.FULL_COMPLETION)
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, service: ZestInlineCompletionService): Boolean {
        // Only enable if completion is visible AND it doesn't start with indentation
        return service.isInlineCompletionVisibleAt(editor, caret.offset) && 
               !service.isInlineCompletionStartWithIndentation()
    }
}) {
    
    /**
     * High priority for tab accept, but lower than explicit accept action.
     */
    override val priority: Int = 11
}
