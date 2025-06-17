package com.zps.zest.completion.diff

import com.github.difflib.DiffUtils
import com.github.difflib.patch.DeltaType

/**
 * Utility class for performing word-level diffing on text
 */
object WordDiffUtil {
    
    /**
     * Represents a word segment with its change type
     */
    data class WordSegment(
        val text: String,
        val type: ChangeType
    )
    
    enum class ChangeType {
        UNCHANGED,
        ADDED,
        DELETED,
        MODIFIED
    }
    
    /**
     * Result of word-level diff containing segments for both original and modified text
     */
    data class WordDiffResult(
        val originalSegments: List<WordSegment>,
        val modifiedSegments: List<WordSegment>
    )
    
    /**
     * Normalize code for better diffing (handles whitespace and formatting)
     */
    fun normalizeCode(text: String, language: String? = null): String {
        var normalized = text
            // Convert tabs to spaces
            .replace("\t", "    ")
            // Ensure consistent line endings
            .replace("\r\n", "\n")
            .replace("\r", "\n")
        
        // Apply language-specific normalization
        if (language?.lowercase() == "java") {
            normalized = normalizeJavaCode(normalized)
        }
        
        // Trim trailing whitespace from each line
        return normalized.lines()
            .joinToString("\n") { it.trimEnd() }
    }
    
    /**
     * Java-specific code normalization for better semantic diffing
     */
    private fun normalizeJavaCode(code: String): String {
        // Normalize brace positioning to same line (K&R style)
        var normalized = code
            // Opening brace on new line -> same line
            .replace(Regex("\\s*\n\\s*\\{"), " {")
            // Multiple spaces before opening brace -> single space
            .replace(Regex("\\s+\\{"), " {")
            // Closing brace with extra spaces
            .replace(Regex("\\}\\s+"), "} ")
            // Empty blocks: { } -> {}
            .replace(Regex("\\{\\s+\\}"), "{}")
            
        // Normalize array brackets
        normalized = normalized
            // Type[] name -> Type[] name (ensure space)
            .replace(Regex("(\\w)\\[\\]"), "$1[]")
            // Type [] name -> Type[] name (remove space)
            .replace(Regex("(\\w)\\s+\\[\\]"), "$1[]")
            
        // Normalize parentheses
        normalized = normalized
            // if( -> if (
            .replace(Regex("\\b(if|for|while|switch|catch)\\("), "$1 (")
            // ) { -> ) {
            .replace(Regex("\\)\\s*\\{"), ") {")
            
        // Normalize empty lines (reduce multiple empty lines to single)
        normalized = normalized
            .replace(Regex("\n\\s*\n\\s*\n"), "\n\n")
            
        // Normalize semicolons
        normalized = normalized
            // ; } -> ;}
            .replace(Regex(";\\s+\\}"), ";}")
            // Multiple semicolons
            .replace(Regex(";;+"), ";")
            
        return normalized
    }
    
    /**
     * Perform word-level diff between two lines of text
     */
    fun diffWords(original: String, modified: String, language: String? = null): WordDiffResult {
        // Normalize the text first
        val normalizedOriginal = normalizeCode(original, language)
        val normalizedModified = normalizeCode(modified, language)
        
        // Split into words/tokens while preserving whitespace
        val originalTokens = tokenize(normalizedOriginal)
        val modifiedTokens = tokenize(normalizedModified)
        
        // Perform diff on tokens
        val patch = DiffUtils.diff(originalTokens, modifiedTokens)
        
        // Build segments for original text
        val originalSegments = mutableListOf<WordSegment>()
        var originalIndex = 0
        
        // Build segments for modified text
        val modifiedSegments = mutableListOf<WordSegment>()
        var modifiedIndex = 0
        
        // Process each delta
        for (delta in patch.deltas) {
            // Add unchanged tokens before this delta
            while (originalIndex < delta.source.position) {
                val token = originalTokens[originalIndex]
                originalSegments.add(WordSegment(token, ChangeType.UNCHANGED))
                modifiedSegments.add(WordSegment(modifiedTokens[modifiedIndex], ChangeType.UNCHANGED))
                originalIndex++
                modifiedIndex++
            }
            
            // Process the delta
            when (delta.type) {
                DeltaType.DELETE -> {
                    // Tokens only in original
                    delta.source.lines.forEach { token ->
                        originalSegments.add(WordSegment(token, ChangeType.DELETED))
                    }
                    originalIndex += delta.source.size()
                }
                DeltaType.INSERT -> {
                    // Tokens only in modified
                    delta.target.lines.forEach { token ->
                        modifiedSegments.add(WordSegment(token, ChangeType.ADDED))
                    }
                    modifiedIndex += delta.target.size()
                }
                DeltaType.CHANGE -> {
                    // Changed tokens
                    delta.source.lines.forEach { token ->
                        originalSegments.add(WordSegment(token, ChangeType.MODIFIED))
                    }
                    delta.target.lines.forEach { token ->
                        modifiedSegments.add(WordSegment(token, ChangeType.MODIFIED))
                    }
                    originalIndex += delta.source.size()
                    modifiedIndex += delta.target.size()
                }
                else -> {
                    // EQUAL - shouldn't happen in deltas but handle it
                    delta.source.lines.forEach { token ->
                        originalSegments.add(WordSegment(token, ChangeType.UNCHANGED))
                    }
                    delta.target.lines.forEach { token ->
                        modifiedSegments.add(WordSegment(token, ChangeType.UNCHANGED))
                    }
                    originalIndex += delta.source.size()
                    modifiedIndex += delta.target.size()
                }
            }
        }
        
        // Add remaining unchanged tokens
        while (originalIndex < originalTokens.size && modifiedIndex < modifiedTokens.size) {
            originalSegments.add(WordSegment(originalTokens[originalIndex], ChangeType.UNCHANGED))
            modifiedSegments.add(WordSegment(modifiedTokens[modifiedIndex], ChangeType.UNCHANGED))
            originalIndex++
            modifiedIndex++
        }
        
        return WordDiffResult(originalSegments, modifiedSegments)
    }
    
    /**
     * Tokenize text into words and whitespace, preserving all characters
     * Enhanced for better Java code tokenization
     */
    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val currentToken = StringBuilder()
        var tokenType: TokenType? = null
        
        for (char in text) {
            val charType = getTokenType(char)
            
            if (charType != tokenType && currentToken.isNotEmpty()) {
                // Token type changed, save current token
                tokens.add(currentToken.toString())
                currentToken.clear()
            }
            
            currentToken.append(char)
            tokenType = charType
        }
        
        if (currentToken.isNotEmpty()) {
            tokens.add(currentToken.toString())
        }
        
        return tokens
    }
    
    private enum class TokenType {
        WORD,           // Letters, digits, underscore
        WHITESPACE,     // Spaces, tabs, newlines
        OPERATOR,       // +, -, *, /, =, <, >, !, &, |, etc.
        DELIMITER,      // (, ), [, ], {, }
        PUNCTUATION,    // ., ,, ;, :
        OTHER
    }
    
    private fun getTokenType(char: Char): TokenType {
        return when {
            char.isLetterOrDigit() || char == '_' -> TokenType.WORD
            char.isWhitespace() -> TokenType.WHITESPACE
            char in "+-*/%=<>!&|^~" -> TokenType.OPERATOR
            char in "()[]{}" -> TokenType.DELIMITER
            char in ".,:;" -> TokenType.PUNCTUATION
            else -> TokenType.OTHER
        }
    }
    
    /**
     * Merge consecutive segments of the same type for cleaner rendering
     */
    fun mergeSegments(segments: List<WordSegment>): List<WordSegment> {
        if (segments.isEmpty()) return emptyList()
        
        val merged = mutableListOf<WordSegment>()
        var current = segments.first()
        
        for (i in 1 until segments.size) {
            val next = segments[i]
            if (next.type == current.type) {
                // Merge with current
                current = WordSegment(current.text + next.text, current.type)
            } else {
                // Add current and start new
                merged.add(current)
                current = next
            }
        }
        
        merged.add(current)
        return merged
    }
}
