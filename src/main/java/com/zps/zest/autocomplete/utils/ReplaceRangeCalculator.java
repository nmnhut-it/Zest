package com.zps.zest.autocomplete.utils;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.zps.zest.autocomplete.ZestCompletionData;
import org.jetbrains.annotations.NotNull;

/**
 * Sophisticated replace range calculator following Tabby ML patterns.
 * Handles complex text replacement scenarios with intelligent analysis.
 */
public class ReplaceRangeCalculator {
    private static final Logger LOG = Logger.getInstance(ReplaceRangeCalculator.class);

    /**
     * Represents the result of replace range calculation.
     */
    public static class ReplaceRangeResult {
        private final ZestCompletionData.Range replaceRange;
        private final ReplacementType type;
        private final String insertPart;
        private final String appendPart;
        private final boolean hasLineSuffixReplacement;

        public ReplaceRangeResult(ZestCompletionData.Range replaceRange, ReplacementType type,
                                  String insertPart, String appendPart, boolean hasLineSuffixReplacement) {
            this.replaceRange = replaceRange;
            this.type = type;
            this.insertPart = insertPart;
            this.appendPart = appendPart;
            this.hasLineSuffixReplacement = hasLineSuffixReplacement;
        }

        public ZestCompletionData.Range getReplaceRange() { return replaceRange; }
        public ReplacementType getType() { return type; }
        public String getInsertPart() { return insertPart; }
        public String getAppendPart() { return appendPart; }
        public boolean hasLineSuffixReplacement() { return hasLineSuffixReplacement; }
    }

    /**
     * Types of replacement scenarios.
     */
    public enum ReplacementType {
        NO_REPLACEMENT,      // No text to replace (simple insertion)
        SINGLE_CHAR,         // Replace single character with smart matching
        MULTIPLE_CHARS,      // Replace multiple characters with markup
        LINE_SUFFIX         // Replace entire line suffix
    }

    /**
     * Calculates sophisticated replace range following Tabby ML logic.
     * 
     * @param editor The editor instance
     * @param offset Current cursor offset
     * @param insertText The completion text to insert
     * @return ReplaceRangeResult with detailed replacement information
     */
    public static ReplaceRangeResult calculateReplaceRange(@NotNull Editor editor, int offset, 
                                                           @NotNull String insertText) {
        Document document = editor.getDocument();
        
        // Get current line information
        int currentLine = document.getLineNumber(offset);
        int lineStartOffset = document.getLineStartOffset(currentLine);
        int lineEndOffset = document.getLineEndOffset(currentLine);
        
        // Calculate line suffix (text after cursor on current line)
        String currentLineSuffix = "";
        if (offset < lineEndOffset) {
            currentLineSuffix = document.getText(new TextRange(offset, lineEndOffset));
        }
        
        // Calculate replacement based on suffix length
        int suffixReplaceLength = calculateSuffixReplaceLength(insertText, currentLineSuffix);
        
        if (suffixReplaceLength == 0) {
            return handleNoReplacement(offset, insertText, currentLineSuffix);
        } else if (suffixReplaceLength == 1) {
            return handleSingleCharReplacement(offset, insertText, currentLineSuffix);
        } else {
            return handleMultipleCharReplacement(offset, insertText, currentLineSuffix, suffixReplaceLength);
        }
    }

    /**
     * Calculates how many characters from the line suffix should be replaced.
     */
    private static int calculateSuffixReplaceLength(String insertText, String lineSuffix) {
        if (lineSuffix.isEmpty() || insertText.isEmpty()) {
            return 0;
        }

        // Find the longest common prefix between insert text and line suffix
        int maxReplaceLength = Math.min(insertText.length(), lineSuffix.length());
        
        // Look for overlapping content that should be replaced
        for (int i = 1; i <= maxReplaceLength; i++) {
            String suffixPart = lineSuffix.substring(0, i);
            if (insertText.contains(suffixPart)) {
                // Found overlapping content, determine best replacement length
                return i;
            }
        }
        
        // Check for single character smart matching
        if (lineSuffix.length() > 0 && insertText.length() > 0) {
            char firstSuffixChar = lineSuffix.charAt(0);
            if (insertText.indexOf(firstSuffixChar) >= 0) {
                return 1; // Single character replacement
            }
        }
        
        return 0; // No replacement needed
    }

    /**
     * Handles the case where no replacement is needed (simple insertion).
     */
    private static ReplaceRangeResult handleNoReplacement(int offset, String insertText, String currentLineSuffix) {
        ZestCompletionData.Range range = new ZestCompletionData.Range(offset, offset);
        
        // Check if we need to handle line suffix for multi-line completions
        boolean hasLineSuffixHandling = insertText.contains("\n") && !currentLineSuffix.trim().isEmpty();
        
        return new ReplaceRangeResult(
            range, 
            ReplacementType.NO_REPLACEMENT,
            insertText,
            "",
            hasLineSuffixHandling
        );
    }

    /**
     * Handles single character replacement with smart matching.
     * This implements Tabby's sophisticated single-character logic.
     */
    private static ReplaceRangeResult handleSingleCharReplacement(int offset, String insertText, String currentLineSuffix) {
        if (currentLineSuffix.isEmpty()) {
            return handleNoReplacement(offset, insertText, currentLineSuffix);
        }

        char replaceChar = currentLineSuffix.charAt(0);
        String firstLine = insertText.split("\n")[0];
        
        // Check if first line starts with the character to replace
        String insertPart;
        String appendPart;
        
        if (firstLine.startsWith(String.valueOf(replaceChar))) {
            // First line starts with the char - no insertion before the char
            insertPart = "";
            appendPart = firstLine.substring(1); // Everything after the char
        } else {
            // First line doesn't start with the char - find where it appears
            int charIndex = firstLine.indexOf(replaceChar);
            if (charIndex >= 0) {
                insertPart = firstLine.substring(0, charIndex);
                appendPart = firstLine.substring(charIndex + 1);
            } else {
                // Character not found in first line - treat as full insertion
                insertPart = firstLine;
                appendPart = "";
            }
        }

        ZestCompletionData.Range range = new ZestCompletionData.Range(offset, offset + 1);
        
        // Check for line suffix handling in multi-line scenarios
        boolean hasLineSuffixHandling = insertText.contains("\n") && currentLineSuffix.length() > 1;
        
        return new ReplaceRangeResult(
            range,
            ReplacementType.SINGLE_CHAR,
            insertPart,
            appendPart,
            hasLineSuffixHandling
        );
    }

    /**
     * Handles multiple character replacement with markup approach.
     */
    private static ReplaceRangeResult handleMultipleCharReplacement(int offset, String insertText, 
                                                                   String currentLineSuffix, int suffixReplaceLength) {
        String firstLine = insertText.split("\n")[0];
        
        ZestCompletionData.Range range = new ZestCompletionData.Range(offset, offset + suffixReplaceLength);
        
        // For multiple chars, we use markup highlighting rather than complex text matching
        boolean hasLineSuffixHandling = insertText.contains("\n") && 
                                       currentLineSuffix.length() > suffixReplaceLength;
        
        return new ReplaceRangeResult(
            range,
            ReplacementType.MULTIPLE_CHARS,
            firstLine,
            "",
            hasLineSuffixHandling
        );
    }

    /**
     * Creates visual markup information for replace ranges.
     */
    public static class MarkupInfo {
        private final TextRange range;
        private final MarkupType type;

        public MarkupInfo(TextRange range, MarkupType type) {
            this.range = range;
            this.type = type;
        }

        public TextRange getRange() { return range; }
        public MarkupType getType() { return type; }
    }

    public enum MarkupType {
        REPLACE_HIGHLIGHT,    // Text that will be replaced
        LINE_SUFFIX_REPLACE   // Line suffix that will be replaced in multi-line
    }

    /**
     * Calculates markup ranges for visual feedback.
     */
    public static MarkupInfo[] calculateMarkupRanges(Editor editor, int offset, ReplaceRangeResult result) {
        if (result.getType() == ReplacementType.NO_REPLACEMENT && !result.hasLineSuffixReplacement()) {
            return new MarkupInfo[0]; // No markup needed
        }

        Document document = editor.getDocument();
        int currentLine = document.getLineNumber(offset);
        int lineEndOffset = document.getLineEndOffset(currentLine);

        switch (result.getType()) {
            case SINGLE_CHAR:
                if (result.hasLineSuffixReplacement()) {
                    // Mark both the single char and the line suffix
                    MarkupInfo charMarkup = new MarkupInfo(
                        new TextRange(offset, offset + 1), 
                        MarkupType.REPLACE_HIGHLIGHT
                    );
                    MarkupInfo suffixMarkup = new MarkupInfo(
                        new TextRange(offset + 1, lineEndOffset),
                        MarkupType.LINE_SUFFIX_REPLACE
                    );
                    return new MarkupInfo[]{charMarkup, suffixMarkup};
                } else {
                    return new MarkupInfo[]{
                        new MarkupInfo(result.getReplaceRange().toTextRange(), MarkupType.REPLACE_HIGHLIGHT)
                    };
                }

            case MULTIPLE_CHARS:
                if (result.hasLineSuffixReplacement()) {
                    int replaceEnd = result.getReplaceRange().getEnd();
                    MarkupInfo replaceMarkup = new MarkupInfo(
                        result.getReplaceRange().toTextRange(),
                        MarkupType.REPLACE_HIGHLIGHT
                    );
                    MarkupInfo suffixMarkup = new MarkupInfo(
                        new TextRange(replaceEnd, lineEndOffset),
                        MarkupType.LINE_SUFFIX_REPLACE
                    );
                    return new MarkupInfo[]{replaceMarkup, suffixMarkup};
                } else {
                    return new MarkupInfo[]{
                        new MarkupInfo(result.getReplaceRange().toTextRange(), MarkupType.REPLACE_HIGHLIGHT)
                    };
                }

            case LINE_SUFFIX:
                return new MarkupInfo[]{
                    new MarkupInfo(new TextRange(offset, lineEndOffset), MarkupType.LINE_SUFFIX_REPLACE)
                };

            case NO_REPLACEMENT:
                if (result.hasLineSuffixReplacement()) {
                    return new MarkupInfo[]{
                        new MarkupInfo(new TextRange(offset, lineEndOffset), MarkupType.LINE_SUFFIX_REPLACE)
                    };
                }
                return new MarkupInfo[0];

            default:
                return new MarkupInfo[0];
        }
    }

    /**
     * Validates a replace range result for consistency.
     */
    public static boolean isValidReplaceRange(ReplaceRangeResult result, String insertText) {
        if (result == null || result.getReplaceRange() == null) {
            return false;
        }

        ZestCompletionData.Range range = result.getReplaceRange();
        
        // Basic range validation
        if (range.getStart() < 0 || range.getEnd() < range.getStart()) {
            return false;
        }

        // Type-specific validation
        switch (result.getType()) {
            case NO_REPLACEMENT:
                return range.getLength() == 0;
                
            case SINGLE_CHAR:
                return range.getLength() == 1;
                
            case MULTIPLE_CHARS:
                return range.getLength() > 1;
                
            case LINE_SUFFIX:
                return range.getLength() >= 0;
                
            default:
                return false;
        }
    }

    /**
     * Logs detailed information about replace range calculation for debugging.
     */
    public static void logReplaceRangeDetails(ReplaceRangeResult result, String insertText, String lineSuffix) {
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format(
                "Replace Range Calculation: type=%s, range=%s, insertPart='%s', appendPart='%s', " +
                "hasLineSuffix=%s, insertText='%s', lineSuffix='%s'",
                result.getType(),
                result.getReplaceRange(),
                result.getInsertPart(),
                result.getAppendPart(),
                result.hasLineSuffixReplacement(),
                insertText.length() > 50 ? insertText.substring(0, 50) + "..." : insertText,
                lineSuffix.length() > 20 ? lineSuffix.substring(0, 20) + "..." : lineSuffix
            ));
        }
    }
}
