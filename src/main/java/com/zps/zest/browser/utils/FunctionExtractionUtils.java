package com.zps.zest.browser.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for extracting functions from various programming languages.
 */
public class FunctionExtractionUtils {
    private static final Logger LOG = Logger.getInstance(FunctionExtractionUtils.class);
    
    /**
     * Extracts the full function implementation starting from a given position.
     * This method handles various JavaScript/TypeScript function patterns.
     */
    public static String extractFullFunctionImplementation(String content, int startPos) {
        // Find the opening brace or arrow
        int functionStart = startPos;
        int openBrace = -1;
        boolean isArrowFunction = false;
        
        // Look for the function body start
        for (int i = startPos; i < content.length(); i++) {
            char c = content.charAt(i);
            char nextChar = i < content.length() - 1 ? content.charAt(i + 1) : '\0';
            
            // Check for arrow function
            if (c == '=' && nextChar == '>') {
                isArrowFunction = true;
                i++; // Skip the '>'
                
                // Skip whitespace after arrow
                while (i < content.length() - 1 && Character.isWhitespace(content.charAt(i + 1))) {
                    i++;
                }
                
                // Check if next character is opening brace or direct expression
                if (i < content.length() - 1 && content.charAt(i + 1) == '{') {
                    openBrace = i + 1;
                    break;
                } else {
                    // Arrow function with expression body (no braces)
                    return CodeExtractionUtils.extractArrowFunctionExpression(content, i + 1);
                }
            } else if (c == '{') {
                openBrace = i;
                break;
            }
        }
        
        if (openBrace == -1) {
            // No function body found, return just the signature
            return extractSignature(content, startPos);
        }
        
        // Find the matching closing brace
        int closeBrace = CodeExtractionUtils.findMatchingBrace(content, openBrace);
        if (closeBrace == -1) {
            // Can't find matching brace, return what we have
            return content.substring(functionStart, Math.min(functionStart + 1000, content.length()));
        }
        
        // Extract the full function including signature and body
        // Find the actual start of the function declaration
        int realStart = CodeExtractionUtils.findFunctionDeclarationStart(content, startPos);
        
        return content.substring(realStart, closeBrace + 1);
    }
    
    /**
     * Extracts function signature from content at given position.
     */
    public static String extractSignature(String content, int startPos) {
        int endPos = startPos;
        int braceCount = 0;
        boolean foundFirstBrace = false;
        
        for (int i = startPos; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                if (!foundFirstBrace) {
                    foundFirstBrace = true;
                    endPos = i;
                }
                braceCount++;
            } else if (c == '}' && foundFirstBrace) {
                braceCount--;
                if (braceCount == 0) {
                    break;
                }
            } else if (c == '\n' && !foundFirstBrace) {
                endPos = i;
                break;
            }
        }
        
        return content.substring(startPos, endPos).trim();
    }
    
    /**
     * Finds Cocos2d-x class methods in content.
     */
    public static void findCocosClassMethods(String content, JsonArray functions, Set<String> foundFunctions, 
                                            String targetName, boolean caseSensitive) {
        // Pattern to find Cocos2d-x class definitions
        Pattern classPattern = Pattern.compile("(?:var\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*)?(?:cc\\.Class|cc\\.Layer|cc\\.Scene|cc\\.Sprite|cc\\.Node|[a-zA-Z_$][a-zA-Z0-9_$]*)\\.extend\\s*\\(\\s*\\{");
        
        Matcher classMatcher = classPattern.matcher(content);
        while (classMatcher.find()) {
            int classStart = classMatcher.end() - 1; // Position of the opening brace
            String className = classMatcher.group(1); // May be null for anonymous classes
            
            // Find the matching closing brace for the class
            int classEnd = CodeExtractionUtils.findMatchingBrace(content, classStart);
            if (classEnd == -1) continue;
            
            String classBody = content.substring(classStart + 1, classEnd);
            
            // Find all methods within this class body
            Pattern methodPattern = Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*:\\s*function\\s*\\([^)]*\\)");
            
            Matcher methodMatcher = methodPattern.matcher(classBody);
            while (methodMatcher.find()) {
                String methodName = methodMatcher.group(1);
                
                if (targetName == null || StringMatchingUtils.matchesAcrossNamingConventions(methodName, targetName, caseSensitive)) {
                    int methodPosition = classStart + 1 + methodMatcher.start();
                    String uniqueKey = methodName + "_" + methodPosition;
                    
                    if (!foundFunctions.contains(uniqueKey)) {
                        foundFunctions.add(uniqueKey);
                        
                        JsonObject func = new JsonObject();
                        func.addProperty("name", methodName);
                        func.addProperty("line", CodeExtractionUtils.getLineNumber(content, methodPosition));
                        func.addProperty("signature", methodName + methodMatcher.group(0).substring(methodName.length()));
                        func.addProperty("type", "cocos-method");
                        if (className != null) {
                            func.addProperty("className", className);
                        }
                        
                        // Add special type annotations for common Cocos2d-x methods
                        if (methodName.equals("ctor")) {
                            func.addProperty("type", "cocos-constructor");
                        } else if (methodName.matches("^(onEnter|onExit|onEnterTransitionDidFinish|onExitTransitionDidStart|init|update|draw|visit)$")) {
                            func.addProperty("type", "cocos-lifecycle");
                        }
                        
                        functions.add(func);
                    }
                }
            }
            
            // Also look for ES6-style methods in Cocos classes
            Pattern es6MethodPattern = Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)\\s*\\{");
            Matcher es6Matcher = es6MethodPattern.matcher(classBody);
            
            while (es6Matcher.find()) {
                String methodName = es6Matcher.group(1);
                
                // Skip if it's actually "function("
                if (methodName.equals("function")) continue;
                
                if (targetName == null || StringMatchingUtils.matchesAcrossNamingConventions(methodName, targetName, caseSensitive)) {
                    int methodPosition = classStart + 1 + es6Matcher.start();
                    String uniqueKey = methodName + "_es6_" + methodPosition;
                    
                    if (!foundFunctions.contains(uniqueKey)) {
                        foundFunctions.add(uniqueKey);
                        
                        JsonObject func = new JsonObject();
                        func.addProperty("name", methodName);
                        func.addProperty("line", CodeExtractionUtils.getLineNumber(content, methodPosition));
                        func.addProperty("signature", es6Matcher.group(0));
                        func.addProperty("type", "cocos-method-es6");
                        if (className != null) {
                            func.addProperty("className", className);
                        }
                        functions.add(func);
                    }
                }
            }
        }
        
        // Also look for Cocos Creator style classes
        Pattern creatorPattern = Pattern.compile("cc\\.Class\\s*\\(\\s*\\{");
        Matcher creatorMatcher = creatorPattern.matcher(content);
        
        while (creatorMatcher.find()) {
            int classStart = creatorMatcher.end() - 1;
            int classEnd = CodeExtractionUtils.findMatchingBrace(content, classStart);
            if (classEnd == -1) continue;
            
            String classBody = content.substring(classStart + 1, classEnd);
            
            // Find methods in Creator-style classes
            Pattern methodPattern = Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*:\\s*function\\s*\\([^)]*\\)");
            Matcher methodMatcher = methodPattern.matcher(classBody);
            
            while (methodMatcher.find()) {
                String methodName = methodMatcher.group(1);
                
                // Skip property definitions
                if (methodName.equals("properties") || methodName.equals("extends")) continue;
                
                if (targetName == null || StringMatchingUtils.matchesAcrossNamingConventions(methodName, targetName, caseSensitive)) {
                    int methodPosition = classStart + 1 + methodMatcher.start();
                    String uniqueKey = "creator_" + methodName + "_" + methodPosition;
                    
                    if (!foundFunctions.contains(uniqueKey)) {
                        foundFunctions.add(uniqueKey);
                        
                        JsonObject func = new JsonObject();
                        func.addProperty("name", methodName);
                        func.addProperty("line", CodeExtractionUtils.getLineNumber(content, methodPosition));
                        func.addProperty("signature", methodName + methodMatcher.group(0).substring(methodName.length()));
                        func.addProperty("type", "cocos-creator-method");
                        functions.add(func);
                    }
                }
            }
        }
    }
    
    /**
     * Extracts a Java method at a specific position.
     */
    public static String extractJavaMethodAtPosition(String content, int position, String methodName) {
        // Search backwards for method start (including annotations and JavaDoc)
        int searchStart = Math.max(0, position - 500);
        String searchRegion = content.substring(searchStart, Math.min(position + 200, content.length()));
        
        // Look for the method signature
        Pattern methodPattern = Pattern.compile(
            "((?:/\\*\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/\\s*)?(?:@\\w+(?:\\([^)]*\\))?\\s*)*)" + // JavaDoc and annotations
            "((?:public|private|protected)\\s+)?" + // access modifier
            "((?:static|final|synchronized|abstract)\\s+)*" + // other modifiers  
            "([\\w<>\\[\\]]+\\s+)?" + // return type
            "(" + Pattern.quote(methodName) + ")\\s*\\([^)]*\\)\\s*" + // method name and params
            "(?:throws\\s+[^{]+)?\\s*\\{" // throws clause and opening brace
        );
        
        Matcher matcher = methodPattern.matcher(searchRegion);
        if (matcher.find()) {
            int methodStart = searchStart + matcher.start();
            int braceStart = searchStart + matcher.end() - 1; // Position of '{'
            
            // Find matching closing brace
            int braceEnd = CodeExtractionUtils.findMatchingBrace(content, braceStart);
            if (braceEnd != -1) {
                return content.substring(methodStart, braceEnd + 1);
            }
        }
        
        return null;
    }
    
    /**
     * Extracts a JavaScript/TypeScript function at a specific position.
     */
    public static String extractJavaScriptFunctionAtPosition(String content, int position, String functionName) {
        // Search around the position
        int searchStart = Math.max(0, position - 200);
        int searchEnd = Math.min(content.length(), position + 100);
        
        // Start from the line containing the match
        int lineStart = position;
        while (lineStart > 0 && content.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        
        // Try to find the function start from the beginning of the line
        String fromLine = content.substring(lineStart, searchEnd);
        
        // Check for various function patterns
        if (fromLine.contains("function " + functionName) || 
            fromLine.contains(functionName + " = function") ||
            fromLine.contains(functionName + " = (") ||
            fromLine.contains(functionName + " = async") ||
            fromLine.contains(functionName + ": function") ||
            fromLine.contains(functionName + "(")) {
            
            // Find where the function body starts
            int bodyStart = -1;
            boolean isArrowFunction = false;
            
            for (int i = lineStart; i < searchEnd; i++) {
                if (content.charAt(i) == '{') {
                    bodyStart = i;
                    break;
                } else if (i < content.length() - 1 && content.charAt(i) == '=' && content.charAt(i + 1) == '>') {
                    isArrowFunction = true;
                    i += 2;
                    // Skip whitespace
                    while (i < content.length() && Character.isWhitespace(content.charAt(i))) {
                        i++;
                    }
                    if (i < content.length() && content.charAt(i) == '{') {
                        bodyStart = i;
                        break;
                    } else {
                        // Arrow function without braces
                        return CodeExtractionUtils.extractArrowFunctionExpression(content, i);
                    }
                }
            }
            
            if (bodyStart != -1) {
                int bodyEnd = CodeExtractionUtils.findMatchingBrace(content, bodyStart);
                if (bodyEnd != -1) {
                    // Include any export/const/let/var prefix
                    int realStart = lineStart;
                    String prefix = content.substring(Math.max(0, lineStart - 50), lineStart);
                    if (prefix.matches(".*\\b(export|const|let|var)\\s*$")) {
                        realStart = Math.max(0, lineStart - 50);
                        while (realStart < lineStart && !Character.isWhitespace(content.charAt(realStart))) {
                            realStart++;
                        }
                    }
                    
                    return content.substring(realStart, bodyEnd + 1).trim();
                }
            }
        }
        
        return null;
    }
    
    /**
     * Extracts a Python function at a specific position.
     */
    public static String extractPythonFunctionAtPosition(String content, int position, String functionName) {
        // Find the start of the function definition
        int defStart = position;
        while (defStart > 0 && !content.substring(Math.max(0, defStart - 4), defStart).equals("def ")) {
            defStart--;
        }
        
        if (defStart > 0) {
            defStart -= 4; // Include "def "
            
            // Find the end of the function (next function or class at same indentation level)
            String[] lines = content.substring(defStart).split("\n");
            StringBuilder function = new StringBuilder();
            
            // Get indentation of the def line
            int defIndent = 0;
            for (char c : lines[0].toCharArray()) {
                if (c == ' ' || c == '\t') defIndent++;
                else break;
            }
            
            function.append(lines[0]).append("\n");
            
            // Collect lines until we find something at the same or lower indentation
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                
                // Skip empty lines
                if (line.trim().isEmpty()) {
                    function.append(line).append("\n");
                    continue;
                }
                
                // Count indentation
                int indent = 0;
                for (char c : line.toCharArray()) {
                    if (c == ' ' || c == '\t') indent++;
                    else break;
                }
                
                // If we find something at the same or lower indentation, stop
                if (indent <= defIndent && line.trim().matches("^(def|class|if|for|while|import|from)\\b.*")) {
                    break;
                }
                
                function.append(line).append("\n");
            }
            
            return function.toString().trim();
        }
        
        return null;
    }
}