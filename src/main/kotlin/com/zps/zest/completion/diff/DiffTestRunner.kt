package com.zps.zest.completion.diff

/**
 * Simple test runner to verify diff functionality
 */
fun main() {
    println("Testing WordDiffUtil...")
    
    // Test 1: Simple word diff
    val original = "Hello world"
    val modified = "Hello beautiful world"
    val result = WordDiffUtil.diffWords(original, modified)
    
    println("\nTest 1 - Simple word diff:")
    println("Original: $original")
    println("Modified: $modified")
    println("Similarity: ${(result.similarity * 100).toInt()}%")
    
    // Test 2: Java code normalization
    val messyJava = """
        public void method()
        {
            if(condition){
                doSomething()  ;
            }
        }
    """.trimIndent()
    
    val normalized = WordDiffUtil.normalizeCode(messyJava, "java")
    println("\nTest 2 - Java normalization:")
    println("Original:\n$messyJava")
    println("\nNormalized:\n$normalized")
    
    // Test 3: Multi-line diff
    val originalCode = """
        public void process(String input) {
            validate(input);
            transform(input);
        }
    """.trimIndent()
    
    val modifiedCode = """
        public void process(String input, Options opts) {
            if (opts.validate) {
                validate(input);
            }
            transform(input, opts);
        }
    """.trimIndent()
    
    val lineDiff = WordDiffUtil.diffLines(originalCode, modifiedCode, WordDiffUtil.DiffAlgorithm.HISTOGRAM, "java")
    
    println("\nTest 3 - Multi-line diff:")
    println("Found ${lineDiff.blocks.size} diff blocks")
    lineDiff.blocks.forEach { block ->
        println("Block type: ${block.type}")
        if (block.originalLines.isNotEmpty()) {
            println("  Original lines: ${block.originalLines.joinToString(" / ")}")
        }
        if (block.modifiedLines.isNotEmpty()) {
            println("  Modified lines: ${block.modifiedLines.joinToString(" / ")}")
        }
    }
    
    println("\nAll tests completed!")
}
