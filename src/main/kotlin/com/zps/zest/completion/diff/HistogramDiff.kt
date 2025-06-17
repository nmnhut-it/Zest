package com.zps.zest.completion.diff

import com.github.difflib.algorithm.Change
import com.github.difflib.algorithm.DiffAlgorithmI
import com.github.difflib.algorithm.DiffAlgorithmListener
import com.github.difflib.patch.DeltaType

/**
 * Implementation of the Histogram diff algorithm
 * 
 * The histogram diff algorithm is particularly good for code diffs because it:
 * 1. Handles code with many similar lines better than Myers
 * 2. Produces more intuitive diffs for refactored code
 * 3. Better handles moved blocks of code
 * 
 * Algorithm overview:
 * 1. Find unique lines that appear exactly once in both sequences (anchors)
 * 2. Use these anchors to split the problem into smaller sub-problems
 * 3. Recursively apply the algorithm to sub-problems
 * 4. Fall back to simple diff for small sequences
 */
class HistogramDiff<T> : DiffAlgorithmI<T> {
    
    companion object {
        // Threshold for falling back to simple algorithm
        private const val SIMPLE_THRESHOLD = 20
    }
    
    private val simpleDiff = SimpleDiffAlgorithm<T>()
    
    override fun computeDiff(
        source: MutableList<T>?, 
        target: MutableList<T>?, 
        progress: DiffAlgorithmListener?
    ): MutableList<Change> {
        if (source == null || target == null) {
            return mutableListOf()
        }
        
        // Convert to Change objects for compatibility
        val changes = mutableListOf<Change>()
        
        if (source.isEmpty() && target.isEmpty()) {
            return changes
        }
        
        if (source.isEmpty()) {
            // Everything in target is an insertion
            changes.add(createChange(DeltaType.INSERT, 0, 0, 0, target.size))
            return changes
        }
        
        if (target.isEmpty()) {
            // Everything in source is a deletion
            changes.add(createChange(DeltaType.DELETE, 0, source.size, 0, 0))
            return changes
        }
        
        // For small sequences, use simple diff
        if (source.size < SIMPLE_THRESHOLD && target.size < SIMPLE_THRESHOLD) {
            return simpleDiff.computeDiff(source, target, progress)
        }
        
        // Find anchors (unique lines)
        val anchors = findAnchors(source, target)
        
        if (anchors.isEmpty()) {
            // No anchors found, use simple diff
            return simpleDiff.computeDiff(source, target, progress)
        }
        
        // Split into sub-problems using anchors
        var sourceStart = 0
        var targetStart = 0
        
        for (anchor in anchors) {
            // Process the region before this anchor
            if (anchor.sourceIndex > sourceStart || anchor.targetIndex > targetStart) {
                val subSource = source.subList(sourceStart, anchor.sourceIndex).toMutableList()
                val subTarget = target.subList(targetStart, anchor.targetIndex).toMutableList()
                
                if (subSource.isNotEmpty() || subTarget.isNotEmpty()) {
                    val subChanges = simpleDiff.computeDiff(subSource, subTarget, progress)
                    
                    // Adjust positions
                    for (change in subChanges) {
                        changes.add(createChange(
                            change.deltaType,
                            change.startOriginal + sourceStart,
                            change.endOriginal + sourceStart,
                            change.startRevised + targetStart,
                            change.endRevised + targetStart
                        ))
                    }
                }
            }
            
            // Move past the anchor (it's unchanged)
            sourceStart = anchor.sourceIndex + 1
            targetStart = anchor.targetIndex + 1
        }
        
        // Process the region after the last anchor
        if (sourceStart < source.size || targetStart < target.size) {
            val subSource = if (sourceStart < source.size) 
                source.subList(sourceStart, source.size).toMutableList() 
            else mutableListOf()
            val subTarget = if (targetStart < target.size) 
                target.subList(targetStart, target.size).toMutableList()
            else mutableListOf()
            
            if (subSource.isNotEmpty() || subTarget.isNotEmpty()) {
                val subChanges = simpleDiff.computeDiff(subSource, subTarget, progress)
                
                // Adjust positions
                for (change in subChanges) {
                    changes.add(createChange(
                        change.deltaType,
                        change.startOriginal + sourceStart,
                        change.endOriginal + sourceStart,
                        change.startRevised + targetStart,
                        change.endRevised + targetStart
                    ))
                }
            }
        }
        
        return changes
    }
    
    private fun createChange(
        type: DeltaType,
        startOrig: Int,
        endOrig: Int,
        startRev: Int,
        endRev: Int
    ): Change {
        // Create Change with constructor parameters
        return Change(type, startOrig, endOrig, startRev, endRev)
    }
    
    /**
     * Find anchor points - lines that appear exactly once in both sequences
     */
    private fun findAnchors(source: List<T>, target: List<T>): List<Anchor<T>> {
        // Count occurrences in source
        val sourceCount = mutableMapOf<T, Int>()
        val sourceIndices = mutableMapOf<T, Int>()
        source.forEachIndexed { index, line ->
            sourceCount[line] = sourceCount.getOrDefault(line, 0) + 1
            if (!sourceIndices.containsKey(line)) {
                sourceIndices[line] = index
            }
        }
        
        // Count occurrences in target
        val targetCount = mutableMapOf<T, Int>()
        val targetIndices = mutableMapOf<T, Int>()
        target.forEachIndexed { index, line ->
            targetCount[line] = targetCount.getOrDefault(line, 0) + 1
            if (!targetIndices.containsKey(line)) {
                targetIndices[line] = index
            }
        }
        
        // Find unique lines (anchors)
        val anchors = mutableListOf<Anchor<T>>()
        for ((line, count) in sourceCount) {
            if (count == 1 && targetCount[line] == 1) {
                anchors.add(Anchor(
                    sourceIndices[line]!!,
                    targetIndices[line]!!,
                    line
                ))
            }
        }
        
        // Sort anchors by source position
        anchors.sortBy { it.sourceIndex }
        
        // Filter out anchors that would create crossing matches
        return filterCrossingAnchors(anchors)
    }
    
    /**
     * Filter out anchors that would create crossing matches
     * We want a strictly increasing sequence of anchors
     */
    private fun filterCrossingAnchors(anchors: List<Anchor<T>>): List<Anchor<T>> {
        if (anchors.isEmpty()) return emptyList()
        
        // Use dynamic programming to find the longest increasing subsequence
        val n = anchors.size
        val dp = IntArray(n) { 1 }
        val parent = IntArray(n) { -1 }
        
        for (i in 1 until n) {
            for (j in 0 until i) {
                if (anchors[j].targetIndex < anchors[i].targetIndex && dp[j] + 1 > dp[i]) {
                    dp[i] = dp[j] + 1
                    parent[i] = j
                }
            }
        }
        
        // Find the maximum length
        var maxLength = 0
        var maxIndex = 0
        for (i in 0 until n) {
            if (dp[i] > maxLength) {
                maxLength = dp[i]
                maxIndex = i
            }
        }
        
        // Reconstruct the sequence
        val result = mutableListOf<Anchor<T>>()
        var current = maxIndex
        while (current != -1) {
            result.add(0, anchors[current])
            current = parent[current]
        }
        
        return result
    }
    
    /**
     * Represents an anchor point - a line that appears exactly once in both sequences
     */
    private data class Anchor<T>(
        val sourceIndex: Int,
        val targetIndex: Int,
        val line: T
    )
}
