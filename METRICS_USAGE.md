# Zest Metrics System - Usage Guide

## Overview

The metrics system has been refactored to be type-safe, well-architected, and maintainable.

---

## Architecture

```
┌─────────────────────────────────────────┐
│ ZestInlineCompletionMetricsService      │  Business Logic
│ - Tracks events                         │
│ - Manages sessions                      │
│ - Queues metrics                        │
└──────────────┬──────────────────────────┘
               │ MetricEvent (type-safe)
               ↓
┌─────────────────────────────────────────┐
│ MetricsSerializer                       │  Serialization
│ - Converts MetricEvent → JSON           │
└──────────────┬──────────────────────────┘
               │ JsonObject
               ↓
┌─────────────────────────────────────────┐
│ MetricsHttpClient                       │  HTTP Transport
│ - HTTP POST to metrics server           │
│ - Error handling & logging              │
└─────────────────────────────────────────┘
```

---

## Configuration

**Settings Location:** Settings → Tools → Zest Plugin → Features → Metrics Configuration

- **Enable metrics collection** - Master toggle
- **Metrics Server URL** - Base URL (e.g., https://zest-internal.zingplay.com)
- **Batch size** - Events per batch (default: 10)
- **Batch interval** - Max seconds before send (default: 30)
- **Max queue size** - Prevent OOM (default: 1000)
- **Dual evaluation** - Multi-AI comparison (default: disabled)
- **AI self-review** - Review code before showing (default: enabled)

---

## Usage Examples

### 1. Track Dual Evaluation (Multi-AI Comparison)

**When to use:** Compare multiple AI models on same prompt

```kotlin
val metricsService = ZestInlineCompletionMetricsService.getInstance(project)

// Test prompt with multiple models
val prompt = "Write a function to calculate factorial"
val models = listOf("gpt-4o-mini", "claude-3-5-sonnet-20241022")

// Get results from each model (async)
val results = models.map { model ->
    val startTime = System.currentTimeMillis()
    val response = callAI(model, prompt)
    val elapsed = System.currentTimeMillis() - startTime

    ModelComparisonResult(
        modelName = model,
        responseTimeMs = elapsed,
        tokenCount = response.tokenCount,
        qualityScore = null  // Optional: judge quality with another AI
    )
}

// Track comparison
metricsService.trackDualEvaluation(
    completionId = "dual-eval-${UUID.randomUUID()}",
    originalPrompt = prompt,
    models = models,
    results = results,
    elapsed = results.maxOf { it.responseTimeMs }
)
```

---

### 2. Track Code Quality (AI Self-Review + Error Metrics)

**When to use:** After generating code, before showing to user

```kotlin
val metricsService = ZestInlineCompletionMetricsService.getInstance(project)
val settings = ZestGlobalSettings.getInstance()

// Generate code
val generatedCode = aiGenerateCode(prompt)
val linesOfCode = generatedCode.lines().size

// AI Self-Review (if enabled)
var styleScore = 100
var reviewPassed = true
var wasImproved = false

if (settings.aiSelfReviewEnabled) {
    val review = aiReviewCode(generatedCode)
    styleScore = review.styleComplianceScore  // 0-100
    reviewPassed = review.score >= 80

    if (!reviewPassed) {
        generatedCode = aiImproveCode(generatedCode, review.feedback)
        wasImproved = true
    }
}

// Static analysis (optional)
val compilationErrors = checkCompilation(generatedCode)
val logicBugs = detectLogicBugs(generatedCode)

// Track metrics
metricsService.trackCodeQuality(
    completionId = "completion-${UUID.randomUUID()}",
    linesOfCode = linesOfCode,
    styleComplianceScore = styleScore,
    selfReviewPassed = reviewPassed,
    compilationErrors = compilationErrors.size,
    logicBugsDetected = logicBugs.size,
    wasReviewed = settings.aiSelfReviewEnabled,
    wasImproved = wasImproved
)
```

**Metrics tracked:**
- **Style compliance score** (0-100) - AI self-review
- **Compilation errors per 1000 lines** - Auto-calculated
- **Logic bugs per 1000 lines** - Auto-calculated
- **Was improved before showing** - Track quality improvements

---

### 3. Track Unit Test Metrics (Work Out of Box + Time Saved)

**When to use:** After unit test generation completes

```kotlin
val metricsService = ZestInlineCompletionMetricsService.getInstance(project)
val settings = ZestGlobalSettings.getInstance()

// Generate tests
val startTime = System.currentTimeMillis()
val testCode = aiGenerateTests(classToTest)
val generationTime = System.currentTimeMillis() - startTime

// Analyze test
val totalTests = countTestMethods(testCode)
val wordCount = testCode.split("\\s+".toRegex()).size

// Run tests to check quality
val compilationResult = compileTests(testCode)
val testsCompiled = if (compilationResult.success) totalTests else 0

val testResult = runTests(testCode)
val testsPassedImmediately = testResult.passedCount

// Track metrics
metricsService.trackUnitTest(
    testId = "test-gen-${UUID.randomUUID()}",
    totalTests = totalTests,
    wordCount = wordCount,
    generationTimeMs = generationTime,
    testsCompiled = testsCompiled,
    testsPassedImmediately = testsPassedImmediately
)
```

**Metrics tracked:**
- **Work out of box %** = (testsPassedImmediately / totalTests) * 100
- **Time saved (minutes)** = (avgWordsPerMin * wordCount) / (generationTimeMs / 60000)
- Auto-calculated based on configuration

---

## Metrics Endpoints

All metrics are sent to: `{baseUrl}/{endpoint}/{eventType}`

**Examples:**
- `https://zest-internal.zingplay.com/dual_evaluation/dual_evaluation`
- `https://zest-internal.zingplay.com/code_quality/code_quality`
- `https://zest-internal.zingplay.com/unit_test/unit_test`

---

## Key Improvements

### ✅ Type Safety
- No more `Map<String, Any>` - all fields strongly typed
- Nullable fields removed - defaults used instead
- Compile-time safety for all metrics

### ✅ Clean Architecture
- **MetricsHttpClient** - HTTP transport only
- **MetricsSerializer** - JSON serialization only
- **MetricsService** - Business logic only
- Single Responsibility Principle

### ✅ Configuration
- All metrics configurable via Settings UI
- Bounded queue to prevent OOM
- Can disable metrics entirely
- Configurable batch size/interval

### ✅ No More Bad Code
- ❌ Removed hard-coded plugin version
- ❌ Removed reflection hack
- ❌ Removed `println` debug statements
- ❌ Removed unbounded Channel
- ✅ Proper logging with Logger
- ✅ Decoupled from LLMService

---

## Future Integration Points

### For Dual Evaluation:
Intercept in `NaiveLLMService.callLLM()` - test prompt with multiple models asynchronously

### For Code Quality:
Hook into code generation - review before showing to user

### For Unit Test:
Hook into `StateMachineTestGenerationService` - track test quality after generation
