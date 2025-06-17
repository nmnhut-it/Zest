package com.zps.zest.completion.diff

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.*
import kotlin.math.max

/**
 * Renderer for multi-line side-by-side diffs with proper text wrapping and arrow alignment
 */
class MultiLineDiffRenderer(
    private val originalLines: List<String>,
    private val modifiedLines: List<String>,
    private val scheme: EditorColorsScheme,
    private val language: String?,
    private val config: DiffRenderingConfig = DiffRenderingConfig.getInstance()
) : EditorCustomElementRenderer {
    
    data class WrappedLine(
        val segments: List<String>,
        val isWrapped: Boolean = false
    )
    
    companion object {
        private const val ARROW_TEXT = " → "
        private const val PADDING = 10
        private const val LINE_PADDING = 4
    }
    
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        // Use full editor width for multi-line rendering
        return inlay.editor.contentComponent.width - (PADDING * 2)
    }
    
    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val font = getEditorFont(inlay.editor)
        val metrics = inlay.editor.contentComponent.getFontMetrics(font)
        val lineHeight = metrics.height + LINE_PADDING
        
        // Calculate wrapped lines for both columns
        val editorWidth = inlay.editor.contentComponent.width
        val columnWidth = (editorWidth.toDouble() * config.getMaxColumnWidthPercentage()).toInt()
        
        val wrappedOriginal = wrapLines(originalLines, font, columnWidth, metrics)
        val wrappedModified = wrapLines(modifiedLines, font, columnWidth, metrics)
        
        val totalLines = max(
            wrappedOriginal.sumOf { it.segments.size },
            wrappedModified.sumOf { it.segments.size }
        )
        
        return totalLines * lineHeight + (PADDING * 2)
    }
    
    override fun paint(inlay: Inlay<*>, g: Graphics, targetRect: Rectangle, textAttributes: TextAttributes) {
        val g2d = g as Graphics2D
        setupGraphics(g2d)
        
        // Clear background
        g2d.color = scheme.defaultBackground
        g2d.fillRect(targetRect.x, targetRect.y, targetRect.width, targetRect.height)
        
        // Calculate layout
        val editorWidth = targetRect.width
        val columnWidth = (editorWidth.toDouble() * config.getMaxColumnWidthPercentage()).toInt()
        val arrowX = targetRect.x + columnWidth + PADDING
        val rightColumnX = arrowX + g2d.fontMetrics.stringWidth(ARROW_TEXT) + PADDING
        
        // Fonts for each column
        val leftFont = getEditorFont(inlay.editor)
        val rightFont = leftFont.deriveFont(leftFont.size * config.getRightColumnFontSizeFactor())
        
        // Wrap lines for both columns if enabled
        g2d.font = leftFont
        val leftMetrics = g2d.fontMetrics
        val wrappedOriginal = if (config.isSmartLineWrappingEnabled()) {
            wrapLines(originalLines, leftFont, columnWidth, leftMetrics)
        } else {
            originalLines.map { WrappedLine(listOf(it)) }
        }
        
        g2d.font = rightFont
        val rightMetrics = g2d.fontMetrics
        val wrappedModified = if (config.isSmartLineWrappingEnabled()) {
            wrapLines(modifiedLines, rightFont, columnWidth - rightColumnX + targetRect.x, rightMetrics)
        } else {
            modifiedLines.map { WrappedLine(listOf(it)) }
        }
        
        // Draw both columns
        var yPos = targetRect.y + PADDING
        val lineHeight = max(leftMetrics.height, rightMetrics.height) + LINE_PADDING
        
        // Find max lines to ensure proper alignment
        val maxLineGroups = max(wrappedOriginal.size, wrappedModified.size)
        
        for (i in 0 until maxLineGroups) {
            val originalGroup = wrappedOriginal.getOrNull(i)
            val modifiedGroup = wrappedModified.getOrNull(i)
            
            val maxSegments = max(
                originalGroup?.segments?.size ?: 0,
                modifiedGroup?.segments?.size ?: 0
            )
            
            // Draw each segment line
            for (j in 0 until maxSegments) {
                val currentY = yPos + leftMetrics.ascent
                
                // Draw left column (original)
                if (originalGroup != null && j < originalGroup.segments.size) {
                    g2d.font = leftFont
                    val segment = originalGroup.segments[j]
                    
                    // Draw deletion styling
                    drawDeletionText(
                        g2d, 
                        segment, 
                        targetRect.x + PADDING, 
                        currentY,
                        columnWidth,
                        leftMetrics.height
                    )
                }
                
                // Draw arrow (only on first line of each logical line)
                if (j == 0) {
                    g2d.font = leftFont
                    g2d.color = scheme.defaultForeground
                    g2d.drawString(ARROW_TEXT, arrowX, currentY)
                }
                
                // Draw right column (modified) with ghost text
                if (modifiedGroup != null && j < modifiedGroup.segments.size) {
                    g2d.font = rightFont
                    val segment = modifiedGroup.segments[j]
                    
                    drawGhostText(
                        g2d,
                        segment,
                        rightColumnX,
                        currentY
                    )
                }
                
                yPos += lineHeight
            }
        }
    }
    
    private fun setupGraphics(g2d: Graphics2D) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }
    
    private fun wrapLines(
        lines: List<String>, 
        font: Font, 
        maxWidth: Int,
        metrics: FontMetrics
    ): List<WrappedLine> {
        return lines.map { line ->
            wrapLine(line, font, maxWidth, metrics)
        }
    }
    
    private fun wrapLine(
        line: String,
        font: Font,
        maxWidth: Int,
        metrics: FontMetrics
    ): WrappedLine {
        if (line.isEmpty() || metrics.stringWidth(line) <= maxWidth) {
            return WrappedLine(listOf(line))
        }
        
        val segments = mutableListOf<String>()
        val words = if (config.shouldWrapAtOperators()) {
            tokenizeForWrapping(line)
        } else {
            // Simple space-based splitting
            line.split(Regex("\\s+"))
        }
        
        val currentLine = StringBuilder()
        var currentWidth = 0
        val spaceWidth = metrics.charWidth(' ')
        
        // Preserve leading whitespace
        val leadingWhitespace = line.takeWhile { it.isWhitespace() }
        if (leadingWhitespace.isNotEmpty()) {
            currentLine.append(leadingWhitespace)
            currentWidth = metrics.stringWidth(leadingWhitespace)
        }
        
        // Create continuation indent
        val continuationIndent = " ".repeat(config.getContinuationIndentSize()) + leadingWhitespace
        
        for (word in words) {
            val wordWidth = metrics.stringWidth(word)
            
            if (currentWidth + wordWidth > maxWidth && currentLine.isNotEmpty()) {
                // Need to wrap
                segments.add(currentLine.toString())
                currentLine.clear()
                
                // Add continuation indent
                currentLine.append(continuationIndent)
                currentWidth = metrics.stringWidth(continuationIndent)
            }
            
            if (currentLine.isNotEmpty() && !currentLine.endsWith(" ") && !word.startsWith(" ")) {
                currentLine.append(" ")
                currentWidth += spaceWidth
            }
            
            currentLine.append(word)
            currentWidth += wordWidth
        }
        
        if (currentLine.isNotEmpty()) {
            segments.add(currentLine.toString())
        }
        
        return WrappedLine(segments, segments.size > 1)
    }
    
    private fun tokenizeForWrapping(line: String): List<String> {
        // Smart tokenization that prefers breaking at certain characters
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        
        for (char in line) {
            when (char) {
                ' ', '\t' -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                    tokens.add(char.toString())
                }
                ',', ';', ')', ']', '}' -> {
                    current.append(char)
                    tokens.add(current.toString())
                    current.clear()
                }
                '(', '[', '{', '.', '+', '-', '*', '/', '=', '<', '>', '&', '|' -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                    current.append(char)
                }
                else -> current.append(char)
            }
        }
        
        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }
        
        return tokens
    }
    
    private fun drawDeletionText(
        g2d: Graphics2D,
        text: String,
        x: Int,
        y: Int,
        maxWidth: Int,
        lineHeight: Int
    ) {
        val textWidth = g2d.fontMetrics.stringWidth(text)
        val actualWidth = minOf(textWidth, maxWidth)
        
        // Draw deletion background
        g2d.color = getDeletionBackgroundColor()
        g2d.fillRect(x, y - g2d.fontMetrics.ascent, actualWidth, lineHeight)
        
        // Draw text
        g2d.color = scheme.defaultForeground
        g2d.drawString(text, x, y)
        
        // Draw strikethrough
        g2d.color = getDeletionEffectColor()
        val strikeY = y - g2d.fontMetrics.height / 3
        g2d.drawLine(x, strikeY, x + actualWidth, strikeY)
    }
    
    private fun drawGhostText(
        g2d: Graphics2D,
        text: String,
        x: Int,
        y: Int
    ) {
        val originalComposite = g2d.composite
        val ghostAlpha = config.getGhostTextAlpha(!JBColor.isBright())
        
        g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, ghostAlpha)
        g2d.color = getGhostTextColor()
        g2d.drawString(text, x, y)
        
        g2d.composite = originalComposite
    }
    
    private fun getDeletionBackgroundColor(): Color {
        return JBColor(
            Color(255, 220, 220), // Light theme
            Color(92, 22, 36)     // Dark theme
        )
    }
    
    private fun getDeletionEffectColor(): Color {
        return JBColor(
            Color(255, 85, 85),   // Light theme
            Color(248, 81, 73)    // Dark theme
        )
    }
    
    private fun getGhostTextColor(): Color {
        return if (JBColor.isBright()) {
            Color(60, 60, 60)     // Light theme
        } else {
            Color(180, 180, 180)  // Dark theme
        }
    }
    
    private fun getEditorFont(editor: Editor): Font {
        return UIUtil.getFontWithFallbackIfNeeded(
            scheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN),
            "sample"
        )
    }
}
