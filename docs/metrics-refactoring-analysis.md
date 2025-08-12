# Metrics Refactoring Analysis - From Map to Data Classes

## Current State Analysis

### Problems with Current Implementation
1. **Type Safety**: `metadata: Map<String, Any>` loses all type safety
2. **Missing Fields**: Easy to forget required fields
3. **Inconsistency**: Different events use different field names
4. **No Validation**: No compile-time guarantee of data completeness
5. **Hard to Track**: Difficult to find all usages and ensure consistency

### Required Common Fields (from your requirements)
- **token**: Authentication token from ConfigurationManager
- **model**: The actual model being used for the feature

## Proposed Data Class Structure

### Base Classes

```kotlin
// Common fields for all metrics
sealed class MetricMetadata {
    abstract val token: String
    abstract val model: String
    abstract val projectId: String
    abstract val userId: String  // Anonymous hash
    abstract val ideVersion: String
    abstract val pluginVersion: String
    abstract val timestamp: Long
}

// Base for all completion-like metrics
abstract class CompletionMetadata : MetricMetadata() {
    abstract val fileType: String
    abstract val fileName: String
    abstract val fileSize: Int
    abstract val cursorPosition: Int
}
```

### Inline Completion Metadata Classes

```kotlin
data class InlineRequestMetadata(
    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    override val fileType: String,
    override val fileName: String,
    override val fileSize: Int,
    override val cursorPosition: Int,
    val strategy: CompletionStrategy,
    val triggerType: TriggerType,
    val contextWindowSize: Int,
    val prefixLength: Int,
    val suffixLength: Int
) : CompletionMetadata()

data class InlineResponseMetadata(
    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    override val fileType: String,
    override val fileName: String,
    override val fileSize: Int,
    override val cursorPosition: Int,
    val strategy: CompletionStrategy,
    val responseTimeMs: Long,
    val tokensGenerated: Int,
    val promptTokens: Int,
    val temperatureUsed: Double,
    val maxTokensRequested: Int
) : CompletionMetadata()

data class InlineViewMetadata(
    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    override val fileType: String,
    override val fileName: String,
    override val fileSize: Int,
    override val cursorPosition: Int,
    val completionLength: Int,
    val completionLineCount: Int,
    val confidence: Float,
    val renderTimeMs: Long,
    val syntaxValid: Boolean
) : CompletionMetadata()

data class InlineAcceptMetadata(
    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    override val fileType: String,
    override val fileName: String,
    override val fileSize: Int,
    override val cursorPosition: Int,
    val acceptType: AcceptType,
    val userAction: UserAction,
    val strategy: CompletionStrategy,
    val isPartial: Boolean,
    val partialAcceptCount: Int,
    val totalAcceptedLength: Int,
    val viewToAcceptTimeMs: Long,
    val editsSinceView: Int,
    val charactersSaved: Int
) : CompletionMetadata()

data class InlineRejectMetadata(
    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    override val fileType: String,
    override val fileName: String,
    override val fileSize: Int,
    override val cursorPosition: Int,
    val reason: RejectReason,
    val viewDurationMs: Long
) : CompletionMetadata()
```

### Quick Action Metadata Classes

```kotlin
data class QuickActionRequestMetadata(
    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val methodName: String,
    val methodSignature: String,
    val language: String,
    val fileType: String,
    val fileName: String,
    val methodLineCount: Int,
    val methodComplexity: Int,
    val hasCustomInstruction: Boolean,
    val customInstructionLength: Int,
    val triggerSource: String
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
    val rewrittenLength: Int,
    val tokensUsed: Int,
    val success: Boolean,
    val errorType: String?
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
    val linesAdded: Int,
    val linesRemoved: Int,
    val confidence: Float,
    val diffRenderTimeMs: Long
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
    val acceptedLength: Int,
    val userAction: UserAction,
    val modificationsAfterAccept: Int
) : MetricMetadata()
```

### Code Health Metadata Classes

```kotlin
data class CodeHealthAnalysisMetadata(
    override val token: String,
    override val model: String,
    override val projectId: String,
    override val userId: String,
    override val ideVersion: String,
    override val pluginVersion: String,
    override val timestamp: Long,
    val triggerType: CodeHealthTrigger,
    val filesAnalyzed: Int,
    val totalLines: Int,
    val issuesFound: Int,
    val criticalIssues: Int,
    val warningIssues: Int,
    val infoIssues: Int,
    val analysisTimeMs: Long,
    val fileTypes: List<String>,
    val fromGitCommit: Boolean,
    val commitHash: String?
) : MetricMetadata()
```

### Enums for Type Safety

```kotlin
enum class CompletionStrategy {
    CURSOR_POSITION,
    FUNCTION_SIGNATURE,
    IMPORT_STATEMENT,
    COMMENT_COMPLETION,
    PATTERN_MATCHING
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
    SHORTCUT
}

enum class RejectReason {
    ESC_PRESSED,
    USER_TYPED,
    FOCUS_LOST,
    FILE_CHANGED,
    CURSOR_MOVED
}

enum class CodeHealthTrigger {
    GIT_COMMIT,
    MANUAL_REVIEW,
    SCHEDULED,
    FILE_SAVE
}
```

## Cascading Changes Required

### 1. MetricEvent.kt Changes
```kotlin
sealed class MetricEvent {
    abstract val completionId: String
    abstract val elapsed: Long
    abstract val eventType: String
    abstract val actualModel: String
    
    // Remove generic metadata, add specific types
    data class CompletionRequest(
        override val completionId: String,
        override val actualModel: String,
        override val elapsed: Long,
        val metadata: InlineRequestMetadata  // Specific type
    ) : MetricEvent()
    
    // ... similar for other event types
}
```

### 2. Service Layer Changes

#### Files to Update:
1. `ZestInlineCompletionMetricsService.kt`
2. `ZestQuickActionMetricsService.kt`
3. `LLMServiceMetricsExtension.kt`

#### Example Change Pattern:
```kotlin
// Before
sendEvent(MetricEvent.CompletionRequest(
    completionId = completionId,
    actualModel = actualModel,
    elapsed = 0,
    metadata = mapOf(
        "strategy" to strategy,
        "file_type" to fileType
    ) + contextInfo
))

// After
sendEvent(MetricEvent.CompletionRequest(
    completionId = completionId,
    actualModel = actualModel,
    elapsed = 0,
    metadata = InlineRequestMetadata(
        token = configManager.authToken,
        model = actualModel,
        projectId = project.name,
        userId = getUserHash(),
        ideVersion = ApplicationInfo.getInstance().fullVersion,
        pluginVersion = getPluginVersion(),
        timestamp = System.currentTimeMillis(),
        fileType = fileType,
        fileName = fileName,
        fileSize = fileSize,
        cursorPosition = cursorPosition,
        strategy = strategy,
        triggerType = triggerType,
        contextWindowSize = contextWindowSize,
        prefixLength = prefixLength,
        suffixLength = suffixLength
    )
))
```

### 3. API Layer Changes

`LLMServiceMetricsExtension.kt` needs to handle serialization:
```kotlin
// Convert typed metadata to JSON
val metadataJson = when (metadata) {
    is InlineRequestMetadata -> gson.toJson(metadata)
    is InlineResponseMetadata -> gson.toJson(metadata)
    // ... handle all types
}
```

### 4. New Dependencies

Add utility methods for common values:
```kotlin
object MetricsUtils {
    fun getUserHash(project: Project): String {
        // Generate anonymous user hash
    }
    
    fun getPluginVersion(): String {
        // Get from plugin.xml or build config
    }
    
    fun getProjectHash(project: Project): String {
        // Generate project identifier
    }
}
```

### 5. Configuration Manager Integration

```kotlin
// Add to ConfigurationManager
fun getMetricsToken(): String {
    return authToken ?: "anonymous"
}
```

## Migration Strategy

### Phase 1: Add New Structure (Week 1)
1. Create all data classes
2. Add enums
3. Create MetricsUtils
4. Don't remove old code yet

### Phase 2: Parallel Implementation (Week 2)
1. Update services to use new data classes
2. Keep old Map-based code as fallback
3. Add feature flag for new metrics

### Phase 3: Testing & Validation (Week 3)
1. Compare old vs new payloads
2. Ensure all fields populated
3. Validate with backend

### Phase 4: Cutover (Week 4)
1. Remove old Map-based code
2. Clean up unused imports
3. Update documentation

## Benefits

1. **Compile-time Safety**: Missing fields caught at compile time
2. **IDE Support**: Auto-completion for all fields
3. **Refactoring**: Safe rename/restructure
4. **Documentation**: Fields are self-documenting
5. **Validation**: Can add validation in constructors
6. **Consistency**: Same fields always present
7. **Type Safety**: No more casting from Any

## Potential Issues

1. **Payload Size**: More fields = larger payloads
2. **Backend Compatibility**: Need to coordinate changes
3. **Version Skew**: Old clients sending old format
4. **Performance**: More object creation

## Next Steps

1. Review and approve structure
2. Create feature branch
3. Implement data classes
4. Update one service as POC
5. Test with backend
6. Roll out to all services