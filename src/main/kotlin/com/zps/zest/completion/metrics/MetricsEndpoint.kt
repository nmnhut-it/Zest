package com.zps.zest.completion.metrics

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Enum representing different metrics endpoints with their own serialization strategies
 */
enum class MetricsEndpoint(val path: String) {
    AUTOCOMPLETE("autocomplete"),
    CODE_HEALTH("code_health"),
    QUICK_ACTION("quick_action"),
    LEGACY("");
    
    /**
     * Build request body based on endpoint type and metadata
     */
    fun buildRequestBody(
        metadata: MetricMetadata, 
        eventType: String, 
        completionId: String, 
        elapsed: Long, 
        completionContent: String?
    ): JsonObject {
        return when (this) {
            LEGACY -> buildLegacyFormat(metadata, eventType, completionId, elapsed, completionContent)
            else -> {
                // Use the metadata's own serialization method
                metadata.toJsonObject().apply {
                    // Add endpoint-specific fields
                    addProperty("completion_id", completionId)
                    addProperty("elapsed_ms", elapsed)
                    completionContent?.let { addProperty("completion_text", it) }
                    // Ensure timestamp is at top level
                    addProperty("timestamp", System.currentTimeMillis())
                }
            }
        }
    }
    
    /**
     * Build legacy format for backward compatibility
     */
    private fun buildLegacyFormat(
        metadata: MetricMetadata,
        eventType: String,
        completionId: String,
        elapsed: Long,
        completionContent: String?
    ): JsonObject {
        val dummyMsg = JsonParser.parseString(
            """
            [
                {
                    "role": "user",
                    "content": "dummy"
                }
            ]
            """.trimIndent()
        )
        
        return JsonObject().apply {
            addProperty("model", metadata.model)
            addProperty("stream", false)
            addProperty("custom_tool", "Zest|LEGACY|$eventType")
            addProperty("completion_id", completionId)
            add("messages", dummyMsg)
            addProperty("elapsed", elapsed)
            completionContent?.let { addProperty("completion_content", it) }
            add("metadata", metadata.toJsonObject())
        }
    }
    
    companion object {
        /**
         * Determine endpoint from usage string
         */
        fun fromUsage(enumUsage: String): MetricsEndpoint {
            return when (enumUsage) {
                "INLINE_COMPLETION_LOGGING" -> AUTOCOMPLETE
                "CODE_HEALTH_LOGGING" -> CODE_HEALTH
                "BLOCK_REWRITE_LOGGING", "QUICK_ACTION_LOGGING" -> QUICK_ACTION
                else -> LEGACY
            }
        }
    }
}