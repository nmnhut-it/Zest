# Metrics Breaking Change Implementation Plan

## Strategy: Let the Compiler Find Everything

By making a breaking change to the core `MetricEvent` structure, the Kotlin compiler will identify every single usage point that needs updating. This ensures we don't miss anything.

## Step 1: Create New Metric Structure Files

### File: `src/main/kotlin/com/zps/zest/completion/metrics/MetricMetadata.kt`

```kotlin
package com.zps.zest.completion.metrics

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.project.Project
import com.zps.zest.ConfigurationManager
import java.security.MessageDigest

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
```

### File: `src/main/kotlin/com/zps/zest/completion/metrics/MetricsUtils.kt`

```kotlin
package com.zps.zest.completion.metrics

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.project.Project
import com.zps.zest.ConfigurationManager
import java.security.MessageDigest

object MetricsUtils {
    private var cachedPluginVersion: String? = null
    
    fun createBaseMetadata(project: Project, model: String): BaseMetadataBuilder {
        return BaseMetadataBuilder(project, model)
    }
    
    fun getUserHash(project: Project): String {
        // Use a combination of username and machine name
        val userName = System.getProperty("user.name", "unknown")
        val machineName = try {
            java.net.InetAddress.getLocalHost().hostName
        } catch (e: Exception) {
            "unknown"
        }
        return hashString("$userName-$machineName")
    }
    
    fun getProjectHash(project: Project): String {
        return hashString(project.basePath ?: project.name)
    }
    
    fun getPluginVersion(): String {
        if (cachedPluginVersion == null) {
            // TODO: Read from plugin.xml or build configuration
            cachedPluginVersion = "1.9.891"  // Fallback version
        }
        return cachedPluginVersion!!
    }
    
    fun getIdeVersion(): String {
        return ApplicationInfo.getInstance().fullVersion
    }
    
    fun getAuthToken(project: Project): String {
        return ConfigurationManager.getInstance(project).authToken ?: "anonymous"
    }
    
    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }
    
    // Builder for common fields
    class BaseMetadataBuilder(private val project: Project, private val model: String) {
        private val token = getAuthToken(project)
        private val projectId = getProjectHash(project)
        private val userId = getUserHash(project)
        private val ideVersion = getIdeVersion()
        private val pluginVersion = getPluginVersion()
        private val timestamp = System.currentTimeMillis()
        
        fun buildInlineRequest(fileType: String, strategy: CompletionStrategy): InlineRequestMetadata {
            return InlineRequestMetadata(
                token, model, projectId, userId, ideVersion, pluginVersion, timestamp,
                fileType, strategy
            )
        }
        
        fun buildInlineResponse(fileType: String, strategy: CompletionStrategy, responseTimeMs: Long): InlineResponseMetadata {
            return InlineResponseMetadata(
                token, model, projectId, userId, ideVersion, pluginVersion, timestamp,
                fileType, strategy, responseTimeMs
            )
        }
        
        // ... add builders for all metadata types
    }
}
```

## Step 2: Update MetricEvent.kt (BREAKING CHANGE)

```kotlin
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
    
    data class Custom(
        override val completionId: String,
        override val actualModel: String,
        override val elapsed: Long,
        val metadata: CustomEventMetadata
    ) : MetricEvent() {
        override val eventType = metadata.customTool.substringAfterLast("|")
    }
}
```

## Step 3: Compiler Will Force Updates

Once we make these changes, the compiler will immediately show errors at every location where metrics are created. This includes:

1. **ZestInlineCompletionMetricsService.kt** - All `sendEvent()` calls
2. **ZestQuickActionMetricsService.kt** - All `sendEvent()` calls  
3. **Any other location** creating metric events

### Example Fix Pattern:

```kotlin
// OLD CODE (will show compiler error)
sendEvent(MetricEvent.CompletionRequest(
    completionId = completionId,
    actualModel = actualModel,
    elapsed = 0,
    metadata = mapOf(
        "strategy" to strategy,
        "file_type" to fileType
    )
))

// NEW CODE (compiler will force this change)
val baseBuilder = MetricsUtils.createBaseMetadata(project, actualModel)
sendEvent(MetricEvent.InlineCompletionRequest(
    completionId = completionId,
    actualModel = actualModel,
    elapsed = 0,
    metadata = baseBuilder.buildInlineRequest(
        fileType = fileType,
        strategy = CompletionStrategy.valueOf(strategy.toUpperCase())
    )
))
```

## Step 4: Update Serialization

### File: `LLMServiceMetricsExtension.kt`

```kotlin
// Update the sendInlineCompletionMetrics function
suspend fun LLMService.sendInlineCompletionMetrics(
    event: MetricEvent,
    enumUsage: String
): Boolean = withContext(Dispatchers.IO) {
    try {
        val configStatus = getConfigStatus()
        if (!configStatus.isConfigured) {
            return@withContext false
        }
        
        val logName = when (enumUsage) {
            "INLINE_COMPLETION_LOGGING" -> "autocomplete"
            "CODE_HEALTH_LOGGING" -> "code_health"
            "QUICK_ACTION_LOGGING" -> "quick_action"
            else -> null
        }
        
        val apiUrl = // ... existing URL logic
        
        val requestBody = when (logName) {
            "autocomplete", "quick_action" -> {
                JsonObject().apply {
                    addProperty("event_type", event.eventType)
                    addProperty("completion_id", event.completionId)
                    addProperty("timestamp", System.currentTimeMillis())
                    addProperty("elapsed_ms", event.elapsed)
                    
                    // Add completion content if present
                    when (event) {
                        is MetricEvent.InlineCompletionResponse -> 
                            addProperty("completion_text", event.completionContent)
                        is MetricEvent.InlineSelect -> 
                            addProperty("completion_text", event.completionContent)
                        is MetricEvent.QuickActionResponse -> 
                            addProperty("completion_text", event.completionContent)
                        is MetricEvent.QuickActionSelect -> 
                            addProperty("completion_text", event.completionContent)
                        else -> {}
                    }
                    
                    // Serialize the typed metadata
                    val metadataJson = when (event) {
                        is MetricEvent.InlineCompletionRequest -> gson.toJsonTree(event.metadata)
                        is MetricEvent.InlineCompletionResponse -> gson.toJsonTree(event.metadata)
                        is MetricEvent.InlineView -> gson.toJsonTree(event.metadata)
                        is MetricEvent.InlineSelect -> gson.toJsonTree(event.metadata)
                        is MetricEvent.InlineDecline -> gson.toJsonTree(event.metadata)
                        is MetricEvent.InlineDismiss -> gson.toJsonTree(event.metadata)
                        is MetricEvent.QuickActionRequest -> gson.toJsonTree(event.metadata)
                        is MetricEvent.QuickActionResponse -> gson.toJsonTree(event.metadata)
                        is MetricEvent.QuickActionView -> gson.toJsonTree(event.metadata)
                        is MetricEvent.QuickActionSelect -> gson.toJsonTree(event.metadata)
                        is MetricEvent.QuickActionDecline -> gson.toJsonTree(event.metadata)
                        is MetricEvent.QuickActionDismiss -> gson.toJsonTree(event.metadata)
                        is MetricEvent.CodeHealthEvent -> gson.toJsonTree(event.metadata)
                        is MetricEvent.Custom -> gson.toJsonTree(event.metadata)
                    }
                    add("metadata", metadataJson)
                }
            }
            // ... rest of the cases
        }
        
        // ... rest of the sending logic
    }
}
```

## Step 5: Fix All Compiler Errors

The beauty of this approach is that we can't miss anything. The compiler will show errors at:

1. Every `MetricEvent.CompletionRequest` creation
2. Every `MetricEvent.CompletionResponse` creation
3. Every `MetricEvent.View` creation
4. Every `MetricEvent.Select` creation
5. Every `MetricEvent.Decline` creation
6. Every `MetricEvent.Dismiss` creation
7. Every `MetricEvent.Custom` creation

We just need to:
1. Go to each error
2. Replace with the appropriate typed event
3. Use `MetricsUtils` to create the metadata
4. Ensure all required fields are provided

## Benefits of Breaking Change

1. **Compiler Enforcement**: Can't accidentally miss any usage
2. **No Legacy Code**: No old Map-based code hanging around
3. **Clean Codebase**: Everything uses the new typed system
4. **Immediate Validation**: Know immediately if something is missing
5. **Type Safety**: Full IDE support and type checking

## Testing Strategy

1. **Compile**: First goal is to get everything compiling
2. **Unit Tests**: Update tests to use new structure
3. **Integration**: Test actual metric sending
4. **Payload Validation**: Ensure backend accepts new format
5. **End-to-End**: Test all metric flows

## Rollback Plan

If issues arise:
1. Git revert the commit
2. Fix issues in a branch
3. Re-apply with fixes

Since it's a breaking change, reverting is clean and simple.