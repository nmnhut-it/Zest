package com.zps.zest.autocomplete.utils;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.zps.zest.autocomplete.AcceptType;
import com.zps.zest.autocomplete.ZestCompletionData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Utility class to eliminate completion state-related code duplication.
 * Provides common patterns for completion processing and text manipulation.
 */
public class CompletionStateUtils {
    private static final Logger LOG = Logger.getInstance(CompletionStateUtils.class);

    /**
     * Validates that a completion is still applicable at the current cursor position.
     * Eliminates repeated validation logic.
     */
    public static boolean isCompletionValid(@NotNull ZestCompletionData.PendingCompletion completion) {
        if (completion.isDisposed()) {
            return false;
        }
        
        Editor editor = completion.getEditor();
        if (!EditorUtils.isEditorReadable(editor)) {
            return false;
        }
        
        try {
            int currentOffset = ThreadingUtils.safeReadAction(() -> EditorUtils.safeGetCaretOffset(editor));
            return completion.getItem().isValidAt(currentOffset);
        } catch (Exception e) {
            LOG.warn("Error validating completion", e);
            return false;
        }
    }

    /**
     * Calculates the text that should be inserted based on current editor state.
     * Eliminates repeated prefix calculation logic.
     */
    @NotNull
    public static CompletionInsertionData calculateInsertionData(@NotNull Editor editor, 
                                                                @NotNull ZestCompletionData.CompletionItem item,
                                                                @NotNull AcceptType acceptType) {
        return ThreadingUtils.safeReadAction(() -> {
            try {
                int currentOffset = EditorUtils.safeGetCaretOffset(editor);
                Document document = editor.getDocument();

                // Re-calculate prefix to handle any changes since completion was created
                int lineNumber = document.getLineNumber(currentOffset);
                int lineStart = document.getLineStartOffset(lineNumber);
                String beforeCursor = EditorUtils.safeGetText(editor, lineStart, currentOffset);

                // Re-clean the completion using current state
                String cleaned = SmartPrefixRemover.removeRedundantPrefix(beforeCursor, item.getInsertText());

                // Handle accept type (full, word, line)
                String textToAccept = AcceptType.extractAcceptableText(cleaned, acceptType);
                String remainingText = null;
                
                if (acceptType != AcceptType.FULL) {
                    remainingText = AcceptType.calculateRemainingText(cleaned, textToAccept);
                }

                return new CompletionInsertionData(
                    textToAccept,
                    remainingText,
                    currentOffset,
                    beforeCursor,
                    cleaned
                );
            } catch (Exception e) {
                LOG.warn("Error calculating insertion data", e);
                return new CompletionInsertionData("", null, 0, "", "");
            }
        });
    }

    /**
     * Checks if the given text insertion would be compatible with the current completion.
     * Eliminates repeated compatibility checking logic.
     */
    public static boolean isInsertionCompatible(@NotNull String insertedText,
                                              @NotNull Editor editor,
                                              @NotNull ZestCompletionData.PendingCompletion completion) {
        return ThreadingUtils.safeReadAction(() -> {
            try {
                String currentLineText = EditorUtils.safeGetCurrentLineText(editor).trim();
                String completionText = completion.getItem().getInsertText().trim();
                
                // If current line is a prefix of completion text, it's compatible
                if (completionText.startsWith(currentLineText)) {
                    return true;
                }
                
                // If completion text is a prefix of current line, it's also compatible (user typed ahead)
                if (currentLineText.startsWith(completionText)) {
                    return true;
                }
                
                return false;
            } catch (Exception e) {
                LOG.warn("Error checking insertion compatibility", e);
                return false;
            }
        });
    }

    /**
     * Determines the best accept type based on completion content and context.
     * Eliminates repeated accept type logic.
     */
    @NotNull
    public static AcceptType determineOptimalAcceptType(@NotNull String remainingText, int tabCount) {
        AcceptType acceptType;
        
        // Determine most appropriate accept type based on remaining text and tab count
        switch (tabCount) {
            case 1: // First tab press - word
                acceptType = AcceptType.WORD;
                break;
                
            case 2: // Second tab press - line
                acceptType = AcceptType.LINE;
                break;
                
            default: // Third or more tab presses - full
                acceptType = AcceptType.FULL;
        }
        
        // Smart fallback: if word is very short but full text isn't, go to next level
        if (acceptType == AcceptType.WORD) {
            String wordText = AcceptType.extractAcceptableText(remainingText, AcceptType.WORD);
            if (wordText.length() <= 2 && remainingText.length() > wordText.length() * 3) {
                acceptType = AcceptType.LINE;
            }
        }
        
        // Smart fallback: if line is very short but full text isn't, go to next level
        if (acceptType == AcceptType.LINE) {
            String lineText = AcceptType.extractAcceptableText(remainingText, AcceptType.LINE);
            if (lineText.length() <= 3 && remainingText.length() > lineText.length() * 3) {
                acceptType = AcceptType.FULL;
            }
        }
        
        return acceptType;
    }

    /**
     * Data class representing completion insertion calculations.
     */
    public static class CompletionInsertionData {
        private final String textToAccept;
        private final String remainingText;
        private final int insertionOffset;
        private final String beforeCursor;
        private final String cleanedCompletion;
        
        public CompletionInsertionData(@NotNull String textToAccept,
                                     @Nullable String remainingText,
                                     int insertionOffset,
                                     @NotNull String beforeCursor,
                                     @NotNull String cleanedCompletion) {
            this.textToAccept = textToAccept;
            this.remainingText = remainingText;
            this.insertionOffset = insertionOffset;
            this.beforeCursor = beforeCursor;
            this.cleanedCompletion = cleanedCompletion;
        }
        
        @NotNull
        public String getTextToAccept() { return textToAccept; }
        
        @Nullable
        public String getRemainingText() { return remainingText; }
        
        public int getInsertionOffset() { return insertionOffset; }
        
        @NotNull
        public String getBeforeCursor() { return beforeCursor; }
        
        @NotNull
        public String getCleanedCompletion() { return cleanedCompletion; }
        
        public boolean hasRemainingText() {
            return remainingText != null && !remainingText.trim().isEmpty();
        }
    }
}
