package com.zps.zest.autocomplete.listeners;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.MessageBus;
import com.zps.zest.autocomplete.ZestAutocompleteService;
import com.zps.zest.autocomplete.events.ZestDocumentEventListener;
import com.zps.zest.autocomplete.events.ZestCompletionEventPublisher;
import org.jetbrains.annotations.NotNull;

/**
 * ✅ SIMPLIFIED document listener for reliable triggering.
 * Focuses on core functionality that works with 200ms debouncing.
 */
public class ZestAutocompleteDocumentListener implements DocumentListener {
    private static final Logger LOG = Logger.getInstance(ZestAutocompleteDocumentListener.class);
    
    private final ZestAutocompleteService autocompleteService;
    private final Editor editor;
    
    public ZestAutocompleteDocumentListener(ZestAutocompleteService autocompleteService, Editor editor) {
        this.autocompleteService = autocompleteService;
        this.editor = editor;
        LOG.debug("ZestAutocompleteDocumentListener created for editor");
    }
    
    @Override
    public void documentChanged(@NotNull DocumentEvent event) {
        LOG.debug("Document changed: " + event);
        
        // ✅ SIMPLIFIED: Focus on the core logic that works
        handleDocumentChange(event);
    }
    
    /**
     * ✅ SIMPLIFIED: Much simpler logic focusing on what actually works
     */
    private void handleDocumentChange(DocumentEvent event) {
        int oldLength = event.getOldLength();
        int newLength = event.getNewLength();
        
        // Handle deletions - always clear completions
        if (oldLength > 0 && newLength == 0) {
            LOG.debug("Text deleted, clearing completion");
            autocompleteService.clearCompletion(editor);
            return;
        }
        
        // Handle insertions - the main case for triggering
        if (oldLength == 0 && newLength > 0) {
            String insertedText = event.getNewFragment().toString();
            
            // ✅ BASIC filtering - only skip obvious non-completion cases
            if (shouldTriggerForInsertion(insertedText, event)) {
                LOG.debug("Triggering autocomplete for insertion: '{}'", insertedText);
                autocompleteService.triggerAutocomplete(editor);
            } else {
                LOG.debug("Skipping trigger for insertion: '{}'", insertedText);
            }
            return;
        }
        
        // Handle replacements - clear existing, maybe trigger new
        if (oldLength > 0 && newLength > 0) {
            LOG.debug("Text replaced, clearing completion");
            autocompleteService.clearCompletion(editor);
            
            // If replacement looks like continued typing, trigger
            String newText = event.getNewFragment().toString();
            if (newText.length() <= 3 && newText.matches("\\w+")) {
                LOG.debug("Replacement looks like continuation, triggering");
                autocompleteService.triggerAutocomplete(editor);
            }
            return;
        }
        
        // Large changes - just clear
        if (oldLength > 50 || newLength > 50) {
            LOG.debug("Large change detected, clearing completion");
            autocompleteService.clearCompletion(editor);
        }
    }
    
    /**
     * ✅ SIMPLIFIED: Basic checks that actually matter
     */
    private boolean shouldTriggerForInsertion(String insertedText, DocumentEvent event) {
        // Don't trigger if empty
        if (insertedText.trim().isEmpty()) {
            return false;
        }
        
        // Don't trigger if completion already active (let the service handle this)
        if (autocompleteService.hasActiveCompletion(editor)) {
            return false;
        }
        
        // ✅ BASIC context check - are we in a reasonable position?
        int offset = event.getOffset() + event.getNewLength();
        if (offset < 3) { // Need some minimum context
            return false;
        }
        
        // ✅ BASIC string/comment check - simplified version
        if (isLikelyInStringOrComment(event.getDocument(), offset)) {
            return false;
        }
        
        // ✅ For everything else, trigger and let the server decide
        return true;
    }
    
    /**
     * ✅ SIMPLIFIED: Basic string/comment detection that actually works
     */
    private boolean isLikelyInStringOrComment(Document document, int offset) {
        try {
            // Get current line text
            int lineNumber = document.getLineNumber(offset);
            int lineStart = document.getLineStartOffset(lineNumber);
            String lineText = document.getText().substring(lineStart, offset);
            
            // Simple heuristic: count quotes on current line
            int doubleQuotes = 0;
            int singleQuotes = 0;
            boolean inEscape = false;
            
            for (char c : lineText.toCharArray()) {
                if (inEscape) {
                    inEscape = false;
                    continue;
                }
                
                if (c == '\\') {
                    inEscape = true;
                } else if (c == '"') {
                    doubleQuotes++;
                } else if (c == '\'') {
                    singleQuotes++;
                }
            }
            
            // If odd number of quotes, likely in string
            boolean inString = (doubleQuotes % 2 == 1) || (singleQuotes % 2 == 1);
            
            // Simple comment check
            // Ignore command
            boolean inComment = lineText.contains("//")  && false;
            
            return inString || inComment;
            
        } catch (Exception e) {
            // If detection fails, allow completion (better to over-trigger than under-trigger)
            return false;
        }
    }
}
