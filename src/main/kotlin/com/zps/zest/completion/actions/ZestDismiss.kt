package com.zps.zest.completion.actions

import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.components.serviceOrNull
import com.zps.zest.completion.ZestInlineCompletionService
import com.zps.zest.completion.ZestMethodRewriteService

/**
 * Action to dismiss the currently visible inline completion.
 * This will hide the completion without accepting it.
 */
class ZestDismiss : ZestInlineCompletionAction(object : ZestInlineCompletionActionHandler {
    
    override fun doExecute(editor: Editor, caret: Caret?, service: ZestInlineCompletionService) {
        // First check if we have an active method rewrite
        val methodRewriteService = editor.project?.serviceOrNull<ZestMethodRewriteService>()
        if (methodRewriteService?.isRewriteInProgress() == true) {
            // Reject the method rewrite instead of dismissing completion
            methodRewriteService.cancelCurrentRewrite()
            return
        }
        
        // Otherwise dismiss inline completion
        service.dismiss()
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, service: ZestInlineCompletionService): Boolean {
        // Enable if we have an active method rewrite
        val methodRewriteService = editor.project?.serviceOrNull<ZestMethodRewriteService>()
        if (methodRewriteService?.isRewriteInProgress() == true) {
            return true
        }
        
        // Otherwise check for inline completion
        return service.isInlineCompletionVisibleAt(editor, caret.offset)
    }
}) {
    
    /**
     * Lower priority for dismiss action - it should be a fallback.
     */
    override val priority: Int = -1
}
