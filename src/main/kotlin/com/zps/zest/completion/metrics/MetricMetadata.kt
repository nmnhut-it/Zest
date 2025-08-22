package com.zps.zest.completion.metrics

import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * Base class for all metric metadata
 * No optional fields - everything must be provided
 */
sealed class MetricMetadata {
//    abstract val token: String
    abstract val model: String
    abstract val projectId: String
    abstract val userId: String
    abstract val user: String
    abstract val ideVersion: String
    abstract val pluginVersion: String
    abstract val timestamp: Long
    
    // Each metadata type knows how to serialize itself
    abstract fun toJsonObject(): JsonObject
    
    // Common fields that all types share
    protected fun addCommonFields(json: JsonObject) {
        json.addProperty("user", user)
        json.addProperty("userId", userId)
        json.addProperty("projectId", projectId)
        json.addProperty("model", model)
        json.addProperty("ideVersion", ideVersion)
        json.addProperty("pluginVersion", pluginVersion)
        json.addProperty("timestamp", timestamp)
    }
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
//    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val user: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val fileType: String,
    val strategy: CompletionStrategy
) : MetricMetadata() {
    override fun toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("event_type", "request")
            addCommonFields(this)
            addProperty("fileType", fileType)
            addProperty("strategy", strategy.name)
        }
    }
}

data class InlineResponseMetadata(
//    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val user: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val fileType: String,
    val strategy: CompletionStrategy,
    val responseTimeMs: Long
) : MetricMetadata() {
    override fun toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("event_type", "response")
            addCommonFields(this)
            addProperty("fileType", fileType)
            addProperty("strategy", strategy.name)
            addProperty("responseTimeMs", responseTimeMs)
        }
    }
}

data class InlineViewMetadata(
//    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val user: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val completionLength: Int,
    val completionLineCount: Int,
    val confidence: Float
) : MetricMetadata() {
    override fun toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("event_type", "view")
            addCommonFields(this)
            addProperty("completionLength", completionLength)
            addProperty("completionLineCount", completionLineCount)
            addProperty("confidence", confidence)
        }
    }
}

data class InlineAcceptMetadata(
//    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val user: String,
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
) : MetricMetadata() {
    override fun toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("event_type", "accept")
            addCommonFields(this)
            addProperty("acceptType", acceptType.name)
            addProperty("userAction", userAction.name)
            addProperty("strategy", strategy.name)
            addProperty("fileType", fileType)
            addProperty("isPartial", isPartial)
            addProperty("partialAcceptCount", partialAcceptCount)
            addProperty("totalAcceptedLength", totalAcceptedLength)
            addProperty("viewToAcceptTimeMs", viewToAcceptTimeMs)
        }
    }
}

data class InlineRejectMetadata(
//    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val user: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val reason: RejectReason
) : MetricMetadata() {
    override fun toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("event_type", "reject")
            addCommonFields(this)
            addProperty("reason", reason.name)
        }
    }
}

data class InlineDismissMetadata(
//    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val user: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val reason: String,
    val partialAcceptCount: Int,
    val totalAcceptedLength: Int
) : MetricMetadata() {
    override fun toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("event_type", "dismiss")
            addCommonFields(this)
            addProperty("reason", reason)
            addProperty("partialAcceptCount", partialAcceptCount)
            addProperty("totalAcceptedLength", totalAcceptedLength)
        }
    }
}

// Quick Action Metadata Classes
data class QuickActionRequestMetadata(
//    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val user: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val methodName: String,
    val language: String,
    val fileType: String,
    val hasCustomInstruction: Boolean
) : MetricMetadata() {
    override fun toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("event_type", "request")
            addCommonFields(this)
            addProperty("methodName", methodName)
            addProperty("language", language)
            addProperty("fileType", fileType)
            addProperty("hasCustomInstruction", hasCustomInstruction)
        }
    }
}

data class QuickActionResponseMetadata(
//    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val user: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val methodName: String,
    val language: String,
    val responseTimeMs: Long,
    val contentLength: Int
) : MetricMetadata() {
    override fun toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("event_type", "response")
            addCommonFields(this)
            addProperty("methodName", methodName)
            addProperty("language", language)
            addProperty("responseTimeMs", responseTimeMs)
            addProperty("contentLength", contentLength)
        }
    }
}

data class QuickActionViewMetadata(
//    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val user: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val methodName: String,
    val language: String,
    val diffChanges: Int,
    val confidence: Float
) : MetricMetadata() {
    override fun toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("event_type", "view")
            addCommonFields(this)
            addProperty("methodName", methodName)
            addProperty("language", language)
            addProperty("diffChanges", diffChanges)
            addProperty("confidence", confidence)
        }
    }
}

data class QuickActionAcceptMetadata(
//    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val user: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val methodName: String,
    val language: String,
    val viewToAcceptTimeMs: Long,
    val contentLength: Int,
    val userAction: UserAction
) : MetricMetadata() {
    override fun toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("event_type", "accept")
            addCommonFields(this)
            addProperty("methodName", methodName)
            addProperty("language", language)
            addProperty("viewToAcceptTimeMs", viewToAcceptTimeMs)
            addProperty("contentLength", contentLength)
            addProperty("userAction", userAction.name)
        }
    }
}

data class QuickActionRejectMetadata(
//    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val user: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val methodName: String,
    val reason: RejectReason
) : MetricMetadata() {
    override fun toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("event_type", "reject")
            addCommonFields(this)
            addProperty("methodName", methodName)
            addProperty("reason", reason.name)
        }
    }
}

data class QuickActionDismissMetadata(
//    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val user: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val methodName: String,
    val reason: String,
    val wasViewed: Boolean
) : MetricMetadata() {
    override fun toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("event_type", "dismiss")
            addCommonFields(this)
            addProperty("methodName", methodName)
            addProperty("reason", reason)
            addProperty("wasViewed", wasViewed)
        }
    }
}

// Code Health Metadata
data class CodeHealthMetadata(
//    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val user: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val eventType: String,
    val analysisData: Map<String, Any>  // Keep this flexible for now
) : MetricMetadata() {
    override fun toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("event_type", eventType)
            addCommonFields(this)
            add("analysis_data", Gson().toJsonTree(analysisData))
        }
    }
}

