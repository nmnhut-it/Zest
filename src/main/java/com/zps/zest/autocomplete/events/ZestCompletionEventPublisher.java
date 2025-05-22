package com.zps.zest.autocomplete.events;

import com.intellij.openapi.editor.Editor;
import com.intellij.util.messages.Topic;
import com.zps.zest.autocomplete.AcceptType;
import com.zps.zest.autocomplete.ZestCompletionData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Message bus publisher for Zest autocomplete events.
 * Follows IntelliJ message bus patterns for better decoupling.
 */
public interface ZestCompletionEventPublisher {
    
    @Topic.ProjectLevel
    Topic<ZestCompletionEventPublisher> TOPIC = Topic.create("ZestCompletionEvents", ZestCompletionEventPublisher.class);

    /**
     * Published when a completion is requested.
     */
    void completionRequested(@NotNull Editor editor, int offset, boolean manually);

    /**
     * Published when a completion is displayed to the user.
     */
    void completionDisplayed(@NotNull Editor editor, @NotNull ZestCompletionData.CompletionItem item, int offset);

    /**
     * Published when a completion is accepted.
     */
    void completionAccepted(@NotNull Editor editor, @NotNull ZestCompletionData.CompletionItem item, 
                           @NotNull AcceptType acceptType, @NotNull String acceptedText);

    /**
     * Published when a completion is rejected/dismissed.
     */
    void completionRejected(@NotNull Editor editor, @NotNull ZestCompletionData.CompletionItem item, 
                           @NotNull RejectionReason reason);

    /**
     * Published when partial acceptance creates a continuation.
     */
    void completionContinued(@NotNull Editor editor, @NotNull ZestCompletionData.CompletionItem originalItem,
                            @NotNull ZestCompletionData.CompletionItem continuationItem, 
                            @NotNull String acceptedPortion);

    /**
     * Published when completion service state changes.
     */
    void serviceStateChanged(boolean enabled, @Nullable String reason);

    /**
     * Published when completion cache is updated.
     */
    void cacheUpdated(int size, int maxSize, @NotNull CacheUpdateType updateType);

    /**
     * Published when an error occurs in completion processing.
     */
    void completionError(@NotNull Editor editor, @NotNull Throwable error, @NotNull String context);

    /**
     * Reasons why a completion might be rejected.
     */
    enum RejectionReason {
        USER_EXPLICIT,          // User pressed Escape or explicitly rejected
        CARET_MOVED,            // User moved cursor away
        TEXT_CHANGED,           // Document was modified invalidating the completion
        TIMEOUT,                // Completion timed out
        NEW_COMPLETION,         // New completion replaced this one
        EDITOR_DISPOSED,        // Editor was closed/disposed
        SERVICE_DISABLED        // Autocomplete service was disabled
    }

    /**
     * Types of cache updates.
     */
    enum CacheUpdateType {
        ENTRY_ADDED,           // New cache entry added
        ENTRY_UPDATED,         // Existing cache entry updated
        ENTRY_REMOVED,         // Cache entry removed/expired
        CACHE_CLEARED,         // Entire cache cleared
        CACHE_PRUNED          // Cache pruned due to size limits
    }
}
