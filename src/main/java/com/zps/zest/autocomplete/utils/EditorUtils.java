package com.zps.zest.autocomplete.utils;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Utility class to eliminate editor-related code duplication.
 * Provides common patterns for editor operations with proper error handling.
 */
public class EditorUtils {
    private static final Logger LOG = Logger.getInstance(EditorUtils.class);

    /**
     * Safely gets the current caret offset.
     * Eliminates repeated null checks and error handling.
     */
    public static int safeGetCaretOffset(@NotNull Editor editor) {
        return safeGetCaretOffset(editor, 0);
    }

    /**
     * Safely gets the current caret offset with a default value.
     */
    public static int safeGetCaretOffset(@NotNull Editor editor, int defaultOffset) {
        if (editor.isDisposed()) {
            LOG.warn("Attempting to get caret offset from disposed editor");
            return defaultOffset;
        }
        
        try {
            return editor.getCaretModel().getOffset();
        } catch (Exception e) {
            LOG.warn("Error getting caret offset", e);
            return defaultOffset;
        }
    }

    /**
     * Safely gets the current line number.
     */
    public static int safeGetCurrentLine(@NotNull Editor editor) {
        return safeGetCurrentLine(editor, 0);
    }

    /**
     * Safely gets the current line number with a default value.
     */
    public static int safeGetCurrentLine(@NotNull Editor editor, int defaultLine) {
        if (editor.isDisposed()) {
            return defaultLine;
        }
        
        try {
            int offset = safeGetCaretOffset(editor, 0);
            return editor.getDocument().getLineNumber(offset);
        } catch (Exception e) {
            LOG.warn("Error getting current line", e);
            return defaultLine;
        }
    }

    /**
     * Safely gets text from the current line up to the cursor.
     */
    @NotNull
    public static String safeGetCurrentLinePrefix(@NotNull Editor editor) {
        return safeGetCurrentLinePrefix(editor, "");
    }

    /**
     * Safely gets text from the current line up to the cursor with default value.
     */
    @NotNull
    public static String safeGetCurrentLinePrefix(@NotNull Editor editor, @NotNull String defaultValue) {
        if (editor.isDisposed()) {
            return defaultValue;
        }
        
        try {
            Document document = editor.getDocument();
            int currentOffset = safeGetCaretOffset(editor);
            int lineNumber = document.getLineNumber(currentOffset);
            int lineStart = document.getLineStartOffset(lineNumber);
            
            if (currentOffset <= lineStart) {
                return "";
            }
            
            return document.getText(TextRange.from(lineStart, currentOffset - lineStart));
        } catch (Exception e) {
            LOG.warn("Error getting current line prefix", e);
            return defaultValue;
        }
    }

    /**
     * Safely gets the entire current line text.
     */
    @NotNull
    public static String safeGetCurrentLineText(@NotNull Editor editor) {
        return safeGetCurrentLineText(editor, "");
    }

    /**
     * Safely gets the entire current line text with default value.
     */
    @NotNull
    public static String safeGetCurrentLineText(@NotNull Editor editor, @NotNull String defaultValue) {
        if (editor.isDisposed()) {
            return defaultValue;
        }
        
        try {
            Document document = editor.getDocument();
            int currentOffset = safeGetCaretOffset(editor);
            int lineNumber = document.getLineNumber(currentOffset);
            int lineStart = document.getLineStartOffset(lineNumber);
            int lineEnd = document.getLineEndOffset(lineNumber);
            
            return document.getText(TextRange.from(lineStart, lineEnd - lineStart));
        } catch (Exception e) {
            LOG.warn("Error getting current line text", e);
            return defaultValue;
        }
    }

    /**
     * Safely gets text in a range with bounds checking.
     */
    @NotNull
    public static String safeGetText(@NotNull Editor editor, int start, int end) {
        return safeGetText(editor, start, end, "");
    }

    /**
     * Safely gets text in a range with bounds checking and default value.
     */
    @NotNull
    public static String safeGetText(@NotNull Editor editor, int start, int end, @NotNull String defaultValue) {
        if (editor.isDisposed()) {
            return defaultValue;
        }
        
        try {
            Document document = editor.getDocument();
            int docLength = document.getTextLength();
            
            // Bounds checking
            start = Math.max(0, Math.min(start, docLength));
            end = Math.max(start, Math.min(end, docLength));
            
            if (start == end) {
                return "";
            }
            
            return document.getText(TextRange.from(start, end - start));
        } catch (Exception e) {
            LOG.warn("Error getting text range", e);
            return defaultValue;
        }
    }

    /**
     * Safely checks if an offset is valid for the document.
     */
    public static boolean isValidOffset(@NotNull Editor editor, int offset) {
        if (editor.isDisposed()) {
            return false;
        }
        
        try {
            Document document = editor.getDocument();
            return offset >= 0 && offset <= document.getTextLength();
        } catch (Exception e) {
            LOG.warn("Error checking offset validity", e);
            return false;
        }
    }

    /**
     * Safely gets the document length.
     */
    public static int safeGetDocumentLength(@NotNull Editor editor) {
        return safeGetDocumentLength(editor, 0);
    }

    /**
     * Safely gets the document length with default value.
     */
    public static int safeGetDocumentLength(@NotNull Editor editor, int defaultLength) {
        if (editor.isDisposed()) {
            return defaultLength;
        }
        
        try {
            return editor.getDocument().getTextLength();
        } catch (Exception e) {
            LOG.warn("Error getting document length", e);
            return defaultLength;
        }
    }

    /**
     * Gets the project-relative file path or a fallback identifier.
     */
    @NotNull
    public static String safeGetFilePath(@NotNull Editor editor) {
        if (editor.isDisposed()) {
            return "disposed-editor";
        }
        
        try {
            if (editor.getVirtualFile() != null) {
                return editor.getVirtualFile().getPath();
            }
            return "unknown-file-" + editor.hashCode();
        } catch (Exception e) {
            LOG.warn("Error getting file path", e);
            return "error-file-" + editor.hashCode();
        }
    }

    /**
     * Checks if the editor is in a valid state for operations.
     */
    public static boolean isEditorValid(@Nullable Editor editor) {
        return editor != null && !editor.isDisposed() && editor.getDocument().isWritable();
    }

    /**
     * Checks if the editor is in a valid state for read operations.
     */
    public static boolean isEditorReadable(@Nullable Editor editor) {
        return editor != null && !editor.isDisposed();
    }
}
