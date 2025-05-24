package com.zps.zest.autocompletion2.rendering;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.InlayModel;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.zps.zest.autocompletion2.core.CompletionItem;
import com.zps.zest.autocompletion2.core.CompletionState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;

/**
 * Renders completion suggestions as inline inlays.
 * Handles multi-line completions and proper text styling.
 */
public class InlayRenderer {
    private static final Logger LOG = Logger.getInstance(InlayRenderer.class);
    
    // Styling for completion text
    private static final Color COMPLETION_COLOR = new Color(128, 128, 128, 180); // Semi-transparent gray
    private static final Font COMPLETION_FONT = new Font(Font.MONOSPACED, Font.ITALIC, 12);
    
    /**
     * Renders a completion as inlays and returns the populated state.
     * Must be called on EDT.
     */
    @Nullable
    public static CompletionState render(@NotNull Editor editor, @NotNull CompletionItem item) {
        if (editor.isDisposed()) {
            LOG.warn("Cannot render completion on disposed editor");
            return null;
        }
        
        CompletionState state = new CompletionState(editor, item);
        
        try {
            String text = item.getDisplayText();
            int offset = item.getInsertOffset();
            
            // Ensure offset is valid
            if (offset < 0 || offset > editor.getDocument().getTextLength()) {
                LOG.warn("Invalid offset " + offset + " for completion rendering");
                return null;
            }
            
            // Split text into lines for rendering
            String[] lines = text.split("\n", -1);
            
            if (lines.length == 1) {
                // Single line completion
                renderSingleLine(editor, state, lines[0], offset);
            } else {
                // Multi-line completion
                renderMultiLine(editor, state, lines, offset);
            }
            
            if (state.getInlays().isEmpty()) {
                LOG.debug("No inlays created for completion");
                return null;
            }
            
            LOG.debug("Rendered completion with " + state.getInlays().size() + " inlays");
            return state;
            
        } catch (Exception e) {
            LOG.warn("Error rendering completion", e);
            state.dispose();
            return null;
        }
    }
    
    private static void renderSingleLine(@NotNull Editor editor, 
                                       @NotNull CompletionState state,
                                       @NotNull String text, 
                                       int offset) {
        if (text.isEmpty()) {
            return;
        }
        
        InlayModel inlayModel = editor.getInlayModel();
        
        Inlay<CompletionInlayRenderer> inlay = inlayModel.addInlineElement(
            offset, 
            true, // Relate to preceding text
            new CompletionInlayRenderer(text, COMPLETION_COLOR)
        );
        
        if (inlay != null) {
            state.addInlay(inlay);
        }
    }
    
    private static void renderMultiLine(@NotNull Editor editor,
                                      @NotNull CompletionState state,
                                      @NotNull String[] lines,
                                      int startOffset) {
        InlayModel inlayModel = editor.getInlayModel();
        int currentOffset = startOffset;
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            
            if (i == 0) {
                // First line: inline with cursor
                if (!line.isEmpty()) {
                    Inlay<CompletionInlayRenderer> inlay = inlayModel.addInlineElement(
                        currentOffset,
                        true,
                        new CompletionInlayRenderer(line, COMPLETION_COLOR)
                    );
                    if (inlay != null) {
                        state.addInlay(inlay);
                    }
                }
            } else {
                // Subsequent lines: block elements
                Inlay<CompletionInlayRenderer> inlay = inlayModel.addBlockElement(
                    currentOffset,
                    true,
                    false, // Don't show above line
                    1, // Priority
                    new CompletionInlayRenderer(line, COMPLETION_COLOR)
                );
                if (inlay != null) {
                    state.addInlay(inlay);
                }
            }
        }
    }
    
    /**
     * Updates an existing completion with new text.
     * Used for continuations.
     */
    public static boolean updateCompletion(@NotNull Editor editor,
                                         @NotNull CompletionState oldState,
                                         @NotNull CompletionItem newItem) {
        // Dispose old state
        oldState.dispose();
        
        // Create new state
        CompletionState newState = render(editor, newItem);
        if (newState == null) {
            return false;
        }
        
        // Copy inlays to old state (for external reference consistency)
        for (Inlay<?> inlay : newState.getInlays()) {
            oldState.addInlay(inlay);
        }
        
        return true;
    }
}
