package com.zps.zest.completion.parser

import com.intellij.openapi.diagnostic.Logger

/**
 * Detects and handles overlapping text between user input and completion suggestions
 * to prevent duplicate text insertion
 */
class ZestCompletionOverlapDetector {
    private val logger = Logger.getInstance(ZestCompletionOverlapDetector::class.java)
    
    data class OverlapResult(
        val adjustedCompletion: String,
        val overlapLength: Int,
        val overlapType: OverlapType
    )
    
    enum class OverlapType {
        NONE,           // No overlap detected
        EXACT_PREFIX,   // User typed exact prefix of completion
        FUZZY_PREFIX,   // User typed similar prefix (case/spacing differences)
        PARTIAL_WORD,   // User typed part of first word in completion
        FULL_WORD       // User typed complete first word in completion
    }
    
    /**
     * Adjusts completion text based on what the user has already typed
     */
    fun adjustCompletionForOverlap(
        userTypedText: String,
        completionText: String,
        cursorOffset: Int,
        documentText: String
    ): OverlapResult {
        
        if (completionText.isBlank()) {
            return OverlapResult(completionText, 0, OverlapType.NONE)
        }
        
        // Extract what the user has typed recently at cursor position
        val recentUserInput = extractRecentUserInput(documentText, cursorOffset)
        
        logger.debug("Overlap detection: userTyped='$recentUserInput', completion='${completionText.take(50)}...'")
        
        // Try different overlap detection strategies
        val strategies = listOf(
            ::detectExactPrefixOverlap,
            ::detectFuzzyPrefixOverlap,
            ::detectPartialWordOverlap,
            ::detectFullWordOverlap
        )
        
        for (strategy in strategies) {
            val result = strategy(recentUserInput, completionText)
            if (result.overlapType != OverlapType.NONE) {
                logger.debug("Detected overlap: type=${result.overlapType}, length=${result.overlapLength}")
                return result
            }
        }
        
        // No overlap detected, return original completion
        return OverlapResult(completionText, 0, OverlapType.NONE)
    }
    
    /**
     * Extract recent user input from the document at cursor position
     */
    private fun extractRecentUserInput(documentText: String, cursorOffset: Int): String {
        if (cursorOffset <= 0) return ""
        
        val startOffset = maxOf(0, cursorOffset - MAX_OVERLAP_DETECTION_LENGTH)
        val textBeforeCursor = documentText.substring(startOffset, cursorOffset)
        
        // Get the current incomplete token/identifier
        val tokenMatch = Regex("""(\w+)$""").find(textBeforeCursor)
        if (tokenMatch != null) {
            return tokenMatch.value
        }
        
        // Fallback: get last few characters
        return textBeforeCursor.takeLast(10).trim()
    }
    
    /**
     * Detect exact prefix overlap (case-sensitive)
     */
    private fun detectExactPrefixOverlap(userInput: String, completion: String): OverlapResult {
        if (userInput.isEmpty()) return OverlapResult(completion, 0, OverlapType.NONE)
        
        if (completion.startsWith(userInput)) {
            val adjustedCompletion = completion.substring(userInput.length)
            return OverlapResult(adjustedCompletion, userInput.length, OverlapType.EXACT_PREFIX)
        }
        
        return OverlapResult(completion, 0, OverlapType.NONE)
    }
    
    /**
     * Detect fuzzy prefix overlap (case-insensitive, whitespace-tolerant)
     */
    private fun detectFuzzyPrefixOverlap(userInput: String, completion: String): OverlapResult {
        if (userInput.isEmpty()) return OverlapResult(completion, 0, OverlapType.NONE)
        
        val normalizedUserInput = userInput.lowercase().replace(Regex("\\s+"), "")
        val normalizedCompletion = completion.lowercase().replace(Regex("\\s+"), "")
        
        if (normalizedCompletion.startsWith(normalizedUserInput)) {
            // Find the actual overlap length in the original completion
            val overlapLength = findActualOverlapLength(userInput, completion)
            if (overlapLength > 0) {
                val adjustedCompletion = completion.substring(overlapLength)
                return OverlapResult(adjustedCompletion, overlapLength, OverlapType.FUZZY_PREFIX)
            }
        }
        
        return OverlapResult(completion, 0, OverlapType.NONE)
    }
    
    /**
     * Detect partial word overlap (user typed part of first word)
     */
    private fun detectPartialWordOverlap(userInput: String, completion: String): OverlapResult {
        if (userInput.isEmpty()) return OverlapResult(completion, 0, OverlapType.NONE)
        
        // Extract first word from completion
        val firstWordMatch = Regex("""^(\w+)""").find(completion)
        if (firstWordMatch != null) {
            val firstWord = firstWordMatch.value
            
            // Check if user input is a prefix of the first word
            if (firstWord.startsWith(userInput, ignoreCase = true)) {
                val adjustedCompletion = completion.substring(userInput.length)
                return OverlapResult(adjustedCompletion, userInput.length, OverlapType.PARTIAL_WORD)
            }
        }
        
        return OverlapResult(completion, 0, OverlapType.NONE)
    }
    
    /**
     * Detect full word overlap (user typed complete first word)
     */
    private fun detectFullWordOverlap(userInput: String, completion: String): OverlapResult {
        if (userInput.isEmpty()) return OverlapResult(completion, 0, OverlapType.NONE)
        
        val userWords = userInput.trim().split(Regex("\\s+"))
        val completionWords = completion.trim().split(Regex("\\s+"))
        
        if (userWords.isNotEmpty() && completionWords.isNotEmpty()) {
            val lastUserWord = userWords.last()
            val firstCompletionWord = completionWords.first()
            
            if (lastUserWord.equals(firstCompletionWord, ignoreCase = true)) {
                // Remove the overlapping word from completion
                val remainingWords = completionWords.drop(1)
                val adjustedCompletion = remainingWords.joinToString(" ")
                return OverlapResult(adjustedCompletion, firstCompletionWord.length, OverlapType.FULL_WORD)
            }
        }
        
        return OverlapResult(completion, 0, OverlapType.NONE)
    }
    
    /**
     * Find the actual overlap length in the original text considering case and whitespace
     */
    private fun findActualOverlapLength(userInput: String, completion: String): Int {
        var overlapLength = 0
        val minLength = minOf(userInput.length, completion.length)
        
        for (i in 0 until minLength) {
            if (userInput[i].lowercase() == completion[i].lowercase()) {
                overlapLength++
            } else {
                break
            }
        }
        
        return overlapLength
    }
    
    /**
     * Detect common edge cases and handle them appropriately
     */
    fun handleEdgeCases(
        userTypedText: String,
        completionText: String
    ): String {
        var adjusted = completionText
        
        // Handle duplicate assignment operators
        if (userTypedText.endsWith("=") && completionText.startsWith("=")) {
            adjusted = completionText.substring(1).trimStart()
        }
        
        // Handle duplicate parentheses
        if (userTypedText.endsWith("(") && completionText.startsWith("(")) {
            adjusted = completionText.substring(1)
        }
        
        // Handle duplicate semicolons
        if (userTypedText.endsWith(";") && completionText.startsWith(";")) {
            adjusted = completionText.substring(1).trimStart()
        }
        
        // Handle duplicate dots for method chaining
        if (userTypedText.endsWith(".") && completionText.startsWith(".")) {
            adjusted = completionText.substring(1)
        }
        
        return adjusted
    }
    
    companion object {
        private const val MAX_OVERLAP_DETECTION_LENGTH = 50
    }
}
