package com.zps.zest.completion.metrics

/**
 * Base class for all metric metadata
 * No optional fields - everything must be provided
 */
sealed class MetricMetadata {
    abstract val token: String
    abstract val model: String
    abstract val projectId: String
    abstract val userId: String
    abstract val ideVersion: String
    abstract val pluginVersion: String
    abstract val timestamp: Long
}

// Enums for type safety
enum class CompletionStrategy {
    CURSOR_POSITION,
    FUNCTION_SIGNATURE,
    IMPORT_STATEMENT,
    COMMENT_COMPLETION,
    PATTERN_MATCHING,
    UNKNOWN
}

enum class TriggerType {
    AUTOMATIC,
    MANUAL,
    DEBOUNCED,
    SHORTCUT
}

enum class AcceptType {
    FULL,
    PARTIAL,
    WORD,
    LINE
}

enum class UserAction {
    TAB,
    ENTER,
    CLICK,
    SHORTCUT,
    UNKNOWN
}

enum class RejectReason {
    ESC_PRESSED,
    USER_TYPED,
    FOCUS_LOST,
    FILE_CHANGED,
    CURSOR_MOVED,
    TIMEOUT
}

enum class CodeHealthTrigger {
    GIT_COMMIT,
    MANUAL_REVIEW,
    SCHEDULED,
    FILE_SAVE,
    POST_COMMIT_AUTO
}

// Inline Completion Metadata Classes
data class InlineRequestMetadata(
    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val fileType: String,
    val strategy: CompletionStrategy
) : MetricMetadata()

data class InlineResponseMetadata(
    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val fileType: String,
    val strategy: CompletionStrategy,
    val responseTimeMs: Long
) : MetricMetadata()

data class InlineViewMetadata(
    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val completionLength: Int,
    val completionLineCount: Int,
    val confidence: Float
) : MetricMetadata()

data class InlineAcceptMetadata(
    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val acceptType: AcceptType,
    val userAction: UserAction,
    val strategy: CompletionStrategy,
    val fileType: String,
    val isPartial: Boolean,
    val partialAcceptCount: Int,
    val totalAcceptedLength: Int,
    val viewToAcceptTimeMs: Long
) : MetricMetadata()

data class InlineRejectMetadata(
    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val reason: RejectReason
) : MetricMetadata()

data class InlineDismissMetadata(
    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val reason: String,
    val partialAcceptCount: Int,
    val totalAcceptedLength: Int
) : MetricMetadata()

// Quick Action Metadata Classes
data class QuickActionRequestMetadata(
    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val methodName: String,
    val language: String,
    val fileType: String,
    val hasCustomInstruction: Boolean
) : MetricMetadata()

data class QuickActionResponseMetadata(
    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val methodName: String,
    val language: String,
    val responseTimeMs: Long,
    val contentLength: Int
) : MetricMetadata()

data class QuickActionViewMetadata(
    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val methodName: String,
    val language: String,
    val diffChanges: Int,
    val confidence: Float
) : MetricMetadata()

data class QuickActionAcceptMetadata(
    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val methodName: String,
    val language: String,
    val viewToAcceptTimeMs: Long,
    val contentLength: Int,
    val userAction: UserAction
) : MetricMetadata()

data class QuickActionRejectMetadata(
    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val methodName: String,
    val reason: RejectReason
) : MetricMetadata()

data class QuickActionDismissMetadata(
    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val methodName: String,
    val reason: String,
    val wasViewed: Boolean
) : MetricMetadata()

// Code Health Metadata
data class CodeHealthMetadata(
    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val eventType: String,
    val analysisData: Map<String, Any>  // Keep this flexible for now
) : MetricMetadata()

// Custom Event Metadata (for backwards compatibility)
data class CustomEventMetadata(
    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val customTool: String,
    val additionalData: Map<String, Any>
) : MetricMetadata()