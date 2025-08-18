package com.zps.zest.scoring

import kotlin.math.sqrt

/**
 * Semantic similarity scoring using various techniques.
 * Refactored from existing similarity calculations for reusability.
 */
class SemanticScorer(
    private val ngramSize: Int = 2,
    private val useJaccard: Boolean = true
) : ScoringStrategy {
    
    override fun calculateScore(query: String, content: String, metadata: Map<String, Any>): Double {
        if (query.isEmpty() || content.isEmpty()) return 0.0
        
        // Combine multiple similarity metrics
        val ngramSimilarity = calculateNgramSimilarity(query, content)
        val tokenSimilarity = calculateTokenSimilarity(query, content)
        val lengthSimilarity = calculateLengthSimilarity(query, content)
        
        // Apply metadata boosting if available
        val metadataBoost = calculateMetadataBoost(query, metadata)
        
        // Weighted combination
        val baseSimilarity = (ngramSimilarity * 0.5) + (tokenSimilarity * 0.3) + (lengthSimilarity * 0.2)
        
        // Apply boost and ensure within 0-1 range
        return (baseSimilarity + metadataBoost).coerceIn(0.0, 1.0)
    }
    
    override fun getName(): String = "Semantic"
    
    /**
     * Calculate n-gram based similarity (Jaccard or Dice coefficient)
     */
    private fun calculateNgramSimilarity(s1: String, s2: String): Double {
        val ngrams1 = extractNgrams(s1.lowercase(), ngramSize)
        val ngrams2 = extractNgrams(s2.lowercase(), ngramSize)
        
        if (ngrams1.isEmpty() || ngrams2.isEmpty()) return 0.0
        
        val intersection = ngrams1.intersect(ngrams2).size
        
        return if (useJaccard) {
            // Jaccard similarity: |A ∩ B| / |A ∪ B|
            val union = (ngrams1 + ngrams2).distinct().size
            if (union > 0) intersection.toDouble() / union else 0.0
        } else {
            // Dice coefficient: 2 * |A ∩ B| / (|A| + |B|)
            val total = ngrams1.size + ngrams2.size
            if (total > 0) (2.0 * intersection) / total else 0.0
        }
    }
    
    /**
     * Calculate token-level similarity
     */
    private fun calculateTokenSimilarity(s1: String, s2: String): Double {
        val tokens1 = extractTokens(s1)
        val tokens2 = extractTokens(s2)
        
        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0
        
        // Calculate cosine similarity between token vectors
        val commonTokens = tokens1.keys.intersect(tokens2.keys)
        
        var dotProduct = 0.0
        var norm1 = 0.0
        var norm2 = 0.0
        
        tokens1.forEach { (token, count) ->
            norm1 += count * count
            if (token in commonTokens) {
                dotProduct += count * (tokens2[token] ?: 0)
            }
        }
        
        tokens2.forEach { (_, count) ->
            norm2 += count * count
        }
        
        val denominator = sqrt(norm1) * sqrt(norm2)
        return if (denominator > 0) dotProduct / denominator else 0.0
    }
    
    /**
     * Calculate length-based similarity (penalize very different lengths)
     */
    private fun calculateLengthSimilarity(s1: String, s2: String): Double {
        val len1 = s1.length.toDouble()
        val len2 = s2.length.toDouble()
        
        if (len1 == 0.0 || len2 == 0.0) return 0.0
        
        val ratio = minOf(len1, len2) / maxOf(len1, len2)
        return ratio * ratio // Square to penalize large differences
    }
    
    /**
     * Calculate metadata-based boosting
     */
    private fun calculateMetadataBoost(query: String, metadata: Map<String, Any>): Double {
        var boost = 0.0
        
        // Boost if file path contains query terms
        val filePath = metadata["filePath"] as? String
        if (filePath != null) {
            val queryTokens = extractTokens(query).keys
            val pathTokens = filePath.lowercase().split("/", "\\", ".", "_", "-").toSet()
            val matches = queryTokens.intersect(pathTokens).size
            boost += matches * 0.05 // Small boost per match
        }
        
        // Boost based on node type relevance
        val nodeType = metadata["nodeType"] as? String
        if (nodeType != null && query.lowercase().contains(nodeType.lowercase())) {
            boost += 0.1
        }
        
        // Boost for recent modifications
        val lastModified = metadata["lastModified"] as? Long
        if (lastModified != null) {
            val hoursSinceModified = (System.currentTimeMillis() - lastModified) / (1000 * 60 * 60)
            if (hoursSinceModified < 24) {
                boost += 0.05 // Recent modification boost
            }
        }
        
        return boost
    }
    
    /**
     * Extract n-grams from text
     */
    private fun extractNgrams(text: String, n: Int): Set<String> {
        if (text.length < n) return setOf(text)
        
        return (0..text.length - n).map { i ->
            text.substring(i, i + n)
        }.toSet()
    }
    
    /**
     * Extract and count tokens from text
     */
    private fun extractTokens(text: String): Map<String, Int> {
        return text.lowercase()
            .split(Regex("\\W+"))
            .filter { it.isNotEmpty() && it.length > 1 }
            .groupingBy { it }
            .eachCount()
    }
    
    companion object {
        /**
         * Quick similarity check for filtering
         */
        fun quickSimilarity(s1: String, s2: String): Double {
            if (s1.isEmpty() || s2.isEmpty()) return 0.0
            
            val tokens1 = s1.lowercase().split(Regex("\\W+")).toSet()
            val tokens2 = s2.lowercase().split(Regex("\\W+")).toSet()
            
            if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0
            
            val intersection = tokens1.intersect(tokens2).size
            val union = tokens1.union(tokens2).size
            
            return if (union > 0) intersection.toDouble() / union else 0.0
        }
    }
}