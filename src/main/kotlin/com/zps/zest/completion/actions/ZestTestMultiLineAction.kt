package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.zps.zest.completion.ZestCompletionProvider
import com.zps.zest.completion.ZestInlineCompletionService
import com.zps.zest.completion.data.ZestInlineCompletionItem
import com.zps.zest.completion.data.CompletionMetadata
import com.zps.zest.ZestNotifications
import java.util.*

/**
 * Test action to verify multi-line rendering works in LEAN mode
 */
class ZestTestMultiLineAction : AnAction("Test Multi-Line Display"), DumbAware {
    private val logger = Logger.getInstance(ZestTestMultiLineAction::class.java)
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getRequiredData(CommonDataKeys.PROJECT)
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val completionService = project.serviceOrNull<ZestInlineCompletionService>()
        
        if (completionService == null) {
            logger.warn("ZestInlineCompletionService not available")
            return
        }
        
        val offset = editor.caretModel.offset
        val currentStrategy = completionService.getCompletionStrategy()
        
        // Create a test multi-line completion
        val multiLineCompletion = when (currentStrategy) {
            ZestCompletionProvider.CompletionStrategy.SIMPLE -> {
                "return value; // SIMPLE shows first line only"
            }
            ZestCompletionProvider.CompletionStrategy.LEAN -> {
                """if (value == null) {
    throw new IllegalArgumentException("Value cannot be null");
}
return value.toString();"""
            }
        }
        
        val testCompletion = ZestInlineCompletionItem(
            insertText = multiLineCompletion,
            replaceRange = ZestInlineCompletionItem.Range(offset, offset),
            confidence = 0.95f,
            metadata = CompletionMetadata(
                model = "test-${currentStrategy.name.lowercase()}",
                tokens = multiLineCompletion.split("\\s+".toRegex()).size,
                latency = 0L,
                requestId = UUID.randomUUID().toString(),
                reasoning = "Test completion for multi-line display verification",
                contextType = "TEST",
                hasValidReasoning = true
            )
        )
        
        // Clear any existing completion first
        completionService.dismiss()
        
        // Show the test completion directly through the renderer
        val renderer = getRendererFromService(completionService)
        renderer?.show(editor, offset, testCompletion, currentStrategy) { context ->
            val message = buildString {
                appendLine("🧪 Multi-Line Display Test")
                appendLine()
                appendLine("Strategy: $currentStrategy")
                appendLine("Lines in completion: ${multiLineCompletion.lines().size}")
                appendLine("Inlays created: ${context.inlays.size}")
                appendLine()
                when (currentStrategy) {
                    ZestCompletionProvider.CompletionStrategy.SIMPLE -> {
                        appendLine("✓ SIMPLE should show: 1 line (inline)")
                        appendLine("Expected: First line only")
                    }
                    ZestCompletionProvider.CompletionStrategy.LEAN -> {
                        appendLine("✓ LEAN should show: ${multiLineCompletion.lines().size} lines")
                        appendLine("Expected: Multi-line with blocks")
                    }
                }
                appendLine()
                appendLine("Press TAB to accept, ESC to dismiss")
            }
            
            ZestNotifications.showInfo(
                project,
                "Multi-Line Test: $currentStrategy",
                message
            )
        }
        
        logger.info("Multi-line test displayed for strategy: $currentStrategy")
    }
    
    private fun getRendererFromService(service: ZestInlineCompletionService): com.zps.zest.completion.ZestInlineCompletionRenderer? {
        return try {
            val rendererField = service.javaClass.getDeclaredField("renderer")
            rendererField.isAccessible = true
            rendererField.get(service) as com.zps.zest.completion.ZestInlineCompletionRenderer
        } catch (e: Exception) {
            logger.warn("Failed to access renderer from service", e)
            null
        }
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
            val lineCount = when (currentStrategy) {
                ZestCompletionProvider.CompletionStrategy.SIMPLE -> "1"
                ZestCompletionProvider.CompletionStrategy.LEAN -> "3"
            }
            e.presentation.text = "Test Multi-Line ($lineCount lines in $currentStrategy)"
        }
    }
}
