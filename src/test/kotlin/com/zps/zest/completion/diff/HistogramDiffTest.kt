package com.zps.zest.completion.diff

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistogramDiffTest {
    
    @Test
    fun testSimpleDiff() {
        val source = mutableListOf("a", "b", "c")
        val target = mutableListOf("a", "x", "c")
        
        val diff = HistogramDiff<String>()
        val changes = diff.computeDiff(source, target, null)
        
        // Should have delete and insert changes
        assertTrue(changes.isNotEmpty())
    }
    
    @Test
    fun testCodeRefactoring() {
        // Test case where histogram diff should perform better
        val source = """
            public void method1() {
                doSomething();
                doAnotherThing();
            }
            
            public void method2() {
                doSomethingElse();
                doYetAnotherThing();
            }
        """.trimIndent().lines()
        
        val target = """
            public void method2() {
                doSomethingElse();
                doYetAnotherThing();
            }
            
            public void method1() {
                doSomething();
                doAnotherThing();
                doExtraThing();
            }
        """.trimIndent().lines()
        
        val diff = HistogramDiff<String>()
        val changes = diff.computeDiff(source.toMutableList(), target.toMutableList(), null)
        
        // Histogram diff should recognize that methods were swapped
        assertTrue(changes.isNotEmpty())
    }
    
    @Test
    fun testMultiLineDiff() {
        val original = """
            public class Example {
                private String name;
                
                public Example(String name) {
                    this.name = name;
                }
                
                public String getName() {
                    return name;
                }
            }
        """.trimIndent()
        
        val modified = """
            public class Example {
                private final String name;
                private final int id;
                
                public Example(String name, int id) {
                    this.name = name;
                    this.id = id;
                }
                
                public String getName() {
                    return name;
                }
                
                public int getId() {
                    return id;
                }
            }
        """.trimIndent()
        
        // Test line-level diff
        val lineDiff = WordDiffUtil.diffLines(
            original, 
            modified,
            WordDiffUtil.DiffAlgorithm.HISTOGRAM,
            "java"
        )
        
        // Should identify:
        // 1. Modified field declaration (added final)
        // 2. Added id field
        // 3. Modified constructor
        // 4. Added getId method
        
        var modifiedBlocks = 0
        var addedBlocks = 0
        
        for (block in lineDiff.blocks) {
            when (block.type) {
                WordDiffUtil.BlockType.MODIFIED -> modifiedBlocks++
                WordDiffUtil.BlockType.ADDED -> addedBlocks++
                else -> {}
            }
        }
        
        assertTrue(modifiedBlocks >= 2) // Field and constructor
        assertTrue(addedBlocks >= 1) // getId method
    }
    
    @Test
    fun testEmptyDiff() {
        val source = mutableListOf<String>()
        val target = mutableListOf("a", "b", "c")
        
        val diff = HistogramDiff<String>()
        val changes = diff.computeDiff(source, target, null)
        
        assertEquals(1, changes.size)
        assertEquals(0, changes[0].startOriginal)
        assertEquals(3, changes[0].endRevised)
    }
}
