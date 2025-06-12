package com.zps.zest.completion

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.zps.zest.completion.data.CompletionContext
import com.zps.zest.completion.data.ZestInlineCompletionList
import com.zps.zest.completion.data.ZestInlineCompletionItem
import com.zps.zest.completion.data.CompletionMetadata
import com.zps.zest.completion.context.ZestCompleteGitContext
import com.zps.zest.completion.context.ZestLeanContextCollector
import com.zps.zest.completion.prompt.ZestReasoningPromptBuilder
import com.zps.zest.completion.parser.ZestReasoningResponseParser
import com.zps.zest.langchain4j.util.LLMService
import com.zps.zest.browser.utils.ChatboxUtilities
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

/**
 * Enhanced completion provider with reasoning and git context
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
    
    private val contextCollector = ZestLeanContextCollector(project)
    private val promptBuilder = ZestReasoningPromptBuilder()
    private val responseParser = ZestReasoningResponseParser()
    
    suspend fun requestCompletion(context: CompletionContext): ZestInlineCompletionList? {
        return try {
            logger.debug("Requesting enhanced completion for ${context.fileName} at offset ${context.offset}")
            
            val startTime = System.currentTimeMillis()
            
            // Get current editor
            val editor = getCurrentEditor() ?: run {
                logger.warn("No active editor found, falling back to basic completion")
                return requestBasicCompletion(context)
            }
            
            // Collect enhanced context with all modified files
            val leanContext = contextCollector.collectContext(editor, context.offset)
            
            // Build reasoning prompt
            val reasoningPrompt = promptBuilder.buildReasoningPrompt(leanContext)
            logger.debug("Reasoning prompt built, length: ${reasoningPrompt.length}")
            
            // Query LLM with timeout
            val response = withTimeoutOrNull(COMPLETION_TIMEOUT_MS) {
                llmService.query(reasoningPrompt, ChatboxUtilities.EnumUsage.INLINE_COMPLETION)
            }
            
            if (response == null) {
                logger.warn("Enhanced completion request timed out, falling back to basic")
                return requestBasicCompletion(context)
            }
            
            // Parse reasoning response
            val reasoningCompletion = responseParser.parseReasoningResponse(response)
            if (reasoningCompletion == null) {
                logger.warn("Failed to parse reasoning response, falling back to basic")
                return requestBasicCompletion(context)
            }
            
            val processingTime = System.currentTimeMillis() - startTime
            
            // Log the reasoning for debugging
            logger.info("Completion reasoning: ${reasoningCompletion.reasoning}")
            
            // Create completion item with enhanced metadata
            val item = ZestInlineCompletionItem(
                insertText = reasoningCompletion.completion,
                replaceRange = ZestInlineCompletionItem.Range(
                    start = context.offset,
                    end = context.offset
                ),
                confidence = reasoningCompletion.confidence,
                metadata = CompletionMetadata(
                    model = "zest-llm-reasoning",
                    tokens = reasoningCompletion.completion.split("\\s+".toRegex()).size,
                    latency = processingTime,
                    requestId = UUID.randomUUID().toString(),
                    reasoning = reasoningCompletion.reasoning,
                    modifiedFilesCount = leanContext.gitInfo?.allModifiedFiles?.size ?: 0
                )
            )
            
            ZestInlineCompletionList.single(item)
            
        } catch (e: Exception) {
            logger.warn("Enhanced reasoning completion failed, falling back to basic", e)
            requestBasicCompletion(context)
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
    
    private suspend fun requestBasicCompletion(context: CompletionContext): ZestInlineCompletionList? {
        return try {
            val startTime = System.currentTimeMillis()
            val prompt = buildBasicCompletionPrompt(context)
            
            val response = withTimeoutOrNull(COMPLETION_TIMEOUT_MS) {
                llmService.query(prompt, ChatboxUtilities.EnumUsage.INLINE_COMPLETION)
            }
            
            if (response == null) {
                logger.warn("Basic completion request timed out")
                return null
            }
            
            val processingTime = System.currentTimeMillis() - startTime
            parseBasicCompletionResponse(response, context, processingTime)
            
        } catch (e: Exception) {
            logger.warn("Basic completion request failed", e)
            null
        }
    }
    
    private fun buildBasicCompletionPrompt(context: CompletionContext): String {
        return when {
            context.language.toLowerCase() in SUPPORTED_LANGUAGES -> {
                buildCodeCompletionPrompt(context)
            }
            else -> {
                buildGenericCompletionPrompt(context)
            }
        }
    }
    
    private fun buildCodeCompletionPrompt(context: CompletionContext): String {
        return promptBuilder.buildSimplePrompt(
            context.language,
            context.fileName,
            context.prefixCode,
            context.suffixCode
        )
    }
    
    private fun buildGenericCompletionPrompt(context: CompletionContext): String {
        return """
        Complete the following text. Provide ONLY the raw completion with NO formatting:
        - NO markdown code blocks (no ``` or language tags)
        - NO XML tags or HTML formatting
        - NO explanatory text or comments
        - ONLY the exact text that should be inserted at cursor position
        
        Text before cursor:
        ${context.prefixCode}
        
        Text after cursor:
        ${context.suffixCode}
        
        Complete at cursor position:
        """.trimIndent()
    }
    
    private fun parseBasicCompletionResponse(
        response: String,
        context: CompletionContext,
        processingTime: Long
    ): ZestInlineCompletionList {
        
        if (response.isBlank()) {
            return ZestInlineCompletionList.EMPTY
        }
        
        // Clean up the response
        val cleanedResponse = cleanCompletionText(response)
        
        if (cleanedResponse.isBlank()) {
            return ZestInlineCompletionList.EMPTY
        }
        
        // Create completion item
        val item = ZestInlineCompletionItem(
            insertText = cleanedResponse,
            replaceRange = ZestInlineCompletionItem.Range(
                start = context.offset,
                end = context.offset
            ),
            confidence = calculateConfidence(cleanedResponse, context),
            metadata = CompletionMetadata(
                model = "zest-llm-basic",
                tokens = cleanedResponse.split("\\s+".toRegex()).size,
                latency = processingTime,
                requestId = UUID.randomUUID().toString()
            )
        )
        
        return ZestInlineCompletionList.single(item)
    }
    
    private fun cleanCompletionText(response: String): String {
        // Use the enhanced cleaning from the reasoning parser
        return responseParser.parseSimpleResponse(response)
    }
    
    private fun calculateConfidence(completion: String, context: CompletionContext): Float {
        // Simple heuristic for confidence calculation
        var confidence = 0.8f
        
        // Reduce confidence for very short completions
        if (completion.length < 3) {
            confidence -= 0.3f
        }
        
        // Increase confidence for longer, structured completions
        if (completion.length > 20 && completion.contains('\n')) {
            confidence += 0.1f
        }
        
        // Ensure confidence is between 0 and 1
        return confidence.coerceIn(0.0f, 1.0f)
    }
    
    companion object {
        private const val COMPLETION_TIMEOUT_MS = 10000L // 10 seconds
        
        private val SUPPORTED_LANGUAGES = setOf(
            "java", "kotlin", "javascript", "typescript", "python", 
            "html", "css", "xml", "json", "yaml", "sql", "groovy"
        )
    }
}
