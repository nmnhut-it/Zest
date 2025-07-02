package com.zps.zest.completion

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.zps.zest.browser.utils.ChatboxUtilities
import com.zps.zest.langchain4j.util.LLMService
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wrapper for LLMService that adds cancellation support
 */
class CancellableLLMRequest(private val project: Project) {
    private val logger = Logger.getInstance(CancellableLLMRequest::class.java)
    private val llmService by lazy { LLMService(project) }
    
    /**
     * Execute an LLM query with cancellation support
     * @param params The query parameters
     * @param usage The usage type for metrics
     * @param cancellationToken A function that returns true if the request should be cancelled
     * @return The LLM response or null if cancelled/failed
     */
    suspend fun queryWithCancellation(
        params: LLMService.LLMQueryParams,
        usage: ChatboxUtilities.EnumUsage,
        cancellationToken: () -> Boolean = { false }
    ): String? = withContext(Dispatchers.IO) {
        val isCancelled = AtomicBoolean(false)
        
        // Start the LLM query in a separate coroutine
        val queryJob = async {
            try {
                // Check cancellation before starting
                if (cancellationToken()) {
                    logger.debug("LLM request cancelled before starting")
                    return@async null
                }
                
                // Execute the query
                val result = llmService.queryWithParams(params, usage)
                
                // Check cancellation after completion
                if (cancellationToken() || isCancelled.get()) {
                    logger.debug("LLM request cancelled after completion")
                    return@async null
                }
                
                result
            } catch (e: Exception) {
                if (e is CancellationException) {
                    logger.debug("LLM request coroutine cancelled")
                    null
                } else {
                    logger.warn("LLM request failed", e)
                    null
                }
            }
        }
        
        // Monitor for cancellation in a separate coroutine
        val monitorJob = launch {
            while (isActive && queryJob.isActive) {
                if (cancellationToken()) {
                    logger.debug("Cancellation requested, cancelling LLM query")
                    isCancelled.set(true)
                    queryJob.cancel()
                    break
                }
                delay(100) // Check every 100ms
            }
        }
        
        try {
            // Wait for the query to complete
            val result = queryJob.await()
            monitorJob.cancel() // Stop monitoring
            result
        } finally {
            // Ensure both jobs are cancelled
            queryJob.cancel()
            monitorJob.cancel()
        }
    }
    
    /**
     * Create a cancellable request with a specific request ID
     * The request will be automatically cancelled if the ID changes
     */
    fun createCancellableRequest(
        requestId: Int,
        activeRequestIdProvider: () -> Int?
    ): CancellableRequest {
        return CancellableRequest(requestId, activeRequestIdProvider)
    }
    
    inner class CancellableRequest(
        private val requestId: Int,
        private val activeRequestIdProvider: () -> Int?
    ) {
        suspend fun query(
            params: LLMService.LLMQueryParams,
            usage: ChatboxUtilities.EnumUsage
        ): String? {
            return queryWithCancellation(params, usage) {
                // Cancel if the active request ID has changed
                val currentActiveId = activeRequestIdProvider()
                currentActiveId != requestId
            }
        }
    }
}
