package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.zps.zest.completion.ZestCompletionProvider
import com.zps.zest.completion.ZestInlineCompletionService
import com.zps.zest.completion.context.ZestLeanContextCollector
import com.zps.zest.completion.prompt.ZestLeanPromptBuilder
import com.zps.zest.completion.parser.ZestLeanResponseParser
import com.zps.zest.ZestNotifications

/**
 * Test action to verify lean strategy components are working
 */
class ZestTestLeanStrategyAction : AnAction("Test Lean Strategy"), DumbAware {
    private val logger = Logger.getInstance(ZestTestLeanStrategyAction::class.java)
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getRequiredData(CommonDataKeys.PROJECT)
        val editor = e.getRequiredData(CommonDataKeys.EDITOR)
        
        try {
            logger.info("=== Testing Lean Strategy Components ===")
            
            // Test context collector
            val contextCollector = ZestLeanContextCollector(project)
            val offset = editor.caretModel.offset
            val leanContext = contextCollector.collectFullFileContext(editor, offset)
            
            logger.info("Context collected: ${leanContext.fileName} (${leanContext.language})")
            logger.info("Context type: ${leanContext.contextType}")
            logger.info("Cursor line: ${leanContext.cursorLine}")
            
            // Test prompt builder
            val promptBuilder = ZestLeanPromptBuilder()
            val prompt = promptBuilder.buildReasoningPrompt(leanContext)
            
            logger.info("Prompt generated, length: ${prompt.length}")
            
            // Test response parser
            val responseParser = ZestLeanResponseParser()
            val testResponse = """
<reasoning>
Looking at the cursor position in this ${leanContext.language} file, I can see that the user is at line ${leanContext.cursorLine}. 
The context suggests they want to add some code here. Based on the surrounding structure, 
I should provide an appropriate completion.
</reasoning>

<code>
${leanContext.fullContent.replace("[CURSOR]", "// TODO: Implement this method\n        return null;")}
</code>
            """.trimIndent()
            
            val reasoningResult = responseParser.parseReasoningResponse(
                testResponse,
                leanContext.fullContent,
                offset
            )
            
            logger.info("Response parsed successfully")
            logger.info("Has valid reasoning: ${reasoningResult.hasValidReasoning}")
            logger.info("Completion text: '${reasoningResult.completionText}'")
            logger.info("Confidence: ${reasoningResult.confidence}")
            
            // Test strategy switching
            val completionService = project.serviceOrNull<ZestInlineCompletionService>()
            if (completionService != null) {
                val currentStrategy = completionService.getCompletionStrategy()
                logger.info("Current strategy: $currentStrategy")
                
                // Switch to lean temporarily to test
                completionService.setCompletionStrategy(ZestCompletionProvider.CompletionStrategy.LEAN)
                logger.info("Switched to LEAN strategy")
                
                // Switch back
                completionService.setCompletionStrategy(currentStrategy)
                logger.info("Switched back to $currentStrategy")
            }
            
            // Show results
            val message = buildString {
                appendLine("✅ Lean Strategy Test Results:")
                appendLine("• Context Type: ${leanContext.contextType}")
                appendLine("• Prompt Length: ${prompt.length} chars")
                appendLine("• Valid Reasoning: ${reasoningResult.hasValidReasoning}")
                appendLine("• Completion: '${reasoningResult.completionText.take(50)}${if (reasoningResult.completionText.length > 50) "..." else ""}'")
                appendLine("• Confidence: ${(reasoningResult.confidence * 100).toInt()}%")
                appendLine()
                appendLine("View IDE logs for detailed output.")
            }
            
            ZestNotifications.showInfo(
                project,
                "Lean Strategy Test",
                message
            )
            
            logger.info("=== Lean Strategy Test Complete ===")
            
        } catch (e: Exception) {
            logger.error("Lean strategy test failed", e)
            ZestNotifications.showError(
                project,
                "Lean Strategy Test Failed",
                "Error: ${e.message}\n\nCheck IDE logs for details."
            )
        }
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }
}
