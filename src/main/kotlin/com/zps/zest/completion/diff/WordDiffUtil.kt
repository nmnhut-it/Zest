package com.zps.zest.completion.diff

import com.github.difflib.DiffUtils
import com.github.difflib.patch.DeltaType
import com.github.difflib.patch.Patch
import com.intellij.openapi.diagnostic.Logger

/**
 * Utility class for performing word-level and line-level diffing on text
 * Supports multiple diff algorithms including Myers and Histogram diff
 */
object WordDiffUtil {
    
    private val logger = Logger.getInstance(WordDiffUtil::class.java)
    
    /**
     * Represents a word segment with its change type
     */
    data class WordSegment(
        val text: String,
        val type: ChangeType,
        val startOffset: Int = -1,  // Position in original text
        val endOffset: Int = -1
    )
    
    enum class ChangeType {
        UNCHANGED,
        ADDED,
        DELETED,
        MODIFIED
    }
    
    /**
     * Supported diff algorithms
     */
    enum class DiffAlgorithm {
        MYERS,      // Default algorithm, good for most cases
        HISTOGRAM   // Better for code with many similar lines
    }
    
    /**
     * Result of word-level diff containing segments for both original and modified text
     */
    data class WordDiffResult(
        val originalSegments: List<WordSegment>,
        val modifiedSegments: List<WordSegment>,
        val similarity: Double = 0.0  // Similarity score between 0 and 1
    )
    
    /**
     * Result of line-level diff for multi-line changes
     */
    data class LineDiffResult(
        val blocks: List<DiffBlock>
    )
    
    /**
     * A block of related changes (consecutive lines)
     */
    data class DiffBlock(
        val originalStartLine: Int,
        val originalEndLine: Int,
        val modifiedStartLine: Int,
        val modifiedEndLine: Int,
        val originalLines: List<String>,
        val modifiedLines: List<String>,
        val type: BlockType
    )
    
    enum class BlockType {
        UNCHANGED,
        MODIFIED,   // Lines changed in place
        DELETED,    // Lines only in original
        ADDED       // Lines only in modified
    }
    
    /**
     * Language-specific tokenization settings
     */
    data class TokenizationSettings(
        val preserveWhitespace: Boolean = true,
        val splitCamelCase: Boolean = true,
        val treatNumbersAsOneToken: Boolean = false,
        val customDelimiters: Set<Char> = emptySet()
    )
    
    private val languageSettings = mapOf(
        "java" to TokenizationSettings(
            splitCamelCase = true,
            treatNumbersAsOneToken = true,
            customDelimiters = setOf('@', '$')
        ),
        "kotlin" to TokenizationSettings(
            splitCamelCase = true,
            treatNumbersAsOneToken = true,
            customDelimiters = setOf('@', '$', '`')
        ),
        "python" to TokenizationSettings(
            splitCamelCase = false,
            treatNumbersAsOneToken = true,
            customDelimiters = setOf('@', '_')
        ),
        "javascript" to TokenizationSettings(
            splitCamelCase = true,
            treatNumbersAsOneToken = true,
            customDelimiters = setOf('$', '_')
        )
    )
    
    /**
     * Perform line-level diff to identify multi-line change blocks
     * Uses histogram diff by default for better code diffing
     */
    fun diffLines(
        originalText: String, 
        modifiedText: String,
        algorithm: DiffAlgorithm = DiffAlgorithm.HISTOGRAM,
        language: String? = null
    ): LineDiffResult {
        val originalLines = originalText.lines()
        val modifiedLines = modifiedText.lines()
        
        // Don't normalize for diffing - keep exact text to preserve whitespace differences
        // Normalization can cause the diff to miss actual changes
        
        // Create diff algorithm instance
        val diffAlgorithm = when (algorithm) {
            DiffAlgorithm.MYERS -> null // Use default
            DiffAlgorithm.HISTOGRAM -> HistogramDiff<String>()
        }
        
        // Perform the diff on original lines (not normalized)
        val patch = try {
            if (diffAlgorithm != null) {
                DiffUtils.diff(originalLines, modifiedLines, diffAlgorithm)
            } else {
                DiffUtils.diff(originalLines, modifiedLines)
            }
        } catch (e: Exception) {
            // Fallback to default if any algorithm fails
            DiffUtils.diff(originalLines, modifiedLines)
        }
        
        // Convert patch to diff blocks
        return patchToDiffBlocks(patch, originalLines, modifiedLines)
    }
    
    /**
     * Convert a patch to structured diff blocks
     */
    private fun patchToDiffBlocks(
        patch: Patch<String>,
        originalLines: List<String>,
        modifiedLines: List<String>
    ): LineDiffResult {
        val blocks = mutableListOf<DiffBlock>()
        var lastOriginalLine = 0
        var lastModifiedLine = 0
        
        for (delta in patch.deltas) {
            // Add unchanged block before this delta if needed
            if (delta.source.position > lastOriginalLine) {
                blocks.add(DiffBlock(
                    originalStartLine = lastOriginalLine,
                    originalEndLine = delta.source.position - 1,
                    modifiedStartLine = lastModifiedLine,
                    modifiedEndLine = delta.target.position - 1,
                    originalLines = originalLines.subList(lastOriginalLine, delta.source.position),
                    modifiedLines = modifiedLines.subList(lastModifiedLine, delta.target.position),
                    type = BlockType.UNCHANGED
                ))
            }
            
            // Add the change block
            val blockType = when (delta.type) {
                DeltaType.DELETE -> BlockType.DELETED
                DeltaType.INSERT -> BlockType.ADDED
                DeltaType.CHANGE -> BlockType.MODIFIED
                else -> BlockType.UNCHANGED
            }
            
            blocks.add(DiffBlock(
                originalStartLine = delta.source.position,
                originalEndLine = delta.source.position + delta.source.size() - 1,
                modifiedStartLine = delta.target.position,
                modifiedEndLine = delta.target.position + delta.target.size() - 1,
                originalLines = delta.source.lines,
                modifiedLines = delta.target.lines,
                type = blockType
            ))
            
            lastOriginalLine = delta.source.position + delta.source.size()
            lastModifiedLine = delta.target.position + delta.target.size()
        }
        
        // Add final unchanged block if needed
        if (lastOriginalLine < originalLines.size || lastModifiedLine < modifiedLines.size) {
            // Make sure we include all remaining lines
            val remainingOriginalLines = if (lastOriginalLine < originalLines.size) 
                originalLines.subList(lastOriginalLine, originalLines.size) 
            else emptyList()
            val remainingModifiedLines = if (lastModifiedLine < modifiedLines.size)
                modifiedLines.subList(lastModifiedLine, modifiedLines.size)
            else emptyList()
            
            // Even if there's just one side with remaining lines, include them
            if (remainingOriginalLines.isNotEmpty() || remainingModifiedLines.isNotEmpty()) {
                blocks.add(DiffBlock(
                    originalStartLine = lastOriginalLine,
                    originalEndLine = originalLines.size - 1,
                    modifiedStartLine = lastModifiedLine,
                    modifiedEndLine = modifiedLines.size - 1,
                    originalLines = remainingOriginalLines,
                    modifiedLines = remainingModifiedLines,
                    type = if (remainingOriginalLines == remainingModifiedLines) BlockType.UNCHANGED else BlockType.MODIFIED
                ))
            }
        }
        
        // Post-process to merge adjacent DELETE and INSERT blocks into MODIFIED blocks
        return LineDiffResult(mergeDeleteInsertBlocks(blocks))
    }
    
    /**
     * Merge adjacent DELETE and INSERT blocks into MODIFIED blocks
     * This handles the common case where diff algorithms return delete+insert instead of change
     */
    private fun mergeDeleteInsertBlocks(blocks: List<DiffBlock>): List<DiffBlock> {
        if (blocks.isEmpty()) return blocks
        
        val mergedBlocks = mutableListOf<DiffBlock>()
        var i = 0
        
        // Debug logging
        if (logger.isDebugEnabled) {
            logger.debug("=== Merging DELETE/INSERT blocks ===")
            logger.debug("Original blocks: ${blocks.size}")
            blocks.forEach { block ->
                logger.debug("  ${block.type}: lines ${block.originalStartLine}-${block.originalEndLine} -> ${block.modifiedStartLine}-${block.modifiedEndLine}")
            }
        }
        
        while (i < blocks.size) {
            val currentBlock = blocks[i]
            
            // Check if this is a DELETE block followed by an INSERT block
            if (currentBlock.type == BlockType.DELETED && 
                i + 1 < blocks.size && 
                blocks[i + 1].type == BlockType.ADDED) {
                
                val nextBlock = blocks[i + 1]
                
                // Check if they are logically adjacent
                // DELETE block represents removed lines from original
                // INSERT block represents added lines in modified
                // They're related if the INSERT follows the DELETE position
                val areAdjacent = true // Always merge DELETE followed by INSERT
                
                if (areAdjacent) {
                    if (logger.isDebugEnabled) {
                        logger.debug("  Merging DELETE (${currentBlock.originalStartLine}-${currentBlock.originalEndLine}) + INSERT (${nextBlock.modifiedStartLine}-${nextBlock.modifiedEndLine}) -> MODIFIED")
                    }
                    
                    // Merge into a MODIFIED block
                    mergedBlocks.add(DiffBlock(
                        originalStartLine = currentBlock.originalStartLine,
                        originalEndLine = currentBlock.originalEndLine,
                        modifiedStartLine = nextBlock.modifiedStartLine,
                        modifiedEndLine = nextBlock.modifiedEndLine,
                        originalLines = currentBlock.originalLines,
                        modifiedLines = nextBlock.modifiedLines,
                        type = BlockType.MODIFIED
                    ))
                    
                    // Skip the INSERT block since we've merged it
                    i += 2
                } else {
                    // Not adjacent, keep as separate blocks
                    mergedBlocks.add(currentBlock)
                    i++
                }
            } else {
                // Keep the block as-is
                mergedBlocks.add(currentBlock)
                i++
            }
        }
        
        if (logger.isDebugEnabled) {
            logger.debug("Merged blocks: ${mergedBlocks.size}")
            logger.debug("=== End merge ===")
        }
        
        return mergedBlocks
    }
    
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
        when (language?.lowercase()) {
            "java", "kotlin" -> normalized = normalizeJavaKotlinCode(normalized)
            "python" -> normalized = normalizePythonCode(normalized)
            "javascript", "typescript" -> normalized = normalizeJavaScriptCode(normalized)
        }
        
        // Trim trailing whitespace from each line
        return normalized.lines()
            .joinToString("\n") { it.trimEnd() }
    }
    
    /**
     * Java/Kotlin-specific code normalization for better semantic diffing
     */
    private fun normalizeJavaKotlinCode(code: String): String {
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
            .replace(Regex("\\b(if|for|while|switch|catch|when)\\("), "$1 (")
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
     * Python-specific code normalization
     */
    private fun normalizePythonCode(code: String): String {
        var normalized = code
            // Normalize colons
            .replace(Regex(":\\s+"), ": ")
            // Normalize commas
            .replace(Regex(",\\s+"), ", ")
            // Normalize function definitions
            .replace(Regex("def\\s+"), "def ")
            .replace(Regex("class\\s+"), "class ")
            
        return normalized
    }
    
    /**
     * JavaScript/TypeScript-specific code normalization
     */
    private fun normalizeJavaScriptCode(code: String): String {
        var normalized = code
            // Arrow functions
            .replace(Regex("\\s*=>\\s*"), " => ")
            // Object literals
            .replace(Regex(":\\s+"), ": ")
            .replace(Regex(",\\s+"), ", ")
            // Function declarations
            .replace(Regex("function\\s+"), "function ")
            
        return normalized
    }
    
    /**
     * Perform word-level diff between two lines of text with enhanced tokenization
     */
    fun diffWords(
        original: String, 
        modified: String, 
        language: String? = null
    ): WordDiffResult {
        // Normalize the text first
        val normalizedOriginal = normalizeCode(original, language)
        val normalizedModified = normalizeCode(modified, language)
        
        // Get language-specific settings
        val settings = languageSettings[language?.lowercase()] ?: TokenizationSettings()
        
        // Split into words/tokens while preserving whitespace
        val originalTokens = tokenizeAdvanced(normalizedOriginal, settings)
        val modifiedTokens = tokenizeAdvanced(normalizedModified, settings)
        
        // Perform diff on tokens
        val patch = DiffUtils.diff(originalTokens.map { it.text }, modifiedTokens.map { it.text })
        
        // Build segments for both sides
        val (originalSegments, modifiedSegments) = buildSegments(
            originalTokens, 
            modifiedTokens, 
            patch
        )
        
        // Calculate similarity score
        val similarity = calculateSimilarity(originalSegments, modifiedSegments)
        
        return WordDiffResult(originalSegments, modifiedSegments, similarity)
    }
    
    /**
     * Advanced tokenization with language-specific rules
     */
    private fun tokenizeAdvanced(
        text: String, 
        settings: TokenizationSettings
    ): List<Token> {
        val tokens = mutableListOf<Token>()
        val currentToken = StringBuilder()
        var tokenType: TokenType? = null
        var startOffset = 0
        
        text.forEachIndexed { index, char ->
            val charType = getTokenType(char, settings.customDelimiters)
            
            // Check if we need to split based on token type change
            val shouldSplit = when {
                tokenType == null -> false
                charType != tokenType -> true
                settings.splitCamelCase && tokenType == TokenType.WORD -> {
                    // Split on camelCase boundaries
                    currentToken.isNotEmpty() && 
                    char.isUpperCase() && 
                    currentToken.last().isLowerCase()
                }
                else -> false
            }
            
            if (shouldSplit && currentToken.isNotEmpty()) {
                tokens.add(Token(
                    text = currentToken.toString(),
                    type = tokenType!!,
                    startOffset = startOffset,
                    endOffset = index
                ))
                currentToken.clear()
                startOffset = index
            }
            
            currentToken.append(char)
            if (tokenType == null || shouldSplit) {
                tokenType = charType
            }
        }
        
        // Add the last token
        if (currentToken.isNotEmpty()) {
            tokens.add(Token(
                text = currentToken.toString(),
                type = tokenType!!,
                startOffset = startOffset,
                endOffset = text.length
            ))
        }
        
        return tokens
    }
    
    private data class Token(
        val text: String,
        val type: TokenType,
        val startOffset: Int,
        val endOffset: Int
    )
    
    private enum class TokenType {
        WORD,           // Letters, digits, underscore
        WHITESPACE,     // Spaces, tabs, newlines
        OPERATOR,       // +, -, *, /, =, <, >, !, &, |, etc.
        DELIMITER,      // (, ), [, ], {, }
        PUNCTUATION,    // ., ,, ;, :
        NUMBER,         // Numeric values
        STRING,         // String literals (basic detection)
        COMMENT,        // Comments (basic detection)
        OTHER
    }
    
    private fun getTokenType(char: Char, customDelimiters: Set<Char>): TokenType {
        return when {
            char.isLetter() || char == '_' -> TokenType.WORD
            char.isDigit() -> TokenType.NUMBER
            char.isWhitespace() -> TokenType.WHITESPACE
            char in "+-*/%=<>!&|^~" -> TokenType.OPERATOR
            char in "()[]{}" || char in customDelimiters -> TokenType.DELIMITER
            char in ".,:;" -> TokenType.PUNCTUATION
            char in "\"'`" -> TokenType.STRING
            else -> TokenType.OTHER
        }
    }
    
    /**
     * Build segments from tokens and patch
     */
    private fun buildSegments(
        originalTokens: List<Token>,
        modifiedTokens: List<Token>,
        patch: Patch<String>
    ): Pair<List<WordSegment>, List<WordSegment>> {
        val originalSegments = mutableListOf<WordSegment>()
        val modifiedSegments = mutableListOf<WordSegment>()
        var originalIndex = 0
        var modifiedIndex = 0
        
        // Process each delta
        for (delta in patch.deltas) {
            // Add unchanged tokens before this delta
            while (originalIndex < delta.source.position) {
                val token = originalTokens[originalIndex]
                originalSegments.add(WordSegment(
                    text = token.text, 
                    type = ChangeType.UNCHANGED,
                    startOffset = token.startOffset,
                    endOffset = token.endOffset
                ))
                val modToken = modifiedTokens[modifiedIndex]
                modifiedSegments.add(WordSegment(
                    text = modToken.text, 
                    type = ChangeType.UNCHANGED,
                    startOffset = modToken.startOffset,
                    endOffset = modToken.endOffset
                ))
                originalIndex++
                modifiedIndex++
            }
            
            // Process the delta
            when (delta.type) {
                DeltaType.DELETE -> {
                    // Tokens only in original
                    for (i in 0 until delta.source.size()) {
                        val token = originalTokens[originalIndex + i]
                        originalSegments.add(WordSegment(
                            text = token.text,
                            type = ChangeType.DELETED,
                            startOffset = token.startOffset,
                            endOffset = token.endOffset
                        ))
                    }
                    originalIndex += delta.source.size()
                }
                DeltaType.INSERT -> {
                    // Tokens only in modified
                    for (i in 0 until delta.target.size()) {
                        val token = modifiedTokens[modifiedIndex + i]
                        modifiedSegments.add(WordSegment(
                            text = token.text,
                            type = ChangeType.ADDED,
                            startOffset = token.startOffset,
                            endOffset = token.endOffset
                        ))
                    }
                    modifiedIndex += delta.target.size()
                }
                DeltaType.CHANGE -> {
                    // Changed tokens
                    for (i in 0 until delta.source.size()) {
                        val token = originalTokens[originalIndex + i]
                        originalSegments.add(WordSegment(
                            text = token.text,
                            type = ChangeType.MODIFIED,
                            startOffset = token.startOffset,
                            endOffset = token.endOffset
                        ))
                    }
                    for (i in 0 until delta.target.size()) {
                        val token = modifiedTokens[modifiedIndex + i]
                        modifiedSegments.add(WordSegment(
                            text = token.text,
                            type = ChangeType.MODIFIED,
                            startOffset = token.startOffset,
                            endOffset = token.endOffset
                        ))
                    }
                    originalIndex += delta.source.size()
                    modifiedIndex += delta.target.size()
                }
                else -> {
                    // EQUAL - shouldn't happen but handle it
                    for (i in 0 until delta.source.size()) {
                        val token = originalTokens[originalIndex + i]
                        originalSegments.add(WordSegment(
                            text = token.text,
                            type = ChangeType.UNCHANGED,
                            startOffset = token.startOffset,
                            endOffset = token.endOffset
                        ))
                    }
                    for (i in 0 until delta.target.size()) {
                        val token = modifiedTokens[modifiedIndex + i]
                        modifiedSegments.add(WordSegment(
                            text = token.text,
                            type = ChangeType.UNCHANGED,
                            startOffset = token.startOffset,
                            endOffset = token.endOffset
                        ))
                    }
                    originalIndex += delta.source.size()
                    modifiedIndex += delta.target.size()
                }
            }
        }
        
        // Add remaining unchanged tokens
        while (originalIndex < originalTokens.size && modifiedIndex < modifiedTokens.size) {
            val origToken = originalTokens[originalIndex]
            val modToken = modifiedTokens[modifiedIndex]
            originalSegments.add(WordSegment(
                text = origToken.text,
                type = ChangeType.UNCHANGED,
                startOffset = origToken.startOffset,
                endOffset = origToken.endOffset
            ))
            modifiedSegments.add(WordSegment(
                text = modToken.text,
                type = ChangeType.UNCHANGED,
                startOffset = modToken.startOffset,
                endOffset = modToken.endOffset
            ))
            originalIndex++
            modifiedIndex++
        }
        
        return Pair(originalSegments, modifiedSegments)
    }
    
    /**
     * Calculate similarity score between original and modified segments
     */
    private fun calculateSimilarity(
        originalSegments: List<WordSegment>,
        modifiedSegments: List<WordSegment>
    ): Double {
        if (originalSegments.isEmpty() && modifiedSegments.isEmpty()) return 1.0
        
        val unchangedCount = originalSegments.count { it.type == ChangeType.UNCHANGED }
        val totalCount = maxOf(originalSegments.size, modifiedSegments.size)
        
        return if (totalCount > 0) unchangedCount.toDouble() / totalCount else 0.0
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
            if (next.type == current.type && 
                current.endOffset >= 0 && 
                next.startOffset >= 0 &&
                next.startOffset == current.endOffset) {
                // Merge with current
                current = WordSegment(
                    text = current.text + next.text, 
                    type = current.type,
                    startOffset = current.startOffset,
                    endOffset = next.endOffset
                )
            } else {
                // Add current and start new
                merged.add(current)
                current = next
            }
        }
        
        merged.add(current)
        return merged
    }
    
    /**
     * Find common subsequences for better alignment in side-by-side view
     */
    fun findCommonSubsequences(
        original: String,
        modified: String,
        minLength: Int = 3
    ): List<CommonSubsequence> {
        val subsequences = mutableListOf<CommonSubsequence>()
        val originalLength = original.length
        val modifiedLength = modified.length
        
        // Dynamic programming table
        val dp = Array(originalLength + 1) { IntArray(modifiedLength + 1) }
        
        // Fill the DP table
        for (i in 1..originalLength) {
            for (j in 1..modifiedLength) {
                if (original[i - 1] == modified[j - 1]) {
                    dp[i][j] = dp[i - 1][j - 1] + 1
                    
                    // Check if we found a subsequence of minimum length
                    if (dp[i][j] >= minLength) {
                        val length = dp[i][j]
                        subsequences.add(CommonSubsequence(
                            text = original.substring(i - length, i),
                            originalStart = i - length,
                            originalEnd = i,
                            modifiedStart = j - length,
                            modifiedEnd = j
                        ))
                    }
                }
            }
        }
        
        // Remove overlapping subsequences, keeping the longest ones
        return filterOverlappingSubsequences(subsequences)
    }
    
    data class CommonSubsequence(
        val text: String,
        val originalStart: Int,
        val originalEnd: Int,
        val modifiedStart: Int,
        val modifiedEnd: Int
    )
    
    private fun filterOverlappingSubsequences(
        subsequences: List<CommonSubsequence>
    ): List<CommonSubsequence> {
        if (subsequences.isEmpty()) return emptyList()
        
        // Sort by length (descending) and then by position
        val sorted = subsequences.sortedWith(
            compareByDescending<CommonSubsequence> { it.text.length }
                .thenBy { it.originalStart }
        )
        
        val result = mutableListOf<CommonSubsequence>()
        val usedOriginal = mutableSetOf<Int>()
        val usedModified = mutableSetOf<Int>()
        
        for (subseq in sorted) {
            val originalRange = subseq.originalStart until subseq.originalEnd
            val modifiedRange = subseq.modifiedStart until subseq.modifiedEnd
            
            // Check if this subsequence overlaps with any already selected
            if (originalRange.none { it in usedOriginal } && 
                modifiedRange.none { it in usedModified }) {
                result.add(subseq)
                usedOriginal.addAll(originalRange)
                usedModified.addAll(modifiedRange)
            }
        }
        
        return result.sortedBy { it.originalStart }
    }
}
