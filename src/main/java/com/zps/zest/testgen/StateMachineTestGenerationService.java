package com.zps.zest.testgen;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import com.zps.zest.testgen.model.TestGenerationRequest;
import com.zps.zest.testgen.model.TestPlan;
import com.zps.zest.testgen.statemachine.*;
import com.zps.zest.testgen.statemachine.handlers.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * State machine-based test generation service with clean OOP design,
 * event-driven architecture, and robust error recovery.
 */
@Service(Service.Level.PROJECT)
public final class StateMachineTestGenerationService {
    private static final Logger LOG = Logger.getInstance(StateMachineTestGenerationService.class);
    
    private final Project project;
    private final Map<String, TestGenerationStateMachine> activeStateMachines = new ConcurrentHashMap<>();
    private final Map<String, TestGenerationEventListener> sessionListeners = new ConcurrentHashMap<>();
    private final Map<String, Consumer<String>> streamingCallbacks = new ConcurrentHashMap<>();
    
    public StateMachineTestGenerationService(@NotNull Project project) {
        this.project = project;
        LOG.info("StateMachineTestGenerationService initialized");
    }

    /**
     * Start test generation with both event listener and streaming callback
     */
    @NotNull
    public CompletableFuture<TestGenerationStateMachine> startTestGeneration(
            @NotNull TestGenerationRequest request,
            @Nullable TestGenerationEventListener eventListener,
            @Nullable Consumer<String> streamingCallback) {
        
        String sessionId = UUID.randomUUID().toString();
        LOG.info("Starting state machine-based test generation: " + sessionId);
        
        // Create state machine
        TestGenerationStateMachine stateMachine = new TestGenerationStateMachine(project, sessionId);
        
        // Register event listener
        if (eventListener != null) {
            stateMachine.addEventListener(eventListener);
            sessionListeners.put(sessionId, eventListener);
        }
        
        // Store streaming callback
        if (streamingCallback != null) {
            streamingCallbacks.put(sessionId, streamingCallback);
        }
        
        // Register all state handlers with streaming support and UI event listener
        setupStateHandlers(stateMachine, streamingCallback, eventListener);
        
        // Store initial request in state machine
        stateMachine.setRequest(request);
        
        // Note: CoordinatorAgent will be created later in TestPlanningHandler when contextTools are available
        // This ensures the CoordinatorAgent has proper access to analyzed classes and context
        
        // Store active state machine
        activeStateMachines.put(sessionId, stateMachine);
        
        // Start the workflow
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Enable auto-flow for initial states (until user selection)
                stateMachine.enableAutoFlow();
                
                // Transition to initializing state and execute
                stateMachine.transitionTo(TestGenerationState.INITIALIZING, "Starting test generation");
                stateMachine.executeCurrentState().join();
                
                return stateMachine;
                
            } catch (Exception e) {
                LOG.error("Failed to start test generation workflow", e);
                stateMachine.transitionTo(TestGenerationState.FAILED, "Startup failed: " + e.getMessage());
                return stateMachine;
            }
        });
    }
    
    /**
     * Continue execution from current state (manual intervention)
     */
    public boolean continueExecution(@NotNull String sessionId) {
        TestGenerationStateMachine stateMachine = activeStateMachines.get(sessionId);
        if (stateMachine == null) {
            LOG.warn("No active state machine found for session: " + sessionId);
            return false;
        }
        
        if (stateMachine.isExecuting()) {
            LOG.warn("State machine is already executing: " + sessionId);
            return false;
        }
        
        if (stateMachine.getCurrentState().isTerminal()) {
            LOG.warn("Cannot continue from terminal state: " + stateMachine.getCurrentState());
            return false;
        }
        
        LOG.info("Continuing execution for session: " + sessionId + " from state: " + stateMachine.getCurrentState());
        
        // Execute current state
        stateMachine.executeCurrentState();
        return true;
    }
    
    /**
     * Retry current state after failure
     */
    public boolean retryCurrentState(@NotNull String sessionId) {
        TestGenerationStateMachine stateMachine = activeStateMachines.get(sessionId);
        if (stateMachine == null) {
            LOG.warn("No active state machine found for session: " + sessionId);
            return false;
        }
        
        if (!stateMachine.getCurrentState().isRetryable()) {
            LOG.warn("Current state is not retryable: " + stateMachine.getCurrentState());
            return false;
        }
        
        LOG.info("Retrying current state for session: " + sessionId + " state: " + stateMachine.getCurrentState());
        
        stateMachine.retryCurrentState();
        return true;
    }
    
    /**
     * Skip current state (manual intervention)
     */
    public boolean skipCurrentState(@NotNull String sessionId, @Nullable String reason) {
        TestGenerationStateMachine stateMachine = activeStateMachines.get(sessionId);
        if (stateMachine == null) {
            LOG.warn("No active state machine found for session: " + sessionId);
            return false;
        }
        
        if (!stateMachine.getCurrentState().isSkippable()) {
            LOG.warn("Current state is not skippable: " + stateMachine.getCurrentState());
            return false;
        }
        
        LOG.info("Skipping current state for session: " + sessionId + " state: " + stateMachine.getCurrentState());
        
        boolean skipped = stateMachine.skipCurrentState(reason);
        if (skipped) {
            // Auto-continue to next state
            continueExecution(sessionId);
        }
        return skipped;
    }
    
    /**
     * Provide user selection for scenarios
     */
    public boolean setUserSelection(@NotNull String sessionId, @NotNull List<TestPlan.TestScenario> selectedScenarios) {
        TestGenerationStateMachine stateMachine = activeStateMachines.get(sessionId);
        if (stateMachine == null) {
            LOG.warn("No active state machine found for session: " + sessionId);
            return false;
        }
        
        if (stateMachine.getCurrentState() != TestGenerationState.AWAITING_USER_SELECTION) {
            LOG.warn("State machine is not awaiting user selection. Current state: " + stateMachine.getCurrentState());
            return false;
        }
        
        LOG.info("Setting user selection for session: " + sessionId + " - " + selectedScenarios.size() + " scenarios");
        
        // Pass selection to TestPlanningHandler
        com.zps.zest.testgen.statemachine.handlers.TestPlanningHandler planningHandler =
            stateMachine.getCurrentHandler(com.zps.zest.testgen.statemachine.handlers.TestPlanningHandler.class);
        if (planningHandler != null) {
            planningHandler.setSelectedScenarios(selectedScenarios);
        }
        
        // Re-enable auto-flow after user selection
        stateMachine.enableAutoFlow();
        LOG.info("Auto-flow re-enabled after user selection");
        
        // Continue execution which will process the selection
        return continueExecution(sessionId);
    }
    
    /**
     * Cancel test generation
     */
    public void cancelGeneration(@NotNull String sessionId, @Nullable String reason) {
        TestGenerationStateMachine stateMachine = activeStateMachines.get(sessionId);
        if (stateMachine == null) {
            LOG.warn("No active state machine found for session: " + sessionId);
            return;
        }
        
        LOG.info("Cancelling test generation for session: " + sessionId);
        stateMachine.cancel(reason);
    }
    
    /**
     * Get current state machine for a session
     */
    @Nullable
    public TestGenerationStateMachine getStateMachine(@NotNull String sessionId) {
        return activeStateMachines.get(sessionId);
    }
    
    /**
     * Get current state for a session
     */
    @Nullable
    public TestGenerationState getCurrentState(@NotNull String sessionId) {
        TestGenerationStateMachine stateMachine = activeStateMachines.get(sessionId);
        return stateMachine != null ? stateMachine.getCurrentState() : null;
    }
    
    /**
     * Check if a session can continue manually
     */
    public boolean canContinueManually(@NotNull String sessionId) {
        TestGenerationStateMachine stateMachine = activeStateMachines.get(sessionId);
        if (stateMachine == null) {
            return false;
        }
        
        TestGenerationState currentState = stateMachine.getCurrentState();
        return !stateMachine.isExecuting() && 
               !currentState.isTerminal() && 
               currentState != TestGenerationState.IDLE;
    }
    
    /**
     * Check if a session can retry current state
     */
    public boolean canRetry(@NotNull String sessionId) {
        TestGenerationStateMachine stateMachine = activeStateMachines.get(sessionId);
        if (stateMachine == null) {
            return false;
        }
        
        return !stateMachine.isExecuting() && 
               stateMachine.getCurrentState().isRetryable() &&
               stateMachine.getLastError() != null;
    }
    
    /**
     * Check if a session can skip current state
     */
    public boolean canSkip(@NotNull String sessionId) {
        TestGenerationStateMachine stateMachine = activeStateMachines.get(sessionId);
        if (stateMachine == null) {
            return false;
        }
        
        return !stateMachine.isExecuting() && 
               stateMachine.getCurrentState().isSkippable();
    }
    
    /**
     * Get all active sessions
     */
    @NotNull
    public Set<String> getActiveSessions() {
        return new HashSet<>(activeStateMachines.keySet());
    }
    
    /**
     * Clean up completed or cancelled sessions
     */
    public void cleanupSession(@NotNull String sessionId) {
        TestGenerationStateMachine stateMachine = activeStateMachines.remove(sessionId);
        sessionListeners.remove(sessionId);
        
        if (stateMachine != null) {
            LOG.info("Cleaned up session: " + sessionId);
        }
    }
    
    /**
     * Clean up old sessions
     */
    public void cleanupOldSessions() {
        Set<String> toRemove = new HashSet<>();
        
        for (Map.Entry<String, TestGenerationStateMachine> entry : activeStateMachines.entrySet()) {
            TestGenerationStateMachine stateMachine = entry.getValue();
            
            // Remove terminal sessions that are older than 1 hour
            if (stateMachine.getCurrentState().isTerminal()) {
                // TODO: Track session start time differently since sessionData removed
                // For now, clean up all terminal sessions
                toRemove.add(entry.getKey());
            }
        }
        
        for (String sessionId : toRemove) {
            cleanupSession(sessionId);
        }
        
        if (!toRemove.isEmpty()) {
            LOG.info("Cleaned up " + toRemove.size() + " old sessions");
        }
    }
    
    /**
     * Setup all state handlers for a state machine
     */
    private void setupStateHandlers(@NotNull TestGenerationStateMachine stateMachine, 
                                   @Nullable Consumer<String> streamingCallback,
                                   @Nullable TestGenerationEventListener eventListener) {
        
        // Extract UI event listener if available
        com.zps.zest.testgen.ui.StreamingEventListener uiEventListener = null;
        if (eventListener instanceof com.zps.zest.testgen.ui.StreamingEventListener) {
            uiEventListener = (com.zps.zest.testgen.ui.StreamingEventListener) eventListener;
        }
        
        stateMachine.registerStateHandler(TestGenerationState.INITIALIZING, new InitializationHandler());
        stateMachine.registerStateHandler(TestGenerationState.GATHERING_CONTEXT, 
            new ContextGatheringHandler(streamingCallback, uiEventListener));
        stateMachine.registerStateHandler(TestGenerationState.PLANNING_TESTS, 
            new TestPlanningHandler(streamingCallback, uiEventListener));
        stateMachine.registerStateHandler(TestGenerationState.AWAITING_USER_SELECTION, new UserSelectionHandler());
        stateMachine.registerStateHandler(TestGenerationState.GENERATING_TESTS, 
            new TestGenerationHandler(streamingCallback, uiEventListener));
        stateMachine.registerStateHandler(TestGenerationState.MERGING_TESTS, new TestMergingHandler(streamingCallback, uiEventListener));

        LOG.debug("State handlers registered for session: " + stateMachine.getSessionId());
    }
}