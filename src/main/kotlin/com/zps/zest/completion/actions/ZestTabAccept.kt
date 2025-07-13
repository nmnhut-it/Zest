package com.zps.zest.completion.actions

import ai.grazie.text.TextRange
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.components.serviceOrNull
import com.intellij.util.text.TextRangeUtil
import com.zps.zest.completion.ZestInlineCompletionService
import com.zps.zest.completion.ZestMethodRewriteService
import kotlin.math.abs

/**
 * TAB action for full completion acceptance.
 * Tab always accepts the entire completion regardless of strategy.
 * For line-by-line acceptance, use Ctrl+Tab instead.
 */
class ZestTabAccept : ZestInlineCompletionAction(object : ZestInlineCompletionActionHandler {
    
    override fun doExecute(editor: Editor, caret: Caret?, service: ZestInlineCompletionService) {
        // First check if we have an active method rewrite
        val methodRewriteService = editor.project?.serviceOrNull<ZestMethodRewriteService>()
        if (methodRewriteService?.isRewriteInProgress() == true) {
            // Accept the method rewrite instead of inline completion
            methodRewriteService.acceptMethodRewrite(editor)
            return
        }
        
        // Always accept full completion with Tab (no more line-by-line)
        service.accept(editor, caret?.offset, ZestInlineCompletionService.AcceptType.FULL_COMPLETION)
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, service: ZestInlineCompletionService): Boolean {
        // Enable if we have an active method rewrite
        val methodRewriteService = editor.project?.serviceOrNull<ZestMethodRewriteService>()
        if (methodRewriteService?.isRewriteInProgress() == true) {
            return true
        }
        
        // Check if completion is visible
        if (!service.isInlineCompletionVisibleAt(editor, caret.offset)) {
            val current = service.renderer.current
            if (  abs((if (current != null) current.offset else Integer.MAX_VALUE) - caret.offset) >= 10 ) {
                return false
            }
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
