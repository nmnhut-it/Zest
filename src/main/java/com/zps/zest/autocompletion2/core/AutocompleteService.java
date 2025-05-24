package com.zps.zest.autocompletion2.core;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.zps.zest.autocompletion2.acceptance.AcceptanceType;
import com.zps.zest.autocompletion2.rendering.InlayRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main service for Zest Autocomplete v2.
 * Manages completions across all editors with clean state management.
 */
@Service(Service.Level.PROJECT)
public final class AutocompleteService implements com.intellij.openapi.Disposable {
    private static final Logger LOG = Logger.getInstance(AutocompleteService.class);
    
    private final Project project;
    private final Map<Editor, CompletionState> activeCompletions = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    
    public AutocompleteService(@NotNull Project project) {
        this.project = project;
        LOG.info("AutocompleteService v2 initialized for project: " + project.getName());
    }
    
    @NotNull
    public static AutocompleteService getInstance(@NotNull Project project) {
        return project.getService(AutocompleteService.class);
    }
    
    /**
     * Shows a completion for the given editor.
     * Must be called on EDT.
     */
    public boolean showCompletion(@NotNull Editor editor, @NotNull String completionText) {
        if (!enabled || editor.isDisposed()) {
            return false;
        }
        
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            LOG.warn("showCompletion called from non-EDT thread, invoking on EDT");
            ApplicationManager.getApplication().invokeLater(() -> showCompletion(editor, completionText));
            return false;
        }
        
        if (completionText.trim().isEmpty()) {
            LOG.debug("Empty completion text, skipping");
            return false;
        }
        
        // Clear any existing completion
        clearCompletion(editor);
        
        try {
            // Create completion item
            int offset = editor.getCaretModel().getOffset();
            CompletionItem item = new CompletionItem(completionText, offset, 0.9f, "user-triggered");
            
            // Render the completion
            CompletionState state = InlayRenderer.render(editor, item);
            if (state == null) {
                LOG.debug("Failed to render completion");
                return false;
            }
            
            // Store the state
            activeCompletions.put(editor, state);
            
            LOG.debug("Showed completion: " + item);
            return true;
        } catch (Exception e) {
            LOG.warn("Error showing completion", e);
            return false;
        }
    }
    
    /**
     * Handles Tab key press for progressive acceptance.
     * Must be called on EDT.
     */
    public boolean handleTab(@NotNull Editor editor) {
        if (!enabled || editor.isDisposed()) {
            return false;
        }
        
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            LOG.warn("handleTab called from non-EDT thread, invoking on EDT");
            ApplicationManager.getApplication().invokeLater(() -> handleTab(editor));
            return false;
        }
        
        CompletionState state = activeCompletions.get(editor);
        if (state == null || !state.isValid()) {
            clearCompletion(editor);
            return false;
        }
        
        try {
            // Increment tab count and determine acceptance type
            int tabCount = state.incrementTabCount();
            AcceptanceType acceptanceType = AcceptanceType.fromTabCount(tabCount);
            
            LOG.debug("Tab press #" + tabCount + " - using acceptance type: " + acceptanceType);
            
            // Perform the acceptance
            return acceptCompletion(editor, state, acceptanceType);
        } catch (Exception e) {
            LOG.warn("Error handling Tab", e);
            clearCompletion(editor);
            return false;
        }
    }
    
    /**
     * Accepts a completion with the specified acceptance type.
     */
    private boolean acceptCompletion(@NotNull Editor editor, 
                                   @NotNull CompletionState state, 
                                   @NotNull AcceptanceType acceptanceType) {
        try {
            CompletionItem item = state.getItem();
            AcceptanceType.AcceptanceResult result = acceptanceType.accept(item.getText());
            
            // Insert the accepted text
            WriteCommandAction.runWriteCommandAction(project, "Zest Autocomplete v2", "Zest", () -> {
                Document document = editor.getDocument();
                int offset = editor.getCaretModel().getOffset();
                
                String textToInsert = result.getAcceptedText();
                document.insertString(offset, textToInsert);
                editor.getCaretModel().moveToOffset(offset + textToInsert.length());
                
                LOG.debug("Inserted text: '" + textToInsert + "'");
            });
            
            // Handle continuation if there's remaining text
            if (result.hasRemaining()) {
                return createContinuation(editor, state, result.getRemainingText());
            } else {
                // No remaining text, clear completion
                clearCompletion(editor);
                return true;
            }
            
        } catch (Exception e) {
            LOG.warn("Error accepting completion", e);
            clearCompletion(editor);
            return false;
        }
    }
    
    /**
     * Creates a continuation completion with remaining text.
     */
    private boolean createContinuation(@NotNull Editor editor, 
                                     @NotNull CompletionState oldState, 
                                     @NotNull String remainingText) {
        try {
            // Get new offset after insertion
            int newOffset = editor.getCaretModel().getOffset();
            
            // Create continuation item
            CompletionItem continuationItem = new CompletionItem(remainingText, newOffset, 0.9f, "continuation");
            
            // Render continuation
            CompletionState continuationState = InlayRenderer.render(editor, continuationItem);
            if (continuationState == null) {
                LOG.debug("Failed to create continuation");
                clearCompletion(editor);
                return false;
            }
            
            // Replace old state with continuation state
            oldState.dispose();
            activeCompletions.put(editor, continuationState);
            
            LOG.debug("Created continuation with remaining: '" + 
                remainingText.substring(0, Math.min(30, remainingText.length())) + "'");
            
            return true;
            
        } catch (Exception e) {
            LOG.warn("Error creating continuation", e);
            clearCompletion(editor);
            return false;
        }
    }
    
    /**
     * Clears any active completion for the editor.
     * Must be called on EDT.
     */
    public void clearCompletion(@NotNull Editor editor) {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(() -> clearCompletion(editor));
            return;
        }
        
        CompletionState state = activeCompletions.remove(editor);
        if (state != null) {
            try {
                state.dispose();
                LOG.debug("Cleared completion for editor");
            } catch (Exception e) {
                LOG.warn("Error disposing completion state", e);
            }
        }
    }
    
    /**
     * Checks if the editor has an active completion.
     */
    public boolean hasCompletion(@NotNull Editor editor) {
        CompletionState state = activeCompletions.get(editor);
        return state != null && state.isValid();
    }
    
    /**
     * Gets the active completion state for an editor.
     */
    @Nullable
    public CompletionState getCompletionState(@NotNull Editor editor) {
        return activeCompletions.get(editor);
    }
    
    /**
     * Enables or disables the autocomplete service.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            // Clear all completions when disabling
            ApplicationManager.getApplication().invokeLater(() -> {
                for (Editor editor : activeCompletions.keySet()) {
                    clearCompletion(editor);
                }
            });
        }
        LOG.info("AutocompleteService v2 " + (enabled ? "enabled" : "disabled"));
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Gets diagnostic information about active completions.
     */
    @NotNull
    public String getDiagnosticInfo() {
        StringBuilder info = new StringBuilder();
        info.append("=== Autocomplete Service v2 Diagnostic ===\n");
        info.append("Service enabled: ").append(enabled).append("\n");
        info.append("Active completions: ").append(activeCompletions.size()).append("\n");
        
        for (Map.Entry<Editor, CompletionState> entry : activeCompletions.entrySet()) {
            Editor editor = entry.getKey();
            CompletionState state = entry.getValue();
            
            info.append("Editor ").append(editor.hashCode()).append(": ").append(state).append("\n");
        }
        
        info.append("=== End Diagnostic ===");
        return info.toString();
    }
    
    /**
     * Cleanup when service is disposed.
     */
    @Override
    public void dispose() {
        LOG.info("Disposing AutocompleteService v2");
        
        // Clear all completions on EDT
        if (ApplicationManager.getApplication().isDispatchThread()) {
            clearAllCompletions();
        } else {
            ApplicationManager.getApplication().invokeLater(this::clearAllCompletions);
        }
    }
    
    /**
     * Clears all active completions.
     * Must be called on EDT.
     */
    private void clearAllCompletions() {
        for (Editor editor : activeCompletions.keySet()) {
            try {
                clearCompletion(editor);
            } catch (Exception e) {
                LOG.warn("Error clearing completion during disposal", e);
            }
        }
        activeCompletions.clear();
    }
}
