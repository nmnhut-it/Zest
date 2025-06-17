package com.zps.zest.completion.diff

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.*

/**
 * Renders the entire new method with color hints for changes
 */
class WholeMethodDiffRenderer(
    private val originalLines: List<String>,
    private val modifiedLines: List<String>,
    private val lineDiff: WordDiffUtil.LineDiffResult,
    private val scheme: EditorColorsScheme,
    private val language: String
) : EditorCustomElementRenderer {
    
    private val config = DiffRenderingConfig.getInstance()
    
    // Line status for coloring
    private enum class LineStatus {
        UNCHANGED,
        MODIFIED,
        ADDED
    }
    
    // Calculate line statuses based on diff
    private val lineStatuses: List<LineStatus> = calculateLineStatuses()
    
    private fun calculateLineStatuses(): List<LineStatus> {
        val statuses = MutableList(modifiedLines.size) { LineStatus.ADDED }
        
        // Process each diff block to determine line statuses
        for (block in lineDiff.blocks) {
            when (block.type) {
                WordDiffUtil.BlockType.UNCHANGED -> {
                    // Mark corresponding lines as unchanged
                    // Fix: use the actual line count from the block
                    for (i in 0 until block.modifiedLines.size) {
                        val lineIdx = block.modifiedStartLine + i
                        if (lineIdx in statuses.indices) {
                            statuses[lineIdx] = LineStatus.UNCHANGED
                        }
                    }
                }
                WordDiffUtil.BlockType.MODIFIED -> {
                    // Mark corresponding lines as modified
                    // Fix: use the actual line count from the block
                    for (i in 0 until block.modifiedLines.size) {
                        val lineIdx = block.modifiedStartLine + i
                        if (lineIdx in statuses.indices) {
                            statuses[lineIdx] = LineStatus.MODIFIED
                        }
                    }
                }
                WordDiffUtil.BlockType.ADDED -> {
                    // Lines are already marked as added by default
                    // But ensure they are within bounds
                    for (i in 0 until block.modifiedLines.size) {
                        val lineIdx = block.modifiedStartLine + i
                        if (lineIdx in statuses.indices) {
                            statuses[lineIdx] = LineStatus.ADDED
                        }
                    }
                }
                WordDiffUtil.BlockType.DELETED -> {
                    // Deleted lines don't appear in the new method
                }
            }
        }
        
        return statuses
    }
    
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        // Use full editor width
        return inlay.editor.contentComponent.width - 40
    }
    
    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val font = getEditorFont(inlay.editor)
        val metrics = inlay.editor.contentComponent.getFontMetrics(font)
        val lineHeight = metrics.height + 2
        return modifiedLines.size * lineHeight + 8 // Add padding
    }
    
    override fun paint(inlay: Inlay<*>, g: Graphics, targetRect: Rectangle, textAttributes: TextAttributes) {
        val g2d = g as Graphics2D
        val font = getEditorFont(inlay.editor)
        g2d.font = font
        val metrics = g2d.fontMetrics
        
        // Enable antialiasing
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        
        // Clear background
        g2d.color = scheme.defaultBackground
        g2d.fillRect(targetRect.x, targetRect.y, targetRect.width, targetRect.height)
        
        // Draw each line with appropriate coloring
        var yPos = targetRect.y + metrics.ascent + 4
        val lineHeight = metrics.height + 2
        val leftMargin = targetRect.x + 20
        
        modifiedLines.forEachIndexed { index, line ->
            val status = lineStatuses.getOrNull(index) ?: LineStatus.ADDED
            
            // Draw line background based on status
            when (status) {
                LineStatus.ADDED -> {
                    // Light green background for additions
                    g2d.color = getAdditionBackgroundColor()
                    g2d.fillRect(targetRect.x, yPos - metrics.ascent, targetRect.width, lineHeight)
                }
                LineStatus.MODIFIED -> {
                    // Light yellow background for modifications
                    g2d.color = getModificationBackgroundColor()
                    g2d.fillRect(targetRect.x, yPos - metrics.ascent, targetRect.width, lineHeight)
                }
                LineStatus.UNCHANGED -> {
                    // No special background for unchanged lines
                }
            }
            
            // Draw change indicator in the gutter
            g2d.font = font.deriveFont(Font.BOLD)
            when (status) {
                LineStatus.ADDED -> {
                    g2d.color = getAdditionIndicatorColor()
                    g2d.drawString("+", targetRect.x + 5, yPos)
                }
                LineStatus.MODIFIED -> {
                    g2d.color = getModificationIndicatorColor()
                    g2d.drawString("~", targetRect.x + 5, yPos)
                }
                LineStatus.UNCHANGED -> {
                    // No indicator for unchanged lines
                }
            }
            g2d.font = font // Reset font
            
            // Draw the line text
            g2d.color = scheme.defaultForeground
            g2d.drawString(line, leftMargin, yPos)
            
            yPos += lineHeight
        }
        
        // Draw a subtle border around the entire block
        g2d.color = getBorderColor()
        g2d.drawRect(targetRect.x, targetRect.y, targetRect.width - 1, targetRect.height - 1)
    }
    
    private fun getAdditionBackgroundColor(): Color {
        val alpha = if (config.useSubtleColors()) 0.1f else 0.15f
        return if (JBColor.isBright()) {
            Color(92, 225, 92, (alpha * 255).toInt()) // Light green
        } else {
            Color(59, 91, 59, (alpha * 255).toInt()) // Dark green
        }
    }
    
    private fun getModificationBackgroundColor(): Color {
        val alpha = if (config.useSubtleColors()) 0.1f else 0.15f
        return if (JBColor.isBright()) {
            Color(255, 235, 59, (alpha * 255).toInt()) // Light yellow
        } else {
            Color(107, 99, 50, (alpha * 255).toInt()) // Dark yellow
        }
    }
    
    private fun getAdditionIndicatorColor(): Color {
        return JBColor(
            Color(34, 139, 34), // Light theme: forest green
            Color(152, 251, 152) // Dark theme: pale green
        )
    }
    
    private fun getModificationIndicatorColor(): Color {
        return JBColor(
            Color(184, 134, 11), // Light theme: dark goldenrod
            Color(238, 203, 173) // Dark theme: peach puff
        )
    }
    
    private fun getBorderColor(): Color {
        return JBColor(
            Color(200, 200, 200, 100), // Light theme
            Color(80, 80, 80, 100) // Dark theme
        )
    }
    
    private fun getEditorFont(editor: Editor): Font {
        return UIUtil.getFontWithFallbackIfNeeded(
            scheme.getFont(EditorFontType.PLAIN),
            "sample"
        )
    }
}
