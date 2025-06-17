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
    fun normalizeCode(text: String): String {
        return text
            // Convert tabs to spaces
            .replace("\t", "    ")
            // Trim trailing whitespace from each line
            .lines()
            .joinToString("\n") { it.trimEnd() }
            // Ensure consistent line endings
            .replace("\r\n", "\n")
            .replace("\r", "\n")
    }
    
    /**
     * Perform word-level diff between two lines of text
     */
    fun diffWords(original: String, modified: String): WordDiffResult {
        // Normalize the text first
        val normalizedOriginal = normalizeCode(original)
        val normalizedModified = normalizeCode(modified)
        
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
     */
    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val currentToken = StringBuilder()
        var inWord = false
        
        for (char in text) {
            val isWordChar = char.isLetterOrDigit() || char == '_'
            
            if (isWordChar != inWord && currentToken.isNotEmpty()) {
                // Transition between word and non-word
                tokens.add(currentToken.toString())
                currentToken.clear()
            }
            
            currentToken.append(char)
            inWord = isWordChar
        }
        
        if (currentToken.isNotEmpty()) {
            tokens.add(currentToken.toString())
        }
        
        return tokens
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
