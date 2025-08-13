package com.zps.zest.scoring

/**
 * Strategy interface for calculating relevance scores.
 * Follows the Strategy pattern to allow flexible scoring implementations.
 */
interface ScoringStrategy {
    /**
     * Calculate relevance score between query and content.
     * 
     * @param query The search query or context
     * @param content The content to score
     * @param metadata Additional metadata for scoring (file path, type, etc.)
     * @return Score between 0.0 and 1.0, where 1.0 is perfect match
     */
    fun calculateScore(query: String, content: String, metadata: Map<String, Any> = emptyMap()): Double
    
    /**
     * Get the name of this scoring strategy for logging/debugging
     */
    fun getName(): String
}

/**
 * Base interface for items that can be scored
 */
interface Scoreable {
    fun getContent(): String
    fun getMetadata(): Map<String, Any>
    fun getIdentifier(): String
}

/**
 * Result of scoring with multiple strategies
 */
data class ScoredItem<T : Scoreable>(
    val item: T,
    val totalScore: Double,
    val scoreBreakdown: Map<String, Double> = emptyMap()
) : Comparable<ScoredItem<T>> {
    override fun compareTo(other: ScoredItem<T>): Int {
        return other.totalScore.compareTo(totalScore) // Descending order
    }
}