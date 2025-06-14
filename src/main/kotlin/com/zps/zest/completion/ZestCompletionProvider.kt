package com.zps.zest.completion

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.zps.zest.completion.data.CompletionContext
import com.zps.zest.completion.data.ZestInlineCompletionList
import com.zps.zest.completion.data.ZestInlineCompletionItem
import com.zps.zest.completion.data.CompletionMetadata
import com.zps.zest.completion.context.ZestSimpleContextCollector
import com.zps.zest.completion.context.ZestLeanContextCollector
import com.zps.zest.completion.prompt.ZestSimplePromptBuilder
import com.zps.zest.completion.prompt.ZestLeanPromptBuilder
import com.zps.zest.completion.parser.ZestSimpleResponseParser
import com.zps.zest.completion.parser.ZestLeanResponseParser
import com.zps.zest.langchain4j.util.LLMService
import com.zps.zest.browser.utils.ChatboxUtilities
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

/**
 * Completion provider with multiple strategies (A/B testing)
 * - SIMPLE: Basic prefix/suffix context (original)
 * - LEAN: Full file context with reasoning prompts (new)
 */
class ZestCompletionProvider(private val project: Project) {
    private val logger = Logger.getInstance(ZestCompletionProvider::class.java)
    private val llmService by lazy { 
        try {
            LLMService(project)
        } catch (e: Exception) {
            logger.warn("Failed to create LLMService instance", e)
            throw IllegalStateException("LLMService not available", e)
        }
    }
    
    // Strategy components
    private val simpleContextCollector = ZestSimpleContextCollector()
    private val simplePromptBuilder = ZestSimplePromptBuilder()
    private val simpleResponseParser = ZestSimpleResponseParser()
    
    // Lean strategy components
    private val leanContextCollector = ZestLeanContextCollector(project)
    private val leanPromptBuilder = ZestLeanPromptBuilder()
    private val leanResponseParser = ZestLeanResponseParser()
    
    // Configuration
    var strategy: CompletionStrategy = CompletionStrategy.SIMPLE
        private set
    
    enum class CompletionStrategy {
        SIMPLE,  // Original FIM-based approach
        LEAN     // Full-file with reasoning approach
    }
    
    /**
     * Switch completion strategy for A/B testing
     */
    fun setStrategy(newStrategy: CompletionStrategy) {
        logger.info("Switching completion strategy from $strategy to $newStrategy")
        strategy = newStrategy
    }
    
    suspend fun requestCompletion(context: CompletionContext): ZestInlineCompletionList? {
        return when (strategy) {
            CompletionStrategy.SIMPLE -> requestSimpleCompletion(context)
            CompletionStrategy.LEAN -> requestLeanCompletion(context)
        }
    }
    
    /**
     * Original simple completion strategy
     */
    private suspend fun requestSimpleCompletion(context: CompletionContext): ZestInlineCompletionList? {
        return try {
            logger.debug("Requesting simple completion for ${context.fileName} at offset ${context.offset}")
            
            val startTime = System.currentTimeMillis()
            
            // Get current editor and document text on EDT
            val (editor, documentText) = withContext(Dispatchers.Main) {
                val ed = FileEditorManager.getInstance(project).selectedTextEditor
                if (ed != null) {
                    Pair(ed, ed.document.text)
                } else {
                    Pair(null, "")
                }
            }
            
            if (editor == null) {
                logger.debug("No active editor found")
                return null
            }
            
            // Collect simple context (thread-safe)
            val simpleContext = simpleContextCollector.collectContext(editor, context.offset)
            
            // Build prompt (thread-safe)
            val prompt = simplePromptBuilder.buildCompletionPrompt(simpleContext)
            logger.debug("Prompt built, length: ${prompt.length}")
            
            // Query LLM with timeout (background thread)
            val llmStartTime = System.currentTimeMillis()
            val response = withTimeoutOrNull(COMPLETION_TIMEOUT_MS) {
                val queryParams = LLMService.LLMQueryParams(prompt)
                    .withModel("local-model-mini")
                    .withMaxTokens(MAX_COMPLETION_TOKENS)
                    .withTemperature(0.1) // Low temperature for more deterministic completions
                    .withStopSequences(getStopSequences()) // Add stop sequences for Qwen FIM
                
                llmService.queryWithParams(queryParams, ChatboxUtilities.EnumUsage.INLINE_COMPLETION)
            }
            val llmTime = System.currentTimeMillis() - llmStartTime
            
            if (response == null) {
                logger.debug("Completion request timed out")
                return null
            }
            
            // Parse response with overlap detection (thread-safe, uses captured documentText)
            val cleanedCompletion = simpleResponseParser.parseResponseWithOverlapDetection(
                response, 
                documentText, 
                context.offset,
                strategy = CompletionStrategy.SIMPLE
            )
            
            if (cleanedCompletion.isBlank()) {
                logger.debug("No valid completion after parsing")
                return null
            }
            
            val totalTime = System.currentTimeMillis() - startTime
            
            // Create completion item with original response stored for re-processing
            val item = ZestInlineCompletionItem(
                insertText = cleanedCompletion,
                replaceRange = ZestInlineCompletionItem.Range(
                    start = context.offset,
                    end = context.offset
                ),
                confidence = calculateConfidence(cleanedCompletion),
                metadata = CompletionMetadata(
                    model = "zest-llm-simple",
                    tokens = cleanedCompletion.split("\\s+".toRegex()).size,
                    latency = totalTime,
                    requestId = UUID.randomUUID().toString(),
                    reasoning = response // Store original response for re-processing overlaps
                )
            )
            
            logger.info("Simple completion completed in ${totalTime}ms (llm=${llmTime}ms)")
            ZestInlineCompletionList.single(item)
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            logger.debug("Completion request was cancelled")
            throw e
        } catch (e: Exception) {
            logger.warn("Simple completion failed", e)
            null
        }
    }
    
    /**
     * New lean completion strategy with full file context and reasoning
     */
    private suspend fun requestLeanCompletion(context: CompletionContext): ZestInlineCompletionList? {
        return try {
            logger.debug("Requesting lean completion for ${context.fileName} at offset ${context.offset}")
            
            val startTime = System.currentTimeMillis()
            
            // Get current editor and full document text on EDT
            val (editor, documentText) = withContext(Dispatchers.Main) {
                val ed = FileEditorManager.getInstance(project).selectedTextEditor
                if (ed != null) {
                    Pair(ed, ed.document.text)
                } else {
                    Pair(null, "")
                }
            }
            
            if (editor == null) {
                logger.debug("No active editor found")
                return null
            }
            
            // Collect lean context (full file with cursor marker)
            val leanContext = leanContextCollector.collectFullFileContext(editor, context.offset)
            
            // Build reasoning prompt
            val prompt = leanPromptBuilder.buildReasoningPrompt(leanContext)
            logger.debug("Lean prompt built, length: ${prompt.length}")
            
            // Query LLM with higher timeout for reasoning
            val llmStartTime = System.currentTimeMillis()
            val response = withTimeoutOrNull(LEAN_COMPLETION_TIMEOUT_MS) {
                val queryParams = LLMService.LLMQueryParams(prompt)
                    .withModel("local-model") // Use full model for reasoning
                    .withMaxTokens(LEAN_MAX_COMPLETION_TOKENS) // Limit tokens to control response length
                    .withTemperature(0.2) // Slightly higher for creative reasoning
                    .withStopSequences(getLeanStopSequences())
                
                llmService.queryWithParams(queryParams, ChatboxUtilities.EnumUsage.INLINE_COMPLETION)
            }
            val llmTime = System.currentTimeMillis() - llmStartTime
            
            if (response == null) {
                logger.debug("Lean completion request timed out")
                return null
            }
            
            // Parse response with diff-based extraction
            val reasoningResult = leanResponseParser.parseReasoningResponse(
                response, 
                documentText, 
                context.offset
            )
            
            if (reasoningResult.completionText.isBlank()) {
                logger.debug("No valid completion after lean parsing")
                return null
            }
            
            val totalTime = System.currentTimeMillis() - startTime
            
            // Create completion item with reasoning metadata
            val item = ZestInlineCompletionItem(
                insertText = reasoningResult.completionText,
                replaceRange = ZestInlineCompletionItem.Range(
                    start = context.offset,
                    end = context.offset
                ),
                confidence = reasoningResult.confidence,
                metadata = CompletionMetadata(
                    model = "zest-llm-lean",
                    tokens = reasoningResult.completionText.split("\\s+".toRegex()).size,
                    latency = totalTime,
                    requestId = UUID.randomUUID().toString(),
                    reasoning = reasoningResult.reasoning,
                    contextType = leanContext.contextType.name,
                    hasValidReasoning = reasoningResult.hasValidReasoning
                )
            )
            
            logger.info("Lean completion completed in ${totalTime}ms (llm=${llmTime}ms) with reasoning: ${reasoningResult.hasValidReasoning}")
            ZestInlineCompletionList.single(item)
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            logger.debug("Lean completion request was cancelled")
            throw e
        } catch (e: Exception) {
            logger.warn("Lean completion failed, falling back to simple", e)
            // Fallback to simple strategy
            requestSimpleCompletion(context)
        }
    }
    
    private suspend fun getCurrentEditor(): Editor? {
        return try {
            withContext(Dispatchers.Main) {
                FileEditorManager.getInstance(project).selectedTextEditor
            }
        } catch (e: Exception) {
            logger.warn("Failed to get current editor", e)
            null
        }
    }
    
    private fun calculateConfidence(completion: String): Float {
        var confidence = 0.7f
        
        // Increase confidence for longer completions
        if (completion.length > 10) {
            confidence += 0.1f
        }
        
        // Increase confidence for structured completions
        if (completion.contains('\n') || completion.contains('{') || completion.contains('(')) {
            confidence += 0.1f
        }
        
        // Decrease confidence for very short completions
        if (completion.length < 3) {
            confidence -= 0.2f
        }
        
        return confidence.coerceIn(0.0f, 1.0f)
    }
    
    /**
     * Get stop sequences for Qwen 2.5 Coder FIM format (simple strategy)
     */
    private fun getStopSequences(): List<String> {
        return listOf(
            "<|fim_suffix|>", 
            "<|fim_prefix|>", 
            "<|fim_pad|>", 
            "<|endoftext|>",
            "<|repo_name|>",
            "<|file_sep|>"
        )
    }
    
    /**
     * Get stop sequences for lean strategy (structured output)
     */
    private fun getLeanStopSequences(): List<String> {
        return listOf(
            "</code>",
            "<|endoftext|>",
            "<|end|>",
            "# End of file"
        )
    }
    
    companion object {
        private const val COMPLETION_TIMEOUT_MS = 8000L // 8 seconds for simple
        private const val MAX_COMPLETION_TOKENS = 16 // Small for simple completions
        
        private const val LEAN_COMPLETION_TIMEOUT_MS = 15000L // 15 seconds for reasoning
        private const val LEAN_MAX_COMPLETION_TOKENS = 1000 // Limited tokens for focused completions (reasoning + completion)
    }
}
