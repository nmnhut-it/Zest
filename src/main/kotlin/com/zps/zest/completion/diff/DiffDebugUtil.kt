package com.zps.zest.completion.diff

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document

/**
 * Utility for debugging diff rendering issues
 */
object DiffDebugUtil {
    private val logger = Logger.getInstance(DiffDebugUtil::class.java)
    
    /**
     * Log detailed diff information for debugging
     */
    fun logDiffDetails(
        originalText: String,
        modifiedText: String,
        lineDiff: WordDiffUtil.LineDiffResult,
        methodName: String
    ) {
        logger.info("=== Diff Debug for method: $methodName ===")
        
        val originalLines = originalText.lines()
        val modifiedLines = modifiedText.lines()
        
        logger.info("Original: ${originalLines.size} lines")
        originalLines.forEachIndexed { index, line ->
            logger.info("  [$index] ${line.take(50)}${if (line.length > 50) "..." else ""}")
        }
        
        logger.info("Modified: ${modifiedLines.size} lines")
        modifiedLines.forEachIndexed { index, line ->
            logger.info("  [$index] ${line.take(50)}${if (line.length > 50) "..." else ""}")
        }
        
        logger.info("Diff blocks: ${lineDiff.blocks.size}")
        lineDiff.blocks.forEach { block ->
            logger.info("  ${block.type}:")
            logger.info("    Original: lines ${block.originalStartLine}-${block.originalEndLine}")
            logger.info("    Modified: lines ${block.modifiedStartLine}-${block.modifiedEndLine}")
            logger.info("    Original content: ${block.originalLines.joinToString(" | ") { it.take(30) }}")
            logger.info("    Modified content: ${block.modifiedLines.joinToString(" | ") { it.take(30) }}")
        }
        
        logger.info("=== End Diff Debug ===")
    }
    
    /**
     * Validate line positions before rendering
     */
    fun validateLinePositions(
        document: Document,
        methodStartOffset: Int,
        methodEndOffset: Int,
        blockStartLine: Int,
        blockSize: Int
    ): Boolean {
        val methodStartLine = document.getLineNumber(methodStartOffset)
        val methodEndLine = document.getLineNumber(methodEndOffset)
        val targetLine = methodStartLine + blockStartLine
        
        if (targetLine < methodStartLine || targetLine > methodEndLine + 1) {
            logger.warn("Line position out of method bounds: target=$targetLine, method=[$methodStartLine, $methodEndLine]")
            return false
        }
        
        if (targetLine + blockSize - 1 > document.lineCount) {
            logger.warn("Block extends beyond document: targetLine=$targetLine, blockSize=$blockSize, docLines=${document.lineCount}")
            return false
        }
        
        return true
    }
    
    /**
     * Create a visual representation of the diff for logging
     */
    fun createDiffVisualization(lineDiff: WordDiffUtil.LineDiffResult): String {
        val sb = StringBuilder()
        
        lineDiff.blocks.forEach { block ->
            when (block.type) {
                WordDiffUtil.BlockType.UNCHANGED -> {
                    block.originalLines.forEach { line ->
                        sb.appendLine("   $line")
                    }
                }
                WordDiffUtil.BlockType.MODIFIED -> {
                    block.originalLines.forEach { line ->
                        sb.appendLine(" - $line")
                    }
                    block.modifiedLines.forEach { line ->
                        sb.appendLine(" + $line")
                    }
                }
                WordDiffUtil.BlockType.DELETED -> {
                    block.originalLines.forEach { line ->
                        sb.appendLine(" - $line")
                    }
                }
                WordDiffUtil.BlockType.ADDED -> {
                    block.modifiedLines.forEach { line ->
                        sb.appendLine(" + $line")
                    }
                }
            }
        }
        
        return sb.toString()
    }
}
