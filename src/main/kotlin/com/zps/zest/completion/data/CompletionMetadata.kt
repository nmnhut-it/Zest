package com.zps.zest.completion.data

/**
 * Metadata associated with a completion item
 */
data class CompletionMetadata(
    val model: String,
    val tokens: Int,
    val latency: Long,
    val requestId: String,
    val confidence: Float? = null,
    val source: String? = null
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
    }
}
