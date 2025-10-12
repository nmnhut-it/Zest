# Feature Usage Tracking - Integration Guide

## Overview

Feature usage tracking infrastructure is **100% complete and working**. This guide shows how to integrate it into the remaining actions.

---

## Current Status

### ✅ **Integrated (2 actions)**
1. ✅ `GenerateTestAction.java` - Test generation
2. ✅ `GitCommitMessageGeneratorAction.java` - Git commit & push

### ❌ **Not Integrated (20 actions)**

Need to add 3 lines of code to each action.

---

## Integration Pattern

### Step 1: Add Imports

```java
import com.zps.zest.completion.metrics.ActionMetricsHelper;
import com.zps.zest.completion.metrics.FeatureType;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
```

### Step 2: Add Tracking Call

In `actionPerformed()` method, add **after project null check**:

```java
@Override
public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) return;

    // ADD THIS
    ActionMetricsHelper.INSTANCE.trackAction(
        project,
        FeatureType.FEATURE_NAME,  // Choose from enum
        "Zest.ActionId",  // From plugin.xml
        e,
        Collections.emptyMap()  // Or add context
    );

    // ... rest of existing code ...
}
```

---

## Actions to Integrate

### **1. Code Review** - `SendCodeReviewToChatBox.java`

**File:** `src/main/java/com/zps/zest/browser/actions/SendCodeReviewToChatBox.java`

**Add:**
```java
Map<String, String> context = new HashMap<>();
if (psiFile != null) {
    context.put("file", psiFile.getName());
}
ActionMetricsHelper.INSTANCE.trackAction(
    project,
    FeatureType.CODE_REVIEW_CHAT,
    "Zest.SendCodeReviewToChatBox",
    e,
    context
);
```

---

### **2. Main Chat** - `OpenZestChatAction.java`

**File:** `src/main/java/com/zps/zest/browser/actions/OpenZestChatAction.java`

**Add:**
```java
ActionMetricsHelper.INSTANCE.trackAction(
    project,
    FeatureType.OPEN_CHAT,
    "Zest.OpenChat",
    e,
    Collections.emptyMap()
);
```

---

### **3. Chat in Editor** - `OpenChatInEditorAction.java`

**File:** `src/main/java/com/zps/zest/browser/actions/OpenChatInEditorAction.java`

**Add:**
```java
ActionMetricsHelper.INSTANCE.trackAction(
    project,
    FeatureType.OPEN_CHAT_EDITOR,
    "Zest.OpenChatInEditor",
    e,
    Collections.emptyMap()
);
```

---

### **4. Tool-Enabled Chat** - `OpenToolEnabledChatAction.java`

**File:** `src/main/java/com/zps/zest/browser/actions/OpenToolEnabledChatAction.java`

**Add:**
```java
ActionMetricsHelper.INSTANCE.trackAction(
    project,
    FeatureType.TOOL_ENABLED_CHAT,
    "Zest.OpenToolEnabledChat",
    e,
    Collections.emptyMap()
);
```

---

### **5. Review Current File** - `ReviewCurrentFileAction.java`

**File:** `src/main/java/com/zps/zest/codehealth/actions/ReviewCurrentFileAction.java`

**Add:**
```java
ActionMetricsHelper.INSTANCE.trackAction(
    project,
    FeatureType.REVIEW_CURRENT_FILE,
    "Zest.ReviewCurrentFile",
    e,
    Collections.emptyMap()
);
```

---

### **6. Code Health Overview** - `OpenCodeHealthAction.java`

**File:** `src/main/java/com/zps/zest/codehealth/actions/OpenCodeHealthAction.java`

**Add:**
```java
ActionMetricsHelper.INSTANCE.trackAction(
    project,
    FeatureType.CODE_HEALTH_OVERVIEW,
    "Zest.OpenCodeHealth",
    e,
    Collections.emptyMap()
);
```

---

### **7. Daily Health Report** - `TriggerFinalReviewAction.java`

**File:** `src/main/java/com/zps/zest/codehealth/actions/TriggerFinalReviewAction.java`

**Add:**
```java
ActionMetricsHelper.INSTANCE.trackAction(
    project,
    FeatureType.DAILY_HEALTH_REPORT,
    "Zest.TriggerFinalReview",
    e,
    Collections.emptyMap()
);
```

---

### **8. Create Rules** - `CreateZestRulesAction.java`

**File:** `src/main/java/com/zps/zest/actions/CreateZestRulesAction.java`

**Add:**
```java
ActionMetricsHelper.INSTANCE.trackAction(
    project,
    FeatureType.CREATE_RULES_FILE,
    "Zest.CreateRulesFile",
    e,
    Collections.emptyMap()
);
```

---

### **9. Check Updates** - `ShowUpdateInfoAction.java`

**File:** `src/main/java/com/zps/zest/update/ShowUpdateInfoAction.java`

**Add:**
```java
ActionMetricsHelper.INSTANCE.trackAction(
    project,
    FeatureType.CHECK_UPDATES,
    "Zest.ShowUpdateInfo",
    e,
    Collections.emptyMap()
);
```

---

### **10-18. Debug Actions**

Same pattern for all debug/testing actions:
- `ZestCompletionTimingDebugAction.java` → FeatureType.COMPLETION_TIMING_DEBUG
- `TestRipgrepToolAction.java` → FeatureType.TEST_RIPGREP_TOOL
- `ToggleDevToolsAction.java` → FeatureType.TOGGLE_DEV_TOOLS
- `TestCodeContextAction.java` → FeatureType.TEST_CODE_CONTEXT
- `TestMetricsAction.java` → FeatureType.TEST_METRICS_SYSTEM
- `ViewMetricsSessionAction.java` → FeatureType.VIEW_METRICS_SESSION
- `ShowTestGenerationHelpAction.java` → FeatureType.TEST_GENERATION_HELP
- `ShowReviewQueueStatusAction.java` → FeatureType.REVIEW_QUEUE_STATUS
- `ZestTriggerQuickAction.kt` → FeatureType.TRIGGER_QUICK_ACTION (in addition to existing quick_action metric)

---

##  Example Session Log After Full Integration

```
═══════════════════════════════════════════════════════════════
SESSION: 2025-10-12 14:00:00 → 14:30:00 (1800 seconds)
═══════════════════════════════════════════════════════════════

[00:00:00.100] feature_usage - TEST_GENERATION
  Triggered by: KEYBOARD_SHORTCUT (Ctrl+Alt+T)
  Context: {"file": "UserService.java"}

[00:00:15.200] request (completion-001)
[00:00:17.500] view (completion-001)
[00:00:18.800] code_quality (completion-001)
[00:00:20.100] tab (completion-001)

[00:01:00.000] feature_usage - GIT_COMMIT_AND_PUSH
  Triggered by: MENU_CLICK

[00:01:05.000] code_health response

[00:02:30.000] feature_usage - CODE_REVIEW_CHAT
  Triggered by: EDITOR_POPUP (right-click)
  Context: {"file": "PaymentService.java"}

[00:03:00.000] feature_usage - OPEN_CHAT
  Triggered by: KEYBOARD_SHORTCUT (Ctrl+Shift+C)

[00:05:00.000] feature_usage - TOOL_ENABLED_CHAT
  Triggered by: MENU_CLICK

[00:10:00.000] feature_usage - CODE_HEALTH_OVERVIEW
  Triggered by: TOOLBAR_CLICK

[00:15:00.000] unit_test

═══════════════════════════════════════════════════════════════
SESSION STATISTICS
═══════════════════════════════════════════════════════════════
Total Events: 45

Events by Type:
  - feature_usage: 18  ← All actions tracked!
  - request: 8
  - view: 7
  - tab: 6
  - code_quality: 8
  - code_health: 3
  - dual_evaluation: 2
  - unit_test: 1

Feature Usage Breakdown:
  - OPEN_CHAT: 5
  - TEST_GENERATION: 3
  - CODE_REVIEW_CHAT: 2
  - GIT_COMMIT_AND_PUSH: 2
  - TOOL_ENABLED_CHAT: 2
  - CODE_HEALTH_OVERVIEW: 1
  - REVIEW_CURRENT_FILE: 1
  - CREATE_RULES_FILE: 1
  - TOGGLE_DEV_TOOLS: 1

Trigger Methods:
  - KEYBOARD_SHORTCUT: 12 (66%)
  - MENU_CLICK: 4 (22%)
  - TOOLBAR_CLICK: 1 (6%)
  - EDITOR_POPUP: 1 (6%)
```

---

## Testing

After integrating, test by:

1. **Use each feature normally** (click action, use keyboard shortcut, etc.)
2. **Open session log:** `Tools → View Metrics Session Log`
3. **Verify:** `feature_usage` events appear for each action
4. **Export:** Save session log to file
5. **Analyze:** Check trigger methods, usage patterns

---

## Benefits

### Before Integration:
- ❌ Don't know which features are used
- ❌ Don't know if users prefer keyboard or menu
- ❌ Can't measure feature adoption
- ❌ Can't identify unused features

### After Integration:
- ✅ Track every action invocation
- ✅ Know how actions are triggered (keyboard/menu/toolbar)
- ✅ Measure feature popularity
- ✅ Data-driven feature development
- ✅ Identify features to deprecate or improve

---

## Quick Reference

### FeatureType Enum Values:
```
TEST_GENERATION
GIT_COMMIT_AND_PUSH
CODE_REVIEW_CHAT
REVIEW_CURRENT_FILE
DAILY_HEALTH_REPORT
OPEN_CHAT
OPEN_CHAT_EDITOR
TOOL_ENABLED_CHAT
CODE_HEALTH_OVERVIEW
REVIEW_QUEUE_STATUS
CREATE_RULES_FILE
CHECK_UPDATES
COMPLETION_TIMING_DEBUG
TEST_RIPGREP_TOOL
TEST_METRICS_SYSTEM
VIEW_METRICS_SESSION
TOGGLE_DEV_TOOLS
TEST_CODE_CONTEXT
TEST_GENERATION_HELP
```

### TriggerMethod Values (auto-detected):
```
KEYBOARD_SHORTCUT - From keyboard shortcut
MENU_CLICK - From menu
TOOLBAR_CLICK - From toolbar button
EDITOR_POPUP - From right-click context menu
PROGRAMMATIC - From code
```

---

## Files Modified (for reference)

- **Core (already done):** MetricMetadata.kt, MetricEvent.kt, MetricsEndpoint.kt, ZestInlineCompletionMetricsService.kt, MetricsSerializer.kt
- **Helper (done):** ActionMetricsHelper.kt
- **Example integrations (done):** GenerateTestAction.java, GitCommitMessageGeneratorAction.java
- **Remaining:** 20 action files (follow same pattern)
