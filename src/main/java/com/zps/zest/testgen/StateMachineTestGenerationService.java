package com.zps.zest.testgen;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import com.zps.zest.testgen.agents.ContextAgent;
import com.zps.zest.testgen.agents.CoordinatorAgent;
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
            // Notify about session creation immediately
            TestGenerationEvent.ActivityLogged sessionCreatedEvent = new TestGenerationEvent.ActivityLogged(
                sessionId,
                stateMachine.getCurrentState(),
                "Session created: " + sessionId,
                System.currentTimeMillis()
            );
            eventListener.onActivityLogged(sessionCreatedEvent);
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
     * Resume test generation from a checkpoint/snapshot.
     * Creates a new session linked to the original, restores agent state, and continues execution.
     */
    @NotNull
    public CompletableFuture<TestGenerationStateMachine> resumeFromCheckpoint(
            @NotNull String snapshotPath,
            @Nullable String nudgeInstructions,
            @Nullable TestGenerationEventListener eventListener,
            @Nullable Consumer<String> streamingCallback) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Load snapshot from file
                com.zps.zest.testgen.snapshot.AgentSnapshot snapshot =
                    com.zps.zest.testgen.snapshot.AgentSnapshotSerializer.loadFromFile(snapshotPath);

                if (snapshot == null) {
                    throw new RuntimeException("Failed to load snapshot from: " + snapshotPath);
                }

                // Create new session linked to original
                String newSessionId = UUID.randomUUID().toString();
                LOG.info("Resuming from checkpoint: " + snapshotPath + " with new session: " + newSessionId);

                // Determine checkpoint timing and target state
                String checkpointTiming = snapshot.getMetadata().getOrDefault("checkpoint_timing", "AFTER");
                com.zps.zest.testgen.snapshot.CheckpointTiming timing =
                    com.zps.zest.testgen.snapshot.CheckpointTiming.valueOf(checkpointTiming);

                // Map agent type to state
                TestGenerationState resumeState = mapAgentTypeToState(snapshot.getAgentType());

                // Create state machine
                TestGenerationStateMachine stateMachine = new TestGenerationStateMachine(project, newSessionId);

                // Register event listener
                if (eventListener != null) {
                    stateMachine.addEventListener(eventListener);
                    sessionListeners.put(newSessionId, eventListener);

                    // Notify about session creation
                    TestGenerationEvent.ActivityLogged sessionCreatedEvent = new TestGenerationEvent.ActivityLogged(
                        newSessionId,
                        stateMachine.getCurrentState(),
                        "Session resumed from checkpoint: " + snapshot.getDescription(),
                        System.currentTimeMillis()
                    );
                    eventListener.onActivityLogged(sessionCreatedEvent);
                }

                // Store streaming callback
                if (streamingCallback != null) {
                    streamingCallbacks.put(newSessionId, streamingCallback);
                }

                // Register all state handlers
                setupStateHandlers(stateMachine, streamingCallback, eventListener);

                // Note: We don't set the original request because we don't have PsiFile reference from snapshot
                // The restored agents have all the state they need to continue
                // stateMachine.setRequest(null);

                // Store active state machine
                activeStateMachines.put(newSessionId, stateMachine);

                // Restore from checkpoint
                stateMachine.restoreFromCheckpoint(snapshot, resumeState, nudgeInstructions);

                // Transition to resume state
                stateMachine.transitionTo(resumeState, "Restored from checkpoint");

                // If BEFORE checkpoint, execute the state
                // If AFTER checkpoint, transition to next state
                if (timing == com.zps.zest.testgen.snapshot.CheckpointTiming.BEFORE) {
                    LOG.info("Executing state from BEFORE checkpoint: " + resumeState);
                    stateMachine.enableAutoFlow();
                    stateMachine.executeCurrentState().join();
                } else {
                    LOG.info("Resuming from AFTER checkpoint, agent already executed: " + resumeState);
                    // Agent has already executed, user can decide next action
                    stateMachine.disableAutoFlow();
                }

                return stateMachine;

            } catch (Exception e) {
                LOG.error("Failed to resume from checkpoint: " + snapshotPath, e);
                throw new RuntimeException("Failed to resume from checkpoint: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Map agent type to corresponding workflow state
     */
    private TestGenerationState mapAgentTypeToState(com.zps.zest.testgen.snapshot.AgentType agentType) {
        switch (agentType) {
            case CONTEXT:
                return TestGenerationState.GATHERING_CONTEXT;
            case COORDINATOR:
                return TestGenerationState.PLANNING_TESTS;
            case TEST_WRITER:
                return TestGenerationState.GENERATING_TESTS;
            case TEST_MERGER:
                return TestGenerationState.MERGING_TESTS;
            default:
                throw new IllegalArgumentException("Unknown agent type: " + agentType);
        }
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
        return setUserSelection(sessionId, selectedScenarios, null);
    }

    /**
     * Provide user selection with edited testing notes
     */
    public boolean setUserSelection(@NotNull String sessionId,
                                  @NotNull List<TestPlan.TestScenario> selectedScenarios,
                                  @Nullable String editedTestingNotes) {
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
            if (editedTestingNotes != null) {
                planningHandler.updateTestingNotes(editedTestingNotes);
            }
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

        // Cancel the state machine (which will cancel handlers and agents)
        stateMachine.cancel(reason);

        LOG.info("Cancellation complete for session: " + sessionId);
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
        streamingCallbacks.remove(sessionId);

        if (stateMachine != null) {
            // Cancel the state machine first to kill any ongoing operations
            if (!stateMachine.getCurrentState().isTerminal()) {
                LOG.info("Cancelling non-terminal session during cleanup: " + sessionId);
                stateMachine.cancel("Cleanup requested");
            }

            // Cancel any agents to kill active HTTP connections
            try {
                StateHandler currentHandler = stateMachine.getCurrentHandler(AbstractStateHandler.class);
                if (currentHandler instanceof ContextGatheringHandler) {
                    ContextAgent contextAgent = ((ContextGatheringHandler) currentHandler).getContextAgent();
                    if (contextAgent != null) {
                        contextAgent.cancel();
                    }
                } else if (currentHandler instanceof TestPlanningHandler) {
                    CoordinatorAgent coordinatorAgent = ((TestPlanningHandler) currentHandler).getCoordinatorAgent();
                    if (coordinatorAgent != null) {
                        coordinatorAgent.cancel();
                    }
                } else if (currentHandler instanceof com.zps.zest.testgen.statemachine.handlers.TestGenerationHandler) {
                    com.zps.zest.testgen.agents.TestWriterAgent testWriterAgent =
                        ((com.zps.zest.testgen.statemachine.handlers.TestGenerationHandler) currentHandler).getTestWriterAgent();
                    if (testWriterAgent != null) {
                        testWriterAgent.cancel();
                    }
                } else if (currentHandler instanceof TestMergingHandler) {
                    com.zps.zest.testgen.agents.AITestMergerAgent mergerAgent =
                        ((TestMergingHandler) currentHandler).getAITestMergerAgent();
                    if (mergerAgent != null) {
                        mergerAgent.cancel();
                    }
                }
            } catch (Exception e) {
                LOG.warn("Error cancelling agents during session cleanup", e);
            }

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
        stateMachine.registerStateHandler(TestGenerationState.MERGING_TESTS, new TestMergingHandler(streamingCallback, uiEventListener, stateMachine));

        LOG.debug("State handlers registered for session: " + stateMachine.getSessionId());
    }
}