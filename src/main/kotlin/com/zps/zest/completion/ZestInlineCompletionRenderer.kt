package com.zps.zest.completion

import com.intellij.openapi.application.invokeLater
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
import com.intellij.openapi.util.TextRange
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
     */
    fun show(
        editor: Editor,
        offset: Int,
        completion: ZestInlineCompletionItem,
        callback: (context: RenderingContext) -> Unit = {}
    ) {
        invokeLater {
            // Hide any existing completion
            current?.let { hide() }
            
            // Verify editor state
            if (editor.caretModel.offset != offset) {
                logger.debug("Editor caret moved, canceling completion display")
                return@invokeLater
            }
            
            if (completion.insertText.isEmpty()) {
                logger.debug("Empty completion text, nothing to display")
                return@invokeLater
            }
            
            logger.debug("Showing completion at offset $offset: '${completion.insertText.take(50)}'")
            
            val id = "zest-completion-${System.currentTimeMillis()}"
            val inlays = mutableListOf<Inlay<*>>()
            val markups = mutableListOf<RangeHighlighter>()
            
            try {
                renderCompletion(editor, offset, completion, inlays, markups)
                
                val context = RenderingContext(id, editor, offset, completion, inlays, markups)
                current = context
                callback(context)
                
                logger.debug("Successfully displayed completion with ${inlays.size} inlays and ${markups.size} markups")
                
            } catch (e: Exception) {
                logger.warn("Failed to render completion", e)
                // Clean up any partial rendering
                inlays.forEach { Disposer.dispose(it) }
                markups.forEach { editor.markupModel.removeHighlighter(it) }
            }
        }
    }
    
    /**
     * Hide the current completion
     */
    fun hide() {
        current?.let { context ->
            invokeLater {
                try {
                    context.inlays.forEach { Disposer.dispose(it) }
                    context.markups.forEach { context.editor.markupModel.removeHighlighter(it) }
                    logger.debug("Hidden completion: ${context.id}")
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
        val document = editor.document
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
        
        // Split completion text into lines
        val lines = insertText.lines()
        
        if (lines.size == 1) {
            // Single line completion
            val inlay = createInlineInlay(editor, offset, lines[0])
            if (inlay != null) {
                inlays.add(inlay)
            }
        } else {
            // Multi-line completion
            // First line goes inline at cursor
            if (lines[0].isNotEmpty()) {
                val inlay = createInlineInlay(editor, offset, lines[0])
                if (inlay != null) {
                    inlays.add(inlay)
                }
            }
            
            // Subsequent lines go as block elements
            lines.drop(1).forEachIndexed { index, line ->
                val blockInlay = createBlockInlay(editor, offset, line, index + 1)
                if (blockInlay != null) {
                    inlays.add(blockInlay)
                }
            }
        }
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
    
    private fun createBlockInlay(editor: Editor, offset: Int, text: String, lineOffset: Int): Inlay<*>? {
        val renderer = object : EditorCustomElementRenderer {
            override fun calcWidthInPixels(inlay: Inlay<*>): Int {
                return maxOf(calculateTextWidth(inlay.editor, text), 1)
            }
            
            override fun paint(inlay: Inlay<*>, g: Graphics, targetRect: Rectangle, textAttributes: TextAttributes) {
                g.font = getCompletionFont(inlay.editor)
                g.color = getCompletionColor()
                g.drawString(text, targetRect.x, targetRect.y + inlay.editor.ascent)
            }
        }
        
        return editor.inlayModel.addBlockElement(offset, true, false, -lineOffset, renderer)
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
