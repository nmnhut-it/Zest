package com.zps.zest.completion.state

import kotlinx.coroutines.Job

/**
 * Manages acceptance state for completion operations
 */
class CompletionAcceptanceState {
    // Flag to track programmatic edits (e.g., when accepting completions)
    @Volatile
    var isProgrammaticEdit = false

    // Timestamp of last accepted completion to implement cooldown
    @Volatile
    var lastAcceptedTimestamp = 0L
    private val ACCEPTANCE_COOLDOWN_MS = 3000L // 3 seconds cooldown

    // Track the last accepted text to avoid re-suggesting the same completion
    @Volatile
    var lastAcceptedText: String? = null

    // Flag to completely disable all completion activities during acceptance
    @Volatile
    var isAcceptingCompletion = false

    // Acceptance timeout protection
    private val ACCEPTANCE_TIMEOUT_MS = 10000L // 10 seconds max
    var acceptanceTimeoutJob: Job? = null

    fun isInCooldown(): Boolean {
        val timeSinceAccept = System.currentTimeMillis() - lastAcceptedTimestamp
        return timeSinceAccept < ACCEPTANCE_COOLDOWN_MS
    }

    fun getTimeSinceLastAcceptance(): Long {
        return System.currentTimeMillis() - lastAcceptedTimestamp
    }

    fun startAcceptance(text: String) {
        isAcceptingCompletion = true
        isProgrammaticEdit = true
        lastAcceptedTimestamp = System.currentTimeMillis()
        lastAcceptedText = text
    }

    fun checkAndFixStuckState(): Boolean {
        val timeSinceAccept = getTimeSinceLastAcceptance()
        val isStuck = isAcceptingCompletion && timeSinceAccept > 3000L

        if (isStuck) {
            // Force reset all flags
            isAcceptingCompletion = false
            isProgrammaticEdit = false
            acceptanceTimeoutJob?.cancel()
            acceptanceTimeoutJob = null
            return true
        }
        return false
    }

    fun reset() {
        isAcceptingCompletion = false
        isProgrammaticEdit = false
        lastAcceptedTimestamp = 0L
        lastAcceptedText = null
        acceptanceTimeoutJob?.cancel()
        acceptanceTimeoutJob = null
    }

    fun getCooldownMs(): Long = ACCEPTANCE_COOLDOWN_MS
    fun getTimeoutMs(): Long = ACCEPTANCE_TIMEOUT_MS
}
