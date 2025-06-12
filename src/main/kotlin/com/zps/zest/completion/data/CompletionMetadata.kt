package com.zps.zest.completion.data

/**
 * Enhanced metadata associated with a completion item
 */
data class CompletionMetadata(
    val model: String,
    val tokens: Int,
    val latency: Long,
    val requestId: String,
    val confidence: Float? = null,
    val source: String? = null,
    val reasoning: String? = null, // NEW: Store the reasoning from LLM
    val modifiedFilesCount: Int = 0 // NEW: Context richness indicator
) {
    companion object {
        fun simple(requestId: String): CompletionMetadata {
            return CompletionMetadata(
                model = "zest-llm",
                tokens = 0,
                latency = 0L,
                requestId = requestId
            )
        }
        
        fun withReasoning(requestId: String, reasoning: String, modifiedFilesCount: Int = 0): CompletionMetadata {
            return CompletionMetadata(
                model = "zest-llm-reasoning",
                tokens = 0,
                latency = 0L,
                requestId = requestId,
                reasoning = reasoning,
                modifiedFilesCount = modifiedFilesCount
            )
        }
    }
}
