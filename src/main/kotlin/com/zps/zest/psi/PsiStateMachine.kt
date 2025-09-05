package com.zps.zest.psi

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

/**
 * State machine for PSI operations that prevents conflicting concurrent access
 * and ensures proper threading and resource management.
 * 
 * States:
 * - IDLE: Ready for new operations
 * - READING: PSI file access in progress
 * - ANALYZING: PSI tree traversal/analysis
 * - CACHING: Storing results for reuse
 * - ERROR: Recovery and cleanup
 * - DISPOSED: Cleanup complete, no longer usable
 */
@Service(Service.Level.PROJECT)
class PsiStateMachine(private val project: Project) : Disposable {
    
    companion object {
        private val logger = Logger.getInstance(PsiStateMachine::class.java)
        
        fun getInstance(project: Project): PsiStateMachine {
            return project.getService(PsiStateMachine::class.java)
        }
    }
    
    /**
     * PSI operation states
     */
    enum class PsiState {
        IDLE,       // Ready for new operations
        READING,    // PSI file access in progress
        ANALYZING,  // PSI tree traversal/analysis
        CACHING,    // Storing results for reuse
        ERROR,      // Recovery and cleanup needed
        DISPOSED    // Cleanup complete, no longer usable
    }
    
    /**
     * PSI operation events that trigger state transitions
     */
    sealed class PsiEvent {
        object StartReading : PsiEvent()
        object ReadingComplete : PsiEvent()
        object StartAnalyzing : PsiEvent()
        object AnalyzingComplete : PsiEvent()
        object StartCaching : PsiEvent()
        object CachingComplete : PsiEvent()
        data class Error(val exception: Exception) : PsiEvent()
        object Reset : PsiEvent()
        object Dispose : PsiEvent()
    }
    
    /**
     * Context for PSI operations
     */
    data class PsiContext(
        val operationId: String,
        val filePath: String? = null,
        val startTime: Long = System.currentTimeMillis(),
        val metadata: Map<String, Any> = emptyMap()
    )
    
    // State management
    private val globalState = AtomicReference(PsiState.IDLE)
    private val operationStates = ConcurrentHashMap<String, AtomicReference<PsiState>>()
    private val operationContexts = ConcurrentHashMap<String, PsiContext>()
    
    // Event listeners
    private val stateListeners = mutableListOf<PsiStateListener>()
    
    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    init {
        logger.info("PsiStateMachine initialized for project: ${project.name}")
    }
    
    /**
     * State change listener interface
     */
    interface PsiStateListener {
        fun onStateChanged(
            operationId: String?,
            oldState: PsiState,
            newState: PsiState,
            context: PsiContext?
        )
        
        fun onError(operationId: String?, error: Exception, context: PsiContext?)
    }
    
    /**
     * Add a state change listener
     */
    fun addStateListener(listener: PsiStateListener) {
        synchronized(stateListeners) {
            stateListeners.add(listener)
        }
    }
    
    /**
     * Remove a state change listener
     */
    fun removeStateListener(listener: PsiStateListener) {
        synchronized(stateListeners) {
            stateListeners.remove(listener)
        }
    }
    
    /**
     * Get current global state
     */
    fun getGlobalState(): PsiState = globalState.get()
    
    /**
     * Get state for specific operation
     */
    fun getOperationState(operationId: String): PsiState {
        return operationStates[operationId]?.get() ?: PsiState.IDLE
    }
    
    /**
     * Check if we can start a new operation
     */
    fun canStartOperation(): Boolean {
        val currentState = globalState.get()
        return currentState == PsiState.IDLE || currentState == PsiState.CACHING
    }
    
    /**
     * Check if specific operation can transition to new state
     */
    fun canTransition(operationId: String, event: PsiEvent): Boolean {
        val currentState = getOperationState(operationId)
        return isValidTransition(currentState, event)
    }
    
    /**
     * Start a new PSI operation
     */
    fun startOperation(
        operationId: String,
        filePath: String? = null,
        metadata: Map<String, Any> = emptyMap()
    ): Boolean {
        if (!canStartOperation()) {
            logger.debug("Cannot start PSI operation $operationId - invalid global state: ${globalState.get()}")
            return false
        }
        
        val context = PsiContext(
            operationId = operationId,
            filePath = filePath,
            metadata = metadata
        )
        
        operationContexts[operationId] = context
        operationStates[operationId] = AtomicReference(PsiState.IDLE)
        
        logger.debug("Started PSI operation: $operationId")
        return true
    }
    
    /**
     * Handle PSI event for specific operation
     */
    fun handleEvent(operationId: String, event: PsiEvent): Boolean {
        val operationStateRef = operationStates[operationId]
        if (operationStateRef == null) {
            logger.warn("No operation state found for: $operationId")
            return false
        }
        
        val currentState = operationStateRef.get()
        if (!isValidTransition(currentState, event)) {
            logger.warn("Invalid transition for operation $operationId: $currentState -> $event")
            return false
        }
        
        val newState = getNextState(currentState, event)
        val context = operationContexts[operationId]
        
        // Update operation state
        operationStateRef.set(newState)
        
        // Update global state if needed
        updateGlobalState()
        
        // Handle special events
        when (event) {
            is PsiEvent.Error -> {
                logger.warn("PSI operation $operationId encountered error", event.exception)
                notifyError(operationId, event.exception, context)
            }
            else -> {
                logger.debug("PSI operation $operationId: $currentState -> $newState")
            }
        }
        
        // Notify listeners
        notifyStateChange(operationId, currentState, newState, context)
        
        // Clean up completed operations
        if (newState == PsiState.IDLE && currentState != PsiState.IDLE) {
            cleanupOperation(operationId)
        }
        
        return true
    }
    
    /**
     * Handle global PSI event
     */
    fun handleGlobalEvent(event: PsiEvent): Boolean {
        val currentState = globalState.get()
        if (!isValidTransition(currentState, event)) {
            logger.warn("Invalid global transition: $currentState -> $event")
            return false
        }
        
        val newState = getNextState(currentState, event)
        globalState.set(newState)
        
        logger.debug("Global PSI state: $currentState -> $newState")
        notifyStateChange(null, currentState, newState, null)
        
        return true
    }
    
    private fun isValidTransition(currentState: PsiState, event: PsiEvent): Boolean {
        return when (currentState) {
            PsiState.IDLE -> when (event) {
                is PsiEvent.StartReading -> true
                is PsiEvent.Error -> true
                is PsiEvent.Dispose -> true
                else -> false
            }
            PsiState.READING -> when (event) {
                is PsiEvent.ReadingComplete -> true
                is PsiEvent.StartAnalyzing -> true
                is PsiEvent.Error -> true
                is PsiEvent.Reset -> true
                else -> false
            }
            PsiState.ANALYZING -> when (event) {
                is PsiEvent.AnalyzingComplete -> true
                is PsiEvent.StartCaching -> true
                is PsiEvent.Error -> true
                is PsiEvent.Reset -> true
                else -> false
            }
            PsiState.CACHING -> when (event) {
                is PsiEvent.CachingComplete -> true
                is PsiEvent.StartReading -> true  // Allow new operations during caching
                is PsiEvent.Error -> true
                is PsiEvent.Reset -> true
                else -> false
            }
            PsiState.ERROR -> when (event) {
                is PsiEvent.Reset -> true
                is PsiEvent.Dispose -> true
                else -> false
            }
            PsiState.DISPOSED -> false // No transitions allowed from disposed state
        }
    }
    
    private fun getNextState(currentState: PsiState, event: PsiEvent): PsiState {
        return when (event) {
            is PsiEvent.StartReading -> PsiState.READING
            is PsiEvent.ReadingComplete -> PsiState.IDLE
            is PsiEvent.StartAnalyzing -> PsiState.ANALYZING
            is PsiEvent.AnalyzingComplete -> PsiState.IDLE
            is PsiEvent.StartCaching -> PsiState.CACHING
            is PsiEvent.CachingComplete -> PsiState.IDLE
            is PsiEvent.Error -> PsiState.ERROR
            is PsiEvent.Reset -> PsiState.IDLE
            is PsiEvent.Dispose -> PsiState.DISPOSED
        }
    }
    
    private fun updateGlobalState() {
        val operationStates = operationStates.values.map { it.get() }
        
        val newGlobalState = when {
            operationStates.any { it == PsiState.ERROR } -> PsiState.ERROR
            operationStates.any { it == PsiState.READING } -> PsiState.READING
            operationStates.any { it == PsiState.ANALYZING } -> PsiState.ANALYZING
            operationStates.any { it == PsiState.CACHING } -> PsiState.CACHING
            else -> PsiState.IDLE
        }
        
        globalState.set(newGlobalState)
    }
    
    private fun notifyStateChange(
        operationId: String?,
        oldState: PsiState,
        newState: PsiState,
        context: PsiContext?
    ) {
        scope.launch {
            try {
                synchronized(stateListeners) {
                    stateListeners.forEach { listener ->
                        try {
                            listener.onStateChanged(operationId, oldState, newState, context)
                        } catch (e: Exception) {
                            logger.warn("Error in state listener", e)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("Error notifying state change", e)
            }
        }
    }
    
    private fun notifyError(operationId: String?, error: Exception, context: PsiContext?) {
        scope.launch {
            try {
                synchronized(stateListeners) {
                    stateListeners.forEach { listener ->
                        try {
                            listener.onError(operationId, error, context)
                        } catch (e: Exception) {
                            logger.warn("Error in error listener", e)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("Error notifying error", e)
            }
        }
    }
    
    private fun cleanupOperation(operationId: String) {
        operationStates.remove(operationId)
        operationContexts.remove(operationId)
        logger.debug("Cleaned up PSI operation: $operationId")
    }
    
    /**
     * Reset all operations to IDLE state
     */
    fun resetAll() {
        logger.info("Resetting all PSI operations")
        
        operationStates.values.forEach { stateRef ->
            stateRef.set(PsiState.IDLE)
        }
        globalState.set(PsiState.IDLE)
        
        // Clear all contexts
        operationContexts.clear()
        
        notifyStateChange(null, PsiState.ERROR, PsiState.IDLE, null)
    }
    
    /**
     * Get statistics about current operations
     */
    fun getStats(): Map<String, Any> {
        val stateCounters = PsiState.values().associateWith { state ->
            operationStates.values.count { it.get() == state }
        }
        
        return mapOf(
            "globalState" to globalState.get().name,
            "activeOperations" to operationStates.size,
            "stateDistribution" to stateCounters.mapKeys { it.key.name },
            "listeners" to stateListeners.size
        )
    }
    
    /**
     * Execute operation with automatic state management
     */
    fun <T> executeWithStateManagement(
        operationId: String,
        filePath: String? = null,
        operation: suspend () -> T
    ): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        
        scope.launch {
            try {
                if (!startOperation(operationId, filePath)) {
                    future.completeExceptionally(
                        Exception("Could not start PSI operation: $operationId")
                    )
                    return@launch
                }
                
                handleEvent(operationId, PsiEvent.StartReading)
                
                val result = operation()
                
                handleEvent(operationId, PsiEvent.ReadingComplete)
                future.complete(result)
                
            } catch (e: Exception) {
                handleEvent(operationId, PsiEvent.Error(e))
                future.completeExceptionally(e)
            }
        }
        
        return future
    }
    
    override fun dispose() {
        logger.info("Disposing PsiStateMachine")
        
        handleGlobalEvent(PsiEvent.Dispose)
        scope.cancel()
        
        operationStates.clear()
        operationContexts.clear()
        stateListeners.clear()
    }
}