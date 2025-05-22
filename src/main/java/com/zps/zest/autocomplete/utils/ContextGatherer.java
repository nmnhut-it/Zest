package com.zps.zest.autocomplete.utils;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;

/**
 * Utility class for gathering context information around the cursor position
 * to provide better autocomplete suggestions.
 */
public class ContextGatherer {
    private static final int CONTEXT_LINES_BEFORE = 10;
    private static final int CONTEXT_LINES_AFTER = 5;
    private static final int MAX_CONTEXT_LENGTH = 1500; // Characters
    
    /**
     * Gathers context around the current cursor position.
     * 
     * @param editor The editor instance
     * @param selectedText Any currently selected text
     * @return Context string with cursor position marked
     */
    public static String gatherCursorContext(Editor editor, String selectedText) {
        if (editor == null) {
            return "";
        }
        
        Document document = editor.getDocument();
        int caretOffset = editor.getCaretModel().getOffset();
        int currentLine = document.getLineNumber(caretOffset);
        
        // Calculate context range
        int startLine = Math.max(0, currentLine - CONTEXT_LINES_BEFORE);
        int endLine = Math.min(document.getLineCount() - 1, currentLine + CONTEXT_LINES_AFTER);
        
        StringBuilder context = new StringBuilder();
        
        // Add lines before cursor
        for (int line = startLine; line < currentLine; line++) {
            context.append(getLineText(document, line)).append("\n");
        }
        
        // Add current line with cursor marker
        String currentLineText = getLineText(document, currentLine);
        int lineStartOffset = document.getLineStartOffset(currentLine);
        int cursorPosInLine = caretOffset - lineStartOffset;
        
        if (cursorPosInLine <= currentLineText.length()) {
            String beforeCursor = currentLineText.substring(0, cursorPosInLine);
            String afterCursor = currentLineText.substring(cursorPosInLine);
            
            context.append(beforeCursor);
            context.append("<CURSOR>"); // Marker for cursor position
            context.append(afterCursor);
            context.append("\n");
        } else {
            context.append(currentLineText).append("<CURSOR>\n");
        }
        
        // Add lines after cursor
        for (int line = currentLine + 1; line <= endLine; line++) {
            context.append(getLineText(document, line)).append("\n");
        }
        
        // Trim to max length if necessary
        String result = context.toString();
        if (result.length() > MAX_CONTEXT_LENGTH) {
            result = trimToMaxLength(result, MAX_CONTEXT_LENGTH);
        }
        
        return result;
    }
    
    /**
     * Gathers broader file context for better understanding.
     * 
     * @param editor The editor instance
     * @return File context including imports, class declaration, and method signatures
     */
    public static String gatherFileContext(Editor editor) {
        if (editor == null) {
            return "";
        }
        
        Document document = editor.getDocument();
        String fullText = document.getText();
        
        // For very large files, limit context
        if (fullText.length() > MAX_CONTEXT_LENGTH * 3) {
            return gatherReducedFileContext(document);
        }
        
        return fullText;
    }
    
    /**
     * Gets the text of a specific line, handling edge cases.
     */
    private static String getLineText(Document document, int lineNumber) {
        if (lineNumber < 0 || lineNumber >= document.getLineCount()) {
            return "";
        }
        
        int lineStart = document.getLineStartOffset(lineNumber);
        int lineEnd = document.getLineEndOffset(lineNumber);
        
        return document.getText(new TextRange(lineStart, lineEnd));
    }
    
    /**
     * Trims text to maximum length while preserving the cursor marker.
     */
    private static String trimToMaxLength(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        
        int cursorPos = text.indexOf("<CURSOR>");
        if (cursorPos == -1) {
            return text.substring(0, maxLength);
        }
        
        // Try to center around cursor position
        int halfLength = maxLength / 2;
        int start = Math.max(0, cursorPos - halfLength);
        int end = Math.min(text.length(), start + maxLength);
        
        // Adjust start if we're at the end
        if (end - start < maxLength) {
            start = Math.max(0, end - maxLength);
        }
        
        return text.substring(start, end);
    }
    
    /**
     * Gathers reduced context for large files (imports, class structure, current method).
     */
    private static String gatherReducedFileContext(Document document) {
        String fullText = document.getText();
        StringBuilder context = new StringBuilder();
        
        // Add package and imports (first ~500 chars)
        String[] lines = fullText.split("\n");
        int importEndLine = 0;
        
        for (int i = 0; i < Math.min(lines.length, 50); i++) {
            String line = lines[i].trim();
            if (line.startsWith("package ") || line.startsWith("import ") || line.isEmpty() || line.startsWith("//")) {
                context.append(lines[i]).append("\n");
                importEndLine = i;
            } else if (line.startsWith("public class") || line.startsWith("class ") || line.startsWith("public interface")) {
                context.append(lines[i]).append("\n");
                break;
            }
        }
        
        // Add current method context (if we can find it)
        // This is a simplified approach - a more sophisticated version would use PSI
        context.append("\n// ... (file content abbreviated for context) ...\n");
        
        return context.toString();
    }
    
    /**
     * Determines if the current position is at the beginning of a line (after whitespace).
     */
    public static boolean isAtLineStart(Editor editor) {
        if (editor == null) {
            return false;
        }
        
        Document document = editor.getDocument();
        int caretOffset = editor.getCaretModel().getOffset();
        int currentLine = document.getLineNumber(caretOffset);
        int lineStart = document.getLineStartOffset(currentLine);
        
        String textBeforeCursor = document.getText(new TextRange(lineStart, caretOffset));
        return textBeforeCursor.trim().isEmpty();
    }
    
    /**
     * Gets the indentation of the current line.
     */
    public static String getCurrentIndentation(Editor editor) {
        if (editor == null) {
            return "";
        }
        
        Document document = editor.getDocument();
        int caretOffset = editor.getCaretModel().getOffset();
        int currentLine = document.getLineNumber(caretOffset);
        
        String lineText = getLineText(document, currentLine);
        int indentEnd = 0;
        
        while (indentEnd < lineText.length() && Character.isWhitespace(lineText.charAt(indentEnd))) {
            indentEnd++;
        }
        
        return lineText.substring(0, indentEnd);
    }
}
