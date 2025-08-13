package com.zps.zest.scoring

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln

/**
 * BM25 (Best Matching 25) scoring implementation.
 * Extracted and refactored from AdvancedRAGService for reusability.
 */
class BM25Scorer(
    private val k1: Double = 1.5,  // Term frequency saturation parameter
    private val b: Double = 0.75   // Length normalization parameter
) : ScoringStrategy {
    
    // Document statistics cache
    private val termFrequencyCache = ConcurrentHashMap<String, Map<String, Int>>()
    private val documentFrequencies = ConcurrentHashMap<String, Int>()
    private val documentLengths = ConcurrentHashMap<String, Int>()
    private var totalDocuments = 0
    private var avgDocLength = 0.0
    
    // Stop words for filtering
    private val stopWords = setOf(
        "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
        "of", "with", "by", "from", "as", "is", "was", "are", "were", "been",
        "be", "have", "has", "had", "do", "does", "did", "will", "would", "could",
        "should", "may", "might", "must", "can", "shall", "if", "then", "else",
        "this", "that", "these", "those", "it", "its", "their", "there"
    )
    
    override fun calculateScore(query: String, content: String, metadata: Map<String, Any>): Double {
        val queryTerms = extractTerms(query)
        if (queryTerms.isEmpty()) return 0.0
        
        val docId = metadata["id"] as? String ?: content.hashCode().toString()
        val docTermFreqs = getOrComputeTermFrequencies(docId, content)
        val docLength = documentLengths.getOrPut(docId) { content.split("\\s+".toRegex()).size }
        
        var score = 0.0
        
        for (term in queryTerms) {
            val termFreq = docTermFreqs[term] ?: 0
            if (termFreq == 0) continue
            
            val docFreq = documentFrequencies[term] ?: 1
            
            // IDF component: log((N - df + 0.5) / (df + 0.5))
            val idf = ln((totalDocuments - docFreq + 0.5) / (docFreq + 0.5))
            
            // TF component with BM25 normalization
            val tf = (termFreq * (k1 + 1)) / 
                    (termFreq + k1 * (1 - b + b * (docLength / avgDocLength)))
            
            score += idf * tf
        }
        
        // Normalize to 0-1 range using tanh
        return kotlin.math.tanh(score / 10.0)
    }
    
    override fun getName(): String = "BM25"
    
    /**
     * Index a document for BM25 scoring
     */
    fun indexDocument(docId: String, content: String) {
        val terms = extractTerms(content)
        val termFreqs = terms.groupingBy { it }.eachCount()
        
        termFrequencyCache[docId] = termFreqs
        documentLengths[docId] = content.split("\\s+".toRegex()).size
        
        // Update document frequencies
        termFreqs.keys.forEach { term ->
            documentFrequencies.merge(term, 1, Int::plus)
        }
        
        totalDocuments++
        updateAverageDocLength()
    }
    
    /**
     * Clear the BM25 index
     */
    fun clearIndex() {
        termFrequencyCache.clear()
        documentFrequencies.clear()
        documentLengths.clear()
        totalDocuments = 0
        avgDocLength = 0.0
    }
    
    private fun getOrComputeTermFrequencies(docId: String, content: String): Map<String, Int> {
        return termFrequencyCache.getOrPut(docId) {
            val terms = extractTerms(content)
            terms.groupingBy { it }.eachCount()
        }
    }
    
    private fun extractTerms(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .split("\\s+".toRegex())
            .filter { it.length > 2 && it !in stopWords }
    }
    
    private fun updateAverageDocLength() {
        if (totalDocuments > 0) {
            avgDocLength = documentLengths.values.sum().toDouble() / totalDocuments
        }
    }
    
    /**
     * Get statistics for debugging/monitoring
     */
    fun getStatistics(): Map<String, Any> {
        return mapOf(
            "totalDocuments" to totalDocuments,
            "avgDocLength" to avgDocLength,
            "uniqueTerms" to documentFrequencies.size,
            "indexedDocs" to termFrequencyCache.size
        )
    }
}