# Block Rewrite Metrics Documentation

This document describes the metrics tracking implementation for the block rewrite feature in Zest.

## Overview

Block rewrite metrics track user interactions and performance data when users trigger the block rewrite functionality. The metrics are sent to dedicated endpoints based on whether the user is on the internal or external network.

## Endpoints

- **Internal (talk.zingplay)**: `https://zest-internal.zingplay.com/block_rewrite/{event_type}`
- **External (chat.zingplay)**: `https://zest.zingplay.com/block_rewrite/{event_type}`

## Event Types

### 1. Request Event (`request`)
Fired when a block rewrite is initiated.

**Payload:**
```json
{
  "event_type": "request",
  "completion_id": "rewrite_uuid",
  "timestamp": 1706234567890,
  "elapsed_ms": 0,
  "metadata": {
    "method_name": "calculateTotal",
    "language": "java",
    "file_type": "java",
    "has_custom_instruction": true,
    "is_cocos2dx": false,
    "has_related_classes": true,
    "method_length": 450
  }
}
```

### 2. Response Event (`response`)
Fired when the LLM returns the rewritten code.

**Payload:**
```json
{
  "event_type": "response",
  "completion_id": "rewrite_uuid",
  "timestamp": 1706234568890,
  "elapsed_ms": 1000,
  "completion_text": "// Rewritten method content...",
  "metadata": {
    "response_time": 950,
    "method_name": "calculateTotal",
    "language": "java",
    "content_length": 520
  }
}
```

### 3. View Event (`view`)
Fired when the diff is displayed to the user.

**Payload:**
```json
{
  "event_type": "view",
  "completion_id": "rewrite_uuid",
  "timestamp": 1706234569890,
  "elapsed_ms": 2000,
  "metadata": {
    "diff_changes": 15,
    "confidence": 0.85,
    "method_name": "calculateTotal",
    "language": "java"
  }
}
```

### 4. Accept Event (`tab`)
Fired when the user accepts the rewrite by pressing TAB.

**Payload:**
```json
{
  "event_type": "tab",
  "completion_id": "rewrite_uuid",
  "timestamp": 1706234571890,
  "elapsed_ms": 4000,
  "completion_text": "// Accepted rewritten content...",
  "metadata": {
    "method_name": "calculateTotal",
    "language": "java",
    "view_to_accept_time": 2000,
    "content_length": 520,
    "user_action": "tab"
  }
}
```

### 5. Reject Event (`esc`)
Fired when the user rejects the rewrite by pressing ESC.

**Payload:**
```json
{
  "event_type": "esc",
  "completion_id": "rewrite_uuid",
  "timestamp": 1706234571890,
  "elapsed_ms": 4000,
  "metadata": {
    "reason": "esc_pressed",
    "method_name": "calculateTotal"
  }
}
```

### 6. Cancel Event (`anykey`)
Fired when the rewrite is cancelled due to timeout, error, or user action.

**Payload:**
```json
{
  "event_type": "anykey",
  "completion_id": "rewrite_uuid",
  "timestamp": 1706234571890,
  "elapsed_ms": 4000,
  "metadata": {
    "reason": "timeout",
    "method_name": "calculateTotal",
    "was_viewed": false
  }
}
```

## Implementation Details

### Services

1. **ZestBlockRewriteMetricsService**
   - Manages block rewrite sessions
   - Queues events for asynchronous processing
   - Sends metrics using the `BLOCK_REWRITE_LOGGING` enum

2. **ZestMethodRewriteService**
   - Integrates with the metrics service
   - Tracks events at key points in the rewrite workflow
   - Provides rewrite IDs for session tracking

3. **LLMServiceMetricsExtension**
   - Routes metrics to the appropriate endpoints
   - Handles internal/external URL detection
   - Formats payloads based on log type

### Key Features

- **Asynchronous Processing**: Metrics are queued and sent asynchronously to avoid blocking the UI
- **Session Tracking**: Each rewrite has a unique ID for tracking the complete user journey
- **Automatic Cleanup**: Sessions are cleaned up after 60 seconds to prevent memory leaks
- **Fire-and-Forget**: Metrics failures don't impact the rewrite functionality

## Usage Analysis

The metrics enable analysis of:
- Method rewrite usage patterns
- Performance characteristics (response times, processing delays)
- User acceptance rates (accept vs reject)
- Error patterns and timeout frequency
- Language-specific rewrite behavior
- Custom instruction usage

## Testing

To test the metrics:

1. Trigger a block rewrite in the IDE
2. Check the IDE logs for metric events (when debug logging is enabled)
3. Monitor the network tab for requests to the metrics endpoints
4. Verify all event types are fired in the correct sequence

## Future Enhancements

- Add more detailed diff statistics (lines added/removed/modified)
- Track partial acceptances for multi-part rewrites
- Include model-specific performance metrics
- Add user feedback tracking