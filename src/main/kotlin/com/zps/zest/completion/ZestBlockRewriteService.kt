package com.zps.zest.completion

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.zps.zest.completion.context.ZestBlockContextCollector
import com.zps.zest.completion.prompt.ZestBlockRewritePromptBuilder
import com.zps.zest.completion.parser.ZestBlockRewriteResponseParser
import com.zps.zest.inlinechat.FloatingCodeWindow
import com.zps.zest.langchain4j.util.LLMService
import com.zps.zest.browser.utils.ChatboxUtilities
import com.zps.zest.ZestNotifications
import kotlinx.coroutines.*

/**
 * Service for managing block-level code rewrites with floating window previews
 */
@Service(Service.Level.PROJECT)
class ZestBlockRewriteService(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(ZestBlockRewriteService::class.java)
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
    
    private val blockContextCollector = ZestBlockContextCollector(project)
    private val promptBuilder = ZestBlockRewritePromptBuilder()
    private val responseParser = ZestBlockRewriteResponseParser()
    
    // State management
    private var currentFloatingWindow: FloatingCodeWindow? = null
    private var currentRewriteJob: Job? = null
    private var currentBlockContext: ZestBlockContextCollector.BlockContext? = null
    private var currentSuggestedCode: String? = null // Store the suggested code
    
    /**
     * Trigger a block rewrite at the cursor position
     */
    fun triggerBlockRewrite(editor: Editor, offset: Int, customInstruction: String? = null) {
        scope.launch {
            try {
                logger.info("Starting block rewrite at offset $offset")
                
                // Cancel any existing rewrite
                cancelCurrentRewrite()
                
                // Collect block context
                val blockContext = withContext(Dispatchers.Main) {
                    blockContextCollector.collectBlockContext(editor, offset)
                }
                currentBlockContext = blockContext
                
                // Show loading window immediately
                withContext(Dispatchers.Main) {
                    currentFloatingWindow = FloatingCodeWindow.createLoadingWindow(
                        project = project,
                        mainEditor = editor,
                        originalCode = blockContext.originalBlock,
                        onAccept = { acceptRewrite() },
                        onReject = { cancelCurrentRewrite() }
                    )
                    currentFloatingWindow?.show()
                }
                
                // Start rewrite process
                currentRewriteJob = scope.launch {
                    performBlockRewrite(blockContext, customInstruction)
                }
                
            } catch (e: Exception) {
                logger.error("Failed to trigger block rewrite", e)
                withContext(Dispatchers.Main) {
                    ZestNotifications.showError(
                        project,
                        "Block Rewrite Error",
                        "Failed to start block rewrite: ${e.message}"
                    )
                }
            }
        }
    }
    
    /**
     * Perform the actual block rewrite operation
     */
    private suspend fun performBlockRewrite(
        blockContext: ZestBlockContextCollector.BlockContext,
        customInstruction: String?
    ) {
        try {
            logger.info("Performing block rewrite for ${blockContext.blockType}")
            
            // Build the prompt
            val prompt = if (customInstruction != null) {
                promptBuilder.buildCustomRewritePrompt(blockContext, customInstruction)
            } else {
                promptBuilder.buildBlockRewritePrompt(blockContext)
            }
            
            logger.debug("Generated prompt length: ${prompt.length}")
            
            // Query the LLM
            val startTime = System.currentTimeMillis()
            val response = withTimeoutOrNull(REWRITE_TIMEOUT_MS) {
                val queryParams = LLMService.LLMQueryParams(prompt)
                    .withModel("local-model-mini") // Use full model for rewrites
                    .withMaxTokens(REWRITE_MAX_TOKENS)
                    .withTemperature(0.8) // Slightly higher for creativity
                    .withStopSequences(getRewriteStopSequences())
                
                llmService.queryWithParams(queryParams, ChatboxUtilities.EnumUsage.INLINE_COMPLETION)
            }
            val responseTime = System.currentTimeMillis() - startTime
            
            if (response == null) {
                throw Exception("LLM request timed out after ${REWRITE_TIMEOUT_MS}ms")
            }
            
            logger.info("Received LLM response in ${responseTime}ms")
            
            // Parse the response
            val parseResult = responseParser.parseBlockRewriteResponse(
                response = response,
                originalBlock = blockContext.originalBlock,
                blockType = blockContext.blockType,
                language = blockContext.language
            )
            
            if (!parseResult.isValid) {
                logger.warn("Invalid rewrite response: ${parseResult.issues}")
                throw Exception("Generated code is invalid: ${parseResult.issues.joinToString(", ")}")
            }
            
            // Update the floating window with the rewritten code
            withContext(Dispatchers.Main) {
                currentSuggestedCode = parseResult.rewrittenCode // Store the suggested code
                
                currentFloatingWindow?.updateContent(
                    newSuggestedCode = parseResult.rewrittenCode,
                    isValid = parseResult.isValid
                )
                
                if (parseResult.issues.isNotEmpty()) {
                    val warningMessage = "Potential issues detected: ${parseResult.issues.joinToString(", ")}"
                    currentFloatingWindow?.showWarning(warningMessage)
                }
            }
            
            logger.info("Block rewrite completed successfully (confidence: ${parseResult.confidence})")
            
        } catch (e: CancellationException) {
            logger.debug("Block rewrite was cancelled")
            throw e
        } catch (e: Exception) {
            logger.error("Block rewrite failed", e)
            
            withContext(Dispatchers.Main) {
                currentFloatingWindow?.hide()
                ZestNotifications.showError(
                    project,
                    "Block Rewrite Failed",
                    "Failed to rewrite code block: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Accept the current rewrite and apply it to the editor
     */
    private fun acceptRewrite() {
        val blockContext = currentBlockContext ?: return
        val floatingWindow = currentFloatingWindow ?: return
        
        ApplicationManager.getApplication().invokeLater {
            try {
                // Get the rewritten code from the floating window
                // Note: This would need to be accessible from the FloatingCodeWindow
                // For now, we'll need to modify FloatingCodeWindow to expose this
                
                WriteCommandAction.runWriteCommandAction(project) {
                    // Apply the rewrite to the editor
                    // Replace the original block with the rewritten code
                    val editor = getCurrentEditor() ?: return@runWriteCommandAction
                    val document = editor.document
                    
                    document.replaceString(
                        blockContext.blockStartOffset,
                        blockContext.blockEndOffset,
                        getSuggestedCodeFromWindow(floatingWindow) ?: return@runWriteCommandAction
                    )
                    
                    logger.info("Applied block rewrite successfully")
                }
                
                // Close the floating window
                floatingWindow.hide()
                
                ZestNotifications.showInfo(
                    project,
                    "Block Rewrite Applied",
                    "Code block has been successfully rewritten"
                )
                
            } catch (e: Exception) {
                logger.error("Failed to apply block rewrite", e)
                ZestNotifications.showError(
                    project,
                    "Apply Rewrite Failed",
                    "Failed to apply the rewritten code: ${e.message}"
                )
            } finally {
                cleanup()
            }
        }
    }
    
    /**
     * Cancel the current rewrite operation
     */
    fun cancelCurrentRewrite() {
        currentRewriteJob?.cancel()
        currentFloatingWindow?.hide()
        cleanup()
    }
    
    /**
     * Check if a rewrite operation is currently in progress
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
     * Extract suggested code from floating window
     */
    private fun getSuggestedCodeFromWindow(window: FloatingCodeWindow): String? {
        return currentSuggestedCode
    }
    
    /**
     * Get stop sequences for rewrite operations
     */
    private fun getRewriteStopSequences(): List<String> {
        return listOf(
            "</code>",
            "<|endoftext|>",
            "<|end|>",
            "# End of code",
            "```",
            "---",
            "Explanation:",
            "Note:",
            "Summary:"
        )
    }
    
    /**
     * Clean up resources
     */
    private fun cleanup() {
        currentRewriteJob = null
        currentBlockContext = null
        currentFloatingWindow = null
        currentSuggestedCode = null
    }
    
    override fun dispose() {
        logger.info("Disposing ZestBlockRewriteService")
        scope.cancel()
        currentFloatingWindow?.hide()
        cleanup()
    }
    
    companion object {
        private const val REWRITE_TIMEOUT_MS = 30000L // 30 seconds for complex rewrites
        private const val REWRITE_MAX_TOKENS = 2000 // Allow larger responses for rewrites
    }
}
