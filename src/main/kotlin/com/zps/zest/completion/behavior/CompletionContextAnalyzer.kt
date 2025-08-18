package com.zps.zest.completion.behavior

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.zps.zest.completion.data.ZestInlineCompletionItem

/**
 * Analyzes completion context to determine the most appropriate behavior for user interactions.
 * This class provides sophisticated analysis of when completions should be accepted vs.
 * when normal editor behavior should take precedence.
 */
object CompletionContextAnalyzer {

    /**
     * Analyzes whether a completion should be accepted when TAB is pressed.
     */
    fun shouldAcceptOnTab(
        editor: Editor,
        offset: Int,
        completion: ZestInlineCompletionItem,
        project: Project
    ): TabBehaviorDecision {
        val indentationContext = IndentationAnalyzer.analyzeIndentationContext(editor, offset, project)
        val completionAnalysis = analyzeCompletion(completion)
        val contextAnalysis = analyzeEditingContext(indentationContext)
        
        return TabBehaviorDecision.decide(indentationContext, completionAnalysis, contextAnalysis)
    }

    /**
     * Analyzes the characteristics of a completion item.
     */
    fun analyzeCompletion(completion: ZestInlineCompletionItem): CompletionAnalysis {
        val text = completion.insertText
        val trimmedText = text.trim()
        
        return CompletionAnalysis(
            isWhitespaceOnly = text.isBlank(),
            startsWithWhitespace = text.isNotEmpty() && text[0].isWhitespace(),
            endsWithWhitespace = text.isNotEmpty() && text.last().isWhitespace(),
            containsNewlines = text.contains('\n'),
            isCodeLike = isCodeLikeContent(trimmedText),
            isIndentationLike = isIndentationLikeContent(text),
            hasStructuralCharacters = hasStructuralCharacters(trimmedText),
            contentType = determineContentType(text),
            leadingWhitespace = text.takeWhile { it.isWhitespace() },
            trailingWhitespace = text.takeLastWhile { it.isWhitespace() },
            codeContent = trimmedText
        )
    }

    /**
     * Analyzes the current editing context.
     */
    fun analyzeEditingContext(indentationContext: IndentationAnalyzer.IndentationContext): EditingContextAnalysis {
        val isInIndentation = IndentationAnalyzer.isInIndentationPosition(indentationContext)
        val expectedIndent = IndentationAnalyzer.calculateExpectedIndentationLevel(indentationContext)
        val currentIndent = IndentationAnalyzer.calculateIndentationLevel(
            indentationContext.lineText,
            IndentationAnalyzer.getIndentationSettings(indentationContext)
        )
        
        return EditingContextAnalysis(
            isInIndentationPosition = isInIndentation,
            isAtLineStart = indentationContext.isAtLineStart,
            hasContentOnLine = indentationContext.hasNonWhitespaceContent,
            isIndentationComplete = currentIndent >= expectedIndent,
            needsMoreIndentation = currentIndent < expectedIndent,
            indentationDeficit = maxOf(0, expectedIndent - currentIndent),
            editingMode = determineEditingMode(indentationContext),
            contextType = determineContextType(indentationContext)
        )
    }

    /**
     * Determines if content appears to be code rather than just whitespace/formatting.
     */
    private fun isCodeLikeContent(text: String): Boolean {
        if (text.isBlank()) return false
        
        val codeIndicators = listOf(
            Regex("[a-zA-Z_][a-zA-Z0-9_]*\\s*\\("), // Function calls
            Regex("[a-zA-Z_][a-zA-Z0-9_]*\\s*="), // Assignments
            Regex("\\b(if|for|while|when|match|def|fun|function|class|struct|enum)\\b"), // Keywords
            Regex("[{()}\\[\\];,.]"), // Structural characters
            Regex("\\b\\d+\\b"), // Numbers
            Regex("\".*\"|'.*'"), // Strings
            Regex("//|/\\*|#|<!--"), // Comments
        )
        
        return codeIndicators.any { it.containsMatchIn(text) }
    }

    /**
     * Determines if content appears to be primarily indentation/whitespace formatting.
     */
    private fun isIndentationLikeContent(text: String): Boolean {
        if (text.isBlank()) return true
        
        val whitespaceRatio = text.count { it.isWhitespace() }.toDouble() / text.length
        return whitespaceRatio > 0.8 || text.matches(Regex("^\\s+\\S*$"))
    }

    /**
     * Checks if text contains structural programming characters.
     */
    private fun hasStructuralCharacters(text: String): Boolean {
        return text.any { it in "{()}[];,." }
    }

    /**
     * Determines the primary content type of the completion.
     */
    private fun determineContentType(text: String): ContentType {
        return when {
            text.isBlank() -> ContentType.WHITESPACE
            isIndentationLikeContent(text) -> ContentType.INDENTATION
            isCodeLikeContent(text.trim()) -> ContentType.CODE
            text.all { it.isLetterOrDigit() || it in "_$" } -> ContentType.IDENTIFIER
            text.contains('\n') -> ContentType.MULTILINE
            else -> ContentType.MIXED
        }
    }

    /**
     * Determines the current editing mode based on context.
     */
    private fun determineEditingMode(context: IndentationAnalyzer.IndentationContext): EditingMode {
        return when {
            context.isAtLineStart -> EditingMode.LINE_START
            context.isInWhitespaceOnly && !context.hasNonWhitespaceContent -> EditingMode.INDENTING
            context.hasNonWhitespaceContent -> EditingMode.CODING
            else -> EditingMode.UNKNOWN
        }
    }

    /**
     * Determines the type of context where editing is happening.
     */
    private fun determineContextType(context: IndentationAnalyzer.IndentationContext): ContextType {
        val languageId = context.language?.id?.lowercase()
        
        return when (languageId) {
            "java", "kotlin", "scala", "groovy" -> ContextType.JVM_LANGUAGE
            "python" -> ContextType.PYTHON
            "javascript", "typescript" -> ContextType.JAVASCRIPT
            "go" -> ContextType.GO
            "rust" -> ContextType.RUST
            "c", "cpp", "c++" -> ContextType.C_FAMILY
            "html", "xml" -> ContextType.MARKUP
            "json", "yaml", "yml" -> ContextType.DATA_FORMAT
            else -> ContextType.GENERIC
        }
    }

    /**
     * Represents the analysis of a completion item.
     */
    data class CompletionAnalysis(
        val isWhitespaceOnly: Boolean,
        val startsWithWhitespace: Boolean,
        val endsWithWhitespace: Boolean,
        val containsNewlines: Boolean,
        val isCodeLike: Boolean,
        val isIndentationLike: Boolean,
        val hasStructuralCharacters: Boolean,
        val contentType: ContentType,
        val leadingWhitespace: String,
        val trailingWhitespace: String,
        val codeContent: String
    )

    /**
     * Represents the analysis of the current editing context.
     */
    data class EditingContextAnalysis(
        val isInIndentationPosition: Boolean,
        val isAtLineStart: Boolean,
        val hasContentOnLine: Boolean,
        val isIndentationComplete: Boolean,
        val needsMoreIndentation: Boolean,
        val indentationDeficit: Int,
        val editingMode: EditingMode,
        val contextType: ContextType
    )

    /**
     * Represents a decision about tab behavior.
     */
    data class TabBehaviorDecision(
        val shouldAcceptCompletion: Boolean,
        val confidence: Double,
        val reason: String,
        val fallbackToIndentation: Boolean = false
    ) {
        companion object {
            fun decide(
                indentationContext: IndentationAnalyzer.IndentationContext,
                completionAnalysis: CompletionAnalysis,
                contextAnalysis: EditingContextAnalysis
            ): TabBehaviorDecision {
                
                // High confidence rejections
                if (completionAnalysis.isIndentationLike && contextAnalysis.isInIndentationPosition) {
                    return TabBehaviorDecision(
                        shouldAcceptCompletion = false,
                        confidence = 0.9,
                        reason = "Completion is indentation-like in indentation context",
                        fallbackToIndentation = true
                    )
                }
                
                if (contextAnalysis.needsMoreIndentation && completionAnalysis.isWhitespaceOnly) {
                    return TabBehaviorDecision(
                        shouldAcceptCompletion = false,
                        confidence = 0.85,
                        reason = "Line needs more indentation and completion is whitespace",
                        fallbackToIndentation = true
                    )
                }
                
                // High confidence acceptances
                if (completionAnalysis.isCodeLike && !contextAnalysis.isInIndentationPosition) {
                    return TabBehaviorDecision(
                        shouldAcceptCompletion = true,
                        confidence = 0.9,
                        reason = "Completion is code-like and not in indentation context"
                    )
                }
                
                if (completionAnalysis.hasStructuralCharacters && contextAnalysis.hasContentOnLine) {
                    return TabBehaviorDecision(
                        shouldAcceptCompletion = true,
                        confidence = 0.8,
                        reason = "Completion has structural characters and line has content"
                    )
                }
                
                // Medium confidence decisions
                if (contextAnalysis.isIndentationComplete && completionAnalysis.contentType == ContentType.CODE) {
                    return TabBehaviorDecision(
                        shouldAcceptCompletion = true,
                        confidence = 0.7,
                        reason = "Indentation complete and completion is code"
                    )
                }
                
                if (contextAnalysis.editingMode == EditingMode.CODING) {
                    return TabBehaviorDecision(
                        shouldAcceptCompletion = true,
                        confidence = 0.6,
                        reason = "Currently in coding mode"
                    )
                }
                
                // Default to safe indentation behavior
                return TabBehaviorDecision(
                    shouldAcceptCompletion = false,
                    confidence = 0.5,
                    reason = "Default safe behavior - prefer indentation",
                    fallbackToIndentation = true
                )
            }
        }
    }

    /**
     * Types of content in completions.
     */
    enum class ContentType {
        WHITESPACE,
        INDENTATION,
        CODE,
        IDENTIFIER,
        MULTILINE,
        MIXED
    }

    /**
     * Different editing modes.
     */
    enum class EditingMode {
        LINE_START,
        INDENTING,
        CODING,
        UNKNOWN
    }

    /**
     * Different context types based on language and file type.
     */
    enum class ContextType {
        JVM_LANGUAGE,
        PYTHON,
        JAVASCRIPT,
        GO,
        RUST,
        C_FAMILY,
        MARKUP,
        DATA_FORMAT,
        GENERIC
    }
}
