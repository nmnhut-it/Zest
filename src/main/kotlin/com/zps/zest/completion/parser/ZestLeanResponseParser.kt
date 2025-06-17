package com.zps.zest.completion.parser

/**
 * Parses responses from the lean completion strategy
 */
class ZestLeanResponseParser {
    
    data class LeanReasoningResult(
        val completionText: String,
        val reasoning: String,
        val confidence: Float,
        val hasValidReasoning: Boolean
    )
    
    /**
     * Parse a reasoning-based response from the lean strategy
     */
    fun parseReasoningResponse(
        response: String,
        documentText: String,
        offset: Int
    ): LeanReasoningResult {
        
        if (response.isBlank()) {
            return LeanReasoningResult("", "", 0.0f, false)
        }
        
        // Extract reasoning and completion from response
        val (reasoning, completion) = extractReasoningAndCompletion(response)
        
        // Clean the completion text
        val cleanedCompletion = extractWrappedContent(completion)
        
        // Validate the reasoning
        val hasValidReasoning = validateReasoning(reasoning)
        
        // Calculate confidence
        val confidence = calculateConfidence(cleanedCompletion, reasoning, hasValidReasoning)
        
        return LeanReasoningResult(
            completionText = cleanedCompletion,
            reasoning = reasoning,
            confidence = confidence,
            hasValidReasoning = hasValidReasoning
        )
    }
    
    /**
     * Extract reasoning and completion from the response
     */
    private fun extractReasoningAndCompletion(response: String): Pair<String, String> {
        // Look for completion section
        val completionMarkers = listOf(
            "**Code Completion:**",
            "**Completion:**",
            "Code Completion:",
            "Completion:"
        )
        
        for (marker in completionMarkers) {
            val markerIndex = response.indexOf(marker, ignoreCase = true)
            if (markerIndex != -1) {
                val reasoning = response.substring(0, markerIndex).trim()
                val completion = response.substring(markerIndex + marker.length).trim()
                return Pair(reasoning, completion)
            }
        }
        
        // If no clear separation, treat entire response as completion
        return Pair("", response)
    }

    /**
     * Extract content from markdown code blocks and XML tags
     */
    private fun extractWrappedContent(completion: String): String {
        var content = completion.trim()

        // Extract from markdown code blocks (```...```)
        val markdownPattern = Regex("```[a-zA-Z]*\\s*(.+?)```", RegexOption.DOT_MATCHES_ALL)
        val markdownMatch = markdownPattern.find(content)
        if (markdownMatch != null) {
            content = markdownMatch.groupValues[1].trim()
        }

        // Extract from XML tags - try each tag type
        val xmlPatterns = listOf(
            "<code>(.+?)</code>",
            "<completion>(.+?)</completion>",
            "<reasoning>(.+?)</reasoning>"
        )

        for (pattern in xmlPatterns) {
            val xmlRegex = Regex(pattern, setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            val xmlMatch = xmlRegex.find(content)
            if (xmlMatch != null) {
                content = xmlMatch.groupValues[1].trim()
                break // Use the first matching tag
            }
        }

        // Remove any remaining wrapper artifacts
//        content = removeTrailingExplanations(content)

        return content.trim()
    }

    /**
     * Alternative version that handles nested wrappers
     */
    private fun extractNestedContent(completion: String): String {
        var content = completion.trim()

        // Keep extracting until no more wrappers are found
        var previousContent: String
        do {
            previousContent = content

            // Try to extract from markdown code blocks
            val markdownPattern = Regex("```[a-zA-Z]*\\s*(.+?)```", RegexOption.DOT_MATCHES_ALL)
            val markdownMatch = markdownPattern.find(content)
            if (markdownMatch != null) {
                content = markdownMatch.groupValues[1].trim()
            }

            // Try to extract from XML tags
            val xmlPatterns = listOf(
                "<code>(.+?)</code>",
                "<completion>(.+?)</completion>",
                "<reasoning>(.+?)</reasoning>"
            )

            for (pattern in xmlPatterns) {
                val xmlRegex = Regex(pattern, setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                val xmlMatch = xmlRegex.find(content)
                if (xmlMatch != null) {
                    content = xmlMatch.groupValues[1].trim()
                    break
                }
            }
        } while (content != previousContent) // Continue while content is changing

        // Final cleanup
//        content = removeTrailingExplanations(content)

        return content.trim()
    }
    
    /**
     * Remove trailing explanations that aren't part of the code
     */
    private fun removeTrailingExplanations(text: String): String {
        val lines = text.lines().toMutableList()
        
        // Remove lines that look like explanations
        val explanationPatterns = listOf(
            Regex("^(this|the|note|explanation).*", RegexOption.IGNORE_CASE),
            Regex("^(reasoning|analysis|thought).*", RegexOption.IGNORE_CASE)
        )
        
        // Work backwards from the end
        while (lines.isNotEmpty()) {
            val lastLine = lines.last().trim()
            
            if (lastLine.isEmpty()) {
                lines.removeAt(lines.size - 1)
                continue
            }
            
            if (explanationPatterns.any { it.matches(lastLine) }) {
                lines.removeAt(lines.size - 1)
                continue
            }
            
            break
        }
        
        return lines.joinToString("\n")
    }
    
    /**
     * Validate that the reasoning makes sense
     */
    private fun validateReasoning(reasoning: String): Boolean {
        if (reasoning.isBlank()) return false
        
        // Look for reasoning indicators
        val reasoningIndicators = listOf(
            "analyze", "understand", "context", "pattern", "style",
            "consider", "based on", "looking at", "given", "since"
        )
        
        val lowerReasoning = reasoning.lowercase()
        val indicatorCount = reasoningIndicators.count { lowerReasoning.contains(it) }
        
        // Should have at least 2 reasoning indicators and be substantial
        return indicatorCount >= 2 && reasoning.length > 50
    }
    
    /**
     * Calculate confidence based on various factors
     */
    private fun calculateConfidence(
        completion: String,
        reasoning: String,
        hasValidReasoning: Boolean
    ): Float {
        var confidence = 0.6f
        
        // Increase confidence for valid reasoning
        if (hasValidReasoning) {
            confidence += 0.2f
        }
        
        // Increase confidence for substantial completion
        if (completion.length > 10) {
            confidence += 0.1f
        }
        
        // Increase confidence for structured code
        if (completion.contains("{") || completion.contains("(")) {
            confidence += 0.05f
        }
        
        // Decrease confidence for very short completions
        if (completion.length < 3) {
            confidence -= 0.2f
        }
        
        return confidence.coerceIn(0.0f, 1.0f)
    }
}
