package com.zps.zest.inlinechat

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages inline ghost text preview without replacing the original text
 */
class InlineChatGhostTextPreview(
    private val project: Project,
    private val editor: Editor
) {
    private var originalStartOffset: Int = 0
    private var originalEndOffset: Int = 0
    private var originalText: String = ""
    private var modifiedText: String = ""
    private var previewHighlighters = mutableListOf<RangeHighlighter>()
    private var inlays = mutableListOf<Inlay<*>>()
    private var inlayCount = AtomicInteger(0)
    
    // Track whether the preview is active
    private var isActive = false
    
    companion object {
        // Colors for ghost text
        private val GHOST_TEXT_COLOR = JBColor(Color(0, 128, 0, 180), Color(0, 180, 0, 200))
        private val GHOST_TEXT_BACKGROUND = JBColor(Color(240, 255, 240, 120), Color(20, 40, 20, 120))
        
        // Highlight colors
        private val PREVIEW_ADDED_BG = JBColor(Color(220, 255, 220, 60), Color(45, 65, 45, 80))
        private val PREVIEW_REMOVED_BG = JBColor(Color(255, 220, 220, 60), Color(65, 45, 45, 80))
    }
    
    /**
     * Show preview using ghost text instead of replacing content
     */
    fun showPreview(
        originalText: String,
        modifiedText: String,
        startOffset: Int,
        endOffset: Int
    ) {
        // Store the original info
        this.originalText = originalText
        this.modifiedText = modifiedText
        this.originalStartOffset = startOffset
        this.originalEndOffset = endOffset
        
        // Clear any existing preview
        hidePreview()
        
        ApplicationManager.getApplication().invokeLater {
            try {
                // Generate diff between original and modified text
                val inlineChatService = project.getService(InlineChatService::class.java)
                val diffSegments = inlineChatService.generateDiffSegments(originalText, modifiedText, 0)
                
                // Split texts into lines
                val originalLines = originalText.split('\n')
                val modifiedLines = modifiedText.split('\n')
                
                // Apply original text highlighting
                highlightOriginalText(startOffset, endOffset)
                
                // Add ghost text inlays for modified content
                addGhostTextInlays(originalLines, modifiedLines, startOffset)
                
                // Scroll to the beginning of the preview
                editor.caretModel.moveToOffset(startOffset)
                editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
                
                // Add visual indicators that this is a preview
                addPreviewMarkers(startOffset, endOffset)
                
                // Mark as active
                isActive = true
                
            } catch (e: Exception) {
                System.out.println("Error showing ghost text preview: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    /**
     * Highlight the original text that will be affected
     */
    private fun highlightOriginalText(startOffset: Int, endOffset: Int) {
        val markupModel = editor.markupModel
        
        // Add a subtle background to the original text
        val highlighter = markupModel.addRangeHighlighter(
            startOffset,
            endOffset,
            HighlighterLayer.SELECTION - 1,
            TextAttributes().apply {
                backgroundColor = JBColor(Color(240, 240, 255, 40), Color(40, 40, 70, 40))
                effectType = EffectType.BOXED
                effectColor = JBColor.BLUE.brighter()
            },
            HighlighterTargetArea.EXACT_RANGE
        )
        
        previewHighlighters.add(highlighter)
    }
    
    /**
     * Add ghost text inlays for modified content
     */
    private fun addGhostTextInlays(originalLines: List<String>, modifiedLines: List<String>, baseOffset: Int) {
        val document = editor.document
        val inlayModel = editor.inlayModel
        
        // Get the line number for the base offset
        val baseLine = document.getLineNumber(baseOffset)
        
        // Use diff algorithm to find matching lines
        val inlineChatService = project.getService(InlineChatService::class.java)
        val diffSegments = inlineChatService.generateDiffSegments(originalText, modifiedText, baseLine)
        
        // Process diff segments to add ghost text
        diffSegments.forEach { segment ->
            // Skip header/footer segments
            if (segment.type == DiffSegmentType.HEADER || segment.type == DiffSegmentType.FOOTER) {
                return@forEach
            }
            
            val startLine = segment.startLine
            
            when (segment.type) {
                DiffSegmentType.INSERTED -> {
                    // Add new line as ghost text
                    try {
                        // Calculate the insertion point based on the line
                        val lineOffset = if (startLine < document.lineCount) {
                            document.getLineEndOffset(startLine)
                        } else {
                            document.textLength
                        }
                        
                        // Create a ghost text inlay
                        val ghostText = segment.content
                        val inlay = inlayModel.addBlockElement(
                            lineOffset,
                            true,
                            false,
                            0,
                            InlineGhostTextRenderer(ghostText, isInserted = true)
                        )
                        
                        if (inlay != null) {
                            inlays.add(inlay)
                            inlayCount.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        // Log error but continue processing
                        System.out.println("Error adding ghost text inlay at line $startLine: ${e.message}")
                    }
                }
                DiffSegmentType.DELETED -> {
                    try {
                        // For deleted lines, highlight the original line that would be removed
                        if (startLine < document.lineCount) {
                            val lineStart = document.getLineStartOffset(startLine)
                            val lineEnd = document.getLineEndOffset(startLine)
                            
                            // Add a strikethrough effect
                            val highlighter = editor.markupModel.addRangeHighlighter(
                                lineStart,
                                lineEnd,
                                HighlighterLayer.SELECTION + 1,
                                TextAttributes().apply {
                                    backgroundColor = PREVIEW_REMOVED_BG
                                    effectType = EffectType.STRIKEOUT
                                    effectColor = JBColor.RED
                                },
                                HighlighterTargetArea.EXACT_RANGE
                            )
                            
                            highlighter.errorStripeTooltip = "Would be removed"
                            previewHighlighters.add(highlighter)
                        }
                    } catch (e: Exception) {
                        System.out.println("Error highlighting deleted line $startLine: ${e.message}")
                    }
                }
                DiffSegmentType.UNCHANGED -> {
                    // No special handling needed for unchanged lines
                }
                else -> { }
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
    }
    
    /**
     * Remove the preview
     */
    fun hidePreview() {
        // Ensure this runs on EDT
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater {
                hidePreview()
            }
            return
        }
        
        try {
            // Clear all inlays
            clearGhostTextInlays()
            
            // Clear all highlighters
            clearPreviewHighlighters()
            
            // Reset state
            isActive = false
            
            // Force editor refresh
            editor.contentComponent.repaint()
            
        } catch (e: Exception) {
            System.out.println("Error hiding preview: ${e.message}")
        }
    }
    
    /**
     * Accept the preview and make the changes permanent
     */
    fun acceptPreview() {
        // Ensure this runs on EDT
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater {
                acceptPreview()
            }
            return
        }
        
        try {
            // Apply the changes to the document
            WriteCommandAction.runWriteCommandAction(project, "Apply Inline Chat Changes", null, Runnable {
                // Replace the original text with the modified text
                editor.document.replaceString(
                    originalStartOffset,
                    originalEndOffset,
                    modifiedText
                )
                
                // Clear the preview
                hidePreview()
                
                // Clear the selection
                editor.selectionModel.removeSelection()
                
                // Update the inline chat service state
                val inlineChatService = project.getService(InlineChatService::class.java)
                inlineChatService.clearState()
                
                // Force a refresh to clear diff highlights
                com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart()
            })
        } catch (e: Exception) {
            System.out.println("Error accepting preview: ${e.message}")
        }
    }
    
    /**
     * Clear all ghost text inlays
     */
    private fun clearGhostTextInlays() {
        try {
            // Clear all our tracked inlays
            val inlaysCopy = inlays.toList()
            inlaysCopy.forEach { inlay ->
                try {
                    if (inlay.isValid) {
                        inlay.dispose()
                    }
                } catch (e: Exception) {
                    // Ignore errors when removing individual inlays
                }
            }
            inlays.clear()
            inlayCount.set(0)
            
        } catch (e: Exception) {
            System.out.println("Error clearing ghost text inlays: ${e.message}")
        }
    }
    
    /**
     * Clear all preview highlighters
     */
    private fun clearPreviewHighlighters() {
        try {
            val markupModel = editor.markupModel
            
            // Clear all our tracked highlighters
            val highlightersCopy = previewHighlighters.toList()
            highlightersCopy.forEach { highlighter ->
                try {
                    if (highlighter.isValid) {
                        markupModel.removeHighlighter(highlighter)
                    }
                } catch (e: Exception) {
                    // Ignore errors when removing individual highlighters
                }
            }
            previewHighlighters.clear()
            
        } catch (e: Exception) {
            System.out.println("Error clearing preview highlighters: ${e.message}")
        }
    }
    
    /**
     * Check if preview is currently active
     */
    fun isPreviewActive(): Boolean {
        return isActive
    }
}

/**
 * Renderer for ghost text inlays
 */
class InlineGhostTextRenderer(
    private val text: String,
    private val isInserted: Boolean = true
) : EditorCustomElementRenderer {
    
    private val ghostTextColor = JBColor(Color(0, 128, 0, 180), Color(0, 180, 0, 200))
    private val ghostBgColor = JBColor(Color(240, 255, 240, 60), Color(20, 40, 20, 80))
    
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val fontMetrics = editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.CONSOLE_PLAIN))
        return fontMetrics.stringWidth(text) + 30 // Add some padding
    }
    
    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        return editor.lineHeight
    }
    
    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes
    ) {
        val editor = inlay.editor
        val colorsScheme = editor.colorsScheme
        
        // Set color based on insert/delete
        g.color = ghostTextColor
        
        // Draw a semi-transparent background
        val originalColor = g.color
        g.color = ghostBgColor
        g.fillRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height)
        g.color = originalColor
        
        // Set font style
        val baseFont = colorsScheme.getFont(EditorFontType.PLAIN)
        g.font = baseFont.deriveFont(Font.ITALIC)
        
        // Prefix for ghost text
        val prefix = if (isInserted) "+ " else "- "
        
        // Draw the text
        g.drawString(prefix + text, targetRegion.x + 5, targetRegion.y + editor.ascent)
    }
}