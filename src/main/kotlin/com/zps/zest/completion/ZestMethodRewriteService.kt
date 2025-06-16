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
import com.zps.zest.gdiff.EnhancedGDiff
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
    private val enhancedGDiff = EnhancedGDiff()
    private val inlineDiffRenderer = ZestInlineMethodDiffRenderer()
    
    // State management
    private var currentRewriteJob: Job? = null
    private var currentMethodContext: ZestMethodContextCollector.MethodContext? = null
    private var currentRewrittenMethod: String? = null
    private var currentDiffResult: GDiff.DiffResult? = null
    private var currentEnhancedDiffResult: EnhancedGDiff.EnhancedDiffResult? = null
    
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
                
                // Show loading notification
                withContext(Dispatchers.Main) {
                    ZestNotifications.showInfo(
                        project,
                        "Method Rewrite",
                        "Rewriting method '${methodContext.methodName}'..."
                    )
                }
                
                // Start method rewrite process
                currentRewriteJob = scope.launch {
                    performMethodRewrite(editor, methodContext, customInstruction)
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
        editor: Editor,
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
            
            //
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
            
            // Use enhanced AST diffing to calculate semantic changes for the method
            val enhancedDiffResult = calculateSemanticChanges(
                originalCode = methodContext.methodContent,
                rewrittenCode = parseResult.rewrittenMethod,
                language = methodContext.language
            )
            currentEnhancedDiffResult = enhancedDiffResult
            
            // Also maintain legacy diff for compatibility
            val diffResult = calculatePreciseChanges(
                originalCode = methodContext.methodContent,
                rewrittenCode = parseResult.rewrittenMethod
            )
            currentDiffResult = diffResult
            currentRewrittenMethod = parseResult.rewrittenMethod
            
            // Show inline diff in the editor
            withContext(Dispatchers.Main) {
                inlineDiffRenderer.show(
                    editor = editor,
                    methodContext = methodContext,
                    diffResult = diffResult,
                    rewrittenMethod = parseResult.rewrittenMethod,
                    onAccept = { acceptMethodRewriteInternal(editor) },
                    onReject = { cancelCurrentRewrite() }
                )
                
                // Show improvement summary with semantic analysis
                val textStats = diffResult.getStatistics()
                val semanticSummary = enhancedDiffResult.getSummary()
                val improvementMessage = buildString {
                    appendLine("🔧 Method Improvements:")
                    parseResult.improvements.forEach { improvement: String ->
                        appendLine("• $improvement")
                    }
                    appendLine()
                    appendLine("🧠 Semantic Analysis:")
                    appendLine("• Language: ${enhancedDiffResult.language}")
                    appendLine("• Diff Strategy: ${semanticSummary.strategy}")
                    appendLine("• Structural Similarity: ${(semanticSummary.structuralSimilarity * 100).toInt()}%")
                    if (semanticSummary.hasLogicChanges) {
                        appendLine("• ⚠️ Contains logic changes")
                    }
                    if (semanticSummary.hasStructuralChanges) {
                        appendLine("• 🏗️ Contains structural changes")
                    }
                    if (enhancedDiffResult.astDiff?.getMajorChanges()?.isNotEmpty() == true) {
                        appendLine("• 🔴 ${enhancedDiffResult.astDiff.getMajorChanges().size} major semantic changes")
                    }
                    appendLine()
                    appendLine("📊 Change Summary:")
                    appendLine("• ${textStats.additions} text additions")
                    appendLine("• ${textStats.deletions} text deletions") 
                    appendLine("• ${textStats.modifications} text modifications")
                    if (semanticSummary.semanticChanges > 0) {
                        appendLine("• ${semanticSummary.semanticChanges} semantic changes")
                    }
                    appendLine("• Confidence: ${(parseResult.confidence * 100).toInt()}%")
                    appendLine()
                    appendLine("Press TAB to accept, ESC to reject")
                }
                
                ZestNotifications.showInfo(
                    project,
                    "Method Rewrite Ready",
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
                inlineDiffRenderer.hide()
                ZestNotifications.showError(
                    project,
                    "Method Rewrite Failed",
                    "Failed to rewrite method: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Calculate precise changes using enhanced AST diffing for better semantic understanding
     */
    private fun calculateSemanticChanges(
        originalCode: String,
        rewrittenCode: String,
        language: String
    ): EnhancedGDiff.EnhancedDiffResult {
        return enhancedGDiff.calculateSemanticChanges(originalCode, rewrittenCode, language)
    }
    
    /**
     * Calculate precise changes using GDiff (legacy method maintained for compatibility)
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
     * Internal method to accept the current method rewrite and apply it using enhanced diffing
     */
    private fun acceptMethodRewriteInternal(editor: Editor) {
        val methodContext = currentMethodContext ?: return
        val rewrittenMethod = currentRewrittenMethod ?: return
        val enhancedDiffResult = currentEnhancedDiffResult
        val diffResult = currentDiffResult
        
        ApplicationManager.getApplication().invokeLater {
            try {
                WriteCommandAction.runWriteCommandAction(project) {
                    val document = editor.document
                    
                    if (enhancedDiffResult != null && enhancedDiffResult.hasAnyChanges()) {
                        // Apply changes using enhanced diffing with semantic understanding
                        logger.info("Applying enhanced semantic changes: ${enhancedDiffResult.getSummary()}")
                        
                        // Replace the entire method with the rewritten version
                        document.replaceString(
                            methodContext.methodStartOffset,
                            methodContext.methodEndOffset,
                            rewrittenMethod
                        )
                        
                    } else if (diffResult != null && diffResult.hasChanges()) {
                        // Fallback to traditional diff application
                        logger.info("Applying traditional diff changes: ${diffResult.getStatistics().totalChanges} changes")
                        
                        document.replaceString(
                            methodContext.methodStartOffset,
                            methodContext.methodEndOffset,
                            rewrittenMethod
                        )
                        
                    } else {
                        // Simple replacement if no diff available
                        document.replaceString(
                            methodContext.methodStartOffset,
                            methodContext.methodEndOffset,
                            rewrittenMethod
                        )
                    }
                    
                    logger.info("Applied method rewrite successfully")
                }
                
                // Hide the inline diff
                inlineDiffRenderer.hide()
                
                val message = if (enhancedDiffResult != null) {
                    val summary = enhancedDiffResult.getSummary()
                    "Method '${methodContext.methodName}' rewritten successfully with ${summary.semanticChanges} semantic changes and ${summary.textChanges} text changes"
                } else {
                    val stats = diffResult?.getStatistics()
                    if (stats != null) {
                        "Method '${methodContext.methodName}' rewritten successfully with ${stats.totalChanges} changes"
                    } else {
                        "Method '${methodContext.methodName}' has been successfully rewritten"
                    }
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
        inlineDiffRenderer.hide()
        cleanup()
    }
    
    /**
     * Check if a method rewrite operation is currently in progress
     */
    fun isRewriteInProgress(): Boolean {
        return currentRewriteJob?.isActive == true || inlineDiffRenderer.isActive()
    }
    
    /**
     * Accept the method rewrite (public method for tab acceptance)
     */
    fun acceptMethodRewrite(editor: Editor) {
        val methodContext = currentMethodContext ?: return
        val rewrittenMethod = currentRewrittenMethod ?: return
        val enhancedDiffResult = currentEnhancedDiffResult
        val diffResult = currentDiffResult
        
        ApplicationManager.getApplication().invokeLater {
            try {
                WriteCommandAction.runWriteCommandAction(project) {
                    val document = editor.document
                    
                    if (enhancedDiffResult != null && enhancedDiffResult.hasAnyChanges()) {
                        // Apply changes using enhanced diffing with semantic understanding
                        logger.info("Applying enhanced semantic changes: ${enhancedDiffResult.getSummary()}")
                        
                        // Replace the entire method with the rewritten version
                        document.replaceString(
                            methodContext.methodStartOffset,
                            methodContext.methodEndOffset,
                            rewrittenMethod
                        )
                        
                    } else if (diffResult != null && diffResult.hasChanges()) {
                        // Fallback to traditional diff application
                        logger.info("Applying traditional diff changes: ${diffResult.getStatistics().totalChanges} changes")
                        
                        document.replaceString(
                            methodContext.methodStartOffset,
                            methodContext.methodEndOffset,
                            rewrittenMethod
                        )
                        
                    } else {
                        // Simple replacement if no diff available
                        document.replaceString(
                            methodContext.methodStartOffset,
                            methodContext.methodEndOffset,
                            rewrittenMethod
                        )
                    }
                    
                    logger.info("Applied method rewrite successfully")
                }
                
                // Hide the inline diff
                inlineDiffRenderer.hide()
                
                val message = if (enhancedDiffResult != null) {
                    val summary = enhancedDiffResult.getSummary()
                    "Method '${methodContext.methodName}' rewritten successfully with ${summary.semanticChanges} semantic changes and ${summary.textChanges} text changes"
                } else {
                    val stats = diffResult?.getStatistics()
                    if (stats != null) {
                        "Method '${methodContext.methodName}' rewritten successfully with ${stats.totalChanges} changes"
                    } else {
                        "Method '${methodContext.methodName}' has been successfully rewritten"
                    }
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
        currentRewrittenMethod = null
        currentDiffResult = null
        currentEnhancedDiffResult = null
    }
    
    override fun dispose() {
        logger.info("Disposing ZestMethodRewriteService")
        scope.cancel()
        inlineDiffRenderer.hide()
        cleanup()
    }
    
    companion object {
        private const val METHOD_REWRITE_TIMEOUT_MS = 20000L // 20 seconds for method rewrites
        private const val METHOD_REWRITE_MAX_TOKENS = 1500 // Allow larger responses for methods
    }
}
