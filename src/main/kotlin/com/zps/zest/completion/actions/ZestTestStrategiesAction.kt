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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Test action to validate both completion strategies
 */
class ZestTestStrategiesAction : AnAction("Test Completion Strategies"), DumbAware {
    private val logger = Logger.getInstance(ZestTestStrategiesAction::class.java)
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getRequiredData(CommonDataKeys.PROJECT)
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val completionService = project.serviceOrNull<ZestInlineCompletionService>()
        
        if (completionService == null) {
            logger.warn("ZestInlineCompletionService not available")
            return
        }
        
        val offset = editor.caretModel.offset
        
        logger.info("=== Testing Completion Strategies ===")
        ZestNotifications.showInfo(
            project,
            "Testing Completion Strategies",
            "Running tests for both SIMPLE and LEAN strategies at cursor position"
        )
        
        CoroutineScope(Dispatchers.IO).launch {
            testBothStrategies(project, completionService, editor, offset)
        }
    }
    
    private suspend fun testBothStrategies(
        project: com.intellij.openapi.project.Project,
        service: ZestInlineCompletionService,
        editor: com.intellij.openapi.editor.Editor,
        offset: Int
    ) {
        val results = mutableMapOf<String, TestResult>()
        
        // Test SIMPLE strategy
        try {
            logger.info("Testing SIMPLE strategy...")
            service.setCompletionStrategy(ZestCompletionProvider.CompletionStrategy.SIMPLE)
            
            val simpleStartTime = System.currentTimeMillis()
            
            // Trigger completion manually
            service.provideInlineCompletion(editor, offset, manually = true)
            
            // Wait a bit for completion
            kotlinx.coroutines.delay(3000)
            
            val simpleCompletion = service.getCurrentCompletion()
            val simpleTime = System.currentTimeMillis() - simpleStartTime
            
            results["SIMPLE"] = TestResult(
                success = simpleCompletion != null,
                completionText = simpleCompletion?.insertText ?: "No completion",
                timeMs = simpleTime,
                confidence = simpleCompletion?.confidence ?: 0.0f,
                reasoning = simpleCompletion?.metadata?.reasoning
            )
            
            logger.info("SIMPLE strategy result: ${results["SIMPLE"]}")
            
        } catch (e: Exception) {
            logger.error("SIMPLE strategy test failed", e)
            results["SIMPLE"] = TestResult(false, "Error: ${e.message}", 0, 0.0f, null)
        }
        
        // Clear current completion
        service.dismiss()
        kotlinx.coroutines.delay(500)
        
        // Test LEAN strategy
        try {
            logger.info("Testing LEAN strategy...")
            service.setCompletionStrategy(ZestCompletionProvider.CompletionStrategy.LEAN)
            
            val leanStartTime = System.currentTimeMillis()
            
            // Trigger completion manually
            service.provideInlineCompletion(editor, offset, manually = true)
            
            // Wait longer for lean completion (reasoning takes time)
            kotlinx.coroutines.delay(8000)
            
            val leanCompletion = service.getCurrentCompletion()
            val leanTime = System.currentTimeMillis() - leanStartTime
            
            results["LEAN"] = TestResult(
                success = leanCompletion != null,
                completionText = leanCompletion?.insertText ?: "No completion",
                timeMs = leanTime,
                confidence = leanCompletion?.confidence ?: 0.0f,
                reasoning = leanCompletion?.metadata?.reasoning
            )
            
            logger.info("LEAN strategy result: ${results["LEAN"]}")
            
        } catch (e: Exception) {
            logger.error("LEAN strategy test failed", e)
            results["LEAN"] = TestResult(false, "Error: ${e.message}", 0, 0.0f, null)
        }
        
        // Show results
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            showTestResults(project, results)
        }
    }
    
    private fun showTestResults(
        project: com.intellij.openapi.project.Project,
        results: Map<String, TestResult>
    ) {
        val message = buildString {
            appendLine("Completion Strategy Test Results:")
            appendLine()
            
            results.forEach { (strategy, result) ->
                appendLine("=== $strategy Strategy ===")
                appendLine("Success: ${if (result.success) "✅" else "❌"}")
                appendLine("Time: ${result.timeMs}ms")
                appendLine("Confidence: ${String.format("%.2f", result.confidence)}")
                appendLine("Completion: '${result.completionText.take(100)}${if (result.completionText.length > 100) "..." else ""}'")
                if (result.reasoning != null) {
                    appendLine("Has Reasoning: ✅ (${result.reasoning.length} chars)")
                } else {
                    appendLine("Has Reasoning: ❌")
                }
                appendLine()
            }
            
            // Comparison
            val simpleResult = results["SIMPLE"]
            val leanResult = results["LEAN"]
            
            if (simpleResult != null && leanResult != null) {
                appendLine("=== Comparison ===")
                val speedDiff = leanResult.timeMs - simpleResult.timeMs
                appendLine("Speed: LEAN is ${speedDiff}ms ${if (speedDiff > 0) "slower" else "faster"}")
                
                val confidenceDiff = leanResult.confidence - simpleResult.confidence
                appendLine("Confidence: LEAN is ${String.format("%.2f", confidenceDiff)} ${if (confidenceDiff > 0) "higher" else "lower"}")
                
                appendLine("Reasoning: LEAN ${if (leanResult.reasoning != null) "provides" else "lacks"} reasoning")
            }
        }
        
        ZestNotifications.showInfo(
            project,
            "Strategy Test Results",
            message
        )
        
        logger.info("Test results:\n$message")
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val completionService = project?.serviceOrNull<ZestInlineCompletionService>()
        
        e.presentation.isEnabledAndVisible = project != null && 
                                           editor != null && 
                                           completionService != null
    }
    
    private data class TestResult(
        val success: Boolean,
        val completionText: String,
        val timeMs: Long,
        val confidence: Float,
        val reasoning: String?
    )
}
