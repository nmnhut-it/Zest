package com.zps.zest.completion.diff

import com.github.difflib.algorithm.Change
import com.github.difflib.algorithm.DiffAlgorithmI
import com.github.difflib.algorithm.DiffAlgorithmListener
import com.github.difflib.patch.DeltaType

/**
 * Simple diff algorithm implementation as a fallback
 */
class SimpleDiffAlgorithm<T> : DiffAlgorithmI<T> {
    
    override fun computeDiff(
        source: MutableList<T>?,
        target: MutableList<T>?,
        progress: DiffAlgorithmListener?
    ): MutableList<Change> {
        val changes = mutableListOf<Change>()
        
        if (source == null || target == null) {
            return changes
        }
        
        // Very basic LCS implementation
        val lcs = findLCS(source, target)
        
        var sourceIdx = 0
        var targetIdx = 0
        var lcsIdx = 0
        
        while (sourceIdx < source.size || targetIdx < target.size) {
            if (lcsIdx < lcs.size && sourceIdx < source.size && targetIdx < target.size &&
                source[sourceIdx] == lcs[lcsIdx] && target[targetIdx] == lcs[lcsIdx]) {
                // Common element
                sourceIdx++
                targetIdx++
                lcsIdx++
            } else if (sourceIdx < source.size && (lcsIdx >= lcs.size || source[sourceIdx] != lcs[lcsIdx])) {
                // Deletion
                val startDel = sourceIdx
                while (sourceIdx < source.size && (lcsIdx >= lcs.size || source[sourceIdx] != lcs[lcsIdx])) {
                    sourceIdx++
                }
                changes.add(createChange(DeltaType.DELETE, startDel, sourceIdx, targetIdx, targetIdx))
            } else if (targetIdx < target.size && (lcsIdx >= lcs.size || target[targetIdx] != lcs[lcsIdx])) {
                // Insertion
                val startIns = targetIdx
                while (targetIdx < target.size && (lcsIdx >= lcs.size || target[targetIdx] != lcs[lcsIdx])) {
                    targetIdx++
                }
                changes.add(createChange(DeltaType.INSERT, sourceIdx, sourceIdx, startIns, targetIdx))
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
     * Find longest common subsequence
     */
    private fun findLCS(source: List<T>, target: List<T>): List<T> {
        val m = source.size
        val n = target.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        
        // Fill DP table
        for (i in 1..m) {
            for (j in 1..n) {
                if (source[i - 1] == target[j - 1]) {
                    dp[i][j] = dp[i - 1][j - 1] + 1
                } else {
                    dp[i][j] = maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }
        
        // Reconstruct LCS
        val lcs = mutableListOf<T>()
        var i = m
        var j = n
        
        while (i > 0 && j > 0) {
            if (source[i - 1] == target[j - 1]) {
                lcs.add(0, source[i - 1])
                i--
                j--
            } else if (dp[i - 1][j] > dp[i][j - 1]) {
                i--
            } else {
                j--
            }
        }
        
        return lcs
    }
}
