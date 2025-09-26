package com.zps.zest.testgen.statemachine.handlers;

import com.zps.zest.testgen.agents.AITestMergerAgent;
import com.zps.zest.testgen.model.*;
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
 * Handles the test merging phase of test generation.
 * Uses AI-based merging to create complete, merged test classes.
 */
public class TestMergingHandler extends AbstractStateHandler {

    private final Consumer<String> streamingCallback;
    private final com.zps.zest.testgen.ui.StreamingEventListener uiEventListener;
    private final TestGenerationStateMachine stateMachine;
    private AITestMergerAgent aiTestMergerAgent;
    private MergedTestClass mergedTestClass;
    
    public TestMergingHandler(Consumer<String> streamingCallback,
                             com.zps.zest.testgen.ui.StreamingEventListener uiEventListener, TestGenerationStateMachine stateMachine) {
        super(TestGenerationState.MERGING_TESTS);
        this.streamingCallback = streamingCallback;
        this.uiEventListener = uiEventListener;
        this.stateMachine = stateMachine;
    }
    
    @NotNull
    @Override
    protected StateResult executeState(@NotNull TestGenerationStateMachine stateMachine) {
        try {
            // Validate required data - no session data checks needed
            
            // Get data from other handlers
            com.zps.zest.testgen.statemachine.handlers.TestGenerationHandler generationHandler =
                stateMachine.getHandler(TestGenerationState.GENERATING_TESTS, com.zps.zest.testgen.statemachine.handlers.TestGenerationHandler.class);
            com.zps.zest.testgen.statemachine.handlers.ContextGatheringHandler contextHandler =
                stateMachine.getHandler(TestGenerationState.GATHERING_CONTEXT, com.zps.zest.testgen.statemachine.handlers.ContextGatheringHandler.class);

            TestGenerationResult result = generationHandler != null ? generationHandler.getTestGenerationResult() : null;
            com.zps.zest.testgen.agents.ContextAgent.ContextGatheringTools contextTools =
                contextHandler != null && contextHandler.getContextAgent() != null ? contextHandler.getContextAgent().getContextTools() : null;
            
            logToolActivity(stateMachine, "TestMerger", "Preparing test merging");

            // Notify UI of phase start
            if (uiEventListener != null) {
                uiEventListener.onPhaseStarted(getHandledState());
                uiEventListener.onMergingStarted(); // New: notify merging started
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

            // Store as field for direct access
            this.aiTestMergerAgent = aiMerger;

            // Set UI event listener on merger agent for live updates
            if (uiEventListener != null) {
                aiMerger.setUiEventListener(uiEventListener);
                uiEventListener.onMergerAgentCreated(aiMerger);
            }

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
            
            // Store merged result in handler field
            this.mergedTestClass = mergedTestClass;
            
            // Session management removed - data available via handler getter
            
            String summary = String.format("AI test merging and review completed: %s with %d methods",
                mergedTestClass.getClassName(),
                mergedTestClass.getMethodCount());
            LOG.info(summary);

            // Notify UI that merging completed successfully
            if (uiEventListener != null) {
                uiEventListener.onMergingCompleted(true);
            }

            // Transition directly to COMPLETED since merging now includes error review and fixing
            return StateResult.success(mergedTestClass, summary, TestGenerationState.COMPLETED);

        } catch (Exception e) {
            LOG.error("AI test merging failed", e);

            // Notify UI that merging failed
            if (uiEventListener != null) {
                uiEventListener.onMergingCompleted(false);
            }

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
        int maxWaitSeconds = 560; // AI merging may take some time
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
     * Get the AI test merger agent (direct access instead of session data)
     */
    @Nullable
    public AITestMergerAgent getAITestMergerAgent() {
        return aiTestMergerAgent;
    }

    /**
     * Get the merged test class
     */
    @Nullable
    public MergedTestClass getMergedTestClass() {
        return mergedTestClass;
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