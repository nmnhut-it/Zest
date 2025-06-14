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
import com.zps.zest.completion.prompt.ZestSimplePromptBuilder
import com.zps.zest.completion.parser.ZestSimpleResponseParser
import com.zps.zest.langchain4j.util.LLMService
import com.zps.zest.browser.utils.ChatboxUtilities
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

/**
 * Simplified completion provider using basic prefix/suffix context
 * Updated for Qwen 2.5 Coder FIM format with proper stop sequences
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
    
    // Simple components
    private val contextCollector = ZestSimpleContextCollector()
    private val promptBuilder = ZestSimplePromptBuilder()
    private val responseParser = ZestSimpleResponseParser()
    
    suspend fun requestCompletion(context: CompletionContext): ZestInlineCompletionList? {
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
            val simpleContext = contextCollector.collectContext(editor, context.offset)
            
            // Build prompt (thread-safe)
            val prompt = promptBuilder.buildCompletionPrompt(simpleContext)
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
            val cleanedCompletion = responseParser.parseResponseWithOverlapDetection(
                response, 
                documentText, 
                context.offset
            )
            
            if (cleanedCompletion.isBlank()) {
                logger.debug("No valid completion after parsing")
                return null
            }
            
            val totalTime = System.currentTimeMillis() - startTime
            
            // Create completion item
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
                    requestId = UUID.randomUUID().toString()
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
     * Get stop sequences for Qwen 2.5 Coder FIM format
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
    
    companion object {
        private const val COMPLETION_TIMEOUT_MS = 8000L // 8 seconds
        private const val MAX_COMPLETION_TOKENS = 10 // Increased for FIM completions
    }
}
