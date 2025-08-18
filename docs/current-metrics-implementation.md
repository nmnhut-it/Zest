# Zest Metrics Implementation - Current State

## Overview
This document provides a detailed specification of the current metrics implementation in Zest, including exact payload structures, event triggers, and API endpoints.

## API Endpoints and Routing

### Endpoint Selection Logic
```kotlin
val baseUrl = if (originalUrl.contains("talk.zingplay")) {
    "https://zest-internal.zingplay.com"  // Internal users
} else {
    "https://zest.zingplay.com"           // External users
}
```

### Endpoints by Feature
1. **Inline Completion**: `{baseUrl}/autocomplete/{event_type}`
2. **Quick Action**: `{baseUrl}/quick_action/{event_type}`
3. **Code Health**: `{baseUrl}/code_health/{event_type}`
4. **Legacy**: LiteLLM endpoint for other usage types

## 1. Inline Completion Metrics

### Service: `ZestInlineCompletionMetricsService`

### Event: `request`
**Trigger**: When user types and triggers completion
```json
{
  "event_type": "request",
  "completion_id": "uuid-v4",
  "timestamp": 1234567890000,
  "elapsed_ms": 0,
  "metadata": {
    "strategy": "cursor_position|function_signature|...",
    "file_type": "java|kotlin|js|...",
    // Additional context info passed from caller
  }
}
```

### Event: `response`
**Trigger**: When LLM returns completion
```json
{
  "event_type": "response",
  "completion_id": "uuid-v4",
  "timestamp": 1234567890000,
  "elapsed_ms": 245,
  "completion_text": "actual generated code",
  "metadata": {
    "response_time": 230,
    "strategy": "cursor_position",
    "file_type": "java"
  }
}
```

### Event: `view`
**Trigger**: When completion is shown to user
```json
{
  "event_type": "view",
  "completion_id": "uuid-v4",
  "timestamp": 1234567890000,
  "elapsed_ms": 350,
  "metadata": {
    "completion_length": 156,
    "completion_line_count": 5,
    "confidence": 0.85
  }
}
```

### Event: `tab` (Accept)
**Trigger**: User presses TAB to accept
```json
{
  "event_type": "tab",
  "completion_id": "uuid-v4",
  "timestamp": 1234567890000,
  "elapsed_ms": 2150,
  "completion_text": "accepted code content",
  "metadata": {
    "accept_type": "full|partial",
    "user_action": "tab",
    "strategy": "cursor_position",
    "file_type": "java",
    "is_partial": false,
    "partial_accept_count": 0,
    "total_accepted_length": 156,
    "view_to_accept_time": 1800
  }
}
```

### Event: `esc` (Decline)
**Trigger**: User presses ESC to reject
```json
{
  "event_type": "esc",
  "completion_id": "uuid-v4",
  "timestamp": 1234567890000,
  "elapsed_ms": 1500,
  "metadata": {
    "reason": "esc_pressed"
  }
}
```

### Event: `anykey` (Dismiss)
**Trigger**: User continues typing, ignoring suggestion
```json
{
  "event_type": "anykey",
  "completion_id": "uuid-v4",
  "timestamp": 1234567890000,
  "elapsed_ms": 800,
  "metadata": {
    "reason": "user_typed"
  }
}
```

### Additional Tracking
- **Context Collection Time**: `trackContextCollectionTime()`
- **LLM Call Time**: `trackLLMCallTime()`
- **Response Parsing Time**: `trackResponseParsingTime()`
- **Inlay Rendering Time**: `trackInlayRenderingTime()`
- **Cancellation Tracking**: Counts cancellations since last completion

## 2. Quick Action Metrics (Method Rewrite)

### Service: `ZestQuickActionMetricsService`

### Event: `request`
**Trigger**: User initiates method rewrite
```json
{
  "event_type": "request",
  "completion_id": "rewrite-uuid",
  "timestamp": 1234567890000,
  "elapsed_ms": 0,
  "metadata": {
    "method_name": "calculateTotal",
    "language": "java",
    "file_type": "java",
    "has_custom_instruction": true,
    // Additional context info
  }
}
```

### Event: `response`
**Trigger**: LLM returns rewritten code
```json
{
  "event_type": "response",
  "completion_id": "rewrite-uuid",
  "timestamp": 1234567890000,
  "elapsed_ms": 1250,
  "completion_text": "rewritten method code",
  "metadata": {
    "response_time": 1200,
    "method_name": "calculateTotal",
    "language": "java",
    "content_length": 512
  }
}
```

### Event: `view`
**Trigger**: Diff is displayed to user
```json
{
  "event_type": "view",
  "completion_id": "rewrite-uuid",
  "timestamp": 1234567890000,
  "elapsed_ms": 1500,
  "metadata": {
    "diff_changes": 15,
    "confidence": 0.92,
    "method_name": "calculateTotal",
    "language": "java"
  }
}
```

### Event: `tab` (Accept)
**Trigger**: User accepts rewrite
```json
{
  "event_type": "tab",
  "completion_id": "rewrite-uuid",
  "timestamp": 1234567890000,
  "elapsed_ms": 5000,
  "completion_text": "accepted rewritten code",
  "metadata": {
    "method_name": "calculateTotal",
    "language": "java",
    "view_to_accept_time": 3500,
    "content_length": 512,
    "user_action": "tab"
  }
}
```

### Event: `esc` (Reject)
**Trigger**: User rejects rewrite
```json
{
  "event_type": "esc",
  "completion_id": "rewrite-uuid",
  "timestamp": 1234567890000,
  "elapsed_ms": 3000,
  "metadata": {
    "reason": "esc_pressed",
    "method_name": "calculateTotal"
  }
}
```

### Event: `anykey` (Cancel)
**Trigger**: Rewrite is cancelled
```json
{
  "event_type": "anykey",
  "completion_id": "rewrite-uuid",
  "timestamp": 1234567890000,
  "elapsed_ms": 2000,
  "metadata": {
    "reason": "cancelled",
    "method_name": "calculateTotal",
    "was_viewed": false
  }
}
```

## 3. Code Health Metrics

### Payload Structure
```json
{
  "event_type": "analysis_complete|review_triggered|...",
  "timestamp": 1234567890000,
  "analysis_data": {
    "files_analyzed": 5,
    "issues_found": 12,
    "trigger_type": "git_commit|manual",
    "file_types": ["java", "kotlin"],
    // Additional analysis results
  }
}
```

## 4. Legacy Format (Other Usage Types)

For enum usage types not mapped to specific endpoints:
```json
{
  "model": "actual-model-name",
  "stream": false,
  "custom_tool": "Zest|ENUM_USAGE_TYPE|event_type",
  "completion_id": "uuid",
  "messages": [{"role": "user", "content": "dummy"}],
  "elapsed": 1234,
  "completion_content": "optional content",
  "metadata": {
    // Custom metadata
  }
}
```

## Common Properties

### All Events Include:
- `timestamp`: Unix timestamp in milliseconds
- `completion_id`: Unique identifier for the session
- `event_type`: The specific event (request, response, view, tab, esc, anykey)

### HTTP Headers:
```
Content-Type: application/json
Accept: application/json
Authorization: Bearer {authToken}  // If configured
```

### Request Configuration:
- Connection timeout: 5000ms
- Read timeout: 5000ms
- Method: POST
- Fire-and-forget pattern (no retry logic)

## Async Processing Flow

1. **Event Generation**: User action triggers metric event
2. **Queue**: Event sent to Kotlin Channel (non-blocking)
3. **Processing**: Background coroutine processes events
4. **API Call**: HTTP POST to appropriate endpoint
5. **Error Handling**: Failures logged but don't affect user experience

## Session Tracking

### CompletionSession Data:
```kotlin
data class CompletionSession(
    val completionId: String,
    val startTime: Long,
    val strategy: String,           // For inline completion
    val fileType: String,
    val actualModel: String,
    val contextInfo: Map<String, Any>,
    var viewedAt: Long?,
    var completionLength: Int?,
    var confidence: Float?,
    var hasViewed: Boolean,
    var partialAcceptances: Int?,
    var totalAcceptedLength: Int?,
    var timingInfo: CompletionTimingInfo?
)
```

### RewriteSession Data:
```kotlin
data class RewriteSession(
    val rewriteId: String,
    val startTime: Long,
    val methodName: String,
    val language: String,
    val fileType: String,
    val actualModel: String,
    val customInstruction: String?,
    val contextInfo: Map<String, Any>,
    var viewedAt: Long?
)
```

## Timing Breakdown Tracking

For inline completions, detailed timing is tracked:
- Context collection time
- LLM API call time
- Response parsing time
- Inlay rendering time
- Total time
- Cancellation status

## Privacy & Security

1. **No PII**: No personally identifiable information is collected
2. **Anonymous IDs**: Only UUID-based completion IDs
3. **Code Snippets**: Only generated/accepted code is sent
4. **Auth Token**: Optional, used if configured
5. **HTTPS Only**: All metrics sent over encrypted connection

## Debug Mode

Enable debug logging by setting:
```kotlin
debugLoggingEnabled = true
```

This logs:
- Event queuing
- Processing details
- API responses
- Timing breakdowns