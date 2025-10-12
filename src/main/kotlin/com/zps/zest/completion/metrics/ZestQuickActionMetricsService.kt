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

/**
 * Service responsible for tracking and sending quick action metrics
 * Handles event collection, batching, and asynchronous submission to the API
 */
@Service(Service.Level.PROJECT)
class ZestQuickActionMetricsService(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(ZestQuickActionMetricsService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Settings and HTTP client
    private val settings by lazy { ZestGlobalSettings.getInstance() }
    private val metricsHttpClient = MetricsHttpClient(project)

    // Event queue for batching (bounded to prevent OOM)
    private val eventChannel by lazy {
        Channel<MetricEvent>(settings.metricsMaxQueueSize)
    }

    // Track active rewrites
    private val activeRewrites = ConcurrentHashMap<String, RewriteSession>()

    // Service components
    private var processingJob: Job? = null
    
    init {
        logger.info("Initializing ZestQuickActionMetricsService")
        startEventProcessor()
    }
    
    /**
     * Track when a quick action is requested
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
        
        val baseBuilder = MetricsUtils.createBaseMetadata(project, actualModel)
        sendEvent(MetricEvent.QuickActionRequest(
            completionId = rewriteId,
            actualModel = actualModel,
            elapsed = 0,
            metadata = baseBuilder.buildQuickActionRequest(
                methodName = methodName,
                language = language,
                fileType = fileType,
                hasCustomInstruction = customInstruction != null
            )
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
        
        val baseBuilder = MetricsUtils.createBaseMetadata(project, session.actualModel)
        sendEvent(MetricEvent.QuickActionResponse(
            completionId = rewriteId,
            completionContent = rewrittenContent,
            actualModel = session.actualModel,
            elapsed = elapsed,
            metadata = baseBuilder.buildQuickActionResponse(
                methodName = session.methodName,
                language = session.language,
                responseTimeMs = responseTime,
                contentLength = rewrittenContent.length
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
        
        val baseBuilder = MetricsUtils.createBaseMetadata(project, session.actualModel)
        sendEvent(MetricEvent.QuickActionView(
            completionId = rewriteId,
            actualModel = session.actualModel,
            elapsed = elapsed,
            metadata = baseBuilder.buildQuickActionView(
                methodName = session.methodName,
                language = session.language,
                diffChanges = diffChanges,
                confidence = confidence
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
        
        val baseBuilder = MetricsUtils.createBaseMetadata(project, session.actualModel)
        sendEvent(MetricEvent.QuickActionSelect(
            completionId = rewriteId,
            completionContent = acceptedContent,
            actualModel = session.actualModel,
            elapsed = elapsed,
            metadata = baseBuilder.buildQuickActionAccept(
                methodName = session.methodName,
                language = session.language,
                viewToAcceptTimeMs = viewToAcceptTime,
                contentLength = acceptedContent.length,
                userAction = MetricsUtils.parseUserAction(userAction)
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
        
        val baseBuilder = MetricsUtils.createBaseMetadata(project, session.actualModel)
        sendEvent(MetricEvent.QuickActionDecline(
            completionId = rewriteId,
            actualModel = session.actualModel,
            elapsed = elapsed,
            metadata = baseBuilder.buildQuickActionReject(
                methodName = session.methodName,
                reason = MetricsUtils.parseRejectReason(reason)
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
        
        val baseBuilder = MetricsUtils.createBaseMetadata(project, session.actualModel)
        sendEvent(MetricEvent.QuickActionDismiss(
            completionId = rewriteId,
            actualModel = session.actualModel,
            elapsed = elapsed,
            metadata = baseBuilder.buildQuickActionDismiss(
                methodName = session.methodName,
                reason = reason,
                wasViewed = session.viewedAt != null
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
                logger.debug("Queued quick action event: ${event.eventType} for ${event.completionId}")
            } catch (e: Exception) {
                logger.warn("Failed to queue quick action event", e)
            }
        }
    }
    
    /**
     * Start the event processor coroutine
     */
    private fun startEventProcessor() {
        processingJob = scope.launch {
            logger.info("Starting quick action event processor")
            
            eventChannel.consumeEach { event ->
                try {
                    processEvent(event)
                } catch (e: Exception) {
                    logger.error("Error processing quick action event", e)
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
            // Determine endpoint
            val endpoint = MetricsEndpoint.fromUsage("QUICK_ACTION_LOGGING")

            // Serialize event to JSON
            val json = MetricsSerializer.serialize(event)

            // Send via HTTP client
            val success = metricsHttpClient.sendMetric(endpoint, event.eventType, json)

            if (success) {
                logger.debug("Successfully sent quick action metric: ${event.eventType}")
            } else {
                logger.debug("Failed to send quick action metric: ${event.eventType}")
            }

        } catch (e: Exception) {
            logger.warn("Error processing quick action metric: ${event.eventType}", e)
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
        logger.info("Disposing ZestQuickActionMetricsService")
        processingJob?.cancel()
        scope.cancel()
        eventChannel.close()
        activeRewrites.clear()
    }
    
    companion object {
        fun getInstance(project: Project): ZestQuickActionMetricsService {
            return project.getService(ZestQuickActionMetricsService::class.java)
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