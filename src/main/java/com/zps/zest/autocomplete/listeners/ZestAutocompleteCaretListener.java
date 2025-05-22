package com.zps.zest.autocomplete.listeners;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.zps.zest.autocomplete.ZestAutocompleteService;
import org.jetbrains.annotations.NotNull;

/**
 * Caret listener that manages autocomplete behavior when the cursor moves.
 * Clears completions when the user navigates away from the completion position.
 */
public class ZestAutocompleteCaretListener implements CaretListener {
    private final ZestAutocompleteService autocompleteService;
    private final Editor editor;
    
    public ZestAutocompleteCaretListener(ZestAutocompleteService autocompleteService, Editor editor) {
        this.autocompleteService = autocompleteService;
        this.editor = editor;
    }
    
    @Override
    public void caretPositionChanged(@NotNull CaretEvent event) {
        // Clear any active completion when the caret moves
        // This ensures completions don't stick around when the user navigates elsewhere
        if (autocompleteService.hasActiveCompletion(editor)) {
            // Only clear if the caret moved significantly
            if (shouldClearCompletion(event)) {
                autocompleteService.clearCompletion(editor);
            }
        }
    }
    
    /**
     * Determines if the completion should be cleared based on caret movement.
     */
    private boolean shouldClearCompletion(CaretEvent event) {
        int oldOffset = event.getEditor().logicalPositionToOffset(event.getOldPosition());
        int newOffset =  event.getEditor().logicalPositionToOffset(event.getNewPosition());
        
        // Clear if the user moved the caret significantly
        int distance = Math.abs(newOffset - oldOffset);
        
        // Allow small movements (like single character navigation)
        // but clear on larger jumps (line changes, clicks, etc.)
        return distance > 5;
    }
}
