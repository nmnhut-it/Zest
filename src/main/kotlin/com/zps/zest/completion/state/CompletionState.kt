package com.zps.zest.completion.state

import com.zps.zest.completion.data.CompletionContext
import com.zps.zest.completion.data.ZestInlineCompletionItem

/**
 * Self-managing state pattern for completion state machine.
 * Each state handles its own events and lifecycle.
 */
sealed class CompletionState {
    
    /**
     * Handle an event and return the next state.
     * Each state decides its own transitions.
     */
    abstract fun handleEvent(event: CompletionEvent): CompletionState
    
    /**
     * Called when entering this state.
     * Used to trigger side effects like displaying completion or updating UI.
     */
    open fun onEnter(context: StateContext) {}
    
    /**
     * Called when exiting this state.
     * Used for cleanup like hiding UI elements.
     */
    open fun onExit(context: StateContext) {}
    
    /**
     * Idle state - waiting for user action
     */
    object Idle : CompletionState() {
        override fun handleEvent(event: CompletionEvent): CompletionState {
            return when (event) {
                is CompletionEvent.RequestCompletion -> {
                    Requesting(event.requestId, event.context)
                }
                else -> this // Stay in Idle for other events
            }
        }
        
        override fun onEnter(context: StateContext) {
            context.renderer.hide()
            context.updateStatusBar("")
        }
        
        override fun toString() = "Idle"
    }
    
    /**
     * Requesting state - waiting for completion from provider
     */
    data class Requesting(
        val requestId: Int,
        val context: CompletionContext,
        val startTime: Long = System.currentTimeMillis()
    ) : CompletionState() {
        
        override fun handleEvent(event: CompletionEvent): CompletionState {
            return when (event) {
                is CompletionEvent.CompletionReceived -> {
                    if (event.requestId == this.requestId) {
                        Ready(event.completion, this.context, this.requestId)
                    } else {
                        this // Ignore completions for wrong request ID
                    }
                }
                is CompletionEvent.Dismiss -> Idle
                is CompletionEvent.Error -> Idle
                is CompletionEvent.RequestCompletion -> {
                    // New request cancels this one
                    Requesting(event.requestId, event.context)
                }
                else -> this // Stay in Requesting
            }
        }
        
        override fun onEnter(context: StateContext) {
            context.updateStatusBar("Requesting completion...")
            context.trackRequestStarted(requestId, this.context)
        }
        
        override fun toString() = "Requesting(id=$requestId)"
    }
    
    /**
     * Ready state - completion is available but not yet displayed
     */
    data class Ready(
        val completion: ZestInlineCompletionItem,
        val context: CompletionContext,
        val requestId: Int,
        val readyTime: Long = System.currentTimeMillis()
    ) : CompletionState() {
        
        override fun handleEvent(event: CompletionEvent): CompletionState {
            return when (event) {
                is CompletionEvent.DisplayCompleted -> {
                    // Transition to Displaying state when display is complete
                    Displaying(completion, context, requestId)
                }
                is CompletionEvent.Dismiss -> Idle
                is CompletionEvent.RequestCompletion -> {
                    // New request replaces this ready completion
                    Requesting(event.requestId, event.context)
                }
                else -> this // Stay in Ready
            }
        }
        
        override fun onEnter(context: StateContext) {
            // Always hide any existing renderer first to prevent double rendering
            context.renderer.hide()
            
            // Display the completion
            context.displayCompletion(completion, this.context) {
                // When display is complete, transition to Displaying state
                context.handleEvent(CompletionEvent.DisplayCompleted)
            }
            context.updateStatusBar("Completion ready - Press Tab")
            
            // Track that we have a completion ready
            context.trackCompletionReady(completion, this.context)
        }
        
        override fun onExit(context: StateContext) {
            // Don't hide here - let the next state decide what to do
        }
        
        override fun toString() = "Ready"
    }
    
    /**
     * Displaying state - completion is displayed and can be accepted
     */
    data class Displaying(
        val completion: ZestInlineCompletionItem,
        val context: CompletionContext,
        val requestId: Int,
        val displayTime: Long = System.currentTimeMillis()
    ) : CompletionState() {
        
        override fun handleEvent(event: CompletionEvent): CompletionState {
            return when (event) {
                is CompletionEvent.AcceptRequested -> {
                    Accepting(completion, context, event.acceptType)
                }
                is CompletionEvent.Dismiss -> Idle
                is CompletionEvent.RequestCompletion -> {
                    // New request replaces this displayed completion
                    Requesting(event.requestId, event.context)
                }
                else -> this // Stay in Displaying
            }
        }
        
        override fun onEnter(context: StateContext) {
            // Completion is already displayed, just update status
            context.updateStatusBar("Completion ready - Press Tab to accept")
        }
        
        override fun onExit(context: StateContext) {
            // Hide the renderer when leaving Displaying state (unless going to Accepting)
            context.renderer.hide()
        }
        
        override fun toString() = "Displaying"
    }
    
    /**
     * Accepting state - completion is being inserted into the document
     */
    data class Accepting(
        val completion: ZestInlineCompletionItem,
        val context: CompletionContext,
        val acceptType: String,
        val startTime: Long = System.currentTimeMillis()
    ) : CompletionState() {
        
        override fun handleEvent(event: CompletionEvent): CompletionState {
            return when (event) {
                is CompletionEvent.AcceptCompleted -> Idle
                is CompletionEvent.Error -> Idle
                else -> this // Stay in Accepting until complete
            }
        }
        
        override fun onEnter(context: StateContext) {
            context.updateStatusBar("Accepting completion...")
            context.renderer.hide() // Hide the preview since we're inserting
            
            // Track acceptance started
            context.trackAcceptanceStarted(completion, acceptType)
            
            // Perform the text insertion
            context.performTextInsertion(completion, this.context, acceptType) { success ->
                if (success) {
                    // Track acceptance completed
                    context.trackAcceptanceCompleted(
                        completion, 
                        acceptType, 
                        System.currentTimeMillis() - startTime
                    )
                    context.handleEvent(CompletionEvent.AcceptCompleted)
                } else {
                    context.handleEvent(CompletionEvent.Error("Failed to insert text"))
                }
            }
        }
        
        override fun toString() = "Accepting(type=$acceptType)"
    }
}

/**
 * Events that can trigger state transitions
 */
sealed class CompletionEvent {
    data class RequestCompletion(val requestId: Int, val context: CompletionContext) : CompletionEvent()
    data class CompletionReceived(val completion: ZestInlineCompletionItem, val requestId: Int) : CompletionEvent()
    object DisplayCompleted : CompletionEvent()
    data class AcceptRequested(val acceptType: String) : CompletionEvent()
    object AcceptCompleted : CompletionEvent()
    object Dismiss : CompletionEvent()
    data class Error(val reason: String) : CompletionEvent()
    object Reset : CompletionEvent()
}

/**
 * Context providing dependencies to states
 */
interface StateContext {
    val renderer: com.zps.zest.completion.ZestInlineCompletionRenderer
    
    fun updateStatusBar(message: String)
    fun handleEvent(event: CompletionEvent): Boolean
    
    // Display operations
    fun displayCompletion(
        completion: ZestInlineCompletionItem, 
        context: CompletionContext,
        onDisplayed: () -> Unit
    )
    
    // Text insertion
    fun performTextInsertion(
        completion: ZestInlineCompletionItem,
        context: CompletionContext,
        acceptType: String,
        onComplete: (Boolean) -> Unit
    )
    
    // Metrics tracking
    fun trackRequestStarted(requestId: Int, context: CompletionContext)
    fun trackCompletionReady(completion: ZestInlineCompletionItem, context: CompletionContext)
    fun trackAcceptanceStarted(completion: ZestInlineCompletionItem, acceptType: String)
    fun trackAcceptanceCompleted(completion: ZestInlineCompletionItem, acceptType: String, timeMs: Long)
}