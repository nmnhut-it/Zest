package com.zps.zest.completion.behavior

import com.intellij.lang.Language
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettingsManager

/**
 * Utility class for analyzing indentation patterns and context in code.
 */
object IndentationAnalyzer {

    /**
     * Analyzes the indentation context at a specific position in the editor.
     */
    fun analyzeIndentationContext(editor: Editor, offset: Int, project: Project): IndentationContext {
        val document = editor.document
        val lineNumber = document.getLineNumber(offset)
        val lineStartOffset = document.getLineStartOffset(lineNumber)
        val lineEndOffset = document.getLineEndOffset(lineNumber)
        val columnNumber = offset - lineStartOffset
        
        val lineText = document.getText(TextRange(lineStartOffset, lineEndOffset))
        val linePrefix = if (columnNumber <= lineText.length) {
            lineText.substring(0, columnNumber)
        } else {
            lineText
        }
        
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
        val language = psiFile?.language
        
        return IndentationContext(
            lineNumber = lineNumber,
            columnNumber = columnNumber,
            lineText = lineText,
            linePrefix = linePrefix,
            language = language,
            document = document,
            project = project
        )
    }

    /**
     * Determines if the cursor is positioned where indentation is expected.
     */
    fun isInIndentationPosition(context: IndentationContext): Boolean {
        return when {
            // At the very start of line
            context.columnNumber == 0 -> true
            
            // In whitespace-only prefix
            context.linePrefix.all { it.isWhitespace() } -> true
            
            // Following established indentation pattern
            isFollowingIndentationPattern(context) -> true
            
            // After structural elements that typically require indentation
            isAfterIndentationTrigger(context) -> true
            
            else -> false
        }
    }

    /**
     * Calculates the expected indentation level for the current line.
     */
    fun calculateExpectedIndentationLevel(context: IndentationContext): Int {
        val settings = getIndentationSettings(context)
        
        // Look at previous non-empty lines to determine expected indentation
        for (i in (context.lineNumber - 1) downTo 0) {
            val prevLineStart = context.document.getLineStartOffset(i)
            val prevLineEnd = context.document.getLineEndOffset(i)
            val prevLineText = context.document.getText(TextRange(prevLineStart, prevLineEnd))
            
            if (prevLineText.isNotBlank()) {
                val prevIndentLevel = calculateIndentationLevel(prevLineText, settings)
                
                // Check if previous line should increase indentation
                val shouldIncreaseIndent = shouldIncreaseIndentationAfter(prevLineText, context.language)
                
                return if (shouldIncreaseIndent) {
                    prevIndentLevel + settings.indentSize
                } else {
                    prevIndentLevel
                }
            }
        }
        
        return 0
    }

    /**
     * Calculates the current indentation level of a line.
     */
    fun calculateIndentationLevel(lineText: String, settings: IndentationSettings): Int {
        var level = 0
        for (char in lineText) {
            when (char) {
                '\t' -> level += settings.tabSize
                ' ' -> level += 1
                else -> break
            }
        }
        return level
    }

    /**
     * Gets indentation settings for the given context.
     */
    fun getIndentationSettings(context: IndentationContext): IndentationSettings {
        val codeStyleSettings = CodeStyleSettingsManager.getInstance(context.project).currentSettings
        val language = context.language
        
        val commonSettings = if (language != null) {
            codeStyleSettings.getCommonSettings(language)
        } else {
            codeStyleSettings.OTHER_INDENT_OPTIONS
        }
        
        return IndentationSettings(
            useTabCharacter = commonSettings?.indentOptions?.USE_TAB_CHARACTER ?: false,
            indentSize = commonSettings?.indentOptions?.INDENT_SIZE ?: 4,
            tabSize = commonSettings?.indentOptions?.TAB_SIZE ?: 4,
            continuationIndentSize = commonSettings?.indentOptions?.CONTINUATION_INDENT_SIZE ?: 8
        )
    }

    /**
     * Checks if the current position follows an established indentation pattern.
     */
    private fun isFollowingIndentationPattern(context: IndentationContext): Boolean {
        if (context.lineNumber == 0) return false
        
        val currentWhitespace = context.linePrefix.takeWhile { it.isWhitespace() }
        if (currentWhitespace.isEmpty()) return false
        
        // Look for similar patterns in nearby lines
        val searchRange = maxOf(0, context.lineNumber - 10)..minOf(context.document.lineCount - 1, context.lineNumber + 10)
        
        for (lineNum in searchRange) {
            if (lineNum == context.lineNumber) continue
            
            val lineStart = context.document.getLineStartOffset(lineNum)
            val lineEnd = context.document.getLineEndOffset(lineNum)
            val lineText = context.document.getText(TextRange(lineStart, lineEnd))
            
            if (lineText.isNotBlank()) {
                val lineWhitespace = lineText.takeWhile { it.isWhitespace() }
                if (lineWhitespace == currentWhitespace) {
                    return true
                }
            }
        }
        
        return false
    }

    /**
     * Checks if the cursor is positioned after elements that typically trigger indentation.
     */
    private fun isAfterIndentationTrigger(context: IndentationContext): Boolean {
        if (context.lineNumber == 0) return false
        
        val prevLineStart = context.document.getLineStartOffset(context.lineNumber - 1)
        val prevLineEnd = context.document.getLineEndOffset(context.lineNumber - 1)
        val prevLineText = context.document.getText(TextRange(prevLineStart, prevLineEnd)).trim()
        
        return when (context.language?.id?.lowercase()) {
            "java", "kotlin", "scala" -> {
                prevLineText.endsWith("{") || 
                prevLineText.endsWith("(") ||
                prevLineText.endsWith("->") ||
                prevLineText.endsWith("=")
            }
            "python" -> {
                prevLineText.endsWith(":") ||
                prevLineText.endsWith("\\") ||
                prevLineText.endsWith("(") ||
                prevLineText.endsWith("[") ||
                prevLineText.endsWith("{")
            }
            "javascript", "typescript" -> {
                prevLineText.endsWith("{") ||
                prevLineText.endsWith("(") ||
                prevLineText.endsWith("=>") ||
                prevLineText.endsWith("=")
            }
            "go" -> {
                prevLineText.endsWith("{") ||
                prevLineText.endsWith("(")
            }
            "rust" -> {
                prevLineText.endsWith("{") ||
                prevLineText.endsWith("(") ||
                prevLineText.endsWith("->")
            }
            else -> {
                // Generic fallback for unknown languages
                prevLineText.endsWith("{") ||
                prevLineText.endsWith("(") ||
                prevLineText.endsWith("[")
            }
        }
    }

    /**
     * Determines if indentation should be increased after the given line.
     */
    private fun shouldIncreaseIndentationAfter(lineText: String, language: Language?): Boolean {
        val trimmed = lineText.trim()
        
        return when (language?.id?.lowercase()) {
            "java", "kotlin", "scala" -> {
                trimmed.endsWith("{") && !trimmed.startsWith("}")
            }
            "python" -> {
                trimmed.endsWith(":")
            }
            "javascript", "typescript" -> {
                trimmed.endsWith("{") && !trimmed.startsWith("}")
            }
            "go" -> {
                trimmed.endsWith("{") && !trimmed.startsWith("}")
            }
            "rust" -> {
                trimmed.endsWith("{") && !trimmed.startsWith("}")
            }
            else -> {
                // Generic fallback
                trimmed.endsWith("{") && !trimmed.startsWith("}")
            }
        }
    }

    /**
     * Represents the indentation context at a specific position.
     */
    data class IndentationContext(
        val lineNumber: Int,
        val columnNumber: Int,
        val lineText: String,
        val linePrefix: String,
        val language: Language?,
        val document: Document,
        val project: Project
    ) {
        val isAtLineStart: Boolean get() = columnNumber == 0
        val hasNonWhitespaceContent: Boolean get() = linePrefix.any { !it.isWhitespace() }
        val whitespacePrefix: String get() = linePrefix.takeWhile { it.isWhitespace() }
        val isInWhitespaceOnly: Boolean get() = linePrefix.all { it.isWhitespace() }
    }

    /**
     * Represents indentation settings for a specific language/context.
     */
    data class IndentationSettings(
        val useTabCharacter: Boolean,
        val indentSize: Int,
        val tabSize: Int,
        val continuationIndentSize: Int
    )
}
