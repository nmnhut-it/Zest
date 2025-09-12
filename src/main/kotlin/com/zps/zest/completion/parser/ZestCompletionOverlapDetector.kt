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

    private data class CursorContext(
        val before: String,
        val after: String
    )

    private data class OverlapAdjustment(
        val text: String,
        val prefixOverlap: Int,
        val suffixOverlap: Int
    )

    private companion object {
        private const val CONTEXT_LIMIT = 500
        private const val DEBUG_CHAR_LIMIT = 100
    }

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

        val context = extractCursorContext(documentText, cursorOffset)
        logDebugInfo(context, completionText)

        var result = OverlapAdjustment(completionText, 0, 0)
        result = adjustForPrefixOverlap(context.before, result)
        result = adjustForSuffixOverlap(result.text, context.after, result.prefixOverlap, result.suffixOverlap)
        result = adjustForLineDuplicates(result, documentText, cursorOffset)

        logger.debug("Final adjusted completion: ${result.text.take(100)}")
        return OverlapResult(result.text, result.prefixOverlap, result.suffixOverlap)
    }

    private fun normalizeCode(text: String): String {
        return text
            .normalizeLineEndings()
            .normalizeBraceStyle()
            .normalizeWhitespace()
            .normalizeDelimiters()
            .trim()
    }

    private fun String.normalizeLineEndings(): String {
        return this.replace("\r\n", "\n").replace("\r", "\n")
    }

    private fun String.normalizeBraceStyle(): String {
        return this.replace(Regex("\\n\\s*\\{"), " {") // C style: \n{ → space{
    }

    private fun String.normalizeWhitespace(): String {
        return this.replace(Regex("\\s+"), " ") // All whitespace to single space
    }

    private fun String.normalizeDelimiters(): String {
        return this
            .replace(Regex("\\s*\\(\\s*"), "(") // Normalize parentheses
            .replace(Regex("\\s*\\)\\s*"), ")")
            .replace(Regex("\\s*\\[\\s*"), "[") // Normalize square brackets
            .replace(Regex("\\s*\\]\\s*"), "]")
            .replace(Regex("\\s*\\{\\s*"), "{") // Normalize curly braces
            .replace(Regex("\\s*\\}\\s*"), "}")
    }

    private fun normalizeLineForComparison(line: String, nextLine: String?): String {
        var normalized = normalizeCode(line)
        if (nextLine?.trim()?.startsWith("{") == true) {
            normalized += "{"
        }
        return normalized
    }

    private fun findPrefixOverlap(contextBefore: String, completion: String): Int {
        if (contextBefore.isEmpty() || completion.isEmpty()) return 0
        if (isCursorOnBlankLine(contextBefore)) return 0

        return findExactOverlap(contextBefore, completion, isPrefix = true) 
            ?: findNormalizedOverlap(contextBefore, completion, isPrefix = true) 
            ?: 0
    }

    private fun findSuffixOverlap(completion: String, contextAfter: String): Int {
        if (completion.isEmpty() || contextAfter.isEmpty()) return 0
        if (shouldSkipSuffixOverlapCheck(completion, contextAfter)) return 0

        return findExactOverlap(completion, contextAfter, isPrefix = false) 
            ?: findNormalizedOverlap(completion, contextAfter, isPrefix = false) 
            ?: 0
    }

    private fun findActualOverlapLength(text1: String, text2: String, isPrefix: Boolean): Int {
        val norm1 = normalizeCode(text1)
        val norm2 = normalizeCode(text2)

        val overlapLength = findNormalizedOverlapLength(norm1, norm2)
        if (overlapLength == 0) return 0

        // Return conservative estimate for original text length
        return minOf(overlapLength, if (isPrefix) text1.length else text2.length)
    }

    private fun findNormalizedOverlapLength(norm1: String, norm2: String): Int {
        for (len in minOf(norm1.length, norm2.length) downTo 1) {
            if (norm1.takeLast(len) == norm2.take(len)) {
                return len
            }
        }
        return 0
    }

    private fun removeLineDuplicates(
        completionText: String,
        documentText: String,
        cursorOffset: Int
    ): LineDeduplicationResult {
        val completionLines = completionText.lines()
        if (completionLines.isEmpty()) {
            return LineDeduplicationResult(completionText, 0, 0, 0)
        }

        val currentLine = extractCurrentLine(documentText, cursorOffset)
        if (currentLine.trim().isEmpty()) {
            logger.debug("Cursor on blank line - skipping duplicate removal for new insertion")
            return LineDeduplicationResult(completionText, 0, 0, 0)
        }

        return processLineDuplicates(completionLines, currentLine)
    }

    private fun extractCurrentLine(documentText: String, cursorOffset: Int): String {
        val currentLineStart = documentText.lastIndexOf('\n', cursorOffset - 1) + 1
        val currentLineEnd = documentText.indexOf('\n', cursorOffset).let { 
            if (it == -1) documentText.length else it 
        }
        return if (currentLineStart <= currentLineEnd) {
            documentText.substring(currentLineStart, currentLineEnd)
        } else ""
    }

    private fun processLineDuplicates(
        completionLines: List<String>, 
        currentLine: String
    ): LineDeduplicationResult {
        val normalizedCurrentLine = normalizeLineForComparison(currentLine, null)
        logger.debug("Checking duplicates against current line: '$currentLine'")

        val linesToKeep = mutableListOf<String>()
        var removedLines = 0
        var removedPrefixChars = 0
        var removedSuffixChars = 0
        var inPrefixRemoval = true

        for (i in completionLines.indices) {
            val line = completionLines[i]
            val nextLine = if (i + 1 < completionLines.size) completionLines[i + 1] else null
            val normalizedLine = normalizeLineForComparison(line, nextLine)

            if (normalizedLine.isEmpty() || normalizedLine != normalizedCurrentLine) {
                linesToKeep.add(line)
                inPrefixRemoval = false
            } else {
                removedLines++
                val lineLength = line.length + 1 // +1 for newline
                if (inPrefixRemoval) {
                    removedPrefixChars += lineLength
                } else {
                    removedSuffixChars += lineLength
                }
                logger.debug("Removing duplicate of current line: '$line'")
            }
        }

        return LineDeduplicationResult(
            linesToKeep.joinToString("\n"),
            removedLines,
            removedPrefixChars,
            removedSuffixChars
        )
    }

    private fun getContextBeforeCursor(documentText: String, cursorOffset: Int, limit: Int): String {
        if (cursorOffset <= 0) return ""
        val startOffset = maxOf(0, cursorOffset - limit)
        return documentText.substring(startOffset, cursorOffset)
    }

    private fun getContextAfterCursor(documentText: String, cursorOffset: Int, limit: Int): String {
        if (cursorOffset >= documentText.length) return ""
        val endOffset = minOf(documentText.length, cursorOffset + limit)
        return documentText.substring(cursorOffset, endOffset)
    }

    private data class LineDeduplicationResult(
        val adjustedText: String,
        val removedLines: Int,
        val removedPrefixChars: Int,
        val removedSuffixChars: Int
    )

    private fun extractCursorContext(documentText: String, cursorOffset: Int): CursorContext {
        return CursorContext(
            before = getContextBeforeCursor(documentText, cursorOffset, CONTEXT_LIMIT),
            after = getContextAfterCursor(documentText, cursorOffset, CONTEXT_LIMIT)
        )
    }

    private fun logDebugInfo(context: CursorContext, completionText: String) {
        if (logger.isDebugEnabled) {
            val beforeDebug = context.before.takeLast(DEBUG_CHAR_LIMIT).makeWhitespaceVisible()
            val afterDebug = context.after.take(DEBUG_CHAR_LIMIT).makeWhitespaceVisible()
            val completionDebug = completionText.take(DEBUG_CHAR_LIMIT).makeWhitespaceVisible()
            
            logger.debug("Context before cursor: '$beforeDebug'")
            logger.debug("Context after cursor: '$afterDebug'")
            logger.debug("Completion: '$completionDebug'")
            
            val isOnBlankLine = context.after.trimStart() != context.after && context.after.trim().isNotEmpty()
            if (isOnBlankLine) {
                logger.debug("Cursor appears to be on a blank line")
            }
        }
    }

    private fun String.makeWhitespaceVisible(): String {
        return this.replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace(" ", "·")
    }

    private fun adjustForPrefixOverlap(contextBefore: String, adjustment: OverlapAdjustment): OverlapAdjustment {
        val prefixResult = findPrefixOverlap(contextBefore, adjustment.text)
        if (prefixResult > 0) {
            logger.debug("Found prefix overlap of $prefixResult chars")
            return adjustment.copy(
                text = adjustment.text.substring(prefixResult),
                prefixOverlap = adjustment.prefixOverlap + prefixResult
            )
        }
        return adjustment
    }

    private fun adjustForSuffixOverlap(
        completionText: String, 
        contextAfter: String, 
        currentPrefixOverlap: Int, 
        currentSuffixOverlap: Int
    ): OverlapAdjustment {
        val suffixResult = findSuffixOverlap(completionText, contextAfter)
        if (suffixResult > 0) {
            logger.debug("Found suffix overlap of $suffixResult chars")
            return OverlapAdjustment(
                text = completionText.substring(0, completionText.length - suffixResult),
                prefixOverlap = currentPrefixOverlap,
                suffixOverlap = currentSuffixOverlap + suffixResult
            )
        }
        return OverlapAdjustment(completionText, currentPrefixOverlap, currentSuffixOverlap)
    }

    private fun adjustForLineDuplicates(
        adjustment: OverlapAdjustment, 
        documentText: String, 
        cursorOffset: Int
    ): OverlapAdjustment {
        if (adjustment.text.isEmpty()) return adjustment
        
        val lineResult = removeLineDuplicates(adjustment.text, documentText, cursorOffset)
        if (lineResult.removedLines > 0) {
            logger.debug("Removed ${lineResult.removedLines} duplicate lines")
        }
        
        return adjustment.copy(
            text = lineResult.adjustedText,
            prefixOverlap = adjustment.prefixOverlap + lineResult.removedPrefixChars,
            suffixOverlap = adjustment.suffixOverlap + lineResult.removedSuffixChars
        )
    }

    private fun isCursorOnBlankLine(contextBefore: String): Boolean {
        val lastNewlineIndex = contextBefore.lastIndexOf('\n')
        if (lastNewlineIndex >= 0) {
            val afterLastNewline = contextBefore.substring(lastNewlineIndex + 1)
            if (afterLastNewline.isBlank()) {
                logger.debug("Cursor at start of blank line, no prefix overlap check needed")
                return true
            }
        }
        return false
    }

    private fun shouldSkipSuffixOverlapCheck(completion: String, contextAfter: String): Boolean {
        if (contextAfter.trimStart() != contextAfter) {
            val contextLeadingWhitespace = contextAfter.takeWhile { it.isWhitespace() }
            val completionTrailingWhitespace = completion.takeLastWhile { it.isWhitespace() }
            
            if (completionTrailingWhitespace.isEmpty() && contextLeadingWhitespace.contains('\n')) {
                logger.debug("Cursor on blank line, no suffix overlap check needed")
                return true
            }
        }
        return false
    }

    private fun findExactOverlap(text1: String, text2: String, isPrefix: Boolean): Int? {
        val maxLength = minOf(text1.length, text2.length)
        
        for (length in maxLength downTo 1) {
            val (suffix, prefix) = if (isPrefix) {
                text1.takeLast(length) to text2.take(length)
            } else {
                text1.takeLast(length) to text2.take(length)
            }
            
            if (suffix == prefix) {
                return length
            }
        }
        return null
    }

    private fun findNormalizedOverlap(text1: String, text2: String, isPrefix: Boolean): Int? {
        val normalized1 = normalizeCode(text1)
        val normalized2 = normalizeCode(text2)
        
        val maxLength = minOf(normalized1.length, normalized2.length)
        
        for (length in maxLength downTo 1) {
            val (suffix, prefix) = if (isPrefix) {
                normalized1.takeLast(length) to normalized2.take(length)
            } else {
                normalized1.takeLast(length) to normalized2.take(length)
            }
            
            if (suffix == prefix) {
                return findActualOverlapLength(text1, text2, isPrefix)
            }
        }
        return null
    }
}
