package com.zps.zest.completion.metrics

import com.jetbrains.rd.generator.nova.PredefinedType

/**
 * Sealed class representing different types of metric events for inline completion
 */
sealed class MetricEvent {
    abstract val completionId: String
    abstract val elapsed: Long
    abstract val eventType: String
    abstract val metadata: Map<String, Any>
    
    /**
     * Convert the event to an API request format
     */
    fun toApiRequest(): Map<String, Any> {
        val baseRequest = mutableMapOf<String, Any>(
            "model" to "local-model-mini",
            "stream" to false,
            "custom_tool" to "Zest|INLINE_COMPLETION_LOGGING|$eventType",
            "completion_id" to completionId,
            "elapsed" to elapsed
        )
        
        // Add event-specific fields
        when (this) {
            is Select -> baseRequest["completion_content"] = completionContent
            else -> { /* No additional fields for other events */ }
        }
        
        // Add metadata if present
        if (metadata.isNotEmpty()) {
            baseRequest["metadata"] = metadata
        }
        
        return baseRequest
    }
    
    /**
     * Completion request initiated (bắt đầu gửi req)
     */
    data class Complete(
        override val completionId: String,
        override val elapsed: Long = 0,
        override val metadata: Map<String, Any> = emptyMap()
    ) : MetricEvent() {
        override val eventType = "complete"
    }
    
    /**
     * Request returned result (req trả về kết quả)
     */
    data class Completed(
        override val completionId: String,
        override val elapsed: Long,
        override val metadata: Map<String, Any> = emptyMap()
    ) : MetricEvent() {
        override val eventType = "completed"
    }
    
    /**
     * Completion displayed to user (hiện ra cho user)
     */
    data class View(
        override val completionId: String,
        override val elapsed: Long,
        override val metadata: Map<String, Any> = emptyMap()
    ) : MetricEvent() {
        override val eventType = "view"
    }
    
    /**
     * User pressed TAB to accept (user nhấn tab để chọn gợi ý)
     */
    data class Select(
        override val completionId: String,
        val completionContent: String,
        override val elapsed: Long,
        override val metadata: Map<String, Any> = emptyMap()
    ) : MetricEvent() {
        override val eventType = "select"
    }
    
    /**
     * User pressed ESC to reject (user nhấn esc để bỏ chọn gợi ý)
     */
    data class Decline(
        override val completionId: String,
        override val elapsed: Long,
        override val metadata: Map<String, Any> = emptyMap()
    ) : MetricEvent() {
        override val eventType = "decline"
    }
    
    /**
     * User continued typing (user gõ tiếp - bỏ qua gợi ý)
     */
    data class Dismiss(
        override val completionId: String,
        override val elapsed: Long,
        override val metadata: Map<String, Any> = emptyMap()
    ) : MetricEvent() {
        override val eventType = "dismiss"
    }
}

/**
 * Represents an active completion session for tracking
 */
data class CompletionSession(
    val completionId: String,
    val startTime: Long,
    val strategy: String,
    val fileType: String,
    val contextInfo: Map<String, Any> = emptyMap(),
    var viewedAt: Long? = null,
    var completionLength: Int? = null,
    var confidence: Float? = null,
    var hasViewed: Boolean,
    var partialAcceptances: Int? = null,
    var totalAcceptedLength: Int? = null
)
