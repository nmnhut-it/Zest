# Inline Completion Metrics System

## Overview

The Zest inline completion metrics system tracks user interactions with code completions to improve the quality and relevance of suggestions. The system is designed to be privacy-conscious, collecting only event data and timing information without storing actual code content (except for accepted completions).

## Architecture

### Components

1. **ZestInlineCompletionMetricsService**
   - Main service responsible for tracking and sending metrics
   - Manages completion sessions and event queuing
   - Handles batching and asynchronous submission

2. **MetricEvent**
   - Sealed class hierarchy representing different event types
   - Events: Complete, View, Select, Dismiss, Decline, Completed

3. **LLMServiceMetricsExtension**
   - Extension functions for LLMService to send metrics
   - Minimal HTTP requests without expecting responses

4. **Integration Points**
   - Hooks into ZestInlineCompletionService at key lifecycle points
   - Tracks events throughout the completion workflow

## Tracked Events

### 1. Complete (`/complete`)
- **When**: Completion request initiated
- **Data**: 
  - Completion ID (UUID)
  - Strategy (SIMPLE, LEAN, METHOD_REWRITE)
  - File type
  - Context info (offset, file name, manually triggered)
  - Elapsed time: 0ms (start of session)

### 2. View (`/view`)
- **When**: Completion displayed to user
- **Data**:
  - Completion ID
  - Completion length (characters)
  - Confidence score
  - Elapsed time since request

### 3. Select (`/select`)
- **When**: User highlights/selects a completion
- **Data**:
  - Completion ID
  - Elapsed time since request

### 4. Dismiss (`/dismiss`)
- **When**: User explicitly dismisses completion
- **Data**:
  - Completion ID
  - Reason (user_dismissed, editor_changed, cursor_moved_backward, etc.)
  - Elapsed time since request

### 5. Decline (`/decline`)
- **When**: User types something different than the suggestion
- **Data**:
  - Completion ID
  - Reason (user_typed_different, user_typed_during_acceptance)
  - Elapsed time since request

### 6. Completed (`/completed`)
- **When**: User accepts the completion
- **Data**:
  - Completion ID
  - Completion content (actual accepted text)
  - Accept type (full, multi_line, multi_word, partial)
  - User action (tab, enter, click)
  - Strategy used
  - File type
  - View-to-accept time
  - Total elapsed time

## API Format

### Request Structure
```json
{
  "model": "local-model-mini",
  "stream": false,
  "custom_tool": "Zest|INLINE_COMPLETION_LOGGING|{event_type}",
  "completion_id": "550e8400-e29b-41d4-a716-446655440000",
  "elapsed": 1250,
  "completion_content": "optional - only for completed events",
  "metadata": {
    "strategy": "LEAN",
    "file_type": "kotlin",
    "additional_context": "..."
  }
}
```

## Session Management

- Each completion request generates a unique session ID (UUID)
- Sessions track timing and state throughout the completion lifecycle
- Sessions are cleaned up 60 seconds after final event
- Active sessions are maintained in memory

## Configuration

### Enable/Disable Metrics
- Metrics collection can be toggled via settings
- Default: Enabled
- No metrics are sent when disabled

### Privacy Considerations
- No code is sent except for accepted completions
- File names are included but can be anonymized
- All metrics are fire-and-forget (no response processing)
- Failed metrics don't impact completion functionality

## Implementation Details

### Event Flow
1. User triggers completion → `Complete` event
2. Completion displayed → `View` event
3. User interacts:
   - Accepts → `Completed` event
   - Dismisses → `Dismiss` event
   - Types different → `Decline` event
   - Moves cursor away → `Dismiss` event with reason

### Timing Information
- All events include elapsed time since completion request
- Additional timing: view-to-accept time for completed events
- Helps understand user interaction patterns

### Batching Strategy
- Events are queued in an unlimited channel
- Processed sequentially to maintain order
- Failed events are logged but don't retry
- Ensures minimal impact on IDE performance

## Testing

### Manual Testing
Use `ZestTestMetricsAction` to send test metrics:
- Simulates complete lifecycle
- Verifies service connectivity
- Shows current metrics state

### Debugging
- Check logs for metric events
- Monitor `getMetricsState()` for service status
- Verify network requests in IDE HTTP logs

## Future Enhancements

1. **Batch Processing**
   - Group multiple events into single requests
   - Reduce network overhead

2. **Local Storage**
   - Persist metrics during network outages
   - Retry failed submissions

3. **Enhanced Context**
   - Project size metrics
   - Time of day patterns
   - Session duration tracking

4. **A/B Testing Support**
   - Experiment tracking
   - Feature flag integration
   - Cohort assignment

5. **Real-time Dashboard**
   - Live metrics visualization
   - Completion success rates
   - Strategy performance comparison
