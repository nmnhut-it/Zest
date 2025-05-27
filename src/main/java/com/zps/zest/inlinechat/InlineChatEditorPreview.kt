package com.zps.zest.inlinechat

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font

/**
 * Manages inline preview of chat changes directly in the editor
 */
class InlineChatEditorPreview(
    private val project: Project,
    private val editor: Editor
) {
    private var originalContent: String? = null
    private var originalStartOffset: Int = 0
    private var originalEndOffset: Int = 0
    private var modifiedContentLength: Int = 0  // Track the length of modified content
    private var previewHighlighters = mutableListOf<RangeHighlighter>()
    
    companion object {
        // Preview text attributes
        private val PREVIEW_ADDED = TextAttributes(
            null,
            JBColor(Color(220, 255, 220), Color(45, 65, 45)),  // Light green / dark green
            null,
            null,
            Font.PLAIN
        )
        
        private val PREVIEW_REMOVED = TextAttributes(
            null,
            JBColor(Color(255, 220, 220), Color(65, 45, 45)),  // Light red / dark red
            null,
            null,
            Font.PLAIN
        ).apply {
            effectType = EffectType.STRIKEOUT
            effectColor = JBColor.RED
        }
        
        private val PREVIEW_CONTEXT = TextAttributes(
            null,
            JBColor(Color(245, 245, 245), Color(30, 30, 30)),  // Very light gray / very dark gray
            null,
            null,
            Font.PLAIN
        )
    }
    
    /**
     * Show preview by temporarily replacing the selected text with the modified version
     */
    fun showPreview(
        originalText: String,
        modifiedText: String,
        startOffset: Int,
        endOffset: Int
    ) {
        ApplicationManager.getApplication().invokeLater {
            WriteCommandAction.runWriteCommandAction(project, "Show Inline Chat Preview", null, Runnable {
                // Store original state - this should be the text that's currently in the selection
                originalContent = editor.document.getText(TextRange(startOffset, endOffset))
                originalStartOffset = startOffset
                originalEndOffset = endOffset
                modifiedContentLength = modifiedText.length  // Store the modified content length
                
                // Replace with modified text
                editor.document.replaceString(startOffset, endOffset, modifiedText)
                
                // Calculate the new end offset after replacement
                val newEndOffset = startOffset + modifiedText.length
                
                // Apply preview highlighting
                applyPreviewHighlighting(originalText, modifiedText, startOffset)
                
                // Scroll to the preview area
                editor.caretModel.moveToOffset(startOffset)
                editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
                
                // Add visual indicators that this is a preview
                addPreviewMarkers(startOffset, newEndOffset)
            })
        }
    }
    
    /**
     * Apply visual highlighting to show what changed
     */
    private fun applyPreviewHighlighting(
        originalText: String,
        modifiedText: String,
        baseOffset: Int
    ) {
        val markupModel = editor.markupModel
        val document = editor.document
        
        // Get diff segments from InlineChatService
        val inlineChatService = project.getService(InlineChatService::class.java)
        val baseLineNumber = document.getLineNumber(baseOffset)
        val segments = inlineChatService.generateDiffSegments(originalText, modifiedText, baseLineNumber)
        
        // Clear any existing preview highlighters
        clearPreviewHighlighters()
        
        // Apply highlighting for each segment
        segments.forEach { segment ->
            // Skip header/footer segments as they're not real content
            if (segment.type == DiffSegmentType.HEADER || segment.type == DiffSegmentType.FOOTER) {
                return@forEach
            }
            
            val startLine = segment.startLine
            val endLine = segment.endLine
            
            if (startLine < document.lineCount && endLine < document.lineCount) {
                val segmentStartOffset = document.getLineStartOffset(startLine)
                val segmentEndOffset = document.getLineEndOffset(endLine)
                
                val attributes = when (segment.type) {
                    DiffSegmentType.INSERTED -> TextAttributes(
                        null,
                        JBColor(Color(198, 255, 198), Color(59, 91, 59)),  // Light green / dark green
                        null,
                        null,
                        Font.PLAIN
                    )
                    DiffSegmentType.DELETED -> TextAttributes(
                        null,
                        JBColor(Color(255, 220, 220), Color(91, 59, 59)),  // Light red / dark red
                        null,
                        null,
                        Font.PLAIN
                    ).apply {
                        effectType = EffectType.STRIKEOUT
                        effectColor = JBColor.RED
                    }
                    DiffSegmentType.UNCHANGED -> TextAttributes(
                        null,
                        null,  // No background for unchanged
                        null,
                        null,
                        Font.PLAIN
                    )
                    else -> return@forEach
                }
                
                if (segmentStartOffset < segmentEndOffset && segmentStartOffset >= 0 && segmentEndOffset <= document.textLength) {
                    val highlighter = markupModel.addRangeHighlighter(
                        segmentStartOffset,
                        segmentEndOffset,
                        HighlighterLayer.SELECTION + 1,
                        attributes,
                        HighlighterTargetArea.EXACT_RANGE
                    )
                    
                    // Add tooltip
                    highlighter.errorStripeTooltip = when (segment.type) {
                        DiffSegmentType.INSERTED -> "Added by AI"
                        DiffSegmentType.DELETED -> "Removed by AI"
                        DiffSegmentType.UNCHANGED -> "Unchanged"
                        else -> ""
                    }
                    
                    previewHighlighters.add(highlighter)
                }
            }
        }
    }
    
    /**
     * Add visual markers to indicate this is a preview
     */
    private fun addPreviewMarkers(startOffset: Int, endOffset: Int) {
        val markupModel = editor.markupModel
        val document = editor.document
        val startLine = document.getLineNumber(startOffset)
        
        // Add a gutter icon to show this is a preview
        if (startLine > 0) {
            val previousLine = startLine - 1
            val gutterHighlighter = markupModel.addLineHighlighter(
                previousLine,
                HighlighterLayer.LAST,
                null
            )
            
            gutterHighlighter.gutterIconRenderer = PreviewGutterIconRenderer()
            previewHighlighters.add(gutterHighlighter)
        }
        
        // Add a subtle border around the preview area
        val borderHighlighter = markupModel.addRangeHighlighter(
            startOffset,
            endOffset,
            HighlighterLayer.CARET_ROW - 1,
            TextAttributes().apply {
                effectType = EffectType.ROUNDED_BOX
                effectColor = JBColor.BLUE.darker()
            },
            HighlighterTargetArea.EXACT_RANGE
        )
        previewHighlighters.add(borderHighlighter)
    }
    
    /**
     * Remove the preview and restore original content
     */
    fun hidePreview() {
        if (originalContent != null) {
            ApplicationManager.getApplication().invokeLater {
                WriteCommandAction.runWriteCommandAction(project, "Hide Inline Chat Preview", null, Runnable {
                    // Calculate the end offset of the preview content
                    val previewEndOffset = originalStartOffset + modifiedContentLength
                    
                    // Restore original content
                    editor.document.replaceString(
                        originalStartOffset,
                        previewEndOffset,
                        originalContent!!
                    )
                    
                    // Clear preview highlighters
                    clearPreviewHighlighters()
                    
                    // Reset state
                    originalContent = null
                    modifiedContentLength = 0
                })
            }
        }
    }
    
    /**
     * Accept the preview and make it permanent
     */
    fun acceptPreview() {
        ApplicationManager.getApplication().invokeLater {
            WriteCommandAction.runWriteCommandAction(project, "Accept Inline Chat Changes", null, Runnable {
                // Just clear the preview highlighting, keep the text
                clearPreviewHighlighters()
                
                // Reset state
                originalContent = null
                modifiedContentLength = 0
                
                // Update the inline chat service state
                val inlineChatService = project.getService(InlineChatService::class.java)
                inlineChatService.clearState()
                
                // Force a refresh to clear diff highlights
                com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
                
                // Clear the selection after accepting
                editor.selectionModel.removeSelection()
            })
        }
    }
    
    /**
     * Find the actual end offset of the preview content
     */
    private fun findPreviewEndOffset(): Int {
        // Look for the last preview highlighter to determine the actual end
        val lastHighlighter = previewHighlighters
            .filter { it.startOffset >= originalStartOffset }
            .maxByOrNull { it.endOffset }
        
        return lastHighlighter?.endOffset ?: (originalStartOffset + editor.document.textLength - originalStartOffset)
    }
    
    /**
     * Clear all preview highlighters
     */
    private fun clearPreviewHighlighters() {
        val markupModel = editor.markupModel
        previewHighlighters.forEach { highlighter ->
            if (highlighter.isValid) {
                markupModel.removeHighlighter(highlighter)
            }
        }
        previewHighlighters.clear()
    }
    
    /**
     * Check if preview is currently active
     */
    fun isPreviewActive(): Boolean {
        return originalContent != null
    }
}

/**
 * Gutter icon renderer for preview indicator
 */
class PreviewGutterIconRenderer : GutterIconRenderer() {
    override fun getIcon() = com.intellij.icons.AllIcons.Actions.Preview
    
    override fun getTooltipText() = "AI Chat Preview - Click Accept or Reject to continue"
    
    override fun equals(other: Any?) = other is PreviewGutterIconRenderer
    
    override fun hashCode() = javaClass.hashCode()
}
