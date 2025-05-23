package com.zps.zest.autocomplete.listeners;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.util.messages.MessageBus;
import com.zps.zest.autocomplete.ZestAutocompleteService;
import com.zps.zest.autocomplete.ZestCompletionData;
import com.zps.zest.autocomplete.ZestInlayRenderer;
import com.zps.zest.autocomplete.events.ZestDocumentEventListener;
import com.zps.zest.autocomplete.events.ZestCompletionEventPublisher;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * ✅ SIMPLIFIED document listener for CODE-ONLY completion.
 * Explicitly blocks comment and javadoc completion.
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

        handleDocumentChange(event);
    }

    /**
     * ✅ SIMPLIFIED: Code-only logic with explicit comment/javadoc blocking
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

        // Handle insertions     - the main case for triggering
        if (oldLength == 0 && newLength > 0) {
            String insertedText = event.getNewFragment().toString();
            ZestCompletionData.PendingCompletion activeCompletion = autocompleteService.getActiveCompletion(editor);
            Document document = editor.getDocument();

            // Get current line information
            int currentLine = document.getLineNumber(event.getOffset());
            int lineStartOffset = document.getLineStartOffset(currentLine);
            int lineEndOffset = document.getLineEndOffset(currentLine);

            String currentLineSuffix = document.getText(new TextRange(lineStartOffset, lineEndOffset)).trim();

            if (activeCompletion != null){

                boolean b = activeCompletion.getItem().getInsertText().trim().startsWith(currentLineSuffix);
                if (b == false){
                    autocompleteService.clearCompletion(editor);

                }
                else {
                    List<Inlay<?>> inlineElementssInRange = editor.getInlayModel().getInlineElementsInRange(lineStartOffset, lineEndOffset);
                    for (var inlineElementsInRange : inlineElementssInRange){
                    if (inlineElementsInRange instanceof ZestInlayRenderer.InlineCompletionRenderer){
                        if (activeCompletion.getInlay().getRenderer() instanceof  ZestInlayRenderer.InlineCompletionRenderer ){
                            ZestInlayRenderer.InlineCompletionRenderer r = (ZestInlayRenderer.InlineCompletionRenderer) activeCompletion.getInlay().getRenderer();
                            String newText = activeCompletion.getItem().getInsertText().trim().replace(currentLineSuffix,"");
                            r.text = newText;
                            activeCompletion.getInlay().repaint();
                        }
                    }}
                }
            }
            else if (shouldTriggerCodeCompletion(insertedText, event)) {
                LOG.debug("Triggering CODE completion for insertion: '{}'", insertedText);
                autocompleteService.triggerAutocomplete(editor);
            } else {
                LOG.debug("Skipping trigger for insertion: '{}'", insertedText);
                // ✅ NEW: Check if this might be a comment/javadoc trigger for future
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
     * ✅ ENHANCED: Only triggers for CODE, explicitly blocks comments/javadocs
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

        // ❌ BLOCK: Comments and javadocs are explicitly blocked
        if (isInCommentOrJavadoc(event.getDocument(), offset)) {
            LOG.debug("Blocking completion - cursor is in comment/javadoc area");
            return false;
        }

        // ❌ BLOCK: If this insertion creates a comment/javadoc start
        if (isCommentOrJavadocStart(insertedText, event.getDocument(), offset)) {
            LOG.debug("Blocking completion - insertion creates comment/javadoc start");
            return false;
        }

        // ✅ ALLOW: Only pure code completion
        return true;
    }

    /**
     * ✅ ENHANCED: Detects if cursor is in comment or javadoc area
     */
    private boolean isInCommentOrJavadoc(Document document, int offset) {
        try {
            // Get current line text
            int lineNumber = document.getLineNumber(offset);
            int lineStart = document.getLineStartOffset(lineNumber);
            String lineText = document.getText().substring(lineStart, offset);

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
     * ✅ NEW: Detects if the insertion creates a comment or javadoc start
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
     * ✅ NEW: Placeholder for future comment/javadoc support
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
//                LOG.info("JAVADOC TRIGGER DETECTED: '{}' - Add your custom logic here", lineText.trim());
                // Example: autocompleteService.triggerJavadocCompletion(editor);
            }

            // TODO: Add your custom logic for comment triggers
            if (lineText.trim().endsWith("//")) {
//                LOG.info("COMMENT TRIGGER DETECTED: '{}' - Add your custom logic here", lineText.trim());
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