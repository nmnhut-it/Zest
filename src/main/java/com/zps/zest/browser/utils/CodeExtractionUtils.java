package com.zps.zest.browser.utils;

import com.intellij.openapi.diagnostic.Logger;

/**
 * Utility class for extracting code elements like functions, methods, etc.
 */
public class CodeExtractionUtils {
    private static final Logger LOG = Logger.getInstance(CodeExtractionUtils.class);
    
    /**
     * Finds the matching closing brace for an opening brace.
     */
    public static int findMatchingBrace(String content, int openBracePos) {
        int braceCount = 1;
        boolean inString = false;
        boolean inComment = false;
        boolean inMultiLineComment = false;
        char stringChar = 0;
        
        for (int i = openBracePos + 1; i < content.length(); i++) {
            char current = content.charAt(i);
            char prev = i > 0 ? content.charAt(i - 1) : 0;
            char next = i < content.length() - 1 ? content.charAt(i + 1) : 0;
            
            // Handle multi-line comments
            if (!inString && !inComment && current == '/' && next == '*') {
                inMultiLineComment = true;
                i++; // Skip next char
                continue;
            }
            if (inMultiLineComment && current == '*' && next == '/') {
                inMultiLineComment = false;
                i++; // Skip next char
                continue;
            }
            if (inMultiLineComment) continue;
            
            // Handle single-line comments
            if (!inString && current == '/' && next == '/') {
                inComment = true;
                continue;
            }
            if (inComment && current == '\n') {
                inComment = false;
                continue;
            }
            if (inComment) continue;
            
            // Handle strings
            if (!inString && (current == '"' || current == '\'' || current == '`')) {
                inString = true;
                stringChar = current;
                continue;
            }
            if (inString && current == stringChar && prev != '\\') {
                inString = false;
                continue;
            }
            if (inString) continue;
            
            // Count braces
            if (current == '{') {
                braceCount++;
            } else if (current == '}') {
                braceCount--;
                if (braceCount == 0) {
                    return i;
                }
            }
        }
        
        return -1; // No matching brace found
    }
    
    /**
     * Gets the line number for a given position in content.
     */
    public static int getLineNumber(String content, int position) {
        int line = 1;
        for (int i = 0; i < position && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
    
    /**
     * Extracts context around a position.
     */
    public static String extractContext(String content, int position, int contextLength) {
        int start = Math.max(0, position - contextLength);
        int end = Math.min(content.length(), position + contextLength);
        String context = content.substring(start, end).replaceAll("\\s+", " ").trim();
        if (start > 0) context = "..." + context;
        if (end < content.length()) context = context + "...";
        return context;
    }
    
    /**
     * Finds the actual start of a function declaration by backing up from the match position.
     */
    public static int findFunctionDeclarationStart(String content, int matchPos) {
        int start = matchPos;
        
        // Back up to find modifiers, export statements, etc.
        while (start > 0) {
            int prevStart = start;
            
            // Check for various prefixes
            String[] prefixes = {
                "export default ", "export ", "static ", "async ", "public ", 
                "private ", "protected ", "readonly ", "const ", "let ", "var ",
                "final ", "synchronized ", "abstract ", "native ", "volatile "
            };
            
            boolean foundPrefix = false;
            for (String prefix : prefixes) {
                if (start >= prefix.length()) {
                    String before = content.substring(start - prefix.length(), start);
                    if (before.equals(prefix)) {
                        start -= prefix.length();
                        foundPrefix = true;
                        break;
                    }
                }
            }
            
            if (!foundPrefix) {
                // Check if we're at the start of a line (after whitespace)
                int lineStart = start;
                while (lineStart > 0 && content.charAt(lineStart - 1) != '\n') {
                    if (!Character.isWhitespace(content.charAt(lineStart - 1))) {
                        return start; // Found non-whitespace, this is the start
                    }
                    lineStart--;
                }
                return lineStart == 0 ? 0 : lineStart;
            }
            
            // Skip whitespace before the prefix
            while (start > 0 && Character.isWhitespace(content.charAt(start - 1))) {
                start--;
                if (content.charAt(start) == '\n') {
                    return start + 1; // Start of line
                }
            }
            
            if (start == prevStart) {
                break; // No more prefixes found
            }
        }
        
        return start;
    }
    
    /**
     * Extracts an arrow function expression body (for arrow functions without braces).
     */
    public static String extractArrowFunctionExpression(String content, int exprStart) {
        // Find the end of the expression (semicolon, comma, closing paren/bracket, or newline followed by dedent)
        int end = exprStart;
        int parenCount = 0;
        int braceCount = 0;
        int bracketCount = 0;
        boolean inString = false;
        boolean inTemplate = false;
        char stringChar = '\0';
        
        while (end < content.length()) {
            char c = content.charAt(end);
            char prev = end > 0 ? content.charAt(end - 1) : '\0';
            
            // Handle strings
            if (!inString && !inTemplate && (c == '"' || c == '\'')) {
                inString = true;
                stringChar = c;
            } else if (inString && c == stringChar && prev != '\\') {
                inString = false;
            } else if (!inString && c == '`') {
                inTemplate = !inTemplate;
            }
            
            if (!inString && !inTemplate) {
                // Track nesting
                if (c == '(') parenCount++;
                else if (c == ')') parenCount--;
                else if (c == '{') braceCount++;
                else if (c == '}') braceCount--;
                else if (c == '[') bracketCount++;
                else if (c == ']') bracketCount--;
                
                // Check for expression end
                if (parenCount == 0 && braceCount == 0 && bracketCount == 0) {
                    if (c == ';' || c == ',' || c == '\n') {
                        break;
                    }
                    // Also check for end of object property (next property or closing brace)
                    if (end + 1 < content.length()) {
                        char next = content.charAt(end + 1);
                        if ((c == '\n' && (next == '}' || next == ',' || !Character.isWhitespace(next))) ||
                            (c == ' ' && next == '}')) {
                            break;
                        }
                    }
                }
            }
            
            end++;
        }
        
        // Back up from the match position to find the full arrow function
        int realStart = exprStart;
        while (realStart > 0 && content.charAt(realStart - 1) != '\n') {
            realStart--;
            if (content.substring(realStart, exprStart).contains("=>")) {
                break;
            }
        }
        
        // Find the actual start including variable declaration
        realStart = findFunctionDeclarationStart(content, realStart);
        
        return content.substring(realStart, end).trim();
    }
    
    /**
     * Detects the type of a function from its declaration.
     */
    public static String detectFunctionType(String declaration) {
        if (declaration.contains("=>")) return "arrow";
        if (declaration.contains("async")) return "async";
        if (declaration.contains("function*") || declaration.contains("*")) return "generator";
        if (declaration.contains("constructor")) return "constructor";
        if (declaration.contains("static")) return "static";
        if (declaration.contains("get ")) return "getter";
        if (declaration.contains("set ")) return "setter";
        if (declaration.contains("export")) return "exported";
        if (declaration.startsWith("(")) return "anonymous";
        if (declaration.contains(":")) return "method";
        return "function";
    }
    
    /**
     * Detects the type of a Java method from its declaration.
     */
    public static String detectJavaMethodType(String declaration) {
        if (declaration.contains("abstract")) return "abstract";
        if (declaration.contains("static")) return "static";
        if (declaration.matches(".*\\b[A-Z][a-zA-Z0-9_]*\\s*\\(.*")) return "constructor";
        if (declaration.contains(";") && !declaration.contains("{")) return "interface";
        return "method";
    }
}