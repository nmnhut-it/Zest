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
     * Enhanced for first-line-only completions with better edge case handling
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
        
        System.out.println("=== OVERLAP DETECTOR DEBUG ===")
        System.out.println("User input: '$recentUserInput'")
        System.out.println("Completion: '${completionText.take(50)}...'")
        
        // Special case: if user typed exactly what completion suggests, return remaining
        if (recentUserInput.isNotEmpty() && completionText.startsWith(recentUserInput)) {
            val remainingCompletion = completionText.substring(recentUserInput.length)
            System.out.println("Exact prefix match found. Remaining: '$remainingCompletion'")
            if (remainingCompletion.isBlank()) {
                System.out.println("User typed the entire completion, returning empty")
                return OverlapResult("", recentUserInput.length, OverlapType.EXACT_PREFIX)
            } else {
                System.out.println("Returning remaining completion after exact prefix")
                return OverlapResult(remainingCompletion, recentUserInput.length, OverlapType.EXACT_PREFIX)
            }
        }
        
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
                System.out.println("Strategy ${strategy.javaClass.simpleName} detected overlap: type=${result.overlapType}, length=${result.overlapLength}")
                System.out.println("Adjusted result: '${result.adjustedCompletion}'")
                return result
            }
        }
        
        // No overlap detected, return original completion
        System.out.println("No overlap detected, returning original completion")
        return OverlapResult(completionText, 0, OverlapType.NONE)
    }
    
    /**
     * Extract recent user input from the document at cursor position
     * Enhanced to handle single character inputs better
     */
    private fun extractRecentUserInput(documentText: String, cursorOffset: Int): String {
        if (cursorOffset <= 0) return ""
        
        // Get current line up to cursor
        val lineStart = documentText.lastIndexOf('\n', cursorOffset - 1) + 1
        val currentLine = documentText.substring(lineStart, cursorOffset)
        
        // Get the current incomplete token/identifier (including single characters)
        val wordMatch = Regex("""(\w+)$""").find(currentLine)
        if (wordMatch != null) {
            return wordMatch.value
        }
        
        // Check for single character that might be meaningful
        val singleCharMatch = Regex("""(\w)$""").find(currentLine)
        if (singleCharMatch != null) {
            return singleCharMatch.value
        }
        
        // Fallback: get last few non-whitespace characters
        val nonWhitespaceMatch = Regex("""(\S+)$""").find(currentLine)
        if (nonWhitespaceMatch != null) {
            return nonWhitespaceMatch.value
        }
        
        return ""
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
