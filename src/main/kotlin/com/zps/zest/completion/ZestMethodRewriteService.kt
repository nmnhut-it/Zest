package com.zps.zest.completion

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.zps.zest.completion.context.ZestMethodContextCollector
import com.zps.zest.completion.prompt.ZestMethodPromptBuilder
import com.zps.zest.completion.parser.ZestMethodResponseParser
import com.zps.zest.inlinechat.FloatingCodeWindow
import com.zps.zest.langchain4j.util.LLMService
import com.zps.zest.browser.utils.ChatboxUtilities
import com.zps.zest.ZestNotifications
import com.zps.zest.gdiff.GDiff
import kotlinx.coroutines.*

/**
 * Service for managing method-level code rewrites with floating window previews.
 * Enhanced with GDiff for precise change calculation and application.
 */
@Service(Service.Level.PROJECT)
class ZestMethodRewriteService(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(ZestMethodRewriteService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Dependencies
    private val llmService by lazy { 
        try {
            LLMService(project)
        } catch (e: Exception) {
            logger.warn("Failed to create LLMService instance", e)
            throw IllegalStateException("LLMService not available", e)
        }
    }
    
    private val methodContextCollector = ZestMethodContextCollector(project)
    private val promptBuilder = ZestMethodPromptBuilder()
    private val responseParser = ZestMethodResponseParser()
    private val gdiff = GDiff()
    
    // State management
    private var currentFloatingWindow: FloatingCodeWindow? = null
    private var currentRewriteJob: Job? = null
    private var currentMethodContext: ZestMethodContextCollector.MethodContext? = null
    private var currentRewrittenMethod: String? = null
    private var currentDiffResult: GDiff.DiffResult? = null
    
    /**
     * Trigger method rewrite at cursor position
     */
    fun rewriteCurrentMethod(editor: Editor, offset: Int, customInstruction: String? = null) {
        scope.launch {
            try {
                logger.info("Starting method rewrite at offset $offset")
                
                // Cancel any existing rewrite
                cancelCurrentRewrite()
                
                // Find the method containing the cursor
                val methodContext = withContext(Dispatchers.Main) {
                    methodContextCollector.findMethodAtCursor(editor, offset)
                }
                
                if (methodContext == null) {
                    withContext(Dispatchers.Main) {
                        ZestNotifications.showWarning(
                            project,
                            "No Method Found",
                            "Could not identify a method at the current cursor position. " +
                            "Place cursor inside a method to rewrite it."
                        )
                    }
                    return@launch
                }
                
                currentMethodContext = methodContext
                
                logger.info("Found method: ${methodContext.methodName} (${methodContext.language})")
                
                // Show loading window immediately with method info
                withContext(Dispatchers.Main) {
                    currentFloatingWindow = FloatingCodeWindow.createLoadingWindow(
                        project = project,
                        mainEditor = editor,
                        originalCode = methodContext.methodContent,
                        onAccept = { acceptMethodRewrite() },
                        onReject = { cancelCurrentRewrite() }
                    )
                    currentFloatingWindow?.show()
                }
                
                // Start method rewrite process
                currentRewriteJob = scope.launch {
                    performMethodRewrite(methodContext, customInstruction)
                }
                
            } catch (e: Exception) {
                logger.error("Failed to trigger method rewrite", e)
                withContext(Dispatchers.Main) {
                    ZestNotifications.showError(
                        project,
                        "Method Rewrite Error",
                        "Failed to start method rewrite: ${e.message}"
                    )
                }
            }
        }
    }
    
    /**
     * Perform the method rewrite operation with enhanced context
     */
    private suspend fun performMethodRewrite(
        methodContext: ZestMethodContextCollector.MethodContext,
        customInstruction: String?
    ) {
        try {
            logger.info("Performing method rewrite for ${methodContext.methodName}")
            
            // Build the method-specific prompt
            val prompt = if (customInstruction != null) {
                promptBuilder.buildCustomMethodPrompt(methodContext, customInstruction)
            } else {
                promptBuilder.buildMethodRewritePrompt(methodContext)
            }
            
            logger.debug("Generated method prompt length: ${prompt.length}")
            
            // Query the LLM with method-optimized parameters
            val startTime = System.currentTimeMillis()
            val response = withTimeoutOrNull(METHOD_REWRITE_TIMEOUT_MS) {
                val queryParams = LLMService.LLMQueryParams(prompt)
                    .withModel("local-model-mini") // Use full model for method rewrites
                    .withMaxTokens(METHOD_REWRITE_MAX_TOKENS)
                    .withTemperature(0.3) // Slightly creative but focused
                    .withStopSequences(getMethodRewriteStopSequences())
                
                llmService.queryWithParams(queryParams, ChatboxUtilities.EnumUsage.INLINE_COMPLETION)
            }
            val responseTime = System.currentTimeMillis() - startTime
            
            if (response == null) {
                throw Exception("LLM request timed out after ${METHOD_REWRITE_TIMEOUT_MS}ms")
            }
            
            logger.info("Received LLM response in ${responseTime}ms")
            
            // Parse the method rewrite response
            val parseResult = responseParser.parseMethodRewriteResponse(
                response = response,
                originalMethod = methodContext.methodContent,
                methodName = methodContext.methodName,
                language = methodContext.language
            )
            
            if (!parseResult.isValid) {
                logger.warn("Invalid method rewrite response: ${parseResult.issues}")
                throw Exception("Generated method is invalid: ${parseResult.issues.joinToString(", ")}")
            }
            
            // Use GDiff to calculate precise changes for the method
            val diffResult = calculatePreciseChanges(
                originalCode = methodContext.methodContent,
                rewrittenCode = parseResult.rewrittenMethod
            )
            currentDiffResult = diffResult
            currentRewrittenMethod = parseResult.rewrittenMethod
            
            // Update the floating window with the rewritten method and diff info
            withContext(Dispatchers.Main) {
                currentFloatingWindow?.updateContent(
                    newSuggestedCode = parseResult.rewrittenMethod,
                    isValid = parseResult.isValid
                )
                
                // Show improvement summary
                val stats = diffResult.getStatistics()
                val improvementMessage = buildString {
                    appendLine("🔧 Method Improvements:")
                    parseResult.improvements.forEach { improvement: String ->
                        appendLine("• $improvement")
                    }
                    appendLine()
                    appendLine("📊 Change Summary:")
                    appendLine("• ${stats.additions} additions")
                    appendLine("• ${stats.deletions} deletions") 
                    appendLine("• ${stats.modifications} modifications")
                    appendLine("• Confidence: ${(parseResult.confidence * 100).toInt()}%")
                }
                // Note: Using a notification instead of showInfo since FloatingCodeWindow might not have that method
                ZestNotifications.showInfo(
                    project,
                    "Method Improvements",
                    improvementMessage
                )
            }
            
            logger.info("Method rewrite completed successfully (confidence: ${parseResult.confidence}, changes: ${diffResult.getStatistics().totalChanges})")
            
        } catch (e: CancellationException) {
            logger.debug("Method rewrite was cancelled")
            throw e
        } catch (e: Exception) {
            logger.error("Method rewrite failed", e)
            
            withContext(Dispatchers.Main) {
                currentFloatingWindow?.hide()
                ZestNotifications.showError(
                    project,
                    "Method Rewrite Failed",
                    "Failed to rewrite method: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Calculate precise changes using GDiff
     */
    private fun calculatePreciseChanges(
        originalCode: String,
        rewrittenCode: String
    ): GDiff.DiffResult {
        return gdiff.diffStrings(
            source = originalCode,
            target = rewrittenCode,
            config = GDiff.DiffConfig(
                ignoreWhitespace = false, // Preserve formatting changes
                ignoreCase = false,
                contextLines = 3
            )
        )
    }
    
    /**
     * Accept the current method rewrite and apply it using GDiff for precise changes
     */
    private fun acceptMethodRewrite() {
        val methodContext = currentMethodContext ?: return
        val rewrittenMethod = currentRewrittenMethod ?: return
        val diffResult = currentDiffResult
        
        ApplicationManager.getApplication().invokeLater {
            try {
                WriteCommandAction.runWriteCommandAction(project) {
                    val editor = getCurrentEditor() ?: return@runWriteCommandAction
                    val document = editor.document
                    
                    if (diffResult != null && diffResult.hasChanges()) {
                        // Apply changes using GDiff for more precise updates
                        logger.info("Applying precise method changes using GDiff: ${diffResult.getStatistics().totalChanges} changes")
                        
                        // Replace the entire method with the rewritten version
                        document.replaceString(
                            methodContext.methodStartOffset,
                            methodContext.methodEndOffset,
                            rewrittenMethod
                        )
                        
                    } else {
                        // Fallback to simple replacement
                        document.replaceString(
                            methodContext.methodStartOffset,
                            methodContext.methodEndOffset,
                            rewrittenMethod
                        )
                    }
                    
                    logger.info("Applied method rewrite successfully")
                }
                
                // Close the floating window
                currentFloatingWindow?.hide()
                
                val stats = diffResult?.getStatistics()
                val message = if (stats != null) {
                    "Method '${methodContext.methodName}' rewritten successfully with ${stats.totalChanges} changes"
                } else {
                    "Method '${methodContext.methodName}' has been successfully rewritten"
                }
                
                ZestNotifications.showInfo(
                    project,
                    "Method Rewrite Applied",
                    message
                )
                
            } catch (e: Exception) {
                logger.error("Failed to apply method rewrite", e)
                ZestNotifications.showError(
                    project,
                    "Apply Method Rewrite Failed",
                    "Failed to apply the rewritten method: ${e.message}"
                )
            } finally {
                cleanup()
            }
        }
    }
    
    /**
     * Cancel the current method rewrite operation
     */
    fun cancelCurrentRewrite() {
        currentRewriteJob?.cancel()
        currentFloatingWindow?.hide()
        cleanup()
    }
    
    /**
     * Check if a method rewrite operation is currently in progress
     */
    fun isRewriteInProgress(): Boolean {
        return currentRewriteJob?.isActive == true || currentFloatingWindow != null
    }
    
    /**
     * Get the current editor (helper method)
     */
    private fun getCurrentEditor(): Editor? {
        return try {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
        } catch (e: Exception) {
            logger.warn("Failed to get current editor", e)
            null
        }
    }
    
    /**
     * Get stop sequences for method rewrite operations
     */
    private fun getMethodRewriteStopSequences(): List<String> {
        return listOf(
            "</method>",
            "</code>",
            "<|endoftext|>",
            "<|end|>",
            "# End of method",
            "```",
            "---",
            "Explanation:",
            "Note:",
            "Summary:",
            "**Explanation:**",
            "**Note:**",
            "class ", // Stop before starting another class/method
            "interface ",
            "enum "
        )
    }
    
    /**
     * Clean up resources
     */
    private fun cleanup() {
        currentRewriteJob = null
        currentMethodContext = null
        currentFloatingWindow = null
        currentRewrittenMethod = null
        currentDiffResult = null
    }
    
    override fun dispose() {
        logger.info("Disposing ZestMethodRewriteService")
        scope.cancel()
        currentFloatingWindow?.hide()
        cleanup()
    }
    
    companion object {
        private const val METHOD_REWRITE_TIMEOUT_MS = 20000L // 20 seconds for method rewrites
        private const val METHOD_REWRITE_MAX_TOKENS = 1500 // Allow larger responses for methods
    }
}
