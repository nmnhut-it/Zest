package com.zps.zest.completion.parser

import com.intellij.openapi.diagnostic.Logger
import com.zps.zest.completion.data.CompletionContext

/**
 * Enhanced response parser that extracts both reasoning and completion from LLM responses
 * and handles partial matching/overlap detection
 */
class ZestReasoningResponseParser {
    private val logger = Logger.getInstance(ZestReasoningResponseParser::class.java)
    private val overlapDetector = ZestCompletionOverlapDetector()
    
    data class ReasoningCompletion(
        val reasoning: String,
        val completion: String,
        val confidence: Float,
        val overlapInfo: ZestCompletionOverlapDetector.OverlapResult? = null
    )
    
    fun parseReasoningResponse(
        response: String, 
        context: CompletionContext? = null,
        documentText: String? = null
    ): ReasoningCompletion? {
        if (response.isBlank()) return null
        
        return try {
            // Look for the structured format first
            val reasoningMatch = Regex("""REASONING:\s*(.+?)(?=COMPLETION:|$)""", RegexOption.DOT_MATCHES_ALL)
                .find(response)
            val completionMatch = Regex("""COMPLETION:\s*(.+)""", RegexOption.DOT_MATCHES_ALL)
                .find(response)
            
            val reasoning = reasoningMatch?.groupValues?.get(1)?.trim()
            val rawCompletion = completionMatch?.groupValues?.get(1)?.trim()
            
            when {
                rawCompletion.isNullOrBlank() -> {
                    // Fallback: treat entire response as completion if no structure found
                    logger.debug("No structured response found, treating entire response as completion")
                    val cleanedCompletion = cleanCompletionText(response)
                    val adjustedCompletion = adjustCompletionForOverlap(cleanedCompletion, context, documentText)
                    
                    ReasoningCompletion(
                        reasoning = "No reasoning provided",
                        completion = adjustedCompletion.adjustedCompletion,
                        confidence = 0.5f,
                        overlapInfo = adjustedCompletion
                    )
                }
                else -> {
                    val cleanedCompletion = cleanCompletionText(rawCompletion)
                    val adjustedCompletion = adjustCompletionForOverlap(cleanedCompletion, context, documentText)
                    val confidence = calculateConfidenceFromReasoning(reasoning, adjustedCompletion.adjustedCompletion)
                    
                    ReasoningCompletion(
                        reasoning = reasoning ?: "No reasoning provided",
                        completion = adjustedCompletion.adjustedCompletion,
                        confidence = confidence,
                        overlapInfo = adjustedCompletion
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            logger.debug("Response parsing was cancelled (normal behavior)")
            throw e
        } catch (e: Exception) {
            logger.debug("Failed to parse reasoning response: ${e.message}")
            // Fallback to treating entire response as completion
            val cleanedCompletion = cleanCompletionText(response)
            val adjustedCompletion = adjustCompletionForOverlap(cleanedCompletion, context, documentText)
            
            ReasoningCompletion(
                reasoning = "Parse error: ${e.message}",
                completion = adjustedCompletion.adjustedCompletion,
                confidence = 0.3f,
                overlapInfo = adjustedCompletion
            )
        }
    }
    
    private fun cleanCompletionText(text: String): String {
        return text
            .trim()
            .let { cleanMarkdownCodeBlocks(it) }
            .let { cleanXmlTags(it) }
            .let { removeLeadingTrailingBackticks(it) }
            .let { cleanExtraFormatting(it) }
            .trim()
    }
    
    private fun cleanMarkdownCodeBlocks(text: String): String {
        var cleaned = text
        
        // Remove opening code blocks with language tags (e.g., ```java, ```kotlin, ```javascript)
        cleaned = cleaned.replace(Regex("^```\\w*\\s*", RegexOption.MULTILINE), "")
        
        // Remove closing code blocks
        cleaned = cleaned.replace(Regex("```\\s*$", RegexOption.MULTILINE), "")
        
        // Handle cases where language tag is on its own line
        val lines = cleaned.lines().toMutableList()
        
        // Remove first line if it's just a language identifier
        if (lines.isNotEmpty() && lines.first().trim().matches(Regex("^(java|kotlin|javascript|typescript|python|html|css|xml|json|yaml|sql|groovy|scala|go|rust|cpp|c)$", RegexOption.IGNORE_CASE))) {
            lines.removeAt(0)
        }
        
        // Remove last line if it's just closing backticks or language tag
        if (lines.isNotEmpty() && lines.last().trim().matches(Regex("^(```|\\w+)$"))) {
            lines.removeAt(lines.size - 1)
        }
        
        return lines.joinToString("\n")
    }
    
    private fun cleanXmlTags(text: String): String {
        var cleaned = text
        
        // Remove common XML-style tags that LLMs sometimes use
        val xmlPatterns = listOf(
            "<code>", "</code>",
            "<pre>", "</pre>",
            "<java>", "</java>",
            "<kotlin>", "</kotlin>", 
            "<javascript>", "</javascript>",
            "<completion>", "</completion>",
            "<answer>", "</answer>"
        )
        
        xmlPatterns.forEach { tag ->
            cleaned = cleaned.replace(tag, "", ignoreCase = true)
        }
        
        return cleaned
    }
    
    private fun removeLeadingTrailingBackticks(text: String): String {
        var cleaned = text
        
        // Remove leading backticks (any number)
        cleaned = cleaned.replace(Regex("^`+"), "")
        
        // Remove trailing backticks (any number)
        cleaned = cleaned.replace(Regex("`+$"), "")
        
        return cleaned
    }
    
    private fun cleanExtraFormatting(text: String): String {
        var cleaned = text
        
        // Remove common markdown formatting that might leak through
        cleaned = cleaned.replace("**", "") // Bold
        cleaned = cleaned.replace("__", "") // Bold alternative
        
        // Be careful with * and _ as they're valid code characters
        // Only remove them if they appear to be markdown formatting (paired)
        cleaned = cleaned.replace(Regex("\\*([^*]+)\\*"), "$1") // Italic: *text*
        cleaned = cleaned.replace(Regex("_([^_\\s]+)_"), "$1") // Italic: _text_ (but not _variable_name)
        
        // Remove any remaining markdown-style formatting
        cleaned = cleaned.replace(Regex("\\[.*?\\]\\(.*?\\)"), "") // Links
        cleaned = cleaned.replace(Regex("!\\[.*?\\]\\(.*?\\)"), "") // Images
        
        // Clean up multiple spaces created by formatting removal
        cleaned = cleaned.replace(Regex("\\s+"), " ")
        
        return cleaned
    }
    
    private fun calculateConfidenceFromReasoning(reasoning: String?, completion: String): Float {
        var confidence = 0.7f // Base confidence
        
        reasoning?.let { reason ->
            // Higher confidence if reasoning mentions specific context
            if (reason.contains("based on", ignoreCase = true) || 
                reason.contains("likely", ignoreCase = true) ||
                reason.contains("similar to", ignoreCase = true) ||
                reason.contains("appears to", ignoreCase = true)) {
                confidence += 0.1f
            }
            
            // Higher confidence if reasoning is detailed
            if (reason.length > 50) {
                confidence += 0.1f
            }
            
            // Higher confidence if reasoning mentions specific code elements
            if (reason.contains("method", ignoreCase = true) ||
                reason.contains("function", ignoreCase = true) ||
                reason.contains("class", ignoreCase = true) ||
                reason.contains("variable", ignoreCase = true)) {
                confidence += 0.05f
            }
        }
        
        // Higher confidence for longer, structured completions
        if (completion.length > 20 && completion.contains('\n')) {
            confidence += 0.1f
        }
        
        // Lower confidence for very short completions
        if (completion.length < 3) {
            confidence -= 0.2f
        }
        
        // Higher confidence if completion contains proper indentation
        if (completion.lines().size > 1 && completion.lines().any { it.startsWith("    ") || it.startsWith("\t") }) {
            confidence += 0.05f
        }
        
        return confidence.coerceIn(0.0f, 1.0f)
    }
    
    /**
     * Parse a simple completion response (non-reasoning format)
     */
    fun parseSimpleResponse(
        response: String,
        context: CompletionContext? = null,
        documentText: String? = null
    ): String {
        val cleanedCompletion = cleanCompletionText(response)
        val adjustedCompletion = adjustCompletionForOverlap(cleanedCompletion, context, documentText)
        return adjustedCompletion.adjustedCompletion
    }
    
    /**
     * Adjust completion text to handle partial matching and overlap with user input
     */
    private fun adjustCompletionForOverlap(
        completionText: String,
        context: CompletionContext?,
        documentText: String?
    ): ZestCompletionOverlapDetector.OverlapResult {
        
        if (context == null || documentText == null) {
            return ZestCompletionOverlapDetector.OverlapResult(
                completionText, 
                0, 
                ZestCompletionOverlapDetector.OverlapType.NONE
            )
        }
        
        // Extract what user has typed recently
        val userTypedText = extractUserTypedText(documentText, context.offset)
        
        // Detect and handle overlaps
        val overlapResult = overlapDetector.adjustCompletionForOverlap(
            userTypedText,
            completionText,
            context.offset,
            documentText
        )
        
        // Handle additional edge cases
        val finalCompletion = overlapDetector.handleEdgeCases(userTypedText, overlapResult.adjustedCompletion)
        
        return overlapResult.copy(adjustedCompletion = finalCompletion)
    }
    
    /**
     * Extract what the user has recently typed at the cursor position
     */
    private fun extractUserTypedText(documentText: String, offset: Int): String {
        if (offset <= 0) return ""
        
        // Look backwards from cursor to find the start of current token/identifier
        val startOffset = maxOf(0, offset - 50) // Look back up to 50 characters
        val textBeforeCursor = documentText.substring(startOffset, offset)
        
        // Find the current incomplete identifier/token
        val tokenPattern = Regex("""(\w+)$""")
        val tokenMatch = tokenPattern.find(textBeforeCursor)
        
        if (tokenMatch != null) {
            return tokenMatch.value
        }
        
        // Fallback: get the last non-whitespace characters
        val nonWhitespacePattern = Regex("""(\S+)$""")
        val nonWhitespaceMatch = nonWhitespacePattern.find(textBeforeCursor)
        
        return nonWhitespaceMatch?.value ?: ""
    }
    
    /**
     * Detect if a response appears to be in reasoning format
     */
    fun isReasoningFormat(response: String): Boolean {
        return response.contains("REASONING:", ignoreCase = true) && 
               response.contains("COMPLETION:", ignoreCase = true)
    }
}
