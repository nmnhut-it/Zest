package com.zps.zest.completion.actions

import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.zps.zest.completion.ZestInlineCompletionService

/**
 * Action to accept only the next word of the current inline completion.
 * This provides fine-grained control over completion acceptance.
 */
class ZestAcceptNextWord : ZestInlineCompletionAction(object : ZestInlineCompletionActionHandler {
    
    override fun doExecute(editor: Editor, caret: Caret?, service: ZestInlineCompletionService) {
        service.accept(editor, caret?.offset, ZestInlineCompletionService.AcceptType.NEXT_WORD)
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, service: ZestInlineCompletionService): Boolean {
        return service.isInlineCompletionVisibleAt(editor, caret.offset)
    }
}) {
    
    /**
     * Standard priority for partial accept actions.
     */
    override val priority: Int = 10
}
