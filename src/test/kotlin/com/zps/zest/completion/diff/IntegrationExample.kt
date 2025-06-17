package com.zps.zest.completion.diff

/**
 * Example demonstrating the enhanced diff rendering features
 */
object IntegrationExample {
    
    fun demonstrateWordDiff() {
        // Example 1: Simple word diff
        val original = "public void processData(String input)"
        val modified = "public void processData(String input, Options options)"
        
        val diff = WordDiffUtil.diffWords(original, modified, "java")
        
        println("Word Diff Example:")
        println("Original segments:")
        WordDiffUtil.mergeSegments(diff.originalSegments).forEach { segment ->
            println("  ${segment.type}: '${segment.text}'")
        }
        
        println("Modified segments:")
        WordDiffUtil.mergeSegments(diff.modifiedSegments).forEach { segment ->
            println("  ${segment.type}: '${segment.text}'")
        }
        
        println("Similarity: ${(diff.similarity * 100).toInt()}%")
    }
    
    fun demonstrateMultiLineDiff() {
        // Example 2: Multi-line diff with histogram algorithm
        val original = """
            public class Calculator {
                private int value;
                
                public Calculator(int value) {
                    this.value = value;
                }
                
                public int getValue() {
                    return value;
                }
            }
        """.trimIndent()
        
        val modified = """
            public class Calculator {
                private final int value;
                private String name;
                
                public Calculator(int value, String name) {
                    this.value = value;
                    this.name = name;
                }
                
                public int getValue() {
                    return value;
                }
                
                public String getName() {
                    return name;
                }
            }
        """.trimIndent()
        
        val lineDiff = WordDiffUtil.diffLines(
            original, 
            modified, 
            WordDiffUtil.DiffAlgorithm.HISTOGRAM,
            "java"
        )
        
        println("\nMulti-line Diff Example:")
        lineDiff.blocks.forEach { block ->
            when (block.type) {
                WordDiffUtil.BlockType.MODIFIED -> {
                    println("Modified (lines ${block.originalStartLine}-${block.originalEndLine}):")
                    println("  Original: ${block.originalLines.joinToString(" / ")}")
                    println("  Modified: ${block.modifiedLines.joinToString(" / ")}")
                }
                WordDiffUtil.BlockType.ADDED -> {
                    println("Added (at line ${block.modifiedStartLine}):")
                    block.modifiedLines.forEach { println("  + $it") }
                }
                WordDiffUtil.BlockType.DELETED -> {
                    println("Deleted (lines ${block.originalStartLine}-${block.originalEndLine}):")
                    block.originalLines.forEach { println("  - $it") }
                }
                else -> {} // Skip unchanged
            }
        }
    }
    
    fun demonstrateCamelCaseDiff() {
        // Example 3: CamelCase-aware diffing
        val original = "myOldVariableName"
        val modified = "myNewVariableName"
        
        val diff = WordDiffUtil.diffWords(original, modified, "java")
        
        println("\nCamelCase Diff Example:")
        println("Original tokens:")
        diff.originalSegments.forEach { segment ->
            if (segment.type != WordDiffUtil.ChangeType.UNCHANGED) {
                println("  ${segment.type}: '${segment.text}'")
            }
        }
        
        println("Modified tokens:")
        diff.modifiedSegments.forEach { segment ->
            if (segment.type != WordDiffUtil.ChangeType.UNCHANGED) {
                println("  ${segment.type}: '${segment.text}'")
            }
        }
    }
    
    fun demonstrateCodeNormalization() {
        // Example 4: Code normalization
        val messyJava = """
            public   void   method( )
            {
                if(condition){
                    doSomething( )  ;
                }
            }
        """.trimIndent()
        
        val normalized = WordDiffUtil.normalizeCode(messyJava, "java")
        
        println("\nCode Normalization Example:")
        println("Original:")
        println(messyJava)
        println("\nNormalized:")
        println(normalized)
    }
    
    fun demonstrateCommonSubsequences() {
        // Example 5: Finding common subsequences
        val original = "The quick brown fox jumps over the lazy dog"
        val modified = "The fast brown fox leaps over the sleeping dog"
        
        val subsequences = WordDiffUtil.findCommonSubsequences(original, modified, 5)
        
        println("\nCommon Subsequences Example:")
        subsequences.forEach { subseq ->
            println("  '${subseq.text}' at positions [${subseq.originalStart}-${subseq.originalEnd}] -> [${subseq.modifiedStart}-${subseq.modifiedEnd}]")
        }
    }
    
    @JvmStatic
    fun main(args: Array<String>) {
        demonstrateWordDiff()
        demonstrateMultiLineDiff()
        demonstrateCamelCaseDiff()
        demonstrateCodeNormalization()
        demonstrateCommonSubsequences()
    }
}
