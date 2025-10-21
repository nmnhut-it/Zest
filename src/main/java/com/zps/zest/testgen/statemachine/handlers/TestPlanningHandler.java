package com.zps.zest.testgen.statemachine.handlers;

import com.zps.zest.testgen.agents.CoordinatorAgent;
import com.zps.zest.testgen.model.TestGenerationRequest;
import com.zps.zest.testgen.model.TestPlan;
import com.zps.zest.testgen.statemachine.AbstractStateHandler;
import com.zps.zest.testgen.statemachine.TestGenerationState;
import com.zps.zest.testgen.statemachine.TestGenerationStateMachine;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Handles the test planning phase of test generation.
 * Creates test scenarios and determines the testing approach.
 */
public class TestPlanningHandler extends AbstractStateHandler {

    private final Consumer<String> streamingCallback;
    private final com.zps.zest.testgen.ui.StreamingEventListener uiEventListener;
    private CoordinatorAgent coordinatorAgent;
    private TestPlan testPlan;
    private java.util.List<TestPlan.TestScenario> selectedScenarios;

    
    public TestPlanningHandler(Consumer<String> streamingCallback,
                              com.zps.zest.testgen.ui.StreamingEventListener uiEventListener) {
        super(TestGenerationState.PLANNING_TESTS);
        this.streamingCallback = streamingCallback;
        this.uiEventListener = uiEventListener;
    }
    
    @NotNull
    @Override
    protected StateResult executeState(@NotNull TestGenerationStateMachine stateMachine) {
        try {
            // Validate required data - no session data checks needed

            TestGenerationRequest request = stateMachine.getRequest();

            // Get contextTools directly from ContextGatheringHandler instead of session data
            com.zps.zest.testgen.statemachine.handlers.ContextGatheringHandler contextHandler =
                stateMachine.getHandler(TestGenerationState.GATHERING_CONTEXT, com.zps.zest.testgen.statemachine.handlers.ContextGatheringHandler.class);
            if (contextHandler == null || contextHandler.getContextAgent() == null) {
                return StateResult.failure("ContextGatheringHandler or ContextAgent not available", false);
            }
            com.zps.zest.testgen.agents.ContextAgent.ContextGatheringTools contextTools = contextHandler.getContextAgent().getContextTools();
            
            // Create coordinator agent with contextTools reference
            ZestLangChain4jService langChainService = getProject(stateMachine).getService(ZestLangChain4jService.class);
            NaiveLLMService naiveLlmService = getProject(stateMachine).getService(NaiveLLMService.class);
            
            CoordinatorAgent coordinatorAgent = new CoordinatorAgent(getProject(stateMachine), langChainService, naiveLlmService, contextTools);
            this.coordinatorAgent = coordinatorAgent; // Store as field for direct access
            
            logToolActivity(stateMachine, "CoordinatorAgent", "Analyzing testing requirements");

            // Notify UI of phase start
            if (uiEventListener != null) {
                uiEventListener.onPhaseStarted(getHandledState());
            }

            // Send initial streaming update
            if (streamingCallback != null) {
                streamingCallback.accept("📋 Starting test plan generation...\n");
            }
            
            // Set up progress callback for plan updates
            Consumer<TestPlan> planUpdateCallback = partialPlan -> {
                if (partialPlan != null) {
                    int scenarioCount = partialPlan.getScenarioCount();
                    int progressPercent = Math.min(80, 20 + (scenarioCount * 10)); // Cap at 80% during planning
                    logToolActivity(stateMachine, "CoordinatorAgent", 
                        String.format("Planning: %d scenarios identified", scenarioCount));
                    
                    // Send streaming update if callback available
                    if (streamingCallback != null) {
                        streamingCallback.accept(String.format("📝 Test plan: %d scenarios identified\n", scenarioCount));
                    }
                    
                    // Trigger UI updates for test plan
                    if (uiEventListener != null) {
                        triggerTestPlanUIUpdates(partialPlan);
                    }
                }
            };
            
            // Execute test planning with error handling
            CompletableFuture<TestPlan> planFuture = coordinatorAgent.planTests(
                request,
                planUpdateCallback
            );
            
            // Wait for planning to complete
            TestPlan testPlan = planFuture.join();
            
            if (testPlan == null) {
                return StateResult.failure("Test planning returned null result", true);
            }
            
            if (testPlan.getScenarioCount() == 0) {
                return StateResult.failure("No test scenarios were generated", true);
            }
            
            // Store test plan in handler field
            this.testPlan = testPlan;
            
            String summary = String.format("Test plan created: %d scenarios for %s",
                testPlan.getScenarioCount(), testPlan.getTargetClass());
            LOG.info(summary);

            // Always pause for user review of test plan
            TestGenerationState nextState = TestGenerationState.AWAITING_USER_SELECTION;

            // Disable auto-flow temporarily for user review
            stateMachine.disableAutoFlow();

            // Request user input for test plan review
            requestUserInput(stateMachine, "test_plan_review",
                "Please review the test plan and testing approach", testPlan);

            return StateResult.success(testPlan, summary, nextState);
            
        } catch (Exception e) {
            LOG.error("Test planning failed", e);
            
            // Check if this is a recoverable error
            boolean recoverable = isRecoverableError(e);
            
            return StateResult.failure(e, recoverable, 
                "Failed to create test plan: " + e.getMessage());
        }
    }
    
    /**
     * Determine if an error during test planning is recoverable
     */
    private boolean isRecoverableError(@NotNull Exception e) {
        // JSON parsing errors from LLM responses are usually recoverable
        if (e.getCause() instanceof com.google.gson.JsonSyntaxException) {
            return true;
        }
        
        // Network timeouts and connection issues are recoverable
        if (e.getMessage() != null && 
            (e.getMessage().contains("timeout") || 
             e.getMessage().contains("connection") ||
             e.getMessage().contains("network"))) {
            return true;
        }
        
        // LangChain4j-related errors are usually recoverable (retry with different prompt)
        if (e.getClass().getName().contains("langchain4j")) {
            return true;
        }
        
        // Context-related errors might not be recoverable
        if (e.getMessage() != null && e.getMessage().contains("context")) {
            return false;
        }
        
        // Default to recoverable for most exceptions
        return true;
    }
    
    @Override
    public boolean isRetryable() {
        return true;
    }
    
    @Override
    public boolean isSkippable() {
        return true; // Test planning can be skipped if user provides manual test plan
    }

    @Override
    public void cancel() {
        super.cancel();
        if (coordinatorAgent != null) {
            coordinatorAgent.cancel();
            LOG.info("Cancelled CoordinatorAgent");
        }
    }

    /**
     * Get the coordinator agent (direct access instead of session data)
     */
    @Nullable
    public CoordinatorAgent getCoordinatorAgent() {
        return coordinatorAgent;
    }

    /**
     * Get the test plan
     */
    @Nullable
    public TestPlan getTestPlan() {
        return testPlan;
    }

    /**
     * Get selected scenarios
     */
    @Nullable
    public java.util.List<TestPlan.TestScenario> getSelectedScenarios() {
        return selectedScenarios;
    }

    /**
     * Set selected scenarios (called from service when user makes selection)
     */
    public void setSelectedScenarios(@NotNull java.util.List<TestPlan.TestScenario> selectedScenarios) {
        this.selectedScenarios = selectedScenarios;
    }

    /**
     * Update testing notes from user edits
     */
    public void updateTestingNotes(@NotNull String editedNotes) {
        if (testPlan != null) {
            testPlan.setTestingNotes(editedNotes);
        }
    }

    /**
     * Trigger UI updates for test plan
     */
    private void triggerTestPlanUIUpdates(TestPlan testPlan) {
        try {
            // Use the existing factory method to create display data (Kotlin companion object)
            com.zps.zest.testgen.ui.model.TestPlanDisplayData planDisplayData =
                com.zps.zest.testgen.ui.model.TestPlanDisplayData.Companion.fromTestPlan(testPlan);

            // Trigger UI update
            uiEventListener.onTestPlanUpdated(planDisplayData);

        } catch (Exception e) {
            LOG.warn("Error triggering test plan UI updates", e);
        }
    }
}