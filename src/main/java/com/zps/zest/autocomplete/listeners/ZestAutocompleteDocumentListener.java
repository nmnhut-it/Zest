package com.zps.zest.autocomplete.listeners;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.util.TextRange;
import com.zps.zest.autocomplete.ZestAutocompleteService;
import com.zps.zest.autocomplete.ZestCompletionData;
import com.zps.zest.autocomplete.utils.CompletionStateUtils;
import com.zps.zest.autocomplete.utils.EditorUtils;
import org.jetbrains.annotations.NotNull;

/**
 * FIXED: Simplified document listener for CODE-ONLY completion.
 * No longer tries to manage inlays directly - that's handled by RenderingContext.
 * Focuses on detecting when to trigger/clear completions based on document changes.
 */
public class ZestAutocompleteDocumentListener implements DocumentListener {
    private static final Logger LOG = Logger.getInstance(ZestAutocompleteDocumentListener.class);

    private final ZestAutocompleteService autocompleteService;
    private final Editor editor;

    public ZestAutocompleteDocumentListener(ZestAutocompleteService autocompleteService, Editor editor) {
        this.autocompleteService = autocompleteService;
        this.editor = editor;
        LOG.debug("ZestAutocompleteDocumentListener created for editor - CODE COMPLETION ONLY");
    }

    @Override
    public void documentChanged(@NotNull DocumentEvent event) {
        LOG.debug("Document changed: " + event);
        autocompleteService.recordLastTypingTime(editor);
        handleDocumentChange(event);
    }



    /**
     * ENHANCED: Simplified logic with intelligent inlay invalidation.
     * Detects when user types something different from the completion suggestion.
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
            ZestCompletionData.PendingCompletion activeCompletion = autocompleteService.getActiveCompletion(editor);

            if (activeCompletion != null) {
                // CRITICAL: Check if user typed something different from completion
                if (isUserTypingDifferentFromCompletion(insertedText, event, activeCompletion)) {
                    LOG.debug("User typed '{}' which differs from completion, clearing inlay", insertedText);
                    autocompleteService.clearCompletion(editor);
                    
                    // Optionally trigger new completion if it makes sense
                    if (shouldTriggerAfterMismatch(insertedText, event)) {
                        autocompleteService.triggerCompletion(editor, false);
                    }
                } else if (isInsertionCompatibleWithCompletion(insertedText, event, activeCompletion)) {
                    LOG.debug("Insertion compatible with current completion, refreshing display");
                    // Instead of directly manipulating inlays, trigger a refresh
                    autocompleteService.clearCompletion(editor);
                    autocompleteService.triggerCompletion(editor, false);
                } else {
                    LOG.debug("Insertion not compatible, clearing completion");
                    autocompleteService.clearCompletion(editor);
                }
            } else if (shouldTriggerCodeCompletion(insertedText, event)) {
                LOG.debug("Triggering CODE completion for insertion: '{}'", insertedText);
                autocompleteService.triggerCompletion(editor, false);
            } else {
                LOG.debug("Skipping trigger for insertion: '{}'", insertedText);
                checkForCommentOrJavadocTrigger(insertedText, event);
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
                autocompleteService.triggerCompletion(editor, false);
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
     * CRITICAL: Detects when user types something different from what the completion suggests.
     * This prevents the issue where inlays show incorrect suggestions after user input.
     */
    private boolean isUserTypingDifferentFromCompletion(String insertedText, DocumentEvent event, 
                                                       ZestCompletionData.PendingCompletion activeCompletion) {
        try {
            String completionText = activeCompletion.getItem().getInsertText();
            if (completionText == null || completionText.isEmpty()) {
                return true; // Invalid completion, clear it
            }
            
            // Get the current cursor position and surrounding context
            int insertionOffset = event.getOffset();
            Document document = event.getDocument();
            
            // Calculate what the text should look like at the cursor position
            int lineNumber = document.getLineNumber(insertionOffset);
            int lineStart = document.getLineStartOffset(lineNumber);
            int currentOffset = insertionOffset + event.getNewLength();
            
            // Get the text before cursor (including the new insertion)
            String beforeCursor = document.getText(TextRange.from(lineStart, currentOffset - lineStart));
            
            // Check if the completion text still makes sense at this position
            String expectedNextChars = getExpectedNextCharacters(completionText, beforeCursor);
            
            if (expectedNextChars.isEmpty()) {
                // Completion is already fully typed or doesn't match context
                return true;
            }
            
            // Check if the inserted character matches what the completion expects next
            char insertedChar = insertedText.charAt(0);
            char expectedChar = expectedNextChars.charAt(0);
            
            boolean matches = (insertedChar == expectedChar);
            
            if (!matches) {
                LOG.debug("User typed '{}' but completion expected '{}' - clearing inlay", 
                         insertedChar, expectedChar);
                return true;
            }
            
            return false; // User typed what was expected
            
        } catch (Exception e) {
            LOG.warn("Error checking user input against completion", e);
            return true; // When in doubt, clear the completion
        }
    }
    
    /**
     * Helper method to determine what characters the completion expects next.
     */
    private String getExpectedNextCharacters(String completionText, String beforeCursor) {
        if (completionText.isEmpty() || beforeCursor.isEmpty()) {
            return completionText;
        }
        
        // Try to find where the completion should continue from
        String beforeCursorTrimmed = beforeCursor.trim();
        
        // Simple prefix matching - find the longest common prefix
        int matchLength = 0;
        int maxCheck = Math.min(completionText.length(), beforeCursorTrimmed.length());
        
        for (int i = 0; i < maxCheck; i++) {
            if (Character.toLowerCase(completionText.charAt(i)) == 
                Character.toLowerCase(beforeCursorTrimmed.charAt(beforeCursorTrimmed.length() - maxCheck + i))) {
                matchLength = i + 1;
            } else {
                break;
            }
        }
        
        if (matchLength < completionText.length()) {
            return completionText.substring(matchLength);
        }
        
        return ""; // Completion is already fully typed
    }
    
    /**
     * Determines if we should trigger a new completion after a mismatch.
     */
    private boolean shouldTriggerAfterMismatch(String insertedText, DocumentEvent event) {
        // Only trigger if the inserted text is alphanumeric (likely continuing to type)
        return insertedText.matches("[a-zA-Z0-9_]") && insertedText.length() == 1;
    }

    /**
     * REFACTORED: Checks if the insertion is compatible using utility classes.
     */
    private boolean isInsertionCompatibleWithCompletion(String insertedText, DocumentEvent event, 
                                                       ZestCompletionData.PendingCompletion activeCompletion) {
        return CompletionStateUtils.isInsertionCompatible(insertedText, editor, activeCompletion);
    }

    /**
     * ENHANCED: Only triggers for CODE, explicitly blocks comments/javadocs
     */
    private boolean shouldTriggerCodeCompletion(String insertedText, DocumentEvent event) {
        // Don't trigger if empty
        if (insertedText.trim().isEmpty()) {
            return false;
        }

        // Don't trigger if completion already active
        if (autocompleteService.hasActiveCompletion(editor)) {
            return false;
        }

        int offset = event.getOffset() + event.getNewLength();
        if (offset < 3) { // Need some minimum context
            return false;
        }

        // BLOCK: Comments and javadocs are explicitly blocked
        if (isInCommentOrJavadoc(event.getDocument(), offset)) {
            LOG.debug("Blocking completion - cursor is in comment/javadoc area");
            return false;
        }

        // BLOCK: If this insertion creates a comment/javadoc start
        if (isCommentOrJavadocStart(insertedText, event.getDocument(), offset)) {
            LOG.debug("Blocking completion - insertion creates comment/javadoc start");
            return false;
        }

        // ALLOW: Only pure code completion
        return true;
    }

    /**
     * REFACTORED: Enhanced detection using utility classes.
     */
    private boolean isInCommentOrJavadoc(Document document, int offset) {
        try {
            // Use utility to get line text safely
            String lineText = EditorUtils.safeGetCurrentLinePrefix(editor);

            // Check for line comments
            if (lineText.trim().startsWith("//")) {
                return true;
            }

            // Check for javadoc/block comments
            if (lineText.contains("/*") || lineText.contains("/**")) {
                // Count opening vs closing
                int openCount = countOccurrences(lineText, "/*");
                int closeCount = countOccurrences(lineText, "*/");
                if (openCount > closeCount) {
                    return true; // We're inside a block comment
                }
            }

            // Check for javadoc continuation lines
            String trimmedLine = lineText.trim();
            if (trimmedLine.startsWith("*") && !trimmedLine.startsWith("*/")) {
                return true; // Inside javadoc block
            }

            return false;

        } catch (Exception e) {
            // If detection fails, allow completion (better to over-trigger than under-trigger)
            return false;
        }
    }

    /**
     * NEW: Detects if the insertion creates a comment or javadoc start
     */
    private boolean isCommentOrJavadocStart(String insertedText, Document document, int offset) {
        try {
            // Get context around the insertion
            int lineNumber = document.getLineNumber(offset);
            int lineStart = document.getLineStartOffset(lineNumber);
            String lineText = document.getText().substring(lineStart, offset);

            // Check if we just completed a comment start pattern
            String fullLineWithInsertion = lineText;

            // Common patterns that start comments/javadocs
            if (fullLineWithInsertion.trim().endsWith("//") ||
                    fullLineWithInsertion.trim().endsWith("/*") ||
                    fullLineWithInsertion.trim().endsWith("/**")) {
                return true;
            }

            return false;

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * NEW: Placeholder for future comment/javadoc support
     * This is where you'll add your custom triggering logic
     */
    private void checkForCommentOrJavadocTrigger(String insertedText, DocumentEvent event) {
        // TODO: Implement your custom comment/javadoc triggering logic here
        // This method is called when completion is blocked but might be a comment/javadoc trigger

        try {
            int offset = event.getOffset() + event.getNewLength();
            int lineNumber = event.getDocument().getLineNumber(offset);
            int lineStart = event.getDocument().getLineStartOffset(lineNumber);
            String lineText = event.getDocument().getText().substring(lineStart, offset);

            // TODO: Add your custom logic for javadoc triggers
            if (lineText.trim().endsWith("/**")) {
                // LOG.info("JAVADOC TRIGGER DETECTED: '{}' - Add your custom logic here", lineText.trim());
                // Example: autocompleteService.triggerJavadocCompletion(editor);
            }

            // TODO: Add your custom logic for comment triggers
            if (lineText.trim().endsWith("//")) {
                // LOG.info("COMMENT TRIGGER DETECTED: '{}' - Add your custom logic here", lineText.trim());
                // Example: autocompleteService.triggerCommentCompletion(editor);
            }

        } catch (Exception e) {
            LOG.warn("Error in comment/javadoc trigger detection", e);
        }
    }

    /**
     * Helper method to count occurrences of a substring
     */
    private int countOccurrences(String text, String pattern) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(pattern, index)) != -1) {
            count++;
            index += pattern.length();
        }
        return count;
    }
}
