package com.zps.zest.completion.data

import java.util.UUID

/**
 * Represents a single inline completion suggestion
 */
data class ZestInlineCompletionItem(
    val insertText: String,
    val replaceRange: Range,
    val confidence: Float = 1.0f,
    val metadata: CompletionMetadata? = null,
    val completionId: String = UUID.randomUUID().toString()
) {
    data class Range(
        val start: Int,
        val end: Int
    )
}
