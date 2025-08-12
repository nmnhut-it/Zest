package com.zps.zest.completion.state

import com.intellij.openapi.diagnostic.Logger
import com.zps.zest.completion.data.CompletionContext
import com.zps.zest.completion.data.ZestInlineCompletionItem
import java.util.concurrent.atomic.AtomicLong

/**
 * Simplified state machine using self-managing states.
 * The state machine only coordinates transitions - each state manages itself.
 */
class CompletionStateMachine(
    private val stateContext: StateContext
) {
    private val logger = Logger.getInstance(CompletionStateMachine::class.java)
    private val requestIdGenerator = AtomicLong(0)
    
    @Volatile
    private var _currentState: CompletionState = CompletionState.Idle
    
    // Listeners for debugging and monitoring
    private val listeners = mutableListOf<StateTransitionListener>()
    
    interface StateTransitionListener {
        fun onStateChanged(
            oldState: CompletionState, 
            newState: CompletionState, 
            event: CompletionEvent
        )
    }
    
    /**
     * Handle an event by delegating to the current state.
     * Returns true if a state transition occurred.
     */
    @Synchronized
    fun handleEvent(event: CompletionEvent): Boolean {
        val oldState = _currentState
        val newState = oldState.handleEvent(event)
        
        if (newState !== oldState) {
            logger.debug("State transition: $oldState -> $newState (event: ${event.javaClass.simpleName})")
            
            // Exit old state
            try {
                oldState.onExit(stateContext)
            } catch (e: Exception) {
                logger.error("Error in onExit for state $oldState", e)
            }
            
            // Transition
            _currentState = newState
            
            // Enter new state
            try {
                newState.onEnter(stateContext)
            } catch (e: Exception) {
                logger.error("Error in onEnter for state $newState", e)
                // If entering new state fails, go to Idle as safe fallback
                if (newState !== CompletionState.Idle) {
                    _currentState = CompletionState.Idle
                    _currentState.onEnter(stateContext)
                }
            }
            
            // Notify listeners for debugging/monitoring
            notifyListeners(oldState, newState, event)
            
            return true
        }
        
        return false
    }
    
    /**
     * Generate a new unique request ID
     */
    fun generateRequestId(): Int = requestIdGenerator.incrementAndGet().toInt()
    
    /**
     * Request a new completion
     */
    fun requestCompletion(context: CompletionContext): Boolean {
        val requestId = generateRequestId()
        return handleEvent(CompletionEvent.RequestCompletion(requestId, context))
    }
    
    /**
     * Accept the current completion
     */
    fun acceptCompletion(acceptType: String = "TAB"): Boolean {
        return handleEvent(CompletionEvent.AcceptRequested(acceptType))
    }
    
    /**
     * Dismiss the current completion
     */
    fun dismiss(): Boolean {
        return handleEvent(CompletionEvent.Dismiss)
    }
    
    /**
     * Force reset to idle state
     */
    fun reset() {
        handleEvent(CompletionEvent.Reset)
    }
    
    // Simple getters for UI and actions
    
    /**
     * Check if we're ready to accept a completion
     */
    val canAccept: Boolean 
        get() = _currentState is CompletionState.Displaying
    
    /**
     * Check if we're currently requesting
     */
    val isRequesting: Boolean 
        get() = _currentState is CompletionState.Requesting
    
    /**
     * Check if we're currently accepting
     */
    val isAccepting: Boolean 
        get() = _currentState is CompletionState.Accepting
    
    /**
     * Check if we're idle
     */
    val isIdle: Boolean 
        get() = _currentState === CompletionState.Idle
    
    /**
     * Check if we can trigger a new completion
     */
    val canTrigger: Boolean
        get() = _currentState === CompletionState.Idle || _currentState is CompletionState.Ready || _currentState is CompletionState.Displaying
    
    /**
     * Get the current state (for V2 service compatibility)
     */
    val currentState: CompletionState
        get() = _currentState
    
    /**
     * Get the current completion if available
     */
    val currentCompletion: ZestInlineCompletionItem? 
        get() = when (val state = _currentState) {
            is CompletionState.Ready -> state.completion
            is CompletionState.Displaying -> state.completion
            is CompletionState.Accepting -> state.completion
            else -> null
        }
    
    /**
     * Get the current context if available
     */
    val currentContext: CompletionContext? 
        get() = when (val state = _currentState) {
            is CompletionState.Requesting -> state.context
            is CompletionState.Ready -> state.context
            is CompletionState.Displaying -> state.context
            is CompletionState.Accepting -> state.context
            else -> null
        }
    
    /**
     * Get the active request ID if any
     */
    val activeRequestId: Int? 
        get() = when (val state = _currentState) {
            is CompletionState.Requesting -> state.requestId
            is CompletionState.Ready -> state.requestId
            is CompletionState.Displaying -> state.requestId
            else -> null
        }
    
    /**
     * Get current state name for debugging
     */
    val currentStateName: String 
        get() = _currentState.toString()
    
    /**
     * Get detailed state info for debugging
     */
    fun getStateInfo(): Map<String, Any> {
        val state = _currentState
        val baseInfo = mutableMapOf<String, Any>(
            "stateName" to state.toString(),
            "timestamp" to System.currentTimeMillis()
        )
        
        when (state) {
            is CompletionState.Requesting -> {
                baseInfo["requestId"] = state.requestId
                baseInfo["elapsed"] = System.currentTimeMillis() - state.startTime
            }
            is CompletionState.Ready -> {
                baseInfo["completionLength"] = state.completion.insertText.length
                baseInfo["elapsed"] = System.currentTimeMillis() - state.readyTime
            }
            is CompletionState.Displaying -> {
                baseInfo["completionLength"] = state.completion.insertText.length
                baseInfo["elapsed"] = System.currentTimeMillis() - state.displayTime
            }
            is CompletionState.Accepting -> {
                baseInfo["acceptType"] = state.acceptType
                baseInfo["elapsed"] = System.currentTimeMillis() - state.startTime
            }
            else -> {}
        }
        
        return baseInfo
    }
    
    /**
     * Add a state transition listener
     */
    fun addListener(listener: StateTransitionListener) {
        listeners.add(listener)
    }
    
    /**
     * Remove a state transition listener
     */
    fun removeListener(listener: StateTransitionListener) {
        listeners.remove(listener)
    }
    
    private fun notifyListeners(
        oldState: CompletionState, 
        newState: CompletionState, 
        event: CompletionEvent
    ) {
        listeners.forEach { listener ->
            try {
                listener.onStateChanged(oldState, newState, event)
            } catch (e: Exception) {
                logger.error("Error in state transition listener", e)
            }
        }
    }
}