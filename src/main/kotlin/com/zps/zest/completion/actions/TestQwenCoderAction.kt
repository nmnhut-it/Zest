package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.zps.zest.completion.QwenCoderConfiguration
import com.zps.zest.completion.ZestCompletionProvider
import com.zps.zest.completion.data.CompletionContext
import com.zps.zest.ZestNotifications
import kotlinx.coroutines.runBlocking

/**
 * Test action to verify Qwen 2.5 Coder integration
 */
class TestQwenCoderAction : AnAction("Test Qwen 2.5 Coder Integration"), DumbAware {
    private val logger = Logger.getInstance(TestQwenCoderAction::class.java)
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        
        logger.info("=== Testing Qwen 2.5 Coder Integration ===")
        
        try {
            // 1. Validate configuration
            val configStatus = QwenCoderConfiguration.validateConfiguration()
            logger.info("Configuration validation: ${if (configStatus.isValid) "PASS" else "FAIL"}")
            
            if (configStatus.issues.isNotEmpty()) {
                logger.warn("Configuration issues: ${configStatus.issues}")
                ZestNotifications.showWarning(
                    project,
                    "Qwen Configuration Issues",
                    "Issues found: ${configStatus.issues.joinToString(", ")}"
                )
                return
            }
            
            // 2. Show recommended configuration
            val recommendedConfig = QwenCoderConfiguration.getRecommendedConfiguration()
            logger.info("Recommended config: $recommendedConfig")
            
            // 3. Generate test prompt
            val testPrompt = QwenCoderConfiguration.generateTestPrompt()
            logger.info("Test prompt generated: ${testPrompt.take(100)}...")
            
            // 4. Test completion provider
            val completionProvider = ZestCompletionProvider(project)
            val context = CompletionContext.from(editor, editor.caretModel.offset, manually = true)
            
            logger.info("Testing completion provider...")
            runBlocking {
                try {
                    val result = completionProvider.requestCompletion(context)
                    
                    if (result != null && result.isNotEmpty()) {
                        val completion = result.firstItem()!!
                        logger.info("Completion received: '${completion.insertText}'")
                        
                        // Validate response
                        val isValid = QwenCoderConfiguration.isValidResponse(completion.insertText)
                        logger.info("Response validation: ${if (isValid) "PASS" else "FAIL"}")
                        
                        ZestNotifications.showInfo(
                            project,
                            "Qwen 2.5 Coder Test",
                            buildString {
                                appendLine("✅ Configuration: ${if (configStatus.isValid) "Valid" else "Issues found"}")
                                appendLine("✅ Completion Provider: Working")
                                appendLine("✅ Response: ${if (isValid) "Valid" else "May have issues"}")
                                appendLine("📝 Completion: '${completion.insertText.take(50)}${if (completion.insertText.length > 50) "..." else ""}'")
                                if (configStatus.warnings.isNotEmpty()) {
                                    appendLine("⚠️ Warnings: ${configStatus.warnings.size}")
                                }
                            }
                        )
                    } else {
                        logger.warn("No completion received")
                        ZestNotifications.showWarning(
                            project,
                            "Qwen 2.5 Coder Test",
                            "No completion received. Check model configuration and connection."
                        )
                    }
                } catch (e: Exception) {
                    logger.error("Completion test failed", e)
                    ZestNotifications.showError(
                        project,
                        "Qwen 2.5 Coder Test Failed",
                        "Error: ${e.message}"
                    )
                }
            }
            
            // 5. Display recommendations
            if (configStatus.recommendations.isNotEmpty()) {
                val recommendations = configStatus.recommendations.joinToString("\n• ", "• ")
                logger.info("Recommendations:\n$recommendations")
            }
            
        } catch (e: Exception) {
            logger.error("Test action failed", e)
            ZestNotifications.showError(
                project,
                "Qwen Test Error",
                "Failed to run test: ${e.message}"
            )
        }
        
        logger.info("=== Qwen 2.5 Coder Test Complete ===")
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }
}
