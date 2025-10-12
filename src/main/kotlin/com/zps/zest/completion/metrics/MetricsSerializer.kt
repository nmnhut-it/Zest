package com.zps.zest.completion.metrics

import com.google.gson.JsonObject

/**
 * Serializes MetricEvent to JSON format.
 * Single responsibility: Convert metric events to JSON.
 * No HTTP knowledge, no business logic.
 */
object MetricsSerializer {

    /**
     * Serialize a MetricEvent to JSON
     */
    fun serialize(event: MetricEvent): JsonObject {
        val metadata = extractMetadata(event)
        val json = metadata.toJsonObject()

        // Add common event fields
        json.addProperty("completion_id", event.completionId)
        json.addProperty("elapsed_ms", event.elapsed)
        json.addProperty("timestamp", System.currentTimeMillis())

        // Add completion content if available
        when (event) {
            is MetricEvent.InlineSelect -> {
                json.addProperty("completion_text", event.completionContent)
            }
            is MetricEvent.InlineCompletionResponse -> {
                json.addProperty("completion_text", event.completionContent)
            }
            is MetricEvent.QuickActionSelect -> {
                json.addProperty("completion_text", event.completionContent)
            }
            is MetricEvent.QuickActionResponse -> {
                json.addProperty("completion_text", event.completionContent)
            }
            else -> {
                // No completion content for other event types
            }
        }

        return json
    }

    /**
     * Extract metadata from event (type-safe)
     */
    private fun extractMetadata(event: MetricEvent): MetricMetadata {
        return when (event) {
            is MetricEvent.InlineCompletionRequest -> event.metadata
            is MetricEvent.InlineCompletionResponse -> event.metadata
            is MetricEvent.InlineView -> event.metadata
            is MetricEvent.InlineSelect -> event.metadata
            is MetricEvent.InlineDecline -> event.metadata
            is MetricEvent.InlineDismiss -> event.metadata
            is MetricEvent.QuickActionRequest -> event.metadata
            is MetricEvent.QuickActionResponse -> event.metadata
            is MetricEvent.QuickActionView -> event.metadata
            is MetricEvent.QuickActionSelect -> event.metadata
            is MetricEvent.QuickActionDecline -> event.metadata
            is MetricEvent.QuickActionDismiss -> event.metadata
            is MetricEvent.CodeHealthEvent -> event.metadata
            is MetricEvent.DualEvaluationEvent -> event.metadata
            is MetricEvent.CodeQualityEvent -> event.metadata
            is MetricEvent.UnitTestEvent -> event.metadata
        }
    }
}
