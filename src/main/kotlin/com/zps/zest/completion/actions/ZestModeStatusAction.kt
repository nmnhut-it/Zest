package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.serviceOrNull
import com.zps.zest.completion.ZestInlineCompletionService
import com.zps.zest.completion.ZestCompletionProvider
import com.zps.zest.ZestNotifications

/**
 * Action to show the current Zest mode status
 */
class ZestModeStatusAction : AnAction("Show Zest Mode Status"), HasPriority {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getRequiredData(CommonDataKeys.PROJECT)
        val completionService = project.serviceOrNull<ZestInlineCompletionService>()
        
        if (completionService == null) {
            ZestNotifications.showWarning(
                project,
                "Zest Status",
                "Zest completion service is not available"
            )
            return
        }
        
        val currentStrategy = completionService.getCompletionStrategy()
        val autoTriggerEnabled = true // TODO: Add getter for auto-trigger status
        
        val statusMessage = buildString {
            appendLine("🎯 Current Mode: ${getStrategyName(currentStrategy)}")
            appendLine("⚡ Auto-trigger: ${if (autoTriggerEnabled) "Enabled" else "Disabled"}")
            appendLine()
            appendLine(getStrategyDescription(currentStrategy))
            appendLine()
            
            when (currentStrategy) {
                ZestCompletionProvider.CompletionStrategy.SIMPLE -> {
                    appendLine("• Fast FIM-based completions")
                    appendLine("• Good for quick suggestions")
                    appendLine("• Minimal context analysis")
                }
                ZestCompletionProvider.CompletionStrategy.LEAN -> {
                    appendLine("• Context-aware completions")
                    appendLine("• Includes reasoning process")
                    appendLine("• Better code understanding")
                }
                ZestCompletionProvider.CompletionStrategy.BLOCK_REWRITE -> {
                    appendLine("• Whole block/function rewrites")
                    appendLine("• Floating window previews")
                    appendLine("• Comprehensive improvements")
                    appendLine("• Perfect for refactoring")
                }
            }
            
            appendLine()
            appendLine("💡 Use 'Switch Zest Mode' to change modes")
        }
        
        ZestNotifications.showInfo(
            project,
            "Zest Mode Status",
            statusMessage
        )
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        val completionService = project?.serviceOrNull<ZestInlineCompletionService>()
        
        e.presentation.isEnabledAndVisible = project != null && completionService != null
        
        // Update action text with current mode
        if (completionService != null) {
            val currentStrategy = completionService.getCompletionStrategy()
            val modeIcon = when (currentStrategy) {
                ZestCompletionProvider.CompletionStrategy.SIMPLE -> "⚡"
                ZestCompletionProvider.CompletionStrategy.LEAN -> "🧠"
                ZestCompletionProvider.CompletionStrategy.BLOCK_REWRITE -> "🔄"
            }
            e.presentation.text = "$modeIcon ${getStrategyName(currentStrategy)} Mode"
            e.presentation.description = "Current Zest mode: ${getStrategyDescription(currentStrategy)}"
        } else {
            e.presentation.text = "Show Zest Mode Status"
            e.presentation.description = "Show current completion mode and settings"
        }
    }
    
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
    
    private fun getStrategyName(strategy: ZestCompletionProvider.CompletionStrategy): String {
        return when (strategy) {
            ZestCompletionProvider.CompletionStrategy.SIMPLE -> "Simple"
            ZestCompletionProvider.CompletionStrategy.LEAN -> "Lean"
            ZestCompletionProvider.CompletionStrategy.BLOCK_REWRITE -> "Block Rewrite"
        }
    }
    
    private fun getStrategyDescription(strategy: ZestCompletionProvider.CompletionStrategy): String {
        return when (strategy) {
            ZestCompletionProvider.CompletionStrategy.SIMPLE -> "Fast inline completions with minimal context"
            ZestCompletionProvider.CompletionStrategy.LEAN -> "Context-aware completions with reasoning"
            ZestCompletionProvider.CompletionStrategy.BLOCK_REWRITE -> "Whole block rewrites with floating window preview"
        }
    }
    
    /**
     * Lower priority for status actions
     */
    override val priority: Int = 5
}
