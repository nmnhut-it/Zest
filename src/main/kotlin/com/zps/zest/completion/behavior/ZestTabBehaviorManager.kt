package com.zps.zest.completion.behavior

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.zps.zest.completion.data.ZestInlineCompletionItem

/**
 * Advanced tab behavior manager that provides intelligent context-aware tab handling.
 * This service analyzes the current editing context to determine when TAB should
 * accept completions vs. perform normal indentation.
 */
@Service(Service.Level.PROJECT)
class ZestTabBehaviorManager(private val project: Project) {
    private val logger = Logger.getInstance(ZestTabBehaviorManager::class.java)

    /**
     * Determines whether TAB should accept the completion or allow normal indentation behavior.
     * 
     * @param editor The current editor
     * @param offset The cursor position
     * @param completion The completion item to potentially accept
     * @return true if TAB should accept the completion, false to allow normal TAB behavior
     */
    fun shouldAcceptCompletionOnTab(
        editor: Editor,
        offset: Int,
        completion: ZestInlineCompletionItem
    ): Boolean {
        val decision = CompletionContextAnalyzer.shouldAcceptOnTab(editor, offset, completion, project)
        
        System.out.println("Tab behavior decision: $decision")
        
        // Use high-confidence decisions directly
        if (decision.confidence >= 0.8) {
            return decision.shouldAcceptCompletion
        }
        
        // For medium confidence, add additional checks
        if (decision.confidence >= 0.6) {
            val additionalChecks = performAdditionalTabChecks(editor, offset, completion)
            val adjustedDecision = decision.shouldAcceptCompletion && additionalChecks
            
            System.out.println("Medium confidence decision adjusted: $adjustedDecision (additional checks: $additionalChecks)")
            return adjustedDecision
        }
        
        // For low confidence, default to safe behavior (no acceptance)
        System.out.println("Low confidence, defaulting to safe behavior (no acceptance)")
        return false
    }

    /**
     * Performs additional checks for medium-confidence tab decisions.
     */
    private fun performAdditionalTabChecks(
        editor: Editor,
        offset: Int,
        completion: ZestInlineCompletionItem
    ): Boolean {
        val context = TabContext.analyze(editor, offset, project)
        
        return when {
            // Don't accept if it would disrupt expected indentation flow
            context.isInIndentationContext && completion.isIndentationCompletion() -> false
            
            // Don't accept if completion is significantly different from context
            completion.conflictsWithIndentation(context) -> false
            
            // Accept if completion adds meaningful content
            completion.isCodeCompletion() && !context.isInIndentationContext -> true
            
            // Default to acceptance if we've made it this far
            else -> true
        }
    }

    /**
     * Analyzes the indentation pattern of the current file to understand user preferences.
     */
    fun analyzeIndentationPattern(editor: Editor): IndentationPattern {
        val document = editor.document
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
        
        // Get code style settings for this file type
        val codeStyleSettings = CodeStyleSettingsManager.getInstance(project).currentSettings
        val languageSettings = psiFile?.let { 
            codeStyleSettings.getCommonSettings(it.language)
        }
        
        val useTabCharacter = languageSettings?.indentOptions?.USE_TAB_CHARACTER ?: false
        val indentSize = languageSettings?.indentOptions?.INDENT_SIZE ?: 4
        val tabSize = languageSettings?.indentOptions?.TAB_SIZE ?: 4
        
        // Analyze actual file content to detect mixed indentation
        val detectedPattern = detectActualIndentationPattern(document)
        
        return IndentationPattern(
            preferredIndentType = if (useTabCharacter) IndentType.TAB else IndentType.SPACE,
            indentSize = indentSize,
            tabSize = tabSize,
            detectedPattern = detectedPattern,
            hasMixedIndentation = detectedPattern.hasMixedIndentation
        )
    }

    /**
     * Checks if the given position is in a context where indentation is expected.
     */
    fun isIndentationExpected(editor: Editor, offset: Int): Boolean {
        val context = TabContext.analyze(editor, offset, project)
        
        return when {
            // At start of line - likely indentation
            context.isAtLineStart -> true
            
            // After opening braces - likely need indentation
            context.isAfterOpeningBrace -> true
            
            // In whitespace-only prefix - likely indentation
            context.linePrefix.isBlank() -> true
            
            // Following indentation pattern
            context.isFollowingIndentationPattern -> true
            
            else -> false
        }
    }

    /**
     * Detects the actual indentation pattern used in the document.
     */
    private fun detectActualIndentationPattern(document: Document): DetectedIndentationPattern {
        val lineCount = minOf(document.lineCount, 100) // Analyze first 100 lines
        var tabCount = 0
        var spaceIndentCount = 0
        var mixedLines = 0
        val spaceSizes = mutableMapOf<Int, Int>()
        
        for (i in 0 until lineCount) {
            val lineStartOffset = document.getLineStartOffset(i)
            val lineEndOffset = document.getLineEndOffset(i)
            val lineText = document.getText(TextRange(lineStartOffset, lineEndOffset))
            
            if (lineText.isBlank()) continue
            
            val leadingWhitespace = lineText.takeWhile { it.isWhitespace() }
            if (leadingWhitespace.isEmpty()) continue
            
            val hasTab = leadingWhitespace.contains('\t')
            val hasSpace = leadingWhitespace.contains(' ')
            
            when {
                hasTab && hasSpace -> mixedLines++
                hasTab -> tabCount++
                hasSpace -> {
                    spaceIndentCount++
                    spaceSizes[leadingWhitespace.length] = spaceSizes.getOrDefault(leadingWhitespace.length, 0) + 1
                }
            }
        }
        
        val mostCommonSpaceSize = spaceSizes.maxByOrNull { it.value }?.key ?: 4
        val predominantType = when {
            tabCount > spaceIndentCount -> IndentType.TAB
            else -> IndentType.SPACE
        }
        
        return DetectedIndentationPattern(
            predominantType = predominantType,
            detectedSpaceSize = mostCommonSpaceSize,
            hasMixedIndentation = mixedLines > 0,
            confidence = if (tabCount + spaceIndentCount == 0) 0.0 else {
                maxOf(tabCount, spaceIndentCount).toDouble() / (tabCount + spaceIndentCount)
            }
        )
    }

    /**
     * Represents the context around a TAB operation.
     */
    data class TabContext(
        val lineNumber: Int,
        val columnNumber: Int,
        val linePrefix: String,
        val lineSuffix: String,
        val isAtLineStart: Boolean,
        val isInIndentationContext: Boolean,
        val isAfterOpeningBrace: Boolean,
        val isFollowingIndentationPattern: Boolean,
        val hasNonWhitespaceContent: Boolean,
        val indentationLevel: Int,
        val expectedIndentationLevel: Int,
        val language: String?
    ) {
        companion object {
            fun analyze(editor: Editor, offset: Int, project: Project): TabContext {
                val document = editor.document
                val lineNumber = document.getLineNumber(offset)
                val lineStartOffset = document.getLineStartOffset(lineNumber)
                val lineEndOffset = document.getLineEndOffset(lineNumber)
                val columnNumber = offset - lineStartOffset
                
                val lineText = document.getText(TextRange(lineStartOffset, lineEndOffset))
                val linePrefix = lineText.substring(0, minOf(columnNumber, lineText.length))
                val lineSuffix = lineText.substring(minOf(columnNumber, lineText.length))
                
                val isAtLineStart = columnNumber == 0
                val hasNonWhitespaceContent = linePrefix.any { !it.isWhitespace() }
                
                // Detect indentation context
                val isInIndentationContext = linePrefix.isBlank() || 
                    (linePrefix.all { it.isWhitespace() } && linePrefix.isNotEmpty())
                
                // Check for opening braces that typically require indentation
                val isAfterOpeningBrace = linePrefix.trimEnd().endsWith("{") ||
                    linePrefix.trimEnd().endsWith("(") ||
                    linePrefix.trimEnd().endsWith("[")
                
                // Analyze indentation levels
                val currentIndentLevel = calculateIndentationLevel(linePrefix)
                val expectedIndentLevel = calculateExpectedIndentationLevel(document, lineNumber)
                
                // Check if following indentation pattern
                val isFollowingPattern = isFollowingIndentationPattern(document, lineNumber, columnNumber)
                
                // Get language info
                val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
                val language = psiFile?.language?.id
                
                return TabContext(
                    lineNumber = lineNumber,
                    columnNumber = columnNumber,
                    linePrefix = linePrefix,
                    lineSuffix = lineSuffix,
                    isAtLineStart = isAtLineStart,
                    isInIndentationContext = isInIndentationContext,
                    isAfterOpeningBrace = isAfterOpeningBrace,
                    isFollowingIndentationPattern = isFollowingPattern,
                    hasNonWhitespaceContent = hasNonWhitespaceContent,
                    indentationLevel = currentIndentLevel,
                    expectedIndentationLevel = expectedIndentLevel,
                    language = language
                )
            }
            
            private fun calculateIndentationLevel(linePrefix: String): Int {
                var level = 0
                for (char in linePrefix) {
                    when (char) {
                        '\t' -> level += 1
                        ' ' -> level += 1 // Will be normalized based on indent size
                        else -> break
                    }
                }
                return level
            }
            
            private fun calculateExpectedIndentationLevel(document: Document, lineNumber: Int): Int {
                // Look at previous non-empty line to determine expected indentation
                for (i in (lineNumber - 1) downTo 0) {
                    val prevLineStartOffset = document.getLineStartOffset(i)
                    val prevLineEndOffset = document.getLineEndOffset(i)
                    val prevLineText = document.getText(TextRange(prevLineStartOffset, prevLineEndOffset))
                    
                    if (prevLineText.isNotBlank()) {
                        val prevIndent = calculateIndentationLevel(prevLineText.takeWhile { it.isWhitespace() })
                        
                        // If previous line ends with opening brace, expect increased indentation
                        return if (prevLineText.trimEnd().endsWith("{") ||
                                  prevLineText.trimEnd().endsWith("(") ||
                                  prevLineText.trimEnd().endsWith("[")) {
                            prevIndent + 4 // Default indent size
                        } else {
                            prevIndent
                        }
                    }
                }
                return 0
            }
            
            private fun isFollowingIndentationPattern(document: Document, lineNumber: Int, columnNumber: Int): Boolean {
                // Check if the current position follows established indentation pattern
                if (lineNumber == 0) return false
                
                val currentLineStart = document.getLineStartOffset(lineNumber)
                val currentLineEnd = document.getLineEndOffset(lineNumber)
                val currentLine = document.getText(TextRange(currentLineStart, currentLineEnd))
                
                if (columnNumber > currentLine.takeWhile { it.isWhitespace() }.length) {
                    return false // Not in indentation area
                }
                
                // Look for pattern in previous lines
                for (i in (lineNumber - 1) downTo maxOf(0, lineNumber - 5)) {
                    val lineStart = document.getLineStartOffset(i)
                    val lineEnd = document.getLineEndOffset(i)
                    val lineText = document.getText(TextRange(lineStart, lineEnd))
                    
                    if (lineText.isNotBlank()) {
                        val indent = lineText.takeWhile { it.isWhitespace() }
                        if (indent.isNotEmpty()) {
                            // Check if current position matches this indentation pattern
                            return columnNumber <= indent.length
                        }
                    }
                }
                
                return false
            }
        }
    }

    /**
     * Represents indentation configuration and patterns.
     */
    data class IndentationPattern(
        val preferredIndentType: IndentType,
        val indentSize: Int,
        val tabSize: Int,
        val detectedPattern: DetectedIndentationPattern,
        val hasMixedIndentation: Boolean
    )

    /**
     * Represents detected indentation pattern from file analysis.
     */
    data class DetectedIndentationPattern(
        val predominantType: IndentType,
        val detectedSpaceSize: Int,
        val hasMixedIndentation: Boolean,
        val confidence: Double
    )

    /**
     * Types of indentation.
     */
    enum class IndentType {
        TAB, SPACE
    }
}

/**
 * Extension functions for completion items to analyze their indentation characteristics.
 */
private fun ZestInlineCompletionItem.isIndentationCompletion(): Boolean {
    return insertText.matches(Regex("^\\s+.*"))
}

private fun ZestInlineCompletionItem.isCodeCompletion(): Boolean {
    val trimmed = insertText.trim()
    return trimmed.isNotEmpty() && !insertText.matches(Regex("^\\s*$"))
}

private fun ZestInlineCompletionItem.conflictsWithIndentation(context: ZestTabBehaviorManager.TabContext): Boolean {
    // Check if accepting this completion would create indentation conflicts
    if (!isIndentationCompletion()) return false
    
    val leadingWhitespace = insertText.takeWhile { it.isWhitespace() }
    val contextWhitespace = context.linePrefix.takeWhile { it.isWhitespace() }
    
    // Conflict if completion would change established indentation pattern
    return leadingWhitespace != contextWhitespace && context.hasNonWhitespaceContent
}
