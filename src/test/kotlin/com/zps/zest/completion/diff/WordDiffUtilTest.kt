package com.zps.zest.completion.diff

import org.junit.Assert.*
import org.junit.Test

class WordDiffUtilTest {
    
    @Test
    fun testSimpleWordDiff() {
        val original = "Hello world"
        val modified = "Hello beautiful world"
        
        val result = WordDiffUtil.diffWords(original, modified)
        
        // Original segments
        assertEquals(2, result.originalSegments.size)
        assertEquals("Hello ", result.originalSegments[0].text)
        assertEquals(WordDiffUtil.ChangeType.UNCHANGED, result.originalSegments[0].type)
        assertEquals("world", result.originalSegments[1].text)
        assertEquals(WordDiffUtil.ChangeType.UNCHANGED, result.originalSegments[1].type)
        
        // Modified segments
        assertEquals(3, result.modifiedSegments.size)
        assertEquals("Hello ", result.modifiedSegments[0].text)
        assertEquals(WordDiffUtil.ChangeType.UNCHANGED, result.modifiedSegments[0].type)
        assertEquals("beautiful ", result.modifiedSegments[1].text)
        assertEquals(WordDiffUtil.ChangeType.ADDED, result.modifiedSegments[1].type)
        assertEquals("world", result.modifiedSegments[2].text)
        assertEquals(WordDiffUtil.ChangeType.UNCHANGED, result.modifiedSegments[2].type)
    }
    
    @Test
    fun testWordReplacement() {
        val original = "The quick brown fox"
        val modified = "The slow brown fox"
        
        val result = WordDiffUtil.diffWords(original, modified)
        
        // Check that "quick" was marked as modified/deleted
        val quickSegment = result.originalSegments.find { it.text.contains("quick") }
        assertNotNull(quickSegment)
        assertTrue(quickSegment!!.type == WordDiffUtil.ChangeType.MODIFIED || 
                  quickSegment.type == WordDiffUtil.ChangeType.DELETED)
        
        // Check that "slow" was marked as modified/added
        val slowSegment = result.modifiedSegments.find { it.text.contains("slow") }
        assertNotNull(slowSegment)
        assertTrue(slowSegment!!.type == WordDiffUtil.ChangeType.MODIFIED || 
                  slowSegment.type == WordDiffUtil.ChangeType.ADDED)
    }
    
    @Test
    fun testCodeNormalization() {
        val original = "function test() {\t\n\treturn true;  \n}"
        val normalized = WordDiffUtil.normalizeCode(original)
        
        assertEquals("function test() {\n    return true;\n}", normalized)
    }
    
    @Test
    fun testMergeSegments() {
        val segments = listOf(
            WordDiffUtil.WordSegment("Hello", WordDiffUtil.ChangeType.UNCHANGED),
            WordDiffUtil.WordSegment(" ", WordDiffUtil.ChangeType.UNCHANGED),
            WordDiffUtil.WordSegment("world", WordDiffUtil.ChangeType.UNCHANGED),
            WordDiffUtil.WordSegment("!", WordDiffUtil.ChangeType.ADDED),
            WordDiffUtil.WordSegment("!", WordDiffUtil.ChangeType.ADDED)
        )
        
        val merged = WordDiffUtil.mergeSegments(segments)
        
        assertEquals(2, merged.size)
        assertEquals("Hello world", merged[0].text)
        assertEquals(WordDiffUtil.ChangeType.UNCHANGED, merged[0].type)
        assertEquals("!!", merged[1].text)
        assertEquals(WordDiffUtil.ChangeType.ADDED, merged[1].type)
    }
    
    @Test
    fun testComplexCodeDiff() {
        val original = "public void calculate(int x) { return x * 2; }"
        val modified = "public int calculate(int x, int y) { return x * y; }"
        
        val result = WordDiffUtil.diffWords(original, modified)
        
        // Should detect void->int change and parameter addition
        assertTrue(result.originalSegments.any { it.text == "void" && it.type != WordDiffUtil.ChangeType.UNCHANGED })
        assertTrue(result.modifiedSegments.any { it.text == "int" && it.type != WordDiffUtil.ChangeType.UNCHANGED })
        assertTrue(result.modifiedSegments.any { it.text.contains("y") && it.type == WordDiffUtil.ChangeType.ADDED })
    }
}
