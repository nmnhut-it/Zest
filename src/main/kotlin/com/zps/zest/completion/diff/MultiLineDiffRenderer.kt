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
    private val isUnchanged: Boolean = false,
    private val config: DiffRenderingConfig = DiffRenderingConfig.getInstance()
) : EditorCustomElementRenderer {
    
    data class WrappedLine(
        val segments: List<String>,
        val isWrapped: Boolean = false
    )
    
    companion object {
        private const val ARROW_TEXT = " → "
        private const val PADDING = 15
        private const val LINE_PADDING = 6
        private const val HEADER_HEIGHT = 30
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
        val columnWidth = calculateColumnWidth(editorWidth)
        
        // Always wrap lines for accurate height calculation
        val wrappedOriginal = wrapLines(originalLines, font, columnWidth - PADDING, metrics)
        val wrappedModified = wrapLines(modifiedLines, font, columnWidth - PADDING, metrics)
        
        val totalLines = max(
            wrappedOriginal.sumOf { it.segments.size },
            wrappedModified.sumOf { it.segments.size }
        )
        
        // Include header height
        return totalLines * lineHeight + (PADDING * 2) + HEADER_HEIGHT
    }
    
    private fun calculateColumnWidth(editorWidth: Int): Int {
        // Calculate usable width for each column
        val totalPadding = PADDING * 4 // Left, middle gaps, right padding
        val arrowWidth = 40 // Space for arrow
        val separatorWidth = 20 // Space for visual separation
        val availableWidth = editorWidth - totalPadding - arrowWidth - separatorWidth
        
        // Each column gets roughly half of the available width
        return (availableWidth * 0.45).toInt()
    }
    
    override fun paint(inlay: Inlay<*>, g: Graphics, targetRect: Rectangle, textAttributes: TextAttributes) {
        val g2d = g as Graphics2D
        setupGraphics(g2d)
        
        // Clear background
        g2d.color = scheme.defaultBackground
        g2d.fillRect(targetRect.x, targetRect.y, targetRect.width, targetRect.height)
        
        // Calculate layout
        val editorWidth = targetRect.width
        val columnWidth = calculateColumnWidth(editorWidth)
        val arrowX = targetRect.x + columnWidth + PADDING
        val rightColumnX = arrowX + g2d.fontMetrics.stringWidth(ARROW_TEXT) + PADDING
        val rightColumnWidth = columnWidth // Use same width for both columns
        
        // Fonts for each column
        val leftFont = getEditorFont(inlay.editor)
        val rightFont = leftFont.deriveFont(leftFont.size * config.getRightColumnFontSizeFactor())
        
        // Wrap lines for both columns - ensure soft wrapping is always enabled
        g2d.font = leftFont
        val leftMetrics = g2d.fontMetrics
        val wrappedOriginal = wrapLines(originalLines, leftFont, columnWidth - PADDING, leftMetrics)
        
        g2d.font = rightFont
        val rightMetrics = g2d.fontMetrics
        val wrappedModified = wrapLines(modifiedLines, rightFont, rightColumnWidth - PADDING, rightMetrics)
        
        // Draw column headers
        g2d.color = JBColor(
            java.awt.Color(240, 240, 240), // Light theme
            java.awt.Color(40, 40, 40)      // Dark theme
        )
        g2d.fillRect(targetRect.x, targetRect.y, targetRect.width, HEADER_HEIGHT)
        
        // Draw header text
        g2d.font = leftFont.deriveFont(Font.BOLD)
        g2d.color = scheme.defaultForeground
        g2d.drawString("Original", targetRect.x + PADDING, targetRect.y + HEADER_HEIGHT - 8)
        g2d.drawString("Modified", rightColumnX, targetRect.y + HEADER_HEIGHT - 8)
        
        // Draw header separator line
        g2d.color = JBColor.GRAY
        g2d.drawLine(targetRect.x, targetRect.y + HEADER_HEIGHT, targetRect.x + targetRect.width, targetRect.y + HEADER_HEIGHT)
        
        // Draw both columns with clear separation
        var yPos = targetRect.y + HEADER_HEIGHT + PADDING
        val lineHeight = max(leftMetrics.height, rightMetrics.height) + LINE_PADDING
        
        // Draw column background for better visibility
        g2d.color = JBColor(
            java.awt.Color(250, 250, 250), // Light theme: very light gray
            java.awt.Color(30, 30, 30)      // Dark theme: slightly lighter than background
        )
        g2d.fillRect(targetRect.x, targetRect.y + HEADER_HEIGHT, columnWidth + PADDING, targetRect.height - HEADER_HEIGHT)
        g2d.fillRect(rightColumnX - PADDING/2, targetRect.y + HEADER_HEIGHT, rightColumnWidth + PADDING, targetRect.height - HEADER_HEIGHT)
        
        // Draw vertical separator line
        g2d.color = JBColor.GRAY
        val separatorX = arrowX - PADDING/2
        g2d.drawLine(separatorX, targetRect.y, separatorX, targetRect.y + targetRect.height)
        
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
                
                // Draw arrow for every line (not just first)
                g2d.font = leftFont
                g2d.color = scheme.defaultForeground
                g2d.drawString(ARROW_TEXT, arrowX, currentY)
                
                // Draw left column (original)
                if (originalGroup != null && j < originalGroup.segments.size) {
                    g2d.font = leftFont
                    val segment = originalGroup.segments[j]
                    
                    // For unchanged lines, don't use deletion styling
                    if (isUnchanged) {
                        // Unchanged - draw normally
                        g2d.color = scheme.defaultForeground
                        g2d.drawString(segment, targetRect.x + PADDING, currentY)
                    } else {
                        // Changed - draw with deletion styling
                        drawDeletionText(
                            g2d, 
                            segment, 
                            targetRect.x + PADDING, 
                            currentY,
                            columnWidth - PADDING,
                            leftMetrics.height
                        )
                    }
                } else if (originalLines.isEmpty() && modifiedLines.isNotEmpty()) {
                    // Pure addition - leave left column empty
                    g2d.color = JBColor.GRAY
                    g2d.drawString("", targetRect.x + PADDING, currentY)
                }
                
                // Draw right column (modified) with ghost text
                if (modifiedGroup != null && j < modifiedGroup.segments.size) {
                    g2d.font = rightFont
                    val segment = modifiedGroup.segments[j]
                    
                    // For unchanged lines, draw normally instead of ghost text
                    if (isUnchanged) {
                        g2d.color = scheme.defaultForeground
                        g2d.drawString(segment, rightColumnX, currentY)
                    } else {
                        drawGhostText(
                            g2d,
                            segment,
                            rightColumnX,
                            currentY
                        )
                    }
                } else if (modifiedLines.isEmpty() && originalLines.isNotEmpty()) {
                    // Pure deletion - leave right column empty
                    g2d.color = JBColor.GRAY
                    g2d.drawString("", rightColumnX, currentY)
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
        if (line.isEmpty()) {
            return WrappedLine(listOf(line))
        }
        
        // Check if line fits within maxWidth
        if (metrics.stringWidth(line) <= maxWidth) {
            return WrappedLine(listOf(line))
        }
        
        // Need to wrap the line
        val segments = mutableListOf<String>()
        val words = tokenizeForWrapping(line)
        val currentLine = StringBuilder()
        var currentWidth = 0
        
        // Preserve leading whitespace
        val leadingWhitespace = line.takeWhile { it.isWhitespace() }
        if (leadingWhitespace.isNotEmpty()) {
            currentLine.append(leadingWhitespace)
            currentWidth = metrics.stringWidth(leadingWhitespace)
        }
        
        // Create continuation indent
        val continuationIndent = leadingWhitespace + "  "
        var isFirstSegment = true
        
        for (word in words) {
            val wordWidth = metrics.stringWidth(word)
            val spaceWidth = if (currentLine.isNotEmpty() && !currentLine.endsWith(" ") && !word.startsWith(" ")) {
                metrics.charWidth(' ')
            } else {
                0
            }
            
            // Check if adding this word would exceed the width
            if (currentWidth + spaceWidth + wordWidth > maxWidth && currentLine.isNotEmpty()) {
                // Save current line
                segments.add(currentLine.toString())
                currentLine.clear()
                isFirstSegment = false
                
                // Start new line with continuation indent
                currentLine.append(continuationIndent)
                currentWidth = metrics.stringWidth(continuationIndent)
            }
            
            // Add space if needed
            if (currentLine.isNotEmpty() && !currentLine.endsWith(" ") && !word.startsWith(" ")) {
                currentLine.append(" ")
                currentWidth += metrics.charWidth(' ')
            }
            
            // Add the word
            currentLine.append(word)
            currentWidth += wordWidth
        }
        
        // Add the last segment
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
