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
    
    // Always try PSI first, then fallback to LLM automatically
    
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
            
            logToolActivity(stateMachine, "TestMerger", "Preparing test merging");
            
            // Send initial streaming update
            if (streamingCallback != null) {
                streamingCallback.accept("🔗 Merging generated tests into final test class...\n");
            }
            
            MergedTestClass mergedTestClass = null;
            String mergerUsed = "PSI";
            
            // Always try PSI merger first (it's fast)
            try {
                logToolActivity(stateMachine, "PSITestMerger", "Attempting fast PSI-based merge");
                mergedTestClass = executePSIMerging(stateMachine, result, context);
                logToolActivity(stateMachine, "PSITestMerger", "✅ PSI merge successful");
            } catch (Exception psiException) {
                // PSI failed, try LLM merger as fallback
                LOG.warn("PSI merger failed, attempting LLM-based merger", psiException);
                logToolActivity(stateMachine, "TestMerger", 
                    "⚠️ PSI merger failed: " + psiException.getMessage() + " - trying LLM merger");
                
                try {
                    mergedTestClass = executeLLMMerging(stateMachine, result, context);
                    mergerUsed = "LLM (PSI fallback)";
                    logToolActivity(stateMachine, "TestMergerAgent", "✅ LLM merge successful");
                } catch (Exception llmException) {
                    // Both failed - this is still recoverable (user can retry)
                    LOG.error("Both PSI and LLM mergers failed", llmException);
                    return StateResult.failure(
                        new Exception("Both mergers failed. PSI: " + psiException.getMessage() + 
                                     ", LLM: " + llmException.getMessage()),
                        true,  // recoverable = true (allows retry without FAILED state)
                        "Test merging failed with both PSI and LLM approaches"
                    );
                }
            }
            
            if (mergedTestClass == null) {
                return StateResult.failure(
                    new Exception("Merger returned null result"), 
                    true, 
                    "Test merging returned null result"
                );
            }
            
            // Store merged result in session data
            setSessionData(stateMachine, "mergedTestClass", mergedTestClass);
            setSessionData(stateMachine, "workflowPhase", "merging");
            setSessionData(stateMachine, "mergerUsed", mergerUsed);  // Track which merger succeeded
            
            // Update session with final result
            TestGenerationSession session = (TestGenerationSession) getSessionData(stateMachine, "session");
            if (session != null) {
                session.setMergedTestClass(mergedTestClass);
                session.setStatus(TestGenerationSession.Status.COMPLETED);
            }
            
            String summary = String.format("Test merging completed: %s with %d methods (using %s)", 
                mergedTestClass.getClassName(), 
                mergedTestClass.getMethodCount(),
                mergerUsed);
            LOG.info(summary);
            
            return StateResult.success(mergedTestClass, summary, TestGenerationState.COMPLETED);
            
        } catch (Exception e) {
            LOG.error("Test merging failed", e);
            
            // Always mark as recoverable to avoid FAILED state - user can retry
            return StateResult.failure(e, true, 
                "Failed to merge tests: " + e.getMessage());
        }
    }
    
    /**
     * Execute PSI-based merging (faster, no LLM calls)
     */
    private MergedTestClass executePSIMerging(@NotNull TestGenerationStateMachine stateMachine,
                                            @NotNull TestGenerationResult result,
                                            @NotNull TestContext context) throws Exception {
        
        logToolActivity(stateMachine, "PSITestMerger", "Using PSI-based merger (fast)");
        
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
        
        logToolActivity(stateMachine, "TestMergerAgent", "Using LLM-based merger (with conflict resolution)");
        
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
        // Determine timeout based on merger type
        int maxWaitSeconds = mergerType.contains("PSI") ? 30 : 120; // PSI is much faster
        int waitedSeconds = 0;
        
        while (!future.isDone() && waitedSeconds < maxWaitSeconds) {
            if (shouldCancel(stateMachine)) {
                future.cancel(true);
                throw new InterruptedException("Test merging cancelled");
            }
            
            // Update progress based on time elapsed
            waitedSeconds += 2;
            progressPercent = Math.min(90, 30 + (waitedSeconds * 60 / maxWaitSeconds));
            
            logToolActivity(stateMachine, "TestMerger", 
                String.format("%s in progress...", mergerType));
            
            Thread.sleep(2000); // Wait 2 seconds between progress updates
        }
        
        if (!future.isDone()) {
            future.cancel(true);
            throw new RuntimeException(mergerType + " timed out after " + maxWaitSeconds + " seconds");
        }
        
        logToolActivity(stateMachine, "TestMerger", mergerType + " completed");
        return future.join();
    }
    
    /**
     * Determine if an error during test merging is recoverable
     * NOTE: This method is now unused since we always return recoverable=true
     * to avoid FAILED state transitions and use automatic fallback instead.
     */
    private boolean isRecoverableError(@NotNull Exception e) {
        // This method is kept for compatibility but not used
        // We now handle PSI failures by automatically falling back to LLM merger
        
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