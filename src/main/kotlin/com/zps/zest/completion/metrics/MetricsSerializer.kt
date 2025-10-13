package com.zps.zest.completion.metrics

import com.google.gson.JsonObject

/**
 * Serializes MetricEvent to JSON format.
 * Single responsibility: Convert metric events to JSON.
 * No HTTP knowledge, no business logic.
 */
object MetricsSerializer {

    /**
     * Serialize a MetricEvent to JSON with proper nesting.
     * Server expects: top-level fields (completion_id, event_type, elapsed_ms)
     * + nested "metadata" object with all metric data.
     */
    fun serialize(event: MetricEvent): JsonObject {
        val metadata = extractMetadata(event)
        val metadataJson = metadata.toJsonObject()

        // Create wrapper JSON with proper structure
        val json = JsonObject()

        // Top-level envelope fields
        json.addProperty("completion_id", event.completionId)
        json.addProperty("event_type", event.eventType)
        json.addProperty("elapsed_ms", event.elapsed)
        json.addProperty("timestamp", System.currentTimeMillis())

        // Add completion content at top level if available (server may need it there)
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

        // Nest all metric data under "metadata" field
        json.add("metadata", metadataJson)

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
            is MetricEvent.FeatureUsageEvent -> event.metadata
        }
    }
}
