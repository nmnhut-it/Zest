package com.zps.zest.testgen.statemachine.handlers;

import com.zps.zest.testgen.agents.TestWriterAgent;
import com.zps.zest.testgen.model.*;
import com.zps.zest.testgen.statemachine.AbstractStateHandler;
import com.zps.zest.testgen.statemachine.TestGenerationState;
import com.zps.zest.testgen.statemachine.TestGenerationStateMachine;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Handles the test generation phase of test generation.
 * Creates actual test methods based on selected scenarios.
 */
public class TestGenerationHandler extends AbstractStateHandler {

    private final Consumer<String> streamingCallback;
    private final com.zps.zest.testgen.ui.StreamingEventListener uiEventListener;
    private TestWriterAgent testWriterAgent;
    private TestGenerationResult testGenerationResult;
    private TestPlan filteredTestPlan;
    
    public TestGenerationHandler() {
        this(null, null);
    }
    
    public TestGenerationHandler(Consumer<String> streamingCallback) {
        this(streamingCallback, null);
    }
    
    public TestGenerationHandler(Consumer<String> streamingCallback,
                                com.zps.zest.testgen.ui.StreamingEventListener uiEventListener) {
        super(TestGenerationState.GENERATING_TESTS);
        this.streamingCallback = streamingCallback;
        this.uiEventListener = uiEventListener;
    }
    
    @NotNull
    @Override
    protected StateResult executeState(@NotNull TestGenerationStateMachine stateMachine) {
        try {
            // Validate required data - no session data checks needed
            
            // Get data from other handlers
            com.zps.zest.testgen.statemachine.handlers.TestPlanningHandler planningHandler =
                stateMachine.getHandler(TestGenerationState.PLANNING_TESTS, com.zps.zest.testgen.statemachine.handlers.TestPlanningHandler.class);
            com.zps.zest.testgen.statemachine.handlers.ContextGatheringHandler contextHandler =
                stateMachine.getHandler(TestGenerationState.GATHERING_CONTEXT, com.zps.zest.testgen.statemachine.handlers.ContextGatheringHandler.class);

            TestPlan originalPlan = planningHandler != null ? planningHandler.getTestPlan() : null;
            com.zps.zest.testgen.agents.ContextAgent.ContextGatheringTools contextTools =
                contextHandler != null && contextHandler.getContextAgent() != null ? contextHandler.getContextAgent().getContextTools() : null;
            List<TestPlan.TestScenario> selectedScenarios = planningHandler != null ? planningHandler.getSelectedScenarios() : null;
            
            // Initialize test writer agent
            ZestLangChain4jService langChainService = getProject(stateMachine).getService(ZestLangChain4jService.class);
            NaiveLLMService naiveLlmService = getProject(stateMachine).getService(NaiveLLMService.class);
            TestWriterAgent testWriterAgent = new TestWriterAgent(getProject(stateMachine), langChainService, naiveLlmService);

            // Store as field for direct access
            this.testWriterAgent = testWriterAgent;
            
            logToolActivity(stateMachine, "TestWriterAgent", "Preparing test generation");

            // Notify UI of phase start
            if (uiEventListener != null) {
                uiEventListener.onPhaseStarted(getHandledState());
            }

            // Send initial streaming update
            if (streamingCallback != null) {
                streamingCallback.accept(String.format("⚡ Generating tests for %d scenarios...\n", selectedScenarios.size()));
            }
            
            // Create filtered test plan with only selected scenarios
            TestPlan filteredPlan = new TestPlan(
                originalPlan.getTargetMethods(),
                originalPlan.getTargetClass(),
                selectedScenarios,
                originalPlan.getDependencies(),
                originalPlan.getRecommendedTestType(),
                originalPlan.getReasoning() + "\n[User selected " + selectedScenarios.size() + 
                    " of " + originalPlan.getScenarioCount() + " scenarios]"
            );
            
            logToolActivity(stateMachine, "TestWriterAgent", "Generating test methods");
            
            // Execute test generation with enhanced error handling
            CompletableFuture<TestGenerationResult> generationFuture = testWriterAgent.generateTests(
                filteredPlan,
                contextTools
            );
            
            // Wait for generation to complete with progress updates
            TestGenerationResult result = waitForGenerationWithProgress(stateMachine, generationFuture, selectedScenarios.size());
            
            if (result == null) {
                return StateResult.failure("Test generation returned null result", true);
            }
            
            if (result.getMethodCount() == 0) {
                return StateResult.failure("No test methods were generated", true);
            }
            
            // Store results in handler fields
            this.testGenerationResult = result;
            this.filteredTestPlan = filteredPlan;
            
            // Trigger UI updates for generated tests
            if (uiEventListener != null) {
                triggerGeneratedTestUIUpdates(result, selectedScenarios);
            }
            
            String summary = String.format("Generated %d test methods for %d scenarios", 
                result.getMethodCount(), selectedScenarios.size());
            LOG.info(summary);
            
            return StateResult.success(result, summary, TestGenerationState.MERGING_TESTS);
            
        } catch (Exception e) {
            LOG.error("Test generation failed", e);
            
            // Check if this is a recoverable error
            boolean recoverable = isRecoverableError(e);
            
            return StateResult.failure(e, recoverable, 
                "Failed to generate tests: " + e.getMessage());
        }
    }
    
    /**
     * Wait for generation to complete with progress updates
     */
    private TestGenerationResult waitForGenerationWithProgress(
            @NotNull TestGenerationStateMachine stateMachine,
            @NotNull CompletableFuture<TestGenerationResult> future,
            int expectedScenarios) throws Exception {
        
        int progressPercent = 20;
        int maxWaitSeconds = 300; // 5 minutes max wait
        int waitedSeconds = 0;
        
        while (!future.isDone() && waitedSeconds < maxWaitSeconds) {
            if (shouldCancel(stateMachine)) {
                future.cancel(true);
                throw new InterruptedException("Test generation cancelled");
            }
            
            // Update progress based on time elapsed
            waitedSeconds += 2;
            progressPercent = Math.min(80, 20 + (waitedSeconds * 60 / maxWaitSeconds));
            
            logToolActivity(stateMachine, "TestWriterAgent", 
                String.format("Generating tests... (%d/%d scenarios)", 
                    Math.min(waitedSeconds / 10, expectedScenarios), expectedScenarios));
            
            Thread.sleep(2000); // Wait 2 seconds between progress updates
        }
        
        if (!future.isDone()) {
            future.cancel(true);
            throw new RuntimeException("Test generation timed out after " + maxWaitSeconds + " seconds");
        }
        
        return future.join();
    }
    
    /**
     * Determine if an error during test generation is recoverable
     */
    private boolean isRecoverableError(@NotNull Exception e) {
        // JSON parsing errors from LLM responses are usually recoverable
        if (e.getCause() instanceof com.google.gson.JsonSyntaxException) {
            LOG.info("JSON parsing error detected - this is recoverable");
            return true;
        }
        
        // Check for specific JSON error patterns in the message
        if (e.getMessage() != null && 
            (e.getMessage().contains("Expected BEGIN_OBJECT but was STRING") ||
             e.getMessage().contains("JsonSyntaxException") ||
             e.getMessage().contains("JSON"))) {
            LOG.info("JSON-related error detected - this is recoverable");
            return true;
        }
        
        // Network timeouts and connection issues are recoverable
        if (e.getMessage() != null && 
            (e.getMessage().contains("timeout") || 
             e.getMessage().contains("connection") ||
             e.getMessage().contains("network"))) {
            return true;
        }
        
        // LangChain4j-related errors are usually recoverable
        if (e.getClass().getName().contains("langchain4j")) {
            return true;
        }
        
        // Thread interruption (cancellation) is not recoverable
        if (e instanceof InterruptedException) {
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
        return true; // Test generation can be skipped if user provides manual tests
    }
    
    /**
     * Trigger UI updates for generated tests
     */
    private void triggerGeneratedTestUIUpdates(TestGenerationResult result, List<TestPlan.TestScenario> scenarios) {
        try {
            // Create display data for each generated test method
            java.util.List<com.zps.zest.testgen.model.GeneratedTestMethod> testMethods = result.getTestMethods();
            
            for (int i = 0; i < testMethods.size() && i < scenarios.size(); i++) {
                com.zps.zest.testgen.model.GeneratedTestMethod testMethod = testMethods.get(i);
                TestPlan.TestScenario scenario = scenarios.get(i);
                
                com.zps.zest.testgen.ui.model.GeneratedTestDisplayData displayData = 
                    new com.zps.zest.testgen.ui.model.GeneratedTestDisplayData(
                        testMethod.getMethodName(), // testName
                        "scenario_" + scenario.getName().hashCode(), // scenarioId  
                        scenario.getName(), // scenarioName
                        testMethod.getMethodBody(), // testCode
                        com.zps.zest.testgen.ui.model.GeneratedTestDisplayData.ValidationStatus.NOT_VALIDATED, // validationStatus
                        new java.util.ArrayList<>(), // validationMessages
                        testMethod.getMethodBody().split("\n").length, // lineCount
                        System.currentTimeMillis(), // timestamp
                        null // completeClassContext - not available in this legacy handler
                    );
                
                // Trigger UI update
                uiEventListener.onTestGenerated(displayData);
            }
            
        } catch (Exception e) {
            LOG.warn("Error triggering generated test UI updates", e);
        }
    }
    
    /**
     * Get the test writer agent (direct access instead of session data)
     */
    @Nullable
    public TestWriterAgent getTestWriterAgent() {
        return testWriterAgent;
    }

    /**
     * Get the test generation result
     */
    @Nullable
    public TestGenerationResult getTestGenerationResult() {
        return testGenerationResult;
    }

    /**
     * Get the filtered test plan
     */
    @Nullable
    public TestPlan getFilteredTestPlan() {
        return filteredTestPlan;
    }

    /**
     * Extract test method name from generated code
     */
    private String extractTestMethodName(String testMethodCode) {
        try {
            // Simple regex to extract method name
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("void\\s+(\\w+)\\s*\\(");
            java.util.regex.Matcher matcher = pattern.matcher(testMethodCode);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return "testMethod"; // Fallback
        } catch (Exception e) {
            return "testMethod"; // Fallback
        }
    }
}