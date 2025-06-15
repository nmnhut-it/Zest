package com.zps.zest.completion.parser

import com.zps.zest.completion.ZestCompletionProvider

/**
 * Simple response parser that cleans up LLM responses for code completion
 * Enhanced with thread-safe overlap detection for Qwen 2.5 Coder FIM format
 * Now strategy-aware: SIMPLE shows first line only, LEAN shows full completion
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
     * Parse response and extract first line only (for display purposes)
     * This is separate from overlap detection to preserve the full response for overlap analysis
     */
    fun parseResponseForDisplay(response: String): String {
        val cleanedResponse = parseResponse(response)
        return extractFirstLineOnly(cleanedResponse)
    }
    
    /**
     * Extract only the first meaningful line of code from the response
     * Enhanced to preserve code fragments that result from overlap detection
     */
    private fun extractFirstLineOnly(text: String): String {
        val lines = text.lines()
        
        // Find the first non-empty line with actual code content
        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isNotEmpty()) {
                // For overlap-adjusted completions, be more permissive
                if (!isMetaContent(trimmedLine)) {
                    return line // Return with original indentation
                }
            }
        }
        
        // If no line passed the meta-content filter, but we have non-empty lines,
        // return the first non-empty line anyway (might be a valid code fragment)
        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isNotEmpty()) {
                return line
            }
        }
        
        return ""
    }
    
    /**
     * Check if a line contains meta-content rather than actual code
     */
    private fun isMetaContent(line: String): Boolean {
        val lower = line.lowercase().trim()
        
        // Don't filter out short code fragments that might be meaningful
        if (line.trim().length <= 20) {
            // For short lines, only filter obvious meta content
            return lower.startsWith("here") || 
                   lower.startsWith("the ") || 
                   lower.startsWith("this ") ||
                   lower.startsWith("i ") ||
                   lower.startsWith("you ") ||
                   lower == "explanation" ||
                   lower == "suggestion" ||
                   lower == "completion"
        }
        
        // For longer lines, use more comprehensive filtering
        return lower.startsWith("here") || 
               lower.startsWith("the") || 
               lower.startsWith("this") ||
               lower.startsWith("i ") ||
               lower.startsWith("you ") ||
               lower.contains("explanation") ||
               lower.contains("suggestion") ||
               lower.contains("completion") ||
               lower.contains("will add") ||
               lower.contains("should be")
    }
    
    /**
     * Parse response with thread-safe overlap detection and adjustment
     * Strategy-aware: SIMPLE shows first line only, LEAN shows full completion
     */
    fun parseResponseWithOverlapDetection(
        response: String,
        documentText: String,
        cursorOffset: Int,
        strategy: ZestCompletionProvider.CompletionStrategy = ZestCompletionProvider.CompletionStrategy.SIMPLE
    ): String {
        if (response.isBlank()) return ""
        
        // First, clean the response but keep all lines for overlap detection
        val cleanedResponse = parseResponse(response)
        if (cleanedResponse.isBlank()) return ""
        
        // Extract recent user input (thread-safe string operations only)
        val recentUserInput = extractRecentUserInputSafe(documentText, cursorOffset)
        
        // Detect and handle overlaps with the FULL cleaned response
        val overlapResult = overlapDetector.adjustCompletionForOverlap(
            userTypedText = recentUserInput,
            completionText = cleanedResponse,
            cursorOffset = cursorOffset,
            documentText = documentText
        )
        
        // Handle edge cases (thread-safe)
        val adjustedCompletion = overlapDetector.handleEdgeCases(recentUserInput, overlapResult.adjustedCompletion)
        
        // Strategy-aware display logic
        val finalCompletion = when (strategy) {
            ZestCompletionProvider.CompletionStrategy.SIMPLE -> {
                // SIMPLE strategy: show only first line for clean inline display
                extractFirstLineOnly(adjustedCompletion)
            }
            ZestCompletionProvider.CompletionStrategy.LEAN -> {
                // LEAN strategy: show full completion (multi-line allowed)
                adjustedCompletion
            }
            ZestCompletionProvider.CompletionStrategy.BLOCK_REWRITE -> {
                // BLOCK_REWRITE strategy: not used for inline completions (uses floating windows)
                ""
            }
        }
        
        // Debug logging with more detail
        if (overlapResult.overlapType != ZestCompletionOverlapDetector.OverlapType.NONE) {
            System.out.println("=== OVERLAP DETECTION DEBUG ===")
            System.out.println("Strategy: $strategy")
            System.out.println("User input: '$recentUserInput'")
            System.out.println("Original completion: '$cleanedResponse'")
            System.out.println("Overlap type: ${overlapResult.overlapType}")
            System.out.println("Overlap length: ${overlapResult.overlapLength}")
            System.out.println("Adjusted completion: '$adjustedCompletion'")
            System.out.println("Final completion: '$finalCompletion'")
            System.out.println("=== END DEBUG ===")
        }
        
        return finalCompletion
    }
    
    
    /**
     * Enhanced thread-safe extraction of recent user input
     * Analyzes current line and meaningful recent typing
     */
    private fun extractRecentUserInputSafe(documentText: String, cursorOffset: Int): String {
        if (cursorOffset <= 0 || cursorOffset > documentText.length) return ""
        
        // Get current line
        val lineStart = documentText.lastIndexOf('\n', cursorOffset - 1) + 1
        val currentLine = documentText.substring(lineStart, cursorOffset)
        
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
