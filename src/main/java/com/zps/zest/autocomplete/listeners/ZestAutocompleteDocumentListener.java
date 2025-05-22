package com.zps.zest.autocomplete.listeners;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.zps.zest.autocomplete.ZestAutocompleteService;
import org.jetbrains.annotations.NotNull;

/**
 * Document listener that triggers autocomplete suggestions when the user types.
 * Implements intelligent triggering based on context and typing patterns.
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
        
        // Only trigger on insertions (user typing), not deletions
        if (event.getNewLength() > event.getOldLength()) {
            LOG.debug("Text insertion detected, handling...");
            handleTextInsertion(event);
        } else {
            LOG.debug("Text deletion detected, clearing completion");
            // Clear completion on deletion
            autocompleteService.clearCompletion(editor);
        }
    }
    
    /**
     * Handles text insertion and decides whether to trigger autocomplete.
     */
    private void handleTextInsertion(DocumentEvent event) {
        boolean shouldTrigger = shouldTriggerAutocomplete(event);
        LOG.debug("Should trigger autocomplete: " + shouldTrigger);
        
        if (shouldTrigger) {
            LOG.info("Triggering autocomplete for text insertion");
            autocompleteService.triggerAutocomplete(editor);
        } else {
            LOG.debug("Not triggering autocomplete, clearing existing completion");
            // Clear existing completion if we shouldn't trigger
            autocompleteService.clearCompletion(editor);
        }
    }
    
    /**
     * Determines if autocomplete should be triggered based on the document change.
     */
    private boolean shouldTriggerAutocomplete(DocumentEvent event) {
        try {
            String insertedText = event.getNewFragment().toString();
            
            // Don't trigger on whitespace-only changes
            if (insertedText.trim().isEmpty()) {
                return false;
            }
            
            // Don't trigger if we're in the middle of accepting a completion
            if (autocompleteService.hasActiveCompletion(editor)) {
                return false;
            }
            
            // Don't trigger on single character deletions or very short insertions
            if (insertedText.length() < 2) {
                return false;
            }
            
            // Check if we're in a context where autocomplete makes sense
            return isInValidContext(event);
            
        } catch (Exception e) {
            // If there's any error, don't trigger to avoid issues
            return false;
        }
    }
    
    /**
     * Checks if the current context is valid for autocomplete.
     */
    private boolean isInValidContext(DocumentEvent event) {
        try {
            Document document = event.getDocument();
            int offset = event.getOffset() + event.getNewLength();
            
            // Don't trigger in comments
//            if (isInComment(document, offset)) {
//                return false;
//            }
//
            // Don't trigger in string literals
            if (isInStringLiteral(document, offset)) {
                return false;
            }
            
            // Don't trigger if we're at the very beginning of the file
            if (offset < 10) {
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Simple heuristic to check if we're inside a comment.
     */
    private boolean isInComment(Document document, int offset) {
        try {
            String text = document.getText();
            if (offset >= text.length()) {
                return false;
            }
            
            // Look backwards for comment markers
            int lineStart = document.getLineStartOffset(document.getLineNumber(offset));
            String lineText = text.substring(lineStart, Math.min(offset + 20, text.length()));
            
            // Check for single-line comments
            if (lineText.contains("//")) {
                int commentStart = lineText.indexOf("//");
                int positionInLine = offset - lineStart;
                if (positionInLine > commentStart) {
                    return true;
                }
            }
            
            // Check for multi-line comments (simplified)
            String beforeCursor = text.substring(Math.max(0, offset - 100), offset);
            int lastCommentStart = beforeCursor.lastIndexOf("/*");
            int lastCommentEnd = beforeCursor.lastIndexOf("*/");
            
            return lastCommentStart > lastCommentEnd;
            
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Simple heuristic to check if we're inside a string literal.
     */
    private boolean isInStringLiteral(Document document, int offset) {
        try {
            String text = document.getText();
            if (offset >= text.length()) {
                return false;
            }
            
            // Count quotes before the cursor position
            int lineStart = document.getLineStartOffset(document.getLineNumber(offset));
            String lineText = text.substring(lineStart, offset);
            
            // Count unescaped quotes
            int quoteCount = 0;
            boolean inEscape = false;
            
            for (char c : lineText.toCharArray()) {
                if (inEscape) {
                    inEscape = false;
                    continue;
                }
                
                if (c == '\\') {
                    inEscape = true;
                } else if (c == '"') {
                    quoteCount++;
                }
            }
            
            // If odd number of quotes, we're inside a string
            return quoteCount % 2 == 1;
            
        } catch (Exception e) {
            return false;
        }
    }
}
