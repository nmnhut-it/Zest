package com.zps.zest.completion.parser

/**
 * Parses responses from the lean completion strategy
 */
class ZestLeanResponseParser {
    
    private val overlapDetector = ZestCompletionOverlapDetector()
    
    data class LeanReasoningResult(
        val completionText: String,
        val reasoning: String,
        val confidence: Float,
        val hasValidReasoning: Boolean
    )
    
    /**
     * Parse a simplified response focused on line completion
     */
    fun parseReasoningResponse(
        response: String,
        documentText: String,
        offset: Int
    ): LeanReasoningResult {
        
        if (response.isBlank()) {
            return LeanReasoningResult("", "", 0.0f, false)
        }
        
        // Extract completion from the response
        val completion = extractCompletionTag(response)
        
        // If extraction failed, try to get raw content
        val finalCompletion = if (completion.isEmpty()) {
            // Fallback: treat the entire response as completion if no tags found
            response.trim()
        } else {
            completion
        }
        
        // Apply overlap detection to avoid duplicating what user already typed
        val adjustedCompletion = if (finalCompletion.isNotEmpty()) {
            val recentUserInput = extractRecentUserInputSafe(documentText, offset)
            val overlapResult = overlapDetector.adjustCompletionForOverlap(
                userTypedText = recentUserInput,
                completionText = finalCompletion,
                cursorOffset = offset,
                documentText = documentText
            )
            
            // Debug logging for overlap detection
            if (overlapResult.prefixOverlapLength > 0 || overlapResult.suffixOverlapLength > 0) {
//                System.out.println("=== LEAN OVERLAP DETECTION ===")
//                System.out.println("User input: '$recentUserInput'")
//                System.out.println("Original completion: '$finalCompletion'")
//                System.out.println("Adjusted completion: '${overlapResult.adjustedCompletion}'")
            }
            
            overlapResult.adjustedCompletion
        } else {
            finalCompletion
        }
        
        // Calculate confidence based on completion quality
        val confidence = calculateSimplifiedConfidence(adjustedCompletion)
        
        // Return result without reasoning since we're not extracting it anymore
        return LeanReasoningResult(
            completionText = adjustedCompletion,
            reasoning = "", // No reasoning in simplified format
            confidence = confidence,
            hasValidReasoning = false // No reasoning validation needed
        )
    }
    
    /**
     * Extract completion from <completion> tags
     */
    private fun extractCompletionTag(response: String): String {
        // Pattern to match <completion>...</completion> tags
        val completionPattern = Regex("<completion>(.+?)</completion>", RegexOption.DOT_MATCHES_ALL)
        val match = completionPattern.find(response)
        
        return if (match != null) {
            match.groupValues[1].trim()
        } else {
            ""
        }
    }
    
    /**
     * Enhanced thread-safe extraction of recent user input
     */
    private fun extractRecentUserInputSafe(documentText: String, cursorOffset: Int): String {
        if (cursorOffset <= 0 || cursorOffset > documentText.length) return ""
        
        // Get current line up to cursor
        val lineStart = documentText.lastIndexOf('\n', cursorOffset - 1) + 1
        val currentLine = documentText.substring(lineStart, cursorOffset)
        
        // Try whole line trimmed first (most comprehensive match)
        val trimmedLine = currentLine.trim()
        if (trimmedLine.isNotEmpty() && trimmedLine.length <= 50) {
            return trimmedLine
        }
        
        // Extract meaningful recent input
        return when {
            // Get incomplete identifier/word
            currentLine.matches(Regex(".*\\w+$")) -> {
                val match = Regex("(\\w+)$").find(currentLine)
                match?.value ?: ""
            }
            // Get single character that might be start of identifier
            currentLine.matches(Regex(".*\\w$")) -> {
                val match = Regex("(\\w)$").find(currentLine)
                match?.value ?: ""
            }
            // Get operator/symbol sequence
            currentLine.matches(Regex(".*[^\\s\\w]+$")) -> {
                val match = Regex("([^\\s\\w]+)$").find(currentLine)
                match?.value ?: ""
            }
            // Get whitespace if user is indenting
            currentLine.matches(Regex(".*\\s+$")) -> {
                val match = Regex("(\\s+)$").find(currentLine)
                match?.value ?: ""
            }
            // Get last few characters as fallback
            else -> currentLine.takeLast(10).trim()
        }
    }
    
    /**
     * Calculate confidence based on completion quality
     */
    private fun calculateSimplifiedConfidence(completion: String): Float {
        var confidence = 0.7f // Base confidence for simplified approach
        
        // Increase confidence for substantial completion
        when {
            completion.length > 20 -> confidence += 0.15f
            completion.length > 10 -> confidence += 0.1f
            completion.length > 5 -> confidence += 0.05f
        }
        
        // Increase confidence for structured code patterns
        if (completion.contains("{") || completion.contains("(") || completion.contains("[")) {
            confidence += 0.05f
        }
        
        // Increase confidence if it looks like a complete statement
        if (completion.contains("}") || completion.contains(")") || completion.contains(";")) {
            confidence += 0.05f
        }
        
        // Decrease confidence for very short completions
        if (completion.length < 3) {
            confidence -= 0.2f
        }
        
        // Decrease confidence if it's just whitespace
        if (completion.isBlank()) {
            confidence = 0.0f
        }
        
        return confidence.coerceIn(0.0f, 1.0f)
    }
}