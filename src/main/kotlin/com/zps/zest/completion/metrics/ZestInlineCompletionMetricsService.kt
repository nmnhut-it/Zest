package com.zps.zest.completion.metrics

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.zps.zest.langchain4j.util.LLMService
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
    
    // Event queue for batching
    private val eventChannel = Channel<MetricEvent>(Channel.UNLIMITED)
    
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
    
    // LLM service for API calls
    private val llmService by lazy { LLMService(project) }
    
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
        contextInfo: Map<String, Any> = emptyMap()
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
        
        sendEvent(MetricEvent.CompletionRequest(
            completionId = completionId,
            actualModel = actualModel,
            elapsed = 0,
            metadata = mapOf(
                "strategy" to strategy,
                "file_type" to fileType
            ) + contextInfo
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
        
        sendEvent(MetricEvent.View(
            completionId = completionId,
            actualModel = session.actualModel,
            elapsed = elapsed,
            metadata = mapOf(
                "completion_length" to completionLength,
                "completion_line_count" to completionLineCount,
                "confidence" to (confidence ?: 0f)
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
        
        sendEvent(MetricEvent.CompletionResponse(
            completionId = completionId,
            completionContent = completionContent,
            actualModel = session.actualModel,
            elapsed = elapsed,
            metadata = mapOf(
                "response_time" to responseTime,
                "strategy" to session.strategy,
                "file_type" to session.fileType
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
        
        sendEvent(MetricEvent.Decline(
            completionId = completionId,
            actualModel = session.actualModel,
            elapsed = elapsed,
            metadata = mapOf("reason" to reason)
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
        
        sendEvent(MetricEvent.Dismiss(
            completionId = completionId,
            actualModel = session.actualModel,
            elapsed = elapsed,
            metadata = mapOf("reason" to reason)
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
        if ((session.partialAcceptances ?: 0) > 0) {
            sendEvent(MetricEvent.Dismiss(
                completionId = completionId,
                actualModel = session.actualModel,
                elapsed = elapsed,
                metadata = mapOf(
                    "reason" to reason,
                    "partial_accept_count" to (session.partialAcceptances ?: 0),
                    "total_accepted_length" to (session.totalAcceptedLength ?: 0)
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
            session.partialAcceptances = (session.partialAcceptances ?: 0) + 1
            session.totalAcceptedLength = (session.totalAcceptedLength ?: 0) + completionContent.length
        }
        
        sendEvent(MetricEvent.Select(
            completionId = completionId,
            completionContent = completionContent,
            actualModel = session.actualModel,
            elapsed = elapsed,
            metadata = mapOf(
                "accept_type" to acceptType,
                "user_action" to userAction,
                "strategy" to session.strategy,
                "file_type" to session.fileType,
                "is_partial" to !isAll,
                "partial_accept_count" to (session.partialAcceptances ?: 0),
                "total_accepted_length" to (session.totalAcceptedLength ?: completionContent.length),
                "view_to_accept_time" to (session.viewedAt?.let { System.currentTimeMillis() - it } ?: 0L)
            )
        ))
        
        // Only clean up session if all lines are accepted
        if (isAll) {
            cleanupSessionDelayed(completionId)
        }
    }
    
    /**
     * Track custom events (like Code Health metrics)
     */
    fun trackCustomEvent(
        eventId: String,
        eventType: String,
        actualModel: String = "local-model-mini",
        metadata: Map<String, Any> = emptyMap()
    ) {
        if (!isEnabled.get()) return
        
        log("Tracking custom event: $eventType for $eventId")
        
        sendEvent(MetricEvent.Custom(
            completionId = eventId,
            customTool = eventType,
            actualModel = actualModel,
            elapsed = 0,
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
        if (!llmService.isConfigured()) {
            logger.debug("LLM service not configured, skipping metric: ${event.eventType}")
            return
        }
        
        try {
            val requestBody = event.toApiRequest()
            
            // Print event details for debugging
            log("Processing event: ${event.eventType}")
            log("  - completionId: ${event.completionId}")
            log("  - elapsed: ${event.elapsed}ms")
            when (event) {
                is MetricEvent.Select -> log("  - content length: ${event.completionContent.length}")
                is MetricEvent.Custom -> log("  - custom tool: ${event.customTool}")
                else -> {}
            }
            
            // Create a minimal LLM query params for metrics
            val params = LLMService.LLMQueryParams("")
                .withModel("local-model-mini")
                .withMaxTokens(1) // Minimal tokens for metrics
                .withMaxRetries(1) // Don't retry metrics too much
            
            // Determine the enum usage based on event type
            val enumUsage = when (event) {
                is MetricEvent.Custom -> {
                    // For custom events, extract the enum usage from the custom tool
                    if (event.customTool.contains("CODE_HEALTH_LOGGING")) {
                        "CODE_HEALTH_LOGGING"
                    } else {
                        "INLINE_COMPLETION_LOGGING"
                    }
                }
                else -> "INLINE_COMPLETION_LOGGING"
            }
            
            // Send the metric using a custom API call
            sendMetricToApi(requestBody, params, enumUsage)
            
            log("Sent metric with enumUsage: $enumUsage")
            
            logger.debug("Sent metric event: ${event.eventType} for ${event.completionId}")
            
        } catch (e: Exception) {
            logger.warn("Failed to send metric event: ${event.eventType}", e)
        }
    }
    
    /**
     * Send metric to API using LLMService infrastructure
     */
    private fun sendMetricToApi(requestBody: Map<String, Any>, params: LLMService.LLMQueryParams, enumUsage: String) {
        // This is a fire-and-forget operation
        scope.launch {
            try {
                // Use the extension method to send metrics
                val event = createMetricEventFromRequest(requestBody)
                llmService.sendMetricEvent(event, enumUsage)
            } catch (e: Exception) {
                // Log but don't fail - metrics are non-critical
                logger.debug("Metric submission failed", e)
            }
        }
    }
    
    /**
     * Create a MetricEvent from the request body map
     */
    private fun createMetricEventFromRequest(requestBody: Map<String, Any>): MetricEvent {
        val customTool = requestBody["custom_tool"] as String
        val eventType = customTool.substringAfterLast("|")
        val completionId = requestBody["completion_id"] as String
        val elapsed = (requestBody["elapsed"] as Number).toLong()
        val metadata = (requestBody["metadata"] as? Map<String, Any>) ?: emptyMap()
        val actualModel = requestBody["model"] as? String ?: "local-model-mini"
        
        return when {
            eventType == "request" -> MetricEvent.CompletionRequest(completionId, actualModel, elapsed, metadata)
            eventType == "response" -> MetricEvent.CompletionResponse(
                completionId = completionId,
                completionContent = requestBody["completion_content"] as? String ?: "",
                actualModel = actualModel,
                elapsed = elapsed,
                metadata = metadata
            )
            eventType == "view" -> MetricEvent.View(completionId, actualModel, elapsed, metadata)
            eventType == "tab" -> MetricEvent.Select(
                completionId = completionId,
                completionContent = requestBody["completion_content"] as? String ?: "",
                actualModel = actualModel,
                elapsed = elapsed,
                metadata = metadata
            )
            eventType == "anykey" -> MetricEvent.Dismiss(completionId, actualModel, elapsed, metadata)
            eventType == "esc" -> MetricEvent.Decline(completionId, actualModel, elapsed, metadata)
            customTool.contains("CODE_HEALTH_LOGGING") -> MetricEvent.Custom(
                completionId = completionId,
                customTool = customTool,
                actualModel = actualModel,
                elapsed = elapsed,
                metadata = metadata
            )
            else -> MetricEvent.CompletionRequest(completionId, actualModel, elapsed, metadata) // Default
        }
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
