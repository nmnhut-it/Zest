package com.zps.zest.scoring

/**
 * Query intent classification for adaptive scoring
 */
enum class QueryIntent {
    EXACT_MATCH,   // Looking for specific code/names
    CONCEPTUAL,    // Looking for similar concepts
    MIXED          // Default/uncertain
}

/**
 * Detects the intent behind a query to adjust scoring weights
 */
class QueryIntentDetector {
    
    private val exactMatchPatterns = listOf(
        Regex("\"[^\"]+\""),                    // Quoted strings
        Regex("\\b(exact|specific|precise)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(class|method|function)\\s+\\w+", RegexOption.IGNORE_CASE),
        Regex("\\w+\\(\\)"),                    // Function calls
        Regex("\\w+\\.\\w+")                    // Property access
    )
    
    private val conceptualPatterns = listOf(
        Regex("\\b(similar|like|related|same as)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(concept|pattern|approach|style)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(how to|what is|explain)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(implement|create|build|make)\\b", RegexOption.IGNORE_CASE)
    )
    
    fun detectIntent(query: String): QueryIntent {
        val hasExactMatch = exactMatchPatterns.any { it.containsMatchIn(query) }
        val hasConceptual = conceptualPatterns.any { it.containsMatchIn(query) }
        
        return when {
            hasExactMatch && !hasConceptual -> QueryIntent.EXACT_MATCH
            !hasExactMatch && hasConceptual -> QueryIntent.CONCEPTUAL
            else -> QueryIntent.MIXED
        }
    }
    
    /**
     * Extract key terms based on intent
     */
    fun extractKeyTerms(query: String, intent: QueryIntent): List<String> {
        val terms = mutableListOf<String>()
        
        // Extract quoted terms first (highest priority)
        val quotedPattern = Regex("\"([^\"]+)\"")
        quotedPattern.findAll(query).forEach { match ->
            terms.addAll(match.groupValues[1].split("\\s+"))
        }
        
        // Extract based on intent
        when (intent) {
            QueryIntent.EXACT_MATCH -> {
                // Extract identifiers and specific names
                val identifierPattern = Regex("\\b[A-Z][a-zA-Z0-9]*\\b") // PascalCase
                identifierPattern.findAll(query).forEach { match ->
                    terms.add(match.value)
                }
                
                val camelCasePattern = Regex("\\b[a-z][a-zA-Z0-9]*\\b") // camelCase
                camelCasePattern.findAll(query).forEach { match ->
                    if (match.value.length > 2) terms.add(match.value)
                }
            }
            
            QueryIntent.CONCEPTUAL -> {
                // Extract conceptual keywords
                val cleanQuery = query.replace(quotedPattern, "")
                terms.addAll(
                    cleanQuery.split(Regex("\\W+"))
                        .filter { it.length > 2 && !isStopWord(it) }
                )
            }
            
            QueryIntent.MIXED -> {
                // Extract all meaningful terms
                terms.addAll(
                    query.split(Regex("\\W+"))
                        .filter { it.length > 2 && !isStopWord(it) }
                )
            }
        }
        
        return terms.distinct()
    }
    
    private fun isStopWord(word: String): Boolean {
        val stopWords = setOf(
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "by", "from", "as", "is", "was", "are", "were", "been"
        )
        return word.lowercase() in stopWords
    }
}