package com.zps.zest.browser.utils;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Contains comprehensive patterns for matching JavaScript/TypeScript functions.
 */
public class JavaScriptPatterns {
    
    /**
     * Returns a list of patterns for matching JavaScript/TypeScript functions.
     */
    public static List<Pattern> getPatterns() {
        return Arrays.asList(
            // Traditional function declarations
            Pattern.compile("function\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)"),
            Pattern.compile("async\\s+function\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)"),
            Pattern.compile("function\\s*\\*\\s*([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)"), // generator
            Pattern.compile("async\\s+function\\s*\\*\\s*([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)"), // async generator
            
            // Variable assignments with function expressions
            Pattern.compile("(?:const|let|var)\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*function\\s*\\([^)]*\\)"),
            Pattern.compile("(?:const|let|var)\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*async\\s+function\\s*\\([^)]*\\)"),
            Pattern.compile("(?:const|let|var)\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*function\\s*\\*\\s*\\([^)]*\\)"),
            
            // Arrow functions
            Pattern.compile("(?:const|let|var)\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*\\([^)]*\\)\\s*=>"),
            Pattern.compile("(?:const|let|var)\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*async\\s*\\([^)]*\\)\\s*=>"),
            Pattern.compile("(?:const|let|var)\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*[a-zA-Z_$][a-zA-Z0-9_$]*\\s*=>"), // single param
            Pattern.compile("(?:const|let|var)\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*async\\s+[a-zA-Z_$][a-zA-Z0-9_$]*\\s*=>"),
            
            // Object methods (ES6 shorthand)
            Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)\\s*\\{"),
            Pattern.compile("async\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)\\s*\\{"),
            Pattern.compile("\\*\\s*([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)\\s*\\{"), // generator method
            
            // Object property functions
            Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*:\\s*function\\s*\\([^)]*\\)"),
            Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*:\\s*async\\s+function\\s*\\([^)]*\\)"),
            Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*:\\s*\\([^)]*\\)\\s*=>"),
            Pattern.compile("([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*:\\s*async\\s*\\([^)]*\\)\\s*=>"),
            
            // Class methods
            Pattern.compile("(?:static\\s+)?([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)\\s*\\{"),
            Pattern.compile("(?:static\\s+)?async\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)\\s*\\{"),
            Pattern.compile("(?:static\\s+)?\\*\\s*([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)\\s*\\{"),
            Pattern.compile("(?:get|set)\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)"), // getters/setters
            
            // Constructor
            Pattern.compile("(constructor)\\s*\\([^)]*\\)\\s*\\{"),
            
            // Exports
            Pattern.compile("export\\s+(?:default\\s+)?function\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)"),
            Pattern.compile("export\\s+(?:default\\s+)?async\\s+function\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)"),
            Pattern.compile("export\\s+(?:const|let|var)\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*\\([^)]*\\)\\s*=>"),
            Pattern.compile("export\\s+(?:const|let|var)\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*async\\s*\\([^)]*\\)\\s*=>"),
            Pattern.compile("export\\s+(?:const|let|var)\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*function"),
            
            // Module exports
            Pattern.compile("module\\.exports\\.([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*function"),
            Pattern.compile("module\\.exports\\.([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*\\([^)]*\\)\\s*=>"),
            Pattern.compile("exports\\.([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*function"),
            Pattern.compile("exports\\.([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*\\([^)]*\\)\\s*=>"),
            
            // IIFE (Immediately Invoked Function Expression) - special handling
            Pattern.compile("\\(function\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)"),
            
            // Object.defineProperty functions
            Pattern.compile("(?:value|get|set)\\s*:\\s*function\\s+([a-zA-Z_$][a-zA-Z0-9_$]*)"),
            
            // React/Vue component methods
            Pattern.compile("(?:componentDidMount|componentWillUnmount|render|mounted|created|beforeDestroy)\\s*\\([^)]*\\)"),
            
            // TypeScript specific
            Pattern.compile("(?:public|private|protected)\\s+(?:static\\s+)?(?:async\\s+)?([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\([^)]*\\)"),
            Pattern.compile("(?:readonly\\s+)?([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*\\([^)]*\\)\\s*=>"),
            
            // jQuery style
            Pattern.compile("\\$\\.fn\\.([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*function"),
            Pattern.compile("jQuery\\.fn\\.([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*=\\s*function")
        );
    }
    
    /**
     * Returns patterns for matching anonymous functions.
     */
    public static List<Pattern> getAnonymousPatterns() {
        return Arrays.asList(
            // Callbacks
            Pattern.compile("\\b(?:then|catch|finally|map|filter|reduce|forEach|find|some|every)\\s*\\(\\s*(?:async\\s*)?(?:function\\s*)?\\([^)]*\\)"),
            Pattern.compile("\\b(?:setTimeout|setInterval|requestAnimationFrame)\\s*\\(\\s*(?:async\\s*)?(?:function\\s*)?\\([^)]*\\)"),
            Pattern.compile("\\b(?:addEventListener|on[A-Z][a-zA-Z]*)\\s*\\(\\s*['\"][^'\"]+['\"]\\s*,\\s*(?:async\\s*)?(?:function\\s*)?\\([^)]*\\)"),
            
            // Promise executors
            Pattern.compile("new\\s+Promise\\s*\\(\\s*(?:async\\s*)?(?:function\\s*)?\\([^)]*\\)"),
            
            // Array methods with anonymous functions
            Pattern.compile("\\.(?:map|filter|reduce|forEach|find|some|every)\\s*\\(\\s*(?:async\\s*)?\\([^)]*\\)\\s*=>"),
            
            // Event handlers
            Pattern.compile("\\bon[A-Z][a-zA-Z]*\\s*=\\s*(?:async\\s*)?(?:function\\s*)?\\([^)]*\\)"),
            
            // Module patterns
            Pattern.compile("\\(\\s*(?:async\\s*)?function\\s*\\([^)]*\\)\\s*\\{[^}]+\\}\\s*\\)\\s*\\("), // IIFE
            
            // React hooks
            Pattern.compile("use(?:Effect|Callback|Memo|LayoutEffect)\\s*\\(\\s*(?:async\\s*)?\\([^)]*\\)\\s*=>")
        );
    }
}