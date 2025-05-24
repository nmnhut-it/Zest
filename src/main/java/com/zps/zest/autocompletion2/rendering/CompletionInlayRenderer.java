package com.zps.zest.autocompletion2.rendering;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.TextAttributes;
import org.jetbrains.annotations.NotNull;

import java.awt.*;

/**
 * Custom renderer for completion inlays.
 * Handles the actual drawing of completion text with proper styling.
 */
public class CompletionInlayRenderer implements EditorCustomElementRenderer {
    private final String text;
    private final Color textColor;
    private final Font font;
    
    public CompletionInlayRenderer(@NotNull String text, @NotNull Color textColor) {
        this.text = text;
        this.textColor = textColor;
        this.font = null; // Will use editor font
    }
    
    @Override
    public int calcWidthInPixels(@NotNull Inlay inlay) {
        Editor editor = inlay.getEditor();
        FontMetrics metrics = editor.getContentComponent().getFontMetrics(getFont(editor));
        return metrics.stringWidth(text);
    }
    
    @Override
    public void paint(@NotNull Inlay inlay, 
                      @NotNull Graphics g, 
                      @NotNull Rectangle targetRegion, 
                      @NotNull TextAttributes textAttributes) {
        
        Editor editor = inlay.getEditor();
        Graphics2D g2d = (Graphics2D) g.create();
        
        try {
            // Set rendering hints for better text quality
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            
            // Set font and color
            g2d.setFont(getFont(editor));
            g2d.setColor(textColor);
            
            // Calculate text position
            FontMetrics metrics = g2d.getFontMetrics();
            int textY = targetRegion.y + metrics.getAscent();
            
            // Draw the completion text
            g2d.drawString(text, targetRegion.x, textY);
            
            // Optional: Add subtle background or border
            // drawBackground(g2d, targetRegion, metrics);
            
        } finally {
            g2d.dispose();
        }
    }
    
    /**
     * Gets the appropriate font for the completion text.
     */
    @NotNull
    private Font getFont(@NotNull Editor editor) {
        if (font != null) {
            return font;
        }
        
        // Use editor's font but make it italic to distinguish completion text
        Font editorFont = editor.getColorsScheme().getFont(EditorFontType.PLAIN);
        return editorFont.deriveFont(Font.ITALIC);
    }
    
    /**
     * Optional: Draw a subtle background behind completion text.
     */
    private void drawBackground(@NotNull Graphics2D g2d, 
                              @NotNull Rectangle targetRegion, 
                              @NotNull FontMetrics metrics) {
        // Semi-transparent background
        Color bgColor = new Color(textColor.getRed(), textColor.getGreen(), textColor.getBlue(), 30);
        g2d.setColor(bgColor);
        
        Rectangle bgRect = new Rectangle(
            targetRegion.x - 2,
            targetRegion.y,
            metrics.stringWidth(text) + 4,
            targetRegion.height
        );
        
        g2d.fillRoundRect(bgRect.x, bgRect.y, bgRect.width, bgRect.height, 4, 4);
    }
    
    @Override
    public String toString() {
        return String.format("CompletionInlayRenderer{text='%s'}", 
            text.length() > 20 ? text.substring(0, 20) + "..." : text);
    }
}
