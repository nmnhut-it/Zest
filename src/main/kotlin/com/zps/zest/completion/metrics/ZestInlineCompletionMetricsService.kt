package com.zps.zest.completion.metrics

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.zps.zest.settings.ZestGlobalSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service responsible for tracking and sending inline completion metrics
 * Handles event collection, batching, and asynchronous submission to the API
 */
@Service(Service.Level.PROJECT)
class ZestInlineCompletionMetricsService(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(ZestInlineCompletionMetricsService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Debug logging flag
    private var debugLoggingEnabled = false

    // Settings and HTTP client
    private val settings by lazy { ZestGlobalSettings.getInstance() }
    private val metricsHttpClient = MetricsHttpClient(project)

    // Event queue for batching (bounded to prevent OOM)
    private val eventChannel by lazy {
        Channel<MetricEvent>(settings.metricsMaxQueueSize)
    }
    
    // Track active completions
    private val activeCompletions = ConcurrentHashMap<String, CompletionSession>()
    
    // Track cancellations
    private var cancellationsSinceLastCompletion = 0
    private var lastSuccessfulCompletionTime: Long? = null
    
    // Timing breakdowns for debugging
    private val timingHistory = mutableListOf<CompletionTimingInfo>()
    private val maxTimingHistory = 50
    
    // Service state
    private val isEnabled = AtomicBoolean(true)
    private var processingJob: Job? = null
    
    init {
        logger.info("Initializing ZestInlineCompletionMetricsService")
        startEventProcessor()
    }
    
    /**
     * Track when a completion is requested
     */
    fun trackCompletionRequested(
        completionId: String,
        strategy: String,
        fileType: String,
        actualModel: String,
        contextInfo: CompletionContextInfo = CompletionContextInfo()
    ) {
        if (!isEnabled.get()) return

        log("Tracking completion requested: $completionId")

        val session = CompletionSession(
            completionId = completionId,
            startTime = System.currentTimeMillis(),
            strategy = strategy,
            fileType = fileType,
            actualModel = actualModel,
            contextInfo = contextInfo,
            hasViewed = false
        )
        
        activeCompletions[completionId] = session
        
        // Initialize timing info
        val timingInfo = CompletionTimingInfo(
            completionId = completionId,
            strategy = strategy,
            fileName = fileType
        )
        session.timingInfo = timingInfo
        
        val baseBuilder = MetricsUtils.createBaseMetadata(project, actualModel)
        sendEvent(MetricEvent.InlineCompletionRequest(
            completionId = completionId,
            actualModel = actualModel,
            elapsed = 0,
            metadata = baseBuilder.buildInlineRequest(
                fileType = fileType,
                strategy = MetricsUtils.parseStrategy(strategy)
            )
        ))
    }
    
    /**
     * Track context collection timing
     */
    fun trackContextCollectionTime(completionId: String, timeMs: Long) {
        activeCompletions[completionId]?.timingInfo?.contextCollectionTime = timeMs
    }
    
    /**
     * Track LLM call timing
     */
    fun trackLLMCallTime(completionId: String, timeMs: Long) {
        if (timeMs > 0) {
            activeCompletions[completionId]?.timingInfo?.llmCallTime = timeMs
        }
    }
    
    /**
     * Track response parsing timing
     */
    fun trackResponseParsingTime(completionId: String, timeMs: Long) {
        activeCompletions[completionId]?.timingInfo?.responseParsingTime = timeMs
    }
    
    /**
     * Track inlay rendering timing
     */
    fun trackInlayRenderingTime(completionId: String, timeMs: Long) {
        activeCompletions[completionId]?.timingInfo?.inlayRenderingTime = timeMs
    }
    
    /**
     * Track when a completion is cancelled
     */
    fun trackCompletionCancelled(completionId: String) {
        val session = activeCompletions[completionId] ?: return
        session.timingInfo?.let { timing ->
            timing.cancelled = true
            timing.totalTime = System.currentTimeMillis() - session.startTime
            
            // Log cancellation details
            logger.info("Completion cancelled: $completionId")
            logger.info("  Total time: ${timing.totalTime}ms")
            logger.info("  Context collection: ${timing.contextCollectionTime}ms") 
            logger.info("  LLM call: ${timing.llmCallTime}ms")
            logger.info("  Response parsing: ${timing.responseParsingTime}ms")
            
            // Add to history
            synchronized(timingHistory) {
                timingHistory.add(0, timing)
                if (timingHistory.size > maxTimingHistory) {
                    timingHistory.removeAt(timingHistory.size - 1)
                }
            }
        }
        
        cancellationsSinceLastCompletion++
        activeCompletions.remove(completionId)
    }
    
    /**
     * Track when a completion is displayed to the user
     */
    fun trackCompletionViewed(
        completionId: String,
        completionLength: Int,
        completionLineCount: Int,
        confidence: Float? = null
    ) {
        if (!isEnabled.get()) return
        
        val session = activeCompletions[completionId] ?: return
        session.hasViewed = true;
        val elapsed = System.currentTimeMillis() - session.startTime
        
        session.viewedAt = System.currentTimeMillis()
        session.completionLength = completionLength
        session.confidence = confidence
        
        val baseBuilder = MetricsUtils.createBaseMetadata(project, session.actualModel)
        sendEvent(MetricEvent.InlineView(
            completionId = completionId,
            actualModel = session.actualModel,
            elapsed = elapsed,
            metadata = baseBuilder.buildInlineView(
                completionLength = completionLength,
                completionLineCount = completionLineCount,
                confidence = confidence ?: 0f
            )
        ))
    }
    
    /**
     * Track when request returns result
     */
    fun trackCompletionCompleted(
        completionId: String,
        completionContent: String,
        responseTime: Long
    ) {
        if (!isEnabled.get()) return
        
        val session = activeCompletions[completionId] ?: return
        val elapsed = System.currentTimeMillis() - session.startTime
        
        val baseBuilder = MetricsUtils.createBaseMetadata(project, session.actualModel)
        sendEvent(MetricEvent.InlineCompletionResponse(
            completionId = completionId,
            completionContent = completionContent,
            actualModel = session.actualModel,
            elapsed = elapsed,
            metadata = baseBuilder.buildInlineResponse(
                fileType = session.fileType,
                strategy = MetricsUtils.parseStrategy(session.strategy),
                responseTimeMs = responseTime
            )
        ))
    }
    
    /**
     * Track when user presses ESC to reject
     */
    fun trackCompletionDeclined(
        completionId: String,
        reason: String = "esc_pressed"
    ) {
        if (!isEnabled.get()) return
        
        val session = activeCompletions[completionId] ?: return
        if (session.hasViewed == false )
            return;
        val elapsed = System.currentTimeMillis() - session.startTime
        
        val baseBuilder = MetricsUtils.createBaseMetadata(project, session.actualModel)
        sendEvent(MetricEvent.InlineDecline(
            completionId = completionId,
            actualModel = session.actualModel,
            elapsed = elapsed,
            metadata = baseBuilder.buildInlineReject(
                reason = MetricsUtils.parseRejectReason(reason)
            )
        ))
        
        // Clean up session after a delay
        cleanupSessionDelayed(completionId)
    }
    
    /**
     * Track when user continues typing (ignores completion)
     */
    fun trackCompletionDismissed(
        completionId: String,
        reason: String = "user_typed"
    ) {
        if (!isEnabled.get()) return
        
        val session = activeCompletions[completionId] ?: return
        val elapsed = System.currentTimeMillis() - session.startTime
        
        val baseBuilder = MetricsUtils.createBaseMetadata(project, session.actualModel)
        sendEvent(MetricEvent.InlineDismiss(
            completionId = completionId,
            actualModel = session.actualModel,
            elapsed = elapsed,
            metadata = baseBuilder.buildInlineDismiss(
                reason = reason,
                partialAcceptCount = 0,
                totalAcceptedLength = 0
            )
        ))
        
        // Clean up session after a delay
        cleanupSessionDelayed(completionId)
    }
    
    /**
     * Track when a multi-line completion session is finalized
     * (user stopped accepting lines before accepting all)
     */
    fun trackCompletionFinalized(
        completionId: String,
        reason: String = "partial_acceptance"
    ) {
        if (!isEnabled.get()) return
        
        val session = activeCompletions[completionId] ?: return
        val elapsed = System.currentTimeMillis() - session.startTime
        
        // Only send if there were partial acceptances
        if (session.partialAcceptances > 0) {
            val baseBuilder = MetricsUtils.createBaseMetadata(project, session.actualModel)
            sendEvent(MetricEvent.InlineDismiss(
                completionId = completionId,
                actualModel = session.actualModel,
                elapsed = elapsed,
                metadata = baseBuilder.buildInlineDismiss(
                    reason = reason,
                    partialAcceptCount = session.partialAcceptances,
                    totalAcceptedLength = session.totalAcceptedLength
                )
            ))
        }
        
        cleanupSessionDelayed(completionId)
    }
    
    /**
     * Track when a completion is accepted (TAB pressed)
     */
    fun trackCompletionAccepted(
        completionId: String,
        completionContent: String,
        isAll: Boolean,
        acceptType: String = "full",
        userAction: String = "tab"
    ) {
        if (!isEnabled.get()) return
        
        val session = activeCompletions[completionId] ?: return
        val elapsed = System.currentTimeMillis() - session.startTime
        
        // Update timing info for successful completion
        session.timingInfo?.let { timing ->
            timing.succeeded = true
            timing.totalTime = elapsed
            
            // Add to history
            synchronized(timingHistory) {
                timingHistory.add(0, timing)
                if (timingHistory.size > maxTimingHistory) {
                    timingHistory.removeAt(timingHistory.size - 1)
                }
            }
        }
        
        // Reset cancellation counter on successful completion
        if (isAll) {
            cancellationsSinceLastCompletion = 0
            lastSuccessfulCompletionTime = System.currentTimeMillis()
        }
        
        // Update session state for partial acceptances
        if (!isAll) {
            session.partialAcceptances += 1
            session.totalAcceptedLength += completionContent.length
        }
        
        val baseBuilder = MetricsUtils.createBaseMetadata(project, session.actualModel)
        sendEvent(MetricEvent.InlineSelect(
            completionId = completionId,
            completionContent = completionContent,
            actualModel = session.actualModel,
            elapsed = elapsed,
            metadata = baseBuilder.buildInlineAccept(
                acceptType = MetricsUtils.parseAcceptType(acceptType),
                userAction = MetricsUtils.parseUserAction(userAction),
                strategy = MetricsUtils.parseStrategy(session.strategy),
                fileType = session.fileType,
                isPartial = !isAll,
                partialAcceptCount = session.partialAcceptances,
                totalAcceptedLength = if (session.totalAcceptedLength > 0) session.totalAcceptedLength else completionContent.length,
                viewToAcceptTimeMs = session.viewedAt?.let { System.currentTimeMillis() - it } ?: 0L
            )
        ))
        
        // Only clean up session if all lines are accepted
        if (isAll) {
            cleanupSessionDelayed(completionId)
        }
    }
    
    /**
     * Update the actual model for a completion session
     */
    fun updateSessionModel(completionId: String, actualModel: String) {
        activeCompletions[completionId]?.let { session ->
            // Create a new session with updated model (since it's a data class)
            val updatedSession = session.copy(actualModel = actualModel)
            activeCompletions[completionId] = updatedSession
            log("Updated session model for $completionId to $actualModel")
        }
    }
    
    /**
     * Track Code Health events with strongly-typed data
     */
    fun trackCodeHealthEvent(
        eventId: String,
        eventType: String,  // "request", "response", "view", "fix"
        analysisData: CodeHealthAnalysisData
    ) {
        if (!isEnabled.get()) return

        log("Tracking code health event: $eventType for $eventId")

        val baseBuilder = MetricsUtils.createBaseMetadata(project, "local-model-mini")
        val metadata = baseBuilder.buildCodeHealth(
            eventType = eventType,
            trigger = CodeHealthTrigger.valueOf(analysisData.trigger.uppercase().replace(" ", "_")),
            criticalIssues = analysisData.criticalIssues,
            highIssues = analysisData.highIssues,
            totalIssues = analysisData.totalIssues,
            methodsAnalyzed = analysisData.methodsAnalyzed,
            averageHealthScore = analysisData.averageHealthScore,
            issuesByCategory = analysisData.issuesByCategory,
            userAction = analysisData.userAction,
            elapsedMs = analysisData.elapsedMs ?: 0
        )

        sendEvent(MetricEvent.CodeHealthEvent(
            completionId = eventId,
            actualModel = "local-model-mini",
            elapsed = analysisData.elapsedMs ?: 0,
            metadata = metadata
        ))
    }
    
    /**
     * Send an event to the processing queue
     */
    private fun sendEvent(event: MetricEvent) {
        scope.launch {
            try {
                eventChannel.send(event)
                log("Queued event: ${event.eventType} for ${event.completionId}")
                logger.debug("Queued metric event: ${event.eventType} for completion ${event.completionId}")
            } catch (e: Exception) {
                logger.warn("Failed to queue metric event", e)
            }
        }
    }
    
    /**
     * Start the event processor coroutine
     */
    private fun startEventProcessor() {
        processingJob = scope.launch {
            logger.info("Starting metric event processor")
            
            eventChannel.consumeEach { event ->
                try {
                    processEvent(event)
                } catch (e: Exception) {
                    logger.error("Error processing metric event", e)
                }
            }
        }
    }
    
    /**
     * Process a single metric event
     */
    private suspend fun processEvent(event: MetricEvent) {
        if (!settings.metricsEnabled) {
            logger.debug("Metrics disabled, skipping event: ${event.eventType}")
            return
        }

        try {
            // Debug logging
            logger.debug("Processing metric event: ${event.eventType} for ${event.completionId}")

            // Determine endpoint from event type
            val enumUsage = getEnumUsage(event)
            val endpoint = MetricsEndpoint.fromUsage(enumUsage)

            // Serialize event to JSON
            val json = MetricsSerializer.serialize(event)

            // Send via HTTP client
            val success = metricsHttpClient.sendMetric(endpoint, event.eventType, json)

            if (success) {
                logger.debug("Successfully sent metric: ${event.eventType}")
            } else {
                logger.debug("Failed to send metric: ${event.eventType}")
            }

        } catch (e: Exception) {
            logger.warn("Error processing metric event: ${event.eventType}", e)
        }
    }

    /**
     * Get enum usage string from event type
     */
    private fun getEnumUsage(event: MetricEvent): String {
        return when (event) {
            is MetricEvent.InlineCompletionRequest,
            is MetricEvent.InlineCompletionResponse,
            is MetricEvent.InlineView,
            is MetricEvent.InlineSelect,
            is MetricEvent.InlineDecline,
            is MetricEvent.InlineDismiss -> "INLINE_COMPLETION_LOGGING"
            is MetricEvent.QuickActionRequest,
            is MetricEvent.QuickActionResponse,
            is MetricEvent.QuickActionView,
            is MetricEvent.QuickActionSelect,
            is MetricEvent.QuickActionDecline,
            is MetricEvent.QuickActionDismiss -> "QUICK_ACTION_LOGGING"
            is MetricEvent.CodeHealthEvent -> "CODE_HEALTH_LOGGING"
            is MetricEvent.DualEvaluationEvent -> "DUAL_EVALUATION_LOGGING"
            is MetricEvent.CodeQualityEvent -> "CODE_QUALITY_LOGGING"
            is MetricEvent.UnitTestEvent -> "UNIT_TEST_LOGGING"
            is MetricEvent.FeatureUsageEvent -> "FEATURE_USAGE_LOGGING"
        }
    }

    /**
     * Track feature usage (action invocations)
     */
    fun trackFeatureUsage(
        featureType: FeatureType,
        actionId: String,
        triggeredBy: TriggerMethod,
        contextInfo: Map<String, String> = emptyMap()
    ) {
        if (!isEnabled.get()) return

        val metadata = FeatureUsageMetadata(
            model = "feature-tracking",
            projectId = MetricsUtils.getProjectHash(project),
            userId = MetricsUtils.getUserHash(project),
            user = MetricsUtils.getActualUsername(project),
            ideVersion = MetricsUtils.getIdeVersion(),
            pluginVersion = MetricsUtils.getPluginVersion(),
            timestamp = System.currentTimeMillis(),
            featureType = featureType,
            actionId = actionId,
            triggeredBy = triggeredBy,
            contextInfo = contextInfo
        )

        sendEvent(MetricEvent.FeatureUsageEvent(
            completionId = "feature-${System.currentTimeMillis()}",
            actualModel = "feature-tracking",
            elapsed = 0,
            metadata = metadata
        ))
    }

    /**
     * Track dual evaluation metrics
     */
    fun trackDualEvaluation(
        completionId: String,
        originalPrompt: String,
        models: List<String>,
        results: List<ModelComparisonResult>,
        elapsed: Long
    ) {
        if (!isEnabled.get()) return

        val metadata = DualEvaluationMetadata(
            model = "dual-eval",
            projectId = MetricsUtils.getProjectHash(project),
            userId = MetricsUtils.getUserHash(project),
            user = MetricsUtils.getActualUsername(project),
            ideVersion = MetricsUtils.getIdeVersion(),
            pluginVersion = MetricsUtils.getPluginVersion(),
            timestamp = System.currentTimeMillis(),
            originalPrompt = originalPrompt,
            models = models,
            results = results
        )

        sendEvent(MetricEvent.DualEvaluationEvent(
            completionId = completionId,
            actualModel = "dual-eval",
            elapsed = elapsed,
            metadata = metadata
        ))
    }

    /**
     * Track code quality metrics
     */
    fun trackCodeQuality(
        completionId: String,
        linesOfCode: Int,
        styleComplianceScore: Int,
        selfReviewPassed: Boolean,
        compilationErrors: Int,
        logicBugsDetected: Int,
        wasReviewed: Boolean,
        wasImproved: Boolean
    ) {
        if (!isEnabled.get()) return

        val compilationErrorsPer1000 = if (linesOfCode > 0) {
            (compilationErrors * 1000f) / linesOfCode
        } else 0f

        val logicBugsPer1000 = if (linesOfCode > 0) {
            (logicBugsDetected * 1000f) / linesOfCode
        } else 0f

        val metadata = CodeQualityMetadata(
            model = "code-quality",
            projectId = MetricsUtils.getProjectHash(project),
            userId = MetricsUtils.getUserHash(project),
            user = MetricsUtils.getActualUsername(project),
            ideVersion = MetricsUtils.getIdeVersion(),
            pluginVersion = MetricsUtils.getPluginVersion(),
            timestamp = System.currentTimeMillis(),
            completionId = completionId,
            linesOfCode = linesOfCode,
            styleComplianceScore = styleComplianceScore,
            selfReviewPassed = selfReviewPassed,
            compilationErrors = compilationErrors,
            compilationErrorsPer1000Lines = compilationErrorsPer1000,
            logicBugsDetected = logicBugsDetected,
            logicBugsPer1000Lines = logicBugsPer1000,
            wasReviewed = wasReviewed,
            wasImproved = wasImproved
        )

        sendEvent(MetricEvent.CodeQualityEvent(
            completionId = completionId,
            actualModel = "code-quality",
            elapsed = 0,
            metadata = metadata
        ))
    }

    /**
     * Track unit test metrics
     */
    fun trackUnitTest(
        testId: String,
        totalTests: Int,
        wordCount: Int,
        generationTimeMs: Long,
        testsCompiled: Int,
        testsPassedImmediately: Int
    ) {
        if (!isEnabled.get()) return

        val workOutOfBoxPct = if (totalTests > 0) {
            (testsPassedImmediately * 100f) / totalTests
        } else 0f

        val avgWordsPerMin = settings.avgWordsPerMinute
        val timeSavedMinutes = if (generationTimeMs > 0) {
            (avgWordsPerMin * wordCount) / (generationTimeMs / 60000f)
        } else 0f

        val metadata = UnitTestMetadata(
            model = "unit-test",
            projectId = MetricsUtils.getProjectHash(project),
            userId = MetricsUtils.getUserHash(project),
            user = MetricsUtils.getActualUsername(project),
            ideVersion = MetricsUtils.getIdeVersion(),
            pluginVersion = MetricsUtils.getPluginVersion(),
            timestamp = System.currentTimeMillis(),
            testId = testId,
            totalTests = totalTests,
            wordCount = wordCount,
            generationTimeMs = generationTimeMs,
            testsCompiled = testsCompiled,
            testsPassedImmediately = testsPassedImmediately,
            workOutOfBoxPercentage = workOutOfBoxPct,
            avgWordsPerMinute = avgWordsPerMin,
            timeSavedMinutes = timeSavedMinutes
        )

        sendEvent(MetricEvent.UnitTestEvent(
            completionId = testId,
            actualModel = "unit-test",
            elapsed = generationTimeMs,
            metadata = metadata
        ))
    }
    
    /**
     * Clean up a completion session after a delay
     */
    private fun cleanupSessionDelayed(completionId: String, delayMs: Long = 60000) {
        scope.launch {
            delay(delayMs)
            activeCompletions.remove(completionId)
            logger.debug("Cleaned up completion session: $completionId")
        }
    }
    
    /**
     * Enable or disable metrics collection
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled.set(enabled)
        logger.info("Metrics collection ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * Get current metrics state for debugging
     */
    fun getMetricsState(): MetricsState {
        return MetricsState(
            enabled = isEnabled.get(),
            activeCompletions = activeCompletions.size,
            queuedEvents = eventChannel.isEmpty.let { if (it) 0 else -1 } // Can't get exact size
        )
    }
    
    /**
     * Get timing history for debugging
     */
    fun getTimingHistory(): List<CompletionTimingInfo> {
        return synchronized(timingHistory) {
            timingHistory.toList()
        }
    }
    
    /**
     * Clear all active sessions (for testing/debugging)
     */
    fun clearAllSessions() {
        activeCompletions.clear()
        logger.info("Cleared all active completion sessions")
    }
    
    /**
     * Generate debug report with timing information
     */
    fun generateTimingDebugReport(): String {
        val sb = StringBuilder()
        
        sb.appendLine("=== ZEST COMPLETION TIMING DEBUG REPORT ===")
        sb.appendLine("Generated at: ${System.currentTimeMillis()}")
        sb.appendLine()
        
        // Summary
        sb.appendLine("=== SUMMARY ===")
        sb.appendLine("Active completions: ${activeCompletions.size}")
        sb.appendLine("Cancellations since last completion: $cancellationsSinceLastCompletion")
        lastSuccessfulCompletionTime?.let {
            val timeSinceLast = System.currentTimeMillis() - it
            sb.appendLine("Time since last successful completion: ${timeSinceLast}ms (${timeSinceLast/1000}s)")
        }
        sb.appendLine()
        
        // Recent timing history
        sb.appendLine("=== RECENT COMPLETIONS (Last ${timingHistory.size}) ===")
        synchronized(timingHistory) {
            timingHistory.forEachIndexed { index, timing ->
                sb.appendLine("\n[${index + 1}] ${timing.completionId}")
                sb.appendLine("  Strategy: ${timing.strategy}")
                sb.appendLine("  File: ${timing.fileName}")
                sb.appendLine("  Status: ${when {
                    timing.cancelled -> "CANCELLED"
                    timing.succeeded -> "ACCEPTED"
                    timing.error != null -> "ERROR: ${timing.error}"
                    else -> "UNKNOWN"
                }}")
                sb.appendLine("  Total time: ${timing.totalTime}ms")
                sb.appendLine("  Breakdown:")
                sb.appendLine("    - Context collection: ${timing.contextCollectionTime}ms")
                sb.appendLine("    - LLM call: ${timing.llmCallTime}ms")
                sb.appendLine("    - Response parsing: ${timing.responseParsingTime}ms")
                sb.appendLine("    - Inlay rendering: ${timing.inlayRenderingTime}ms")
            }
        }
        
        // Average timings for successful completions
        val successfulCompletions = timingHistory.filter { it.succeeded }
        if (successfulCompletions.isNotEmpty()) {
            sb.appendLine("\n=== AVERAGE TIMINGS (${successfulCompletions.size} successful) ===")
            sb.appendLine("Average total time: ${successfulCompletions.map { it.totalTime }.average().toLong()}ms")
            sb.appendLine("Average context collection: ${successfulCompletions.map { it.contextCollectionTime }.average().toLong()}ms")
            sb.appendLine("Average LLM call: ${successfulCompletions.map { it.llmCallTime }.average().toLong()}ms")
            sb.appendLine("Average response parsing: ${successfulCompletions.map { it.responseParsingTime }.average().toLong()}ms")
            sb.appendLine("Average inlay rendering: ${successfulCompletions.map { it.inlayRenderingTime }.average().toLong()}ms")
        }
        
        return sb.toString()
    }
    
    override fun dispose() {
        logger.info("Disposing ZestInlineCompletionMetricsService")
        processingJob?.cancel()
        scope.cancel()
        eventChannel.close()
        activeCompletions.clear()
    }
    
    /**
     * Internal debug logging function
     * @param message The message to log
     * @param tag Optional tag for categorizing logs (default: "ZestMetrics")
     */
    private fun log(message: String, tag: String = "ZestMetrics") {
        if (debugLoggingEnabled) {
            println("[$tag] $message")
        }
    }
    
    /**
     * Enable or disable debug logging
     */
    fun setDebugLogging(enabled: Boolean) {
        debugLoggingEnabled = enabled
        log("Debug logging ${if (enabled) "enabled" else "disabled"}")
    }
    
    companion object {
        fun getInstance(project: Project): ZestInlineCompletionMetricsService {
            return project.getService(ZestInlineCompletionMetricsService::class.java)
        }
    }
}

/**
 * Data class representing metrics state
 */
data class MetricsState(
    val enabled: Boolean,
    val activeCompletions: Int,
    val queuedEvents: Int
)

/**
 * Timing information for a completion request
 */
data class CompletionTimingInfo(
    val completionId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val strategy: String,
    val fileName: String,
    
    // Timing breakdowns (all in milliseconds)
    var contextCollectionTime: Long = 0,
    var llmCallTime: Long = 0,
    var responseParsingTime: Long = 0,
    var inlayRenderingTime: Long = 0,
    var totalTime: Long = 0,
    
    // Additional info
    var cancelled: Boolean = false,
    var succeeded: Boolean = false,
    var error: String? = null
)
