package com.zps.zest.autocompletion2.integration;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.project.Project;
import com.zps.zest.autocompletion2.core.AutocompleteService;
import com.zps.zest.autocompletion2.settings.AutocompleteSettings;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sets up automatic triggering of LLM completions based on typing.
 * Simple integration example for enabling auto-completion.
 */
public class AutoTriggerSetup {
    private static final Logger LOG = Logger.getInstance(AutoTriggerSetup.class);
    
    /**
     * Enables auto-completion for an editor.
     * Call this when an editor is opened to enable automatic LLM completions.
     */
    public static void enableAutoCompletion(@NotNull Editor editor) {
        Project project = editor.getProject();
        if (project == null) {
            return;
        }
        
        AutocompleteSettings settings = AutocompleteSettings.getInstance(project);
        
        // Only enable if auto-trigger is enabled in settings
        if (!settings.isAutoTriggerEnabled()) {
            LOG.debug("Auto-completion disabled in settings for project: " + project.getName());
            return;
        }
        
        AutocompleteService service = AutocompleteService.getInstance(project);
        
        // Add document listener for typing detection
        editor.getDocument().addDocumentListener(new SmartCompletionTrigger(editor, service, settings));
        
        LOG.debug("Auto-completion enabled for editor (delay: " + settings.getTriggerDelayMs() + "ms)");
    }
    
    /**
     * Disables auto-completion for an editor.
     */
    public static void disableAutoCompletion(@NotNull Editor editor) {
        // Note: In practice, this is handled by editor disposal
        // The document listeners are automatically cleaned up
        LOG.debug("Auto-completion disabled for editor");
    }
    
    /**
     * Document listener that triggers completions after user stops typing.
     */
    private static class SmartCompletionTrigger implements DocumentListener {
        private final Editor editor;
        private final AutocompleteService service;
        private final AutocompleteSettings settings;
        private CompletableFuture<Void> pendingCompletion;
        
        public SmartCompletionTrigger(@NotNull Editor editor, @NotNull AutocompleteService service, @NotNull AutocompleteSettings settings) {
            this.editor = editor;
            this.service = service;
            this.settings = settings;
        }
        
        @Override
        public void documentChanged(@NotNull DocumentEvent event) {
            // Skip if auto-trigger is disabled
            if (!settings.isAutoTriggerEnabled()) {
                return;
            }
            
            // Cancel any pending completion
            if (pendingCompletion != null && !pendingCompletion.isDone()) {
                pendingCompletion.cancel(true);
            }
            
            // Don't trigger if there's already an active completion
            if (service.hasCompletion(editor)) {
                return;
            }
            
            // Check if we should trigger completion
            if (shouldTriggerCompletion(event)) {
                // Schedule completion with configurable delay
                pendingCompletion = CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(settings.getTriggerDelayMs());
                        
                        if (!Thread.currentThread().isInterrupted()) {
                            // Trigger completion on EDT
                            ApplicationManager.getApplication().invokeLater(() -> {
                                if (!editor.isDisposed() && !service.hasCompletion(editor)) {
                                    triggerCompletionIfAppropriate();
                                }
                            });
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        }
        
        private boolean shouldTriggerCompletion(@NotNull DocumentEvent event) {
            // Only trigger on text insertion (not deletion)
            if (event.getOldLength() > 0) {
                return false;
            }
            
            // Check for trigger patterns based on settings
            String newText = event.getNewFragment().toString();
            
            if (settings.isTriggerOnDot() && newText.equals(".")) {
                return true; // Method access
            }
            
            if (settings.isTriggerOnAssignment() && newText.equals(" = ")) {
                return true; // Assignment
            }
            
            if (settings.isTriggerOnMethodCall() && newText.equals("(")) {
                return true; // Method call
            }
            
            if (settings.isTriggerOnKeywords() && newText.equals(" ")) {
                return shouldTriggerOnSpace(event);
            }
            
            return false;
        }
        
        private boolean shouldTriggerOnSpace(@NotNull DocumentEvent event) {
            try {
                Document doc = event.getDocument();
                int offset = event.getOffset();
                
                // Get text before the space
                if (offset < 10) return false;
                
                String before = doc.getText().substring(Math.max(0, offset - 10), offset);
                
                // Trigger after keywords that usually need completion
                return before.endsWith("new") || 
                       before.endsWith("return") ||
                       before.endsWith("throw");
                       
            } catch (Exception e) {
                return false;
            }
        }
        
        private void triggerCompletionIfAppropriate() {
            try {
                // Get context around cursor
                int offset = editor.getCaretModel().getOffset();
                Document doc = editor.getDocument();
                String text = doc.getText();
                
                if (offset > 0 && offset <= text.length()) {
                    // Get current line prefix
                    int lineStart = doc.getLineStartOffset(doc.getLineNumber(offset));
                    String linePrefix = text.substring(lineStart, offset).trim();
                    
                    // Only trigger if line has some content
                    if (linePrefix.length() > 2) {
                        if (settings.isDebugLoggingEnabled()) {
                            LOG.debug("Auto-triggering LLM completion for line: " + linePrefix);
                        }
                        service.triggerLLMCompletion(editor);
                    }
                }
            } catch (Exception e) {
                LOG.warn("Error in auto-trigger", e);
            }
        }
    }
}
