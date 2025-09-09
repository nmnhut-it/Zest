package com.zps.zest.explanation.tools;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Advanced glob pattern matcher that supports complex file patterns similar to ripgrep/Claude Code.
 * 
 * Supports:
 * - Basic globs: *.java, *.{java,kt}
 * - Directory recursion: &#42;&#42;/*.java, src/&#42;&#42;/*.kt
 * - Character classes: [Tt]est*.java, file[0-9].txt
 * - Negation: !*test*, !&#42;&#42;/target/&#42;&#42;
 * - Full path matching vs filename-only matching
 */
public class AdvancedGlobMatcher {
    
    /**
     * Convert a glob pattern to a regex pattern with full support for advanced glob syntax.
     */
    private static Pattern globToRegex(String glob, boolean fullPath) {
        if (glob == null || glob.isEmpty()) {
            return null;
        }
        
        // Remove negation prefix - handle it at the calling level
        if (glob.startsWith("!")) {
            glob = glob.substring(1);
        }
        
        StringBuilder regex = new StringBuilder();
        boolean inBraces = false;
        boolean inBrackets = false;
        
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            
            switch (c) {
                case '*':
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        // Handle **
                        if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                            // **/ - match any number of directories
                            regex.append("(?:[^/]+/)*");
                            i += 2; // Skip the next * and /
                        } else if (i + 2 == glob.length()) {
                            // ** at end - match everything
                            regex.append(".*");
                            i++; // Skip the next *
                        } else {
                            // ** in middle - match any characters including /
                            regex.append(".*");
                            i++; // Skip the next *
                        }
                    } else {
                        // Single * - match anything except /
                        regex.append(fullPath ? "[^/]*" : "[^/]*");
                    }
                    break;
                    
                case '?':
                    regex.append(fullPath ? "[^/]" : ".");
                    break;
                    
                case '{':
                    inBraces = true;
                    regex.append("(?:");
                    break;
                    
                case '}':
                    if (inBraces) {
                        inBraces = false;
                        regex.append(")");
                    } else {
                        regex.append(Pattern.quote(String.valueOf(c)));
                    }
                    break;
                    
                case ',':
                    if (inBraces) {
                        regex.append("|");
                    } else {
                        regex.append(Pattern.quote(String.valueOf(c)));
                    }
                    break;
                    
                case '[':
                    inBrackets = true;
                    regex.append("[");
                    break;
                    
                case ']':
                    if (inBrackets) {
                        inBrackets = false;
                        regex.append("]");
                    } else {
                        regex.append(Pattern.quote(String.valueOf(c)));
                    }
                    break;
                    
                case '\\':
                    if (i + 1 < glob.length()) {
                        // Escape the next character
                        regex.append(Pattern.quote(String.valueOf(glob.charAt(i + 1))));
                        i++;
                    } else {
                        regex.append(Pattern.quote("\\"));
                    }
                    break;
                    
                default:
                    if (inBrackets) {
                        // Inside character class, don't quote special chars
                        regex.append(c);
                    } else {
                        // Quote regex special characters
                        regex.append(Pattern.quote(String.valueOf(c)));
                    }
                    break;
            }
        }
        
        try {
            return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            // Fall back to simple pattern matching if regex is invalid
            String simpleRegex = glob.replace("*", ".*").replace("?", ".");
            return Pattern.compile(simpleRegex, Pattern.CASE_INSENSITIVE);
        }
    }
    
    /**
     * Match a file path against a glob pattern.
     */
    public static boolean matches(String filePath, String globPattern) {
        if (globPattern == null || globPattern.isEmpty()) {
            return true;
        }
        
        // Normalize path separators
        filePath = filePath.replace('\\', '/');
        
        // Determine if this should be full path or filename matching
        boolean fullPath = globPattern.contains("/") || globPattern.contains("**");
        
        // Handle negation at this level
        boolean negated = globPattern.startsWith("!");
        
        String testPath = fullPath ? filePath : getFileName(filePath);
        Pattern pattern = globToRegex(globPattern, fullPath);
        
        if (pattern == null) {
            return true;
        }
        
        boolean matches = pattern.matcher(testPath).matches();
        return negated ? !matches : matches;
    }
    
    /**
     * Check if any of the patterns match the file path.
     */
    public static boolean matchesAny(String filePath, String... patterns) {
        if (patterns == null || patterns.length == 0) {
            return true;
        }
        
        for (String pattern : patterns) {
            if (matches(filePath, pattern)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check if path should be excluded based on exclude patterns.
     */
    public static boolean isExcluded(String filePath, String excludePattern) {
        if (excludePattern == null || excludePattern.isEmpty()) {
            return false;
        }
        
        // Split multiple exclude patterns by comma or semicolon
        String[] patterns = excludePattern.split("[,;]");
        for (String pattern : patterns) {
            pattern = pattern.trim();
            if (!pattern.isEmpty() && matches(filePath, pattern)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Get filename from a path.
     */
    private static String getFileName(String path) {
        Path p = Paths.get(path);
        return p.getFileName() != null ? p.getFileName().toString() : path;
    }
    
}