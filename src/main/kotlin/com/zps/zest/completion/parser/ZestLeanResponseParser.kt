package com.zps.zest.completion.parser

/**
 * Parses responses from the lean completion strategy
 */
class ZestLeanResponseParser {

    private companion object {
        private const val BASE_CONFIDENCE = 0.7f
        private const val MAX_TRIMMED_LINE_LENGTH = 50
        private const val FALLBACK_INPUT_LENGTH = 10
        private const val MIN_LENGTH_FOR_PENALTY = 3
        private const val SHORT_COMPLETION_PENALTY = 0.2f
    }
    
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
            return createEmptyResult()
        }
        
        val completion = extractAndCleanCompletion(response)
        val adjustedCompletion = completion // No overlap adjustment needed
        val confidence = calculateSimplifiedConfidence(adjustedCompletion)
        
        return LeanReasoningResult(
            completionText = adjustedCompletion,
            reasoning = "",
            confidence = confidence,
            hasValidReasoning = false
        )
    }
    
    private fun extractCompletionTag(response: String): String {
        val completionPattern = Regex("<code>(.+?)</code>", RegexOption.DOT_MATCHES_ALL)
        return completionPattern.find(response)?.groupValues?.get(1) ?: ""
    }
    
    private fun cleanExtractedCompletion(completion: String): String {
        return completion
            .let { removeMarkdownCodeBlocks(it) }
            .trim()
    }
    
    private fun cleanRawResponse(response: String): String {
        return response
            .removeXmlTags()
            .let { removeMarkdownCodeBlocks(it) }
            .removeExplanationText()
            .trim()
            .extractCodeFromNaturalLanguage()
    }
    
    private fun removeMarkdownCodeBlocks(text: String): String {
        // Remove triple backtick code blocks
        val codeBlockPattern = Regex("```[a-zA-Z0-9-]*\\s*(.+?)```", RegexOption.DOT_MATCHES_ALL)
        val result = codeBlockPattern.find(text)?.groupValues?.get(1) ?: text
        
        // Remove single backticks if entire content is wrapped
        return if (result.startsWith("`") && result.endsWith("`") && result.count { it == '`' } == 2) {
            result.substring(1, result.length - 1)
        } else {
            result
        }
    }
    
    private fun extractRecentUserInputSafe(documentText: String, cursorOffset: Int): String {
        if (cursorOffset <= 0 || cursorOffset > documentText.length) return ""
        
        val currentLine = getCurrentLineUpToCursor(documentText, cursorOffset)
        val trimmedLine = currentLine.trim()
        
        return if (trimmedLine.isNotEmpty() && trimmedLine.length <= MAX_TRIMMED_LINE_LENGTH) {
            trimmedLine
        } else {
            extractMeaningfulInput(currentLine)
        }
    }
    
    private fun calculateSimplifiedConfidence(completion: String): Float {
        if (completion.isBlank()) return 0.0f
        
        var confidence = BASE_CONFIDENCE
        confidence += getLengthBonus(completion.length)
        confidence += getStructureBonus(completion)
        confidence += getCompletenessBonus(completion)
        confidence -= getShortCompletionPenalty(completion.length)
        
        return confidence.coerceIn(0.0f, 1.0f)
    }

    private fun createEmptyResult(): LeanReasoningResult {
        return LeanReasoningResult("", "", 0.0f, false)
    }

    private fun extractAndCleanCompletion(response: String): String {
        val completion = extractCompletionTag(response)
        return if (completion.isEmpty()) {
            cleanRawResponse(response)
        } else {
            cleanExtractedCompletion(completion)
        }
    }

    // Overlap detection removed - method kept for compatibility
    private fun adjustCompletionForOverlap(completion: String, documentText: String, offset: Int): String {
        return completion
    }

    private fun String.removeXmlTags(): String {
        return this.replace(Regex("<[^>]+>"), "")
    }

    private fun String.removeExplanationText(): String {
        val explanationPatterns = listOf(
            Regex("^(Here's|Here is|The completion|Completion:).*?\\n", RegexOption.IGNORE_CASE),
            Regex("^(Based on|Looking at|Given).*?\\n", RegexOption.IGNORE_CASE),
            Regex("^```\\w*\\n"),
            Regex("\\n```$")
        )
        
        var result = this
        for (pattern in explanationPatterns) {
            result = result.replace(pattern, "")
        }
        return result
    }

    private fun String.extractCodeFromNaturalLanguage(): String {
        if (!this.contains("\n") || !this.lines().first().matches(Regex("^[A-Z].*"))) {
            return this
        }
        
        val lines = this.lines()
        val codeStartIndex = lines.indexOfFirst { line ->
            line.trimStart().matchesCodePattern()
        }
        
        return if (codeStartIndex > 0) {
            lines.drop(codeStartIndex).joinToString("\n").trim()
        } else {
            this
        }
    }

    private fun String.matchesCodePattern(): Boolean {
        val codeKeywords = Regex("^(if|for|while|return|var|let|const|public|private|protected|class|function|def|import|from)\\b.*")
        val identifierPattern = Regex("^[a-zA-Z_$][a-zA-Z0-9_$]*\\s*[=.(].*")
        val bracePattern = Regex("^}.*")
        
        return this.matches(codeKeywords) || this.matches(identifierPattern) || this.matches(bracePattern)
    }

    private fun getCurrentLineUpToCursor(documentText: String, cursorOffset: Int): String {
        val lineStart = documentText.lastIndexOf('\n', cursorOffset - 1) + 1
        return documentText.substring(lineStart, cursorOffset)
    }

    private fun extractMeaningfulInput(currentLine: String): String {
        val patterns = listOf(
            Regex("(\\w+)$") to "word completion",
            Regex("(\\w)$") to "single character",
            Regex("([^\\s\\w]+)$") to "operator/symbol",
            Regex("(\\s+)$") to "whitespace/indentation"
        )
        
        for ((pattern, _) in patterns) {
            val match = pattern.find(currentLine)
            if (match != null) {
                return match.value
            }
        }
        
        return currentLine.takeLast(FALLBACK_INPUT_LENGTH).trim()
    }

    private fun getLengthBonus(length: Int): Float {
        return when {
            length > 20 -> 0.15f
            length > 10 -> 0.1f
            length > 5 -> 0.05f
            else -> 0.0f
        }
    }

    private fun getStructureBonus(completion: String): Float {
        val structureChars = setOf('{', '(', '[')
        return if (completion.any { it in structureChars }) 0.05f else 0.0f
    }

    private fun getCompletenessBonus(completion: String): Float {
        val completionChars = setOf('}', ')', ';')
        return if (completion.any { it in completionChars }) 0.05f else 0.0f
    }

    private fun getShortCompletionPenalty(length: Int): Float {
        return if (length < MIN_LENGTH_FOR_PENALTY) SHORT_COMPLETION_PENALTY else 0.0f
    }
}