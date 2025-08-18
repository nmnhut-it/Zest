package com.zps.zest.testgen.statemachine.handlers;

import com.zps.zest.testgen.agents.PSITestMergerAgent;
import com.zps.zest.testgen.agents.TestMergerAgent;
import com.zps.zest.testgen.model.*;
import com.zps.zest.testgen.statemachine.AbstractStateHandler;
import com.zps.zest.testgen.statemachine.TestGenerationState;
import com.zps.zest.testgen.statemachine.TestGenerationStateMachine;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.util.LLMService;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Handles the test merging phase of test generation.
 * Combines generated test methods into a complete test class.
 */
public class TestMergingHandler extends AbstractStateHandler {
    
    // Configuration for merger type (could be made configurable)
    private static final boolean USE_PSI_MERGER = true;
    
    private final Consumer<String> streamingCallback;
    
    public TestMergingHandler() {
        this(null);
    }
    
    public TestMergingHandler(Consumer<String> streamingCallback) {
        super(TestGenerationState.MERGING_TESTS);
        this.streamingCallback = streamingCallback;
    }
    
    @NotNull
    @Override
    protected StateResult executeState(@NotNull TestGenerationStateMachine stateMachine) {
        try {
            // Validate required data
            if (!hasRequiredData(stateMachine, "testGenerationResult", "context")) {
                return StateResult.failure("Missing required data for test merging", false);
            }
            
            TestGenerationResult result = (TestGenerationResult) getSessionData(stateMachine, "testGenerationResult");
            TestContext context = (TestContext) getSessionData(stateMachine, "context");
            
            updateProgress(stateMachine, 10, "Preparing test merging");
            
            // Send initial streaming update
            if (streamingCallback != null) {
                streamingCallback.accept("🔗 Merging generated tests into final test class...\n");
            }
            
            MergedTestClass mergedTestClass;
            
            if (USE_PSI_MERGER) {
                mergedTestClass = executePSIMerging(stateMachine, result, context);
            } else {
                mergedTestClass = executeLLMMerging(stateMachine, result, context);
            }
            
            if (mergedTestClass == null) {
                return StateResult.failure("Test merging returned null result", true);
            }
            
            // Store merged result in session data
            setSessionData(stateMachine, "mergedTestClass", mergedTestClass);
            setSessionData(stateMachine, "workflowPhase", "merging");
            
            // Update session with final result
            TestGenerationSession session = (TestGenerationSession) getSessionData(stateMachine, "session");
            if (session != null) {
                session.setMergedTestClass(mergedTestClass);
                session.setStatus(TestGenerationSession.Status.COMPLETED);
            }
            
            String summary = String.format("Test merging completed: %s with %d methods (%s)", 
                mergedTestClass.getClassName(), 
                mergedTestClass.getMethodCount(),
                USE_PSI_MERGER ? "PSI merger" : "LLM merger");
            LOG.info(summary);
            
            return StateResult.success(mergedTestClass, summary, TestGenerationState.COMPLETED);
            
        } catch (Exception e) {
            LOG.error("Test merging failed", e);
            
            // Check if this is a recoverable error
            boolean recoverable = isRecoverableError(e);
            
            return StateResult.failure(e, recoverable, 
                "Failed to merge tests: " + e.getMessage());
        }
    }
    
    /**
     * Execute PSI-based merging (faster, no LLM calls)
     */
    private MergedTestClass executePSIMerging(@NotNull TestGenerationStateMachine stateMachine,
                                            @NotNull TestGenerationResult result,
                                            @NotNull TestContext context) throws Exception {
        
        updateProgress(stateMachine, 20, "Using PSI-based merger (fast)");
        
        PSITestMergerAgent psiMerger = new PSITestMergerAgent(getProject(stateMachine));
        
        CompletableFuture<MergedTestClass> mergeFuture = psiMerger.mergeTests(result, context);
        
        // Wait for PSI merging to complete (should be fast)
        return waitForMerging(stateMachine, mergeFuture, "PSI merging");
    }
    
    /**
     * Execute LLM-based merging (slower but handles conflicts better)
     */
    private MergedTestClass executeLLMMerging(@NotNull TestGenerationStateMachine stateMachine,
                                            @NotNull TestGenerationResult result,
                                            @NotNull TestContext context) throws Exception {
        
        updateProgress(stateMachine, 20, "Using LLM-based merger (with conflict resolution)");
        
        ZestLangChain4jService langChainService = getProject(stateMachine).getService(ZestLangChain4jService.class);
        LLMService llmService = getProject(stateMachine).getService(LLMService.class);
        TestMergerAgent llmMerger = new TestMergerAgent(getProject(stateMachine), langChainService, llmService);
        
        CompletableFuture<MergedTestClass> mergeFuture = llmMerger.mergeTests(result, context);
        
        // Wait for LLM merging to complete
        return waitForMerging(stateMachine, mergeFuture, "LLM merging");
    }
    
    /**
     * Wait for merging to complete with progress updates
     */
    private MergedTestClass waitForMerging(@NotNull TestGenerationStateMachine stateMachine,
                                         @NotNull CompletableFuture<MergedTestClass> future,
                                         @NotNull String mergerType) throws Exception {
        
        int progressPercent = 30;
        int maxWaitSeconds = USE_PSI_MERGER ? 30 : 120; // PSI is much faster
        int waitedSeconds = 0;
        
        while (!future.isDone() && waitedSeconds < maxWaitSeconds) {
            if (shouldCancel(stateMachine)) {
                future.cancel(true);
                throw new InterruptedException("Test merging cancelled");
            }
            
            // Update progress based on time elapsed
            waitedSeconds += 2;
            progressPercent = Math.min(90, 30 + (waitedSeconds * 60 / maxWaitSeconds));
            
            updateProgress(stateMachine, progressPercent, 
                String.format("%s in progress...", mergerType));
            
            Thread.sleep(2000); // Wait 2 seconds between progress updates
        }
        
        if (!future.isDone()) {
            future.cancel(true);
            throw new RuntimeException(mergerType + " timed out after " + maxWaitSeconds + " seconds");
        }
        
        updateProgress(stateMachine, 100, mergerType + " completed");
        return future.join();
    }
    
    /**
     * Determine if an error during test merging is recoverable
     */
    private boolean isRecoverableError(@NotNull Exception e) {
        // PSI errors are usually not recoverable (file system issues)
        if (USE_PSI_MERGER && e.getMessage() != null && 
            (e.getMessage().contains("PSI") || e.getMessage().contains("file"))) {
            return false;
        }
        
        // JSON parsing errors from LLM responses are recoverable (switch to PSI merger)
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
        return false; // Merging cannot be skipped - it's required for final output
    }
}