package com.zps.zest.autocompletion2.core;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.zps.zest.autocompletion2.acceptance.AcceptanceType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages the state of a single completion for an editor.
 * Thread-safe and handles the complete lifecycle of a completion.
 */
public class CompletionState {
    private static final Logger LOG = Logger.getInstance(CompletionState.class);
    
    private final Editor editor;
    private final CompletionItem item;
    private final List<Inlay<?>> inlays;
    private final AtomicInteger tabCount;
    private final long createdAt;
    private volatile boolean disposed = false;
    
    public CompletionState(@NotNull Editor editor, @NotNull CompletionItem item) {
        this.editor = editor;
        this.item = item;
        this.inlays = new ArrayList<>();
        this.tabCount = new AtomicInteger(0);
        this.createdAt = System.currentTimeMillis();
        
        LOG.debug("Created completion state: " + item);
    }
    
    @NotNull
    public Editor getEditor() { return editor; }
    
    @NotNull 
    public CompletionItem getItem() { return item; }
    
    @NotNull
    public List<Inlay<?>> getInlays() { return new ArrayList<>(inlays); }
    
    public int getTabCount() { return tabCount.get(); }
    
    public long getCreatedAt() { return createdAt; }
    
    public boolean isDisposed() { return disposed; }
    
    /**
     * Adds an inlay to this completion state.
     */
    public void addInlay(@NotNull Inlay<?> inlay) {
        if (!disposed) {
            inlays.add(inlay);
            LOG.debug("Added inlay to completion state, total: " + inlays.size());
        }
    }
    
    /**
     * Increments the tab count and returns the new value.
     * This is used for progressive acceptance.
     */
    public int incrementTabCount() {
        int newCount = tabCount.incrementAndGet();
        LOG.debug("Tab count incremented to: " + newCount);
        return newCount;
    }
    
    /**
     * Resets the tab count to 0.
     * Used when creating continuations.
     */
    public void resetTabCount() {
        int oldCount = tabCount.getAndSet(0);
        LOG.debug("Tab count reset from " + oldCount + " to 0");
    }
    
    /**
     * Gets the next acceptance type based on current tab count.
     */
    @NotNull
    public AcceptanceType getNextAcceptanceType() {
        return AcceptanceType.fromTabCount(getTabCount() + 1);
    }
    
    /**
     * Checks if this completion is still valid.
     */
    public boolean isValid() {
        if (disposed || editor.isDisposed()) {
            return false;
        }
        
        // Check if item is still valid at current cursor position
        int currentOffset = editor.getCaretModel().getOffset();
        return item.isValidAt(currentOffset);
    }
    
    /**
     * Disposes of this completion state and cleans up all inlays.
     * Must be called on EDT.
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        
        disposed = true;
        
        // Dispose all inlays
        int disposedCount = 0;
        for (Inlay<?> inlay : inlays) {
            if (inlay.isValid()) {
                try {
                    inlay.dispose();
                    disposedCount++;
                } catch (Exception e) {
                    LOG.warn("Error disposing inlay", e);
                }
            }
        }
        
        inlays.clear();
        LOG.debug("Disposed completion state, removed " + disposedCount + " inlays");
    }
    
    /**
     * Creates a continuation state with remaining text.
     * Resets tab count for the new completion.
     */
    @NotNull
    public CompletionState createContinuation(@NotNull String remainingText, int newOffset) {
        CompletionItem continuationItem = item.createContinuation(remainingText, newOffset);
        CompletionState continuationState = new CompletionState(editor, continuationItem);
        
        LOG.debug("Created continuation state with remaining: " + 
            remainingText.substring(0, Math.min(30, remainingText.length())));
        
        return continuationState;
    }
    
    @Override
    public String toString() {
        return String.format("CompletionState{item=%s, tabCount=%d, inlays=%d, disposed=%s}", 
            item, tabCount.get(), inlays.size(), disposed);
    }
}
