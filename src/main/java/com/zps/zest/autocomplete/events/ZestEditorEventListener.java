package com.zps.zest.autocomplete.events;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.util.messages.Topic;
import org.jetbrains.annotations.NotNull;

/**
 * Message bus listener for editor lifecycle events relevant to autocomplete.
 * Handles editor creation, disposal, and focus changes.
 */
public interface ZestEditorEventListener {
    
    @Topic.ProjectLevel
    Topic<ZestEditorEventListener> TOPIC = Topic.create("ZestEditorEvents", ZestEditorEventListener.class);

    /**
     * Called when an editor is created and should be registered for autocomplete.
     * 
     * @param editor The newly created editor
     */
    void editorCreated(@NotNull Editor editor);

    /**
     * Called when an editor is disposed and should be unregistered from autocomplete.
     * 
     * @param editor The editor being disposed
     */
    void editorDisposed(@NotNull Editor editor);

    /**
     * Called when the active editor changes (selection changes in FileEditorManager).
     * 
     * @param event The editor manager event
     * @param oldEditor The previously active editor (can be null)
     * @param newEditor The newly active editor (can be null)
     */
    void activeEditorChanged(@NotNull FileEditorManagerEvent event, Editor oldEditor, Editor newEditor);

    /**
     * Called when an editor gains focus and autocomplete should be activated.
     * 
     * @param editor The editor that gained focus
     */
    void editorFocused(@NotNull Editor editor);

    /**
     * Called when an editor loses focus and autocomplete should be deactivated.
     * 
     * @param editor The editor that lost focus
     */
    void editorUnfocused(@NotNull Editor editor);
}
