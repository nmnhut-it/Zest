package com.zps.zest.completion.parser

import com.intellij.openapi.diagnostic.Logger

/**
 * Detects and handles overlapping text between user input and completion suggestions
 * to prevent duplicate text insertion.
 *
 * Uses line-by-line comparison with code normalization for robust overlap detection.
 */
class ZestCompletionOverlapDetector {
    private val logger = Logger.getInstance(ZestCompletionOverlapDetector::class.java)

    data class OverlapResult(
        val adjustedCompletion: String,
        val prefixOverlapLength: Int,
        val suffixOverlapLength: Int
    )

    /**
     * Adjusts completion text based on what already exists in the document.
     * Uses line-by-line comparison with code normalization to detect duplicates.
     */
    fun adjustCompletionForOverlap(
        userTypedText: String,
        completionText: String,
        cursorOffset: Int,
        documentText: String
    ): OverlapResult {

        if (completionText.isBlank()) {
            return OverlapResult(completionText, 0, 0)
        }

        // Get context around cursor
        val contextBeforeCursor = getContextBeforeCursor(documentText, cursorOffset, 500)
        val contextAfterCursor = getContextAfterCursor(documentText, cursorOffset, 500)

        System.out.println("=== OVERLAP DETECTOR DEBUG ===")
        // Make whitespace visible for debugging
        val beforeDebug = contextBeforeCursor.takeLast(100)
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace(" ", "·")
        val afterDebug = contextAfterCursor.take(100)
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace(" ", "·")
        val completionDebug = completionText.take(100)
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace(" ", "·")
            
        System.out.println("Context before cursor (last 100 chars): '$beforeDebug'")
        System.out.println("Context after cursor (first 100 chars): '$afterDebug'")
        System.out.println("Completion (first 100 chars): '$completionDebug${if (completionText.length > 100) "..." else ""}'")
        
        // Check if cursor is on a blank line
        val isOnBlankLine = contextAfterCursor.trimStart() != contextAfterCursor && 
                           contextAfterCursor.trim().isNotEmpty()
        if (isOnBlankLine) {
            System.out.println("Cursor appears to be on a blank line")
        }

        // First, try character-level prefix/suffix overlap detection
        var adjustedCompletion = completionText
        var prefixOverlap = 0
        var suffixOverlap = 0

        // Detect prefix overlap
        val prefixResult = findPrefixOverlap(contextBeforeCursor, adjustedCompletion)
        if (prefixResult > 0) {
            adjustedCompletion = adjustedCompletion.substring(prefixResult)
            prefixOverlap = prefixResult
            System.out.println("Found prefix overlap of $prefixResult chars")
        }

        // Detect suffix overlap on adjusted completion
        val suffixResult = findSuffixOverlap(adjustedCompletion, contextAfterCursor)
        if (suffixResult > 0) {
            adjustedCompletion = adjustedCompletion.substring(0, adjustedCompletion.length - suffixResult)
            suffixOverlap = suffixResult
            System.out.println("Found suffix overlap of $suffixResult chars")
        }

        // If we still have content, check for line-level duplicates
        if (adjustedCompletion.isNotEmpty()) {
            val lineResult = removeLineDuplicates(adjustedCompletion, documentText, cursorOffset)
            adjustedCompletion = lineResult.adjustedText
            prefixOverlap += lineResult.removedPrefixChars
            suffixOverlap += lineResult.removedSuffixChars

            if (lineResult.removedLines > 0) {
                System.out.println("Removed ${lineResult.removedLines} duplicate lines")
            }
        }

        System.out.println("Final adjusted completion: '${adjustedCompletion.take(100).replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace(" ", "·")}${if (adjustedCompletion.length > 100) "..." else ""}'")

        return OverlapResult(adjustedCompletion, prefixOverlap, suffixOverlap)
    }

    /**
     * Normalize code for comparison by standardizing whitespace and delimiters
     */
    private fun normalizeCode(text: String): String {
        return text
            .replace("\r\n", "\n")                 // Normalize line endings
            .replace("\r", "\n")
            .replace(Regex("\\n\\s*\\{"), " {")    // C style: \n{ → space{
            .replace(Regex("\\s+"), " ")           // All whitespace to single space
            .replace(Regex("\\s*\\(\\s*"), "(")    // Normalize parentheses
            .replace(Regex("\\s*\\)\\s*"), ")")
            .replace(Regex("\\s*\\[\\s*"), "[")    // Normalize square brackets
            .replace(Regex("\\s*\\]\\s*"), "]")
            .replace(Regex("\\s*\\{\\s*"), "{")    // Normalize curly braces
            .replace(Regex("\\s*\\}\\s*"), "}")
            .trim()
    }

    /**
     * Normalize a line for comparison, considering the next line for brace style handling
     */
    private fun normalizeLineForComparison(line: String, nextLine: String?): String {
        var normalized = normalizeCode(line)

        // If next line starts with {, append it to this line for comparison
        if (nextLine != null && nextLine.trim().startsWith("{")) {
            normalized += "{"
        }

        return normalized
    }

    /**
     * Find prefix overlap between context and completion
     */
    private fun findPrefixOverlap(contextBefore: String, completion: String): Int {
        if (contextBefore.isEmpty() || completion.isEmpty()) return 0

        // Special handling: if context before cursor ends with only whitespace after the last statement,
        // we're on a blank line and should not check for overlaps with previous code
        val lastNewlineIndex = contextBefore.lastIndexOf('\n')
        if (lastNewlineIndex >= 0) {
            val afterLastNewline = contextBefore.substring(lastNewlineIndex + 1)
            // If everything after the last newline is whitespace, we're on a blank line
            if (afterLastNewline.isBlank()) {
                System.out.println("Cursor at start of blank line, no prefix overlap check needed")
                return 0
            }
        }

        // Try exact character match from end of context
        val maxLength = minOf(contextBefore.length, completion.length)

        for (length in maxLength downTo 1) {
            val contextSuffix = contextBefore.takeLast(length)
            val completionPrefix = completion.take(length)

            if (contextSuffix == completionPrefix) {
                return length
            }
        }

        // Try normalized match for code structures
        val normalizedContext = normalizeCode(contextBefore)
        val normalizedCompletion = normalizeCode(completion)

        for (length in minOf(normalizedContext.length, normalizedCompletion.length) downTo 1) {
            val contextSuffix = normalizedContext.takeLast(length)
            val completionPrefix = normalizedCompletion.take(length)

            if (contextSuffix == completionPrefix) {
                // Find actual character count in original text
                return findActualOverlapLength(contextBefore, completion, true)
            }
        }

        return 0
    }

    /**
     * Find suffix overlap between completion and context
     */
    private fun findSuffixOverlap(completion: String, contextAfter: String): Int {
        if (completion.isEmpty() || contextAfter.isEmpty()) return 0

        // Special handling: if context after cursor starts with newline/whitespace,
        // we're on a blank line and should not consider it an overlap
        if (contextAfter.trimStart() != contextAfter) {
            // There's leading whitespace (including newlines) after cursor
            // Only check for overlap if the completion also ends with similar whitespace pattern
            val contextLeadingWhitespace = contextAfter.takeWhile { it.isWhitespace() }
            val completionTrailingWhitespace = completion.takeLastWhile { it.isWhitespace() }
            
            // If completion doesn't end with whitespace but context starts with it,
            // we're trying to insert on a blank line - no overlap
            if (completionTrailingWhitespace.isEmpty() && contextLeadingWhitespace.contains('\n')) {
                System.out.println("Cursor on blank line, no suffix overlap check needed")
                return 0
            }
        }

        // Try exact character match from start of context
        val maxLength = minOf(completion.length, contextAfter.length)

        for (length in maxLength downTo 1) {
            val completionSuffix = completion.takeLast(length)
            val contextPrefix = contextAfter.take(length)

            if (completionSuffix == contextPrefix) {
                return length
            }
        }

        // Try normalized match
        val normalizedCompletion = normalizeCode(completion)
        val normalizedContext = normalizeCode(contextAfter)

        for (length in minOf(normalizedCompletion.length, normalizedContext.length) downTo 1) {
            val completionSuffix = normalizedCompletion.takeLast(length)
            val contextPrefix = normalizedContext.take(length)

            if (completionSuffix == contextPrefix) {
                // Find actual character count in original text
                return findActualOverlapLength(completion, contextAfter, false)
            }
        }

        return 0
    }

    /**
     * Find actual overlap length when normalized strings match
     */
    private fun findActualOverlapLength(text1: String, text2: String, isPrefix: Boolean): Int {
        val norm1 = normalizeCode(text1)
        val norm2 = normalizeCode(text2)

        // Find where they actually overlap in normalized form
        var overlapLength = 0
        if (isPrefix) {
            // For prefix: end of text1 matches start of text2
            for (len in minOf(norm1.length, norm2.length) downTo 1) {
                if (norm1.takeLast(len) == norm2.take(len)) {
                    overlapLength = len
                    break
                }
            }
        } else {
            // For suffix: end of text1 matches start of text2
            for (len in minOf(norm1.length, norm2.length) downTo 1) {
                if (norm1.takeLast(len) == norm2.take(len)) {
                    overlapLength = len
                    break
                }
            }
        }

        if (overlapLength == 0) return 0

        // Map back to original text length
        var originalOverlap = 0
        var normPos = 0
        var origPos = if (isPrefix) text1.length - 1 else 0

        // This is a simplified mapping - in practice might need more sophisticated approach
        // For now, return a conservative estimate
        return minOf(overlapLength, if (isPrefix) text1.length else text2.length)
    }

    /**
     * Remove duplicate lines from completion
     */
    private fun removeLineDuplicates(
        completionText: String,
        documentText: String,
        cursorOffset: Int
    ): LineDeduplicationResult {

        // Get document lines around cursor (larger context for line matching)
        val startOffset = maxOf(0, cursorOffset - 1000)
        val endOffset = minOf(documentText.length, cursorOffset + 1000)
        val contextText = documentText.substring(startOffset, endOffset)

        val docLines = contextText.lines()
        val completionLines = completionText.lines()

        if (completionLines.isEmpty()) {
            return LineDeduplicationResult(completionText, 0, 0, 0)
        }

        // Create normalized version of document lines for comparison
        val normalizedDocLines = mutableSetOf<String>()
        for (i in docLines.indices) {
            val nextLine = if (i + 1 < docLines.size) docLines[i + 1] else null
            val normalized = normalizeLineForComparison(docLines[i], nextLine)
            if (normalized.isNotEmpty()) {
                normalizedDocLines.add(normalized)
            }
        }

        // Check each completion line
        val linesToKeep = mutableListOf<String>()
        var removedLines = 0
        var removedPrefixChars = 0
        var removedSuffixChars = 0
        var inPrefixRemoval = true

        for (i in completionLines.indices) {
            val line = completionLines[i]
            val nextLine = if (i + 1 < completionLines.size) completionLines[i + 1] else null
            val normalizedLine = normalizeLineForComparison(line, nextLine)

            // Keep empty lines and non-duplicate lines
            if (normalizedLine.isEmpty() || !normalizedDocLines.contains(normalizedLine)) {
                linesToKeep.add(line)
                inPrefixRemoval = false
            } else {
                // Line is a duplicate
                removedLines++
                val lineLength = line.length + 1 // +1 for newline

                if (inPrefixRemoval) {
                    removedPrefixChars += lineLength
                } else {
                    removedSuffixChars += lineLength
                }

                System.out.println("Removing duplicate line: '$line'")
            }
        }

        val adjustedText = linesToKeep.joinToString("\n")

        return LineDeduplicationResult(
            adjustedText,
            removedLines,
            removedPrefixChars,
            removedSuffixChars
        )
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
     * Result of line deduplication
     */
    private data class LineDeduplicationResult(
        val adjustedText: String,
        val removedLines: Int,
        val removedPrefixChars: Int,
        val removedSuffixChars: Int
    )
}
