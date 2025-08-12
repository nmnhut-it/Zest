# Zest User Usage Tracking Plan

## Overview
This document outlines a comprehensive plan to track user usage across all Zest features to understand user behavior, improve features, and measure engagement.

## Current Metrics Infrastructure

### 1. Inline Completion Metrics
- **Service**: `ZestInlineCompletionMetricsService`
- **Events**: request, response, view, tab (accept), esc (reject), anykey (dismiss)
- **Endpoint**: `/autocomplete/{event_type}`
- **Key Metrics**:
  - Acceptance rate (tab/view ratio)
  - Response time
  - Partial vs full acceptance
  - File types and strategies used

### 2. Quick Action Metrics (formerly Block Rewrite)
- **Service**: `ZestQuickActionMetricsService`
- **Events**: request, response, view, tab (accept), esc (reject)
- **Endpoint**: `/quick_action/{event_type}`
- **Key Metrics**:
  - Method rewrite acceptance rate
  - Custom instruction usage
  - Language/file type distribution
  - Time to accept after viewing

### 3. Code Health Metrics
- **Service**: Through `LLMServiceMetricsExtension`
- **Endpoint**: `/code_health/{event_type}`
- **Key Metrics**:
  - Analysis frequency
  - Issues found per analysis
  - Git-triggered vs manual reviews

## Proposed Enhancements

### 1. Unified Metrics Service
Create a central `ZestUsageMetricsService` that:
- Consolidates all metric events
- Provides consistent event structure
- Handles batching and retry logic
- Offers real-time usage dashboard

### 2. Additional Tracking Points

#### A. Feature Usage
- **Git Integration**:
  - Commit message generation usage
  - Push/pull operations
  - Code review triggers
- **Chat Features**:
  - New chat creation
  - Model switching
  - System prompt usage
- **Tool Usage**:
  - File exploration
  - Code search
  - Context collection

#### B. User Journey Metrics
- Session duration
- Feature discovery paths
- Error/failure points
- Feature adoption funnel

#### C. Performance Metrics
- API latency by endpoint
- Cache hit rates
- Token usage optimization
- Concurrent request handling

### 3. Implementation Steps

#### Phase 1: Instrumentation (Week 1-2)
1. Add tracking to all user-facing actions
2. Implement session tracking
3. Add performance timing to all API calls
4. Create debug mode for metric validation

#### Phase 2: Collection (Week 3-4)
1. Implement batch processing for efficiency
2. Add local metric storage for offline usage
3. Create metric export functionality
4. Add privacy controls/opt-out

#### Phase 3: Analysis (Week 5-6)
1. Build usage dashboard in IDE
2. Create weekly usage reports
3. Implement anomaly detection
4. Add A/B testing framework

### 4. Technical Implementation

#### Event Structure
```kotlin
data class UsageEvent(
    val userId: String,         // Anonymous user ID
    val sessionId: String,      // Session identifier
    val timestamp: Long,        // Event time
    val eventType: String,      // Category of event
    val action: String,         // Specific action
    val feature: String,        // Feature area
    val metadata: Map<String, Any>, // Additional context
    val duration: Long?,        // Time taken (if applicable)
    val success: Boolean        // Operation success
)
```

#### Tracking Categories
1. **feature_usage** - Direct feature interactions
2. **api_call** - LLM API interactions
3. **performance** - Timing and resource usage
4. **error** - Failures and exceptions
5. **configuration** - Settings changes

### 5. Privacy Considerations
- No PII collection
- Opt-in for detailed metrics
- Local aggregation before sending
- Clear data retention policy
- GDPR compliance

### 6. Success Metrics
- Daily Active Users (DAU)
- Feature adoption rates
- Time to first value
- User retention rates
- Error reduction rates

### 7. Dashboard Mockup
```
Zest Usage Dashboard
├── Overview
│   ├── Active users (today/week/month)
│   ├── Top features by usage
│   └── Error rate trends
├── Features
│   ├── Inline Completion
│   │   ├── Acceptance rate: 68%
│   │   ├── Avg response time: 230ms
│   │   └── Top file types
│   ├── Quick Actions
│   │   ├── Usage frequency
│   │   └── Success rate
│   └── Code Health
│       ├── Reviews per day
│       └── Issues found
└── Performance
    ├── API latency (p50/p95/p99)
    ├── Cache hit rate
    └── Token usage efficiency
```

## Next Steps
1. Review and approve plan
2. Create implementation tickets
3. Set up metric infrastructure
4. Begin phased rollout
5. Monitor and iterate

## Timeline
- Week 1-2: Core infrastructure
- Week 3-4: Feature instrumentation
- Week 5-6: Dashboard and reporting
- Week 7-8: Testing and refinement
- Week 9-10: Full rollout

## Resources Needed
- 1 senior developer (full-time, 4 weeks)
- 1 data analyst (part-time, 2 weeks)
- Infrastructure for metric storage
- Dashboard development time