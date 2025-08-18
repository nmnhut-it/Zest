package com.zps.zest.gdiff

import com.github.difflib.DiffUtils
import com.github.difflib.patch.AbstractDelta
import com.github.difflib.patch.DeltaType
import com.github.difflib.patch.Patch
import com.github.difflib.*;
import com.github.difflib.patch.*;
import com.github.difflib.text.*;


import com.github.difflib.text.DiffRowGenerator
import com.github.difflib.UnifiedDiffUtils;
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import com.github.difflib.text.DiffRow.Tag;
/**
 * GDiff - A multi-language diff utility for the Zest plugin
 * 
 * This class provides comprehensive diff functionality that works with any text content
 * regardless of language, using Unicode-safe processing for international content.
 */
class GDiff {
    
    /**
     * Configuration for diff operations
     */
    data class DiffConfig(
        val ignoreWhitespace: Boolean = false,
        val ignoreCase: Boolean = false,
        val charset: Charset = StandardCharsets.UTF_8,
        val contextLines: Int = 3
    )
    
    /**
     * Represents a single diff operation
     */
    data class DiffChange(
        val type: ChangeType,
        val sourceLineNumber: Int,
        val targetLineNumber: Int,
        val sourceLines: List<String>,
        val targetLines: List<String>
    )
    
    enum class ChangeType {
        EQUAL,    // Lines are the same
        DELETE,   // Lines deleted from source
        INSERT,   // Lines added to target
        CHANGE    // Lines modified
    }
    
    /**
     * Result of a diff operation
     */
    data class DiffResult(
        val changes: List<DiffChange>,
        val sourceFile: String? = null,
        val targetFile: String? = null,
        val identical: Boolean = false
    ) {
        fun hasChanges(): Boolean = changes.any { it.type != ChangeType.EQUAL }
        
        fun getStatistics(): DiffStatistics {
            var additions = 0
            var deletions = 0
            var modifications = 0
            
            changes.forEach { change ->
                when (change.type) {
                    ChangeType.INSERT -> additions += change.targetLines.size
                    ChangeType.DELETE -> deletions += change.sourceLines.size
                    ChangeType.CHANGE -> {
                        modifications += maxOf(change.sourceLines.size, change.targetLines.size)
                    }
                    ChangeType.EQUAL -> { /* no changes */ }
                }
            }
            
            return DiffStatistics(additions, deletions, modifications)
        }
    }
    
    data class DiffStatistics(
        val additions: Int,
        val deletions: Int,
        val modifications: Int
    ) {
        val totalChanges: Int get() = additions + deletions + modifications
    }
    
    /**
     * Compare two strings and return detailed diff information
     */
    fun diffStrings(
        source: String,
        target: String,
        config: DiffConfig = DiffConfig()
    ): DiffResult {
        val sourceLines = preprocessLines(source.lines(), config)
        val targetLines = preprocessLines(target.lines(), config)
        
        val patch = DiffUtils.diff(sourceLines, targetLines)
        val changes = convertPatchToChanges(patch, sourceLines, targetLines)
        
        return DiffResult(
            changes = changes,
            identical = changes.all { it.type == ChangeType.EQUAL }
        )
    }
    
    /**
     * Compare two files and return detailed diff information
     */
    fun diffFiles(
        sourceFile: File,
        targetFile: File,
        config: DiffConfig = DiffConfig()
    ): DiffResult {
        val sourceContent = sourceFile.readText(config.charset)
        val targetContent = targetFile.readText(config.charset)
        
        val result = diffStrings(sourceContent, targetContent, config)
        return result.copy(
            sourceFile = sourceFile.name,
            targetFile = targetFile.name
        )
    }
    
    /**
     * Compare two files by path and return detailed diff information
     */
    fun diffFiles(
        sourcePath: String,
        targetPath: String,
        config: DiffConfig = DiffConfig()
    ): DiffResult {
        return diffFiles(File(sourcePath), File(targetPath), config)
    }
    
    /**
     * Generate unified diff format (similar to git diff)
     */
    fun generateUnifiedDiff(
        source: String,
        target: String,
        sourceFileName: String = "source",
        targetFileName: String = "target",
        config: DiffConfig = DiffConfig()
    ): String {
        val sourceLines = preprocessLines(source.lines(), config)
        val targetLines = preprocessLines(target.lines(), config)
        
        val patch = DiffUtils.diff(sourceLines, targetLines)
        
        return UnifiedDiffUtils.generateUnifiedDiff(
            sourceFileName,
            targetFileName,
            sourceLines,
            patch,
            config.contextLines
        ).joinToString("\n")
    }
    
    /**
     * Generate side-by-side diff for UI display
     */
    fun generateSideBySideDiff(
        source: String,
        target: String,
        config: DiffConfig = DiffConfig()
    ): List<DiffRow> {
        val sourceLines = preprocessLines(source.lines(), config)
        val targetLines = preprocessLines(target.lines(), config)
        
        val generator = DiffRowGenerator.create()
            .showInlineDiffs(true)
            .ignoreWhiteSpaces(config.ignoreWhitespace)
            .build()
        
        return generator.generateDiffRows(sourceLines, targetLines)
            .map { row ->
                DiffRow(
                    sourceLineNumber = -1, // Will be set by caller if needed
                    targetLineNumber = -1, // Will be set by caller if needed
                    sourceText = row.oldLine,
                    targetText = row.newLine,
                    type = when (row.tag) {
                        com.github.difflib.text.DiffRow.Tag. INSERT -> ChangeType.INSERT
                        com.github.difflib.text.DiffRow.Tag.   DELETE -> ChangeType.DELETE
                        com.github.difflib.text.DiffRow.Tag.    CHANGE -> ChangeType.CHANGE
                          else -> ChangeType.EQUAL
                    }
                )
            }
    }
    
    data class DiffRow(
        val sourceLineNumber: Int,
        val targetLineNumber: Int,
        val sourceText: String,
        val targetText: String,
        val type: ChangeType
    )
    
    /**
     * Check if two strings are identical (useful for quick equality check)
     */
    fun areIdentical(
        source: String,
        target: String,
        config: DiffConfig = DiffConfig()
    ): Boolean {
        val sourceLines = preprocessLines(source.lines(), config)
        val targetLines = preprocessLines(target.lines(), config)
        return sourceLines == targetLines
    }
    
    /**
     * Get only the changed lines (no context)
     */
    fun getChangedLinesOnly(
        source: String,
        target: String,
        config: DiffConfig = DiffConfig()
    ): DiffResult {
        val result = diffStrings(source, target, config)
        val changedOnly = result.changes.filter { it.type != ChangeType.EQUAL }
        return result.copy(changes = changedOnly)
    }
    
    // Private helper methods
    
    private fun preprocessLines(lines: List<String>, config: DiffConfig): List<String> {
        return lines.map { line ->
            var processed = line
            if (config.ignoreCase) {
                processed = processed.lowercase()
            }
            if (config.ignoreWhitespace) {
                processed = processed.trim()
            }
            processed
        }
    }
    
    private fun convertPatchToChanges(
        patch: Patch<String>,
        sourceLines: List<String>,
        targetLines: List<String>
    ): List<DiffChange> {
        val changes = mutableListOf<DiffChange>()
        var sourceIndex = 0
        var targetIndex = 0
        
        for (delta in patch.deltas) {
            // Add equal lines before this delta
            while (sourceIndex < delta.source.position) {
                changes.add(
                    DiffChange(
                        type = ChangeType.EQUAL,
                        sourceLineNumber = sourceIndex + 1,
                        targetLineNumber = targetIndex + 1,
                        sourceLines = listOf(sourceLines[sourceIndex]),
                        targetLines = listOf(targetLines[targetIndex])
                    )
                )
                sourceIndex++
                targetIndex++
            }
            
            // Add the delta change
            val change = when (delta.type) {
                DeltaType.DELETE -> DiffChange(
                    type = ChangeType.DELETE,
                    sourceLineNumber = sourceIndex + 1,
                    targetLineNumber = targetIndex + 1,
                    sourceLines = delta.source.lines,
                    targetLines = emptyList()
                )
                DeltaType.INSERT -> DiffChange(
                    type = ChangeType.INSERT,
                    sourceLineNumber = sourceIndex + 1,
                    targetLineNumber = targetIndex + 1,
                    sourceLines = emptyList(),
                    targetLines = delta.target.lines
                )
                DeltaType.CHANGE -> DiffChange(
                    type = ChangeType.CHANGE,
                    sourceLineNumber = sourceIndex + 1,
                    targetLineNumber = targetIndex + 1,
                    sourceLines = delta.source.lines,
                    targetLines = delta.target.lines
                )
                else -> DiffChange(
                    type = ChangeType.EQUAL,
                    sourceLineNumber = sourceIndex + 1,
                    targetLineNumber = targetIndex + 1,
                    sourceLines = delta.source.lines,
                    targetLines = delta.target.lines
                )
            }
            changes.add(change)
            
            sourceIndex += delta.source.size()
            targetIndex += delta.target.size()
        }
        
        // Add remaining equal lines
        while (sourceIndex < sourceLines.size && targetIndex < targetLines.size) {
            changes.add(
                DiffChange(
                    type = ChangeType.EQUAL,
                    sourceLineNumber = sourceIndex + 1,
                    targetLineNumber = targetIndex + 1,
                    sourceLines = listOf(sourceLines[sourceIndex]),
                    targetLines = listOf(targetLines[targetIndex])
                )
            )
            sourceIndex++
            targetIndex++
        }
        
        return changes
    }
}
