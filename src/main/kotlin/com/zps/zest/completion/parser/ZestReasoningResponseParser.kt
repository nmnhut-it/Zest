package com.zps.zest.completion.parser

import com.intellij.openapi.diagnostic.Logger
import com.zps.zest.completion.data.CompletionContext

/**
 * Simple response parser for inline code completion
 */
class ZestSimpleResponseParser {
    private val logger = Logger.getInstance(ZestSimpleResponseParser::class.java)
    private val overlapDetector = ZestCompletionOverlapDetector()
    
    companion object {
        private const val MAX_COMPLETION_TOKENS = 64
    }
    
    /**
     * Parse and clean completion response
     */
    fun parseResponse(
        response: String, 
        context: CompletionContext? = null,
        documentText: String? = null
    ): String {
        if (response.isBlank()) return ""
        
        val cleanedCompletion = cleanCompletionText(response)
        val adjustedCompletion = adjustCompletionForOverlap(cleanedCompletion, context, documentText)
        return validateCompletionLength(adjustedCompletion.adjustedCompletion)
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
        
        // Remove opening code blocks with language tags
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
            "<answer>", "</answer>",
            "<fim_middle>", "</fim_middle>"
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
        
        // Clean up multiple spaces created by formatting removal
        cleaned = cleaned.replace(Regex("\\s+"), " ")
        
        return cleaned
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
     * Validate completion length (approximately 64 tokens)
     */
    private fun validateCompletionLength(completion: String): String {
        if (completion.isBlank()) return completion
        
        // Rough token estimation: split by whitespace and punctuation
        val tokens = completion.split(Regex("[\\s\\p{Punct}]+")).filter { it.isNotEmpty() }
        
        return if (tokens.size <= MAX_COMPLETION_TOKENS) {
            completion
        } else {
            // Truncate at token boundary, try to keep it meaningful
            val truncatedTokens = tokens.take(MAX_COMPLETION_TOKENS)
            val truncated = truncatedTokens.joinToString(" ")
            System.out.println("Completion too long (${tokens.size} tokens), truncated to ${truncatedTokens.size} tokens")
            
            // Try to end at a meaningful boundary (semicolon, closing brace, etc.)
            val meaningfulEnd = findMeaningfulEndpoint(truncated)
            meaningfulEnd ?: truncated
        }
    }
    
    /**
     * Find a meaningful endpoint for truncated completion
     */
    private fun findMeaningfulEndpoint(text: String): String? {
        val meaningfulEndings = listOf(";", "}", ")", "]", ",")
        
        for (ending in meaningfulEndings) {
            val lastIndex = text.lastIndexOf(ending)
            if (lastIndex > text.length / 2) { // Only if we're not cutting too much
                return text.substring(0, lastIndex + 1)
            }
        }
        
        return null // No meaningful endpoint found
    }
}
