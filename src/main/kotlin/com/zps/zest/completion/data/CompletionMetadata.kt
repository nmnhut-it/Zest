package com.zps.zest.completion.data

/**
 * Enhanced metadata associated with a completion item
 * Updated to support lean strategy with reasoning
 */
data class CompletionMetadata(
    val model: String,
    val tokens: Int,
    val latency: Long,
    val requestId: String,
    val confidence: Float? = null,
    val source: String? = null,
    val reasoning: String? = null,        // AI reasoning from lean strategy
    val modifiedFilesCount: Int = 0,      // Context richness indicator
    val contextType: String? = null,      // Detected cursor context type
    val hasValidReasoning: Boolean = false // Whether reasoning is meaningful
) {
    companion object {
        fun simple(requestId: String): CompletionMetadata {
            return CompletionMetadata(
                model = "zest-llm-simple",
                tokens = 0,
                latency = 0L,
                requestId = requestId
            )
        }
        
        fun withReasoning(
            requestId: String, 
            reasoning: String, 
            contextType: String,
            modifiedFilesCount: Int = 0
        ): CompletionMetadata {
            return CompletionMetadata(
                model = "zest-llm-lean",
                tokens = 0,
                latency = 0L,
                requestId = requestId,
                reasoning = reasoning,
                contextType = contextType,
                modifiedFilesCount = modifiedFilesCount,
                hasValidReasoning = reasoning.isNotBlank() && reasoning.length > 20
            )
        }
    }
}
