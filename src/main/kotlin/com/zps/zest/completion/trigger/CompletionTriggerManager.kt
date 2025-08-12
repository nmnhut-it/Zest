package com.zps.zest.completion.trigger

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.zps.zest.completion.state.CompletionStateMachine
import kotlinx.coroutines.*

/**
 * Manages completion triggering with proper debouncing and state coordination
 * Replaces the scattered timer logic in the main service
 */
class CompletionTriggerManager(
    private val stateMachine: CompletionStateMachine,
    private val scope: CoroutineScope
) {
    private val logger = Logger.getInstance(CompletionTriggerManager::class.java)
    
    // Triggering configuration
    private var primaryDelayMs = 100L      // Quick trigger after typing stops
    private var secondaryDelayMs = 500L    // Backup trigger for missed completions
    private var maxRetryDelayMs = 2000L    // Maximum delay for retry attempts
    
    // Timer jobs
    private var primaryTimer: Job? = null
    private var secondaryTimer: Job? = null
    private var retryTimer: Job? = null
    
    // State tracking
    @Volatile
    private var lastTypingTimestamp = 0L
    @Volatile
    private var lastTriggerAttemptTimestamp = 0L
    
    // Configuration flags
    private var autoTriggerEnabled = true
    private var aggressiveRetryEnabled = true
    
    /**
     * Trigger completion request
     */
    fun interface CompletionTriggerCallback {
        fun triggerCompletion(editor: Editor, offset: Int, manually: Boolean)
    }
    
    private var triggerCallback: CompletionTriggerCallback? = null
    
    fun setTriggerCallback(callback: CompletionTriggerCallback) {
        this.triggerCallback = callback
    }
    
    fun setConfiguration(
        autoTriggerEnabled: Boolean,
        primaryDelayMs: Long = 100L,
        secondaryDelayMs: Long = 500L,
        aggressiveRetryEnabled: Boolean = true
    ) {
        this.autoTriggerEnabled = autoTriggerEnabled
        this.primaryDelayMs = primaryDelayMs
        this.secondaryDelayMs = secondaryDelayMs
        this.aggressiveRetryEnabled = aggressiveRetryEnabled
        log("Configuration updated: autoTrigger=$autoTriggerEnabled, primaryDelay=${primaryDelayMs}ms, secondaryDelay=${secondaryDelayMs}ms")
    }
    
    /**
     * Schedule completion after user activity (typing, caret movement)
     * This replaces the old scheduleNewCompletion method
     */
    fun scheduleCompletionAfterActivity(editor: Editor, reason: String = "user_activity") {
        lastTypingTimestamp = System.currentTimeMillis()
        
        log("scheduleCompletionAfterActivity: $reason", 1)
        
        if (!autoTriggerEnabled) {
            log("Auto-trigger disabled, not scheduling")
            return
        }
        
        // Cancel existing timers
        cancelAllTimers()
        
        // Don't schedule during acceptance
        if (stateMachine.isAccepting) {
            log("Currently accepting, not scheduling")
            return
        }
        
        // Don't schedule during cooldown
        if (stateMachine.isInCooldown) {
            log("In cooldown, not scheduling")
            return
        }
        
        // Start primary timer (quick trigger)
        schedulePrimaryTrigger(editor)
        
        // Start secondary timer (backup for missed completions)
        if (aggressiveRetryEnabled) {
            scheduleSecondaryTrigger(editor)
        }
    }
    
    /**
     * Request completion immediately (manual trigger)
     */
    fun requestCompletionNow(editor: Editor, offset: Int) {
        log("requestCompletionNow at offset $offset")
        
        // Cancel all timers since we're triggering manually
        cancelAllTimers()
        
        // Trigger immediately
        triggerCallback?.triggerCompletion(editor, offset, manually = true)
    }
    
    /**
     * Primary trigger - fires quickly after user stops typing
     */
    private fun schedulePrimaryTrigger(editor: Editor) {
        log("Scheduling primary trigger (${primaryDelayMs}ms)", 1)
        
        // Update state machine
        if (!stateMachine.handleEvent(CompletionStateMachine.Event.StartWaiting)) {
            log("Cannot start waiting state - invalid transition")
            return
        }
        
        primaryTimer = scope.launch {
            delay(primaryDelayMs)
            
            log("Primary trigger fired")
            
            // Check if user is still typing
            val timeSinceTyping = System.currentTimeMillis() - lastTypingTimestamp
            if (timeSinceTyping < primaryDelayMs - 10) {
                log("User still typing, primary trigger cancelled")
                return@launch
            }
            
            // Check state machine allows triggering
            if (!stateMachine.canTriggerCompletion()) {
                log("State machine doesn't allow triggering: ${stateMachine.currentState}")
                return@launch
            }
            
            // Trigger completion
            ApplicationManager.getApplication().invokeLater {
                try {
                    val currentOffset = editor.caretModel.offset
                    log("Primary trigger: requesting completion at offset $currentOffset")
                    lastTriggerAttemptTimestamp = System.currentTimeMillis()
                    triggerCallback?.triggerCompletion(editor, currentOffset, manually = false)
                } catch (e: Exception) {
                    log("ERROR in primary trigger: ${e.message}")
                    stateMachine.handleEvent(CompletionStateMachine.Event.Error("Primary trigger failed: ${e.message}"))
                }
            }
        }
    }
    
    /**
     * Secondary trigger - backup for missed completions, more aggressive
     */
    private fun scheduleSecondaryTrigger(editor: Editor) {
        log("Scheduling secondary trigger (${secondaryDelayMs}ms)", 1)
        
        secondaryTimer = scope.launch {
            delay(secondaryDelayMs)
            
            log("Secondary trigger fired")
            
            // Check if user stopped typing
            val timeSinceTyping = System.currentTimeMillis() - lastTypingTimestamp
            if (timeSinceTyping < secondaryDelayMs - 50) {
                log("User still typing, secondary trigger cancelled")
                return@launch
            }
            
            // More aggressive conditions for secondary trigger
            val shouldTrigger = when {
                // No completion exists and state allows
                stateMachine.canTriggerCompletion() -> true
                
                // Completion exists but might be stale
                stateMachine.isDisplaying -> {
                    val currentContext = stateMachine.currentContext
                    if (currentContext != null) {
                        val currentOffset = editor.caretModel.offset
                        val offsetDiff = kotlin.math.abs(currentOffset - currentContext.offset)
                        
                        // Context changed significantly - completion might be stale
                        if (offsetDiff > 3) {
                            log("Context changed significantly (offset diff: $offsetDiff), secondary trigger needed")
                            
                            // Clear stale completion and trigger new one
                            stateMachine.handleEvent(CompletionStateMachine.Event.Dismiss("stale_context"))
                            true
                        } else false
                    } else false
                }
                
                // Primary trigger failed to produce completion
                stateMachine.isIdle -> {
                    val timeSinceTriggerAttempt = System.currentTimeMillis() - lastTriggerAttemptTimestamp
                    if (timeSinceTriggerAttempt > 200L && timeSinceTriggerAttempt < secondaryDelayMs + 100L) {
                        log("Primary trigger didn't produce completion, secondary retry needed")
                        true
                    } else false
                }
                
                else -> false
            }
            
            if (shouldTrigger) {
                ApplicationManager.getApplication().invokeLater {
                    try {
                        val currentOffset = editor.caretModel.offset
                        log("Secondary trigger: requesting completion at offset $currentOffset")
                        triggerCallback?.triggerCompletion(editor, currentOffset, manually = false)
                    } catch (e: Exception) {
                        log("ERROR in secondary trigger: ${e.message}")
                    }
                }
            } else {
                log("Secondary trigger conditions not met")
            }
        }
    }
    
    /**
     * Schedule retry after failed completion attempt
     */
    fun scheduleRetryAfterFailure(editor: Editor, reason: String) {
        if (!aggressiveRetryEnabled) return
        
        log("Scheduling retry after failure: $reason")
        
        retryTimer = scope.launch {
            delay(maxRetryDelayMs)
            
            log("Retry trigger fired after failure")
            
            // Only retry if we're back to idle state and user stopped typing
            val timeSinceTyping = System.currentTimeMillis() - lastTypingTimestamp
            if (stateMachine.isIdle && timeSinceTyping > 1000L) {
                ApplicationManager.getApplication().invokeLater {
                    try {
                        val currentOffset = editor.caretModel.offset
                        log("Retry trigger: requesting completion at offset $currentOffset")
                        triggerCallback?.triggerCompletion(editor, currentOffset, manually = false)
                    } catch (e: Exception) {
                        log("ERROR in retry trigger: ${e.message}")
                    }
                }
            }
        }
    }
    
    /**
     * Cancel all active timers
     */
    fun cancelAllTimers() {
        primaryTimer?.cancel()
        secondaryTimer?.cancel()  
        retryTimer?.cancel()
        
        primaryTimer = null
        secondaryTimer = null
        retryTimer = null
        
        log("All timers cancelled", 1)
    }
    
    /**
     * Cancel only the primary timer (when manual trigger overrides)
     */
    fun cancelPrimaryTimer() {
        primaryTimer?.cancel()
        primaryTimer = null
        log("Primary timer cancelled", 1)
    }
    
    /**
     * Get current trigger state info
     */
    fun getTriggerInfo(): Map<String, Any> {
        val now = System.currentTimeMillis()
        return mapOf(
            "autoTriggerEnabled" to autoTriggerEnabled,
            "primaryDelayMs" to primaryDelayMs,
            "secondaryDelayMs" to secondaryDelayMs,
            "aggressiveRetryEnabled" to aggressiveRetryEnabled,
            "timeSinceLastTyping" to (now - lastTypingTimestamp),
            "timeSinceLastTriggerAttempt" to (now - lastTriggerAttemptTimestamp),
            "primaryTimerActive" to (primaryTimer?.isActive == true),
            "secondaryTimerActive" to (secondaryTimer?.isActive == true),
            "retryTimerActive" to (retryTimer?.isActive == true)
        )
    }
    
    /**
     * Dispose and clean up resources
     */
    fun dispose() {
        log("Disposing CompletionTriggerManager")
        cancelAllTimers()
        triggerCallback = null
    }
    
    private fun log(message: String, level: Int = 0) {
        val prefix = if (level > 0) "[VERBOSE]" else ""
        println("$prefix[TriggerManager] $message")
        logger.debug("$prefix[TriggerManager] $message")
    }
}