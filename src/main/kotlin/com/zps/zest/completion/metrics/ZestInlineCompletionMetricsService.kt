package com.zps.zest.completion.metrics

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.zps.zest.langchain4j.util.LLMService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import java.util.UUID
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
    
    // Event queue for batching
    private val eventChannel = Channel<MetricEvent>(Channel.UNLIMITED)
    
    // Track active completions
    private val activeCompletions = ConcurrentHashMap<String, CompletionSession>()
    
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
        contextInfo: Map<String, Any> = emptyMap()
    ) {
        if (!isEnabled.get()) return
        
        println("[ZestMetrics] Tracking completion requested: $completionId")
        
        val session = CompletionSession(
            completionId = completionId,
            startTime = System.currentTimeMillis(),
            strategy = strategy,
            fileType = fileType,
            contextInfo = contextInfo
        )
        
        activeCompletions[completionId] = session
        
        sendEvent(MetricEvent.Complete(
            completionId = completionId,
            elapsed = 0,
            metadata = mapOf(
                "strategy" to strategy,
                "file_type" to fileType
            ) + contextInfo
        ))
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
        val elapsed = System.currentTimeMillis() - session.startTime
        
        session.viewedAt = System.currentTimeMillis()
        session.completionLength = completionLength
        session.confidence = confidence
        
        sendEvent(MetricEvent.View(
            completionId = completionId,
            elapsed = elapsed,
            metadata = mapOf(
                "completion_length" to completionLength,
                "completion_line_count" to completionLineCount,
                "confidence" to (confidence ?: 0f)
            )
        ))
    }
    
    /**
     * Track when a completion is selected (highlighted)
     */
    fun trackCompletionSelected(completionId: String) {
        if (!isEnabled.get()) return
        
        val session = activeCompletions[completionId] ?: return
        val elapsed = System.currentTimeMillis() - session.startTime
        
        sendEvent(MetricEvent.Select(
            completionId = completionId,
            elapsed = elapsed
        ))
    }
    
    /**
     * Track when a completion is dismissed
     */
    fun trackCompletionDismissed(
        completionId: String,
        reason: String = "user_action"
    ) {
        if (!isEnabled.get()) return
        
        val session = activeCompletions[completionId] ?: return
        val elapsed = System.currentTimeMillis() - session.startTime
        
        sendEvent(MetricEvent.Dismiss(
            completionId = completionId,
            elapsed = elapsed,
            metadata = mapOf("reason" to reason)
        ))
        
        // Clean up session after a delay
        cleanupSessionDelayed(completionId)
    }
    
    /**
     * Track when a completion is declined
     */
    fun trackCompletionDeclined(
        completionId: String,
        reason: String = "user_typed_different"
    ) {
        if (!isEnabled.get()) return
        
        val session = activeCompletions[completionId] ?: return
        val elapsed = System.currentTimeMillis() - session.startTime
        
        sendEvent(MetricEvent.Decline(
            completionId = completionId,
            elapsed = elapsed,
            metadata = mapOf("reason" to reason)
        ))
        
        // Clean up session after a delay
        cleanupSessionDelayed(completionId)
    }
    
    /**
     * Track when a completion is accepted
     */
    fun trackCompletionAccepted(
        completionId: String,
        completionContent: String,
        acceptType: String = "full",
        userAction: String = "tab"
    ) {
        if (!isEnabled.get()) return
        
        val session = activeCompletions[completionId] ?: return
        val elapsed = System.currentTimeMillis() - session.startTime
        
        sendEvent(MetricEvent.Completed(
            completionId = completionId,
            completionContent = completionContent,
            elapsed = elapsed,
            metadata = mapOf(
                "accept_type" to acceptType,
                "user_action" to userAction,
                "strategy" to session.strategy,
                "file_type" to session.fileType,
                "view_to_accept_time" to (session.viewedAt?.let { System.currentTimeMillis() - it } ?: 0L)
            )
        ))
        
        // Clean up session after a delay
        cleanupSessionDelayed(completionId)
    }
    
    /**
     * Send an event to the processing queue
     */
    private fun sendEvent(event: MetricEvent) {
        scope.launch {
            try {
                eventChannel.send(event)
                println("[ZestMetrics] Queued event: ${event.eventType} for ${event.completionId}")
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
            println("[ZestMetrics] Processing event: ${event.eventType}")
            println("  - completionId: ${event.completionId}")
            println("  - elapsed: ${event.elapsed}ms")
            when (event) {
                is MetricEvent.Completed -> println("  - content length: ${event.completionContent.length}")
                else -> {}
            }
            
            // Create a minimal LLM query params for metrics
            val params = LLMService.LLMQueryParams("")
                .withModel("local-model-mini")
                .withMaxTokens(1) // Minimal tokens for metrics
                .withMaxRetries(1) // Don't retry metrics too much
            
            // Send the metric using a custom API call
            sendMetricToApi(requestBody, params)
            
            logger.debug("Sent metric event: ${event.eventType} for ${event.completionId}")
            
        } catch (e: Exception) {
            logger.warn("Failed to send metric event: ${event.eventType}", e)
        }
    }
    
    /**
     * Send metric to API using LLMService infrastructure
     */
    private fun sendMetricToApi(requestBody: Map<String, Any>, params: LLMService.LLMQueryParams) {
        // This is a fire-and-forget operation
        scope.launch {
            try {
                // Use the extension method to send metrics
                val event = createMetricEventFromRequest(requestBody)
                llmService.sendMetricEvent(event)
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
        val eventType = (requestBody["custom_tool"] as String).substringAfterLast("|")
        val completionId = requestBody["completion_id"] as String
        val elapsed = (requestBody["elapsed"] as Number).toLong()
        val metadata = (requestBody["metadata"] as? Map<String, Any>) ?: emptyMap()
        
        return when (eventType) {
            "complete" -> MetricEvent.Complete(completionId, elapsed, metadata)
            "view" -> MetricEvent.View(completionId, elapsed, metadata)
            "select" -> MetricEvent.Select(completionId, elapsed, metadata)
            "dismiss" -> MetricEvent.Dismiss(completionId, elapsed, metadata)
            "decline" -> MetricEvent.Decline(completionId, elapsed, metadata)
            "completed" -> MetricEvent.Completed(
                completionId = completionId,
                completionContent = requestBody["completion_content"] as? String ?: "",
                elapsed = elapsed,
                metadata = metadata
            )
            else -> MetricEvent.Complete(completionId, elapsed, metadata) // Default
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
     * Clear all active sessions (for testing/debugging)
     */
    fun clearAllSessions() {
        activeCompletions.clear()
        logger.info("Cleared all active completion sessions")
    }
    
    override fun dispose() {
        logger.info("Disposing ZestInlineCompletionMetricsService")
        processingJob?.cancel()
        scope.cancel()
        eventChannel.close()
        activeCompletions.clear()
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
