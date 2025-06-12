package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorAction
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.zps.zest.completion.ZestInlineCompletionService

/**
 * Base class for all Zest inline completion actions.
 * Provides common functionality and ensures proper integration with the completion service.
 */
abstract class ZestInlineCompletionAction(
    private val completionHandler: ZestInlineCompletionActionHandler
) : EditorAction(
    object : EditorActionHandler() {
        override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
            //
            val completionService = editor.project?.serviceOrNull<ZestInlineCompletionService>() ?: return
            completionHandler.doExecute(editor, caret, completionService)
        }

        override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean {
            val completionService = editor.project?.serviceOrNull<ZestInlineCompletionService>() ?: return false
            return completionHandler.isEnabledForCaret(editor, caret, completionService)
        }
    }
), HasPriority {
    
    /**
     * Default priority for Zest completion actions.
     * Higher than most other actions to ensure they take precedence.
     */
    override val priority: Int = 10
}
