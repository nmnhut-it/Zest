package com.zps.zest.completion.diff

/**
 * Test to diagnose missing lines issue
 */
fun main() {
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
    
    println("=== Testing diff with identical content ===")
    val diff1 = WordDiffUtil.diffLines(original, modified)
    printDiffBlocks(diff1)
    
    val modifiedWithChange = """private String userIdToken(Long userId) {
    if (userId == null || userId < 0) {
        throw new IllegalArgumentException("User ID cannot be null or negative");
    }
    return leaderboardKey + ":" + userId;
}"""
    
    println("\n=== Testing diff with changes ===")
    val diff2 = WordDiffUtil.diffLines(original, modifiedWithChange)
    printDiffBlocks(diff2)
}

fun printDiffBlocks(diff: WordDiffUtil.LineDiffResult) {
    println("Total blocks: ${diff.blocks.size}")
    diff.blocks.forEachIndexed { idx, block ->
        println("\nBlock $idx: ${block.type}")
        println("  Original lines ${block.originalStartLine}-${block.originalEndLine}: ${block.originalLines.size} lines")
        block.originalLines.forEachIndexed { i, line ->
            println("    O[$i]: '$line'")
        }
        println("  Modified lines ${block.modifiedStartLine}-${block.modifiedEndLine}: ${block.modifiedLines.size} lines")
        block.modifiedLines.forEachIndexed { i, line ->
            println("    M[$i]: '$line'")
        }
    }
    
    // Check coverage
    val originalLineCount = diff.blocks.maxOfOrNull { it.originalEndLine + 1 } ?: 0
    println("\nOriginal lines covered: 0-${originalLineCount - 1}")
}
