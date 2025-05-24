package com.zps.zest.autocompletion2.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a single completion suggestion.
 * Immutable and thread-safe.
 */
public class CompletionItem {
    private final String text;
    private final String displayText;
    private final int insertOffset;
    private final float confidence;
    private final String context;
    
    public CompletionItem(@NotNull String text, 
                         int insertOffset, 
                         float confidence,
                         @Nullable String context) {
        this.text = text;
        this.displayText = text; // Can be different for display purposes
        this.insertOffset = insertOffset;
        this.confidence = Math.max(0.0f, Math.min(1.0f, confidence));
        this.context = context;
    }
    
    @NotNull
    public String getText() { return text; }
    
    @NotNull 
    public String getDisplayText() { return displayText; }
    
    public int getInsertOffset() { return insertOffset; }
    
    public float getConfidence() { return confidence; }
    
    @Nullable
    public String getContext() { return context; }
    
    /**
     * Checks if this completion is still valid at the given offset.
     */
    public boolean isValidAt(int currentOffset) {
        return currentOffset >= insertOffset;
    }
    
    /**
     * Creates a continuation item with remaining text.
     */
    public CompletionItem createContinuation(@NotNull String remainingText, int newOffset) {
        return new CompletionItem(remainingText, newOffset, confidence, context);
    }
    
    @Override
    public String toString() {
        return String.format("CompletionItem{text='%s', offset=%d, confidence=%.2f}", 
            text.length() > 30 ? text.substring(0, 30) + "..." : text, 
            insertOffset, confidence);
    }
}
