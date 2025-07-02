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
     * Structure to hold parsed response with context
     */
    private data class ParsedResponse(
        val prefix: String,
        val completion: String,
        val suffix: String,
        val reasoning: String
    )
    
    /**
     * Parse a reasoning-based response from the lean strategy with overlap detection
     */
    fun parseReasoningResponse(
        response: String,
        documentText: String,
        offset: Int
    ): LeanReasoningResult {
        
        if (response.isBlank()) {
            return LeanReasoningResult("", "", 0.0f, false)
        }
        
        // Extract structured response with prefix/completion/suffix
        val parsedResponse = extractStructuredResponse(response)
        
        // Use the completion from the structured response
        var cleanedCompletion = parsedResponse.completion
        
        // If structured parsing failed, fall back to old method
        if (cleanedCompletion.isEmpty()) {
            val (reasoning, completion) = extractReasoningAndCompletion(response)
            cleanedCompletion = extractWrappedContent(completion)
            
            // Update parsed response for consistency
            parsedResponse.copy(
                completion = cleanedCompletion,
                reasoning = reasoning
            )
        }
        
        // Verify prefix/suffix match if provided
        if (parsedResponse.prefix.isNotEmpty() || parsedResponse.suffix.isNotEmpty()) {
            verifyContextMatch(parsedResponse, documentText, offset)
        }
        
        // Apply overlap detection to the cleaned completion
        // COMMENTED OUT: Overlap detection disabled for lean mode to rely on structured response format
        /*
        val adjustedCompletion = if (cleanedCompletion.isNotEmpty()) {
            val recentUserInput = extractRecentUserInputSafe(documentText, offset)
            val overlapResult = overlapDetector.adjustCompletionForOverlap(
                userTypedText = recentUserInput,
                completionText = cleanedCompletion,
                cursorOffset = offset,
                documentText = documentText
            )
            
            // Debug logging for lean strategy overlap detection
            if (overlapResult.prefixOverlapLength > 0 || overlapResult.suffixOverlapLength > 0) {
                System.out.println("=== LEAN OVERLAP DETECTION DEBUG ===")
                System.out.println("User input: '$recentUserInput'")
                System.out.println("Original completion: '$cleanedCompletion'")
                System.out.println("Prefix overlap: ${overlapResult.prefixOverlapLength}")
                System.out.println("Suffix overlap: ${overlapResult.suffixOverlapLength}")
                System.out.println("Final completion: '${overlapResult.adjustedCompletion}'")
                System.out.println("=== END LEAN DEBUG ===")
            }
            
            overlapResult.adjustedCompletion
        } else {
            cleanedCompletion
        }
        */
        
        // Use cleaned completion directly without overlap detection
        val adjustedCompletion = cleanedCompletion
        
        // Validate the reasoning
        val hasValidReasoning = validateReasoning(parsedResponse.reasoning)
        
        // Calculate confidence with context verification bonus
        val contextMatchBonus = if (parsedResponse.prefix.isNotEmpty() || parsedResponse.suffix.isNotEmpty()) 0.1f else 0f
        val confidence = calculateConfidence(adjustedCompletion, parsedResponse.reasoning, hasValidReasoning) + contextMatchBonus
        
        return LeanReasoningResult(
            completionText = adjustedCompletion,
            reasoning = parsedResponse.reasoning,
            confidence = confidence.coerceIn(0.0f, 1.0f),
            hasValidReasoning = hasValidReasoning
        )
    }
    
    /**
     * Extract structured response with prefix/completion/suffix format
     */
    private fun extractStructuredResponse(response: String): ParsedResponse {
        var prefix = ""
        var completion = ""
        var suffix = ""
        var reasoning = ""
        
        // Extract prefix
        val prefixPattern = Regex("<prefix>\\s*(.+?)\\s*</prefix>", RegexOption.DOT_MATCHES_ALL)
        val prefixMatch = prefixPattern.find(response)
        if (prefixMatch != null) {
            prefix = prefixMatch.groupValues[1].trim()
        }
        
        // Extract completion
        val completionPattern = Regex("<completion>\\s*(.+?)\\s*</completion>", RegexOption.DOT_MATCHES_ALL)
        val completionMatch = completionPattern.find(response)
        if (completionMatch != null) {
            completion = completionMatch.groupValues[1].trim()
        }
        
        // Extract suffix
        val suffixPattern = Regex("<suffix>\\s*(.+?)\\s*</suffix>", RegexOption.DOT_MATCHES_ALL)
        val suffixMatch = suffixPattern.find(response)
        if (suffixMatch != null) {
            suffix = suffixMatch.groupValues[1].trim()
        }
        
        // Extract reasoning (everything before the structured tags)
        if (prefixMatch != null) {
            reasoning = response.substring(0, prefixMatch.range.first).trim()
        } else if (completionMatch != null) {
            reasoning = response.substring(0, completionMatch.range.first).trim()
        }
        
        // Clean up reasoning from any instruction text
        reasoning = reasoning
            .replace("**Response Format:**", "")
            .replace("You must provide your response in this EXACT format", "")
            .trim()
        
        System.out.println("=== STRUCTURED RESPONSE PARSING ===")
        System.out.println("Prefix: '${prefix.take(50)}${if (prefix.length > 50) "..." else ""}'")
        System.out.println("Completion: '${completion.take(50)}${if (completion.length > 50) "..." else ""}'")
        System.out.println("Suffix: '${suffix.take(50)}${if (suffix.length > 50) "..." else ""}'")
        System.out.println("=== END STRUCTURED PARSING ===")
        
        return ParsedResponse(prefix, completion, suffix, reasoning)
    }
    
    /**
     * Verify that the provided prefix/suffix match the actual document
     */
    private fun verifyContextMatch(
        parsedResponse: ParsedResponse,
        documentText: String,
        offset: Int
    ) {
        // Get actual context from document
        val contextBefore = getContextBeforeCursor(documentText, offset, 200)
        val contextAfter = getContextAfterCursor(documentText, offset, 200)
        
        // Normalize for comparison
        val normalizedPrefix = parsedResponse.prefix.trim()
        val normalizedSuffix = parsedResponse.suffix.trim()
        
        // Check prefix match
        if (normalizedPrefix.isNotEmpty()) {
            val prefixMatches = contextBefore.trimEnd().endsWith(normalizedPrefix)
            if (!prefixMatches) {
                System.out.println("WARNING: Prefix mismatch!")
                System.out.println("Expected prefix: '$normalizedPrefix'")
                System.out.println("Actual context ends with: '${contextBefore.takeLast(normalizedPrefix.length)}'")
            }
        }
        
        // Check suffix match
        if (normalizedSuffix.isNotEmpty()) {
            val suffixMatches = contextAfter.trimStart().startsWith(normalizedSuffix)
            if (!suffixMatches) {
                System.out.println("WARNING: Suffix mismatch!")
                System.out.println("Expected suffix: '$normalizedSuffix'")
                System.out.println("Actual context starts with: '${contextAfter.take(normalizedSuffix.length)}'")
            }
        }
    }
    
    /**
     * Get context before cursor
     */
    private fun getContextBeforeCursor(documentText: String, cursorOffset: Int, limit: Int): String {
        if (cursorOffset <= 0) return ""
        val startOffset = maxOf(0, cursorOffset - limit)
        return documentText.substring(startOffset, cursorOffset)
    }
    
    /**
     * Get context after cursor
     */
    private fun getContextAfterCursor(documentText: String, cursorOffset: Int, limit: Int): String {
        if (cursorOffset >= documentText.length) return ""
        val endOffset = minOf(documentText.length, cursorOffset + limit)
        return documentText.substring(cursorOffset, endOffset)
    }
    
    /**
     * Enhanced thread-safe extraction of recent user input (copied from ZestSimpleResponseParser)
     * ENHANCED: Try matching the whole line trimmed first, then other cases
     */
    private fun extractRecentUserInputSafe(documentText: String, cursorOffset: Int): String {
        if (cursorOffset <= 0 || cursorOffset > documentText.length) return ""
        
        // Get current line up to cursor
        val lineStart = documentText.lastIndexOf('\n', cursorOffset - 1) + 1
        val currentLine = documentText.substring(lineStart, cursorOffset)
        
        // ENHANCED: Try whole line trimmed first (most comprehensive match)
        val trimmedLine = currentLine.trim()
        if (trimmedLine.isNotEmpty() && trimmedLine.length <= 50) { // Reasonable length limit
            return trimmedLine
        }
        
        // Extract meaningful recent input with better handling for single characters
        return when {
            // Get incomplete identifier/word (most common case)
            currentLine.matches(Regex(".*\\w+$")) -> {
                val match = Regex("(\\w+)$").find(currentLine)
                match?.value ?: ""
            }
            // Get single character that might be start of identifier
            currentLine.matches(Regex(".*\\w$")) -> {
                val match = Regex("(\\w)$").find(currentLine)
                match?.value ?: ""
            }
            // Get operator/symbol sequence (e.g., "=", "->", ".")
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
     * Extract reasoning and completion from the response (fallback method)
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
