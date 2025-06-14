package com.zps.zest.completion.parser

/**
 * Simple response parser that cleans up LLM responses for code completion
 * Enhanced with thread-safe overlap detection for Qwen 2.5 Coder FIM format
 */
class ZestSimpleResponseParser {
    
    private val overlapDetector = ZestCompletionOverlapDetector()
    
    fun parseResponse(response: String): String {
        if (response.isBlank()) return ""
        
        return response
            .trim()
            .let { cleanFimTokens(it) }
            .let { cleanMarkdownFormatting(it) }
            .let { cleanXmlTags(it) }
            .let { removeExplanations(it) }
            .let { limitLength(it) }
            .trim()
    }
    
    /**
     * Parse response with thread-safe overlap detection and adjustment
     * Only operates on provided strings, no editor access
     */
    fun parseResponseWithOverlapDetection(
        response: String,
        documentText: String,
        cursorOffset: Int
    ): String {
        if (response.isBlank()) return ""
        
        // First, clean the response normally
        val cleanedResponse = parseResponse(response)
        if (cleanedResponse.isBlank()) return ""
        
        // Extract recent user input (thread-safe string operations only)
        val recentUserInput = extractRecentUserInputSafe(documentText, cursorOffset)
        
        // Detect and handle overlaps (pure string processing, thread-safe)
        val overlapResult = overlapDetector.adjustCompletionForOverlap(
            userTypedText = recentUserInput,
            completionText = cleanedResponse,
            cursorOffset = cursorOffset,
            documentText = documentText
        )
        
        // Handle edge cases (thread-safe)
        val finalCompletion = overlapDetector.handleEdgeCases(recentUserInput, overlapResult.adjustedCompletion)
        
        // Debug logging
        if (overlapResult.overlapType != ZestCompletionOverlapDetector.OverlapType.NONE) {
            System.out.println("Overlap detected: ${overlapResult.overlapType}, adjusting '$cleanedResponse' -> '$finalCompletion'")
        }
        
        return finalCompletion
    }
    
    /**
     * Thread-safe extraction of recent user input
     * Only uses string operations, no editor access
     */
    private fun extractRecentUserInputSafe(documentText: String, cursorOffset: Int): String {
        if (cursorOffset <= 0 || cursorOffset > documentText.length) return ""
        
        val startOffset = maxOf(0, cursorOffset - 50)
        val textBeforeCursor = documentText.substring(startOffset, cursorOffset)
        
        // Get current incomplete token/identifier
        val tokenMatch = Regex("""(\w+)$""").find(textBeforeCursor)
        if (tokenMatch != null) {
            return tokenMatch.value
        }
        
        // Get last non-whitespace characters
        val nonWhitespaceMatch = Regex("""(\S+)$""").find(textBeforeCursor)
        if (nonWhitespaceMatch != null) {
            return nonWhitespaceMatch.value
        }
        
        return ""
    }
    
    /**
     * Remove FIM (Fill-In-the-Middle) tokens from response
     * Updated for Qwen 2.5 Coder format
     */
    private fun cleanFimTokens(text: String): String {
        return text
            // Qwen 2.5 Coder FIM tokens
            .replace("<|fim_prefix|>", "")
            .replace("<|fim_suffix|>", "") 
            .replace("<|fim_middle|>", "")
            .replace("<|fim_pad|>", "")
            // Legacy FIM tokens (for backward compatibility)
            .replace("<fim_prefix>", "")
            .replace("<fim_suffix>", "") 
            .replace("<fim_middle>", "")
            .replace("</fim_middle>", "")
            // Other completion tokens
            .replace("<CURSOR>", "")
            .replace("[COMPLETE]", "")
            .replace("│", "") // Our minimal separator
    }
    
    private fun cleanMarkdownFormatting(text: String): String {
        var cleaned = text
        
        // Remove code blocks
        cleaned = cleaned.replace(Regex("^```[a-zA-Z]*\\s*", RegexOption.MULTILINE), "")
        cleaned = cleaned.replace(Regex("```\\s*$", RegexOption.MULTILINE), "")
        
        // Remove language tags on their own lines
        val lines = cleaned.lines().toMutableList()
        if (lines.isNotEmpty() && lines.first().trim().matches(Regex("^(java|kotlin|javascript|typescript|python|html|css|xml|json|yaml|sql)$", RegexOption.IGNORE_CASE))) {
            lines.removeAt(0)
        }
        if (lines.isNotEmpty() && lines.last().trim().matches(Regex("^```$"))) {
            lines.removeAt(lines.size - 1)
        }
        
        return lines.joinToString("\n")
    }
    
    private fun cleanXmlTags(text: String): String {
        var cleaned = text
        
        val xmlTags = listOf(
            "<code>", "</code>",
            "<completion>", "</completion>",
            "<answer>", "</answer>",
            // Qwen 2.5 Coder FIM tokens
            "<|fim_prefix|>", "<|fim_suffix|>", "<|fim_middle|>", "<|fim_pad|>",
            // Legacy FIM tokens
            "<fim_middle>", "</fim_middle>",
            "<fim_prefix>", "<fim_suffix>",
            "<r>", "</r>"
        )
        
        xmlTags.forEach { tag ->
            cleaned = cleaned.replace(tag, "", ignoreCase = true)
        }
        
        return cleaned
    }
    
    private fun removeExplanations(text: String): String {
        val lines = text.lines()
        val codeLines = mutableListOf<String>()
        
        for (line in lines) {
            val trimmed = line.trim()
            
            // Skip explanation lines
            if (trimmed.startsWith("Here") || 
                trimmed.startsWith("The completion") ||
                trimmed.startsWith("This will") ||
                trimmed.startsWith("This code") ||
                trimmed.contains("explanation", ignoreCase = true)) {
                continue
            }
            
            codeLines.add(line)
        }
        
        return codeLines.joinToString("\n")
    }
    
    private fun limitLength(text: String): String {
        val maxTokens = 50
        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        
        return if (tokens.size <= maxTokens) {
            text
        } else {
            tokens.take(maxTokens).joinToString(" ")
        }
    }
}
