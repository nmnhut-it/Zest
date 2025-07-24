# Block Rewrite Metrics Implementation

## Overview

This document describes the implementation of metrics tracking for the block rewrite feature in Zest. The implementation follows the same pattern as the existing inline completion metrics.

## Components Added

### 1. ZestBlockRewriteMetricsService
- **Location**: `src/main/kotlin/com/zps/zest/completion/metrics/ZestBlockRewriteMetricsService.kt`
- **Purpose**: Service responsible for tracking and sending block rewrite metrics
- **Key Features**:
  - Tracks active rewrite sessions
  - Batches and sends events asynchronously
  - Handles all metric event types (request, response, view, accept, reject, cancel)
  - Debug logging support

### 2. BlockRewriteMetricEvent
- **Location**: Same file as the service
- **Purpose**: Sealed class representing different types of block rewrite events
- **Event Types**:
  - `RewriteRequest`: When user triggers a block rewrite
  - `RewriteResponse`: When LLM returns rewritten content
  - `View`: When diff is displayed to user
  - `Accept`: When user accepts the rewrite (TAB)
  - `Reject`: When user rejects the rewrite (ESC)
  - `Cancel`: When rewrite is cancelled (timeout, error, etc.)

### 3. Integration with ZestMethodRewriteService
- **Modified File**: `src/main/kotlin/com/zps/zest/completion/ZestMethodRewriteService.kt`
- **Changes Made**:
  - Added metrics service dependency
  - Generate unique rewrite ID for each session
  - Track events at key points in the rewrite flow
  - Pass rewrite ID through the entire flow

## Metrics Flow

1. **Request Phase**:
   - User triggers block rewrite
   - `trackRewriteRequested()` called with method details
   - Captures: method name, language, file type, model, context info

2. **Response Phase**:
   - LLM returns rewritten code
   - `trackRewriteResponse()` called with response time
   - Captures: response time, success status, content

3. **View Phase**:
   - Diff is calculated and displayed
   - `trackRewriteViewed()` called with diff statistics
   - Captures: number of changes, confidence score

4. **Decision Phase**:
   - User either accepts (TAB) or rejects (ESC)
   - `trackRewriteAccepted()` or `trackRewriteRejected()` called
   - Captures: user action, time from view to decision

5. **Cancellation Cases**:
   - Timeout, error, or user cancellation
   - `trackRewriteCancelled()` called with reason
   - Captures: cancellation reason

## Event Data Structure

Events are sent to the metrics API with the following structure:

```json
{
  "event_type": "request|response|view|tab|esc|anykey",
  "completion_id": "rewrite_<uuid>",
  "timestamp": 1234567890000,
  "elapsed_ms": 1500,
  "completion_text": "...", // For response and accept events
  "metadata": {
    "method_name": "calculateTotal",
    "language": "javascript",
    "file_type": "js",
    "confidence": 0.85,
    // ... other contextual data
  }
}
```

## API Endpoint

- Uses the same endpoint pattern as inline completion: `https://zest.zingplay.com/block_rewrite/{event_type}`
- Authentication via Bearer token
- Fire-and-forget pattern (non-blocking)

## Testing

A test file has been created at `test/block_rewrite_metrics_test.js` that simulates various rewrite scenarios:
- Successful rewrite flow (request → response → view → accept)
- Rejected rewrite flow (request → response → view → reject)
- Timeout flow (request → cancel)

## Future Enhancements

1. **Timing Breakdowns**: Track detailed timing for each phase (context collection, LLM call, parsing, rendering)
2. **Diff Analytics**: More detailed diff statistics (lines added/removed, complexity changes)
3. **User Behavior**: Track partial acceptances, multiple rewrites of same method
4. **Performance Metrics**: Track memory usage, CPU impact
5. **A/B Testing Support**: Track different rewrite strategies or models

## Configuration

The metrics service respects the same configuration as other LLM services:
- Enabled only when LLM service is configured
- Respects internal vs external API endpoints
- Uses project-level service lifecycle

## Debugging

Enable debug logging:
```kotlin
val metricsService = ZestBlockRewriteMetricsService.getInstance(project)
metricsService.setDebugLogging(true)
```

This will print detailed event information to the console for troubleshooting.