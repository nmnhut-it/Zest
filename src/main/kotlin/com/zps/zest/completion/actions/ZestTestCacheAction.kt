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
 * Test action to demonstrate line-by-line completion acceptance (now default in LEAN mode)
 */
class ZestTestCacheAction : AnAction("Test Line-by-Line Completion"), DumbAware {
    private val logger = Logger.getInstance(ZestTestCacheAction::class.java)
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getRequiredData(CommonDataKeys.PROJECT)
        val editor = e.getRequiredData(CommonDataKeys.EDITOR)
        
        try {
            logger.info("=== Testing Line-by-Line Completion ===")
            
            val completionService = project.serviceOrNull<ZestInlineCompletionService>()
            if (completionService == null) {
                ZestNotifications.showError(project, "Test Failed", "ZestInlineCompletionService not available")
                return
            }
            
            val currentStrategy = completionService.getCompletionStrategy()
            logger.info("Current strategy: $currentStrategy")
            
            // Test with LEAN strategy (default - should do line-by-line acceptance)
            logger.info("Testing LEAN strategy (default) with line-by-line acceptance...")
            completionService.setCompletionStrategy(ZestCompletionProvider.CompletionStrategy.LEAN)
            
            // Trigger a completion request manually
            val offset = editor.caretModel.offset
            logger.info("Requesting completion at offset: $offset")
            completionService.provideInlineCompletion(editor, offset, manually = true)
            
            // Wait a moment for completion
            Thread.sleep(3000)
            
            val cacheStats1 = completionService.getCacheStats()
            logger.info("Cache stats after LEAN completion: $cacheStats1")
            
            // Test with SIMPLE strategy (should show first line only, traditional full acceptance)
            logger.info("Testing SIMPLE strategy...")
            completionService.setCompletionStrategy(ZestCompletionProvider.CompletionStrategy.SIMPLE)
            
            // Request again at same position
            completionService.provideInlineCompletion(editor, offset, manually = true)
            
            // Wait a moment for completion
            Thread.sleep(3000)
            
            val cacheStats2 = completionService.getCacheStats()
            logger.info("Cache stats after SIMPLE completion: $cacheStats2")
            
            // Restore original strategy
            completionService.setCompletionStrategy(currentStrategy)
            logger.info("Restored strategy to: $currentStrategy")
            
            // Show results
            val message = buildString {
                appendLine("✅ **Line-by-Line Completion Test Results**")
                appendLine()
                appendLine("🔄 **How It Works (LEAN Strategy - Default):**")
                appendLine("1. **Type** → Full multi-line completion shown")
                appendLine("2. **Tab** → Accept FIRST line only")
                appendLine("3. **Automatically** → Remaining lines shown as new completion")
                appendLine("4. **Tab** → Accept NEXT line only")
                appendLine("5. **Continue** → Until all lines accepted")
                appendLine()
                appendLine("🎯 **Strategy Behavior:**")
                appendLine("• **LEAN (Default)**: Shows full completion + line-by-line acceptance")
                appendLine("• **SIMPLE**: Shows first line only + traditional full acceptance")
                appendLine("• **METHOD_REWRITE**: Floating window, no inline")
                appendLine()
                appendLine("📊 **Test Results:**")
                appendLine("• Cache after LEAN: $cacheStats1")
                appendLine("• Cache after SIMPLE: $cacheStats2")
                appendLine()
                appendLine("⌨️ **Key Bindings:**")
                appendLine("• **Tab**: Line-by-line accept (LEAN) / Full accept (SIMPLE)")
                appendLine("• **Ctrl+Tab**: Accept next line (traditional)")
                appendLine("• **Ctrl+Enter**: Accept full completion")
                appendLine("• **Ctrl+Right**: Accept next word")
                appendLine("• **Escape**: Dismiss completion")
                appendLine()
                appendLine("💡 **Ready to use!** LEAN mode is default with line-by-line acceptance.")
                appendLine("Just type code and press Tab to see line-by-line acceptance in action!")
                appendLine()
                appendLine("View IDE logs for detailed activity.")
            }
            
            ZestNotifications.showInfo(
                project,
                "Line-by-Line Completion Test",
                message
            )
            
            logger.info("=== Line-by-Line Completion Test Complete ===")
            
        } catch (e: Exception) {
            logger.error("Line-by-line completion test failed", e)
            ZestNotifications.showError(
                project,
                "Test Failed",
                "Error: ${e.message}\n\nCheck IDE logs for details."
            )
        }
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }
}
