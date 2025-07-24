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

/**
 * Service responsible for tracking and sending block rewrite metrics
 * Handles event collection, batching, and asynchronous submission to the API
 */
@Service(Service.Level.PROJECT)
class ZestBlockRewriteMetricsService(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(ZestBlockRewriteMetricsService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Event queue for batching
    private val eventChannel = Channel<MetricEvent>(Channel.UNLIMITED)
    
    // Track active rewrites
    private val activeRewrites = ConcurrentHashMap<String, RewriteSession>()
    
    // Service components
    private var processingJob: Job? = null
    private val llmService by lazy { LLMService(project) }
    
    init {
        logger.info("Initializing ZestBlockRewriteMetricsService")
        startEventProcessor()
    }
    
    /**
     * Track when a block rewrite is requested
     */
    fun trackRewriteRequested(
        rewriteId: String,
        methodName: String,
        language: String,
        fileType: String,
        actualModel: String,
        customInstruction: String? = null,
        contextInfo: Map<String, Any> = emptyMap()
    ) {
        logger.debug("Tracking rewrite requested: $rewriteId for method $methodName")
        
        val session = RewriteSession(
            rewriteId = rewriteId,
            startTime = System.currentTimeMillis(),
            methodName = methodName,
            language = language,
            fileType = fileType,
            actualModel = actualModel,
            customInstruction = customInstruction,
            contextInfo = contextInfo
        )
        
        activeRewrites[rewriteId] = session
        
        sendEvent(MetricEvent.CompletionRequest(
            completionId = rewriteId,
            actualModel = actualModel,
            elapsed = 0,
            metadata = mapOf(
                "method_name" to methodName,
                "language" to language,
                "file_type" to fileType,
                "has_custom_instruction" to (customInstruction != null)
            ) + contextInfo
        ))
    }
    
    /**
     * Track when LLM responds with rewritten code
     */
    fun trackRewriteResponse(
        rewriteId: String,
        responseTime: Long,
        rewrittenContent: String,
        success: Boolean = true
    ) {
        val session = activeRewrites[rewriteId] ?: return
        val elapsed = System.currentTimeMillis() - session.startTime
        
        sendEvent(MetricEvent.CompletionResponse(
            completionId = rewriteId,
            completionContent = rewrittenContent,
            actualModel = session.actualModel,
            elapsed = elapsed,
            metadata = mapOf(
                "response_time" to responseTime,
                "method_name" to session.methodName,
                "language" to session.language,
                "content_length" to rewrittenContent.length
            )
        ))
    }
    
    /**
     * Track when the diff is displayed to user
     */
    fun trackRewriteViewed(
        rewriteId: String,
        diffChanges: Int,
        confidence: Float
    ) {
        val session = activeRewrites[rewriteId] ?: return
        val elapsed = System.currentTimeMillis() - session.startTime
        
        session.viewedAt = System.currentTimeMillis()
        
        sendEvent(MetricEvent.View(
            completionId = rewriteId,
            actualModel = session.actualModel,
            elapsed = elapsed,
            metadata = mapOf(
                "diff_changes" to diffChanges,
                "confidence" to confidence,
                "method_name" to session.methodName,
                "language" to session.language
            )
        ))
    }
    
    /**
     * Track when user accepts the rewrite (TAB)
     */
    fun trackRewriteAccepted(
        rewriteId: String,
        acceptedContent: String,
        userAction: String = "tab"
    ) {
        val session = activeRewrites[rewriteId] ?: return
        val elapsed = System.currentTimeMillis() - session.startTime
        val viewToAcceptTime = session.viewedAt?.let { System.currentTimeMillis() - it } ?: 0L
        
        sendEvent(MetricEvent.Select(
            completionId = rewriteId,
            completionContent = acceptedContent,
            actualModel = session.actualModel,
            elapsed = elapsed,
            metadata = mapOf(
                "method_name" to session.methodName,
                "language" to session.language,
                "view_to_accept_time" to viewToAcceptTime,
                "content_length" to acceptedContent.length,
                "user_action" to userAction
            )
        ))
        
        cleanupSession(rewriteId)
    }
    
    /**
     * Track when user rejects the rewrite (ESC)
     */
    fun trackRewriteRejected(
        rewriteId: String,
        reason: String = "esc_pressed"
    ) {
        val session = activeRewrites[rewriteId] ?: return
        val elapsed = System.currentTimeMillis() - session.startTime
        
        sendEvent(MetricEvent.Decline(
            completionId = rewriteId,
            actualModel = session.actualModel,
            elapsed = elapsed,
            metadata = mapOf(
                "reason" to reason,
                "method_name" to session.methodName
            )
        ))
        
        cleanupSession(rewriteId)
    }
    
    /**
     * Track when rewrite is cancelled
     */
    fun trackRewriteCancelled(
        rewriteId: String,
        reason: String = "cancelled"
    ) {
        val session = activeRewrites[rewriteId] ?: return
        val elapsed = System.currentTimeMillis() - session.startTime
        
        sendEvent(MetricEvent.Dismiss(
            completionId = rewriteId,
            actualModel = session.actualModel,
            elapsed = elapsed,
            metadata = mapOf(
                "reason" to reason,
                "method_name" to session.methodName,
                "was_viewed" to (session.viewedAt != null)
            )
        ))
        
        cleanupSession(rewriteId)
    }
    
    /**
     * Send an event to the processing queue
     */
    private fun sendEvent(event: MetricEvent) {
        scope.launch {
            try {
                eventChannel.send(event)
                logger.debug("Queued block rewrite event: ${event.eventType} for ${event.completionId}")
            } catch (e: Exception) {
                logger.warn("Failed to queue block rewrite event", e)
            }
        }
    }
    
    /**
     * Start the event processor coroutine
     */
    private fun startEventProcessor() {
        processingJob = scope.launch {
            logger.info("Starting block rewrite event processor")
            
            eventChannel.consumeEach { event ->
                try {
                    processEvent(event)
                } catch (e: Exception) {
                    logger.error("Error processing block rewrite event", e)
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
            // Send metric using BLOCK_REWRITE_LOGGING enum
            llmService.sendMetricEvent(event, "BLOCK_REWRITE_LOGGING")
            logger.debug("Sent block rewrite metric: ${event.eventType} for ${event.completionId}")
        } catch (e: Exception) {
            logger.warn("Failed to send block rewrite metric: ${event.eventType}", e)
        }
    }
    
    /**
     * Clean up a rewrite session
     */
    private fun cleanupSession(rewriteId: String) {
        scope.launch {
            delay(60000) // Keep session for 1 minute for potential debugging
            activeRewrites.remove(rewriteId)
            logger.debug("Cleaned up rewrite session: $rewriteId")
        }
    }
    
    override fun dispose() {
        logger.info("Disposing ZestBlockRewriteMetricsService")
        processingJob?.cancel()
        scope.cancel()
        eventChannel.close()
        activeRewrites.clear()
    }
    
    companion object {
        fun getInstance(project: Project): ZestBlockRewriteMetricsService {
            return project.getService(ZestBlockRewriteMetricsService::class.java)
        }
    }
    
    /**
     * Data class for tracking rewrite sessions
     */
    private data class RewriteSession(
        val rewriteId: String,
        val startTime: Long,
        val methodName: String,
        val language: String,
        val fileType: String,
        val actualModel: String,
        val customInstruction: String?,
        val contextInfo: Map<String, Any>,
        var viewedAt: Long? = null
    )
}