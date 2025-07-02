package com.zps.zest.completion.actions

import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.zps.zest.completion.ZestInlineCompletionService

/**
 * Action to accept the currently visible inline completion.
 * Ctrl+Tab behavior:
 * - LEAN strategy: Line-by-line acceptance (accepts first line, shows remaining)
 * - SIMPLE/METHOD_REWRITE: Full completion acceptance
 */
class ZestAccept : ZestInlineCompletionAction(object : ZestInlineCompletionActionHandler {
    
    override fun doExecute(editor: Editor, caret: Caret?, service: ZestInlineCompletionService) {
        // For Ctrl+Tab, we want line-by-line acceptance in LEAN mode
        // This tells the service to handle line-by-line logic for LEAN strategy
        service.accept(editor, caret?.offset, ZestInlineCompletionService.AcceptType.CTRL_TAB_COMPLETION)
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
