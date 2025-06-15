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
 * Quick action to demonstrate strategy differences
 */
class ZestQuickStrategyTestAction : AnAction("Quick Strategy Demo"), DumbAware {
    private val logger = Logger.getInstance(ZestQuickStrategyTestAction::class.java)
    
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
            appendLine("🔧 Current Strategy: $currentStrategy")
            appendLine()
            when (currentStrategy) {
                ZestCompletionProvider.CompletionStrategy.SIMPLE -> {
                    appendLine("📋 SIMPLE Strategy Features:")
                    appendLine("• Fast FIM-based completions (~2-5 seconds)")
                    appendLine("• Shows first line only for clean display")
                    appendLine("• Uses prefix/suffix context")
                    appendLine("• 16 token limit, mini model")
                    appendLine()
                    appendLine("💡 Try switching to LEAN for:")
                    appendLine("• Multi-line completions")
                    appendLine("• Better context understanding")
                    appendLine("• AI reasoning explanations")
                }
                ZestCompletionProvider.CompletionStrategy.LEAN -> {
                    appendLine("🧠 LEAN Strategy Features:")
                    appendLine("• Reasoning-based completions (~5-15 seconds)")
                    appendLine("• Shows full multi-line completions")
                    appendLine("• Uses entire file context")
                    appendLine("• 1000 token limit, full model")
                    appendLine()
                    appendLine("💡 Try switching to SIMPLE for:")
                    appendLine("• Faster responses")
                    appendLine("• Simple inline suggestions")
                    appendLine("• Less resource usage")
                }
                ZestCompletionProvider.CompletionStrategy.BLOCK_REWRITE -> {
                    appendLine("🔄 BLOCK_REWRITE Strategy Features:")
                    appendLine("• Block-level code rewriting")
                    appendLine("• Shows floating window preview")
                    appendLine("• Rewrites entire code blocks")
                    appendLine("• Context-aware improvements")
                    appendLine()
                    appendLine("💡 Try switching to SIMPLE or LEAN for:")
                    appendLine("• Traditional inline completions")
                    appendLine("• Single-line suggestions")
                    appendLine("• Faster responses")
                }
            }
            appendLine()
            appendLine("🎯 Triggering completion at offset $offset")
            appendLine("Use 'Switch Completion Strategy' to change modes")
        }
        
        // Trigger a completion to demonstrate current strategy
        completionService.provideInlineCompletion(editor, offset, manually = true)
        
        ZestNotifications.showInfo(
            project,
            "Strategy Demo: $currentStrategy",
            message
        )
        
        logger.info("Quick strategy demo shown for: $currentStrategy")
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
            e.presentation.text = "Demo: $currentStrategy Strategy"
        }
    }
}
