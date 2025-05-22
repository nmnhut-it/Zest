package com.zps.zest.autocomplete;

import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Defines the granularity of completion acceptance following Tabby ML patterns.
 * Supports full completion, word-by-word, and line-by-line acceptance.
 */
public enum AcceptType {
    /**
     * Accept the entire completion text.
     */
    FULL_COMPLETION,
    
    /**
     * Accept the next word from the completion.
     * A word is defined as a sequence of word characters or non-word characters.
     */
    NEXT_WORD,
    
    /**
     * Accept the next line from the completion.
     * If the first line is empty, accepts the first line plus the newline.
     */
    NEXT_LINE;

    // Pattern for word boundary detection (matches word chars or non-word chars)
    private static final Pattern WORD_PATTERN = Pattern.compile("\\w+\\s?|\\W+\\s?");

    /**
     * Extracts the text to accept based on the accept type and completion text.
     * 
     * @param completionText The full completion text
     * @param acceptType The type of acceptance
     * @return The text that should be accepted
     */
    public static String extractAcceptableText(@NotNull String completionText, @NotNull AcceptType acceptType) {
        if (completionText.isEmpty()) {
            return "";
        }

        switch (acceptType) {
            case FULL_COMPLETION:
                return completionText;
                
            case NEXT_WORD:
                return extractNextWord(completionText);
                
            case NEXT_LINE:
                return extractNextLine(completionText);
                
            default:
                return completionText;
        }
    }

    /**
     * Extracts the next word from the completion text.
     * Uses regex to find word boundaries (sequences of word chars or non-word chars).
     */
    private static String extractNextWord(@NotNull String completionText) {
        Matcher matcher = WORD_PATTERN.matcher(completionText);
        if (matcher.find()) {
            return matcher.group();
        }
        return ""; // No word found
    }

    /**
     * Extracts the next line from the completion text.
     * If the first line is empty, includes the newline character.
     */
    private static String extractNextLine(@NotNull String completionText) {
        String[] lines = completionText.split("\n", -1); // -1 to preserve empty strings
        
        if (lines.length <= 1) {
            // No newlines, return the entire text
            return completionText;
        }
        
        String firstLine = lines[0];
        if (firstLine.isEmpty()) {
            // First line is empty, return first line + newline
            // This handles cases where completion starts with a newline
            return "\n";
        } else {
            // Return first line only (without newline)
            return firstLine;
        }
    }

    /**
     * Calculates the remaining text after accepting a portion.
     * 
     * @param originalText The original completion text
     * @param acceptedText The text that was accepted
     * @return The remaining text, or null if no continuation is possible
     */
    public static String calculateRemainingText(@NotNull String originalText, @NotNull String acceptedText) {
        if (acceptedText.isEmpty() || !originalText.startsWith(acceptedText)) {
            return null; // Invalid acceptance
        }
        
        if (acceptedText.length() >= originalText.length()) {
            return null; // Nothing remaining
        }
        
        return originalText.substring(acceptedText.length());
    }

    /**
     * Determines if the given text represents a complete word.
     * Used to decide if word-by-word acceptance makes sense.
     */
    public static boolean isCompleteWord(@NotNull String text) {
        if (text.isEmpty()) {
            return false;
        }
        
        // A complete word should match our word pattern exactly
        Matcher matcher = WORD_PATTERN.matcher(text);
        return matcher.matches();
    }

    /**
     * Determines if the given text represents a complete line.
     * Used to decide if line-by-line acceptance makes sense.
     */
    public static boolean isCompleteLine(@NotNull String text) {
        if (text.isEmpty()) {
            return false;
        }
        
        // A complete line either doesn't contain newlines, or ends with a newline
        return !text.contains("\n") || text.endsWith("\n");
    }

    /**
     * Gets the appropriate accept type based on the current context and user preference.
     * This can be used for smart acceptance behavior.
     */
    public static AcceptType getSmartAcceptType(@NotNull String completionText, boolean preferWordByWord) {
        if (completionText.isEmpty()) {
            return FULL_COMPLETION;
        }
        
        // If it's a single word, always accept fully
        if (isCompleteWord(completionText) && !completionText.contains("\n")) {
            return FULL_COMPLETION;
        }
        
        // If it's a single line, decide based on preference
        if (!completionText.contains("\n")) {
            return preferWordByWord ? NEXT_WORD : FULL_COMPLETION;
        }
        
        // Multi-line completion - prefer line-by-line
        return NEXT_LINE;
    }

    /**
     * Statistics and information about acceptance patterns.
     */
    public static class AcceptanceInfo {
        private final AcceptType type;
        private final String acceptedText;
        private final String remainingText;
        private final boolean hasMoreContent;
        
        public AcceptanceInfo(AcceptType type, String acceptedText, String remainingText) {
            this.type = type;
            this.acceptedText = acceptedText;
            this.remainingText = remainingText;
            this.hasMoreContent = remainingText != null && !remainingText.trim().isEmpty();
        }
        
        public AcceptType getType() { return type; }
        public String getAcceptedText() { return acceptedText; }
        public String getRemainingText() { return remainingText; }
        public boolean hasMoreContent() { return hasMoreContent; }
        
        /**
         * Creates acceptance info for the given completion text and accept type.
         */
        public static AcceptanceInfo create(@NotNull String completionText, @NotNull AcceptType acceptType) {
            String acceptedText = extractAcceptableText(completionText, acceptType);
            String remainingText = calculateRemainingText(completionText, acceptedText);
            return new AcceptanceInfo(acceptType, acceptedText, remainingText);
        }
        
        @Override
        public String toString() {
            return String.format("AcceptanceInfo{type=%s, accepted='%s', remaining='%s', hasMore=%s}",
                type, 
                acceptedText.length() > 20 ? acceptedText.substring(0, 20) + "..." : acceptedText,
                remainingText != null && remainingText.length() > 20 ? remainingText.substring(0, 20) + "..." : remainingText,
                hasMoreContent);
        }
    }
}
