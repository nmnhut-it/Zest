package com.zps.zest.autocompletion2.acceptance;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Defines how much of a completion to accept on Tab press.
 * Supports progressive acceptance: WORD → LINE → FULL
 */
public enum AcceptanceType {
    WORD("Next word or symbol group"),
    LINE("Next line or statement"),  
    FULL("Entire completion");
    
    private final String description;
    private static final Pattern WORD_PATTERN = Pattern.compile("\\w+|[^\\w\\s]+|\\s+");
    
    AcceptanceType(String description) {
        this.description = description;
    }
    
    public String getDescription() { return description; }
    
    /**
     * Determines the acceptance type based on Tab press count.
     * This is the core progression logic.
     */
    public static AcceptanceType fromTabCount(int tabCount) {
        switch (tabCount) {
            case 1: return WORD;
            case 2: return LINE; 
            default: return FULL;
        }
    }
    
    /**
     * Extracts the portion of text to accept based on this acceptance type.
     */
    @NotNull
    public String extractText(@NotNull String completionText) {
        if (completionText.isEmpty()) {
            return "";
        }
        
        switch (this) {
            case FULL:
                return completionText;
                
            case WORD:
                return extractFirstWord(completionText);
                
            case LINE:
                return extractFirstLine(completionText);
                
            default:
                return completionText;
        }
    }
    
    /**
     * Calculates the remaining text after acceptance.
     */
    @Nullable
    public String calculateRemaining(@NotNull String originalText, @NotNull String acceptedText) {
        if (acceptedText.isEmpty() || acceptedText.length() >= originalText.length()) {
            return null; // Nothing left
        }
        
        // Ensure the accepted text actually matches the beginning
        if (!originalText.startsWith(acceptedText)) {
            return null; // Invalid acceptance
        }
        
        String remaining = originalText.substring(acceptedText.length());
        return remaining.isEmpty() ? null : remaining;
    }
    
    private static String extractFirstWord(@NotNull String text) {
        Matcher matcher = WORD_PATTERN.matcher(text);
        if (matcher.find()) {
            String word = matcher.group();
            
            // Include following punctuation if it's part of a method call
            if (matcher.find() && matcher.group().matches("[(){}\\[\\].,;]")) {
                word += matcher.group();
            }
            
            return word;
        }
        return text; // Fallback to entire text
    }
    
    private static String extractFirstLine(@NotNull String text) {
        int newlineIndex = text.indexOf('\n');
        if (newlineIndex == -1) {
            return text; // No newlines, return entire text
        }
        
        // Include the newline character
        return text.substring(0, newlineIndex + 1);
    }
    
    /**
     * Result of an acceptance operation.
     */
    public static class AcceptanceResult {
        private final String acceptedText;
        private final String remainingText;
        private final boolean hasRemaining;
        
        public AcceptanceResult(@NotNull String acceptedText, @Nullable String remainingText) {
            this.acceptedText = acceptedText;
            this.remainingText = remainingText;
            this.hasRemaining = remainingText != null && !remainingText.trim().isEmpty();
        }
        
        @NotNull
        public String getAcceptedText() { return acceptedText; }
        
        @Nullable
        public String getRemainingText() { return remainingText; }
        
        public boolean hasRemaining() { return hasRemaining; }
        
        @Override
        public String toString() {
            return String.format("AcceptanceResult{accepted='%s', hasRemaining=%s}", 
                acceptedText.length() > 20 ? acceptedText.substring(0, 20) + "..." : acceptedText,
                hasRemaining);
        }
    }
    
    /**
     * Performs the acceptance operation and returns the result.
     */
    public AcceptanceResult accept(@NotNull String completionText) {
        String accepted = extractText(completionText);
        String remaining = calculateRemaining(completionText, accepted);
        return new AcceptanceResult(accepted, remaining);
    }
}
