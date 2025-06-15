package com.zps.zest.gdiff

import org.junit.Assert.*
import org.junit.Test

/**
 * Comprehensive tests for GDiff functionality
 */
class GDiffTest {
    
    private val gdiff = GDiff()
    
    @Test
    fun testIdenticalStrings() {
        val source = "Hello\nWorld"
        val target = "Hello\nWorld"
        
        val result = gdiff.diffStrings(source, target)
        
        assertTrue("Strings should be identical", result.identical)
        assertFalse("Should have no changes", result.hasChanges())
        assertEquals("Should have 0 total changes", 0, result.getStatistics().totalChanges)
    }
    
    @Test
    fun testSimpleInsertion() {
        val source = "Line 1\nLine 3"
        val target = "Line 1\nLine 2\nLine 3"
        
        val result = gdiff.diffStrings(source, target)
        
        assertFalse("Strings should not be identical", result.identical)
        assertTrue("Should have changes", result.hasChanges())
        
        val stats = result.getStatistics()
        assertEquals("Should have 1 addition", 1, stats.additions)
        assertEquals("Should have 0 deletions", 0, stats.deletions)
        assertEquals("Should have 0 modifications", 0, stats.modifications)
    }
    
    @Test
    fun testSimpleDeletion() {
        val source = "Line 1\nLine 2\nLine 3"
        val target = "Line 1\nLine 3"
        
        val result = gdiff.diffStrings(source, target)
        
        val stats = result.getStatistics()
        assertEquals("Should have 0 additions", 0, stats.additions)
        assertEquals("Should have 1 deletion", 1, stats.deletions)
        assertEquals("Should have 0 modifications", 0, stats.modifications)
    }
    
    @Test
    fun testSimpleModification() {
        val source = "Hello World"
        val target = "Hello Universe"
        
        val result = gdiff.diffStrings(source, target)
        
        val stats = result.getStatistics()
        assertTrue("Should have modifications", stats.modifications > 0)
    }
    
    @Test
    fun testMultiLanguageSupport() {
        val source = """
            English text
            中文文本
            العربية
            🚀 Emoji
        """.trimIndent()
        
        val target = """
            English text modified
            中文文本修改
            العربية معدلة
            🚀🌟 Emoji
        """.trimIndent()
        
        val result = gdiff.diffStrings(source, target)
        
        assertTrue("Should detect changes in multi-language content", result.hasChanges())
        val stats = result.getStatistics()
        assertTrue("Should have changes", stats.totalChanges > 0)
    }
    
    @Test
    fun testIgnoreCaseConfig() {
        val source = "Hello World"
        val target = "hello world"
        
        // Default config (case sensitive)
        val defaultResult = gdiff.diffStrings(source, target)
        assertTrue("Should detect case differences by default", defaultResult.hasChanges())
        
        // Ignore case config
        val ignoreCaseConfig = GDiff.DiffConfig(ignoreCase = true)
        val ignoreCaseResult = gdiff.diffStrings(source, target, ignoreCaseConfig)
        assertFalse("Should ignore case differences", ignoreCaseResult.hasChanges())
    }
    
    @Test
    fun testIgnoreWhitespaceConfig() {
        val source = "  Hello World  "
        val target = "Hello World"
        
        // Default config (whitespace sensitive)
        val defaultResult = gdiff.diffStrings(source, target)
        assertTrue("Should detect whitespace differences by default", defaultResult.hasChanges())
        
        // Ignore whitespace config
        val ignoreWhitespaceConfig = GDiff.DiffConfig(ignoreWhitespace = true)
        val ignoreWhitespaceResult = gdiff.diffStrings(source, target, ignoreWhitespaceConfig)
        assertFalse("Should ignore whitespace differences", ignoreWhitespaceResult.hasChanges())
    }
    
    @Test
    fun testUnifiedDiffGeneration() {
        val source = "Line 1\nLine 2\nLine 3"
        val target = "Line 1\nLine 2 modified\nLine 3"
        
        val unifiedDiff = gdiff.generateUnifiedDiff(
            source, target, 
            "source.txt", "target.txt"
        )
        
        assertNotNull("Unified diff should not be null", unifiedDiff)
        assertTrue("Should contain source filename", unifiedDiff.contains("source.txt"))
        assertTrue("Should contain target filename", unifiedDiff.contains("target.txt"))
        assertTrue("Should contain change markers", unifiedDiff.contains("-") || unifiedDiff.contains("+"))
    }
    
    @Test
    fun testSideBySideDiff() {
        val source = "Line 1\nLine 2"
        val target = "Line 1\nLine 2 modified"
        
        val sideBySide = gdiff.generateSideBySideDiff(source, target)
        
        assertNotNull("Side-by-side diff should not be null", sideBySide)
        assertTrue("Should have at least one row", sideBySide.isNotEmpty())
        
        val hasChangeRow = sideBySide.any { it.type == GDiff.ChangeType.CHANGE }
        assertTrue("Should have at least one change row", hasChangeRow)
    }
    
    @Test
    fun testAreIdenticalUtility() {
        val source = "Hello\nWorld"
        val target1 = "Hello\nWorld"
        val target2 = "Hello\nUniverse"
        
        assertTrue("Identical strings should return true", 
                  gdiff.areIdentical(source, target1))
        assertFalse("Different strings should return false", 
                   gdiff.areIdentical(source, target2))
    }
    
    @Test
    fun testGetChangedLinesOnly() {
        val source = "Line 1\nLine 2\nLine 3\nLine 4"
        val target = "Line 1\nLine 2 modified\nLine 3\nLine 4"
        
        val result = gdiff.getChangedLinesOnly(source, target)
        
        assertTrue("Should have changes", result.hasChanges())
        val changeTypes = result.changes.map { it.type }.toSet()
        assertFalse("Should not contain EQUAL changes", 
                   changeTypes.contains(GDiff.ChangeType.EQUAL))
    }
    
    @Test
    fun testEmptyStrings() {
        val empty1 = ""
        val empty2 = ""
        val nonEmpty = "Not empty"
        
        assertTrue("Two empty strings should be identical", 
                  gdiff.areIdentical(empty1, empty2))
        
        val result = gdiff.diffStrings(empty1, nonEmpty)
        assertTrue("Empty vs non-empty should have changes", result.hasChanges())
        assertEquals("Should have 1 addition", 1, result.getStatistics().additions)
    }
    
    @Test
    fun testLargeFiles() {
        val largeSource = (1..1000).joinToString("\n") { "Line $it" }
        val largeTarget = (1..1000).joinToString("\n") { 
            if (it == 500) "Line $it modified" else "Line $it" 
        }
        
        val result = gdiff.diffStrings(largeSource, largeTarget)
        
        assertTrue("Should detect change in large file", result.hasChanges())
        val stats = result.getStatistics()
        assertTrue("Should have reasonable number of changes", stats.totalChanges < 10)
    }
    
    @Test
    fun testComplexChanges() {
        val source = """
            function hello() {
                console.log("Hello");
                return true;
            }
        """.trimIndent()
        
        val target = """
            function hello(name) {
                console.log("Hello " + name);
                console.log("Debug info");
                return true;
            }
        """.trimIndent()
        
        val result = gdiff.diffStrings(source, target)
        
        assertTrue("Should detect complex changes", result.hasChanges())
        val stats = result.getStatistics()
        assertTrue("Should have multiple types of changes", 
                  stats.additions > 0 || stats.modifications > 0)
    }
}
