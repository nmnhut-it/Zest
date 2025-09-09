package com.zps.zest.explanation.tools;

/**
 * Simple main method to test AdvancedGlobMatcher functionality
 */
public class GlobMatcherTestMain {
    
    public static void main(String[] args) {
        System.out.println("=== Testing AdvancedGlobMatcher ===");
        
        // Test 1: Basic wildcard
        testPattern("Test.java", "*.java", true);
        testPattern("Test.kt", "*.java", false);
        
        // Test 2: Multiple extensions
        testPattern("Test.java", "*.{java,kt}", true);
        testPattern("Test.kt", "*.{java,kt}", true);
        testPattern("Test.py", "*.{java,kt}", false);
        
        // Test 3: Recursive patterns
        testPattern("src/main/Test.java", "**/*.java", true);
        testPattern("src/main/java/Test.java", "src/**/*.java", true);
        testPattern("test/Test.java", "src/**/*.java", false);
        
        // Test 4: Character classes
        testPattern("Test123.java", "[Tt]est*.java", true);
        testPattern("test456.java", "[Tt]est*.java", true);
        testPattern("Best123.java", "[Tt]est*.java", false);
        
        // Test 5: Negation
        testPattern("TestFile.java", "!*test*", false);
        testPattern("RegularFile.java", "!*test*", true);
        
        // Test 6: Exclusion
        testExclusion("test/TestFile.java", "*test*", true);
        testExclusion("build/output.class", "**/build/**", true);
        testExclusion("src/main/App.java", "*test*", false);
        
        System.out.println("\n=== All tests completed! ===");
    }
    
    private static void testPattern(String filePath, String pattern, boolean expected) {
        boolean result = AdvancedGlobMatcher.matches(filePath, pattern);
        String status = (result == expected) ? "✅ PASS" : "❌ FAIL";
        System.out.println(String.format("%s: matches('%s', '%s') = %s (expected %s)", 
            status, filePath, pattern, result, expected));
    }
    
    private static void testExclusion(String filePath, String pattern, boolean expected) {
        boolean result = AdvancedGlobMatcher.isExcluded(filePath, pattern);
        String status = (result == expected) ? "✅ PASS" : "❌ FAIL";
        System.out.println(String.format("%s: isExcluded('%s', '%s') = %s (expected %s)", 
            status, filePath, pattern, result, expected));
    }
}