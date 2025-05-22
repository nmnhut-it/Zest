package com.zps.zest.autocomplete;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.impl.EditorImpl;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;

/**
 * Custom renderer for displaying autocomplete suggestions as inline inlays.
 * Renders completion text in a subtle, grayed-out style.
 */
public class ZestInlayRenderer implements EditorCustomElementRenderer {
    private final String completionText;
    private final boolean isMultiLine;
    private final String firstLine;
    private final Font font;
    private final Color textColor;
    
    public ZestInlayRenderer(String completionText, Editor editor) {
        this.completionText = completionText;
        this.isMultiLine = completionText.contains("\n");
        this.firstLine = isMultiLine 
            ? completionText.substring(0, completionText.indexOf('\n'))
            : completionText;
        
        // Get editor font and styling
        this.font =new Font(Font.SANS_SERIF, Font.ITALIC, 12);
        this.textColor = getCompletionTextColor(editor);
    }
    
    @Override
    public int calcWidthInPixels(@NotNull Inlay inlay) {
        Graphics2D g = (Graphics2D) inlay.getEditor().getContentComponent().getGraphics();
        if (g == null) {
            // Fallback calculation
            return firstLine.length() * 7; // Approximate character width
        }
        
        FontMetrics metrics = g.getFontMetrics(font);
        return metrics.stringWidth(firstLine);
    }
    
    @Override
    public int calcHeightInPixels(@NotNull Inlay inlay) {
        // For inline completions, we use the line height
        return inlay.getEditor().getLineHeight();
    }
    
    @Override
    public void paint(@NotNull Inlay inlay, @NotNull Graphics g, @NotNull Rectangle targetRegion, @NotNull TextAttributes textAttributes) {
        Graphics2D g2d = (Graphics2D) g.create();
        
        try {
            // Enable anti-aliasing for smoother text
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            
            // Set font and color
            g2d.setFont(font);
            g2d.setColor(textColor);
            
            // Calculate text position
            FontMetrics metrics = g2d.getFontMetrics();
            int textY = targetRegion.y + metrics.getAscent();
            
            // Draw the completion text
            if (isMultiLine) {
                drawMultiLineCompletion(g2d, targetRegion.x, textY, metrics, inlay);
            } else {
                g2d.drawString(firstLine, targetRegion.x, textY);
            }
            
        } finally {
            g2d.dispose();
        }
    }
    
    /**
     * Draws multi-line completion with visual indicators.
     */
    private void drawMultiLineCompletion(Graphics2D g2d, int x, int y, FontMetrics metrics, Inlay inlay) {
        // Draw the first line
        g2d.drawString(firstLine, x, y);
        
        // Add visual indicator for multi-line completion
        int indicatorX = x + metrics.stringWidth(firstLine) + 5;
        int indicatorY = y - metrics.getAscent() / 2;
        
        // Draw a small arrow or indicator
        g2d.setColor(textColor.darker());
        g2d.drawString("⋯", indicatorX, y); // Use ellipsis to indicate more content
        
        // Optionally show line count
        String[] lines = completionText.split("\n");
        if (lines.length > 2) {
            String lineCount = "+" + (lines.length - 1) + " lines";
            g2d.setFont(font.deriveFont(font.getSize() * 0.8f));
            g2d.drawString(lineCount, indicatorX + 15, y);
        }
    }
    
    /**
     * Gets the appropriate color for completion text based on editor theme.
     */
    private Color getCompletionTextColor(Editor editor) {
        // Try to get a subtle gray color that works with the current theme
        EditorColorsManager colorsManager = EditorColorsManager.getInstance();
        
        // Use comment color as base but make it even more subtle
        Color commentColor =EditorColors.PREVIEW_BORDER_COLOR.getDefaultColor();
        if (commentColor != null) {
            // Make it more transparent/subtle
            return new Color(
                commentColor.getRed(),
                commentColor.getGreen(), 
                commentColor.getBlue(),
                128 // 50% alpha for subtlety
            );
        }
        
        // Fallback colors for light/dark themes
        Color backgroundColor = editor.getColorsScheme().getDefaultBackground();
        boolean isDarkTheme = backgroundColor.getRed() + backgroundColor.getGreen() + backgroundColor.getBlue() < 384;
        
        if (isDarkTheme) {
            return new Color(160, 160, 160, 128); // Light gray with transparency
        } else {
            return new Color(100, 100, 100, 128); // Dark gray with transparency
        }
    }
    
    /**
     * Gets the full completion text.
     */
    public String getCompletionText() {
        return completionText;
    }
    
    /**
     * Checks if this is a multi-line completion.
     */
    public boolean isMultiLine() {
        return isMultiLine;
    }
    
    /**
     * Gets the first line of the completion for display purposes.
     */
    public String getFirstLine() {
        return firstLine;
    }
    
    /**
     * Creates a renderer for single-line completions.
     */
    public static ZestInlayRenderer createSingleLine(String completionText, Editor editor) {
        // Ensure single line by taking only the first line if multiple exist
        String singleLine = completionText.contains("\n") 
            ? completionText.substring(0, completionText.indexOf('\n'))
            : completionText;
        return new ZestInlayRenderer(singleLine, editor);
    }
    
    /**
     * Creates a renderer for multi-line completions.
     */
    public static ZestInlayRenderer createMultiLine(String completionText, Editor editor) {
        return new ZestInlayRenderer(completionText, editor);
    }
}
