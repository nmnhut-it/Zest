package com.zps.zest.testgen.statemachine.handlers;

import com.zps.zest.testgen.agents.AITestMergerAgent;
import com.zps.zest.testgen.model.*;
import com.zps.zest.testgen.statemachine.AbstractStateHandler;
import com.zps.zest.testgen.statemachine.TestGenerationState;
import com.zps.zest.testgen.statemachine.TestGenerationStateMachine;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Handles the test merging phase of test generation.
 * Uses AI-based merging to create complete, merged test classes.
 */
public class TestMergingHandler extends AbstractStateHandler {
    
    private final Consumer<String> streamingCallback;
    private final com.zps.zest.testgen.ui.StreamingEventListener uiEventListener;
    
    public TestMergingHandler() {
        this(null, null);
    }
    
    public TestMergingHandler(Consumer<String> streamingCallback) {
        this(streamingCallback, null);
    }
    
    public TestMergingHandler(Consumer<String> streamingCallback,
                             com.zps.zest.testgen.ui.StreamingEventListener uiEventListener) {
        super(TestGenerationState.MERGING_TESTS);
        this.streamingCallback = streamingCallback;
        this.uiEventListener = uiEventListener;
    }
    
    @NotNull
    @Override
    protected StateResult executeState(@NotNull TestGenerationStateMachine stateMachine) {
        try {
            // Validate required data
            if (!hasRequiredData(stateMachine, "testGenerationResult", "contextTools")) {
                return StateResult.failure("Missing required data for test merging", false);
            }
            
            TestGenerationResult result = (TestGenerationResult) getSessionData(stateMachine, "testGenerationResult");
            com.zps.zest.testgen.agents.ContextAgent.ContextGatheringTools contextTools = 
                (com.zps.zest.testgen.agents.ContextAgent.ContextGatheringTools) getSessionData(stateMachine, "contextTools");
            
            logToolActivity(stateMachine, "TestMerger", "Preparing test merging");

            // Notify UI of phase start
            if (uiEventListener != null) {
                uiEventListener.onPhaseStarted(getHandledState());
            }

            // Send initial streaming update
            if (streamingCallback != null) {
                streamingCallback.accept("🤖 AI-based test merging starting...\n");
            }
            
            // Use AI-based merging for complete test class generation
            logToolActivity(stateMachine, "AITestMerger", "Starting AI-based test merging");
            
            ZestLangChain4jService langChainService = getProject(stateMachine).getService(ZestLangChain4jService.class);
            NaiveLLMService naiveLlmService = getProject(stateMachine).getService(NaiveLLMService.class);
            AITestMergerAgent aiMerger = new AITestMergerAgent(getProject(stateMachine), langChainService, naiveLlmService);
            
            // Store AITestMergerAgent in session data for chat memory access
            setSessionData(stateMachine, "aiMergerAgent", aiMerger);
            
            CompletableFuture<MergedTestClass> mergeFuture = aiMerger.mergeTests(result, contextTools);
            
            MergedTestClass mergedTestClass = waitForMerging(stateMachine, mergeFuture, "AI merging");
            
            // Trigger UI update for merged test class
            if (uiEventListener != null && mergedTestClass != null) {
                uiEventListener.onMergedTestClassUpdated(mergedTestClass);
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
            setSessionData(stateMachine, "mergerUsed", "AI");  // Using AI-based merging
            
            // Update session with final result
            TestGenerationSession session = (TestGenerationSession) getSessionData(stateMachine, "session");
            if (session != null) {
                session.setMergedTestClass(mergedTestClass);
                session.setStatus(TestGenerationSession.Status.COMPLETED);
            }
            
            String summary = String.format("AI test merging and review completed: %s with %d methods", 
                mergedTestClass.getClassName(), 
                mergedTestClass.getMethodCount());
            LOG.info(summary);
            
            // Transition directly to COMPLETED since merging now includes error review and fixing
            return StateResult.success(mergedTestClass, summary, TestGenerationState.COMPLETED);
            
        } catch (Exception e) {
            LOG.error("AI test merging failed", e);
            
            // Mark as recoverable to allow retry
            return StateResult.failure(e, true, 
                "Failed to merge tests with AI: " + e.getMessage());
        }
    }
    
    /**
     * Wait for merging to complete with progress updates
     */
    private MergedTestClass waitForMerging(@NotNull TestGenerationStateMachine stateMachine,
                                         @NotNull CompletableFuture<MergedTestClass> future,
                                         @NotNull String mergerType) throws Exception {
        
        int progressPercent = 30;
        // Set timeout for AI merging
        int maxWaitSeconds = 120; // AI merging may take some time
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
    
    @Override
    public boolean isRetryable() {
        return true;
    }
    
    @Override
    public boolean isSkippable() {
        return false; // Merging cannot be skipped - it's required for final output
    }
}