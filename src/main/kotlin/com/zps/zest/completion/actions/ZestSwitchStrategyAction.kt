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
 * Action to switch between completion strategies for A/B testing
 */
class ZestSwitchStrategyAction : AnAction("Switch Completion Strategy"), DumbAware {
    private val logger = Logger.getInstance(ZestSwitchStrategyAction::class.java)
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getRequiredData(CommonDataKeys.PROJECT)
        val completionService = project.serviceOrNull<ZestInlineCompletionService>()
        
        if (completionService == null) {
            logger.warn("ZestInlineCompletionService not available")
            return
        }
        
        // Access the completion provider through reflection or make it public
        // For now, we'll use a simple toggle approach
        val currentStrategy = getCurrentStrategy(project)
        val newStrategy = when (currentStrategy) {
            ZestCompletionProvider.CompletionStrategy.SIMPLE -> ZestCompletionProvider.CompletionStrategy.LEAN
            ZestCompletionProvider.CompletionStrategy.LEAN -> ZestCompletionProvider.CompletionStrategy.BLOCK_REWRITE
            ZestCompletionProvider.CompletionStrategy.BLOCK_REWRITE -> ZestCompletionProvider.CompletionStrategy.SIMPLE
        }
        
        setStrategy(project, newStrategy)
        
        val message = when (newStrategy) {
            ZestCompletionProvider.CompletionStrategy.SIMPLE -> 
                "Switched to SIMPLE strategy (FIM-based, fast completions)"
            ZestCompletionProvider.CompletionStrategy.LEAN -> 
                "Switched to LEAN strategy (reasoning-based, context-aware completions)"
            ZestCompletionProvider.CompletionStrategy.BLOCK_REWRITE -> 
                "Switched to BLOCK_REWRITE strategy (block-level rewrites with floating preview)"
        }
        
        ZestNotifications.showInfo(
            project,
            "Completion Strategy Changed",
            message
        )
        
        logger.info("Completion strategy changed to: $newStrategy")
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        val completionService = project?.serviceOrNull<ZestInlineCompletionService>()
        
        e.presentation.isEnabledAndVisible = project != null && completionService != null
        
        if (project != null) {
            val currentStrategy = getCurrentStrategy(project)
            e.presentation.text = when (currentStrategy) {
                ZestCompletionProvider.CompletionStrategy.SIMPLE -> "Switch to LEAN Strategy"
                ZestCompletionProvider.CompletionStrategy.LEAN -> "Switch to BLOCK_REWRITE Strategy"
                ZestCompletionProvider.CompletionStrategy.BLOCK_REWRITE -> "Switch to SIMPLE Strategy"
            }
        }
    }
    
    private fun getCurrentStrategy(project: com.intellij.openapi.project.Project): ZestCompletionProvider.CompletionStrategy {
        // Get strategy from the completion service
        val completionService = project.serviceOrNull<ZestInlineCompletionService>()
        return completionService?.getCompletionStrategy() ?: ZestCompletionProvider.CompletionStrategy.SIMPLE
    }
    
    private fun setStrategy(project: com.intellij.openapi.project.Project, strategy: ZestCompletionProvider.CompletionStrategy) {
        // Store strategy in project user data for persistence
        project.putUserData(STRATEGY_KEY, strategy)
        
        // Update the completion service strategy
        val completionService = project.serviceOrNull<ZestInlineCompletionService>()
        completionService?.setCompletionStrategy(strategy)
    }
    
    private fun updateCompletionProviderStrategy(
        service: ZestInlineCompletionService, 
        strategy: ZestCompletionProvider.CompletionStrategy
    ) {
        // Use the public method we added to the service
        service.setCompletionStrategy(strategy)
    }
    
    companion object {
        private val STRATEGY_KEY = com.intellij.openapi.util.Key.create<ZestCompletionProvider.CompletionStrategy>("ZEST_COMPLETION_STRATEGY")
    }
}
