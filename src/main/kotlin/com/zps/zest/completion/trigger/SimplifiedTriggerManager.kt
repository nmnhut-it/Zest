package com.zps.zest.completion.trigger

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import kotlinx.coroutines.*

/**
 * Simplified trigger manager that works with the new state machine.
 * Handles debouncing and scheduling of completion requests.
 */
class SimplifiedTriggerManager(
    private val scope: CoroutineScope,
    private val canTrigger: () -> Boolean,
    private val isAccepting: () -> Boolean
) {
    private val logger = Logger.getInstance(SimplifiedTriggerManager::class.java)
    
    // Configuration
    private var primaryDelayMs = 300L
    private var secondaryDelayMs = 800L
    private var autoTriggerEnabled = true
    
    // Timer jobs
    private var primaryTimer: Job? = null
    private var secondaryTimer: Job? = null
    
    // Tracking
    @Volatile
    private var lastTypingTimestamp = 0L
    
    // Callback for triggering completion
    fun interface CompletionTriggerCallback {
        fun triggerCompletion(editor: Editor, offset: Int, manually: Boolean)
    }
    
    private var triggerCallback: CompletionTriggerCallback? = null
    
    fun setTriggerCallback(callback: CompletionTriggerCallback) {
        this.triggerCallback = callback
    }
    
    fun setConfiguration(
        autoTriggerEnabled: Boolean,
        primaryDelayMs: Long = 300L,
        secondaryDelayMs: Long = 800L
    ) {
        this.autoTriggerEnabled = autoTriggerEnabled
        this.primaryDelayMs = primaryDelayMs
        this.secondaryDelayMs = secondaryDelayMs
    }
    
    /**
     * Schedule completion after user activity (typing, caret movement)
     */
    fun scheduleCompletionAfterActivity(editor: Editor, reason: String = "user_activity") {
        lastTypingTimestamp = System.currentTimeMillis()
        
        if (!autoTriggerEnabled) {
            return
        }
        
        // Cancel existing timers
        cancelAllTimers()
        
        // Don't schedule if we're accepting
        if (isAccepting()) {
            return
        }
        
        // Don't schedule if we can't trigger
        if (!canTrigger()) {
            return
        }
        
        // Schedule primary timer
        primaryTimer = scope.launch {
            delay(primaryDelayMs)
            
            // Check if user stopped typing
            val timeSinceTyping = System.currentTimeMillis() - lastTypingTimestamp
            if (timeSinceTyping >= primaryDelayMs - 10) {
                ApplicationManager.getApplication().invokeLater {
                    if (canTrigger()) {
                        val currentOffset = editor.caretModel.offset
                        triggerCallback?.triggerCompletion(editor, currentOffset, manually = false)
                    }
                }
            }
        }
        
        // Schedule secondary timer as backup
        secondaryTimer = scope.launch {
            delay(secondaryDelayMs)
            
            val timeSinceTyping = System.currentTimeMillis() - lastTypingTimestamp
            if (timeSinceTyping >= secondaryDelayMs - 50) {
                ApplicationManager.getApplication().invokeLater {
                    if (canTrigger()) {
                        val currentOffset = editor.caretModel.offset
                        triggerCallback?.triggerCompletion(editor, currentOffset, manually = false)
                    }
                }
            }
        }
    }
    
    /**
     * Request completion immediately (manual trigger)
     */
    fun requestCompletionNow(editor: Editor, offset: Int) {
        cancelAllTimers()
        triggerCallback?.triggerCompletion(editor, offset, manually = true)
    }
    
    /**
     * Cancel all active timers
     */
    fun cancelAllTimers() {
        primaryTimer?.cancel()
        secondaryTimer?.cancel()
        primaryTimer = null
        secondaryTimer = null
    }
    
    /**
     * Dispose and clean up resources
     */
    fun dispose() {
        cancelAllTimers()
        triggerCallback = null
    }
}