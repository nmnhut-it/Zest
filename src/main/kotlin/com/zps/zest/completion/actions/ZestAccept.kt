package com.zps.zest.completion.actions

import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.zps.zest.completion.ZestInlineCompletionService

/**
 * Action to accept the currently visible inline completion.
 * This will insert the full completion text at the current cursor position.
 */
class ZestAccept : ZestInlineCompletionAction(object : ZestInlineCompletionActionHandler {
    
    override fun doExecute(editor: Editor, caret: Caret?, service: ZestInlineCompletionService) {
        service.accept(editor, caret?.offset, ZestInlineCompletionService.AcceptType.FULL_COMPLETION)
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, service: ZestInlineCompletionService): Boolean {
        return service.isInlineCompletionVisibleAt(editor, caret.offset)
    }
}) {
    
    /**
     * High priority for accept action.
     */
    override val priority: Int = 12
}
