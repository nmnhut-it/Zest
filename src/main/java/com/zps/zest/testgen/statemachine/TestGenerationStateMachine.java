package com.zps.zest.testgen.statemachine;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.testgen.model.TestGenerationSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Core state machine for managing test generation workflow.
 * Provides clean separation of concerns, error recovery, and manual intervention capabilities.
 */
public class TestGenerationStateMachine {
    private static final Logger LOG = Logger.getInstance(TestGenerationStateMachine.class);
    
    private final Project project;
    private final String sessionId;
    private TestGenerationState currentState;
    private final Map<TestGenerationState, StateHandler> stateHandlers;
    private final List<TestGenerationEventListener> listeners;
    private final Set<TestGenerationState> validTransitions;
    private com.zps.zest.testgen.model.TestGenerationRequest request;
    
    // State execution tracking
    private volatile boolean isExecuting = false;
    private CompletableFuture<Void> currentExecution;
    private Exception lastError;
    
    // Auto-flow control
    private volatile boolean autoFlowEnabled = false;
    
    public TestGenerationStateMachine(@NotNull Project project, @NotNull String sessionId) {
        this.project = project;
        this.sessionId = sessionId;
        this.currentState = TestGenerationState.IDLE;
        this.stateHandlers = new HashMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
        this.validTransitions = initializeValidTransitions();
        
        LOG.info("State machine created for session: " + sessionId);
    }
    
    /**
     * Initialize valid state transitions
     */
    private Set<TestGenerationState> initializeValidTransitions() {
        Set<TestGenerationState> transitions = new HashSet<>();
        
        // Define all valid transitions
        // From IDLE
        transitions.add(TestGenerationState.IDLE);
        transitions.add(TestGenerationState.INITIALIZING);
        
        // Normal workflow progression
        for (TestGenerationState state : TestGenerationState.values()) {
            if (state.isActive()) {
                TestGenerationState next = state.getNextState();
                if (next != state) {
                    transitions.add(next);
                }
            }
        }
        
        // Error and cancellation transitions (can happen from any active state)
        transitions.add(TestGenerationState.FAILED);
        transitions.add(TestGenerationState.CANCELLED);
        
        return transitions;
    }
    
    /**
     * Register a state handler for a specific state
     */
    public void registerStateHandler(@NotNull TestGenerationState state, @NotNull StateHandler handler) {
        stateHandlers.put(state, handler);
        LOG.debug("Registered handler for state: " + state);
    }
    
    /**
     * Add an event listener
     */
    public void addEventListener(@NotNull TestGenerationEventListener listener) {
        listeners.add(listener);
    }
    
    /**
     * Remove an event listener
     */
    public void removeEventListener(@NotNull TestGenerationEventListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * Get current state
     */
    @NotNull
    public TestGenerationState getCurrentState() {
        return currentState;
    }
    
    /**
     * Check if the state machine is currently executing a state
     */
    public boolean isExecuting() {
        return isExecuting;
    }
    
    /**
     * Check if transition to new state is valid
     */
    public boolean canTransitionTo(@NotNull TestGenerationState newState) {
        // Can't transition while executing (except to terminal states for cancellation)
        if (isExecuting && !newState.isTerminal()) {
            return false;
        }
        
        // Terminal states can only transition to IDLE (for restart)
        if (currentState.isTerminal()) {
            return newState == TestGenerationState.IDLE;
        }
        
        // Check valid transitions based on current state
        switch (currentState) {
            case IDLE:
                return newState == TestGenerationState.INITIALIZING;
            case INITIALIZING:
                return newState == TestGenerationState.GATHERING_CONTEXT 
                    || newState == TestGenerationState.FAILED 
                    || newState == TestGenerationState.CANCELLED;
            case GATHERING_CONTEXT:
                return newState == TestGenerationState.PLANNING_TESTS 
                    || newState == TestGenerationState.FAILED 
                    || newState == TestGenerationState.CANCELLED;
            case PLANNING_TESTS:
                return newState == TestGenerationState.AWAITING_USER_SELECTION 
                    || newState == TestGenerationState.FAILED 
                    || newState == TestGenerationState.CANCELLED;
            case AWAITING_USER_SELECTION:
                return newState == TestGenerationState.GENERATING_TESTS 
                    || newState == TestGenerationState.FAILED 
                    || newState == TestGenerationState.CANCELLED;
            case GENERATING_TESTS:
                return newState == TestGenerationState.MERGING_TESTS 
                    || newState == TestGenerationState.FAILED 
                    || newState == TestGenerationState.CANCELLED;
            case MERGING_TESTS:
                return newState == TestGenerationState.FIXING_TESTS
                    || newState == TestGenerationState.COMPLETED 
                    || newState == TestGenerationState.FAILED 
                    || newState == TestGenerationState.CANCELLED;
            case FIXING_TESTS:
                return newState == TestGenerationState.COMPLETED 
                    || newState == TestGenerationState.FAILED 
                    || newState == TestGenerationState.CANCELLED;
            default:
                return false;
        }
    }
    
    /**
     * Transition to a new state
     */
    public boolean transitionTo(@NotNull TestGenerationState newState, @Nullable String reason) {
        if (!canTransitionTo(newState)) {
            LOG.warn("Invalid transition from " + currentState + " to " + newState);
            return false;
        }
        
        TestGenerationState oldState = currentState;
        currentState = newState;
        
        LOG.info("State transition: " + oldState + " -> " + newState + 
                 (reason != null ? " (" + reason + ")" : ""));
        
        // Fire state change event
        fireEvent(new TestGenerationEvent.StateChanged(sessionId, oldState, newState, reason));
        
        return true;
    }
    
    /**
     * Start execution from current state
     */
    @NotNull
    public CompletableFuture<Void> executeCurrentState() {
        if (isExecuting) {
            LOG.warn("Already executing state: " + currentState);
            return currentExecution != null ? currentExecution : CompletableFuture.completedFuture(null);
        }
        
        StateHandler handler = stateHandlers.get(currentState);
        if (handler == null) {
            String error = "No handler registered for state: " + currentState;
            LOG.error(error);
            handleError(new IllegalStateException(error), false);
            return CompletableFuture.completedFuture(null);
        }
        
        isExecuting = true;
        lastError = null;
        
        LOG.info("Executing state: " + currentState);
        
        currentExecution = CompletableFuture.runAsync(() -> {
            try {
                fireEvent(new TestGenerationEvent.ActivityLogged(
                    sessionId, currentState, "🏁 Starting " + currentState.getDisplayName().toLowerCase(), System.currentTimeMillis()
                ));
                
                StateHandler.StateResult result = handler.execute(this);
                
                if (result.isSuccess()) {
                    // State completed successfully
                    fireEvent(new TestGenerationEvent.StepCompleted(
                        sessionId, currentState, result.getResult(), result.getSummary()
                    ));
                    
                    // Clear executing flag before auto-transition
                    isExecuting = false;
                    
                    // Re-enable auto-flow if this was a successful retry
                    if (lastError != null && !autoFlowEnabled) {
                        autoFlowEnabled = true;
                        LOG.info("Re-enabled auto-flow after successful retry of state: " + currentState);
                    }
                    
                    // Auto-transition to next state if specified
                    if (result.getNextState() != null) {
                        transitionTo(result.getNextState(), "State completed successfully");
                        
                        // If auto-flow is enabled, automatically execute the next state
                        if (autoFlowEnabled && !result.getNextState().isTerminal()) {
                            LOG.info("Auto-flow: continuing to next state: " + result.getNextState());
                            executeCurrentState();
                        }
                    }
                    
                } else {
                    // State failed
                    handleError(result.getError(), result.isRecoverable());
                    isExecuting = false;
                }
                
            } catch (Exception e) {
                LOG.error("State execution failed: " + currentState, e);
                handleError(e, true); // Most exceptions are recoverable
                isExecuting = false;
            }
        });
        
        return currentExecution;
    }
    
    /**
     * Retry the current state (after failure)
     */
    @NotNull
    public CompletableFuture<Void> retryCurrentState() {
        if (!currentState.isRetryable()) {
            LOG.warn("State is not retryable: " + currentState);
            return CompletableFuture.completedFuture(null);
        }
        
        LOG.info("Retrying state: " + currentState);
        return executeCurrentState();
    }
    
    /**
     * Skip the current state and move to next
     */
    public boolean skipCurrentState(@Nullable String reason) {
        if (!currentState.isSkippable()) {
            LOG.warn("State is not skippable: " + currentState);
            return false;
        }
        
        if (isExecuting) {
            LOG.warn("Cannot skip while executing");
            return false;
        }
        
        TestGenerationState nextState = currentState.getNextState();
        return transitionTo(nextState, "Skipped: " + (reason != null ? reason : "Manual skip"));
    }
    
    /**
     * Cancel the entire workflow
     */
    public void cancel(@Nullable String reason) {
        LOG.info("Cancelling workflow: " + (reason != null ? reason : "User requested"));
        
        // If currently executing, the state will be set to cancelled when execution completes
        transitionTo(TestGenerationState.CANCELLED, reason);
        
        // Try to interrupt current execution
        if (currentExecution != null && !currentExecution.isDone()) {
            currentExecution.cancel(true);
        }
    }
    
    /**
     * Reset to idle state (for restart)
     */
    public void reset() {
        if (isExecuting) {
            cancel("Reset requested");
            // Wait for cancellation to complete
            if (currentExecution != null) {
                try {
                    currentExecution.join();
                } catch (Exception e) {
                    LOG.warn("Error waiting for cancellation", e);
                }
            }
        }
        
        currentState = TestGenerationState.IDLE;
        lastError = null;
        
        LOG.info("State machine reset to IDLE");
        fireEvent(new TestGenerationEvent.StateChanged(sessionId, currentState, TestGenerationState.IDLE, "Reset"));
    }
    
    
    /**
     * Get last error (if any)
     */
    @Nullable
    public Exception getLastError() {
        return lastError;
    }
    
    /**
     * Handle error during state execution
     */
    private void handleError(@NotNull Exception error, boolean recoverable) {
        this.lastError = error;
        
        // Disable auto-flow when error occurs - require manual intervention
        if (autoFlowEnabled) {
            autoFlowEnabled = false;
            LOG.info("Auto-flow disabled due to error in state: " + currentState);
        }
        
        LOG.error("Error in state " + currentState + ": " + error.getMessage(), error);
        
        fireEvent(new TestGenerationEvent.ErrorOccurred(
            sessionId, currentState, error.getMessage(), error, recoverable
        ));
        
        if (!recoverable) {
            transitionTo(TestGenerationState.FAILED, "Unrecoverable error: " + error.getMessage());
        }
    }
    
    /**
     * Fire an event to all listeners
     */
    private void fireEvent(@NotNull TestGenerationEvent event) {
        for (TestGenerationEventListener listener : listeners) {
            try {
                // Call specific handler based on event type
                if (event instanceof TestGenerationEvent.StateChanged) {
                    listener.onStateChanged((TestGenerationEvent.StateChanged) event);
                } else if (event instanceof TestGenerationEvent.ActivityLogged) {
                    listener.onActivityLogged((TestGenerationEvent.ActivityLogged) event);
                } else if (event instanceof TestGenerationEvent.ErrorOccurred) {
                    listener.onErrorOccurred((TestGenerationEvent.ErrorOccurred) event);
                } else if (event instanceof TestGenerationEvent.StepCompleted) {
                    listener.onStepCompleted((TestGenerationEvent.StepCompleted) event);
                } else if (event instanceof TestGenerationEvent.UserInputRequired) {
                    listener.onUserInputRequired((TestGenerationEvent.UserInputRequired) event);
                }
                
                // Always call generic handler
                listener.onEvent(event);
                
            } catch (Exception e) {
                LOG.error("Error in event listener", e);
            }
        }
    }
    
    /**
     * Create a user input required event and fire it
     */
    public void requestUserInput(@NotNull String inputType, @NotNull String prompt, @Nullable Object data) {
        fireEvent(new TestGenerationEvent.UserInputRequired(sessionId, currentState, inputType, prompt, data));
    }
    
    /**
     * Log activity during execution (replaces progress updates)
     */
    public void logActivity(@NotNull String message) {
        LOG.info("[" + sessionId + "] " + message);
        fireEvent(new TestGenerationEvent.ActivityLogged(sessionId, currentState, message, System.currentTimeMillis()));
    }
    
    @NotNull
    public Project getProject() {
        return project;
    }
    
    @NotNull
    public String getSessionId() {
        return sessionId;
    }
    
    /**
     * Enable auto-flow mode - states will automatically continue to next state on success
     */
    public void enableAutoFlow() {
        this.autoFlowEnabled = true;
        LOG.info("Auto-flow enabled for session: " + sessionId);
    }
    
    /**
     * Disable auto-flow mode - manual intervention required for state transitions
     */
    public void disableAutoFlow() {
        this.autoFlowEnabled = false;
        LOG.info("Auto-flow disabled for session: " + sessionId);
    }
    
    /**
     * Check if auto-flow is enabled
     */
    public boolean isAutoFlowEnabled() {
        return autoFlowEnabled;
    }

    /**
     * Get the current handler for the current state with type safety
     */
    @Nullable
    public <T extends StateHandler> T getCurrentHandler(@NotNull Class<T> handlerType) {
        StateHandler handler = stateHandlers.get(currentState);
        return handlerType.isInstance(handler) ? handlerType.cast(handler) : null;
    }

    /**
     * Get the handler for any state with type safety
     */
    @Nullable
    public <T extends StateHandler> T getHandler(@NotNull TestGenerationState state, @NotNull Class<T> handlerType) {
        StateHandler handler = stateHandlers.get(state);
        return handlerType.isInstance(handler) ? handlerType.cast(handler) : null;
    }

    /**
     * Set the test generation request
     */
    public void setRequest(@NotNull com.zps.zest.testgen.model.TestGenerationRequest request) {
        this.request = request;
    }

    /**
     * Get the test generation request
     */
    @Nullable
    public com.zps.zest.testgen.model.TestGenerationRequest getRequest() {
        return request;
    }
}