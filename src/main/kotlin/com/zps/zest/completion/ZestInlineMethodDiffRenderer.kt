package com.zps.zest.completion

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ScrollType
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
 * 
 * Rendering Modes:
 * 1. Addition-only diffs: Uses ghost text inline rendering
 *    - Semi-transparent text with "+" prefix
 *    - No hiding of original code
 *    - No scrolling needed (appears inline)
 *    
 * 2. Diffs with modifications/deletions: Uses side-by-side or color-coded view
 *    - Hides original method
 *    - Shows full comparison
 *    - Supports scrolling/navigation options
 * 
 * Focus Options (for full diff view):
 * 1. autoScrollToDiff (default: true) - Automatically scrolls to show the diff
 * 2. showDiffAtStart (default: false) - Shows diff at method start instead of end
 * 3. showFloatingDiffButton (default: false) - Shows a button to jump to diff
 * 
 * When autoScrollToDiff is false, shows a temporary hint at cursor position.
 */
class ZestInlineMethodDiffRenderer {
    private val logger = Logger.getInstance(ZestInlineMethodDiffRenderer::class.java)
    
    companion object {
        // Enable debug logging to diagnose missing last line issue
        private const val DEBUG_DIFF_RENDERING = true
        
        private fun debugLog(message: String) {
            if (DEBUG_DIFF_RENDERING) {
                println("[ZestDiff] $message")
            }
        }
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
        val rejectCallback: () -> Unit,
        var mainDiffInlay: Inlay<*>? = null,
        var floatingButtonInlay: Inlay<*>? = null,
        var isAdditionOnly: Boolean = false
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
            
            // Dispose floating button if exists
            floatingButtonInlay?.let { inlay ->
                try {
                    if (inlay.isValid) {
                        Disposer.dispose(inlay)
                    }
                } catch (e: Exception) {
                    // Inlay might already be disposed
                }
            }
            floatingButtonInlay = null
            mainDiffInlay = null
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
            debugLog("Showing inline method diff for ${methodContext.methodName}")
            
            // Verify editor is still valid
            if (editor.isDisposed) {
                logger.warn("Editor is disposed, cannot show diff")
                return
            }
            
            // Validate method boundaries
            val document = editor.document
            val extractedContent = document.getText(TextRange(methodContext.methodStartOffset, methodContext.methodEndOffset))
            if (extractedContent != methodContext.methodContent) {
                debugLog("WARNING: Method boundary mismatch!")
                debugLog("  Expected length: ${methodContext.methodContent.length}")
                debugLog("  Extracted length: ${extractedContent.length}")
                debugLog("  Method content ends with newline: ${methodContext.methodContent.endsWith("\n")}")
                debugLog("  Extracted content ends with newline: ${extractedContent.endsWith("\n")}")
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
            
            debugLog("Inline diff rendered successfully with ${context.deletionHighlighters.size} deletions and ${context.additionInlays.size} additions")
            if (context.isAdditionOnly) {
                debugLog("Used ghost text rendering for addition-only diff")
            }
            
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
                debugLog("Inline diff hidden and cleaned up")
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
                debugLog("Accepting method diff changes")
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
                debugLog("Rejecting method diff changes")
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
     * For addition-only diffs:
     * - Uses ghost text inline rendering (no side-by-side view)
     * - Shows additions as semi-transparent text
     * 
     * For diffs with modifications/deletions:
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
        
        // Special handling for empty original method
        if (originalText.trim().isEmpty() && modifiedText.isNotEmpty()) {
            debugLog("Original method is empty - treating as pure addition")
            context.isAdditionOnly = true
            renderAdditionOnlyDiff(context, lineDiff)
            return
        }
        
        // Debug logging if enabled
        if (DEBUG_DIFF_RENDERING || logger.isDebugEnabled) {
            debugLog("=== Original method content ===")
            originalText.lines().forEachIndexed { idx, line ->
                debugLog("Original[$idx]: '$line'")
            }
            debugLog("=== Modified method content ===")
            modifiedText.lines().forEachIndexed { idx, line ->
                debugLog("Modified[$idx]: '$line'")
            }
            
            DiffDebugUtil.logDiffDetails(originalText, modifiedText, lineDiff, context.methodContext.methodName)
        }
        
        // Check if this is an addition-only diff
        val isAdditionOnly = isAdditionOnlyDiff(lineDiff)
        
        if (isAdditionOnly && config.useGhostTextForAdditions()) {
            debugLog("Detected addition-only diff, using ghost text rendering")
            context.isAdditionOnly = true
            // For addition-only diffs, use ghost text rendering
            renderAdditionOnlyDiff(context, lineDiff)
        } else {
            if (isAdditionOnly) {
                debugLog("Detected addition-only diff, but ghost text is disabled - using full diff view")
            } else {
                debugLog("Detected modifications/deletions in diff, using full diff view")
            }
            // For diffs with modifications/deletions, use the full diff view
            renderFullDiff(context, originalText, modifiedText, lineDiff)
        }
    }
    
    /**
     * Check if the diff contains only additions (no deletions or modifications)
     * 
     * This is common when:
     * - Adding new methods to a class
     * - Adding new parameters to existing methods
     * - Adding new code blocks within methods
     * - Adding comments or documentation
     * - The entire method is new (original is empty)
     * 
     * For these cases, ghost text is cleaner than showing a full diff view.
     */
    private fun isAdditionOnlyDiff(lineDiff: WordDiffUtil.LineDiffResult): Boolean {
        // Special case: if original is empty, this is purely an addition
        if (lineDiff.blocks.all { it.originalLines.isEmpty() && it.modifiedLines.isNotEmpty() }) {
            return true
        }
        
        return lineDiff.blocks.all { block ->
            block.type == WordDiffUtil.BlockType.UNCHANGED || 
            block.type == WordDiffUtil.BlockType.ADDED
        }
    }
    
    /**
     * Render addition-only diff using ghost text
     */
    private fun renderAdditionOnlyDiff(
        context: RenderingContext,
        lineDiff: WordDiffUtil.LineDiffResult
    ) {
        val config = DiffRenderingConfig.getInstance()
        val document = context.editor.document
        val methodStartLine = document.getLineNumber(context.methodContext.methodStartOffset)
        val methodEndLine = document.getLineNumber(context.methodContext.methodEndOffset)
        
        debugLog("Rendering addition-only diff:")
        debugLog("  Method spans lines $methodStartLine to $methodEndLine")
        
        // Group consecutive additions by their insertion point
        val additionGroups = mutableMapOf<Int, MutableList<String>>()
        var totalAddedLines = 0
        
        for (block in lineDiff.blocks) {
            if (block.type == WordDiffUtil.BlockType.ADDED) {
                totalAddedLines += block.modifiedLines.size
                
                debugLog("  Processing ADDED block: ${block.modifiedLines.size} lines")
                debugLog("    Original start line: ${block.originalStartLine}")
                
                // Calculate where this block should be inserted relative to original lines
                // For additions, originalStartLine indicates where in the original text this should go
                val insertAfterOriginalLine = if (block.originalStartLine > 0) {
                    block.originalStartLine - 1
                } else {
                    -1 // Insert at beginning
                }
                
                // Convert to document line number
                val targetDocumentLine = if (insertAfterOriginalLine >= 0) {
                    methodStartLine + insertAfterOriginalLine
                } else {
                    methodStartLine - 1 // Insert before method start
                }
                
                // Clamp to method boundaries
                val clampedLine = when {
                    targetDocumentLine < methodStartLine -> methodStartLine
                    targetDocumentLine > methodEndLine -> methodEndLine
                    else -> targetDocumentLine
                }
                
                debugLog("    Insert after original line: $insertAfterOriginalLine")
                debugLog("    Target document line: $targetDocumentLine")
                debugLog("    Clamped line: $clampedLine")
                
                additionGroups.getOrPut(clampedLine) { mutableListOf() }.addAll(block.modifiedLines)
            }
        }
        
        // Render grouped additions
        for ((documentLine, lines) in additionGroups.entries.sortedBy { it.key }) {
            // Calculate the exact offset, ensuring it's within method bounds
            val lineStartOffset = document.getLineStartOffset(documentLine)
            val lineEndOffset = document.getLineEndOffset(documentLine)
            
            // Determine insertion offset based on position
            val insertOffset = when {
                // If at method start, insert after the line
                documentLine == methodStartLine -> lineEndOffset.coerceAtMost(context.methodContext.methodEndOffset)
                // If at method end, insert before the line
                documentLine == methodEndLine -> lineStartOffset.coerceAtLeast(context.methodContext.methodStartOffset)
                // Otherwise insert after the line
                else -> lineEndOffset
            }
            
            // Final safety check
            if (insertOffset < context.methodContext.methodStartOffset || 
                insertOffset > context.methodContext.methodEndOffset) {
                debugLog("WARNING: Skipping insertion outside method bounds: offset=$insertOffset, method=${context.methodContext.methodStartOffset}..${context.methodContext.methodEndOffset}")
                continue
            }
            
            debugLog("  Rendering ${lines.size} lines at document line $documentLine (offset $insertOffset)")
            
            val renderer = createMultiLineGhostTextRenderer(lines, context.editor.colorsScheme)
            val inlay = context.editor.inlayModel.addBlockElement(
                insertOffset,
                true, // relatesToPrecedingText
                false, // showAbove - show inline after the text
                0,
                renderer
            )
            
            if (inlay != null) {
                context.additionInlays.add(inlay)
            }
        }
        
        debugLog("Rendered $totalAddedLines added lines as ghost text in ${additionGroups.size} blocks")
        
        if (config.showAdditionHint() && totalAddedLines > 0) {
            showAdditionHint(context.editor)
        }
    }
    
    /**
     * Show a hint about ghost text additions
     */
    private fun showAdditionHint(editor: Editor) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val cursorOffset = editor.caretModel.offset
                val hintRenderer = createAdditionHintRenderer(editor.colorsScheme)
                
                val hintInlay = editor.inlayModel.addInlineElement(
                    cursorOffset,
                    true,
                    hintRenderer
                )
                
                if (hintInlay != null) {
                    // Auto-remove after 2 seconds
                    ApplicationManager.getApplication().executeOnPooledThread {
                        Thread.sleep(2000)
                        ApplicationManager.getApplication().invokeLater {
                            if (!hintInlay.isValid) return@invokeLater
                            Disposer.dispose(hintInlay)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to show addition hint", e)
            }
        }
    }
    
    /**
     * Create hint renderer for addition-only diffs
     */
    private fun createAdditionHintRenderer(scheme: EditorColorsScheme): EditorCustomElementRenderer {
        return object : EditorCustomElementRenderer {
            override fun calcWidthInPixels(inlay: Inlay<*>): Int {
                val font = getEditorFont(inlay.editor).deriveFont(Font.ITALIC)
                val metrics = inlay.editor.contentComponent.getFontMetrics(font)
                return metrics.stringWidth(" ✨ Tab to accept additions ") + 10
            }
            
            override fun calcHeightInPixels(inlay: Inlay<*>): Int {
                return inlay.editor.lineHeight
            }
            
            override fun paint(inlay: Inlay<*>, g: Graphics, targetRect: Rectangle, textAttributes: TextAttributes) {
                val g2d = g as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                
                // Draw background
                g2d.color = JBColor(
                    java.awt.Color(92, 225, 92, 60),   // Light green
                    java.awt.Color(59, 91, 59, 60)     // Dark green
                )
                g2d.fillRoundRect(targetRect.x, targetRect.y + 2, targetRect.width, targetRect.height - 4, 6, 6)
                
                // Draw text
                g2d.color = JBColor(
                    java.awt.Color(34, 139, 34),
                    java.awt.Color(152, 251, 152)
                )
                g2d.font = getEditorFont(inlay.editor).deriveFont(Font.ITALIC)
                val metrics = g2d.fontMetrics
                g2d.drawString(" ✨ Tab to accept additions ", targetRect.x + 5, targetRect.y + metrics.ascent)
            }
        }
    }
    
    /**
     * Render full diff with modifications/deletions using the configured view
     */
    private fun renderFullDiff(
        context: RenderingContext,
        originalText: String,
        modifiedText: String,
        lineDiff: WordDiffUtil.LineDiffResult
    ) {
        val config = DiffRenderingConfig.getInstance()
        
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
        
        // Use lines() but manually handle the trailing newline case  
        val originalLines = originalText.lines().toMutableList()
        val modifiedLines = modifiedText.lines().toMutableList()
        
        // If text ends with newline, add an empty line that lines() removes
        if (originalText.endsWith("\n") && (originalLines.isEmpty() || originalLines.last().isNotEmpty())) {
            originalLines.add("")
        }
        if (modifiedText.endsWith("\n") && (modifiedLines.isEmpty() || modifiedLines.last().isNotEmpty())) {
            modifiedLines.add("")
        }
        
        // Create renderer based on user preference
        val renderer = if (config.useSideBySideView()) {
            createSideBySideMethodRenderer(
                originalLines,
                modifiedLines, 
                lineDiff,
                context.editor.colorsScheme,
                context.methodContext.language
            )
        } else {
            createWholeMethodRenderer(
                originalLines,
                modifiedLines, 
                lineDiff,
                context.editor.colorsScheme,
                context.methodContext.language
            )
        }
        
        // Determine where to insert the diff
        val inlayOffset = if (config.showDiffAtStart()) {
            // Insert at the beginning of the method
            methodStartOffset
        } else {
            // Insert at the end of the method
            methodEndOffset
        }
        
        // Insert the diff rendering at the calculated position
        val inlay = context.editor.inlayModel.addBlockElement(
            inlayOffset,
            true,
            config.showDiffAtStart(), // showAbove: true if at start, false if at end
            0,
            renderer
        )
        
        if (inlay != null) {
            context.additionInlays.add(inlay)
            context.mainDiffInlay = inlay
            val viewType = if (config.useSideBySideView()) "side-by-side" else "color-coded"
            val position = if (config.showDiffAtStart()) "start" else "end"
            debugLog("Successfully rendered $viewType method diff at $position")
            
            // Handle diff visibility based on configuration
            when {
                config.autoScrollToDiff() -> {
                    // Automatically scroll to the diff
                    scrollToDiff(context.editor, inlay)
                }
                config.showFloatingDiffButton() -> {
                    // Show a floating button to jump to diff
                    showFloatingDiffButton(context)
                }
                else -> {
                    // Show a temporary hint at cursor position
                    showDiffHint(context.editor, inlayOffset)
                }
            }
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
     * Used for addition-only diffs and multi-line additions
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
                return lines.size * metrics.height + 4 // Minimal padding
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
                    yPos += metrics.height
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
        
        debugLog("Rendering line addition at document line $insertAtDocumentLine: ${rewrittenLine.take(50)}")
        
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
                debugLog("Successfully created inlay at offset $insertOffset")
            } else {
                debugLog("Failed to create inlay at offset $insertOffset")
            }
        } else {
            debugLog("Invalid insertion line: $insertAtDocumentLine (document has ${document.lineCount} lines)")
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
     * Used for single-line additions in addition-only diffs
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
        val originalLines = context.methodContext.methodContent.lines().toMutableList()
        if (context.methodContext.methodContent.endsWith("\n") && (originalLines.isEmpty() || originalLines.last().isNotEmpty())) {
            originalLines.add("")
        }
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
     * Manually scroll to the diff (can be called from outside)
     */
    fun scrollToCurrentDiff() {
        val context = currentRenderingContext ?: return
        val inlay = context.mainDiffInlay ?: return
        
        if (inlay.isValid) {
            scrollToDiff(context.editor, inlay)
        }
    }
    
    /**
     * Show a floating button to jump to the diff
     */
    private fun showFloatingDiffButton(context: RenderingContext) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val config = DiffRenderingConfig.getInstance()
                val cursorOffset = context.editor.caretModel.offset
                
                // Create floating button renderer
                val buttonRenderer = createFloatingButtonRenderer(
                    if (config.showDiffAtStart()) "↑" else "↓",
                    "Jump to diff",
                    context.editor.colorsScheme
                ) {
                    // On click, scroll to the diff
                    context.mainDiffInlay?.let { scrollToDiff(context.editor, it) }
                }
                
                // Place the button after the current line
                val document = context.editor.document
                val currentLine = document.getLineNumber(cursorOffset)
                val lineEndOffset = document.getLineEndOffset(currentLine)
                
                val buttonInlay = context.editor.inlayModel.addBlockElement(
                    lineEndOffset,
                    false,
                    true,
                    1, // Higher priority to show above other elements
                    buttonRenderer
                )
                
                if (buttonInlay != null) {
                    context.floatingButtonInlay = buttonInlay
                    // Don't add to additionInlays to handle disposal separately
                }
            } catch (e: Exception) {
                logger.warn("Failed to show floating diff button", e)
            }
        }
    }
    
    /**
     * Create a clickable floating button renderer
     */
    private fun createFloatingButtonRenderer(
        icon: String,
        tooltip: String,
        scheme: EditorColorsScheme,
        onClick: () -> Unit
    ): EditorCustomElementRenderer {
        return object : EditorCustomElementRenderer {
            private val buttonWidth = 120
            private val buttonHeight = 30
            
            override fun calcWidthInPixels(inlay: Inlay<*>): Int = buttonWidth
            
            override fun calcHeightInPixels(inlay: Inlay<*>): Int = buttonHeight + 10
            
            override fun paint(inlay: Inlay<*>, g: Graphics, targetRect: Rectangle, textAttributes: TextAttributes) {
                val g2d = g as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                
                // Calculate button position (right-aligned)
                val buttonX = targetRect.x + targetRect.width - buttonWidth - 20
                val buttonY = targetRect.y + 5
                
                // Draw button background
                g2d.color = JBColor(
                    java.awt.Color(100, 149, 237, 200),  // Semi-transparent blue
                    java.awt.Color(70, 130, 180, 200)
                )
                g2d.fillRoundRect(buttonX, buttonY, buttonWidth, buttonHeight, 8, 8)
                
                // Draw button border
                g2d.color = JBColor(
                    java.awt.Color(70, 130, 180),
                    java.awt.Color(135, 206, 235)
                )
                g2d.drawRoundRect(buttonX, buttonY, buttonWidth, buttonHeight, 8, 8)
                
                // Draw text
                g2d.color = java.awt.Color.WHITE
                g2d.font = getEditorFont(inlay.editor).deriveFont(Font.BOLD)
                val metrics = g2d.fontMetrics
                val text = "$icon $tooltip"
                val textX = buttonX + (buttonWidth - metrics.stringWidth(text)) / 2
                val textY = buttonY + (buttonHeight + metrics.ascent - metrics.descent) / 2
                g2d.drawString(text, textX, textY)
            }
        }
    }
    
    /**
     * Show a hint that diff is available
     */
    private fun showDiffHint(editor: Editor, diffOffset: Int) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val config = DiffRenderingConfig.getInstance()
                val position = if (config.showDiffAtStart()) "above" else "below"
                
                // Create a temporary inlay hint at cursor position
                val cursorOffset = editor.caretModel.offset
                val hintRenderer = createDiffHintRenderer(position, editor.colorsScheme)
                
                val hintInlay = editor.inlayModel.addInlineElement(
                    cursorOffset,
                    true,
                    hintRenderer
                )
                
                if (hintInlay != null) {
                    // Auto-remove the hint after 3 seconds
                    ApplicationManager.getApplication().executeOnPooledThread {
                        Thread.sleep(3000)
                        ApplicationManager.getApplication().invokeLater {
                            if (!hintInlay.isValid) return@invokeLater
                            Disposer.dispose(hintInlay)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to show diff hint", e)
            }
        }
    }
    
    /**
     * Create a hint renderer that shows where the diff is
     */
    private fun createDiffHintRenderer(position: String, scheme: EditorColorsScheme): EditorCustomElementRenderer {
        return object : EditorCustomElementRenderer {
            override fun calcWidthInPixels(inlay: Inlay<*>): Int {
                val font = getEditorFont(inlay.editor).deriveFont(Font.ITALIC)
                val metrics = inlay.editor.contentComponent.getFontMetrics(font)
                return metrics.stringWidth(" ← Method diff $position ") + 10
            }
            
            override fun calcHeightInPixels(inlay: Inlay<*>): Int {
                return inlay.editor.lineHeight
            }
            
            override fun paint(inlay: Inlay<*>, g: Graphics, targetRect: Rectangle, textAttributes: TextAttributes) {
                val g2d = g as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                
                // Draw background
                g2d.color = JBColor(
                    java.awt.Color(255, 235, 59, 80),  // Light yellow
                    java.awt.Color(107, 99, 50, 80)    // Dark yellow
                )
                g2d.fillRoundRect(targetRect.x, targetRect.y + 2, targetRect.width, targetRect.height - 4, 6, 6)
                
                // Draw text
                g2d.color = scheme.defaultForeground
                g2d.font = getEditorFont(inlay.editor).deriveFont(Font.ITALIC)
                val metrics = g2d.fontMetrics
                g2d.drawString(" ← Method diff $position ", targetRect.x + 5, targetRect.y + metrics.ascent)
            }
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
    
    /**
     * Scroll editor to make the diff inlay visible
     */
    private fun scrollToDiff(editor: Editor, inlay: Inlay<*>) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val config = DiffRenderingConfig.getInstance()
                val inlayOffset = inlay.offset
                
                // Move caret to the diff location
                editor.caretModel.moveToOffset(inlayOffset)
                
                // Scroll to make the diff visible
                if (config.showDiffAtStart()) {
                    // If diff is at start, scroll to show it with some context above
                    editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
                    
                    // Then adjust to show a bit of the original method above
                    val currentY = editor.scrollingModel.verticalScrollOffset
                    val adjustment = editor.lineHeight * 2 // Show 2 lines above
                    editor.scrollingModel.scrollVertically(Math.max(0, currentY - adjustment))
                } else {
                    // If diff is at end, center it in the view if possible
                    editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                }
                
                // Flash the caret to draw attention
                val caretModel = editor.caretModel
                caretModel.addCaretListener(object : com.intellij.openapi.editor.event.CaretListener {
                    override fun caretPositionChanged(event: com.intellij.openapi.editor.event.CaretEvent) {
                        // Remove listener after first position change
                        caretModel.removeCaretListener(this)
                    }
                })
                
                debugLog("Scrolled to diff at offset $inlayOffset")
            } catch (e: Exception) {
                logger.warn("Failed to scroll to diff", e)
            }
        }
    }
}
