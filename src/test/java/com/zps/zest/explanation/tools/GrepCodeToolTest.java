package com.zps.zest.explanation.tools;

import junit.framework.TestCase;

/**
 * Unit tests for AdvancedGlobMatcher to verify pattern matching functionality
 * Note: Simplified to test only the glob matcher without IntelliJ dependencies
 */
public class GrepCodeToolTest extends TestCase {

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

    public void testAdvancedGlobMatcher() {
        // Test basic glob patterns
        assertTrue(AdvancedGlobMatcher.matches("Test.java", "*.java"));
        assertTrue(AdvancedGlobMatcher.matches("src/main/Test.java", "*.java"));
        assertTrue(AdvancedGlobMatcher.matches("src/main/Test.java", "src/**/*.java"));
        
        // Test multiple extensions
        assertTrue(AdvancedGlobMatcher.matches("Test.java", "*.{java,kt}"));
        assertTrue(AdvancedGlobMatcher.matches("Test.kt", "*.{java,kt}"));
        assertFalse(AdvancedGlobMatcher.matches("Test.py", "*.{java,kt}"));
        
        // Test character classes
        assertTrue(AdvancedGlobMatcher.matches("Test123.java", "[Tt]est[0-9]*.java"));
        assertTrue(AdvancedGlobMatcher.matches("test456.java", "[Tt]est[0-9]*.java"));
        assertFalse(AdvancedGlobMatcher.matches("Best123.java", "[Tt]est[0-9]*.java"));
        
        // Test negation
        assertFalse(AdvancedGlobMatcher.matches("TestFile.java", "!*Test*"));
        assertTrue(AdvancedGlobMatcher.matches("RegularFile.java", "!*Test*"));
        
        // Test exclusion
        assertTrue(AdvancedGlobMatcher.isExcluded("test/TestFile.java", "*test*"));
        assertTrue(AdvancedGlobMatcher.isExcluded("build/output.class", "**/build/**"));
        assertFalse(AdvancedGlobMatcher.isExcluded("src/main/App.java", "*test*"));
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

}