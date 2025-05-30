package com.zps.zest.browser.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Utility class for case-insensitive string matching across different naming conventions.
 * Supports camelCase, PascalCase, snake_case, kebab-case, UPPER_SNAKE_CASE, etc.
 */
public class StringMatchingUtils {
    
    /**
     * Checks if a target string matches a search term using case-insensitive matching
     * that works across different naming conventions.
     */
    public static boolean matchesAcrossNamingConventions(String target, String searchTerm, boolean caseSensitive) {
        if (target == null || searchTerm == null) {
            return false;
        }
        
        if (caseSensitive) {
            return target.contains(searchTerm);
        }
        
        // Direct case-insensitive match
        if (target.toLowerCase().contains(searchTerm.toLowerCase())) {
            return true;
        }
        
        // Try matching with naming convention transformations
        List<String> searchVariants = generateNamingVariants(searchTerm);
        List<String> targetVariants = generateNamingVariants(target);
        
        // Check if any search variant matches any target variant
        for (String searchVariant : searchVariants) {
            for (String targetVariant : targetVariants) {
                if (targetVariant.toLowerCase().contains(searchVariant.toLowerCase())) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Generates a regex pattern that matches across different naming conventions.
     */
    public static Pattern createCrossNamingPattern(String searchTerm, boolean caseSensitive) {
        if (searchTerm == null || searchTerm.isEmpty()) {
            return null;
        }
        
        // Split the search term into words
        List<String> words = splitIntoWords(searchTerm);
        if (words.isEmpty()) {
            return Pattern.compile(Pattern.quote(searchTerm), caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
        }
        
        // Build regex that matches words in various naming conventions
        StringBuilder pattern = new StringBuilder();
        
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            
            if (i > 0) {
                // Between words, allow for different separators or case changes
                pattern.append("(?:[_\\-\\s]?|(?=[A-Z]))");
            }
            
            // Make each word match case-insensitively if needed
            if (caseSensitive) {
                pattern.append(Pattern.quote(word));
            } else {
                // Match the word with flexible casing
                pattern.append("(?i:").append(Pattern.quote(word)).append(")");
            }
        }
        
        return Pattern.compile(pattern.toString());
    }
    
    /**
     * Generates naming variants for a given string.
     */
    public static List<String> generateNamingVariants(String input) {
        List<String> variants = new ArrayList<>();
        variants.add(input); // Original
        
        List<String> words = splitIntoWords(input);
        if (words.size() <= 1) {
            return variants;
        }
        
        // camelCase
        variants.add(toCamelCase(words));
        
        // PascalCase
        variants.add(toPascalCase(words));
        
        // snake_case
        variants.add(toSnakeCase(words));
        
        // kebab-case
        variants.add(toKebabCase(words));
        
        // UPPER_SNAKE_CASE
        variants.add(toUpperSnakeCase(words));
        
        return variants;
    }
    
    /**
     * Splits a string into words based on common naming conventions.
     */
    public static List<String> splitIntoWords(String input) {
        List<String> words = new ArrayList<>();
        
        if (input == null || input.isEmpty()) {
            return words;
        }
        
        // Replace common separators with spaces
        String normalized = input.replaceAll("[_\\-]", " ");
        
        // Split camelCase and PascalCase
        normalized = normalized.replaceAll("([a-z])([A-Z])", "$1 $2");
        normalized = normalized.replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        
        // Split by spaces and filter out empty strings
        for (String word : normalized.split("\\s+")) {
            if (!word.isEmpty()) {
                words.add(word.toLowerCase());
            }
        }
        
        return words;
    }
    
    private static String toCamelCase(List<String> words) {
        if (words.isEmpty()) return "";
        
        StringBuilder result = new StringBuilder(words.get(0).toLowerCase());
        for (int i = 1; i < words.size(); i++) {
            result.append(capitalize(words.get(i)));
        }
        return result.toString();
    }
    
    private static String toPascalCase(List<String> words) {
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            result.append(capitalize(word));
        }
        return result.toString();
    }
    
    private static String toSnakeCase(List<String> words) {
        return String.join("_", words);
    }
    
    private static String toKebabCase(List<String> words) {
        return String.join("-", words);
    }
    
    private static String toUpperSnakeCase(List<String> words) {
        List<String> upperWords = new ArrayList<>();
        for (String word : words) {
            upperWords.add(word.toUpperCase());
        }
        return String.join("_", upperWords);
    }
    
    private static String capitalize(String word) {
        if (word == null || word.isEmpty()) return word;
        return word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase();
    }
    
    /**
     * Creates a flexible regex pattern for function name matching.
     */
    public static String createFlexibleFunctionPattern(String functionName, boolean caseSensitive) {
        List<String> words = splitIntoWords(functionName);
        if (words.isEmpty()) {
            return Pattern.quote(functionName);
        }
        
        StringBuilder pattern = new StringBuilder();
        
        // Allow for word boundaries or naming convention separators before
        pattern.append("(?:^|\\b|(?<=[_\\-\\s]))");
        
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) {
                // Between words, allow various separators or case changes
                pattern.append("(?:[_\\-\\s]*|(?=[A-Z]))");
            }
            
            String word = words.get(i);
            if (caseSensitive) {
                // For case sensitive, still allow for different naming conventions
                pattern.append("(?:");
                pattern.append(word).append("|");
                pattern.append(capitalize(word)).append("|");
                pattern.append(word.toUpperCase());
                pattern.append(")");
            } else {
                pattern.append("(?i:").append(Pattern.quote(word)).append(")");
            }
        }
        
        // Allow for word boundaries or naming convention separators after
        pattern.append("(?:$|\\b|(?=[_\\-\\s]))");
        
        return pattern.toString();
    }
}