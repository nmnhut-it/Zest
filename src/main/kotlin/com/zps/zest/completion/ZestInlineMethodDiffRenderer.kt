package com.zps.zest.completion

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import com.zps.zest.completion.context.ZestMethodContextCollector
import com.zps.zest.completion.diff.WordDiffUtil
import com.zps.zest.gdiff.GDiff
import java.awt.AlphaComposite
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Renders method diff inline in the editor using GDiff results
 * Handles proper threading, cleanup, and integration with tab acceptance
 */
class ZestInlineMethodDiffRenderer {
    private val logger = Logger.getInstance(ZestInlineMethodDiffRenderer::class.java)
    
    // State management
    private var currentRenderingContext: RenderingContext? = null
    private val isActive = AtomicBoolean(false)
    
    /**
     * Container for all rendering elements that need cleanup
     */
    data class RenderingContext(
        val editor: Editor,
        val methodContext: ZestMethodContextCollector.MethodContext,
        val diffResult: GDiff.DiffResult,
        val rewrittenMethod: String,
        val deletionHighlighters: MutableList<RangeHighlighter> = mutableListOf(),
        val additionInlays: MutableList<Inlay<*>> = mutableListOf(),
        val acceptCallback: () -> Unit,
        val rejectCallback: () -> Unit
    ) {
        fun dispose() {
            // Remove all highlighters
            deletionHighlighters.forEach { highlighter ->
                try {
                    editor.markupModel.removeHighlighter(highlighter)
                } catch (e: Exception) {
                    // Highlighter might already be disposed
                }
            }
            deletionHighlighters.clear()
            
            // Dispose all inlays
            additionInlays.forEach { inlay ->
                try {
                    Disposer.dispose(inlay)
                } catch (e: Exception) {
                    // Inlay might already be disposed
                }
            }
            additionInlays.clear()
        }
    }
    
    /**
     * Show inline diff for method rewrite
     * Must be called on EDT
     */
    fun show(
        editor: Editor,
        methodContext: ZestMethodContextCollector.MethodContext,
        diffResult: GDiff.DiffResult,
        rewrittenMethod: String,
        onAccept: () -> Unit,
        onReject: () -> Unit
    ) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        
        if (!isActive.compareAndSet(false, true)) {
            logger.warn("Diff renderer already active, hiding previous")
            hide()
        }
        
        try {
            logger.info("Showing inline method diff for ${methodContext.methodName}")
            
            // Verify editor is still valid
            if (editor.isDisposed) {
                logger.warn("Editor is disposed, cannot show diff")
                return
            }
            
            val context = RenderingContext(
                editor = editor,
                methodContext = methodContext,
                diffResult = diffResult,
                rewrittenMethod = rewrittenMethod,
                acceptCallback = onAccept,
                rejectCallback = onReject
            )
            
            currentRenderingContext = context
            
            // Render the diff
            renderDiffChanges(context)
            
            logger.info("Inline diff rendered successfully with ${context.deletionHighlighters.size} deletions and ${context.additionInlays.size} additions")
            
        } catch (e: Exception) {
            logger.error("Failed to show inline diff", e)
            hide()
        }
    }
    
    /**
     * Hide the current diff rendering
     * Thread-safe - can be called from any thread
     */
    fun hide() {
        if (!isActive.compareAndSet(true, false)) {
            return // Already hidden
        }
        
        ApplicationManager.getApplication().invokeLater {
            try {
                currentRenderingContext?.dispose()
                currentRenderingContext = null
                logger.debug("Inline diff hidden and cleaned up")
            } catch (e: Exception) {
                logger.warn("Error during diff cleanup", e)
            }
        }
    }
    
    /**
     * Accept the current method rewrite
     * Thread-safe - can be called from any thread
     */
    fun acceptChanges() {
        val context = currentRenderingContext ?: return
        
        ApplicationManager.getApplication().invokeLater {
            try {
                logger.info("Accepting method diff changes")
                context.acceptCallback()
                hide()
            } catch (e: Exception) {
                logger.error("Error accepting changes", e)
                hide()
            }
        }
    }
    
    /**
     * Reject the current method rewrite
     * Thread-safe - can be called from any thread  
     */
    fun rejectChanges() {
        val context = currentRenderingContext ?: return
        
        ApplicationManager.getApplication().invokeLater {
            try {
                logger.info("Rejecting method diff changes")
                context.rejectCallback()
                hide()
            } catch (e: Exception) {
                logger.error("Error rejecting changes", e)
                hide()
            }
        }
    }
    
    /**
     * Check if diff is currently active
     */
    fun isActive(): Boolean = isActive.get()
    
    /**
     * Get current method context if active
     */
    fun getCurrentMethodContext(): ZestMethodContextCollector.MethodContext? {
        return currentRenderingContext?.methodContext
    }
    
    /**
     * Check if cursor is within the current diff area
     */
    fun isCursorInDiffArea(editor: Editor, offset: Int): Boolean {
        val context = currentRenderingContext ?: return false
        val methodContext = context.methodContext
        
        return offset >= methodContext.methodStartOffset && 
               offset <= methodContext.methodEndOffset
    }
    
    /**
     * Render diff changes using GDiff results
     */
    private fun renderDiffChanges(context: RenderingContext) {
        val document = context.editor.document
        val originalLines = context.methodContext.methodContent.lines()
        val rewrittenLines = context.rewrittenMethod.lines()
        
        // Simple line-by-line diff rendering
        // For more sophisticated rendering, we'd parse the GDiff.DiffResult
        val maxLines = maxOf(originalLines.size, rewrittenLines.size)
        var methodLineOffset = 0
        
        for (i in 0 until maxLines) {
            val originalLine = originalLines.getOrNull(i)
            val rewrittenLine = rewrittenLines.getOrNull(i)
            
            when {
                originalLine != null && rewrittenLine != null && originalLine != rewrittenLine -> {
                    // Modified line - show side-by-side with word diff
                    renderLineModification(context, i, originalLine, rewrittenLine, methodLineOffset)
                }
                originalLine != null && rewrittenLine == null -> {
                    // Deleted line
                    renderLineDeletion(context, i, originalLine, methodLineOffset)
                }
                originalLine == null && rewrittenLine != null -> {
                    // Added line
                    renderLineAddition(context, i, rewrittenLine, methodLineOffset)
                }
                // Unchanged lines don't need special rendering
            }
            
            if (originalLine != null) methodLineOffset++
        }
    }
    
    /**
     * Render a line modification with side-by-side word diff
     */
    private fun renderLineModification(
        context: RenderingContext, 
        lineIndex: Int, 
        originalLine: String, 
        rewrittenLine: String,
        methodLineOffset: Int
    ) {
        val document = context.editor.document
        val methodStartLine = document.getLineNumber(context.methodContext.methodStartOffset)
        val actualLineNumber = methodStartLine + methodLineOffset
        
        if (actualLineNumber >= 0 && actualLineNumber < document.lineCount) {
            val lineStartOffset = document.getLineStartOffset(actualLineNumber)
            val lineEndOffset = document.getLineEndOffset(actualLineNumber)
            
            // Hide the original line (we'll show it in the inlay)
            val hideHighlighter = context.editor.markupModel.addRangeHighlighter(
                lineStartOffset,
                lineEndOffset,
                HighlighterLayer.LAST + 100,
                TextAttributes().apply {
                    foregroundColor = context.editor.colorsScheme.defaultBackground
                    backgroundColor = context.editor.colorsScheme.defaultBackground
                },
                HighlighterTargetArea.EXACT_RANGE
            )
            context.deletionHighlighters.add(hideHighlighter)
            
            // Create side-by-side diff inlay with ghost text for new content
            val renderer = createSideBySideDiffRenderer(
                originalLine, 
                rewrittenLine, 
                context.editor.colorsScheme,
                context.methodContext.language
            )
            val inlay = context.editor.inlayModel.addBlockElement(
                lineEndOffset,
                true,
                false,
                0,
                renderer
            )
            
            if (inlay != null) {
                context.additionInlays.add(inlay)
            }
        }
    }
    
    /**
     * Render line deletion with strike-through and red background
     */
    private fun renderLineDeletion(
        context: RenderingContext, 
        lineIndex: Int, 
        originalLine: String,
        methodLineOffset: Int
    ) {
        val document = context.editor.document
        val methodStartLine = document.getLineNumber(context.methodContext.methodStartOffset)
        val actualLineNumber = methodStartLine + methodLineOffset
        
        if (actualLineNumber >= 0 && actualLineNumber < document.lineCount) {
            val lineStartOffset = document.getLineStartOffset(actualLineNumber)
            val lineEndOffset = document.getLineEndOffset(actualLineNumber)
            
            // Create red strike-through highlighting with theme-aware colors
            val highlighter = context.editor.markupModel.addRangeHighlighter(
                lineStartOffset,
                lineEndOffset,
                HighlighterLayer.LAST + 100,
                createDeletionTextAttributes(context.editor.colorsScheme),
                HighlighterTargetArea.EXACT_RANGE
            )
            
            context.deletionHighlighters.add(highlighter)
        }
    }
    
    /**
     * Render line addition with ghost text style
     */
    private fun renderLineAddition(
        context: RenderingContext, 
        lineIndex: Int, 
        rewrittenLine: String,
        methodLineOffset: Int
    ) {
        val document = context.editor.document
        val methodStartLine = document.getLineNumber(context.methodContext.methodStartOffset)
        val actualLineNumber = methodStartLine + methodLineOffset
        
        if (actualLineNumber >= 0 && actualLineNumber <= document.lineCount) {
            val insertOffset = if (actualLineNumber < document.lineCount) {
                document.getLineStartOffset(actualLineNumber)
            } else {
                document.textLength
            }
            
            // Create ghost text addition inlay
            val renderer = createGhostTextRenderer(rewrittenLine, context.editor.colorsScheme)
            val inlay = context.editor.inlayModel.addBlockElement(
                insertOffset,
                true,
                false,
                0,
                renderer
            )
            
            if (inlay != null) {
                context.additionInlays.add(inlay)
            }
        }
    }
    
    /**
     * Create text attributes for deletions with theme-aware colors
     */
    private fun createDeletionTextAttributes(scheme: EditorColorsScheme): TextAttributes {
        return TextAttributes().apply {
            backgroundColor = JBColor(
                // Light theme: subtle red background
                java.awt.Color(255, 220, 220),
                // Dark theme: subtle red background
                java.awt.Color(92, 22, 36)
            )
            foregroundColor = scheme.defaultForeground // Use theme's default text color
            effectType = EffectType.STRIKEOUT
            effectColor = JBColor(
                java.awt.Color(255, 85, 85),
                java.awt.Color(248, 81, 73)
            )
        }
    }
    
    /**
     * Create renderer for side-by-side diff with word highlighting and ghost text
     */
    private fun createSideBySideDiffRenderer(
        originalLine: String, 
        modifiedLine: String,
        scheme: EditorColorsScheme,
        language: String
    ): EditorCustomElementRenderer {
        // Perform word-level diff with language-specific normalization
        val wordDiff = WordDiffUtil.diffWords(originalLine, modifiedLine, language)
        
        return object : EditorCustomElementRenderer {
            override fun calcWidthInPixels(inlay: Inlay<*>): Int {
                val font = getEditorFont(inlay.editor)
                val metrics = inlay.editor.contentComponent.getFontMetrics(font)
                
                // Calculate width for both lines plus arrow
                val originalWidth = metrics.stringWidth(originalLine)
                val modifiedWidth = metrics.stringWidth(modifiedLine)
                val arrowWidth = metrics.stringWidth(" → ")
                
                return originalWidth + arrowWidth + modifiedWidth + 20 // Add padding
            }
            
            override fun calcHeightInPixels(inlay: Inlay<*>): Int {
                val font = getEditorFont(inlay.editor)
                val metrics = inlay.editor.contentComponent.getFontMetrics(font)
                return metrics.height + 4 // Add padding
            }
            
            override fun paint(inlay: Inlay<*>, g: Graphics, targetRect: Rectangle, textAttributes: TextAttributes) {
                val g2d = g as Graphics2D
                val font = getEditorFont(inlay.editor)
                g2d.font = font
                val metrics = g2d.fontMetrics
                
                // Use theme background
                g2d.color = scheme.defaultBackground
                g2d.fillRect(targetRect.x, targetRect.y, targetRect.width, targetRect.height)
                
                var xPos = targetRect.x + 5
                val yPos = targetRect.y + metrics.ascent + 2
                
                // Draw original segments with highlighting
                val originalSegments = WordDiffUtil.mergeSegments(wordDiff.originalSegments)
                for (segment in originalSegments) {
                    when (segment.type) {
                        WordDiffUtil.ChangeType.DELETED, WordDiffUtil.ChangeType.MODIFIED -> {
                            // Draw background highlight
                            val segmentWidth = metrics.stringWidth(segment.text)
                            g2d.color = JBColor(
                                java.awt.Color(255, 220, 220),
                                java.awt.Color(92, 22, 36)
                            )
                            g2d.fillRect(xPos, targetRect.y, segmentWidth, targetRect.height)
                            
                            // Draw text with strikethrough
                            g2d.color = scheme.defaultForeground
                            g2d.drawString(segment.text, xPos, yPos)
                            
                            // Draw strikethrough
                            g2d.color = JBColor(
                                java.awt.Color(255, 85, 85),
                                java.awt.Color(248, 81, 73)
                            )
                            val strikeY = yPos - metrics.height / 3
                            g2d.drawLine(xPos, strikeY, xPos + segmentWidth, strikeY)
                            
                            xPos += segmentWidth
                        }
                        else -> {
                            // Draw unchanged text
                            g2d.color = scheme.defaultForeground
                            g2d.drawString(segment.text, xPos, yPos)
                            xPos += metrics.stringWidth(segment.text)
                        }
                    }
                }
                
                // Draw arrow
                g2d.color = scheme.defaultForeground
                g2d.drawString(" → ", xPos, yPos)
                xPos += metrics.stringWidth(" → ")
                
                // Draw modified segments as ghost text
                val modifiedSegments = WordDiffUtil.mergeSegments(wordDiff.modifiedSegments)
                
                // Set up ghost text rendering
                val originalComposite = g2d.composite
                val ghostAlpha = if (JBColor.isBright()) 0.6f else 0.7f
                
                for (segment in modifiedSegments) {
                    when (segment.type) {
                        WordDiffUtil.ChangeType.ADDED, WordDiffUtil.ChangeType.MODIFIED -> {
                            // Draw as ghost text (semi-transparent)
                            g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, ghostAlpha)
                            g2d.color = getGhostTextColor(scheme)
                            g2d.drawString(segment.text, xPos, yPos)
                            g2d.composite = originalComposite
                            xPos += metrics.stringWidth(segment.text)
                        }
                        else -> {
                            // Draw unchanged text as ghost too for consistency
                            g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, ghostAlpha)
                            g2d.color = scheme.defaultForeground
                            g2d.drawString(segment.text, xPos, yPos)
                            g2d.composite = originalComposite
                            xPos += metrics.stringWidth(segment.text)
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Create renderer for pure addition lines with ghost text style
     */
    private fun createGhostTextRenderer(text: String, scheme: EditorColorsScheme): EditorCustomElementRenderer {
        return object : EditorCustomElementRenderer {
            override fun calcWidthInPixels(inlay: Inlay<*>): Int {
                val font = getEditorFont(inlay.editor)
                val metrics = inlay.editor.contentComponent.getFontMetrics(font)
                return metrics.stringWidth("+ $text") + 10 // Add padding
            }
            
            override fun calcHeightInPixels(inlay: Inlay<*>): Int {
                val font = getEditorFont(inlay.editor)
                val metrics = inlay.editor.contentComponent.getFontMetrics(font)
                return metrics.height + 4 // Add padding
            }
            
            override fun paint(inlay: Inlay<*>, g: Graphics, targetRect: Rectangle, textAttributes: TextAttributes) {
                val g2d = g as Graphics2D
                val font = getEditorFont(inlay.editor)
                g2d.font = font
                
                // Clear background
                g2d.color = scheme.defaultBackground
                g2d.fillRect(targetRect.x, targetRect.y, targetRect.width, targetRect.height)
                
                // Draw ghost text with transparency
                val originalComposite = g2d.composite
                val ghostAlpha = if (JBColor.isBright()) 0.6f else 0.7f
                g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, ghostAlpha)
                
                g2d.color = getGhostTextColor(scheme)
                val metrics = g2d.fontMetrics
                g2d.drawString("+ $text", targetRect.x + 5, targetRect.y + metrics.ascent + 2)
                
                // Restore composite
                g2d.composite = originalComposite
            }
        }
    }
    
    /**
     * Get appropriate ghost text color based on theme
     */
    private fun getGhostTextColor(scheme: EditorColorsScheme): java.awt.Color {
        return if (JBColor.isBright()) {
            // Light theme: dark gray ghost text
            java.awt.Color(60, 60, 60)
        } else {
            // Dark theme: light gray ghost text
            java.awt.Color(180, 180, 180)
        }
    }
    
    /**
     * Get editor font for consistent rendering
     */
    private fun getEditorFont(editor: Editor): Font {
        val scheme = EditorColorsManager.getInstance().globalScheme
        return UIUtil.getFontWithFallbackIfNeeded(
            scheme.getFont(EditorFontType.PLAIN),
            "sample"
        )
    }
}
