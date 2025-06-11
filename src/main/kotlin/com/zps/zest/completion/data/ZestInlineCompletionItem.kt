package com.zps.zest.completion.data

/**
 * Represents a single inline completion suggestion
 */
data class ZestInlineCompletionItem(
    val insertText: String,
    val replaceRange: Range,
    val confidence: Float = 1.0f,
    val metadata: CompletionMetadata? = null
) {
    data class Range(
        val start: Int,
        val end: Int
    )
}
