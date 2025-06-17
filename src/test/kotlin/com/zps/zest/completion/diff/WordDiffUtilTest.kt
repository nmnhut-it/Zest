package com.zps.zest.completion.diff

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WordDiffUtilTest {
    
    @Test
    fun testSimpleWordDiff() {
        val original = "Hello world"
        val modified = "Hello beautiful world"
        
        val result = WordDiffUtil.diffWords(original, modified)
        
        // Check original segments
        val origMerged = WordDiffUtil.mergeSegments(result.originalSegments)
        assertEquals(1, origMerged.size)
        assertEquals("Hello world", origMerged[0].text)
        assertEquals(WordDiffUtil.ChangeType.UNCHANGED, origMerged[0].type)
        
        // Check modified segments
        val modMerged = WordDiffUtil.mergeSegments(result.modifiedSegments)
        assertEquals(3, modMerged.size)
        assertEquals("Hello ", modMerged[0].text)
        assertEquals(WordDiffUtil.ChangeType.UNCHANGED, modMerged[0].type)
        assertEquals("beautiful ", modMerged[1].text)
        assertEquals(WordDiffUtil.ChangeType.ADDED, modMerged[1].type)
        assertEquals("world", modMerged[2].text)
        assertEquals(WordDiffUtil.ChangeType.UNCHANGED, modMerged[2].type)
    }
    
    @Test
    fun testJavaCodeNormalization() {
        val original = """
            public void method()
            {
                if(condition){
                    doSomething()  ;
                }
            }
        """.trimIndent()
        
        val expected = """
            public void method() {
                if (condition) {
                    doSomething();
                }
            }
        """.trimIndent()
        
        val normalized = WordDiffUtil.normalizeCode(original, "java")
        assertEquals(expected, normalized)
    }
    
    @Test
    fun testCamelCaseSplitting() {
        val original = "myVariableName"
        val modified = "myUpdatedVariableName"
        
        val result = WordDiffUtil.diffWords(original, modified, "java")
        
        // With camelCase splitting enabled for Java
        assertTrue(result.originalSegments.any { it.text == "Variable" })
        assertTrue(result.modifiedSegments.any { it.text == "Updated" && it.type == WordDiffUtil.ChangeType.ADDED })
    }
    
    @Test
    fun testMethodSignatureChange() {
        val original = "public void process(String input)"
        val modified = "public void process(String input, Options options)"
        
        val result = WordDiffUtil.diffWords(original, modified, "java")
        
        // Should identify the added parameter
        assertTrue(result.modifiedSegments.any { 
            it.text.contains("Options") && it.type == WordDiffUtil.ChangeType.ADDED 
        })
        
        // Check similarity score
        assertTrue(result.similarity > 0.5) // Most of the signature is unchanged
    }
    
    @Test
    fun testMultiLineDiff() {
        val original = """
            public class Example {
                private String name;
                
                public Example(String name) {
                    this.name = name;
                }
            }
        """.trimIndent()
        
        val modified = """
            public class Example {
                private final String name;
                private int id;
                
                public Example(String name, int id) {
                    this.name = name;
                    this.id = id;
                }
            }
        """.trimIndent()
        
        val lineDiff = WordDiffUtil.diffLines(original, modified, WordDiffUtil.DiffAlgorithm.HISTOGRAM, "java")
        
        // Verify we detect the right blocks
        val modifiedBlocks = lineDiff.blocks.filter { it.type == WordDiffUtil.BlockType.MODIFIED }
        val addedBlocks = lineDiff.blocks.filter { it.type == WordDiffUtil.BlockType.ADDED }
        
        assertTrue(modifiedBlocks.isNotEmpty()) // Field and constructor modifications
        assertTrue(addedBlocks.isNotEmpty()) // Added id field and assignment
    }
    
    @Test
    fun testCommonSubsequences() {
        val original = "The quick brown fox jumps"
        val modified = "The fast brown fox leaps"
        
        val subsequences = WordDiffUtil.findCommonSubsequences(original, modified, 3)
        
        // Should find "The ", "brown fox "
        assertTrue(subsequences.any { it.text.contains("The") })
        assertTrue(subsequences.any { it.text.contains("brown fox") })
    }
    
    @Test
    fun testJavaScriptNormalization() {
        val original = "const func=()=>{return x;}"
        val normalized = WordDiffUtil.normalizeCode(original, "javascript")
        
        // Should normalize arrow functions and spacing
        assertTrue(normalized.contains(" => "))
        assertTrue(normalized.contains("return x;"))
    }
    
    @Test
    fun testPythonNormalization() {
        val original = "def  method(x,y):return x+y"
        val normalized = WordDiffUtil.normalizeCode(original, "python")
        
        // Should normalize function definitions and spacing
        assertEquals("def method(x, y): return x+y", normalized)
    }
    
    @Test
    fun testEmptyStringDiff() {
        val original = ""
        val modified = "New content"
        
        val result = WordDiffUtil.diffWords(original, modified)
        
        assertTrue(result.originalSegments.isEmpty())
        assertTrue(result.modifiedSegments.all { it.type == WordDiffUtil.ChangeType.ADDED })
        assertEquals(0.0, result.similarity)
    }
    
    @Test
    fun testIdenticalStrings() {
        val text = "public void method() { return; }"
        
        val result = WordDiffUtil.diffWords(text, text, "java")
        
        assertTrue(result.originalSegments.all { it.type == WordDiffUtil.ChangeType.UNCHANGED })
        assertTrue(result.modifiedSegments.all { it.type == WordDiffUtil.ChangeType.UNCHANGED })
        assertEquals(1.0, result.similarity)
    }
    
    @Test
    fun testTokenOffsets() {
        val original = "Hello world"
        val modified = "Hi world"
        
        val result = WordDiffUtil.diffWords(original, modified)
        
        // Check that offsets are properly set
        val firstOriginalSegment = result.originalSegments.first()
        assertTrue(firstOriginalSegment.startOffset >= 0)
        assertTrue(firstOriginalSegment.endOffset > firstOriginalSegment.startOffset)
    }
}
