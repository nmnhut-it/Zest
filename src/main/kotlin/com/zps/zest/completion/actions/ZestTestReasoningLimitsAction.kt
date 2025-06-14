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
 * Test action to validate reasoning word limits are enforced
 */
class ZestTestReasoningLimitsAction : AnAction("Test Reasoning Limits"), DumbAware {
    private val logger = Logger.getInstance(ZestTestReasoningLimitsAction::class.java)
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getRequiredData(CommonDataKeys.PROJECT)
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val completionService = project.serviceOrNull<ZestInlineCompletionService>()
        
        if (completionService == null) {
            logger.warn("ZestInlineCompletionService not available")
            return
        }
        
        val currentStrategy = completionService.getCompletionStrategy()
        val offset = editor.caretModel.offset
        
        val message = buildString {
            appendLine("🧪 Reasoning Limits Test")
            appendLine()
            appendLine("Current Strategy: $currentStrategy")
            appendLine()
            
            when (currentStrategy) {
                ZestCompletionProvider.CompletionStrategy.SIMPLE -> {
                    appendLine("📋 SIMPLE Strategy Limits:")
                    appendLine("• No reasoning section (FIM-based)")
                    appendLine("• Max tokens: 16")
                    appendLine("• Timeout: 8 seconds")
                    appendLine("• Show: First line only")
                    appendLine()
                    appendLine("💡 Switch to LEAN to test reasoning limits")
                }
                ZestCompletionProvider.CompletionStrategy.LEAN -> {
                    appendLine("🧠 LEAN Strategy Limits:")
                    appendLine("• Max reasoning words: 60")
                    appendLine("• Max total tokens: 200 (reasoning + completion)")
                    appendLine("• Timeout: 15 seconds")
                    appendLine("• Show: Full multi-line")
                    appendLine()
                    appendLine("📏 Prompt Instructions:")
                    appendLine("• Main prompt: MAX 50 words reasoning")
                    appendLine("• Simple prompt: MAX 20 words reasoning")
                    appendLine("• Focused prompt: MAX 30 words reasoning")
                    appendLine()
                    appendLine("🔧 Enforced by parser:")
                    appendLine("• Truncates verbose reasoning")
                    appendLine("• Removes verbose prefixes")
                    appendLine("• Adds '...' if truncated")
                    appendLine()
                    appendLine("🎯 Triggering LEAN completion to test...")
                }
            }
            appendLine()
            appendLine("Watch for debug logs showing reasoning truncation")
        }
        
        // Trigger completion to test limits
        if (currentStrategy == ZestCompletionProvider.CompletionStrategy.LEAN) {
            completionService.provideInlineCompletion(editor, offset, manually = true)
        }
        
        ZestNotifications.showInfo(
            project,
            "Reasoning Limits: $currentStrategy",
            message
        )
        
        logger.info("Reasoning limits test for strategy: $currentStrategy")
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val completionService = project?.serviceOrNull<ZestInlineCompletionService>()
        
        e.presentation.isEnabledAndVisible = project != null && 
                                           editor != null && 
                                           completionService != null
        
        if (project != null && completionService != null) {
            val currentStrategy = completionService.getCompletionStrategy()
            val limits = when (currentStrategy) {
                ZestCompletionProvider.CompletionStrategy.SIMPLE -> "16 tokens"
                ZestCompletionProvider.CompletionStrategy.LEAN -> "60 words reasoning, 200 tokens total"
            }
            e.presentation.text = "Test Limits: $limits"
        }
    }
}
