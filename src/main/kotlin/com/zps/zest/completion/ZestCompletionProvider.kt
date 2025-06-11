package com.zps.zest.completion

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.zps.zest.completion.data.CompletionContext
import com.zps.zest.completion.data.ZestInlineCompletionList
import com.zps.zest.completion.data.ZestInlineCompletionItem
import com.zps.zest.completion.data.CompletionMetadata
import com.zps.zest.langchain4j.util.LLMService
import com.zps.zest.browser.utils.ChatboxUtilities
import kotlinx.coroutines.withTimeoutOrNull
import java.util.*

/**
 * Handles completion requests using the existing LLMService
 */
class ZestCompletionProvider(private val project: Project) {
    private val logger = Logger.getInstance(ZestCompletionProvider::class.java)
    private val llmService by lazy { 
        try {
            // Create LLMService instance with project parameter
            LLMService(project)
        } catch (e: Exception) {
            logger.warn("Failed to create LLMService instance", e)
            throw IllegalStateException("LLMService not available", e)
        }
    }
    
    suspend fun requestCompletion(context: CompletionContext): ZestInlineCompletionList? {
        return try {
            logger.debug("Requesting completion for ${context.fileName} at offset ${context.offset}")
            
            val startTime = System.currentTimeMillis()
            val prompt = buildCompletionPrompt(context)
            
            // Use timeout to prevent hanging
            val response = withTimeoutOrNull(COMPLETION_TIMEOUT_MS) {
                // Use the LLMService query method with appropriate usage
                llmService.query(prompt, ChatboxUtilities.EnumUsage.LLM_SERVICE)
            }
            
            if (response == null) {
                logger.warn("Completion request timed out after ${COMPLETION_TIMEOUT_MS}ms")
                return null
            }
            
            val processingTime = System.currentTimeMillis() - startTime
            parseCompletionResponse(response, context, processingTime)
            
        } catch (e: Exception) {
            logger.warn("Completion request failed", e)
            null
        }
    }
    
    private fun buildCompletionPrompt(context: CompletionContext): String {
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
        return """
        Complete the following ${context.language} code. Provide ONLY the completion text, no explanations.
        
        File: ${context.fileName}
        
        Code before cursor:
        ${context.prefixCode}
        
        Code after cursor:
        ${context.suffixCode}
        
        Complete at cursor position:
        """.trimIndent()
    }
    
    private fun buildGenericCompletionPrompt(context: CompletionContext): String {
        return """
        Complete the following text. Provide ONLY the completion, no explanations.
        
        Text before cursor:
        ${context.prefixCode}
        
        Text after cursor:
        ${context.suffixCode}
        
        Complete at cursor position:
        """.trimIndent()
    }
    
    private fun parseCompletionResponse(
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
                model = "zest-llm",
                tokens = cleanedResponse.split("\\s+".toRegex()).size,
                latency = processingTime,
                requestId = UUID.randomUUID().toString()
            )
        )
        
        return ZestInlineCompletionList.single(item)
    }
    
    private fun cleanCompletionText(response: String): String {
        return response
            .trim()
            .removePrefix("```")
            .removeSuffix("```")
            .lines()
            .takeWhile { !it.trim().startsWith("```") }
            .joinToString("\n")
            .trim()
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
