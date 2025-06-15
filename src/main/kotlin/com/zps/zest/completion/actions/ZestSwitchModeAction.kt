package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.diagnostic.Logger
import com.zps.zest.completion.ZestInlineCompletionService
import com.zps.zest.completion.ZestCompletionProvider
import com.zps.zest.ZestNotifications

/**
 * Action to switch between completion modes (Simple, Lean, Block Rewrite)
 */
class ZestSwitchModeAction : AnAction("Switch Zest Mode"), HasPriority {
    private val logger = Logger.getInstance(ZestSwitchModeAction::class.java)
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getRequiredData(CommonDataKeys.PROJECT)
        val completionService = project.serviceOrNull<ZestInlineCompletionService>()
        
        if (completionService == null) {
            logger.warn("ZestInlineCompletionService not available")
            return
        }
        
        // Get current strategy and switch to next one
        val currentStrategy = completionService.getCompletionStrategy()
        val nextStrategy = getNextStrategy(currentStrategy)
        
        // Update the strategy
        completionService.setCompletionStrategy(nextStrategy)
        
        // Show notification
        val modeDescription = getStrategyDescription(nextStrategy)
        ZestNotifications.showInfo(
            project,
            "Zest Mode Switched",
            "Now using: $modeDescription"
        )
        
        logger.info("Switched completion mode from $currentStrategy to $nextStrategy")
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        val completionService = project?.serviceOrNull<ZestInlineCompletionService>()
        
        e.presentation.isEnabledAndVisible = project != null && completionService != null
        
        // Update action text to show current mode
        if (completionService != null) {
            val currentStrategy = completionService.getCompletionStrategy()
            val currentModeShort = getStrategyShortName(currentStrategy)
            e.presentation.text = "Switch Mode (Current: $currentModeShort)"
            e.presentation.description = "Current: ${getStrategyDescription(currentStrategy)}. Click to switch."
        } else {
            e.presentation.text = "Switch Zest Mode"
            e.presentation.description = "Switch between completion modes"
        }
    }
    
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
    
    /**
     * Get the next strategy in the cycle
     */
    private fun getNextStrategy(current: ZestCompletionProvider.CompletionStrategy): ZestCompletionProvider.CompletionStrategy {
        return when (current) {
            ZestCompletionProvider.CompletionStrategy.SIMPLE -> ZestCompletionProvider.CompletionStrategy.LEAN
            ZestCompletionProvider.CompletionStrategy.LEAN -> ZestCompletionProvider.CompletionStrategy.METHOD_REWRITE
            ZestCompletionProvider.CompletionStrategy.METHOD_REWRITE -> ZestCompletionProvider.CompletionStrategy.SIMPLE
        }
    }
    
    /**
     * Get human-readable description of strategy
     */
    private fun getStrategyDescription(strategy: ZestCompletionProvider.CompletionStrategy): String {
        return when (strategy) {
            ZestCompletionProvider.CompletionStrategy.SIMPLE -> "Simple Mode - FIM-based inline completions"
            ZestCompletionProvider.CompletionStrategy.LEAN -> "Lean Mode - Context-aware completions with reasoning"
            ZestCompletionProvider.CompletionStrategy.METHOD_REWRITE -> "Block Rewrite Mode - Whole block/function rewrites in floating window"
        }
    }
    
    /**
     * Get short name for UI display
     */
    private fun getStrategyShortName(strategy: ZestCompletionProvider.CompletionStrategy): String {
        return when (strategy) {
            ZestCompletionProvider.CompletionStrategy.SIMPLE -> "Simple"
            ZestCompletionProvider.CompletionStrategy.LEAN -> "Lean"
            ZestCompletionProvider.CompletionStrategy.METHOD_REWRITE -> "Block Rewrite"
        }
    }
    
    /**
     * High priority for mode switching
     */
    override val priority: Int = 18
}
