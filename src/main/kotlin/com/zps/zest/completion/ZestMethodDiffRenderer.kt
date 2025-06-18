package com.zps.zest.completion

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import com.zps.zest.completion.context.ZestMethodContextCollector
import com.zps.zest.completion.diff.DiffRenderingConfig
import com.zps.zest.completion.diff.SideBySideMethodDiffRenderer
import com.zps.zest.completion.diff.WholeMethodDiffRenderer
import com.zps.zest.completion.diff.WordDiffUtil
import com.zps.zest.gdiff.GDiff
import java.awt.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * New simplified method diff renderer with cleaner UX:
 * 1. Select method (highlight it)
 * 2. Hide original method and show processing indicator
 * 3. Replace with prominent diff display as main content
 * 4. User reviews diff in-place where method was
 * 5. Clean transitions between states
 */
class ZestMethodDiffRenderer {
    private val logger = Logger.getInstance(ZestMethodDiffRenderer::class.java)
    
    companion object {
        private const val DEBUG = true
        
        private fun debugLog(message: String) {
            if (DEBUG) {
                println("[ZestMethodDiff] $message")
            }
        }
    }
    
    // State management
    private var currentContext: RenderingContext? = null
    private val isActive = AtomicBoolean(false)
    
    /**
     * Rendering states for progress indication
     */
    enum class State {
        SELECTING,      // Method is being selected
        PROCESSING,     // AI is working on rewrite
        REVIEWING,      // User is reviewing diff
        COMPLETED       // Applied or cancelled
    }
    
    /**
     * Simplified rendering context
     */
    data class RenderingContext(
        val editor: Editor,
        val methodContext: ZestMethodContextCollector.MethodContext,
        var state: State = State.SELECTING,
        var selectionHighlighter: RangeHighlighter? = null,
        var hideHighlighter: RangeHighlighter? = null,
        var statusInlay: Inlay<*>? = null,
        var diffInlay: Inlay<*>? = null,
        val acceptCallback: () -> Unit,
        val rejectCallback: () -> Unit
    ) {
        fun dispose() {
            // Remove selection highlight
            selectionHighlighter?.let { highlighter ->
                try {
                    editor.markupModel.removeHighlighter(highlighter)
                } catch (e: Exception) {
                    // Already disposed
                }
            }
            
            // Remove hide highlighter
            hideHighlighter?.let { highlighter ->
                try {
                    editor.markupModel.removeHighlighter(highlighter)
                } catch (e: Exception) {
                    // Already disposed
                }
            }
            
            // Dispose inlays
            listOfNotNull(statusInlay, diffInlay).forEach { inlay ->
                try {
                    if (inlay.isValid) {
                        Disposer.dispose(inlay)
                    }
                } catch (e: Exception) {
                    // Already disposed
                }
            }
            
            selectionHighlighter = null
            hideHighlighter = null
            statusInlay = null
            diffInlay = null
        }
    }
    
    /**
     * Start the method rewrite flow
     * Step 1: Select and highlight the method
     */
    fun startMethodRewrite(
        editor: Editor,
        methodContext: ZestMethodContextCollector.MethodContext,
        onAccept: () -> Unit,
        onReject: () -> Unit
    ) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        
        if (!isActive.compareAndSet(false, true)) {
            logger.warn("Method diff already active, hiding previous")
            hide()
        }
        
        try {
            debugLog("Starting method rewrite for ${methodContext.methodName}")
            
            if (editor.isDisposed) {
                logger.warn("Editor is disposed, cannot start method rewrite")
                return
            }
            
            val context = RenderingContext(
                editor = editor,
                methodContext = methodContext,
                acceptCallback = onAccept,
                rejectCallback = onReject
            )
            
            currentContext = context
            
            // Step 1: Select the method
            selectMethod(context)
            
        } catch (e: Exception) {
            logger.error("Failed to start method rewrite", e)
            hide()
        }
    }
    
    /**
     * Step 2: Show processing state
     */
    fun showProcessing() {
        val context = currentContext ?: return
        
        ApplicationManager.getApplication().invokeLater {
            try {
                debugLog("Showing processing state")
                context.state = State.PROCESSING
                
                // Hide the original method content
                hideMethodContent(context)
                
                // Show processing indicator in place
                showStatusIndicator(context, "🔄 AI rewriting method...")
                
            } catch (e: Exception) {
                logger.error("Failed to show processing state", e)
            }
        }
    }
    
    /**
     * Step 3: Show diff for review
     */
    fun showDiff(
        diffResult: GDiff.DiffResult,
        rewrittenMethod: String
    ) {
        val context = currentContext ?: return
        
        ApplicationManager.getApplication().invokeLater {
            try {
                debugLog("Showing diff for review")
                context.state = State.REVIEWING
                
                // Update status
                showStatusIndicator(context, "📝 Review changes (Tab to accept, Esc to reject)")
                
                // Show the diff in place of collapsed method
                showInPlaceDiff(context, diffResult, rewrittenMethod)
                
            } catch (e: Exception) {
                logger.error("Failed to show diff", e)
            }
        }
    }
    
    /**
     * Accept the changes
     */
    fun acceptChanges() {
        val context = currentContext ?: return
        
        ApplicationManager.getApplication().invokeLater {
            try {
                debugLog("Accepting changes")
                context.state = State.COMPLETED
                showStatusIndicator(context, "✅ Changes applied")
                
                // Brief delay before cleanup to show success
                ApplicationManager.getApplication().executeOnPooledThread {
                    Thread.sleep(1000)
                    ApplicationManager.getApplication().invokeLater {
                        context.acceptCallback()
                        hide()
                    }
                }
                
            } catch (e: Exception) {
                logger.error("Error accepting changes", e)
                hide()
            }
        }
    }
    
    /**
     * Reject the changes
     */
    fun rejectChanges() {
        val context = currentContext ?: return
        
        ApplicationManager.getApplication().invokeLater {
            try {
                debugLog("Rejecting changes")
                context.state = State.COMPLETED
                showStatusIndicator(context, "❌ Changes rejected")
                
                // Brief delay before cleanup
                ApplicationManager.getApplication().executeOnPooledThread {
                    Thread.sleep(500)
                    ApplicationManager.getApplication().invokeLater {
                        context.rejectCallback()
                        hide()
                    }
                }
                
            } catch (e: Exception) {
                logger.error("Error rejecting changes", e)
                hide()
            }
        }
    }
    
    /**
     * Hide and cleanup
     */
    fun hide() {
        if (!isActive.compareAndSet(true, false)) {
            return // Already hidden
        }
        
        ApplicationManager.getApplication().invokeLater {
            try {
                currentContext?.dispose()
                currentContext = null
                debugLog("Method diff hidden and cleaned up")
            } catch (e: Exception) {
                logger.warn("Error during cleanup", e)
            }
        }
    }
    
    /**
     * Check if active
     */
    fun isActive(): Boolean = isActive.get()
    
    /**
     * Get current method context
     */
    fun getCurrentMethodContext(): ZestMethodContextCollector.MethodContext? {
        return currentContext?.methodContext
    }
    
    // Private implementation methods
    
    private fun selectMethod(context: RenderingContext) {
        val methodStartOffset = context.methodContext.methodStartOffset
        val methodEndOffset = context.methodContext.methodEndOffset
        
        // Highlight method selection
        val selectionAttributes = TextAttributes().apply {
            backgroundColor = JBColor(
                java.awt.Color(173, 216, 230, 60),  // Light blue selection
                java.awt.Color(70, 130, 180, 60)    // Dark blue selection
            )
        }
        
        val highlighter = context.editor.markupModel.addRangeHighlighter(
            methodStartOffset,
            methodEndOffset,
            HighlighterLayer.SELECTION,
            selectionAttributes,
            HighlighterTargetArea.EXACT_RANGE
        )
        
        context.selectionHighlighter = highlighter
        debugLog("Method selected and highlighted")
    }
    
    private fun hideMethodContent(context: RenderingContext) {
        val methodStartOffset = context.methodContext.methodStartOffset
        val methodEndOffset = context.methodContext.methodEndOffset
        
        // Hide the original method content by making it same color as background
        val hideAttributes = TextAttributes().apply {
            foregroundColor = context.editor.colorsScheme.defaultBackground
            backgroundColor = context.editor.colorsScheme.defaultBackground
        }
        
        val hideHighlighter = context.editor.markupModel.addRangeHighlighter(
            methodStartOffset,
            methodEndOffset,
            HighlighterLayer.LAST + 100,
            hideAttributes,
            HighlighterTargetArea.EXACT_RANGE
        )
        
        context.hideHighlighter = hideHighlighter
        debugLog("Method content hidden")
    }
    
    private fun showStatusIndicator(context: RenderingContext, message: String) {
        // Remove existing status inlay
        context.statusInlay?.let { inlay ->
            if (inlay.isValid) {
                Disposer.dispose(inlay)
            }
        }
        
        val renderer = createStatusRenderer(message, context.editor.colorsScheme)
        
        // Determine insertion point based on state
        val insertOffset = when {
            context.state == State.PROCESSING -> {
                // During processing, show at method start
                context.methodContext.methodStartOffset
            }
            context.diffInlay != null -> {
                // If diff is showing, show status below it at method end
                context.methodContext.methodEndOffset
            }
            else -> {
                // Default to method start
                context.methodContext.methodStartOffset
            }
        }
        
        val inlay = context.editor.inlayModel.addBlockElement(
            insertOffset,
            true,
            false,
            50, // Medium priority, below diff but above normal content
            renderer
        )
        
        context.statusInlay = inlay
        debugLog("Status indicator shown: $message")
    }
    
    private fun showInPlaceDiff(
        context: RenderingContext,
        diffResult: GDiff.DiffResult,
        rewrittenMethod: String
    ) {
        val config = DiffRenderingConfig.getInstance()
        val originalText = context.methodContext.methodContent
        
        // Remove the status indicator to make room for diff
        context.statusInlay?.let { inlay ->
            if (inlay.isValid) {
                Disposer.dispose(inlay)
            }
        }
        context.statusInlay = null
        
        // Perform line-level diff
        val lineDiff = WordDiffUtil.diffLines(
            originalText,
            rewrittenMethod,
            config.getDiffAlgorithm(),
            context.methodContext.language
        )
        
        // Prepare lines for rendering
        val originalLines = originalText.lines().toMutableList()
        val modifiedLines = rewrittenMethod.lines().toMutableList()
        
        // Handle trailing newlines
        if (originalText.endsWith("\n") && (originalLines.isEmpty() || originalLines.last().isNotEmpty())) {
            originalLines.add("")
        }
        if (rewrittenMethod.endsWith("\n") && (modifiedLines.isEmpty() || modifiedLines.last().isNotEmpty())) {
            modifiedLines.add("")
        }
        
        // Create diff renderer
        val diffRenderer = if (config.useSideBySideView()) {
            SideBySideMethodDiffRenderer(
                originalLines,
                modifiedLines,
                lineDiff,
                context.editor.colorsScheme,
                context.methodContext.language
            )
        } else {
            WholeMethodDiffRenderer(
                originalLines,
                modifiedLines,
                lineDiff,
                context.editor.colorsScheme,
                context.methodContext.language
            )
        }
        
        // Show diff prominently in place of the hidden method
        // This becomes the main visible content that user focuses on
        val insertOffset = context.methodContext.methodStartOffset
        val inlay = context.editor.inlayModel.addBlockElement(
            insertOffset,
            true,
            false, // showAbove = false to show inline/below
            100, // HIGH priority to be prominently displayed
            diffRenderer
        )
        
        context.diffInlay = inlay
        debugLog("Diff displayed prominently in place as main content")
        
        // Add a small status hint below the diff
        showStatusIndicator(context, "📝 Review changes (Tab to accept, Esc to reject)")
    }
    
    private fun createStatusRenderer(message: String, scheme: EditorColorsScheme): EditorCustomElementRenderer {
        return object : EditorCustomElementRenderer {
            override fun calcWidthInPixels(inlay: Inlay<*>): Int {
                val font = getEditorFont(inlay.editor)
                val metrics = inlay.editor.contentComponent.getFontMetrics(font)
                return metrics.stringWidth(message) + 20
            }
            
            override fun calcHeightInPixels(inlay: Inlay<*>): Int {
                return inlay.editor.lineHeight + 8
            }
            
            override fun paint(inlay: Inlay<*>, g: Graphics, targetRect: Rectangle, textAttributes: TextAttributes) {
                val g2d = g as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                
                // Draw background based on state
                val bgColor = when {
                    message.contains("rewriting") -> JBColor(
                        java.awt.Color(255, 235, 59, 80),  // Yellow for processing
                        java.awt.Color(107, 99, 50, 80)
                    )
                    message.contains("Review") -> JBColor(
                        java.awt.Color(173, 216, 230, 80), // Blue for review
                        java.awt.Color(70, 130, 180, 80)
                    )
                    message.contains("applied") -> JBColor(
                        java.awt.Color(92, 225, 92, 80),   // Green for success
                        java.awt.Color(59, 91, 59, 80)
                    )
                    message.contains("rejected") -> JBColor(
                        java.awt.Color(255, 92, 92, 80),   // Red for rejection
                        java.awt.Color(92, 22, 36, 80)
                    )
                    else -> scheme.defaultBackground
                }
                
                g2d.color = bgColor
                g2d.fillRoundRect(targetRect.x + 5, targetRect.y + 2, targetRect.width - 10, targetRect.height - 4, 8, 8)
                
                // Draw text
                g2d.color = scheme.defaultForeground
                g2d.font = getEditorFont(inlay.editor).deriveFont(Font.ITALIC)
                val metrics = g2d.fontMetrics
                g2d.drawString(message, targetRect.x + 10, targetRect.y + metrics.ascent + 4)
            }
        }
    }
    
    private fun getEditorFont(editor: Editor): Font {
        return UIUtil.getFontWithFallbackIfNeeded(
            editor.colorsScheme.getFont(EditorFontType.PLAIN),
            "sample"
        )
    }
}
