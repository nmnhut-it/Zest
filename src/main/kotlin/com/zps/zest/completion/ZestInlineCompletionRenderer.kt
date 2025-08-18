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
import com.zps.zest.completion.metrics.ZestInlineCompletionMetricsService
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle
/**
 * Renders inline completion suggestions in the editor
 */
class ZestInlineCompletionRenderer {
    private val logger = Logger.getInstance(ZestInlineCompletionRenderer::class.java)
    
    // Debug logging flag
    private var debugLoggingEnabled = true
    
    /**
     * Internal debug logging function
     * @param message The message to log
     * @param tag Optional tag for categorizing logs (default: "ZestRenderer")
     */
    private fun log(message: String, tag: String = "ZestRenderer") {
        if (debugLoggingEnabled) {
            println("[$tag] $message")
        }
    }
    
    /**
     * Enable or disable debug logging
     */
    fun setDebugLogging(enabled: Boolean) {
        debugLoggingEnabled = enabled
        log("Debug logging ${if (enabled) "enabled" else "disabled"}")
    }
    
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
     * Strategy-aware: shows multi-line for LEAN, single-line for SIMPLE
     */
    fun show(
        editor: Editor,
        offset: Int,
        completion: ZestInlineCompletionItem,
        strategy: ZestCompletionProvider.CompletionStrategy = ZestCompletionProvider.CompletionStrategy.SIMPLE,
        callback: (context: RenderingContext) -> Unit = {},
        errorCallback: (reason: String) -> Unit = {}
    ) {
        val renderStartTime = System.currentTimeMillis()
        
        ApplicationManager.getApplication().invokeLater {
            // Always hide any existing completion first to prevent duplicates
            hide()
            
            // Verify editor state
            try {
                // Try to access editor to check if it's still valid
                val currentCaretOffset = editor.caretModel.offset
                if (currentCaretOffset != offset) {
                    log("Caret moved, canceling completion display")
                    errorCallback("caret_moved")
                    return@invokeLater
                }
            } catch (e: Exception) {
                log("Editor disposed, canceling completion display")
                errorCallback("editor_disposed")
                return@invokeLater
            }
            
            if (completion.insertText.isEmpty()) {
                log("Empty completion text, nothing to display")
                errorCallback("empty_completion")
                return@invokeLater
            }
            
            log("Showing completion at offset $offset: '${completion.insertText.take(50)}'", tag = "ZestRenderer")
            
            val id = "zest-completion-${System.currentTimeMillis()}"
            val inlays = mutableListOf<Inlay<*>>()
            val markups = mutableListOf<RangeHighlighter>()
            
            try {
                renderCompletion(editor, offset, completion, inlays, markups, strategy)
                
                val context = RenderingContext(id, editor, offset, completion, inlays, markups)
                current = context
                callback(context)
                
                val renderTime = System.currentTimeMillis() - renderStartTime
//                log("Successfully displayed completion with ${inlays.size} inlays and ${markups.size} markups in ${renderTime}ms")
                
                // Track inlay rendering time if we have a completion ID
                completion.metadata?.requestId?.let { requestId ->
                    try {
                        editor.project?.let { project ->
                            val metricsService = ZestInlineCompletionMetricsService.getInstance(project)
                            metricsService.trackInlayRenderingTime(requestId, renderTime)
                        }
                    } catch (e: Exception) {
                        // Ignore metrics tracking errors
                    }
                }
                
                // Schedule a check to ensure our completion is still visible
                // This helps detect when IntelliJ actions have interfered
                scheduleVisibilityCheck(context)
                
            } catch (e: Exception) {
                logger.warn("Failed to render completion", e)
                // Clean up any partial rendering
                inlays.forEach { 
                    try {
                        if (it.isValid) Disposer.dispose(it)
                    } catch (ex: Exception) {
                        // Ignore disposal errors
                    }
                }
                markups.forEach { 
                    try {
                        editor.markupModel.removeHighlighter(it)
                    } catch (ex: Exception) {
                        // Ignore removal errors
                    }
                }
                current = null
                errorCallback("render_failed")
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
                    log("Detected $disposedInlays disposed inlays - clearing completion to prevent blinking")
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
        val context = current
        current = null // Clear current reference immediately to prevent re-entry
        
        context?.let { 
            ApplicationManager.getApplication().invokeLater {
                try {
                    // Dispose all inlays with error handling
                    context.inlays.forEach { inlay ->
                        try {
                            if (inlay.isValid) {
                                Disposer.dispose(inlay)
                            }
                        } catch (e: Exception) {
                            // Continue disposing other inlays even if one fails
                            logger.debug("Error disposing inlay", e)
                        }
                    }
                    
                    // Remove all markups with error handling
                    context.markups.forEach { markup ->
                        try {
                            context.editor.markupModel.removeHighlighter(markup)
                        } catch (e: Exception) {
                            // Continue removing other markups even if one fails
                            logger.debug("Error removing markup", e)
                        }
                    }
                    
                    log("Hidden completion: ${context.id}")
                } catch (e: Exception) {
                    logger.warn("Error hiding completion", e)
                }
            }
        }
    }
    
    /**
     * Check if there's an active completion rendering
     */
    fun isActive(): Boolean {
        return current != null
    }
    
    private fun renderCompletion(
        editor: Editor,
        offset: Int,
        completion: ZestInlineCompletionItem,
        inlays: MutableList<Inlay<*>>,
        markups: MutableList<RangeHighlighter>,
        strategy: ZestCompletionProvider.CompletionStrategy
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
        
        // Strategy-aware rendering
        when (strategy) {
            ZestCompletionProvider.CompletionStrategy.SIMPLE -> {
                // SIMPLE: Only show the first line for clean inline display (traditional full acceptance)
                val lines = insertText.lines()
                val firstLine = lines.firstOrNull() ?: return
                
                if (firstLine.isNotEmpty()) {
                    val inlay = createInlineInlay(editor, offset, firstLine)
                    if (inlay != null) {
                        inlays.add(inlay)
                        log("SIMPLE strategy: rendered single line: '$firstLine' (traditional full acceptance)")
                    }
                }
            }
            
            ZestCompletionProvider.CompletionStrategy.LEAN -> {
                // LEAN: Show full multi-line completion (line-by-line acceptance handled in service)
                val lines = insertText.lines()
                
                if (lines.isNotEmpty()) {
                    // First line goes inline at cursor
                    val firstLine = lines[0]
                    if (firstLine.isNotEmpty()) {
                        val inlay = createInlineInlay(editor, offset, firstLine)
                        if (inlay != null) {
                            inlays.add(inlay)
                        }
                    }
                    
                    // Subsequent lines go as block elements below
                    lines.drop(1).forEachIndexed { index, line ->
                        if (line.isNotEmpty() || index == 0) { // Show empty lines only if they're the first additional line
                            val blockInlay = createBlockInlay(editor, offset, line, index + 1)
                            if (blockInlay != null) {
                                inlays.add(blockInlay)
                            }
                        }
                    }
                    
                    log("LEAN strategy: rendered ${lines.size} lines (${inlays.size} inlays) with line-by-line acceptance")
                }
            }
            
            ZestCompletionProvider.CompletionStrategy.METHOD_REWRITE -> {
                // METHOD_REWRITE: Not used for inline rendering (uses floating windows)
                log("METHOD_REWRITE strategy: skipping inline rendering (uses floating windows)")
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
            
            override fun getContextMenuGroupId(inlay: Inlay<*>): String {
                return "Zest.InlineCompletionContextMenu"
            }
        }
        
        return editor.inlayModel.addBlockElement(offset, true, false, -lineOffset, renderer)
    }
    
    private fun getCompletionColor(): JBColor {
        // Use a more visible color that adapts to the theme
        // Light theme: darker gray, Dark theme: lighter gray
        return JBColor(
            Color(128, 128, 128, 180), // Light theme: semi-transparent gray
            Color(160, 160, 160, 180)  // Dark theme: lighter semi-transparent gray
        )
    }
    
    private fun calculateTextWidth(editor: Editor, text: String): Int {
        val font = getCompletionFont(editor)
        val metrics = FontInfo.getFontMetrics(font, FontInfo.getFontRenderContext(editor.contentComponent))
        return metrics.stringWidth(text)
    }
}
