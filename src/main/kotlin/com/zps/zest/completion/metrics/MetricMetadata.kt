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

// Feature usage tracking
enum class FeatureType {
    TEST_GENERATION,
    TEST_GENERATION_HELP,
    GIT_COMMIT_AND_PUSH,
    CODE_REVIEW_CHAT,
    REVIEW_CURRENT_FILE,
    DAILY_HEALTH_REPORT,
    OPEN_CHAT,
    OPEN_CHAT_EDITOR,
    TOOL_ENABLED_CHAT,
    CODE_HEALTH_OVERVIEW,
    REVIEW_QUEUE_STATUS,
    CREATE_RULES_FILE,
    CHECK_UPDATES,
    COMPLETION_TIMING_DEBUG,
    TEST_RIPGREP_TOOL,
    TEST_METRICS_SYSTEM,
    VIEW_METRICS_SESSION,
    TOGGLE_DEV_TOOLS,
    TEST_CODE_CONTEXT
}

enum class TriggerMethod {
    KEYBOARD_SHORTCUT,
    MENU_CLICK,
    TOOLBAR_CLICK,
    EDITOR_POPUP,
    PROGRAMMATIC
}

// Dual Evaluation - Model comparison result
data class ModelComparisonResult(
    val modelName: String,
    val responseTimeMs: Long,
    val tokenCount: Int,
    val qualityScore: Float?  // Optional AI-judged quality
)

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

// Dual Evaluation Metadata
data class DualEvaluationMetadata(
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val user: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val originalPrompt: String,
    val models: List<String>,  // Models being compared
    val results: List<ModelComparisonResult>
) : MetricMetadata() {
    override fun toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("event_type", "dual_evaluation")
            addCommonFields(this)
            addProperty("original_prompt", originalPrompt)
            add("models", Gson().toJsonTree(models))
            add("results", Gson().toJsonTree(results))
        }
    }
}

// Code Quality Metadata
data class CodeQualityMetadata(
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val user: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val completionId: String,
    val linesOfCode: Int,
    // AI Self-Review
    val styleComplianceScore: Int,  // 0-100
    val selfReviewPassed: Boolean,
    // Error Metrics
    val compilationErrors: Int,
    val compilationErrorsPer1000Lines: Float,
    val logicBugsDetected: Int,
    val logicBugsPer1000Lines: Float,
    // Pre-user improvements
    val wasReviewed: Boolean,
    val wasImproved: Boolean
) : MetricMetadata() {
    override fun toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("event_type", "code_quality")
            addCommonFields(this)
            addProperty("completion_id", completionId)
            addProperty("lines_of_code", linesOfCode)
            addProperty("style_compliance_score", styleComplianceScore)
            addProperty("self_review_passed", selfReviewPassed)
            addProperty("compilation_errors", compilationErrors)
            addProperty("compilation_errors_per_1000_lines", compilationErrorsPer1000Lines)
            addProperty("logic_bugs_detected", logicBugsDetected)
            addProperty("logic_bugs_per_1000_lines", logicBugsPer1000Lines)
            addProperty("was_reviewed", wasReviewed)
            addProperty("was_improved", wasImproved)
        }
    }
}

// Unit Test Metadata
data class UnitTestMetadata(
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val user: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val testId: String,
    val totalTests: Int,
    val wordCount: Int,
    val generationTimeMs: Long,
    // Work out of box metrics
    val testsCompiled: Int,
    val testsPassedImmediately: Int,
    val workOutOfBoxPercentage: Float,
    // Time saved calculation
    val avgWordsPerMinute: Float,
    val timeSavedMinutes: Float
) : MetricMetadata() {
    override fun toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("event_type", "unit_test")
            addCommonFields(this)
            addProperty("test_id", testId)
            addProperty("total_tests", totalTests)
            addProperty("word_count", wordCount)
            addProperty("generation_time_ms", generationTimeMs)
            addProperty("tests_compiled", testsCompiled)
            addProperty("tests_passed_immediately", testsPassedImmediately)
            addProperty("work_out_of_box_percentage", workOutOfBoxPercentage)
            addProperty("avg_words_per_minute", avgWordsPerMinute)
            addProperty("time_saved_minutes", timeSavedMinutes)
        }
    }
}

// Feature Usage Metadata
data class FeatureUsageMetadata(
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val user: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val featureType: FeatureType,
    val actionId: String,
    val triggeredBy: TriggerMethod,
    val contextInfo: Map<String, String> = emptyMap()
) : MetricMetadata() {
    override fun toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("event_type", "feature_usage")
            addCommonFields(this)
            addProperty("feature_type", featureType.name)
            addProperty("action_id", actionId)
            addProperty("triggered_by", triggeredBy.name)
            add("context", Gson().toJsonTree(contextInfo))
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
    val trigger: CodeHealthTrigger,
    val criticalIssues: Int,
    val highIssues: Int,
    val totalIssues: Int,
    val methodsAnalyzed: Int,
    val averageHealthScore: Int,
    val issuesByCategory: Map<String, Int>,
    val userAction: String?,
    val elapsedMs: Long
) : MetricMetadata() {
    override fun toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("event_type", eventType)
            addCommonFields(this)
            addProperty("trigger", trigger.name)
            addProperty("critical_issues", criticalIssues)
            addProperty("high_issues", highIssues)
            addProperty("total_issues", totalIssues)
            addProperty("methods_analyzed", methodsAnalyzed)
            addProperty("average_health_score", averageHealthScore)
            add("issues_by_category", Gson().toJsonTree(issuesByCategory))
            addProperty("user_action", userAction ?: "")
            addProperty("elapsed_ms", elapsedMs)
        }
    }
}

