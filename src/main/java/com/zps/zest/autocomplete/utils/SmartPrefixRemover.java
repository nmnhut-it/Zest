package com.zps.zest.autocomplete.utils;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Utility class for smart removal of redundant prefixes in code completions.
 * Handles various scenarios like partial overlaps, exact matches, and code-specific constructs.
 */
public class SmartPrefixRemover {
    private static final Logger LOG = Logger.getInstance(SmartPrefixRemover.class);

    /**
     * Removes redundant prefix from completion text based on current editor context.
     * 
     * @param editor The editor instance
     * @param completionText The completion text to clean
     * @return Cleaned completion text with redundant prefix removed
     */
    public static String removeRedundantPrefix(@NotNull Editor editor, @NotNull String completionText) {
        try {
            Document document = editor.getDocument();
            int currentOffset = editor.getCaretModel().getOffset();
            
            // Get current line prefix
            int lineNumber = document.getLineNumber(currentOffset);
            int lineStart = document.getLineStartOffset(lineNumber);
            String currentLinePrefix = document.getText().substring(lineStart, currentOffset);
            
            String cleaned = applySmartRedundantPrefixRemoval(currentLinePrefix, completionText);
            
            if (!cleaned.equals(completionText)) {
                LOG.debug("Smart prefix removal: '{}' -> '{}'", 
                         completionText.substring(0, Math.min(30, completionText.length())), 
                         cleaned.substring(0, Math.min(30, cleaned.length())));
            }
            
            return cleaned;
            
        } catch (Exception e) {
            LOG.warn("Error in smart redundant prefix removal", e);
            return completionText;
        }
    }

    /**
     * Core smart redundant prefix removal logic.
     * Handles exact matches, overlaps, and various code constructs.
     * 
     * @param currentPrefix The text currently on the line before cursor
     * @param completionText The completion text to process
     * @return Cleaned completion text
     */
    public static String applySmartRedundantPrefixRemoval(@Nullable String currentPrefix, @NotNull String completionText) {
        if (currentPrefix == null || completionText.isEmpty()) {
            return completionText;
        }
        
        if (currentPrefix.isEmpty()) {
            return completionText;
        }
        
        // Handle comments specially - be very conservative  
        if (currentPrefix.startsWith("//") && completionText.startsWith("//")) {
            return handleCommentRedundancy(currentPrefix, completionText);
        }
        

        if (completionText.startsWith(currentPrefix)) {
            String remainder = completionText.substring(currentPrefix.length());
            if (!remainder.isEmpty()) {
                LOG.debug("Removed exact prefix match: '{}'", currentPrefix);
                return remainder;
            }
        }
        

        String overlap = findLongestOverlap(currentPrefix, completionText);
        if (overlap != null && !overlap.isEmpty()) {
            String remainder = completionText.substring(overlap.length());
            if (isValidOverlapRemoval(currentPrefix, overlap, remainder)) {
                LOG.debug("Removed overlapping prefix: '{}'", overlap);
                return remainder;
            }
        }
        
        // Fallback to character-by-character matching for edge cases
        int commonLength = findCommonSuffixPrefixLength(currentPrefix, completionText);
        
        if (commonLength > 0) {
            String matchedText = completionText.substring(0, commonLength);
            
            if (isValidPrefixToRemove(currentPrefix, matchedText, completionText)) {
                return completionText.substring(commonLength);
            }
        }
        
        return completionText;
    }

    /**
     * Find the longest overlap between end of current prefix and start of completion.
     * This handles cases like: currentPrefix="if (" and completion="if (result.size() < 2)"
     * 
     * Improved with character-by-character comparison and better handling of code constructs.
     */
    private static String findLongestOverlap(String currentPrefix, String completionText) {
        // Fast path: direct check for exact prefix match
        if (completionText.startsWith(currentPrefix)) {
            return currentPrefix;
        }
        
        String longestOverlap = "";
        
        // Check all possible overlaps, starting from the longest
        for (int i = Math.min(currentPrefix.length(), completionText.length()); i >= 1; i--) {
            if (i < longestOverlap.length()) {
                // Already found a longer overlap
                break;
            }
            
            String prefixSuffix = currentPrefix.substring(currentPrefix.length() - i);
            String completionPrefix = completionText.substring(0, i);
            
            if (prefixSuffix.equals(completionPrefix)) {
                longestOverlap = completionPrefix;
                // Continue checking for even longer overlaps
            }
        }
        
        // Check if we found something better than trivial single character match
        if (longestOverlap.length() <= 1) {
            // Character-by-character comparison for special cases
            longestOverlap = findCharacterByCharacterOverlap(currentPrefix, completionText);
        }
        
        // Code-specific enhancement: handle common programming patterns
        if (longestOverlap.isEmpty()) {
            longestOverlap = findCodeSpecificOverlap(currentPrefix, completionText);
        }
        
        return longestOverlap;
    }
    
    /**
     * Find overlap using character-by-character comparison.
     * Better for handling cases with slight variations in whitespace or punctuation.
     */
    private static String findCharacterByCharacterOverlap(String currentPrefix, String completionText) {
        // Skip leading whitespace for comparison
        int prefixStart = skipLeadingWhitespace(currentPrefix);
        int completionStart = skipLeadingWhitespace(completionText);
        
        // Get effective text for comparison
        String effectivePrefix = currentPrefix.substring(prefixStart);
        String effectiveCompletion = completionText.substring(completionStart);
        
        // Compare character by character, ignoring some whitespace differences
        StringBuilder overlap = new StringBuilder();
        int minLength = Math.min(effectivePrefix.length(), effectiveCompletion.length());
        
        for (int i = 0; i < minLength; i++) {
            char prefixChar = effectivePrefix.charAt(i);
            char completionChar = effectiveCompletion.charAt(i);
            
            if (prefixChar == completionChar) {
                overlap.append(prefixChar);
            } else if (isWhitespaceEquivalent(prefixChar, completionChar)) {
                // Consider different whitespace as equivalent
                overlap.append(' '); // Standardize to space
            } else {
                break; // Stop at first non-matching, non-whitespace character
            }
        }
        
        return overlap.toString();
    }
    
    /**
     * Check if two characters are whitespace equivalent (space, tab, etc.).
     */
    private static boolean isWhitespaceEquivalent(char a, char b) {
        return Character.isWhitespace(a) && Character.isWhitespace(b);
    }
    
    /**
     * Skip leading whitespace in a string.
     * 
     * @return The index of the first non-whitespace character
     */
    private static int skipLeadingWhitespace(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }
    
    /**
     * Find overlap specific to code constructs.
     * Handles common programming patterns like brackets, operators, etc.
     */
    private static String findCodeSpecificOverlap(String currentPrefix, String completionText) {
        // Check for common code constructs at the end of prefix
        for (String construct : new String[] {"{", "}", "(", ")", "[", "]", ";", ",", ".", "+=", "-=", "*=", "/="}) {
            if (currentPrefix.endsWith(construct) && completionText.startsWith(construct)) {
                return construct;
            }
        }
        
        // Check for common keywords
        for (String keyword : new String[] {"if", "for", "while", "switch", "return", "break", "continue"}) {
            // Check if keyword appears at end of prefix and start of completion
            if (endsWithWord(currentPrefix, keyword) && startsWithWord(completionText, keyword)) {
                return keyword;
            }
        }
        
        return "";
    }
    
    /**
     * Check if a string ends with a complete word.
     */
    private static boolean endsWithWord(String s, String word) {
        if (!s.endsWith(word)) {
            return false;
        }
        
        // Check if character before word is not a letter/digit
        int beforeWordIndex = s.length() - word.length() - 1;
        return beforeWordIndex < 0 || !Character.isLetterOrDigit(s.charAt(beforeWordIndex));
    }
    
    /**
     * Check if a string starts with a complete word.
     */
    private static boolean startsWithWord(String s, String word) {
        if (!s.startsWith(word)) {
            return false;
        }
        
        // Check if character after word is not a letter/digit
        int afterWordIndex = word.length();
        return afterWordIndex >= s.length() || !Character.isLetterOrDigit(s.charAt(afterWordIndex));
    }

    /**
     * Validate if overlap removal makes sense and won't break the completion.
     */
    private static boolean isValidOverlapRemoval(String currentPrefix, String overlap, String remainder) {
        // Don't remove if remainder would be empty or just whitespace
        if (remainder.trim().isEmpty()) {
            return false;
        }
        
        // Allow removal of even short overlaps for code constructs
        if (overlap.length() >= 1) {
            // Special cases for common code patterns
            if (isCommonCodeConstruct(overlap)) {
                return true;
            }
            
            // For longer overlaps, be more permissive
            if (overlap.length() >= 2) {
                return true;
            }
            
            // Single character overlaps: only allow if it's punctuation
            if (overlap.length() == 1) {
                char c = overlap.charAt(0);
                return !Character.isLetterOrDigit(c); // Allow punctuation, not letters/digits
            }
        }
        
        return false;
    }

    /**
     * Check if the overlap represents a common code construct that's safe to remove.
     */
    private static boolean isCommonCodeConstruct(String overlap) {
        return overlap.equals("(") || overlap.equals("{") || overlap.equals("[") || 
               overlap.equals("if") || overlap.equals("for") || overlap.equals("while") ||
               overlap.equals("switch") || overlap.equals("try") || overlap.equals("catch") ||
               overlap.matches("\\w+\\s*\\("); // method calls like "method("
    }

    /**
     * Find common suffix-prefix length (original character-by-character matching).
     * Used as fallback for edge cases.
     */
    private static int findCommonSuffixPrefixLength(String currentPrefix, String completionText) {
        int commonLength = 0;
        int maxCheck = Math.min(currentPrefix.length(), completionText.length());
        
        for (int i = 1; i <= maxCheck; i++) {
            String prefixSuffix = currentPrefix.substring(currentPrefix.length() - i);
            String completionPrefix = completionText.substring(0, i);
            
            if (prefixSuffix.equals(completionPrefix)) {
                commonLength = i;
            } else {
                break;
            }
        }
        
        return commonLength;
    }

    /**
     * Validate if prefix removal is safe and meaningful.
     * More permissive than original for better code completion UX.
     */
    private static boolean isValidPrefixToRemove(String currentPrefix, String matchedText, String fullCompletion) {
        String remainder = fullCompletion.substring(matchedText.length());
        
        // Allow shorter remainders for code completion
        if (remainder.trim().length() < 1) {
            return false;
        }
        
        // Allow shorter matches - even single characters can be meaningful in code
        if (matchedText.trim().isEmpty()) {
            return false;
        }
        
        // Don't break word boundaries unless it's a natural code boundary
        if (matchedText.length() < fullCompletion.length()) {
            char nextChar = fullCompletion.charAt(matchedText.length());
            char lastMatchChar = matchedText.charAt(matchedText.length() - 1);
            
            if (Character.isLetterOrDigit(nextChar) && Character.isLetterOrDigit(lastMatchChar)) {
                // Allow breaking if the match ends with common code constructs
                if (!isCodeBoundary(matchedText)) {
                    return false;
                }
            }
        }
        
        return true;
    }

    /**
     * Check if a string ends at a natural code boundary where it's safe to split.
     */
    private static boolean isCodeBoundary(String text) {
        if (text.isEmpty()) return false;
        
        // Common code boundaries where it's safe to split
        return text.endsWith("(") || text.endsWith("{") || text.endsWith("[") ||
               text.endsWith(";") || text.endsWith(",") || text.endsWith(".") ||
               text.endsWith(" ") || text.endsWith("\t") ||
               text.matches(".*\\b(if|for|while|switch|try|catch|finally|class|interface|public|private|protected)$");
    }

    /**
     * Handle comment redundancy with conservative approach.
     */
    private static String handleCommentRedundancy(String currentPrefix, String completionText) {
        if (currentPrefix.equals(completionText.substring(0, Math.min(currentPrefix.length(), completionText.length())))) {
            return completionText.substring(currentPrefix.length());
        }
        return completionText;
    }

    /**
     * Convenience method for simple prefix/completion pair without editor context.
     * 
     * @param currentPrefix The current prefix text
     * @param completionText The completion text
     * @return Cleaned completion text
     */
    public static String removeRedundantPrefix(@Nullable String currentPrefix, @NotNull String completionText) {
        return applySmartRedundantPrefixRemoval(currentPrefix, completionText);
    }
}