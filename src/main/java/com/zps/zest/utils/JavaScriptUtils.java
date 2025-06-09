package com.zps.zest.utils;

public class JavaScriptUtils {
    
    /**
     * Escapes a string for safe inclusion in JavaScript code.
     * Handles quotes, newlines, and other special characters.
     */
    public static String escapeForJavaScript(String input) {
        if (input == null) return "";
        
        return input
            .replace("\\", "\\\\")     // Backslash must be first
            .replace("\"", "\\\"")     // Double quotes
            .replace("'", "\\'")       // Single quotes
            .replace("\n", "\\n")      // Newlines
            .replace("\r", "\\r")      // Carriage returns
            .replace("\t", "\\t")      // Tabs
            .replace("\f", "\\f")      // Form feeds
            .replace("\b", "\\b")      // Backspace
            .replace("\u0000", "\\0"); // Null character
    }
    
    /**
     * Escapes a string for safe inclusion in HTML.
     */
    public static String escapeForHtml(String input) {
        if (input == null) return "";
        
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}
