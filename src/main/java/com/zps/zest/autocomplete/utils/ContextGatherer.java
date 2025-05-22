package com.zps.zest.autocomplete.utils;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Enhanced context gatherer following Tabby ML patterns.
 * Provides focused, relevant context for better completions.
 */
public class ContextGatherer {
    private static final int MAX_PREFIX_LINES = 10;
    private static final int MAX_SUFFIX_LINES = 5;
    private static final int MAX_LINE_LENGTH = 120;

    /**
     * Enhanced cursor context gathering following Tabby ML patterns.
     */
    public static CursorContext gatherEnhancedCursorContext(@NotNull Editor editor, @Nullable PsiFile psiFile) {
        Document document = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();

        // Get current line info
        int currentLine = document.getLineNumber(offset);
        int lineStartOffset = document.getLineStartOffset(currentLine);
        int lineEndOffset = document.getLineEndOffset(currentLine);

        // Calculate prefix and suffix on current line
        String currentLineText = document.getText(new TextRange(lineStartOffset, lineEndOffset));
        int positionInLine = offset - lineStartOffset;

        String currentLinePrefix = currentLineText.substring(0, positionInLine);
        String currentLineSuffix = currentLineText.substring(positionInLine);

        // Gather multi-line prefix context
        String prefixContext = gatherPrefixContext(document, currentLine, currentLinePrefix);

        // Gather suffix context
        String suffixContext = gatherSuffixContext(document, currentLine, currentLineSuffix);

        // Detect indentation
        String indentation = detectIndentation(currentLineText);

        return new CursorContext(
                prefixContext,
                suffixContext,
                currentLinePrefix,
                currentLineSuffix,
                indentation,
                offset,
                currentLine
        );
    }

    /**
     * Gathers cursor context (backward compatibility method).
     */
    public static String gatherCursorContext(@NotNull Editor editor, @Nullable PsiFile psiFile) {
        CursorContext context = gatherEnhancedCursorContext(editor, psiFile);
        return context.getPrefixContext() + "<CURSOR>" + context.getSuffixContext();
    }

    /**
     * Gathers prefix context (code before cursor).
     */
    private static String gatherPrefixContext(Document document, int currentLine, String currentLinePrefix) {
        StringBuilder prefix = new StringBuilder();

        // Start from a few lines above, but not too many
        int startLine = Math.max(0, currentLine - MAX_PREFIX_LINES);

        // Add preceding lines
        for (int line = startLine; line < currentLine; line++) {
            int lineStart = document.getLineStartOffset(line);
            int lineEnd = document.getLineEndOffset(line);
            String lineText = document.getText(new TextRange(lineStart, lineEnd));

            // Filter out very long lines or empty lines at the beginning
            if (lineText.trim().isEmpty() && prefix.length() == 0) {
                continue;
            }

            if (lineText.length() > MAX_LINE_LENGTH) {
                lineText = lineText.substring(0, MAX_LINE_LENGTH) + "...";
            }

            prefix.append(lineText).append("\n");
        }

        // Add current line prefix
        prefix.append(currentLinePrefix);

        return prefix.toString();
    }

    /**
     * Gathers suffix context (code after cursor).
     */
    private static String gatherSuffixContext(Document document, int currentLine, String currentLineSuffix) {
        StringBuilder suffix = new StringBuilder();

        // Add current line suffix if not empty
        if (!currentLineSuffix.trim().isEmpty()) {
            suffix.append(currentLineSuffix);
        }

        // Add following lines
        int totalLines = document.getLineCount();
        int endLine = Math.min(totalLines, currentLine + MAX_SUFFIX_LINES + 1);

        for (int line = currentLine + 1; line < endLine; line++) {
            int lineStart = document.getLineStartOffset(line);
            int lineEnd = document.getLineEndOffset(line);
            String lineText = document.getText(new TextRange(lineStart, lineEnd));

            if (lineText.length() > MAX_LINE_LENGTH) {
                lineText = lineText.substring(0, MAX_LINE_LENGTH) + "...";
            }

            suffix.append("\n").append(lineText);
        }

        return suffix.toString();
    }

    /**
     * Detects the indentation pattern of the current line.
     */
    private static String detectIndentation(String lineText) {
        StringBuilder indentation = new StringBuilder();

        for (char ch : lineText.toCharArray()) {
            if (ch == ' ' || ch == '\t') {
                indentation.append(ch);
            } else {
                break;
            }
        }

        return indentation.toString();
    }

    /**
     * Gathers broader file context for the prompt.
     */
    public static String gatherFileContext(@NotNull Editor editor, @Nullable PsiFile psiFile) {
        Document document = editor.getDocument();
        String fullText = document.getText();

        // For large files, extract key structural elements
        if (fullText.length() > 3000) {
            return extractStructuralContext(fullText);
        }

        return fullText;
    }

    /**
     * Extracts key structural elements from large files.
     */
    private static String extractStructuralContext(String fullText) {
        StringBuilder context = new StringBuilder();
        String[] lines = fullText.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();

            // Include package, imports, class/interface declarations, method signatures
            if (trimmed.startsWith("package ") ||
                    trimmed.startsWith("import ") ||
                    trimmed.contains("class ") ||
                    trimmed.contains("interface ") ||
                    (trimmed.contains("(") && (trimmed.contains("public ") ||
                            trimmed.contains("private ") || trimmed.contains("protected ")))) {

                context.append(line).append("\n");
            }
        }

        return context.toString();
    }

    /**
     * Represents cursor context information.
     */
    public static class CursorContext {
        private final String prefixContext;
        private final String suffixContext;
        private final String currentLinePrefix;
        private final String currentLineSuffix;
        private final String indentation;
        private final int offset;
        private final int lineNumber;

        public CursorContext(String prefixContext, String suffixContext,
                             String currentLinePrefix, String currentLineSuffix,
                             String indentation, int offset, int lineNumber) {
            this.prefixContext = prefixContext;
            this.suffixContext = suffixContext;
            this.currentLinePrefix = currentLinePrefix;
            this.currentLineSuffix = currentLineSuffix;
            this.indentation = indentation;
            this.offset = offset;
            this.lineNumber = lineNumber;
        }

        // Getters
        public String getPrefixContext() {
            return prefixContext;
        }

        public String getSuffixContext() {
            return suffixContext;
        }

        public String getCurrentLinePrefix() {
            return currentLinePrefix;
        }

        public String getCurrentLineSuffix() {
            return currentLineSuffix;
        }

        public String getIndentation() {
            return indentation;
        }

        public int getOffset() {
            return offset;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        /**
         * Checks if we're at the start of a line (only whitespace before cursor).
         */
        public boolean isAtLineStart() {
            return currentLinePrefix.trim().isEmpty();
        }

        /**
         * Checks if we're likely inside a method body.
         */
        public boolean isInMethodBody() {
            return prefixContext.contains("{") &&
                    prefixContext.lastIndexOf("{") > prefixContext.lastIndexOf("}");
        }

        /**
         * Gets the expected indentation for the next line.
         */
        public String getNextLineIndentation() {
            if (currentLinePrefix.trim().endsWith("{")) {
                // Add one level of indentation
                return indentation + (indentation.contains("\t") ? "\t" : "    ");
            }
            return indentation;
        }
    }
}