package com.zps.zest.completion

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.impl.FontInfo
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import com.zps.zest.completion.data.ZestInlineCompletionItem
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle
/**
 * Renders inline completion suggestions in the editor
 */
class ZestInlineCompletionRenderer {
    private val logger = Logger.getInstance(ZestInlineCompletionRenderer::class.java)
    
    data class RenderingContext(
        val id: String,
        val editor: Editor,
        val offset: Int,
        val completionItem: ZestInlineCompletionItem,
        val inlays: List<Inlay<*>>,
        val markups: List<RangeHighlighter>,
        val displayTime: Long = System.currentTimeMillis()
    ) {
        fun calcElapsedTime(): Long = System.currentTimeMillis() - displayTime
    }
    
    var current: RenderingContext? = null
        private set
    
    /**
     * Show inline completion at the specified position
     * Enhanced protection against IntelliJ action interference
     */
    fun show(
        editor: Editor,
        offset: Int,
        completion: ZestInlineCompletionItem,
        callback: (context: RenderingContext) -> Unit = {}
    ) {
        ApplicationManager.getApplication().invokeLater {
            // Hide any existing completion
            current?.let { hide() }
            
            // Verify editor state
            try {
                // Try to access editor to check if it's still valid
                val currentCaretOffset = editor.caretModel.offset
                if (currentCaretOffset != offset) {
                    System.out.println("Caret moved, canceling completion display")
                    return@invokeLater
                }
            } catch (e: Exception) {
                System.out.println("Editor disposed, canceling completion display")
                return@invokeLater
            }
            
            if (completion.insertText.isEmpty()) {
                System.out.println("Empty completion text, nothing to display")
                return@invokeLater
            }
            
            System.out.println("Showing completion at offset $offset: '${completion.insertText.take(50)}'")
            
            val id = "zest-completion-${System.currentTimeMillis()}"
            val inlays = mutableListOf<Inlay<*>>()
            val markups = mutableListOf<RangeHighlighter>()
            
            try {
                renderCompletion(editor, offset, completion, inlays, markups)
                
                val context = RenderingContext(id, editor, offset, completion, inlays, markups)
                current = context
                callback(context)
                
                System.out.println("Successfully displayed completion with ${inlays.size} inlays and ${markups.size} markups")
                
                // Schedule a check to ensure our completion is still visible
                // This helps detect when IntelliJ actions have interfered
                scheduleVisibilityCheck(context)
                
            } catch (e: Exception) {
                logger.warn("Failed to render completion", e)
                // Clean up any partial rendering
                inlays.forEach { Disposer.dispose(it) }
                markups.forEach { editor.markupModel.removeHighlighter(it) }
            }
        }
    }
    
    /**
     * Schedule a check to ensure completion visibility is maintained
     */
    private fun scheduleVisibilityCheck(context: RenderingContext) {
        // SIMPLIFIED: Just check once, don't try to restore automatically
        // This prevents the blinking caused by show/hide cycles
        ApplicationManager.getApplication().invokeLater({
            if (current == context) {
                // Check if any of our inlays have been disposed unexpectedly
                val disposedInlays = context.inlays.count { inlay ->
                    try {
                        !inlay.isValid
                    } catch (e: Exception) {
                        true // Assume disposed if we can't check
                    }
                }
                if (disposedInlays > 0) {
                    System.out.println("Detected $disposedInlays disposed inlays - clearing completion to prevent blinking")
                    // DON'T try to restore - just clear to prevent blinking
                    current = null
                }
            }
        }, ModalityState.defaultModalityState())
    }
    
    /**
     * Hide the current completion
     */
    fun hide() {
        current?.let { context ->
            ApplicationManager.getApplication().invokeLater {
                try {
                    context.inlays.forEach { Disposer.dispose(it) }
                    context.markups.forEach { context.editor.markupModel.removeHighlighter(it) }
                    System.out.println("Hidden completion: ${context.id}")
                } catch (e: Exception) {
                    logger.warn("Error hiding completion", e)
                }
            }
            current = null
        }
    }
    
    private fun renderCompletion(
        editor: Editor,
        offset: Int,
        completion: ZestInlineCompletionItem,
        inlays: MutableList<Inlay<*>>,
        markups: MutableList<RangeHighlighter>
    ) {
        val insertText = completion.insertText
        val replaceRange = completion.replaceRange
        
        // Calculate what we're replacing vs inserting
        val replaceLength = replaceRange.end - replaceRange.start
        
        if (replaceLength > 0) {
            // We need to replace some existing text
            val replaceAttributes = createReplaceTextAttributes()
            val highlighter = editor.markupModel.addRangeHighlighter(
                replaceRange.start,
                replaceRange.end,
                HighlighterLayer.LAST + 100,
                replaceAttributes,
                HighlighterTargetArea.EXACT_RANGE
            )
            markups.add(highlighter)
        }
        
        // Only show the first line (since parser now returns first line only)
        // Split just in case there are still multiple lines for robustness
        val lines = insertText.lines()
        val firstLine = lines.firstOrNull() ?: return
        
        if (firstLine.isNotEmpty()) {
            val inlay = createInlineInlay(editor, offset, firstLine)
            if (inlay != null) {
                inlays.add(inlay)
            }
        }
        
        // Remove the multi-line block rendering logic - we only show first line now
    }
    
    private fun createInlineInlay(editor: Editor, offset: Int, text: String): Inlay<*>? {
        if (text.isEmpty()) return null
        
        val renderer = object : EditorCustomElementRenderer {
            override fun calcWidthInPixels(inlay: Inlay<*>): Int {
                return maxOf(calculateTextWidth(inlay.editor, text), 1)
            }
            
            override fun paint(inlay: Inlay<*>, g: Graphics, targetRect: Rectangle, textAttributes: TextAttributes) {
                g.font = getCompletionFont(inlay.editor)
                g.color = getCompletionColor()
                g.drawString(text, targetRect.x, targetRect.y + inlay.editor.ascent)
            }
            
            override fun getContextMenuGroupId(inlay: Inlay<*>): String {
                return "Zest.InlineCompletionContextMenu"
            }
        }
        
        return editor.inlayModel.addInlineElement(offset, true, renderer)
    }
    
    private fun createReplaceTextAttributes(): TextAttributes {
        return TextAttributes().apply {
            backgroundColor = JBColor.YELLOW.darker()
            foregroundColor = JBColor.BLACK
        }
    }
    
    private fun getCompletionFont(editor: Editor): Font {
        return editor.colorsScheme.getFont(EditorFontType.ITALIC).let { font ->
            UIUtil.getFontWithFallbackIfNeeded(font, "sample").deriveFont(
                editor.colorsScheme.editorFontSize
            )
        }
    }
    
    private fun getCompletionColor(): JBColor {
        return JBColor.GRAY
    }
    
    private fun calculateTextWidth(editor: Editor, text: String): Int {
        val font = getCompletionFont(editor)
        val metrics = FontInfo.getFontMetrics(font, FontInfo.getFontRenderContext(editor.contentComponent))
        return metrics.stringWidth(text)
    }
}
