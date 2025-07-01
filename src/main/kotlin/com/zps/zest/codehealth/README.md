# Code Health Points System

## Overview

The Code Health Points system is a proactive code quality monitoring feature that tracks methods you modify throughout the day and analyzes them for potential issues. It provides daily health reports with actionable insights to help maintain code quality.

## Features

### 1. **Automatic Method Tracking**
- Monitors all methods you modify during your coding session
- Tracks modification frequency (how many times each method was changed)
- Stores up to 500 methods with automatic cleanup of old entries

### 2. **Intelligent Analysis**
- Uses LLM to analyze modified methods for:
  - Null pointer exceptions (NPE risks)
  - Logic errors
  - Performance issues
  - Security vulnerabilities
  - Resource leaks
  - Concurrency issues
  - Missing validation
  - API breaking changes
  - Test coverage gaps

### 3. **Impact Assessment**
- Identifies all callers of modified methods
- Shows ripple effects of changes across the codebase
- Prioritizes issues based on impact and severity

### 4. **Health Scoring**
- Each method gets a health score (0-100)
- Score factors:
  - Issue severity (critical issues = -30 points)
  - Modification frequency (more changes = higher risk)
  - Number of callers (wider impact = lower score)

### 5. **Daily Reports**
- Scheduled daily analysis (default: 1 PM)
- Balloon notification with summary
- Detailed HTML report with:
  - Issue descriptions
  - Affected methods and callers
  - Copy-to-clipboard fix prompts
  - Batch fix suggestions

## Usage

### Manual Check
1. Go to **Tools → Zest → Check Code Health**
2. Or right-click in editor → **Zest → Check Code Health**

### Configuration
1. Go to **Settings → Tools → Zest Plugin → Code Health**
2. Options:
   - Enable/disable daily checks
   - Set check time (24-hour format)
   - Configure max methods to track

### Notification Actions
When you receive a health check notification:
- Click **View Details** to see the full report
- Click on **Copy Fix Prompt** buttons to get LLM-ready prompts
- Use batch fix prompts for similar issues across methods

## Implementation Details

### Architecture
```
CodeHealthTracker (Service)
  ↓ Tracks modifications
CodeHealthAnalyzer (Service)
  ↓ Analyzes with LLM
CodeHealthNotification (UI)
  ↓ Shows results
Developer
```

### Rate Limiting
- Max 3 concurrent LLM analyses
- 1-second delay between LLM calls
- Prevents API overload

### Data Persistence
- Modified methods stored in `zest-code-health.xml`
- Survives IDE restarts
- Auto-cleanup after 24 hours

### Integration Points
- Uses existing Zest components:
  - `ZestDocumentListener` for change tracking
  - `ZestLeanContextCollector` for method context
  - `LLMService` for intelligent analysis
  - `StructuralIndex` for finding callers

## Example Report

```
Code Health Report
Summary: 5 methods analyzed
Total Issues: 12 (Critical: 3, Warning: 6, Minor: 3)
Average Health Score: 65/100

Methods Requiring Attention:

UserService.validateUser                    45/100
Modified 8 times today
- NPE Risk: Parameter 'user' not checked for null
  [Copy Fix Prompt]
- Missing Validation: Email format not validated
  [Copy Fix Prompt]
Impact: Called by 15 methods

PaymentProcessor.processPayment             70/100
Modified 3 times today
- Resource Leak: Connection may not be closed
  [Copy Fix Prompt]
Impact: Called by 5 methods
```

## Benefits

1. **Proactive Quality Control**: Catch issues before they reach production
2. **Context-Aware**: Analyzes based on your actual changes
3. **Actionable Insights**: Provides specific fix suggestions
4. **Low Overhead**: Runs in background, minimal performance impact
5. **Team Collaboration**: Export reports for code reviews

## Future Enhancements

1. **Git Integration**: Check health before push
2. **Team Sharing**: Share health reports
3. **Custom Rules**: Add project-specific checks
4. **Metrics Dashboard**: Track health trends over time
5. **IDE Markers**: Show health scores in project view
