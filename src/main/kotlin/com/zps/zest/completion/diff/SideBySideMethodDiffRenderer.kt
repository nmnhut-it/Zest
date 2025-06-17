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
import kotlin.math.max

/**
 * Renders old and new method side by side with subtle coloring for changes
 */
class SideBySideMethodDiffRenderer(
    private val originalLines: List<String>,
    private val modifiedLines: List<String>,
    private val lineDiff: WordDiffUtil.LineDiffResult,
    private val scheme: EditorColorsScheme,
    private val language: String
) : EditorCustomElementRenderer {
    
    private val config = DiffRenderingConfig.getInstance()
    
    // Line alignment based on diff blocks
    private data class LineAlignment(
        val leftLineIndex: Int?,  // null means this line is deleted/empty on left side
        val rightLineIndex: Int?, // null means this line is added/empty on right side
        val changeType: WordDiffUtil.BlockType
    )
    
    private val lineAlignments: List<LineAlignment> = calculateLineAlignments()
    
    private fun calculateLineAlignments(): List<LineAlignment> {
        val alignments = mutableListOf<LineAlignment>()
        
        for (block in lineDiff.blocks) {
            when (block.type) {
                WordDiffUtil.BlockType.UNCHANGED, WordDiffUtil.BlockType.MODIFIED -> {
                    // Lines exist on both sides
                    val leftCount = block.originalEndLine - block.originalStartLine
                    val rightCount = block.modifiedEndLine - block.modifiedStartLine
                    val maxCount = max(leftCount, rightCount)
                    
                    for (i in 0 until maxCount) {
                        val leftIdx = if (i < leftCount) block.originalStartLine + i else null
                        val rightIdx = if (i < rightCount) block.modifiedStartLine + i else null
                        alignments.add(LineAlignment(leftIdx, rightIdx, block.type))
                    }
                }
                WordDiffUtil.BlockType.DELETED -> {
                    // Lines only on left side
                    for (i in block.originalStartLine until block.originalEndLine) {
                        alignments.add(LineAlignment(i, null, block.type))
                    }
                }
                WordDiffUtil.BlockType.ADDED -> {
                    // Lines only on right side
                    for (i in block.modifiedStartLine until block.modifiedEndLine) {
                        alignments.add(LineAlignment(null, i, block.type))
                    }
                }
            }
        }
        
        return alignments
    }
    
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        // Use full editor width
        return inlay.editor.contentComponent.width - 20
    }
    
    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        val font = getEditorFont(inlay.editor)
        val metrics = inlay.editor.contentComponent.getFontMetrics(font)
        val lineHeight = metrics.height + 2
        
        // Height based on number of aligned lines plus padding
        return lineAlignments.size * lineHeight + 20
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
        
        // Calculate column widths
        val dividerWidth = 20
        val columnWidth = (targetRect.width - dividerWidth) / 2
        val leftColumnX = targetRect.x
        val rightColumnX = targetRect.x + columnWidth + dividerWidth
        
        // Draw header
        g2d.font = font.deriveFont(Font.BOLD)
        g2d.color = getHeaderColor()
        var yPos = targetRect.y + metrics.ascent + 5
        g2d.drawString("Original", leftColumnX + 10, yPos)
        g2d.drawString("Modified", rightColumnX + 10, yPos)
        g2d.font = font
        
        // Draw separator line
        yPos += 5
        g2d.color = getSeparatorColor()
        g2d.drawLine(targetRect.x, yPos, targetRect.x + targetRect.width, yPos)
        yPos += metrics.height
        
        // Draw each aligned line pair
        val lineHeight = metrics.height + 2
        
        for (alignment in lineAlignments) {
            // Determine background colors based on change type
            val (leftBgColor, rightBgColor) = when (alignment.changeType) {
                WordDiffUtil.BlockType.DELETED -> {
                    (getDeletionBackgroundColor() to null)
                }
                WordDiffUtil.BlockType.ADDED -> {
                    (null to getAdditionBackgroundColor())
                }
                WordDiffUtil.BlockType.MODIFIED -> {
                    (getModificationBackgroundColor() to getModificationBackgroundColor())
                }
                WordDiffUtil.BlockType.UNCHANGED -> {
                    (null to null)
                }
            }
            
            // Draw left side (original)
            if (alignment.leftLineIndex != null) {
                val line = originalLines.getOrNull(alignment.leftLineIndex) ?: ""
                
                // Draw background if needed
                if (leftBgColor != null) {
                    g2d.color = leftBgColor
                    g2d.fillRect(leftColumnX, yPos - metrics.ascent, columnWidth, lineHeight)
                }
                
                // Draw line number
                g2d.color = getLineNumberColor()
                g2d.font = font.deriveFont(Font.PLAIN, font.size * 0.9f)
                val lineNumStr = "${alignment.leftLineIndex + 1}"
                val lineNumWidth = metrics.stringWidth("9999")
                g2d.drawString(lineNumStr, leftColumnX + lineNumWidth - metrics.stringWidth(lineNumStr), yPos)
                g2d.font = font
                
                // Draw text with word-level diff if enabled and line is modified
                if (alignment.changeType == WordDiffUtil.BlockType.MODIFIED && 
                    config.isWordLevelDiffEnabled() && 
                    alignment.leftLineIndex != null && 
                    alignment.rightLineIndex != null) {
                    val origLine = originalLines.getOrNull(alignment.leftLineIndex) ?: ""
                    val modLine = modifiedLines.getOrNull(alignment.rightLineIndex) ?: ""
                    val wordDiff = WordDiffUtil.diffWords(origLine, modLine, language)
                    
                    // Draw original line segments
                    var xPos = leftColumnX + lineNumWidth + 10
                    for (segment in wordDiff.originalSegments) {
                        when (segment.type) {
                            WordDiffUtil.ChangeType.DELETED, WordDiffUtil.ChangeType.MODIFIED -> {
                                // Highlight deleted/modified parts
                                val segmentWidth = metrics.stringWidth(segment.text)
                                g2d.color = getWordDiffHighlightColor()
                                g2d.fillRect(xPos, yPos - metrics.ascent + 2, segmentWidth, metrics.height - 4)
                                g2d.color = scheme.defaultForeground
                                g2d.drawString(segment.text, xPos, yPos)
                                xPos += segmentWidth
                            }
                            else -> {
                                g2d.color = scheme.defaultForeground
                                g2d.drawString(segment.text, xPos, yPos)
                                xPos += metrics.stringWidth(segment.text)
                            }
                        }
                        
                        // Stop if we exceed column width
                        if (xPos > leftColumnX + columnWidth - 20) break
                    }
                } else {
                    g2d.color = scheme.defaultForeground
                    drawTextWithClipping(g2d, line, leftColumnX + lineNumWidth + 10, yPos, columnWidth - lineNumWidth - 20)
                }
            }
            
            // Draw divider
            g2d.color = getSeparatorColor()
            g2d.drawLine(leftColumnX + columnWidth, targetRect.y, leftColumnX + columnWidth, targetRect.y + targetRect.height)
            
            // Draw right side (modified)
            if (alignment.rightLineIndex != null) {
                val line = modifiedLines.getOrNull(alignment.rightLineIndex) ?: ""
                
                // Draw background if needed
                if (rightBgColor != null) {
                    g2d.color = rightBgColor
                    g2d.fillRect(rightColumnX, yPos - metrics.ascent, columnWidth, lineHeight)
                }
                
                // Draw line number
                g2d.color = getLineNumberColor()
                g2d.font = font.deriveFont(Font.PLAIN, font.size * 0.9f)
                val lineNumStr = "${alignment.rightLineIndex + 1}"
                val lineNumWidth = metrics.stringWidth("9999")
                g2d.drawString(lineNumStr, rightColumnX + lineNumWidth - metrics.stringWidth(lineNumStr), yPos)
                g2d.font = font
                
                // Draw text with word-level diff if enabled and line is modified
                if (alignment.changeType == WordDiffUtil.BlockType.MODIFIED && 
                    config.isWordLevelDiffEnabled() && 
                    alignment.leftLineIndex != null && 
                    alignment.rightLineIndex != null) {
                    val origLine = originalLines.getOrNull(alignment.leftLineIndex) ?: ""
                    val modLine = modifiedLines.getOrNull(alignment.rightLineIndex) ?: ""
                    val wordDiff = WordDiffUtil.diffWords(origLine, modLine, language)
                    
                    // Draw modified line segments
                    var xPos = rightColumnX + lineNumWidth + 10
                    for (segment in wordDiff.modifiedSegments) {
                        when (segment.type) {
                            WordDiffUtil.ChangeType.ADDED, WordDiffUtil.ChangeType.MODIFIED -> {
                                // Highlight added/modified parts
                                val segmentWidth = metrics.stringWidth(segment.text)
                                g2d.color = getWordDiffHighlightColor()
                                g2d.fillRect(xPos, yPos - metrics.ascent + 2, segmentWidth, metrics.height - 4)
                                g2d.color = scheme.defaultForeground
                                g2d.drawString(segment.text, xPos, yPos)
                                xPos += segmentWidth
                            }
                            else -> {
                                g2d.color = scheme.defaultForeground
                                g2d.drawString(segment.text, xPos, yPos)
                                xPos += metrics.stringWidth(segment.text)
                            }
                        }
                        
                        // Stop if we exceed column width
                        if (xPos > rightColumnX + columnWidth - 20) break
                    }
                } else {
                    g2d.color = scheme.defaultForeground
                    drawTextWithClipping(g2d, line, rightColumnX + lineNumWidth + 10, yPos, columnWidth - lineNumWidth - 20)
                }
            }
            
            yPos += lineHeight
        }
        
        // Draw border
        g2d.color = getBorderColor()
        g2d.drawRect(targetRect.x, targetRect.y, targetRect.width - 1, targetRect.height - 1)
    }
    
    private fun drawTextWithClipping(g2d: Graphics2D, text: String, x: Int, y: Int, maxWidth: Int) {
        val metrics = g2d.fontMetrics
        var displayText = text
        
        // Trim text if it's too long
        if (metrics.stringWidth(text) > maxWidth) {
            val ellipsis = "..."
            val ellipsisWidth = metrics.stringWidth(ellipsis)
            var endIndex = text.length
            
            while (endIndex > 0 && metrics.stringWidth(text.substring(0, endIndex)) + ellipsisWidth > maxWidth) {
                endIndex--
            }
            
            displayText = text.substring(0, endIndex) + ellipsis
        }
        
        g2d.drawString(displayText, x, y)
    }
    
    private fun getAdditionBackgroundColor(): Color {
        val alpha = 0.08f  // Very subtle
        return if (JBColor.isBright()) {
            Color(92, 225, 92, (alpha * 255).toInt())
        } else {
            Color(59, 91, 59, (alpha * 255).toInt())
        }
    }
    
    private fun getDeletionBackgroundColor(): Color {
        val alpha = 0.08f  // Very subtle
        return if (JBColor.isBright()) {
            Color(255, 92, 92, (alpha * 255).toInt())
        } else {
            Color(92, 22, 36, (alpha * 255).toInt())
        }
    }
    
    private fun getModificationBackgroundColor(): Color {
        val alpha = 0.08f  // Very subtle
        return if (JBColor.isBright()) {
            Color(255, 235, 59, (alpha * 255).toInt())
        } else {
            Color(107, 99, 50, (alpha * 255).toInt())
        }
    }
    
    private fun getHeaderColor(): Color {
        return JBColor(
            Color(60, 60, 60),
            Color(200, 200, 200)
        )
    }
    
    private fun getSeparatorColor(): Color {
        return JBColor(
            Color(220, 220, 220),
            Color(60, 60, 60)
        )
    }
    
    private fun getLineNumberColor(): Color {
        return JBColor(
            Color(120, 120, 120),
            Color(140, 140, 140)
        )
    }
    
    private fun getBorderColor(): Color {
        return JBColor(
            Color(200, 200, 200, 100),
            Color(80, 80, 80, 100)
        )
    }
    
    private fun getWordDiffHighlightColor(): Color {
        val alpha = 0.25f  // More visible for word-level highlights
        return if (JBColor.isBright()) {
            Color(255, 200, 0, (alpha * 255).toInt())  // Orange highlight
        } else {
            Color(255, 180, 0, (alpha * 255).toInt())
        }
    }
    
    private fun getEditorFont(editor: Editor): Font {
        return UIUtil.getFontWithFallbackIfNeeded(
            scheme.getFont(EditorFontType.PLAIN),
            "sample"
        )
    }
}
