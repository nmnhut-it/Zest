package com.zps.zest.autocomplete.service;

import com.intellij.openapi.editor.Editor;
import com.zps.zest.autocomplete.AcceptType;
import com.zps.zest.autocomplete.ZestCompletionData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Clean interface for completion operations.
 * Separates completion logic from the complex service implementation.
 */
public interface CompletionService {
    
    /**
     * Triggers completion for the given editor.
     */
    void triggerCompletion(@NotNull Editor editor, boolean forced);
    
    /**
     * Accepts the current completion with the specified type.
     */
    void acceptCompletion(@NotNull Editor editor, @NotNull AcceptType acceptType);
    
    /**
     * Accepts the current completion fully.
     */
    default void acceptCompletion(@NotNull Editor editor) {
        acceptCompletion(editor, AcceptType.FULL_COMPLETION);
    }
    
    /**
     * Handles tab completion with smart cycling.
     */
    void handleTabCompletion(@NotNull Editor editor);
    
    /**
     * Rejects the current completion.
     */
    void rejectCompletion(@NotNull Editor editor);
    
    /**
     * Clears any active completion.
     */
    void clearCompletion(@NotNull Editor editor);
    
    /**
     * Checks if there's an active completion for the editor.
     */
    boolean hasActiveCompletion(@NotNull Editor editor);
    
    /**
     * Gets the active completion for the editor.
     */
    @Nullable
    ZestCompletionData.PendingCompletion getActiveCompletion(@NotNull Editor editor);
    
    /**
     * Enables or disables the completion service.
     */
    void setEnabled(boolean enabled);
    
    /**
     * Checks if the service is enabled.
     */
    boolean isEnabled();
    
    /**
     * Clears the completion cache.
     */
    void clearCache();
    
    /**
     * Gets cache statistics.
     */
    String getCacheStats();
}
