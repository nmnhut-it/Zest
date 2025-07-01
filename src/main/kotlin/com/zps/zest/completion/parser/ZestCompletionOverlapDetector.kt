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
        
        // Get context before and after cursor for more intelligent overlap detection
        val contextBeforeCursor = getContextBeforeCursor(documentText, cursorOffset)
        val contextAfterCursor = getContextAfterCursor(documentText, cursorOffset)
        
        System.out.println("=== OVERLAP DETECTOR DEBUG ===")
        System.out.println("Context before cursor: '${contextBeforeCursor.takeLast(50)}'")
        System.out.println("Context after cursor: '${contextAfterCursor.take(50)}'")
        System.out.println("Completion: '${completionText.take(50)}...'")
        
        // Check for prefix overlap with larger context
        val prefixOverlap = findLongestPrefixOverlap(contextBeforeCursor, completionText)
        if (prefixOverlap > 0) {
            val adjustedCompletion = completionText.substring(prefixOverlap)
            System.out.println("Found prefix overlap of $prefixOverlap chars")
            return OverlapResult(adjustedCompletion, prefixOverlap, OverlapType.EXACT_PREFIX)
        }
        
        // Check for suffix overlap - if completion ends with what's already after cursor
        val suffixOverlap = findLongestSuffixOverlap(completionText, contextAfterCursor)
        if (suffixOverlap > 0) {
            val adjustedCompletion = completionText.substring(0, completionText.length - suffixOverlap)
            System.out.println("Found suffix overlap of $suffixOverlap chars")
            return OverlapResult(adjustedCompletion, suffixOverlap, OverlapType.EXACT_PREFIX)
        }
        
        // Use passed parameter with fallback to extraction if empty
        val recentUserInput = userTypedText.ifEmpty { 
            extractRecentUserInput(documentText, cursorOffset) 
        }
        
        // Special handling for whitespace-only input
        if (recentUserInput.isNotBlank() || (recentUserInput.isNotEmpty() && recentUserInput.all { it.isWhitespace() })) {
            // Check exact match with whitespace consideration
            if (completionText.startsWith(recentUserInput)) {
                val remainingCompletion = completionText.substring(recentUserInput.length)
                System.out.println("Exact prefix match found (including whitespace). Remaining: '$remainingCompletion'")
                if (remainingCompletion.isBlank()) {
                    System.out.println("User typed the entire completion, returning empty")
                    return OverlapResult("", recentUserInput.length, OverlapType.EXACT_PREFIX)
                } else {
                    System.out.println("Returning remaining completion after exact prefix")
                    return OverlapResult(remainingCompletion, recentUserInput.length, OverlapType.EXACT_PREFIX)
                }
            }
            
            // Check with normalized whitespace
            val normalizedInput = normalizeWhitespace(recentUserInput)
            val normalizedCompletion = normalizeWhitespace(completionText)
            if (normalizedInput.isNotEmpty() && normalizedCompletion.startsWith(normalizedInput)) {
                // Find actual overlap considering whitespace
                val actualOverlap = findActualOverlapWithWhitespace(recentUserInput, completionText)
                if (actualOverlap > 0) {
                    val remainingCompletion = completionText.substring(actualOverlap)
                    System.out.println("Normalized whitespace match found. Remaining: '$remainingCompletion'")
                    return OverlapResult(remainingCompletion, actualOverlap, OverlapType.FUZZY_PREFIX)
                }
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
     * Find actual overlap length when whitespace is involved
     */
    private fun findActualOverlapWithWhitespace(userInput: String, completion: String): Int {
        var userPos = 0
        var compPos = 0
        
        while (userPos < userInput.length && compPos < completion.length) {
            val userChar = userInput[userPos]
            val compChar = completion[compPos]
            
            if (userChar == compChar) {
                userPos++
                compPos++
            } else if (userChar.isWhitespace() && compChar.isWhitespace()) {
                // Skip all whitespace in both strings
                while (userPos < userInput.length && userInput[userPos].isWhitespace()) userPos++
                while (compPos < completion.length && completion[compPos].isWhitespace()) compPos++
            } else {
                // Mismatch - stop here
                break
            }
        }
        
        // If we consumed all user input, return the completion position
        return if (userPos == userInput.length) compPos else 0
    }
    
    /**
     * Get context before cursor up to a reasonable limit
     */
    private fun getContextBeforeCursor(documentText: String, cursorOffset: Int): String {
        if (cursorOffset <= 0) return ""
        val startOffset = maxOf(0, cursorOffset - 100) // Look back up to 100 chars
        return documentText.substring(startOffset, cursorOffset)
    }
    
    /**
     * Get context after cursor up to a reasonable limit
     */
    private fun getContextAfterCursor(documentText: String, cursorOffset: Int): String {
        if (cursorOffset >= documentText.length) return ""
        val endOffset = minOf(documentText.length, cursorOffset + 100) // Look ahead up to 100 chars
        return documentText.substring(cursorOffset, endOffset)
    }
    
    /**
     * Find the longest prefix overlap between context and completion
     * Enhanced to handle whitespace intelligently
     */
    private fun findLongestPrefixOverlap(contextBefore: String, completion: String): Int {
        if (contextBefore.isEmpty() || completion.isEmpty()) return 0
        
        // Try exact match first
        for (length in minOf(contextBefore.length, completion.length) downTo 1) {
            val suffix = contextBefore.takeLast(length)
            if (completion.startsWith(suffix)) {
                return length
            }
        }
        
        // Try with normalized whitespace (treating different whitespace as equivalent)
        val normalizedContext = normalizeWhitespace(contextBefore)
        val normalizedCompletion = normalizeWhitespace(completion)
        
        for (length in minOf(normalizedContext.length, normalizedCompletion.length) downTo 1) {
            val suffix = normalizedContext.takeLast(length)
            if (normalizedCompletion.startsWith(suffix)) {
                // Find the actual character count in original strings
                return findActualOverlapLength(contextBefore, completion, length)
            }
        }
        
        return 0
    }
    
    /**
     * Find the longest suffix overlap between completion and context after cursor
     * Enhanced to handle whitespace intelligently
     */
    private fun findLongestSuffixOverlap(completion: String, contextAfter: String): Int {
        if (completion.isEmpty() || contextAfter.isEmpty()) return 0
        
        // Try exact match first
        for (length in minOf(completion.length, contextAfter.length) downTo 1) {
            val prefix = contextAfter.take(length)
            if (completion.endsWith(prefix)) {
                return length
            }
        }
        
        // Try with normalized whitespace
        val normalizedCompletion = normalizeWhitespace(completion)
        val normalizedContext = normalizeWhitespace(contextAfter)
        
        for (length in minOf(normalizedCompletion.length, normalizedContext.length) downTo 1) {
            val prefix = normalizedContext.take(length)
            if (normalizedCompletion.endsWith(prefix)) {
                // Find the actual character count in original strings
                return findActualSuffixOverlapLength(completion, contextAfter, length)
            }
        }
        
        return 0
    }
    
    /**
     * Normalize whitespace for comparison
     * Converts all whitespace sequences to single spaces
     */
    private fun normalizeWhitespace(text: String): String {
        return text
            .replace(Regex("\\s+"), " ")  // Replace any whitespace sequence with single space
            .trim()
    }
    
    /**
     * Find actual character overlap length considering whitespace differences
     */
    private fun findActualOverlapLength(context: String, completion: String, normalizedLength: Int): Int {
        var contextPos = context.length - 1
        var completionPos = 0
        var normalizedCount = 0
        
        // Work backwards through context
        while (contextPos >= 0 && completionPos < completion.length && normalizedCount < normalizedLength) {
            val contextChar = context[contextPos]
            val completionChar = completion[completionPos]
            
            if (contextChar == completionChar) {
                contextPos--
                completionPos++
            } else if (contextChar.isWhitespace() && completionChar.isWhitespace()) {
                // Skip through whitespace in both strings
                while (contextPos >= 0 && context[contextPos].isWhitespace()) contextPos--
                while (completionPos < completion.length && completion[completionPos].isWhitespace()) completionPos++
            } else {
                // No match
                break
            }
            
            if (!contextChar.isWhitespace() || !completionChar.isWhitespace()) {
                normalizedCount++
            }
        }
        
        return completionPos
    }
    
    /**
     * Find actual suffix overlap length considering whitespace differences
     */
    private fun findActualSuffixOverlapLength(completion: String, context: String, normalizedLength: Int): Int {
        var completionPos = completion.length - 1
        var contextPos = 0
        var normalizedCount = 0
        var overlapLength = 0
        
        // Work backwards through completion and forwards through context
        while (completionPos >= 0 && contextPos < context.length && normalizedCount < normalizedLength) {
            val completionChar = completion[completionPos]
            val contextChar = context[contextPos]
            
            if (completionChar == contextChar) {
                completionPos--
                contextPos++
                overlapLength++
            } else if (completionChar.isWhitespace() && contextChar.isWhitespace()) {
                // Count all whitespace in both strings
                var completionWhitespace = 0
                var contextWhitespace = 0
                
                while (completionPos >= 0 && completion[completionPos].isWhitespace()) {
                    completionPos--
                    completionWhitespace++
                }
                while (contextPos < context.length && context[contextPos].isWhitespace()) {
                    contextPos++
                    contextWhitespace++
                }
                
                // Use the maximum whitespace length
                overlapLength += maxOf(completionWhitespace, contextWhitespace)
            } else {
                // No match
                break
            }
            
            if (!completionChar.isWhitespace() || !contextChar.isWhitespace()) {
                normalizedCount++
            }
        }
        
        return overlapLength
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
        
        // First, try to get the whole line trimmed (most comprehensive)
        val trimmedLine = currentLine.trim()
        if (trimmedLine.isNotEmpty() && trimmedLine.length <= MAX_OVERLAP_DETECTION_LENGTH) {
            return trimmedLine
        }
        
        // If line is too long, try to get meaningful segments
        
        // Get the current incomplete token/identifier (including single characters)
        val wordMatch = Regex("""(\w+)$""").find(currentLine)
        if (wordMatch != null) {
            return wordMatch.value
        }
        
        // Check for operator sequences at end
        val operatorMatch = Regex("""([=!<>+\-*/&|]+)$""").find(currentLine)
        if (operatorMatch != null) {
            return operatorMatch.value
        }
        
        // Check for single character that might be meaningful
        val singleCharMatch = Regex("""(\w)$""").find(currentLine)
        if (singleCharMatch != null) {
            return singleCharMatch.value
        }
        
        // Get recent symbol sequence (parentheses, brackets, etc.)
        val symbolMatch = Regex("""([(){}\[\].,;:]+)$""").find(currentLine)
        if (symbolMatch != null) {
            return symbolMatch.value
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
     * ENHANCED: Better handling of partial character matches to prevent hint disappearing
     */
    private fun detectFuzzyPrefixOverlap(userInput: String, completion: String): OverlapResult {
        if (userInput.isEmpty()) return OverlapResult(completion, 0, OverlapType.NONE)
        
        val normalizedUserInput = userInput.lowercase().replace(Regex("\\s+"), "")
        val normalizedCompletion = completion.lowercase().replace(Regex("\\s+"), "")
        
        // Check for exact fuzzy match first
        if (normalizedCompletion.startsWith(normalizedUserInput)) {
            // Find the actual overlap length in the original completion
            val overlapLength = findActualOverlapLength(userInput, completion)
            if (overlapLength > 0) {
                val adjustedCompletion = completion.substring(overlapLength)
                return OverlapResult(adjustedCompletion, overlapLength, OverlapType.FUZZY_PREFIX)
            }
        }
        
        // ENHANCED: Check for similar/close matches to prevent hints from disappearing
        // If user input is similar to the start of completion, consider it a partial match
        if (normalizedUserInput.length >= 1 && normalizedCompletion.length >= 1) {
            val similarity = calculateStringSimilarity(normalizedUserInput, normalizedCompletion.take(normalizedUserInput.length))
            System.out.println("Fuzzy similarity check: '$normalizedUserInput' vs '${normalizedCompletion.take(normalizedUserInput.length)}' = $similarity")
            
            // If similarity is high, treat as fuzzy match but don't remove text
            if (similarity >= 0.7) { // 70% similarity threshold
                System.out.println("High similarity detected, treating as fuzzy match but keeping completion")
                // Return the completion without removing any text to keep the hint visible
                return OverlapResult(completion, 0, OverlapType.FUZZY_PREFIX)
            }
        }
        
        return OverlapResult(completion, 0, OverlapType.NONE)
    }
    
    /**
     * Calculate string similarity using simple character-based approach
     */
    private fun calculateStringSimilarity(str1: String, str2: String): Double {
        if (str1.isEmpty() && str2.isEmpty()) return 1.0
        if (str1.isEmpty() || str2.isEmpty()) return 0.0
        
        val maxLength = maxOf(str1.length, str2.length)
        var matchingChars = 0
        
        for (i in 0 until minOf(str1.length, str2.length)) {
            if (str1[i] == str2[i]) {
                matchingChars++
            }
        }
        
        return matchingChars.toDouble() / maxLength
    }
    
    /**
     * Detect partial word overlap (user typed part of first word)
     * ENHANCED: More lenient matching to prevent hints from disappearing
     */
    private fun detectPartialWordOverlap(userInput: String, completion: String): OverlapResult {
        if (userInput.isEmpty()) return OverlapResult(completion, 0, OverlapType.NONE)
        
        // Extract first word from completion
        val firstWordMatch = Regex("""^(\w+)""").find(completion)
        if (firstWordMatch != null) {
            val firstWord = firstWordMatch.value
            
            // Check if user input is a prefix of the first word (case-insensitive)
            if (firstWord.startsWith(userInput, ignoreCase = true)) {
                val adjustedCompletion = completion.substring(userInput.length)
                return OverlapResult(adjustedCompletion, userInput.length, OverlapType.PARTIAL_WORD)
            }
            
            // ENHANCED: Check for similar/close matches
            if (userInput.length >= 2 && firstWord.length >= 2) {
                val similarity = calculateStringSimilarity(
                    userInput.lowercase(), 
                    firstWord.take(userInput.length).lowercase()
                )
                System.out.println("Partial word similarity: '$userInput' vs '${firstWord.take(userInput.length)}' = $similarity")
                
                // If user input is similar to the beginning of the first word, keep the completion visible
                if (similarity >= 0.6) { // 60% similarity threshold for partial words
                    System.out.println("Partial word similarity detected, keeping completion visible")
                    // Don't remove any text, just mark as partial match to keep hint visible
                    return OverlapResult(completion, 0, OverlapType.PARTIAL_WORD)
                }
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
     * Enhanced to handle whitespace variations
     */
    fun handleEdgeCases(
        userTypedText: String,
        completionText: String
    ): String {
        var adjusted = completionText
        
        // Handle whitespace normalization first
        adjusted = handleWhitespaceEdgeCases(userTypedText, adjusted)
        
        // Handle duplicate operators and symbols
        val duplicatePatterns = listOf(
            "=" to "=",
            "==" to "==",
            "!=" to "!=",
            "+=" to "+=",
            "-=" to "-=",
            "*=" to "*=",
            "/=" to "/=",
            "(" to "(",
            ")" to ")",
            "{" to "{",
            "}" to "}",
            "[" to "[",
            "]" to "]",
            ";" to ";",
            ":" to ":",
            "," to ",",
            "." to ".",
            "->" to "->",
            "=>" to "=>",
            "::" to "::",
            "..." to "...",
            "?" to "?",
            "!" to "!",
            "&&" to "&&",
            "||" to "||",
            "++" to "++",
            "--" to "--",
            "<<" to "<<",
            ">>" to ">>",
            "<" to "<",
            ">" to ">",
            "<=" to "<=",
            ">=" to ">="
        )
        
        // Check each pattern
        for ((suffix, prefix) in duplicatePatterns) {
            if (userTypedText.endsWith(suffix) && adjusted.startsWith(prefix)) {
                // Remove the duplicate, preserving any whitespace after it
                adjusted = adjusted.substring(prefix.length)
                // Only trim start if the next character is whitespace
                if (adjusted.startsWith(" ") || adjusted.startsWith("\t")) {
                    adjusted = adjusted.trimStart()
                }
            }
        }
        
        // Handle keyword duplicates
        val keywords = listOf(
            "public", "private", "protected", "static", "final", "abstract",
            "class", "interface", "enum", "extends", "implements",
            "void", "int", "String", "boolean", "double", "float", "long", "char",
            "if", "else", "for", "while", "do", "switch", "case", "default",
            "return", "break", "continue", "throw", "throws", "try", "catch", "finally",
            "new", "this", "super", "import", "package",
            "var", "val", "fun", "override", "open", "sealed", "data",
            "const", "let", "function", "async", "await", "export", "import"
        )
        
        // Check if user typed a keyword and completion starts with the same keyword
        val lastWord = userTypedText.split(Regex("\\s+")).lastOrNull()?.trim()
        if (lastWord != null && keywords.contains(lastWord.lowercase())) {
            val completionFirstWord = adjusted.split(Regex("\\s+")).firstOrNull()?.trim()
            if (completionFirstWord != null && lastWord.equals(completionFirstWord, ignoreCase = true)) {
                // Remove the duplicate keyword
                adjusted = adjusted.substring(completionFirstWord.length).trimStart()
            }
        }
        
        // Handle common patterns in method signatures
        if (userTypedText.matches(Regex(".*\\s+(\\w+)\\s*\\($"))) {
            // User typed method name and opening paren
            val methodNameMatch = Regex("(\\w+)\\s*\\($").find(userTypedText)
            if (methodNameMatch != null) {
                val methodName = methodNameMatch.groupValues[1]
                // Check if completion starts with the same method name and paren
                if (adjusted.startsWith("$methodName(") || adjusted.startsWith("$methodName (")) {
                    adjusted = adjusted.substring(methodName.length).trimStart()
                    if (adjusted.startsWith("(")) {
                        adjusted = adjusted.substring(1)
                    }
                }
            }
        }
        
        return adjusted
    }
    
    /**
     * Handle whitespace-specific edge cases
     */
    private fun handleWhitespaceEdgeCases(userTypedText: String, completionText: String): String {
        var adjusted = completionText
        
        // If user ended with whitespace and completion starts with whitespace, normalize
        if (userTypedText.isNotEmpty() && completionText.isNotEmpty()) {
            val userEndsWithWhitespace = userTypedText.last().isWhitespace()
            val completionStartsWithWhitespace = completionText.first().isWhitespace()
            
            if (userEndsWithWhitespace && completionStartsWithWhitespace) {
                // Remove leading whitespace from completion since user already has some
                adjusted = completionText.trimStart()
            }
            
            // Handle newline cases
            if (userTypedText.endsWith("\n") && adjusted.startsWith("\n")) {
                adjusted = adjusted.substring(1)
            }
            
            // Handle indentation duplication
            val lastLine = userTypedText.lines().lastOrNull() ?: ""
            if (lastLine.isNotBlank() && lastLine.all { it.isWhitespace() }) {
                // User typed only indentation on current line
                val indentLevel = lastLine.length
                val completionLines = adjusted.lines()
                if (completionLines.isNotEmpty()) {
                    val firstLineIndent = completionLines.first().takeWhile { it.isWhitespace() }
                    if (firstLineIndent.length >= indentLevel) {
                        // Remove duplicate indentation
                        adjusted = adjusted.substring(minOf(indentLevel, firstLineIndent.length))
                    }
                }
            }
        }
        
        // Handle tab vs space inconsistencies
        if (userTypedText.contains("\t") && adjusted.startsWith("    ")) {
            // User uses tabs, completion uses spaces - convert
            adjusted = adjusted.replace("    ", "\t")
        } else if (userTypedText.contains("    ") && adjusted.startsWith("\t")) {
            // User uses spaces, completion uses tabs - convert
            adjusted = adjusted.replace("\t", "    ")
        }
        
        return adjusted
    }
    
    companion object {
        private const val MAX_OVERLAP_DETECTION_LENGTH = 50
    }
}
