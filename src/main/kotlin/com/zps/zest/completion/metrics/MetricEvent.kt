package com.zps.zest.completion.metrics

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
    open fun toApiRequest(): Map<String, Any> {
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
            is CompletionResponse -> baseRequest["completion_content"] = completionContent
            is Custom -> {
                // For custom events, use the custom tool string
                baseRequest["custom_tool"] = customTool
            }
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
    data class CompletionRequest(
        override val completionId: String,
        override val elapsed: Long = 0,
        override val metadata: Map<String, Any> = emptyMap()
    ) : MetricEvent() {
        override val eventType = "request"
    }
    
    /**
     * Request returned result (req trả về kết quả)
     */
    data class CompletionResponse(
        override val completionId: String,
        val completionContent: String,
        override val elapsed: Long,
        override val metadata: Map<String, Any> = emptyMap()
    ) : MetricEvent() {
        override val eventType = "response"
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
        override val eventType = "tab"
    }
    
    /**
     * User pressed ESC to reject (user nhấn esc để bỏ chọn gợi ý)
     */
    data class Decline(
        override val completionId: String,
        override val elapsed: Long,
        override val metadata: Map<String, Any> = emptyMap()
    ) : MetricEvent() {
        override val eventType = "esc"
    }
    
    /**
     * User continued typing (user gõ tiếp - bỏ qua gợi ý)
     */
    data class Dismiss(
        override val completionId: String,
        override val elapsed: Long,
        override val metadata: Map<String, Any> = emptyMap()
    ) : MetricEvent() {
        override val eventType = "anykey"
    }
    
    /**
     * Custom event type for other metrics (e.g. Code Health)
     */
    data class Custom(
        override val completionId: String,
        val customTool: String,
        override val elapsed: Long,
        override val metadata: Map<String, Any> = emptyMap()
    ) : MetricEvent() {
        override val eventType = customTool.substringAfterLast("|")
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
    var totalAcceptedLength: Int? = null,
    var timingInfo: CompletionTimingInfo? = null
)
