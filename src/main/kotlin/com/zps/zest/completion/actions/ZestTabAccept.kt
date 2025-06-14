package com.zps.zest.completion.actions

import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.zps.zest.completion.ZestInlineCompletionService

/**
 * TAB action for accepting only the next line of completions.
 * Simple and predictable: TAB = accept next line only.
 */
class ZestTabAccept : ZestInlineCompletionAction(object : ZestInlineCompletionActionHandler {
    
    override fun doExecute(editor: Editor, caret: Caret?, service: ZestInlineCompletionService) {
        // Since we now only show first line, TAB accepts the entire visible completion
        // This is equivalent to NEXT_LINE but more intuitive for single-line display
        service.accept(editor, caret?.offset, ZestInlineCompletionService.AcceptType.FULL_COMPLETION)
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, service: ZestInlineCompletionService): Boolean {
        // Check if completion is visible
        if (!service.isInlineCompletionVisibleAt(editor, caret.offset)) {
            return false
        }
        
        // Simple check: accept if completion contains meaningful content
        val currentCompletion = service.getCurrentCompletion() ?: return false
        val completionText = currentCompletion.insertText.trim()
        
        // Don't accept if it's just whitespace
        if (completionText.isBlank()) {
            return false
        }
        
        // Don't accept if the cursor is at the start of a line and completion is just indentation
        val document = editor.document
        val lineNumber = document.getLineNumber(caret.offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val isAtLineStart = caret.offset == lineStart
        
        if (isAtLineStart && completionText.all { it.isWhitespace() }) {
            return false
        }
        
        return true
    }
}) {
    
    /**
     * Very high priority for tab accept to override IntelliJ's tab handling.
     */
    override val priority: Int = 500
}
