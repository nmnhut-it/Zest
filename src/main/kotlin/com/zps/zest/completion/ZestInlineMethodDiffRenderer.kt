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
import com.intellij.openapi.util.TextRange
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import com.zps.zest.completion.context.ZestMethodContextCollector
import com.zps.zest.completion.diff.DiffDebugUtil
import com.zps.zest.completion.diff.DiffRenderingConfig
import com.zps.zest.completion.diff.MultiLineDiffRenderer
import com.zps.zest.completion.diff.SideBySideMethodDiffRenderer
import com.zps.zest.completion.diff.WholeMethodDiffRenderer
import com.zps.zest.completion.diff.WordDiffUtil
import com.zps.zest.gdiff.GDiff
import java.awt.AlphaComposite
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Renders method diff inline in the editor using GDiff results
 * Handles proper threading, cleanup, and integration with tab acceptance
 */
class ZestInlineMethodDiffRenderer {
    private val logger = Logger.getInstance(ZestInlineMethodDiffRenderer::class.java)
    
    companion object {
        // Enable debug logging to diagnose missing last line issue
        private const val DEBUG_DIFF_RENDERING = true
    }
    
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
     * Render diff changes using either side-by-side or color-coded view
     * 
     * Side-by-side view (default):
     * 1. Hides the entire original method in the editor
     * 2. Shows a side-by-side comparison below:
     *    - Left column: Original method with line numbers
     *    - Right column: New method with line numbers
     * 3. Uses subtle background colors to indicate changes
     * 
     * Color-coded view (alternative):
     * 1. Hides the entire original method
     * 2. Shows the complete new method in a single block
     * 3. Uses color coding with indicators (+, ~) for changes
     * 
     * The view mode is controlled by the DiffRenderingConfig.useSideBySideView setting.
     */
    private fun renderDiffChanges(context: RenderingContext) {
        val document = context.editor.document
        val originalText = context.methodContext.methodContent
        val modifiedText = context.rewrittenMethod
        val config = DiffRenderingConfig.getInstance()
        
        // Perform line-level diff to identify change blocks
        val lineDiff = WordDiffUtil.diffLines(
            originalText, 
            modifiedText,
            config.getDiffAlgorithm(),
            context.methodContext.language
        )
        
        // Debug logging if enabled
        if (DEBUG_DIFF_RENDERING || logger.isDebugEnabled) {
            logger.info("=== Original method content ===")
            originalText.lines().forEachIndexed { idx, line ->
                logger.info("Original[$idx]: '$line'")
            }
            logger.info("=== Modified method content ===")
            modifiedText.lines().forEachIndexed { idx, line ->
                logger.info("Modified[$idx]: '$line'")
            }
            
            DiffDebugUtil.logDiffDetails(originalText, modifiedText, lineDiff, context.methodContext.methodName)
        }
        
        // Hide the entire original method
        val methodStartOffset = context.methodContext.methodStartOffset
        val methodEndOffset = context.methodContext.methodEndOffset
        
        if (methodStartOffset < methodEndOffset) {
            val hideHighlighter = context.editor.markupModel.addRangeHighlighter(
                methodStartOffset,
                methodEndOffset,
                HighlighterLayer.LAST + 100,
                TextAttributes().apply {
                    foregroundColor = context.editor.colorsScheme.defaultBackground
                    backgroundColor = context.editor.colorsScheme.defaultBackground
                },
                HighlighterTargetArea.EXACT_RANGE
            )
            context.deletionHighlighters.add(hideHighlighter)
        }
        
        // Create renderer based on user preference
        val renderer = if (config.useSideBySideView()) {
            createSideBySideMethodRenderer(
                originalText.lines(),
                modifiedText.lines(), 
                lineDiff,
                context.editor.colorsScheme,
                context.methodContext.language
            )
        } else {
            createWholeMethodRenderer(
                originalText.lines(),
                modifiedText.lines(), 
                lineDiff,
                context.editor.colorsScheme,
                context.methodContext.language
            )
        }
        
        // Insert the new method rendering after the hidden original
        val inlay = context.editor.inlayModel.addBlockElement(
            methodEndOffset,
            true,
            false,
            0,
            renderer
        )
        
        if (inlay != null) {
            context.additionInlays.add(inlay)
            val viewType = if (config.useSideBySideView()) "side-by-side" else "color-coded"
            logger.info("Successfully rendered $viewType method diff")
        }
    }
    
    /**
     * Render a multi-line modification block with side-by-side view
     */
    private fun renderMultiLineBlock(
        context: RenderingContext,
        block: WordDiffUtil.DiffBlock,
        documentLineNumber: Int
    ) {
        val document = context.editor.document
        
        if (documentLineNumber >= 0 && documentLineNumber < document.lineCount) {
            // Only hide original lines if there are any
            if (block.originalLines.isNotEmpty()) {
                // Hide all original lines in the block
                val startOffset = document.getLineStartOffset(documentLineNumber)
                val endLineNumber = documentLineNumber + block.originalLines.size - 1
                val endOffset = if (endLineNumber < document.lineCount) {
                    document.getLineEndOffset(endLineNumber)
                } else {
                    document.textLength
                }
                
                // Ensure valid offsets
                if (startOffset <= endOffset) {
                    // Hide the entire block
                    val hideHighlighter = context.editor.markupModel.addRangeHighlighter(
                        startOffset,
                        endOffset,
                        HighlighterLayer.LAST + 100,
                        TextAttributes().apply {
                            foregroundColor = context.editor.colorsScheme.defaultBackground
                            backgroundColor = context.editor.colorsScheme.defaultBackground
                        },
                        HighlighterTargetArea.EXACT_RANGE
                    )
                    context.deletionHighlighters.add(hideHighlighter)
                }
            }
            
            // Determine the insertion offset for the inlay
            val inlayOffset = if (block.originalLines.isNotEmpty()) {
                // Insert after the last original line
                val endLineNumber = documentLineNumber + block.originalLines.size - 1
                if (endLineNumber < document.lineCount) {
                    document.getLineEndOffset(endLineNumber)
                } else {
                    document.textLength
                }
            } else {
                // For pure additions, insert at the current position
                if (documentLineNumber > 0) {
                    document.getLineEndOffset(documentLineNumber - 1)
                } else {
                    0
                }
            }
            
            // Create multi-line side-by-side renderer
            val renderer = MultiLineDiffRenderer(
                block.originalLines,
                block.modifiedLines,
                context.editor.colorsScheme,
                context.methodContext.language,
                isUnchanged = (block.type == WordDiffUtil.BlockType.UNCHANGED)
            )
            
            // Add inlay at the calculated offset
            val inlay = context.editor.inlayModel.addBlockElement(
                inlayOffset,
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
     * Render a multi-line addition block
     */
    private fun renderMultiLineAddition(
        context: RenderingContext,
        addedLines: List<String>,
        insertAfterDocumentLine: Int
    ) {
        val document = context.editor.document
        
        // Calculate the proper insertion offset
        val insertOffset = when {
            insertAfterDocumentLine < 0 -> {
                // Insert at the beginning of the method
                context.methodContext.methodStartOffset
            }
            insertAfterDocumentLine < document.lineCount -> {
                // Insert after the specified line
                document.getLineEndOffset(insertAfterDocumentLine)
            }
            else -> {
                // Insert at the end of the document
                document.textLength
            }
        }
        
        // Use MultiLineDiffRenderer for consistency with modifications
        val renderer = MultiLineDiffRenderer(
            emptyList(), // No original lines for pure additions
            addedLines,
            context.editor.colorsScheme,
            context.methodContext.language,
            isUnchanged = false
        )
        
        val inlay = context.editor.inlayModel.addBlockElement(
            insertOffset,
            true,  // relatesToPrecedingText
            true,  // showAbove
            0,
            renderer
        )
        
        if (inlay != null) {
            context.additionInlays.add(inlay)
        }
    }
    
    /**
     * Create renderer for multiple ghost text lines
     */
    private fun createMultiLineGhostTextRenderer(lines: List<String>, scheme: EditorColorsScheme): EditorCustomElementRenderer {
        val config = DiffRenderingConfig.getInstance()
        
        return object : EditorCustomElementRenderer {
            override fun calcWidthInPixels(inlay: Inlay<*>): Int {
                val font = getEditorFont(inlay.editor)
                val metrics = inlay.editor.contentComponent.getFontMetrics(font)
                // Use full editor width to ensure proper background clearing
                return inlay.editor.contentComponent.width - 20
            }
            
            override fun calcHeightInPixels(inlay: Inlay<*>): Int {
                val font = getEditorFont(inlay.editor)
                val metrics = inlay.editor.contentComponent.getFontMetrics(font)
                return lines.size * (metrics.height + 2) + 4 // Add extra padding
            }
            
            override fun paint(inlay: Inlay<*>, g: Graphics, targetRect: Rectangle, textAttributes: TextAttributes) {
                val g2d = g as Graphics2D
                val font = getEditorFont(inlay.editor)
                g2d.font = font
                val metrics = g2d.fontMetrics
                
                // Enable antialiasing
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                
                // Clear background with editor background color to prevent overlap
                g2d.color = scheme.defaultBackground
                g2d.fillRect(targetRect.x, targetRect.y, targetRect.width, targetRect.height)
                
                // Draw each line as ghost text
                val originalComposite = g2d.composite
                val ghostAlpha = config.getGhostTextAlpha(!JBColor.isBright())
                g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, ghostAlpha)
                g2d.color = getGhostTextColor(scheme)
                
                var yPos = targetRect.y + metrics.ascent + 2
                for (line in lines) {
                    g2d.drawString("+ $line", targetRect.x + 5, yPos)
                    yPos += metrics.height + 2
                }
                
                g2d.composite = originalComposite
            }
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
        insertAtDocumentLine: Int, 
        rewrittenLine: String,
        originalBlockLine: Int
    ) {
        val document = context.editor.document
        
        logger.debug("Rendering line addition at document line $insertAtDocumentLine: ${rewrittenLine.take(50)}")
        
        if (insertAtDocumentLine >= 0 && insertAtDocumentLine <= document.lineCount) {
            // Calculate the proper insertion offset
            val insertOffset = when {
                insertAtDocumentLine == 0 -> {
                    // Insert at the beginning of the document
                    0
                }
                insertAtDocumentLine < document.lineCount -> {
                    // Insert after the specified line
                    document.getLineEndOffset(insertAtDocumentLine)
                }
                else -> {
                    // Insert at the end of document
                    document.textLength
                }
            }
            
            // Create ghost text addition inlay
            val renderer = createGhostTextRenderer(rewrittenLine, context.editor.colorsScheme)
            val inlay = context.editor.inlayModel.addBlockElement(
                insertOffset,
                true,  // relatesToPrecedingText
                true,  // showAbove - show the inlay above the next line
                0,
                renderer
            )
            
            if (inlay != null) {
                context.additionInlays.add(inlay)
                logger.debug("Successfully created inlay at offset $insertOffset")
            } else {
                logger.warn("Failed to create inlay at offset $insertOffset")
            }
        } else {
            logger.warn("Invalid insertion line: $insertAtDocumentLine (document has ${document.lineCount} lines)")
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
        val config = DiffRenderingConfig.getInstance()
        
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
                
                // Enable antialiasing for better text rendering
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                
                // Use theme background
                g2d.color = scheme.defaultBackground
                g2d.fillRect(targetRect.x, targetRect.y, targetRect.width, targetRect.height)
                
                var xPos = targetRect.x + 5
                val yPos = targetRect.y + metrics.ascent + 2
                
                // Draw original segments with highlighting if word diff is enabled
                if (config.isWordLevelDiffEnabled()) {
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
                } else {
                    // Draw entire line with strikethrough if word diff is disabled
                    val lineWidth = metrics.stringWidth(originalLine)
                    g2d.color = getDeletionBackgroundColor()
                    g2d.fillRect(xPos, targetRect.y, lineWidth, targetRect.height)
                    
                    g2d.color = scheme.defaultForeground
                    g2d.drawString(originalLine, xPos, yPos)
                    
                    g2d.color = getDeletionEffectColor()
                    val strikeY = yPos - metrics.height / 3
                    g2d.drawLine(xPos, strikeY, xPos + lineWidth, strikeY)
                    
                    xPos += lineWidth
                }
                
                // Draw arrow
                g2d.color = scheme.defaultForeground
                g2d.drawString(" → ", xPos, yPos)
                xPos += metrics.stringWidth(" → ")
                
                // Draw modified segments as ghost text
                val originalComposite = g2d.composite
                val ghostAlpha = config.getGhostTextAlpha(!JBColor.isBright())
                
                if (config.isWordLevelDiffEnabled()) {
                    val modifiedSegments = WordDiffUtil.mergeSegments(wordDiff.modifiedSegments)
                    
                    for (segment in modifiedSegments) {
                        when (segment.type) {
                            WordDiffUtil.ChangeType.ADDED, WordDiffUtil.ChangeType.MODIFIED -> {
                                // Draw as highlighted ghost text
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
                } else {
                    // Draw entire modified line as ghost text
                    g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, ghostAlpha)
                    g2d.color = getGhostTextColor(scheme)
                    g2d.drawString(modifiedLine, xPos, yPos)
                    g2d.composite = originalComposite
                }
                
                // Show similarity score in debug mode (commented out for production)
                // if (wordDiff.similarity < 0.5) {
                //     g2d.color = Color.GRAY
                //     g2d.font = font.deriveFont(font.size * 0.8f)
                //     g2d.drawString(" (${(wordDiff.similarity * 100).toInt()}%)", xPos + 10, yPos)
                // }
            }
        }
    }
    
    /**
     * Get deletion background color based on theme
     */
    private fun getDeletionBackgroundColor(): java.awt.Color {
        return JBColor(
            java.awt.Color(255, 220, 220), // Light theme
            java.awt.Color(92, 22, 36)     // Dark theme
        )
    }
    
    /**
     * Get deletion effect color based on theme
     */
    private fun getDeletionEffectColor(): java.awt.Color {
        return JBColor(
            java.awt.Color(255, 85, 85),   // Light theme
            java.awt.Color(248, 81, 73)    // Dark theme
        )
    }
    
    /**
     * Create renderer for pure addition lines with ghost text style
     */
    private fun createGhostTextRenderer(text: String, scheme: EditorColorsScheme): EditorCustomElementRenderer {
        val config = DiffRenderingConfig.getInstance()
        
        return object : EditorCustomElementRenderer {
            override fun calcWidthInPixels(inlay: Inlay<*>): Int {
                // Use full editor width to ensure proper background clearing
                return inlay.editor.contentComponent.width - 20
            }
            
            override fun calcHeightInPixels(inlay: Inlay<*>): Int {
                val font = getEditorFont(inlay.editor)
                val metrics = inlay.editor.contentComponent.getFontMetrics(font)
                return metrics.height + 6 // Add padding
            }
            
            override fun paint(inlay: Inlay<*>, g: Graphics, targetRect: Rectangle, textAttributes: TextAttributes) {
                val g2d = g as Graphics2D
                val font = getEditorFont(inlay.editor)
                g2d.font = font
                
                // Enable antialiasing
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                
                // Clear background to prevent overlap
                g2d.color = scheme.defaultBackground
                g2d.fillRect(targetRect.x, targetRect.y, targetRect.width, targetRect.height)
                
                // Draw ghost text with transparency
                val originalComposite = g2d.composite
                val ghostAlpha = config.getGhostTextAlpha(!JBColor.isBright())
                g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, ghostAlpha)
                
                g2d.color = getGhostTextColor(scheme)
                val metrics = g2d.fontMetrics
                g2d.drawString("+ $text", targetRect.x + 5, targetRect.y + metrics.ascent + 3)
                
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
     * Debug method to log detailed information about method boundaries and diff blocks
     */
    private fun debugMethodBoundaries(
        context: RenderingContext,
        lineDiff: WordDiffUtil.LineDiffResult
    ) {
        val document = context.editor.document
        val methodContext = context.methodContext
        
        logger.info("=== DEBUG: Method Boundaries ===")
        logger.info("Method: ${methodContext.methodName}")
        logger.info("Method start offset: ${methodContext.methodStartOffset}")
        logger.info("Method end offset: ${methodContext.methodEndOffset}")
        
        // Check what character is at the end offset
        if (methodContext.methodEndOffset < document.textLength) {
            val charAtEnd = document.charsSequence[methodContext.methodEndOffset]
            logger.info("Character at end offset: '${charAtEnd}' (code: ${charAtEnd.toInt()})")
        } else {
            logger.info("Method end offset is at document end")
        }
        
        // Log the actual method content with visible line endings
        logger.info("Method content (with \\n visible):")
        logger.info("'${methodContext.methodContent.replace("\n", "\\n")}'")
        logger.info("Method content length: ${methodContext.methodContent.length}")
        
        // Log line information
        val methodStartLine = document.getLineNumber(methodContext.methodStartOffset)
        val methodEndLine = document.getLineNumber(methodContext.methodEndOffset)
        logger.info("Method spans document lines: $methodStartLine to $methodEndLine")
        
        // Log each line in the method
        for (line in methodStartLine..methodEndLine) {
            val lineStart = document.getLineStartOffset(line)
            val lineEnd = document.getLineEndOffset(line)
            val lineText = document.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd))
            logger.info("  Line $line: '${lineText.take(50)}${if (lineText.length > 50) "..." else ""}'")
        }
        
        // Log the rewritten method content
        logger.info("\nRewritten method content (with \\n visible):")
        logger.info("'${context.rewrittenMethod.replace("\n", "\\n")}'")
        logger.info("Rewritten method length: ${context.rewrittenMethod.length}")
        
        // Log diff blocks
        logger.info("\nDiff blocks from line diff:")
        lineDiff.blocks.forEachIndexed { index, block ->
            logger.info("Block $index: ${block.type}")
            logger.info("  Original lines: ${block.originalStartLine} to ${block.originalEndLine} (${block.originalLines.size} lines)")
            logger.info("  Modified lines: ${block.modifiedStartLine} to ${block.modifiedEndLine} (${block.modifiedLines.size} lines)")
            
            // Show actual content for debugging
            if (block.originalLines.isNotEmpty()) {
                logger.info("  Original content: '${block.originalLines.joinToString("\\n") { it.take(40) }}'")
            }
            if (block.modifiedLines.isNotEmpty()) {
                logger.info("  Modified content: '${block.modifiedLines.joinToString("\\n") { it.take(40) }}'")
            }
            
            // Check if this is the last block
            if (index == lineDiff.blocks.size - 1) {
                logger.info("  This is the LAST block")
                if (block.type == WordDiffUtil.BlockType.UNCHANGED) {
                    logger.info("  Last block is UNCHANGED - might be why it's not displayed")
                }
            }
        }
        
        // Check for missing lines
        val originalLines = context.methodContext.methodContent.lines()
        val lastCoveredLine = lineDiff.blocks.lastOrNull()?.originalEndLine ?: -1
        if (lastCoveredLine < originalLines.size - 1) {
            logger.warn("MISSING LINES: Last diff block covers up to line $lastCoveredLine, but method has ${originalLines.size} lines")
            logger.warn("Missing lines: ${originalLines.subList(lastCoveredLine + 1, originalLines.size)}")
        }
        
        logger.info("=== END DEBUG ===")
    }
    
    /**
     * Create a side-by-side renderer showing old method on left, new method on right
     */
    private fun createSideBySideMethodRenderer(
        originalLines: List<String>,
        modifiedLines: List<String>,
        lineDiff: WordDiffUtil.LineDiffResult,
        scheme: EditorColorsScheme,
        language: String
    ): EditorCustomElementRenderer {
        return SideBySideMethodDiffRenderer(originalLines, modifiedLines, lineDiff, scheme, language)
    }
    
    /**
     * Create a renderer that shows the entire new method with color hints for changes
     */
    private fun createWholeMethodRenderer(
        originalLines: List<String>,
        modifiedLines: List<String>,
        lineDiff: WordDiffUtil.LineDiffResult,
        scheme: EditorColorsScheme,
        language: String
    ): EditorCustomElementRenderer {
        return WholeMethodDiffRenderer(originalLines, modifiedLines, lineDiff, scheme, language)
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
