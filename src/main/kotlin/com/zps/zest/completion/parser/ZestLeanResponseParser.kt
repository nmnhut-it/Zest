package com.zps.zest.completion.parser

import com.intellij.openapi.diagnostic.Logger

/**
 * Lean response parser that extracts reasoning and completion text directly
 * No diff calculation needed since LLM generates only the completion text
 */
class ZestLeanResponseParser {
    private val logger = Logger.getInstance(ZestLeanResponseParser::class.java)
    
    data class ReasoningResult(
        val reasoning: String,
        val completionText: String,
        val confidence: Float,
        val hasValidReasoning: Boolean
    ) {
        companion object {
            fun empty() = ReasoningResult("", "", 0.0f, false)
        }
    }
    
    fun parseReasoningResponse(
        response: String, 
        originalFile: String, 
        cursorPosition: Int
    ): ReasoningResult {
        try {
            // Extract reasoning and completion sections
            val reasoning = extractReasoning(response)
            val completion = extractCompletion(response)
            
            if (completion.isBlank()) {
                logger.debug("No completion section found in response")
                return ReasoningResult.empty()
            }
            
            // Clean the completion text
            val cleanedCompletion = cleanCompletionText(completion)
            
            return ReasoningResult(
                reasoning = reasoning,
                completionText = cleanedCompletion,
                confidence = calculateConfidenceFromReasoning(reasoning, cleanedCompletion),
                hasValidReasoning = reasoning.isNotBlank() && reasoning.length > 20
            )
            
        } catch (e: Exception) {
            logger.warn("Failed to parse reasoning response", e)
            return ReasoningResult.empty()
        }
    }
    
    private fun extractReasoning(response: String): String {
        val reasoningPattern = Regex("<reasoning>(.*?)</reasoning>", RegexOption.DOT_MATCHES_ALL)
        val rawReasoning = reasoningPattern.find(response)?.groupValues?.get(1)?.trim() ?: ""
        
        // Validate and limit reasoning length
        return validateAndLimitReasoning(rawReasoning)
    }
    
    /**
     * Validate and limit reasoning to prevent overly verbose responses
     */
    private fun validateAndLimitReasoning(reasoning: String): String {
        if (reasoning.isBlank()) return ""
        
        var cleaned = reasoning
        
        // Remove common verbose prefixes
        cleaned = cleaned.replace(Regex("^(Let me analyze|I will analyze|Looking at|Analyzing).*?[:.] ?", RegexOption.IGNORE_CASE), "")
        
        // Limit to maximum word count
        val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        
        val limitedWords = if (words.size > MAX_REASONING_WORDS) {
            logger.debug("Truncating reasoning from ${words.size} to $MAX_REASONING_WORDS words")
            words.take(MAX_REASONING_WORDS)
        } else {
            words
        }
        
        val result = limitedWords.joinToString(" ").trim()
        
        // Ensure it ends properly if truncated
        return if (words.size > MAX_REASONING_WORDS && !result.endsWith(".")) {
            "$result..."
        } else {
            result
        }
    }
    
    private fun extractCompletion(response: String): String {
        val completionPattern = Regex("<completion>(.*?)</completion>", RegexOption.DOT_MATCHES_ALL)
        val completionMatch = completionPattern.find(response)?.groupValues?.get(1)?.trim()
        
        if (completionMatch != null) {
            return completionMatch
        }
        
        // Fallback: try the old <code> tag for backward compatibility
        val codePattern = Regex("<code>(.*?)</code>", RegexOption.DOT_MATCHES_ALL)
        val codeMatch = codePattern.find(response)?.groupValues?.get(1)?.trim()
        
        return codeMatch ?: ""
    }
    
    private fun cleanCompletionText(completion: String): String {
        var cleaned = completion
        
        // Remove markdown code blocks if present
        cleaned = cleaned.replace(Regex("^```[a-zA-Z]*\\s*", RegexOption.MULTILINE), "")
        cleaned = cleaned.replace(Regex("```\\s*$", RegexOption.MULTILINE), "")
        
        // Remove cursor marker if somehow included
        cleaned = cleaned.replace("[CURSOR]", "")
        
        // Remove common explanation prefixes
        cleaned = cleaned.replace(Regex("^(Here's|Here is|The completion is|To complete this).*?:", RegexOption.IGNORE_CASE), "")
        
        // Trim whitespace
        cleaned = cleaned.trim()
        
        // If it's multiline, ensure proper formatting
        if (cleaned.contains('\n')) {
            val lines = cleaned.lines()
            
            // Remove empty lines at start and end
            val trimmedLines = lines.dropWhile { it.trim().isEmpty() }
                .dropLastWhile { it.trim().isEmpty() }
            
            // Limit to reasonable number of lines
            val limitedLines = if (trimmedLines.size > MAX_COMPLETION_LINES) {
                trimmedLines.take(MAX_COMPLETION_LINES)
            } else {
                trimmedLines
            }
            
            cleaned = limitedLines.joinToString("\n")
        }
        
        // Final length check
        if (cleaned.length > MAX_COMPLETION_LENGTH) {
            cleaned = cleaned.take(MAX_COMPLETION_LENGTH)
            logger.debug("Truncated completion from ${completion.length} to $MAX_COMPLETION_LENGTH characters")
        }
        
        return cleaned
    }
    
    private fun calculateConfidenceFromReasoning(reasoning: String, completion: String): Float {
        var confidence = 0.6f // Start higher since we have full context
        
        // Higher confidence if reasoning is present but not too verbose
        val wordCount = reasoning.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        when {
            wordCount in 10..30 -> confidence += 0.2f // Sweet spot for reasoning length
            wordCount in 5..50 -> confidence += 0.1f  // Reasonable length
            wordCount > 50 -> confidence -= 0.1f      // Too verbose, may be unfocused
            wordCount < 5 -> confidence -= 0.1f       // Too brief, may lack analysis
        }
        
        // Check for focused analysis keywords
        val focusedKeywords = listOf(
            "context", "pattern", "completion", "method", "variable", 
            "class", "import", "return", "implement"
        )
        val keywordCount = focusedKeywords.count { 
            reasoning.contains(it, ignoreCase = true) 
        }
        confidence += keywordCount * 0.03f
        
        // Higher confidence for structured completions
        if (completion.contains('\n')) confidence += 0.1f
        if (completion.contains('{') || completion.contains('(')) confidence += 0.1f
        
        // Check for code-like patterns
        if (completion.matches(Regex(".*[a-zA-Z_][a-zA-Z0-9_]*.*"))) confidence += 0.1f
        
        // Higher confidence if completion looks complete (ends with semicolon, brace, etc.)
        if (completion.trimEnd().matches(Regex(".*[;})]$"))) confidence += 0.1f
        
        // Lower confidence for very short completions (unless they look intentional)
        if (completion.length < 5 && !completion.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*$"))) {
            confidence -= 0.3f
        }
        
        // Penalize reasoning that doesn't seem focused
        val unfocusedPhrases = listOf(
            "i think", "perhaps", "maybe", "it seems", "probably", 
            "i believe", "it appears", "likely"
        )
        val unfocusedCount = unfocusedPhrases.count { 
            reasoning.contains(it, ignoreCase = true) 
        }
        confidence -= unfocusedCount * 0.05f
        
        return confidence.coerceIn(0.0f, 1.0f)
    }
    
    companion object {
        private const val MAX_COMPLETION_LINES = 25 // Maximum lines for completion
        private const val MAX_COMPLETION_LENGTH = 1500 // Maximum characters for completion
        private const val MAX_REASONING_WORDS = 60 // Maximum words in reasoning section
    }
}


