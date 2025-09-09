package com.zps.zest.explanation.tools;

import junit.framework.TestCase;

/**
 * Simple standalone test for AdvancedGlobMatcher to verify basic functionality
 */
public class AdvancedGlobMatcherSimpleTest extends TestCase {

    public void testBasicGlobPatterns() {
        // Test basic wildcard matching
        assertTrue("*.java should match Test.java", 
            AdvancedGlobMatcher.matches("Test.java", "*.java"));
        
        assertTrue("*.java should match src/main/Test.java (filename matching)", 
            AdvancedGlobMatcher.matches("src/main/Test.java", "*.java"));
            
        assertFalse("*.java should not match Test.kt", 
            AdvancedGlobMatcher.matches("Test.kt", "*.java"));
    }

    public void testMultipleExtensions() {
        // Test {java,kt} pattern
        assertTrue("*.{java,kt} should match Test.java", 
            AdvancedGlobMatcher.matches("Test.java", "*.{java,kt}"));
            
        assertTrue("*.{java,kt} should match Test.kt", 
            AdvancedGlobMatcher.matches("Test.kt", "*.{java,kt}"));
            
        assertFalse("*.{java,kt} should not match Test.py", 
            AdvancedGlobMatcher.matches("Test.py", "*.{java,kt}"));
    }

    public void testRecursiveMatching() {
        // Test ** for recursive directory matching
        assertTrue("**/*.java should match src/main/Test.java", 
            AdvancedGlobMatcher.matches("src/main/Test.java", "**/*.java"));
            
        assertTrue("src/**/*.java should match src/main/java/Test.java", 
            AdvancedGlobMatcher.matches("src/main/java/Test.java", "src/**/*.java"));
            
        assertFalse("src/**/*.java should not match test/Test.java", 
            AdvancedGlobMatcher.matches("test/Test.java", "src/**/*.java"));
    }

    public void testCharacterClasses() {
        // Test [Tt]est pattern
        assertTrue("[Tt]est*.java should match Test123.java", 
            AdvancedGlobMatcher.matches("Test123.java", "[Tt]est*.java"));
            
        assertTrue("[Tt]est*.java should match test456.java", 
            AdvancedGlobMatcher.matches("test456.java", "[Tt]est*.java"));
            
        assertFalse("[Tt]est*.java should not match Best123.java", 
            AdvancedGlobMatcher.matches("Best123.java", "[Tt]est*.java"));
    }

    public void testNegationPatterns() {
        // Test !pattern negation
        assertFalse("!*test* should exclude TestFile.java", 
            AdvancedGlobMatcher.matches("TestFile.java", "!*test*"));
            
        assertTrue("!*test* should include RegularFile.java", 
            AdvancedGlobMatcher.matches("RegularFile.java", "!*test*"));
    }

    public void testExclusionLogic() {
        // Test isExcluded method
        assertTrue("Should exclude test files", 
            AdvancedGlobMatcher.isExcluded("test/TestFile.java", "*test*"));
            
        assertTrue("Should exclude build directories", 
            AdvancedGlobMatcher.isExcluded("build/output.class", "**/build/**"));
            
        assertFalse("Should not exclude regular source files", 
            AdvancedGlobMatcher.isExcluded("src/main/App.java", "*test*"));
    }

    public void testMultiplePatterns() {
        // Test matchesAny method
        assertTrue("Should match any of the patterns", 
            AdvancedGlobMatcher.matchesAny("Test.java", "*.kt", "*.java", "*.py"));
            
        assertFalse("Should not match any of the patterns", 
            AdvancedGlobMatcher.matchesAny("Test.cpp", "*.kt", "*.java", "*.py"));
    }

    public void testEdgeCases() {
        // Test null and empty patterns
        assertTrue("Null pattern should match anything", 
            AdvancedGlobMatcher.matches("Test.java", null));
            
        assertTrue("Empty pattern should match anything", 
            AdvancedGlobMatcher.matches("Test.java", ""));
            
        // Test exact matches
        assertTrue("Exact filename should match", 
            AdvancedGlobMatcher.matches("Test.java", "Test.java"));
            
        assertFalse("Different filename should not match", 
            AdvancedGlobMatcher.matches("Test.java", "Other.java"));
    }

    public void testComplexPatterns() {
        // Test combinations
        assertTrue("Complex pattern should work", 
            AdvancedGlobMatcher.matches("src/main/java/com/example/UserService.java", 
                                      "src/**/java/**/*Service.java"));
                                      
        assertTrue("Pattern with multiple wildcards", 
            AdvancedGlobMatcher.matches("test-file-123.java", "*-file-*.java"));
            
        assertFalse("Pattern should be specific", 
            AdvancedGlobMatcher.matches("src/main/UserService.kt", 
                                      "src/**/java/**/*Service.java"));
    }
}