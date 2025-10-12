package com.zps.zest.completion.metrics

/**
 * Sealed class representing different types of metric events
 * BREAKING CHANGE: metadata is now strongly typed
 */
sealed class MetricEvent {
    abstract val completionId: String
    abstract val elapsed: Long
    abstract val eventType: String
    abstract val actualModel: String
    
    // Inline Completion Events
    data class InlineCompletionRequest(
        override val completionId: String,
        override val actualModel: String,
        override val elapsed: Long,
        val metadata: InlineRequestMetadata
    ) : MetricEvent() {
        override val eventType = "request"
    }
    
    data class InlineCompletionResponse(
        override val completionId: String,
        val completionContent: String,
        override val actualModel: String,
        override val elapsed: Long,
        val metadata: InlineResponseMetadata
    ) : MetricEvent() {
        override val eventType = "response"
    }
    
    data class InlineView(
        override val completionId: String,
        override val actualModel: String,
        override val elapsed: Long,
        val metadata: InlineViewMetadata
    ) : MetricEvent() {
        override val eventType = "view"
    }
    
    data class InlineSelect(
        override val completionId: String,
        val completionContent: String,
        override val actualModel: String,
        override val elapsed: Long,
        val metadata: InlineAcceptMetadata
    ) : MetricEvent() {
        override val eventType = "tab"
    }
    
    data class InlineDecline(
        override val completionId: String,
        override val actualModel: String,
        override val elapsed: Long,
        val metadata: InlineRejectMetadata
    ) : MetricEvent() {
        override val eventType = "esc"
    }
    
    data class InlineDismiss(
        override val completionId: String,
        override val actualModel: String,
        override val elapsed: Long,
        val metadata: InlineDismissMetadata
    ) : MetricEvent() {
        override val eventType = "anykey"
    }
    
    // Quick Action Events
    data class QuickActionRequest(
        override val completionId: String,
        override val actualModel: String,
        override val elapsed: Long,
        val metadata: QuickActionRequestMetadata
    ) : MetricEvent() {
        override val eventType = "request"
    }
    
    data class QuickActionResponse(
        override val completionId: String,
        val completionContent: String,
        override val actualModel: String,
        override val elapsed: Long,
        val metadata: QuickActionResponseMetadata
    ) : MetricEvent() {
        override val eventType = "response"
    }
    
    data class QuickActionView(
        override val completionId: String,
        override val actualModel: String,
        override val elapsed: Long,
        val metadata: QuickActionViewMetadata
    ) : MetricEvent() {
        override val eventType = "view"
    }
    
    data class QuickActionSelect(
        override val completionId: String,
        val completionContent: String,
        override val actualModel: String,
        override val elapsed: Long,
        val metadata: QuickActionAcceptMetadata
    ) : MetricEvent() {
        override val eventType = "tab"
    }
    
    data class QuickActionDecline(
        override val completionId: String,
        override val actualModel: String,
        override val elapsed: Long,
        val metadata: QuickActionRejectMetadata
    ) : MetricEvent() {
        override val eventType = "esc"
    }
    
    data class QuickActionDismiss(
        override val completionId: String,
        override val actualModel: String,
        override val elapsed: Long,
        val metadata: QuickActionDismissMetadata
    ) : MetricEvent() {
        override val eventType = "anykey"
    }
    
    // Other Events
    data class CodeHealthEvent(
        override val completionId: String,
        override val actualModel: String,
        override val elapsed: Long,
        val metadata: CodeHealthMetadata
    ) : MetricEvent() {
        override val eventType = metadata.eventType
    }

    // Dual Evaluation Event
    data class DualEvaluationEvent(
        override val completionId: String,
        override val actualModel: String,
        override val elapsed: Long,
        val metadata: DualEvaluationMetadata
    ) : MetricEvent() {
        override val eventType = "dual_evaluation"
    }

    // Code Quality Event
    data class CodeQualityEvent(
        override val completionId: String,
        override val actualModel: String,
        override val elapsed: Long,
        val metadata: CodeQualityMetadata
    ) : MetricEvent() {
        override val eventType = "code_quality"
    }

    // Unit Test Event
    data class UnitTestEvent(
        override val completionId: String,
        override val actualModel: String,
        override val elapsed: Long,
        val metadata: UnitTestMetadata
    ) : MetricEvent() {
        override val eventType = "unit_test"
    }

    // Feature Usage Event
    data class FeatureUsageEvent(
        override val completionId: String,
        override val actualModel: String,
        override val elapsed: Long,
        val metadata: FeatureUsageMetadata
    ) : MetricEvent() {
        override val eventType = "feature_usage"
    }

}

/**
 * Context information for a completion request
 */
data class CompletionContextInfo(
    val cursorOffset: Int = 0,
    val lineNumber: Int = 0,
    val fileName: String = "",
    val language: String = ""
)

/**
 * Represents an active completion session for tracking
 */
data class CompletionSession(
    val completionId: String,
    val startTime: Long,
    val strategy: String,
    val fileType: String,
    val actualModel: String,
    val contextInfo: CompletionContextInfo = CompletionContextInfo(),
    var viewedAt: Long? = null,
    var completionLength: Int? = null,
    var confidence: Float? = null,
    var hasViewed: Boolean = false,
    var partialAcceptances: Int = 0,
    var totalAcceptedLength: Int = 0,
    var timingInfo: CompletionTimingInfo? = null
)
