package com.zps.zest.completion.diff

/**
 * Simple test runner to verify diff functionality
 */
fun main() {
    println("Testing WordDiffUtil...")
    
    // Test the missing lines issue
    testMissingLinesIssue()
    
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

fun testMissingLinesIssue() {
    println("\n=== Testing Missing Lines Issue ===")
    
    val original = """private String userIdToken(Long userId) {
    if (userId == null) {
        throw new IllegalArgumentException("User ID cannot be null");
    }
    return leaderboardKey + ":" + userId;
}"""
    
    val modified = """private String userIdToken(Long userId) {
    if (userId == null) {
        throw new IllegalArgumentException("User ID cannot be null");
    }
    return leaderboardKey + ":" + userId;
}"""
    
    println("Original has ${original.lines().size} lines:")
    original.lines().forEachIndexed { idx, line ->
        println("  [$idx] '$line'")
    }
    
    println("\nModified has ${modified.lines().size} lines:")
    modified.lines().forEachIndexed { idx, line ->
        println("  [$idx] '$line'")
    }
    
    val diff = WordDiffUtil.diffLines(original, modified)
    println("\nDiff blocks: ${diff.blocks.size}")
    diff.blocks.forEachIndexed { idx, block ->
        println("\nBlock $idx: ${block.type}")
        println("  Original lines ${block.originalStartLine}-${block.originalEndLine}")
        println("  Modified lines ${block.modifiedStartLine}-${block.modifiedEndLine}")
        println("  Original content: ${block.originalLines.size} lines")
        println("  Modified content: ${block.modifiedLines.size} lines")
    }
    
    // Check which lines are covered
    val maxOriginalLine = diff.blocks.maxOfOrNull { it.originalEndLine } ?: -1
    println("\nMax original line covered: $maxOriginalLine")
    println("Total original lines: ${original.lines().size}")
    if (maxOriginalLine < original.lines().size - 1) {
        println("WARNING: Missing lines! Only covered up to line $maxOriginalLine but have ${original.lines().size} lines")
    }
}
