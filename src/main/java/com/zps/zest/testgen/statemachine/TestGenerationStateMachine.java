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

    // Debug flags for console logging
    private static final boolean DEBUG_STATE_TRANSITIONS = true;
    private static final boolean DEBUG_AUTO_FLOW = true;
    private static final boolean DEBUG_EXECUTION = true;
    private static final boolean DEBUG_SESSION = true;
    private static final boolean DEBUG_CANCELLATION = true;
    private static final boolean WARN_POTENTIAL_ERRORS = true;

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

        if (DEBUG_SESSION) {
            System.out.println("[DEBUG_SESSION] State machine created for session: " + sessionId);
        }
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
            if (WARN_POTENTIAL_ERRORS) {
                System.out.println("[WARN_TRANSITION_BLOCKED] Cannot transition while executing! " +
                    "currentState=" + currentState + ", targetState=" + newState +
                    ", isExecuting=true, sessionId=" + sessionId);
            }
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
            if (DEBUG_STATE_TRANSITIONS) {
                System.out.println("[DEBUG_STATE_TRANSITION] INVALID transition from " + currentState +
                    " to " + newState + " (isExecuting=" + isExecuting + ", sessionId=" + sessionId + ")");
            }
            LOG.warn("Invalid transition from " + currentState + " to " + newState);
            return false;
        }

        TestGenerationState oldState = currentState;
        currentState = newState;

        if (DEBUG_STATE_TRANSITIONS) {
            System.out.println("[DEBUG_STATE_TRANSITION] " + oldState + " → " + newState +
                (reason != null ? " (reason: " + reason + ")" : "") +
                ", sessionId=" + sessionId + ", autoFlow=" + autoFlowEnabled);
        }
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
            if (DEBUG_EXECUTION) {
                System.out.println("[DEBUG_EXEC] Already executing state: " + currentState +
                    ", sessionId=" + sessionId);
            }
            LOG.warn("Already executing state: " + currentState);
            return currentExecution != null ? currentExecution : CompletableFuture.completedFuture(null);
        }

        StateHandler handler = stateHandlers.get(currentState);
        if (handler == null) {
            String error = "No handler registered for state: " + currentState;
            if (DEBUG_EXECUTION) {
                System.out.println("[DEBUG_EXEC] ERROR: " + error + ", sessionId=" + sessionId);
            }
            LOG.error(error);
            handleError(new IllegalStateException(error), false);
            return CompletableFuture.completedFuture(null);
        }

        isExecuting = true;
        lastError = null;

        if (DEBUG_EXECUTION) {
            System.out.println("[DEBUG_EXEC] START execution, state=" + currentState +
                ", isExecuting=true, autoFlow=" + autoFlowEnabled + ", sessionId=" + sessionId);
        }
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
                    if (DEBUG_EXECUTION) {
                        System.out.println("[DEBUG_EXEC] SUCCESS, state=" + currentState +
                            ", isExecuting=false, nextState=" + result.getNextState() +
                            ", sessionId=" + sessionId);
                    }

                    // Re-enable auto-flow if this was a successful retry
                    if (lastError != null && !autoFlowEnabled) {
                        autoFlowEnabled = true;
                        if (DEBUG_AUTO_FLOW) {
                            System.out.println("[DEBUG_AUTO_FLOW] Re-enabled after successful retry, state=" +
                                currentState + ", sessionId=" + sessionId);
                        }
                        LOG.info("Re-enabled auto-flow after successful retry of state: " + currentState);
                    }

                    // Auto-transition to next state if specified
                    if (result.getNextState() != null) {
                        transitionTo(result.getNextState(), "State completed successfully");

                        // If auto-flow is enabled, automatically execute the next state
                        if (autoFlowEnabled && !result.getNextState().isTerminal()) {
                            if (DEBUG_AUTO_FLOW) {
                                System.out.println("[DEBUG_AUTO_FLOW] Continuing to next state=" +
                                    result.getNextState() + ", autoFlow=true, sessionId=" + sessionId);
                            }
                            LOG.info("Auto-flow: continuing to next state: " + result.getNextState());
                            executeCurrentState();
                        } else if (DEBUG_AUTO_FLOW) {
                            System.out.println("[DEBUG_AUTO_FLOW] NOT continuing, autoFlow=" + autoFlowEnabled +
                                ", nextState=" + result.getNextState() + ", isTerminal=" +
                                result.getNextState().isTerminal() + ", sessionId=" + sessionId);

                            // WARNING: Auto-flow disabled but in active state
                            if (WARN_POTENTIAL_ERRORS && !autoFlowEnabled && !result.getNextState().isTerminal() &&
                                result.getNextState() != TestGenerationState.AWAITING_USER_SELECTION) {
                                System.out.println("[WARN_AUTOFLOW_STUCK] Auto-flow disabled in non-terminal state, " +
                                    "may require manual intervention! state=" + result.getNextState() + ", sessionId=" + sessionId);
                            }
                        }
                    }

                } else {
                    // State failed
                    handleError(result.getError(), result.isRecoverable());
                    isExecuting = false;
                    if (DEBUG_EXECUTION) {
                        System.out.println("[DEBUG_EXEC] FAILED, state=" + currentState +
                            ", isExecuting=false, recoverable=" + result.isRecoverable() +
                            ", sessionId=" + sessionId);
                    }
                }
                
            } catch (Exception e) {
                if (DEBUG_EXECUTION) {
                    System.out.println("[DEBUG_EXEC] EXCEPTION in state=" + currentState +
                        ", error=" + e.getMessage() + ", sessionId=" + sessionId);
                }
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
            if (DEBUG_EXECUTION) {
                System.out.println("[DEBUG_EXEC] RETRY FAILED: state not retryable, state=" +
                    currentState + ", sessionId=" + sessionId);
            }
            LOG.warn("State is not retryable: " + currentState);
            return CompletableFuture.completedFuture(null);
        }

        if (DEBUG_EXECUTION) {
            System.out.println("[DEBUG_EXEC] RETRY state=" + currentState +
                ", autoFlow=" + autoFlowEnabled + ", sessionId=" + sessionId);
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
        if (DEBUG_CANCELLATION) {
            System.out.println("[DEBUG_CANCEL] Cancelling workflow, state=" + currentState +
                ", reason=" + (reason != null ? reason : "User requested") +
                ", isExecuting=" + isExecuting + ", sessionId=" + sessionId);
        }
        LOG.info("Cancelling workflow: " + (reason != null ? reason : "User requested"));

        // Cancel the current state handler to propagate to agents
        StateHandler currentHandler = stateHandlers.get(currentState);
        if (currentHandler != null) {
            currentHandler.cancel();
            if (DEBUG_CANCELLATION) {
                System.out.println("[DEBUG_CANCEL] Cancelled state handler for state=" + currentState);
            }
            LOG.info("Cancelled current state handler: " + currentState);
        }

        // Transition to cancelled state
        transitionTo(TestGenerationState.CANCELLED, reason);

        // Try to interrupt current execution
        if (currentExecution != null && !currentExecution.isDone()) {
            currentExecution.cancel(true);
            if (DEBUG_CANCELLATION) {
                System.out.println("[DEBUG_CANCEL] Cancelled execution future");
            }
            LOG.info("Cancelled current execution future");
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
            if (DEBUG_AUTO_FLOW) {
                System.out.println("[DEBUG_AUTO_FLOW] DISABLED due to error, state=" + currentState +
                    ", error=" + error.getMessage() + ", sessionId=" + sessionId);
            }
            LOG.info("Auto-flow disabled due to error in state: " + currentState);
        }

        if (DEBUG_EXECUTION) {
            System.out.println("[DEBUG_EXEC] ERROR in state=" + currentState +
                ", recoverable=" + recoverable + ", error=" + error.getMessage() +
                ", sessionId=" + sessionId);
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
        if (DEBUG_AUTO_FLOW) {
            System.out.println("[DEBUG_AUTO_FLOW] ENABLED, state=" + currentState +
                ", sessionId=" + sessionId);
        }
        LOG.info("Auto-flow enabled for session: " + sessionId);
    }

    /**
     * Disable auto-flow mode - manual intervention required for state transitions
     */
    public void disableAutoFlow() {
        this.autoFlowEnabled = false;
        if (DEBUG_AUTO_FLOW) {
            System.out.println("[DEBUG_AUTO_FLOW] DISABLED (manual), state=" + currentState +
                ", sessionId=" + sessionId);
        }
        LOG.info("Auto-flow disabled for session: " + sessionId);
    }
    
    /**
     * Check if auto-flow is enabled
     */
    public boolean isAutoFlowEnabled() {
        return autoFlowEnabled;
    }

    /**
     * Restore state machine from a checkpoint snapshot.
     * Restores agent state and optionally injects nudge instructions.
     */
    public void restoreFromCheckpoint(
            @NotNull com.zps.zest.testgen.snapshot.AgentSnapshot snapshot,
            @NotNull TestGenerationState targetState,
            @Nullable String nudgeInstructions) {

        LOG.info("Restoring from checkpoint: " + snapshot.getDescription() + " to state: " + targetState);

        // Get handler for target state
        StateHandler handler = stateHandlers.get(targetState);
        if (handler == null) {
            throw new IllegalStateException("No handler registered for state: " + targetState);
        }

        // Restore agent based on snapshot type
        try {
            switch (snapshot.getAgentType()) {
                case CONTEXT:
                    if (handler instanceof com.zps.zest.testgen.statemachine.handlers.ContextGatheringHandler) {
                        com.zps.zest.testgen.statemachine.handlers.ContextGatheringHandler contextHandler =
                            (com.zps.zest.testgen.statemachine.handlers.ContextGatheringHandler) handler;

                        // Create agent if not exists
                        if (contextHandler.getContextAgent() == null) {
                            com.zps.zest.langchain4j.ZestLangChain4jService langChainService =
                                project.getService(com.zps.zest.langchain4j.ZestLangChain4jService.class);
                            com.zps.zest.langchain4j.naive_service.NaiveLLMService naiveLlmService =
                                project.getService(com.zps.zest.langchain4j.naive_service.NaiveLLMService.class);
                            com.zps.zest.testgen.agents.ContextAgent agent =
                                new com.zps.zest.testgen.agents.ContextAgent(project, langChainService, naiveLlmService);

                            // Restore from snapshot
                            agent.restoreFromSnapshot(snapshot);

                            // Inject nudge message
                            if (nudgeInstructions != null && !nudgeInstructions.isEmpty()) {
                                agent.getChatMemory().add(dev.langchain4j.data.message.UserMessage.from(nudgeInstructions));
                            } else {
                                agent.getChatMemory().add(dev.langchain4j.data.message.UserMessage.from("Continue with the task."));
                            }

                            // Set agent on handler
                            contextHandler.setContextAgent(agent);
                        }
                    }
                    break;

                case COORDINATOR:
                    if (handler instanceof com.zps.zest.testgen.statemachine.handlers.TestPlanningHandler) {
                        com.zps.zest.testgen.statemachine.handlers.TestPlanningHandler planningHandler =
                            (com.zps.zest.testgen.statemachine.handlers.TestPlanningHandler) handler;

                        // Create agent if not exists
                        if (planningHandler.getCoordinatorAgent() == null) {
                            com.zps.zest.langchain4j.ZestLangChain4jService langChainService =
                                project.getService(com.zps.zest.langchain4j.ZestLangChain4jService.class);
                            com.zps.zest.langchain4j.naive_service.NaiveLLMService naiveLlmService =
                                project.getService(com.zps.zest.langchain4j.naive_service.NaiveLLMService.class);

                            // Get context tools from previous handler
                            com.zps.zest.testgen.agents.ContextAgent.ContextGatheringTools contextTools = null;
                            com.zps.zest.testgen.statemachine.handlers.ContextGatheringHandler contextHandler =
                                getCurrentHandler(com.zps.zest.testgen.statemachine.handlers.ContextGatheringHandler.class);
                            if (contextHandler != null && contextHandler.getContextAgent() != null) {
                                contextTools = contextHandler.getContextAgent().getContextTools();
                            }

                            com.zps.zest.testgen.agents.CoordinatorAgent agent =
                                new com.zps.zest.testgen.agents.CoordinatorAgent(project, langChainService, naiveLlmService, contextTools);

                            // Restore from snapshot
                            agent.restoreFromSnapshot(snapshot);

                            // Inject nudge message
                            if (nudgeInstructions != null && !nudgeInstructions.isEmpty()) {
                                agent.getChatMemory().add(dev.langchain4j.data.message.UserMessage.from(nudgeInstructions));
                            } else {
                                agent.getChatMemory().add(dev.langchain4j.data.message.UserMessage.from("Continue with the task."));
                            }

                            // Set agent on handler
                            planningHandler.setCoordinatorAgent(agent);
                        }
                    }
                    break;

                case TEST_WRITER:
                    if (handler instanceof com.zps.zest.testgen.statemachine.handlers.TestGenerationHandler) {
                        com.zps.zest.testgen.statemachine.handlers.TestGenerationHandler generationHandler =
                            (com.zps.zest.testgen.statemachine.handlers.TestGenerationHandler) handler;

                        // Create agent if not exists
                        if (generationHandler.getTestWriterAgent() == null) {
                            com.zps.zest.langchain4j.ZestLangChain4jService langChainService =
                                project.getService(com.zps.zest.langchain4j.ZestLangChain4jService.class);
                            com.zps.zest.langchain4j.naive_service.NaiveLLMService naiveLlmService =
                                project.getService(com.zps.zest.langchain4j.naive_service.NaiveLLMService.class);
                            com.zps.zest.testgen.agents.TestWriterAgent agent =
                                new com.zps.zest.testgen.agents.TestWriterAgent(project, langChainService, naiveLlmService);

                            // Restore from snapshot
                            agent.restoreFromSnapshot(snapshot);

                            // Inject nudge message
                            if (nudgeInstructions != null && !nudgeInstructions.isEmpty()) {
                                agent.getChatMemory().add(dev.langchain4j.data.message.UserMessage.from(nudgeInstructions));
                            } else {
                                agent.getChatMemory().add(dev.langchain4j.data.message.UserMessage.from("Continue with the task."));
                            }

                            // Set agent on handler
                            generationHandler.setTestWriterAgent(agent);
                        }
                    }
                    break;

                case TEST_MERGER:
                    if (handler instanceof com.zps.zest.testgen.statemachine.handlers.TestMergingHandler) {
                        com.zps.zest.testgen.statemachine.handlers.TestMergingHandler mergingHandler =
                            (com.zps.zest.testgen.statemachine.handlers.TestMergingHandler) handler;

                        // Create agent if not exists
                        if (mergingHandler.getAITestMergerAgent() == null) {
                            com.zps.zest.langchain4j.ZestLangChain4jService langChainService =
                                project.getService(com.zps.zest.langchain4j.ZestLangChain4jService.class);
                            com.zps.zest.langchain4j.naive_service.NaiveLLMService naiveLlmService =
                                project.getService(com.zps.zest.langchain4j.naive_service.NaiveLLMService.class);
                            com.zps.zest.testgen.agents.AITestMergerAgent agent =
                                new com.zps.zest.testgen.agents.AITestMergerAgent(
                                    project, langChainService, naiveLlmService,
                                    com.zps.zest.testgen.model.TestFixStrategy.FULL_REWRITE_ONLY
                                );

                            // Restore from snapshot
                            agent.restoreFromSnapshot(snapshot);

                            // Inject nudge message
                            if (nudgeInstructions != null && !nudgeInstructions.isEmpty()) {
                                agent.getChatMemory().add(dev.langchain4j.data.message.UserMessage.from(nudgeInstructions));
                            } else {
                                agent.getChatMemory().add(dev.langchain4j.data.message.UserMessage.from("Continue with the task."));
                            }

                            // Set agent on handler
                            mergingHandler.setAITestMergerAgent(agent);
                        }
                    }
                    break;

                default:
                    throw new IllegalArgumentException("Unknown agent type: " + snapshot.getAgentType());
            }

            LOG.info("Successfully restored agent from checkpoint");

        } catch (Exception e) {
            LOG.error("Failed to restore agent from checkpoint", e);
            throw new RuntimeException("Failed to restore from checkpoint: " + e.getMessage(), e);
        }
    }

    /**
     * Get the current handler for the current state with type safety
     */
    @Nullable
    public <T extends StateHandler> T getCurrentHandler(@NotNull Class<T> handlerType) {
        StateHandler handler = stateHandlers.get(currentState);
        if (handlerType.isInstance(handler)){
            return handlerType.cast(handler);
        }
        for (StateHandler handler1: stateHandlers.values()){
            if (handlerType.isInstance(handler1))
                return handlerType.cast(handler1);
        }
        return null;
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