package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.zps.zest.completion.ZestCompletionProvider
import com.zps.zest.completion.ZestInlineCompletionService
import com.zps.zest.ZestNotifications

/**
 * Quick demo action for line-by-line completion functionality (LEAN mode is default)
 */
class ZestTestProgressiveAction : AnAction("Demo Line-by-Line Completion"), DumbAware {
    private val logger = Logger.getInstance(ZestTestProgressiveAction::class.java)
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getRequiredData(CommonDataKeys.PROJECT)
        val editor = e.getRequiredData(CommonDataKeys.EDITOR)
        
        try {
            val completionService = project.serviceOrNull<ZestInlineCompletionService>()
            if (completionService == null) {
                ZestNotifications.showError(project, "Demo Failed", "ZestInlineCompletionService not available")
                return
            }
            
            // LEAN strategy is already default, no need to switch
            val currentStrategy = completionService.getCompletionStrategy()
            
            // Trigger completion
            val offset = editor.caretModel.offset
            completionService.provideInlineCompletion(editor, offset, manually = true)
            
            // Show quick instructions
            val message = buildString {
                appendLine("🚀 **Line-by-Line Completion Ready!**")
                appendLine()
                appendLine("**LEAN mode is now default with line-by-line acceptance:**")
                appendLine("1. You see the FULL completion displayed")
                appendLine("2. Press **Tab** to accept FIRST line only")
                appendLine("3. Remaining lines appear automatically")
                appendLine("4. Keep pressing **Tab** to accept one line at a time")
                appendLine("5. Press **Escape** to dismiss at any time")
                appendLine()
                appendLine("**Strategy behaviors:**")
                appendLine("• **LEAN (Default)**: Full display + line-by-line acceptance")
                appendLine("• **SIMPLE**: First line only + traditional full acceptance")
                appendLine()
                appendLine("**Current settings:**")
                appendLine("• Strategy: ${currentStrategy}")
                appendLine("• Cache: ${completionService.getCacheStats()}")
                appendLine()
                appendLine("**This is automatic!** Just type code and use Tab for line-by-line acceptance.")
            }
            
            ZestNotifications.showInfo(
                project,
                "Line-by-Line Completion Ready",
                message
            )
            
        } catch (e: Exception) {
            logger.error("Line-by-line completion demo failed", e)
            ZestNotifications.showError(
                project,
                "Demo Failed",
                "Error: ${e.message}"
            )
        }
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }
}
