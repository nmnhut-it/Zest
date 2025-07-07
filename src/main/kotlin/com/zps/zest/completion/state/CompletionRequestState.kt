package com.zps.zest.completion.state

import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages request state and rate limiting for completion requests
 */
class CompletionRequestState {
    enum class RequestState {
        IDLE,           // No request active
        WAITING,        // Timer active, will request soon
        REQUESTING,     // Provider call in progress
        DISPLAYING      // Completion shown
    }

    @Volatile
    var currentRequestState = RequestState.IDLE

    // Request tracking to prevent multiple concurrent requests
    private val requestGeneration = AtomicInteger(0)
    @Volatile
    var activeRequestId: Int? = null

    // Track the last request time for rate limiting
    @Volatile
    private var lastRequestTimestamp = 0L
    private val MIN_REQUEST_INTERVAL_MS = 500L

    // Dynamic rate limiting (thread-safe)
    private val requestHistory = Collections.synchronizedList(mutableListOf<Long>())
    private val REQUEST_HISTORY_WINDOW_MS = 60_000L // 1 minute window
    private val MAX_REQUESTS_PER_MINUTE = 30

    fun generateNewRequestId(): Int {
        return requestGeneration.incrementAndGet()
    }

    fun isRateLimited(): Boolean {
        val now = System.currentTimeMillis()

        synchronized(requestHistory) {
            requestHistory.removeAll { now - it > REQUEST_HISTORY_WINDOW_MS }

            if (requestHistory.size >= MAX_REQUESTS_PER_MINUTE) {
                return true
            }
        }

        val timeSinceLastRequest = now - lastRequestTimestamp
        return timeSinceLastRequest < MIN_REQUEST_INTERVAL_MS
    }

    fun recordRequest() {
        val now = System.currentTimeMillis()
        lastRequestTimestamp = now
        synchronized(requestHistory) {
            requestHistory.add(now)
        }
    }

    fun getRequestCount(): Int {
        val now = System.currentTimeMillis()
        synchronized(requestHistory) {
            requestHistory.removeAll { now - it > REQUEST_HISTORY_WINDOW_MS }
            return requestHistory.size
        }
    }

    fun getTimeSinceLastRequest(): Long {
        return System.currentTimeMillis() - lastRequestTimestamp
    }

    fun reset() {
        currentRequestState = RequestState.IDLE
        activeRequestId = null
        lastRequestTimestamp = 0L
        requestHistory.clear()
    }
}
