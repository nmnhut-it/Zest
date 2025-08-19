package com.zps.zest.testgen.statemachine.handlers;

import com.zps.zest.testgen.agents.CoordinatorAgent;
import com.zps.zest.testgen.model.TestContext;
import com.zps.zest.testgen.model.TestGenerationRequest;
import com.zps.zest.testgen.model.TestPlan;
import com.zps.zest.testgen.statemachine.AbstractStateHandler;
import com.zps.zest.testgen.statemachine.TestGenerationState;
import com.zps.zest.testgen.statemachine.TestGenerationStateMachine;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.util.LLMService;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Handles the test planning phase of test generation.
 * Creates test scenarios and determines the testing approach.
 */
public class TestPlanningHandler extends AbstractStateHandler {
    
    private final Consumer<String> streamingCallback;
    private final com.zps.zest.testgen.ui.StreamingEventListener uiEventListener;
    
    public TestPlanningHandler() {
        this(null, null);
    }
    
    public TestPlanningHandler(Consumer<String> streamingCallback) {
        this(streamingCallback, null);
    }
    
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
            // Validate required data
            if (!hasRequiredData(stateMachine, "request", "context")) {
                return StateResult.failure("Missing required data: request or context", false);
            }
            
            TestGenerationRequest request = (TestGenerationRequest) getSessionData(stateMachine, "request");
            TestContext context = (TestContext) getSessionData(stateMachine, "context");
            
            // Initialize coordinator agent
            ZestLangChain4jService langChainService = getProject(stateMachine).getService(ZestLangChain4jService.class);
            LLMService llmService = getProject(stateMachine).getService(LLMService.class);
            CoordinatorAgent coordinatorAgent = new CoordinatorAgent(getProject(stateMachine), langChainService, llmService);
            
            logToolActivity(stateMachine, "CoordinatorAgent", "Analyzing testing requirements");
            
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
                context,
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
            
            // Store test plan in session data
            setSessionData(stateMachine, "testPlan", testPlan);
            setSessionData(stateMachine, "workflowPhase", "planning");
            
            String summary = String.format("Test plan created: %d scenarios for %s", 
                testPlan.getScenarioCount(), testPlan.getTargetClass());
            LOG.info(summary);
            
            // Determine next state based on scenario count
            TestGenerationState nextState;
            if (testPlan.getScenarioCount() > 1) {
                // Multiple scenarios - require user selection (pause auto-flow)
                nextState = TestGenerationState.AWAITING_USER_SELECTION;
                
                // Disable auto-flow temporarily for user selection
                stateMachine.disableAutoFlow();
                
                // Request user input for scenario selection
                requestUserInput(stateMachine, "scenario_selection", 
                    "Please select which test scenarios to generate", testPlan);
            } else {
                // Single scenario - proceed directly to generation (keep auto-flow)
                nextState = TestGenerationState.GENERATING_TESTS;
                setSessionData(stateMachine, "selectedScenarios", testPlan.getTestScenarios());
            }
            
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