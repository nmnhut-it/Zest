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

    private static final boolean DEBUG_HANDLER_EXECUTION = true;

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
        if (DEBUG_HANDLER_EXECUTION) {
            System.out.println("[DEBUG_HANDLER] TestGenerationHandler.executeState START, sessionId=" +
                stateMachine.getSessionId());
        }

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

            // Save BEFORE checkpoint - capture state before test generation
            try {
                TestGenerationRequest request = stateMachine.getRequest();
                String promptDescription = request != null ?
                    "Generate tests for " + request.getTargetFile().getName() +
                        " (" + request.getTargetMethods().size() + " methods)" :
                    "Test generation";
                com.zps.zest.testgen.snapshot.AgentSnapshot beforeSnapshot = testWriterAgent.exportSnapshot(
                    stateMachine.getSessionId(),
                    "Before test generation - about to generate " + selectedScenarios.size() + " test methods",
                    promptDescription
                );
                java.io.File snapshotFile = com.zps.zest.testgen.snapshot.AgentSnapshotSerializer.saveCheckpoint(
                    beforeSnapshot,
                    getProject(stateMachine),
                    com.zps.zest.testgen.snapshot.CheckpointTiming.BEFORE
                );
                LOG.info("Saved BEFORE checkpoint: " + snapshotFile.getName());
            } catch (Exception e) {
                LOG.warn("Failed to save BEFORE checkpoint (non-critical)", e);
            }

            // Set the UI event listener for streaming
            if (uiEventListener != null) {
                testWriterAgent.setEventListener(uiEventListener);
            }

            // Set the streaming consumer for console output
            if (streamingCallback != null) {
                testWriterAgent.setStreamingConsumer(streamingCallback);
            }

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

            // Copy the testing notes (which may have been edited by user)
            filteredPlan.setTestingNotes(originalPlan.getTestingNotes());
            
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

            // Save AFTER checkpoint for debugging and prompt experimentation
            try {
                TestGenerationRequest request = stateMachine.getRequest();
                String promptDescription = request != null ?
                    "Generate tests for " + request.getTargetFile().getName() +
                        " (" + request.getTargetMethods().size() + " methods)" :
                    "Test generation";
                com.zps.zest.testgen.snapshot.AgentSnapshot snapshot = testWriterAgent.exportSnapshot(
                    stateMachine.getSessionId(),
                    "After test generation - " + result.getMethodCount() + " test methods generated",
                    promptDescription
                );
                java.io.File snapshotFile = com.zps.zest.testgen.snapshot.AgentSnapshotSerializer.saveCheckpoint(
                    snapshot,
                    getProject(stateMachine),
                    com.zps.zest.testgen.snapshot.CheckpointTiming.AFTER
                );
                LOG.info("Saved AFTER checkpoint: " + snapshotFile.getName());
            } catch (Exception e) {
                LOG.warn("Failed to save AFTER checkpoint (non-critical)", e);
            }

            if (DEBUG_HANDLER_EXECUTION) {
                System.out.println("[DEBUG_HANDLER] TestGenerationHandler SUCCESS: " + summary +
                    ", nextState=MERGING_TESTS, sessionId=" + stateMachine.getSessionId());
            }
            return StateResult.success(result, summary, TestGenerationState.MERGING_TESTS);

        } catch (Exception e) {
            if (DEBUG_HANDLER_EXECUTION) {
                System.out.println("[DEBUG_HANDLER] TestGenerationHandler EXCEPTION: " +
                    e.getMessage() + ", sessionId=" + stateMachine.getSessionId());
            }
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
            // Send complete test class to UI (simplified display data)
            String completeTestClass = result.getCompleteTestClass();
            if (completeTestClass != null && !completeTestClass.isEmpty()) {
                com.zps.zest.testgen.ui.model.GeneratedTestDisplayData displayData =
                    new com.zps.zest.testgen.ui.model.GeneratedTestDisplayData(
                        result.getClassName(), // className
                        completeTestClass, // fullTestCode
                        System.currentTimeMillis() // timestamp
                    );

                // Trigger UI update with complete test class
                uiEventListener.onTestGenerated(displayData);
            } else {
                // Fallback: if no complete test class, build one from components
                StringBuilder classBuilder = new StringBuilder();
                classBuilder.append("package ").append(result.getPackageName()).append(";\n\n");

                // Add imports
                for (String importStatement : result.getImports()) {
                    classBuilder.append("import ").append(importStatement).append(";\n");
                }
                classBuilder.append("\n");

                // Add class declaration
                classBuilder.append("public class ").append(result.getClassName()).append(" {\n\n");

                // Add test methods
                for (com.zps.zest.testgen.model.GeneratedTestMethod testMethod : result.getTestMethods()) {
                    for (String annotation : testMethod.getAnnotations()) {
                        classBuilder.append("    @").append(annotation).append("\n");
                    }
                    classBuilder.append("    public void ").append(testMethod.getMethodName()).append("() {\n");
                    String[] lines = testMethod.getMethodBody().split("\n");
                    for (String line : lines) {
                        if (!line.trim().isEmpty()) {
                            classBuilder.append("        ").append(line).append("\n");
                        }
                    }
                    classBuilder.append("    }\n\n");
                }

                classBuilder.append("}\n");

                com.zps.zest.testgen.ui.model.GeneratedTestDisplayData displayData =
                    new com.zps.zest.testgen.ui.model.GeneratedTestDisplayData(
                        result.getClassName(), // className
                        classBuilder.toString(), // fullTestCode
                        System.currentTimeMillis() // timestamp
                    );

                // Trigger UI update
                uiEventListener.onTestGenerated(displayData);
            }
            
        } catch (Exception e) {
            LOG.warn("Error triggering generated test UI updates", e);
        }
    }
    
    @Override
    public void cancel() {
        super.cancel();
        if (testWriterAgent != null) {
            testWriterAgent.cancel();
            LOG.info("Cancelled TestWriterAgent");
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
     * Set the test writer agent (for checkpoint restoration)
     */
    public void setTestWriterAgent(@NotNull TestWriterAgent agent) {
        this.testWriterAgent = agent;
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