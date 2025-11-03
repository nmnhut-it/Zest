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

        System.out.println("[DEBUG_SNAPSHOT] resumeFromCheckpoint START: path=" + snapshotPath);

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Load snapshot from file
                System.out.println("[DEBUG_SNAPSHOT] Loading snapshot from: " + snapshotPath);
                com.zps.zest.testgen.snapshot.AgentSnapshot snapshot =
                    com.zps.zest.testgen.snapshot.AgentSnapshotSerializer.loadFromFile(snapshotPath);

                if (snapshot == null) {
                    System.out.println("[WARN_SNAPSHOT] Failed to load snapshot from: " + snapshotPath);
                    throw new RuntimeException("Failed to load snapshot from: " + snapshotPath);
                }

                // Create new session linked to original
                String newSessionId = UUID.randomUUID().toString();
                System.out.println("[DEBUG_SNAPSHOT] Loaded successfully: agentType=" + snapshot.getAgentType() +
                    ", messageCount=" + snapshot.getChatMessages().size() +
                    ", newSessionId=" + newSessionId);
                LOG.info("Resuming from checkpoint: " + snapshotPath + " with new session: " + newSessionId);

                // Determine checkpoint timing and target state
                String checkpointTiming = snapshot.getMetadata().getOrDefault("checkpoint_timing", "AFTER");
                com.zps.zest.testgen.snapshot.CheckpointTiming timing =
                    com.zps.zest.testgen.snapshot.CheckpointTiming.valueOf(checkpointTiming);

                // Map agent type to state
                TestGenerationState resumeState = mapAgentTypeToState(snapshot.getAgentType());

                System.out.println("[DEBUG_SNAPSHOT] Checkpoint timing=" + timing + ", resumeState=" + resumeState);

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

                // Try to reconstruct the request from snapshot metadata
                System.out.println("[DEBUG_SNAPSHOT] Attempting to reconstruct request from metadata");
                TestGenerationRequest reconstructedRequest = com.intellij.openapi.application.ReadAction.compute(() ->
                    reconstructRequestFromSnapshot(snapshot)
                );
                if (reconstructedRequest != null) {
                    System.out.println("[DEBUG_SNAPSHOT] Successfully reconstructed request: " +
                            reconstructedRequest.getTargetMethods().size() + " methods");
                    stateMachine.setRequest(reconstructedRequest);
                } else {
                    System.out.println("[DEBUG_SNAPSHOT] Could not reconstruct request from metadata");
                    LOG.warn("Cannot reconstruct request - AFTER checkpoint resume will be view-only");
                }

                // Store active state machine
                activeStateMachines.put(newSessionId, stateMachine);

                // Restore from checkpoint
                System.out.println("[DEBUG_SNAPSHOT] Restoring agent state from checkpoint");
                stateMachine.restoreFromCheckpoint(snapshot, resumeState, nudgeInstructions);

                // Force transition to resume state (bypass validation for checkpoint restoration)
                System.out.println("[DEBUG_SNAPSHOT] Force transitioning to resume state: " + resumeState);
                stateMachine.forceTransitionTo(resumeState, "Restored from checkpoint");

                // If BEFORE checkpoint, execute the state
                // If AFTER checkpoint, transition to next state and continue (if request was reconstructed)
                if (timing == com.zps.zest.testgen.snapshot.CheckpointTiming.BEFORE) {
                    System.out.println("[DEBUG_SNAPSHOT] BEFORE checkpoint - executing state with auto-flow");
                    LOG.info("Executing state from BEFORE checkpoint: " + resumeState);
                    stateMachine.enableAutoFlow();
                    stateMachine.executeCurrentState().join();
                } else {
                    System.out.println("[DEBUG_SNAPSHOT] AFTER checkpoint - agent already executed");
                    LOG.info("Resuming from AFTER checkpoint, agent already executed: " + resumeState);

                    // Check if we successfully reconstructed the request
                    if (reconstructedRequest != null) {
                        // We have the request! Can continue execution
                        System.out.println("[DEBUG_SNAPSHOT] Request available - transitioning to next state");
                        TestGenerationState nextState = getNextState(resumeState);
                        if (nextState != null) {
                            System.out.println("[DEBUG_SNAPSHOT] Transitioning to next state: " + nextState);
                            stateMachine.forceTransitionTo(nextState, "AFTER checkpoint - skipping to next state");

                            // For COORDINATOR snapshots, restore test plan and wait for user review
                            if (snapshot.getAgentType() == com.zps.zest.testgen.snapshot.AgentType.COORDINATOR
                                && snapshot.getPlanningToolsState() != null) {
                                restoreTestPlanToHandler(stateMachine, snapshot.getPlanningToolsState());
                                stateMachine.disableAutoFlow();
                                System.out.println("[DEBUG_SNAPSHOT] Resume mode: MANUAL flow - user must review scenarios and click Continue");
                                LOG.info("Resume mode: Manual flow. User must review test scenarios and click Continue to proceed.");
                            } else {
                                // For other agents (CONTEXT, TEST_WRITER, TEST_MERGER), continue automatically
                                stateMachine.enableAutoFlow();
                                System.out.println("[DEBUG_SNAPSHOT] Resume mode: AUTO flow - continuing to next state: " + nextState);
                                LOG.info("Resume mode: Auto-continuing to next state: " + nextState);
                                stateMachine.executeCurrentState();
                            }
                        } else {
                            System.out.println("[DEBUG_SNAPSHOT] No next state, staying in: " + resumeState);
                            stateMachine.disableAutoFlow();
                        }
                    } else {
                        // No request - enter view-only mode
                        System.out.println("[DEBUG_SNAPSHOT] No request available - entering view-only mode");
                        LOG.warn("Cannot continue execution without request object");
                        LOG.info("View-only mode: Checkpoint loaded for viewing chat history and debugging prompts");

                        // Mark as view-only and transition to next state to reflect actual progress
                        stateMachine.setViewOnlyMode(true);
                        stateMachine.disableAutoFlow();

                        // Transition to next state since AFTER checkpoint means agent completed
                        TestGenerationState nextState = getNextState(resumeState);
                        if (nextState != null) {
                            System.out.println("[DEBUG_SNAPSHOT] View-only: transitioning to next state: " + nextState);
                            stateMachine.forceTransitionTo(nextState,
                                "View-only mode - AFTER checkpoint completed, cannot continue without request");
                        } else {
                            System.out.println("[DEBUG_SNAPSHOT] View-only: staying in terminal state: " + resumeState);
                            LOG.info("View-only mode: already in terminal state " + resumeState);
                        }
                    }
                }

                System.out.println("[DEBUG_SNAPSHOT] Resume complete, sessionId=" + newSessionId);
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
     * Restore test plan scenarios from snapshot to handler.
     * This allows the UI to display previously generated scenarios when resuming.
     */
    private void restoreTestPlanToHandler(
            @NotNull TestGenerationStateMachine stateMachine,
            @NotNull com.zps.zest.testgen.snapshot.PlanningToolsSnapshot planningToolsState) {

        try {
            String scenariosJson = planningToolsState.getScenarios();
            if (scenariosJson == null || scenariosJson.isEmpty()) {
                LOG.warn("No scenarios found in planning tools snapshot");
                return;
            }

            // Deserialize scenarios
            com.google.gson.Gson gson = new com.google.gson.Gson();
            List<TestPlan.TestScenario> scenarios = gson.fromJson(
                scenariosJson,
                new com.google.gson.reflect.TypeToken<List<TestPlan.TestScenario>>(){}.getType()
            );

            if (scenarios == null || scenarios.isEmpty()) {
                LOG.warn("Deserialized scenarios are empty");
                return;
            }

            // Reconstruct TestPlan
            String targetClass = planningToolsState.getTargetClass() != null
                ? planningToolsState.getTargetClass()
                : "Unknown";
            List<String> targetMethods = planningToolsState.getTargetMethods();
            String reasoning = planningToolsState.getReasoning() != null
                ? planningToolsState.getReasoning()
                : "";
            String testingNotes = planningToolsState.getTestingNotes();

            // Constructor signature: targetMethods, targetClass, scenarios, dependencies, testType, reasoning
            TestPlan restoredPlan = new TestPlan(
                targetMethods,
                targetClass,
                scenarios,
                new ArrayList<>(), // dependencies - not stored in snapshot
                TestGenerationRequest.TestType.UNIT_TESTS, // default test type
                reasoning
            );

            // Set testing notes if available
            if (testingNotes != null && !testingNotes.isEmpty()) {
                restoredPlan.setTestingNotes(testingNotes);
            }

            // Get TestPlanningHandler and set the restored plan
            com.zps.zest.testgen.statemachine.handlers.TestPlanningHandler planningHandler =
                stateMachine.getCurrentHandler(com.zps.zest.testgen.statemachine.handlers.TestPlanningHandler.class);

            if (planningHandler != null) {
                // Use reflection to set the testPlan field since it's private
                try {
                    java.lang.reflect.Field testPlanField =
                        com.zps.zest.testgen.statemachine.handlers.TestPlanningHandler.class.getDeclaredField("testPlan");
                    testPlanField.setAccessible(true);
                    testPlanField.set(planningHandler, restoredPlan);

                    LOG.info("Restored test plan with " + scenarios.size() + " scenarios to handler");
                    System.out.println("[DEBUG_SNAPSHOT] Restored " + scenarios.size() +
                        " scenarios to TestPlanningHandler for user review");
                } catch (NoSuchFieldException | IllegalAccessException e) {
                    LOG.error("Failed to set test plan via reflection", e);
                }
            } else {
                LOG.warn("TestPlanningHandler not found in state machine");
            }

        } catch (Exception e) {
            LOG.error("Failed to restore test plan to handler", e);
        }
    }

    /**
     * Get the next state in the workflow after the given state
     */
    private TestGenerationState getNextState(TestGenerationState currentState) {
        switch (currentState) {
            case IDLE:
                return TestGenerationState.INITIALIZING;
            case INITIALIZING:
                return TestGenerationState.GATHERING_CONTEXT;
            case GATHERING_CONTEXT:
                return TestGenerationState.PLANNING_TESTS;
            case PLANNING_TESTS:
                return TestGenerationState.AWAITING_USER_SELECTION;
            case AWAITING_USER_SELECTION:
                return TestGenerationState.GENERATING_TESTS;
            case GENERATING_TESTS:
                return TestGenerationState.MERGING_TESTS;
            case MERGING_TESTS:
                return TestGenerationState.FIXING_TESTS;
            case FIXING_TESTS:
                return TestGenerationState.COMPLETED;
            default:
                return null; // Terminal or unknown state
        }
    }

    /**
     * Reconstruct TestGenerationRequest from snapshot metadata.
     * Returns null if metadata is missing or PSI references cannot be resolved.
     */
    @Nullable
    private TestGenerationRequest reconstructRequestFromSnapshot(@NotNull com.zps.zest.testgen.snapshot.AgentSnapshot snapshot) {
        Map<String, String> metadata = snapshot.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            LOG.warn("No metadata in snapshot, cannot reconstruct request");
            return null;
        }

        String targetFilePath = metadata.get("target_file_path");
        String methodNamesStr = metadata.get("target_method_names");
        String testTypeStr = metadata.get("test_type");

        if (targetFilePath == null || methodNamesStr == null || testTypeStr == null) {
            LOG.warn("Incomplete metadata in snapshot: targetFilePath=" + targetFilePath +
                    ", methodNames=" + methodNamesStr + ", testType=" + testTypeStr);
            return null;
        }

        try {
            // Find the virtual file
            com.intellij.openapi.vfs.VirtualFile virtualFile =
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(targetFilePath);
            if (virtualFile == null) {
                LOG.warn("Cannot find file at path: " + targetFilePath);
                return null;
            }

            // Get PsiFile
            com.intellij.psi.PsiFile psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile);
            if (!(psiFile instanceof com.intellij.psi.PsiJavaFile)) {
                LOG.warn("File is not a Java file: " + targetFilePath);
                return null;
            }

            com.intellij.psi.PsiJavaFile javaFile = (com.intellij.psi.PsiJavaFile) psiFile;

            // Find the target methods by name
            String[] methodNames = methodNamesStr.split(",");
            List<com.intellij.psi.PsiMethod> targetMethods = new ArrayList<>();

            // Get all classes in the file
            com.intellij.psi.PsiClass[] classes = javaFile.getClasses();
            if (classes.length == 0) {
                LOG.warn("No classes found in file: " + targetFilePath);
                return null;
            }

            // Search for methods in all classes
            for (com.intellij.psi.PsiClass psiClass : classes) {
                for (String methodName : methodNames) {
                    com.intellij.psi.PsiMethod[] methods = psiClass.findMethodsByName(methodName.trim(), false);
                    if (methods.length > 0) {
                        targetMethods.add(methods[0]); // Take first match
                    }
                }
            }

            if (targetMethods.isEmpty()) {
                LOG.warn("Could not find any target methods in file: " + targetFilePath);
                return null;
            }

            // Parse test type
            TestGenerationRequest.TestType testType;
            try {
                testType = TestGenerationRequest.TestType.valueOf(testTypeStr);
            } catch (IllegalArgumentException e) {
                LOG.warn("Invalid test type in metadata: " + testTypeStr);
                testType = TestGenerationRequest.TestType.UNIT_TESTS; // Default
            }

            LOG.info("Successfully reconstructed request from snapshot: " + targetMethods.size() + " methods");
            return new TestGenerationRequest(javaFile, targetMethods, null, testType, null);

        } catch (Exception e) {
            LOG.error("Failed to reconstruct request from snapshot", e);
            return null;
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

        // Enable auto-flow to allow automatic progression through states
        stateMachine.enableAutoFlow();

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