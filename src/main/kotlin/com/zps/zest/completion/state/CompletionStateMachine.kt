package com.zps.zest.completion.state

import com.zps.zest.completion.data.CompletionContext
import com.zps.zest.completion.data.ZestInlineCompletionItem
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicLong

/**
 * State machine for managing completion lifecycle states
 * Replaces scattered state management with centralized, validated state transitions
 */
class CompletionStateMachine {
    
    // State definitions
    sealed class State {
        object Idle : State()
        data class Waiting(val startTime: Long) : State()
        data class Requesting(val requestId: Int, val startTime: Long) : State()
        data class Displaying(
            val completion: ZestInlineCompletionItem,
            val context: CompletionContext,
            val displayTime: Long
        ) : State()
        data class Accepting(
            val completion: ZestInlineCompletionItem,
            val startTime: Long,
            val acceptType: String
        ) : State()
        data class Cooldown(val endTime: Long, val reason: String) : State()
        data class Error(val message: String, val timestamp: Long) : State()
    }
    
    // Events that can trigger state transitions
    sealed class Event {
        object StartWaiting : Event()
        data class StartRequesting(val requestId: Int) : Event()
        data class CompletionReceived(
            val completion: ZestInlineCompletionItem, 
            val context: CompletionContext
        ) : Event()
        data class StartAccepting(val acceptType: String) : Event()
        object AcceptanceComplete : Event()
        data class Dismiss(val reason: String) : Event()
        data class Error(val message: String) : Event()
        object Reset : Event()
        object ForceIdle : Event()
    }
    
    private val requestIdGenerator = AtomicLong(0)
    
    @Volatile
    private var _currentState: State = State.Idle
    
    val currentState: State get() = _currentState
    
    // State query methods
    val isIdle: Boolean get() = _currentState is State.Idle
    val isWaiting: Boolean get() = _currentState is State.Waiting
    val isRequesting: Boolean get() = _currentState is State.Requesting
    val isDisplaying: Boolean get() = _currentState is State.Displaying
    val isAccepting: Boolean get() = _currentState is State.Accepting
    val isInCooldown: Boolean get() = _currentState is State.Cooldown && System.currentTimeMillis() < (_currentState as State.Cooldown).endTime
    val hasError: Boolean get() = _currentState is State.Error
    
    val currentCompletion: ZestInlineCompletionItem? get() = when (val state = _currentState) {
        is State.Displaying -> state.completion
        is State.Accepting -> state.completion
        else -> null
    }
    
    val currentContext: CompletionContext? get() = when (val state = _currentState) {
        is State.Displaying -> state.context
        else -> null
    }
    
    val activeRequestId: Int? get() = when (val state = _currentState) {
        is State.Requesting -> state.requestId
        else -> null
    }
    
    // State transition listeners
    private val listeners = mutableListOf<StateTransitionListener>()
    
    interface StateTransitionListener {
        fun onStateChanged(oldState: State, newState: State, event: Event)
    }
    
    fun addListener(listener: StateTransitionListener) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: StateTransitionListener) {
        listeners.remove(listener)
    }
    
    /**
     * Generate a new unique request ID
     */
    fun generateRequestId(): Int = requestIdGenerator.incrementAndGet().toInt()
    
    /**
     * Handle an event and potentially transition to a new state
     */
    @Synchronized
    fun handleEvent(event: Event): Boolean {
        val oldState = _currentState
        val newState = calculateNewState(_currentState, event)
        
        if (newState != null && isValidTransition(oldState, newState)) {
            _currentState = newState
            
            // Notify listeners on state change
            listeners.forEach { listener ->
                try {
                    listener.onStateChanged(oldState, newState, event)
                } catch (e: Exception) {
                    // Don't let listener errors break state machine
                    e.printStackTrace()
                }
            }
            
            return true
        }
        
        return false
    }
    
    /**
     * Calculate the new state based on current state and event
     */
    private fun calculateNewState(currentState: State, event: Event): State? {
        return when (event) {
            Event.StartWaiting -> when (currentState) {
                is State.Idle -> State.Waiting(System.currentTimeMillis())
                else -> null // Can only start waiting from idle
            }
            
            is Event.StartRequesting -> when (currentState) {
                is State.Idle -> State.Requesting(event.requestId, System.currentTimeMillis())
                is State.Waiting -> State.Requesting(event.requestId, System.currentTimeMillis())
                else -> null
            }
            
            is Event.CompletionReceived -> when (currentState) {
                is State.Requesting -> State.Displaying(
                    event.completion, 
                    event.context, 
                    System.currentTimeMillis()
                )
                else -> null
            }
            
            is Event.StartAccepting -> when (currentState) {
                is State.Displaying -> State.Accepting(
                    currentState.completion,
                    System.currentTimeMillis(),
                    event.acceptType
                )
                else -> null
            }
            
            Event.AcceptanceComplete -> when (currentState) {
                is State.Accepting -> State.Cooldown(
                    System.currentTimeMillis() + 2000L, // 2 second cooldown
                    "acceptance_complete"
                )
                else -> null
            }
            
            is Event.Dismiss -> when (currentState) {
                is State.Displaying -> State.Idle
                is State.Accepting -> State.Idle
                is State.Waiting -> State.Idle
                is State.Requesting -> State.Idle
                else -> null
            }
            
            is Event.Error -> State.Error(event.message, System.currentTimeMillis())
            
            Event.Reset, Event.ForceIdle -> State.Idle
        }
    }
    
    /**
     * Validate if a state transition is allowed
     */
    private fun isValidTransition(from: State, to: State): Boolean {
        return when {
            // From Idle
            from is State.Idle && to is State.Waiting -> true
            from is State.Idle && to is State.Requesting -> true
            
            // From Waiting
            from is State.Waiting && to is State.Requesting -> true
            from is State.Waiting && to is State.Idle -> true
            
            // From Requesting
            from is State.Requesting && to is State.Displaying -> true
            from is State.Requesting && to is State.Idle -> true
            from is State.Requesting && to is State.Error -> true
            
            // From Displaying
            from is State.Displaying && to is State.Accepting -> true
            from is State.Displaying && to is State.Idle -> true
            
            // From Accepting
            from is State.Accepting && to is State.Cooldown -> true
            from is State.Accepting && to is State.Idle -> true
            from is State.Accepting && to is State.Error -> true
            
            // From Cooldown
            from is State.Cooldown && to is State.Idle -> true
            from is State.Cooldown && to is State.Waiting -> true
            
            // From Error
            from is State.Error && to is State.Idle -> true
            
            // Force transitions (always allowed)
            to is State.Idle -> true
            to is State.Error -> true
            
            else -> false
        }
    }
    
    /**
     * Check if completion can be triggered based on current state
     */
    fun canTriggerCompletion(): Boolean {
        return when (_currentState) {
            is State.Idle -> true
            is State.Cooldown -> !isInCooldown
            else -> false
        }
    }
    
    /**
     * Check if completion can be accepted based on current state
     */
    fun canAcceptCompletion(): Boolean {
        return _currentState is State.Displaying
    }
    
    /**
     * Get detailed state information for debugging
     */
    fun getStateInfo(): Map<String, Any> {
        val state = _currentState
        val baseInfo = mapOf(
            "stateName" to state.javaClass.simpleName,
            "timestamp" to System.currentTimeMillis()
        )
        
        return baseInfo + when (state) {
            is State.Idle -> emptyMap()
            is State.Waiting -> mapOf("waitingTime" to (System.currentTimeMillis() - state.startTime))
            is State.Requesting -> mapOf(
                "requestId" to state.requestId,
                "requestTime" to (System.currentTimeMillis() - state.startTime)
            )
            is State.Displaying -> mapOf(
                "completionLength" to state.completion.insertText.length,
                "displayTime" to (System.currentTimeMillis() - state.displayTime)
            )
            is State.Accepting -> mapOf(
                "acceptType" to state.acceptType,
                "acceptTime" to (System.currentTimeMillis() - state.startTime)
            )
            is State.Cooldown -> mapOf(
                "reason" to state.reason,
                "remainingCooldown" to maxOf(0L, state.endTime - System.currentTimeMillis())
            )
            is State.Error -> mapOf(
                "message" to state.message,
                "errorAge" to (System.currentTimeMillis() - state.timestamp)
            )
        }
    }
    
    /**
     * Force reset to idle state (for emergency recovery)
     */
    fun forceReset() {
        handleEvent(Event.ForceIdle)
    }
}