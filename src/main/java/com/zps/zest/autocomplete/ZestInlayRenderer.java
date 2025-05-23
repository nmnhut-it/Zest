package com.zps.zest.autocomplete;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.ui.JBColor;
import com.intellij.util.DocumentUtil;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Enhanced inlay renderer following Tabby ML patterns.
 * Supports multi-line rendering, replace ranges, and sophisticated display logic.
 */
public class ZestInlayRenderer {
    private static final Logger LOG = Logger.getInstance(ZestInlayRenderer.class);

    /**
     * Rendering context similar to Tabby ML's approach.
     */
    public static class RenderingContext {
        private final String id;
        private final Editor editor;
        private final int offset;
        private final ZestCompletionData.CompletionItem item;
        private final List<Inlay<?>> inlays;
        private final List<RangeHighlighter> markups;
        private final long displayTime;

        public RenderingContext(String id, Editor editor, int offset,
                                ZestCompletionData.CompletionItem item) {
            this.id = id;
            this.editor = editor;
            this.offset = offset;
            this.item = item;
            this.inlays = new ArrayList<>();
            this.markups = new ArrayList<>();
            this.displayTime = System.currentTimeMillis();
        }

        // Getters
        public String getId() { return id; }
        public Editor getEditor() { return editor; }
        public int getOffset() { return offset; }
        public ZestCompletionData.CompletionItem getItem() { return item; }
        public List<Inlay<?>> getInlays() { return inlays; }
        public List<RangeHighlighter> getMarkups() { return markups; }
        public long getDisplayTime() { return displayTime; }

        public long getElapsedTime() {
            return System.currentTimeMillis() - displayTime;
        }

        public void addInlay(Inlay<?> inlay) {
            inlays.add(inlay);
        }

        public void addMarkup(RangeHighlighter markup) {
            markups.add(markup);
        }

        /**
         * Cleans up all rendering elements.
         * 
         * @return The number of inlays and markups that were disposed
         */
        public int dispose() {
            int count = 0;
            
            // Dispose inlays with more robust error handling
            List<Inlay<?>> validInlays = new ArrayList<>(inlays.size());
            for (Inlay<?> inlay : inlays) {
                try {
                    if (inlay != null && inlay.isValid()) {
                        validInlays.add(inlay);
                    }
                } catch (Exception e) {
                    LOG.warn("Error checking inlay validity", e);
                }
            }
            
            // Now dispose the valid inlays
            for (Inlay<?> inlay : validInlays) {
                try {
                    inlay.dispose();
                    count++;
                } catch (Exception e) {
                    LOG.warn("Error disposing inlay", e);
                }
            }

            // Dispose markups with better error handling
            List<RangeHighlighter> validMarkups = new ArrayList<>(markups.size());
            for (RangeHighlighter markup : markups) {
                try {
                    if (markup != null && markup.isValid()) {
                        validMarkups.add(markup);
                    }
                } catch (Exception e) {
                    LOG.warn("Error checking markup validity", e);
                }
            }
            
            // Now dispose the valid markups
            if (editor != null && !editor.isDisposed()) {
                for (RangeHighlighter markup : validMarkups) {
                    try {
                        editor.getMarkupModel().removeHighlighter(markup);
                        count++;
                    } catch (Exception e) {
                        LOG.warn("Error removing highlighter", e);
                    }
                }
            }

            inlays.clear();
            markups.clear();
            
            return count;
        }
    }

    /**
     * Creates and displays a completion following Tabby ML patterns.
     * Now with pre-cleaned responses, rendering is much safer.
     */
    public static RenderingContext show(Editor editor, int offset,
                                        ZestCompletionData.CompletionItem item) {

        String completionId = "zest-" + System.currentTimeMillis();
        RenderingContext context = new RenderingContext(completionId, editor, offset, item);

        // Basic safety check: Don't render if it would obviously break flow
        if (!isBasicallySafeToRender(editor, offset)) {
            LOG.debug("Skipping render - basic safety check failed");
            return context; // Return empty context
        }

        // Calculate what text to display using smart detection
        String visibleText = item.getSmartVisibleText(editor, offset);
        if (visibleText.isEmpty()) {
            LOG.debug("No visible text after smart prefix removal");
            return context; // Nothing to display
        }

        // Handle multi-line vs single-line completions
        if (item.isMultiLine()) {
            renderMultiLineCompletion(context, visibleText, offset);
        } else {
            renderSingleLineCompletion(context, visibleText, offset);
        }

        return context;
    }
    
    /**
     * Cleanup existing inlays at the specified offset to prevent overlapping elements.
     */
    private static void cleanupExistingInlaysAtOffset(Editor editor, int offset) {
        if (editor.isDisposed()) {
            return;
        }
        
        try {
            // Check for inline elements at this offset
            for (Inlay<?> inlay : editor.getInlayModel().getInlineElementsInRange(offset, offset)) {
                if (inlay.getRenderer() instanceof InlineCompletionRenderer ||
                    inlay.getRenderer() instanceof BlockCompletionRenderer) {
                    try {
                        inlay.dispose();
                        LOG.debug("Cleaned up existing inlay at offset " + offset);
                    } catch (Exception ex) {
                        LOG.warn("Failed to clean up existing inlay", ex);
                    }
                }
            }
            
            // Check for block elements at this line
            int line = editor.getDocument().getLineNumber(offset);
            for (Inlay<?> inlay : editor.getInlayModel().getBlockElementsForVisualLine(line, false)) {
                if (inlay.getRenderer() instanceof BlockCompletionRenderer) {
                    try {
                        inlay.dispose();
                        LOG.debug("Cleaned up existing block inlay at line " + line);
                    } catch (Exception ex) {
                        LOG.warn("Failed to clean up existing block inlay", ex);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Error during cleanup of existing inlays", e);
        }
    }

    /**
     * Basic safety check - much simpler since responses are pre-cleaned.
     * Just ensures we're not in an obviously bad position.
     */
    private static boolean isBasicallySafeToRender(Editor editor, int offset) {
        try {
            Document document = editor.getDocument();
            
            // Basic bounds check
            if (offset < 0 || offset > document.getTextLength()) {
                return false;
            }
            
            // Don't render if editor is read-only
            if (!editor.getDocument().isWritable()) {
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            LOG.warn("Error in basic render safety check", e);
            return false;
        }
    }

    /**
     * Renders a single-line completion.
     */
    private static void renderSingleLineCompletion(RenderingContext context,
                                                   String text,
                                                   int offset) {
        Editor editor = context.getEditor();
        TextRange lineTextRange = DocumentUtil.getLineTextRange(editor.getDocument(), editor.getCaretModel().getLogicalPosition().line);
        String currentLine = editor.getDocument().getText(lineTextRange);
        text = text.trim().replace(currentLine.trim(),"");
        // Create inline renderer
        InlineCompletionRenderer renderer = new InlineCompletionRenderer(text, editor, false);

        // Add inline element
        Inlay<?> inlay = editor.getInlayModel().addInlineElement(offset, true, renderer);
        if (inlay != null) {
            context.addInlay(inlay);
        }
    }

    /**
     * Renders a multi-line completion following Tabby ML approach.
     */
    private static void renderMultiLineCompletion(RenderingContext context,
                                                  String text, int offset) {
        Editor editor = context.getEditor();

        String[] lines = text.split("\n");

        if (lines.length == 0) return;
        TextRange lineTextRange = DocumentUtil.getLineTextRange(editor.getDocument(), editor.getCaretModel().getLogicalPosition().line);
        String currentLine = editor.getDocument().getText(lineTextRange);
        lines[0] = lines[0].trim().replace(currentLine.trim(),"");
        // Render first line inline
        if (!lines[0].isEmpty()) {
            InlineCompletionRenderer firstLineRenderer =
                    new InlineCompletionRenderer(lines[0], editor, true);
            Inlay<?> firstInlay = editor.getInlayModel().addInlineElement(offset, true, firstLineRenderer);
            if (firstInlay != null) {
                context.addInlay(firstInlay);
            }
        }

        // Render subsequent lines as block elements
        for (int i = 1; i < lines.length; i++) {
            BlockCompletionRenderer blockRenderer =
                    new BlockCompletionRenderer(lines[i], editor, i);
            Inlay<?> blockInlay = editor.getInlayModel().addBlockElement(
                    offset, true, false, -i, blockRenderer);
            if (blockInlay != null) {
                context.addInlay(blockInlay);
            }
        }

        // Handle replace range if needed
        ZestCompletionData.Range replaceRange = context.getItem().getReplaceRange();
        if (replaceRange.getLength() > 0) {
            // Add markup to show text that will be replaced
            RangeHighlighter markup = createReplaceRangeMarkup(editor, replaceRange);
            if (markup != null) {
                context.addMarkup(markup);
            }
        }
    }

    /**
     * Creates markup for replace range highlighting.
     */
    private static RangeHighlighter createReplaceRangeMarkup(Editor editor,
                                                             ZestCompletionData.Range range) {
        TextAttributes attributes = new TextAttributes();
        attributes.setBackgroundColor(JBColor.YELLOW.darker());
        attributes.setForegroundColor(JBColor.GRAY);

        return editor.getMarkupModel().addRangeHighlighter(
                range.getStart(),
                range.getEnd(),
                1000, // High layer
                attributes,
                com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE
        );
    }

    /**
     * Inline completion renderer for single lines and first line of multi-line.
     */
    public static class InlineCompletionRenderer implements EditorCustomElementRenderer {
        public String text;
        private final Editor editor;
        private final boolean isFirstLineOfMulti;
        private final Font font;
        private final Color textColor;

        public InlineCompletionRenderer(String text, Editor editor, boolean isFirstLineOfMulti) {
            this.text = text;
            this.editor = editor;
            this.isFirstLineOfMulti = isFirstLineOfMulti;
            this.font = getCompletionFont(editor);
            this.textColor = getCompletionColor(editor);
        }

        @Override
        public int calcWidthInPixels(@NotNull Inlay inlay) {
            Graphics2D g = getGraphics(inlay);
            if (g == null) {
                return text.length() * 8; // Fallback
            }

            FontMetrics metrics = g.getFontMetrics(font);
            int width = metrics.stringWidth(text);

            // Add indicator width for multi-line
            if (isFirstLineOfMulti) {
                width += metrics.stringWidth(" ⋯");
            }

            return Math.max(width, 1);
        }

        @Override
        public void paint(@NotNull Inlay inlay, @NotNull Graphics g,
                          @NotNull Rectangle targetRect, @NotNull TextAttributes textAttributes) {
            Graphics2D g2d = (Graphics2D) g.create();

            try {
                // Enable anti-aliasing
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                // Set font and color
                g2d.setFont(font);
                g2d.setColor(textColor);

                // Calculate text position
                FontMetrics metrics = g2d.getFontMetrics();
                int textY = targetRect.y + metrics.getAscent();

                // Draw main text
                g2d.drawString(text, targetRect.x, textY);

                // Draw multi-line indicator
                if (isFirstLineOfMulti) {
                    int indicatorX = targetRect.x + metrics.stringWidth(text);
                    g2d.setColor(textColor.darker());
                    g2d.drawString(" ⋯", indicatorX, textY);
                }

            } finally {
                g2d.dispose();
            }
        }

        private Graphics2D getGraphics(Inlay inlay) {
            Component component = inlay.getEditor().getContentComponent();
            return component != null ? (Graphics2D) component.getGraphics() : null;
        }
    }

    /**
     * Block completion renderer for additional lines in multi-line completions.
     */
    public static class BlockCompletionRenderer implements EditorCustomElementRenderer {
        private final String text;
        private final Editor editor;
        private final int lineIndex;
        private final Font font;
        private final Color textColor;

        public BlockCompletionRenderer(String text, Editor editor, int lineIndex) {
            this.text = text;
            this.editor = editor;
            this.lineIndex = lineIndex;
            this.font = getCompletionFont(editor);
            this.textColor = getCompletionColor(editor);
        }

        @Override
        public int calcWidthInPixels(@NotNull Inlay inlay) {
            Graphics2D g = getGraphics(inlay);
            if (g == null) {
                return text.length() * 8;
            }

            FontMetrics metrics = g.getFontMetrics(font);
            return Math.max(metrics.stringWidth(text), 1);
        }

        @Override
        public int calcHeightInPixels(@NotNull Inlay inlay) {
            return inlay.getEditor().getLineHeight();
        }

        @Override
        public void paint(@NotNull Inlay inlay, @NotNull Graphics g,
                          @NotNull Rectangle targetRect, @NotNull TextAttributes textAttributes) {
            Graphics2D g2d = (Graphics2D) g.create();

            try {
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                g2d.setFont(font);
                g2d.setColor(textColor);

                FontMetrics metrics = g2d.getFontMetrics();
                int textY = targetRect.y + metrics.getAscent();

                g2d.drawString(text, targetRect.x, textY);

            } finally {
                g2d.dispose();
            }
        }

        private Graphics2D getGraphics(Inlay inlay) {
            Component component = inlay.getEditor().getContentComponent();
            return component != null ? (Graphics2D) component.getGraphics() : null;
        }
    }

    /**
     * Gets the appropriate font for completion text.
     */
    private static Font getCompletionFont(Editor editor) {
        Font editorFont = editor.getColorsScheme().getFont(EditorFontType.PLAIN);
        return editorFont.deriveFont(Font.ITALIC, editorFont.getSize() * 0.95f);
    }

    /**
     * Gets the appropriate color for completion text based on theme.
     */
    private static Color getCompletionColor(Editor editor) {
        Color backgroundColor = editor.getColorsScheme().getDefaultBackground();
        boolean isDarkTheme = backgroundColor.getRed() + backgroundColor.getGreen() + backgroundColor.getBlue() < 384;

        if (isDarkTheme) {
            return new Color(150, 150, 150, 160); // Light gray with transparency
        } else {
            return new Color(120, 120, 120, 160); // Dark gray with transparency
        }
    }
}
