package com.zps.zest.completion.actions

import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.zps.zest.completion.ZestInlineCompletionService

/**
 * Action to accept only the next line of the current inline completion.
 * This provides fine-grained control over completion acceptance.
 */
class ZestAcceptNextLine : ZestInlineCompletionAction(object : ZestInlineCompletionActionHandler {
    
    override fun doExecute(editor: Editor, caret: Caret?, service: ZestInlineCompletionService) {
        service.accept(editor, caret?.offset, ZestInlineCompletionService.AcceptType.NEXT_LINE)
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
