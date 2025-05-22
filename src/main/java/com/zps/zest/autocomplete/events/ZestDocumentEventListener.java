package com.zps.zest.autocomplete.events;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;

/**
 * Message bus listener for document events relevant to autocomplete.
 * Replaces direct document listener registration with message bus integration.
 */
public interface ZestDocumentEventListener {
    
    @Topic.ProjectLevel
    Topic<ZestDocumentEventListener> TOPIC = Topic.create("ZestDocumentEvents", ZestDocumentEventListener.class);

    /**
     * Called when document content changes in a way that affects autocomplete.
     * 
     * @param document The document that changed
     * @param editor The editor associated with the document
     * @param event The document change event
     * @param triggerCompletion Whether this change should trigger autocomplete
     */
    void documentChangedForCompletion(@NotNull Document document, @NotNull Editor editor, 
                                     @NotNull DocumentEvent event, boolean triggerCompletion);

    /**
     * Called when document changes should invalidate current completions.
     * 
     * @param document The document that changed
     * @param editor The editor associated with the document
     * @param event The document change event
     */
    void documentChangedInvalidateCompletion(@NotNull Document document, @NotNull Editor editor, 
                                           @NotNull DocumentEvent event);

    /**
     * Called when bulk document changes start (e.g., formatting, refactoring).
     * Autocomplete should be suspended during bulk operations.
     */
    void bulkDocumentChangeStarted(@NotNull Document document, @NotNull Editor editor);

    /**
     * Called when bulk document changes end.
     * Autocomplete can be resumed after bulk operations.
     */
    void bulkDocumentChangeEnded(@NotNull Document document, @NotNull Editor editor);
}
