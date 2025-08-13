package com.zps.zest.scoring

/**
 * Hybrid scoring that combines multiple scoring strategies with adaptive weights.
 * Implements the Composite pattern for flexible scoring combinations.
 */
class HybridScorer(
    private val strategies: Map<ScoringStrategy, Double>,
    private val queryIntentDetector: QueryIntentDetector = QueryIntentDetector()
) : ScoringStrategy {
    
    init {
        require(strategies.isNotEmpty()) { "At least one scoring strategy required" }
        val totalWeight = strategies.values.sum()
        require(totalWeight > 0) { "Total weight must be positive" }
    }
    
    override fun calculateScore(query: String, content: String, metadata: Map<String, Any>): Double {
        val intent = queryIntentDetector.detectIntent(query)
        val adjustedWeights = adjustWeightsForIntent(intent)
        
        var totalScore = 0.0
        var totalWeight = 0.0
        
        strategies.forEach { (strategy, baseWeight) ->
            val adjustedWeight = adjustedWeights[strategy] ?: baseWeight
            val score = strategy.calculateScore(query, content, metadata)
            totalScore += score * adjustedWeight
            totalWeight += adjustedWeight
        }
        
        return if (totalWeight > 0) totalScore / totalWeight else 0.0
    }
    
    override fun getName(): String = "Hybrid"
    
    /**
     * Calculate scores with breakdown for debugging
     */
    fun calculateWithBreakdown(query: String, content: String, metadata: Map<String, Any>): ScoredItem<SimpleScoreable> {
        val intent = queryIntentDetector.detectIntent(query)
        val adjustedWeights = adjustWeightsForIntent(intent)
        
        val breakdown = mutableMapOf<String, Double>()
        var totalScore = 0.0
        var totalWeight = 0.0
        
        strategies.forEach { (strategy, baseWeight) ->
            val adjustedWeight = adjustedWeights[strategy] ?: baseWeight
            val score = strategy.calculateScore(query, content, metadata)
            breakdown[strategy.getName()] = score
            totalScore += score * adjustedWeight
            totalWeight += adjustedWeight
        }
        
        val finalScore = if (totalWeight > 0) totalScore / totalWeight else 0.0
        breakdown["intent"] = intent.ordinal.toDouble() // For debugging
        
        return ScoredItem(
            item = SimpleScoreable(content, metadata, content.hashCode().toString()),
            totalScore = finalScore,
            scoreBreakdown = breakdown
        )
    }
    
    /**
     * Adjust weights based on query intent
     */
    private fun adjustWeightsForIntent(intent: QueryIntent): Map<ScoringStrategy, Double> {
        val adjusted = mutableMapOf<ScoringStrategy, Double>()
        
        strategies.forEach { (strategy, baseWeight) ->
            val multiplier = when (intent) {
                QueryIntent.EXACT_MATCH -> {
                    when (strategy.getName()) {
                        "BM25" -> 1.5      // Boost lexical matching
                        "Semantic" -> 0.7   // Reduce semantic
                        else -> 1.0
                    }
                }
                QueryIntent.CONCEPTUAL -> {
                    when (strategy.getName()) {
                        "BM25" -> 0.7       // Reduce lexical
                        "Semantic" -> 1.5   // Boost semantic
                        else -> 1.0
                    }
                }
                QueryIntent.MIXED -> 1.0    // Use base weights
            }
            adjusted[strategy] = baseWeight * multiplier
        }
        
        return adjusted
    }
    
    /**
     * Builder for creating hybrid scorers with fluent API
     */
    class Builder {
        private val strategies = mutableMapOf<ScoringStrategy, Double>()
        private var intentDetector: QueryIntentDetector? = null
        
        fun addStrategy(strategy: ScoringStrategy, weight: Double): Builder {
            strategies[strategy] = weight
            return this
        }
        
        fun withBM25(weight: Double = 0.3, k1: Double = 1.5, b: Double = 0.75): Builder {
            strategies[BM25Scorer(k1, b)] = weight
            return this
        }
        
        fun withSemantic(weight: Double = 0.7, ngramSize: Int = 2): Builder {
            strategies[SemanticScorer(ngramSize)] = weight
            return this
        }
        
        fun withIntentDetector(detector: QueryIntentDetector): Builder {
            this.intentDetector = detector
            return this
        }
        
        fun build(): HybridScorer {
            return HybridScorer(
                strategies.toMap(),
                intentDetector ?: QueryIntentDetector()
            )
        }
    }
    
    companion object {
        /**
         * Create default hybrid scorer for code completion
         */
        fun forCodeCompletion(): HybridScorer {
            return Builder()
                .withBM25(0.3)
                .withSemantic(0.7)
                .build()
        }
        
        /**
         * Create hybrid scorer optimized for RAG
         */
        fun forRAG(): HybridScorer {
            return Builder()
                .withBM25(0.4)
                .withSemantic(0.6)
                .build()
        }
    }
}

/**
 * Simple implementation of Scoreable for testing/debugging
 */
data class SimpleScoreable(
    private val content: String,
    private val metadata: Map<String, Any>,
    private val id: String
) : Scoreable {
    override fun getContent() = content
    override fun getMetadata() = metadata
    override fun getIdentifier() = id
}