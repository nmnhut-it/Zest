package com.zps.zest.testgen.statemachine.handlers;

import com.zps.zest.completion.metrics.ZestInlineCompletionMetricsService;
import com.zps.zest.testgen.agents.AITestMergerAgent;
import com.zps.zest.testgen.evaluation.TestCodeValidator;
import com.zps.zest.testgen.model.*;
import com.zps.zest.testgen.statemachine.AbstractStateHandler;
import com.zps.zest.testgen.statemachine.TestGenerationState;
import com.zps.zest.testgen.statemachine.TestGenerationStateMachine;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.LocalDateTime;
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
                streamingCallback.accept("🤖 Starting phased test merging workflow...\n");
            }

            // Create AI merger agent
            logToolActivity(stateMachine, "AITestMerger", "Initializing merger agent");

            ZestLangChain4jService langChainService = getProject(stateMachine).getService(ZestLangChain4jService.class);
            NaiveLLMService naiveLlmService = getProject(stateMachine).getService(NaiveLLMService.class);

            // Default to FULL_REWRITE_ONLY for simpler, faster fixing
            // TODO: Make this configurable via settings for A/B testing
            TestFixStrategy strategy = TestFixStrategy.FULL_REWRITE_ONLY;

            AITestMergerAgent aiMerger = new AITestMergerAgent(
                    getProject(stateMachine),
                    langChainService,
                    naiveLlmService,
                    strategy
            );

            // Store as field for direct access
            this.aiTestMergerAgent = aiMerger;

            // Set UI event listener on merger agent for live updates
            if (uiEventListener != null) {
                aiMerger.setUiEventListener(uiEventListener);
                uiEventListener.onMergerAgentCreated(aiMerger);
            }

            // Execute phased workflow with explicit checkpoints
            MergedTestClass mergedTestClass = executePhasedMerging(stateMachine, aiMerger, result, contextTools);
            
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

            // Track unit test metrics
            trackUnitTestMetrics(stateMachine, result, mergedTestClass);

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
     * Execute phased merging workflow with explicit checkpoints.
     * New workflow: Java handles deterministic tasks, AI uses tools for code modification.
     */
    private MergedTestClass executePhasedMerging(
            @NotNull TestGenerationStateMachine stateMachine,
            @NotNull AITestMergerAgent aiMerger,
            @NotNull TestGenerationResult result,
            @Nullable com.zps.zest.testgen.agents.ContextAgent.ContextGatheringTools contextTools) throws Exception {

        String targetClass = result.getTargetClass();
        String testClassName = result.getClassName();
        String newTestCode = result.getCompleteTestClass();

        // ============ PHASE 1: Find Existing Test (Java - No AI) ============
        logToolActivity(stateMachine, "Phase1", "Finding existing test");
        if (streamingCallback != null) {
            streamingCallback.accept("\n**PHASE 1: Finding Existing Test (Java)**\n");
        }

        AITestMergerAgent.ExistingTestInfo existingTest = aiMerger.findExistingTest(targetClass);

        if (shouldCancel(stateMachine)) {
            throw new InterruptedException("Merging cancelled during Phase 1");
        }

        // ============ PHASE 2: Merge (AI Only If Needed) ============
        logToolActivity(stateMachine, "Phase2", "Merging test code");
        if (streamingCallback != null) {
            streamingCallback.accept("\n**PHASE 2: Merge (AI only if existing test found)**\n");
        }

        String mergedCode = aiMerger.mergeUsingTools(existingTest, newTestCode, testClassName, targetClass, contextTools);

        if (shouldCancel(stateMachine)) {
            throw new InterruptedException("Merging cancelled during Phase 2");
        }

        // ============ PHASE 3: Fix Imports (Java - Deterministic) ============
        logToolActivity(stateMachine, "Phase3", "Fixing imports");
        if (streamingCallback != null) {
            streamingCallback.accept("\n**PHASE 3: Import Enhancement (Java)**\n");
        }

        mergedCode = aiMerger.autoEnhanceImports(mergedCode, targetClass, result.getFramework(), contextTools);

        // Update working code in tools
        aiMerger.getMergingTools().setNewTestCode(testClassName, mergedCode);

        if (streamingCallback != null) {
            streamingCallback.accept("✅ Imports enhanced\n");
        }

        if (shouldCancel(stateMachine)) {
            throw new InterruptedException("Merging cancelled during Phase 3");
        }

        // ============ PHASE 4: Validate (Java) ============
        logToolActivity(stateMachine, "Phase4", "Validating test code");
        if (streamingCallback != null) {
            streamingCallback.accept("\n**PHASE 4: Validation (Java)**\n");
        }

        TestCodeValidator.ValidationResult validation = TestCodeValidator.validate(
                getProject(stateMachine),
                mergedCode,
                testClassName
        );

        if (streamingCallback != null) {
            streamingCallback.accept(validation.compiles() ?
                    "✅ Validation passed - no errors\n" :
                    "❌ Validation failed: " + validation.getErrorCount() + " errors\n");
        }

        // ============ PHASE 5: Fix Errors (AI with Chain-of-Thought) ============
        if (!validation.compiles() && validation.getErrors() != null && !validation.getErrors().isEmpty()) {
            if (shouldCancel(stateMachine)) {
                throw new InterruptedException("Merging cancelled during validation");
            }

            logToolActivity(stateMachine, "Phase5", "Fixing validation errors");
            if (streamingCallback != null) {
                streamingCallback.accept("\n**PHASE 5: Fix Errors (AI chain-of-thought + exploration)**\n");
            }

            int maxFixAttempts = 3;
            mergedCode = aiMerger.fixUsingTools(testClassName, validation.getErrors(), contextTools, maxFixAttempts);
        } else {
            if (streamingCallback != null) {
                streamingCallback.accept("\n⏭️ Skipping Phase 5 (no errors to fix)\n");
            }
        }

        if (shouldCancel(stateMachine)) {
            throw new InterruptedException("Merging cancelled during Phase 5");
        }

        // ============ PHASE 6: Final Validation (Java) ============
        logToolActivity(stateMachine, "Phase6", "Final validation");
        if (streamingCallback != null) {
            streamingCallback.accept("\n**PHASE 6: Final Validation (Java)**\n");
        }

        validation = TestCodeValidator.validate(getProject(stateMachine), mergedCode, testClassName);

        if (streamingCallback != null) {
            streamingCallback.accept(validation.compiles() ?
                    "✅ Final validation passed\n" :
                    "⚠️ Still has " + validation.getErrorCount() + " errors\n");
        }

        // ============ PHASE 7: Logic Bug Review (AI with Self-Questioning) ============
        if (validation.compiles()) {
            if (shouldCancel(stateMachine)) {
                throw new InterruptedException("Merging cancelled before logic review");
            }

            logToolActivity(stateMachine, "Phase7", "Logic bug review");
            if (streamingCallback != null) {
                streamingCallback.accept("\n**PHASE 7: Logic Review (AI self-questioning + exploration)**\n");
            }

            mergedCode = aiMerger.reviewLogicUsingTools(testClassName, contextTools);
        } else {
            if (streamingCallback != null) {
                streamingCallback.accept("\n⏭️ Skipping Phase 7 (validation failed)\n");
            }
        }

        // ============ Create Final Result ============
        logToolActivity(stateMachine, "Finalizing", "Creating merged test class");
        if (streamingCallback != null) {
            streamingCallback.accept("\n**FINALIZING**\n");
        }

        String outputPath = existingTest != null ?
                existingTest.getFilePath() :
                determineOutputPath(testClassName, result.getPackageName());

        int methodCount = countTestMethods(mergedCode);

        MergedTestClass finalResult = new MergedTestClass(
                testClassName,
                result.getPackageName(),
                mergedCode,
                testClassName + ".java",
                outputPath,
                methodCount,
                result.getFramework()
        );

        if (streamingCallback != null) {
            streamingCallback.accept("✅ Merging workflow complete\n");
            streamingCallback.accept(String.format("   Class: %s\n", testClassName));
            streamingCallback.accept(String.format("   Methods: %d\n", methodCount));
            streamingCallback.accept(String.format("   Validates: %s\n", validation.compiles() ? "YES" : "NO"));
        }

        return finalResult;
    }

    private int countTestMethods(String testCode) {
        int count = 0;
        String[] lines = testCode.split("\n");
        for (String line : lines) {
            if (line.trim().startsWith("@Test")) {
                count++;
            }
        }
        return count;
    }

    private String determineOutputPath(String className, String packageName) {
        // Find test source root
        String basePath = getProject(null).getBasePath();
        if (basePath == null) {
            return className + ".java";
        }

        java.io.File testDir = new java.io.File(basePath, "src/test/java");
        if (!testDir.exists()) {
            testDir = new java.io.File(basePath, "test");
        }

        String packagePath = packageName.replace('.', java.io.File.separatorChar);
        java.io.File packageDir = packagePath.isEmpty() ? testDir : new java.io.File(testDir, packagePath);
        java.io.File testFile = new java.io.File(packageDir, className + ".java");

        return testFile.getAbsolutePath();
    }

    
    /**
     * Get the AI test merger agent (direct access instead of session data)
     */
    @Override
    public void cancel() {
        super.cancel();
        if (aiTestMergerAgent != null) {
            aiTestMergerAgent.cancel();
            LOG.info("Cancelled AITestMergerAgent");
        }
    }

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

    /**
     * Track unit test metrics when generation completes
     */
    private void trackUnitTestMetrics(
            @NotNull TestGenerationStateMachine stateMachine,
            @Nullable TestGenerationResult result,
            @NotNull MergedTestClass mergedTestClass
    ) {
        try {
            if (result == null) return;

            ZestInlineCompletionMetricsService metricsService =
                    getProject(stateMachine).getService(ZestInlineCompletionMetricsService.class);

            // Use timestamp for metrics (session details not directly accessible)
            long generationTimeMs = 60000L; // Default estimate: 1 minute

            // Count test methods
            int totalTests = result.getMethodCount();

            // Estimate word count from test code
            String testCode = mergedTestClass.getFullContent();
            int wordCount = testCode.split("\\s+").length;

            // ✅ REAL COMPILATION CHECK using IntelliJ's CodeSmellDetector
            TestCodeValidator.ValidationResult validation = TestCodeValidator.validate(
                    getProject(stateMachine),
                    testCode,
                    mergedTestClass.getClassName()
            );

            int testsCompiled = validation.compiles() ? totalTests : 0;
            int testsPassedImmediately = 0;  // Don't run tests, just check compilation

            // Log compilation result
            if (validation.compiles()) {
                LOG.info("Test compilation: SUCCESS - " + testsCompiled + "/" + totalTests + " tests compiled");
            } else {
                LOG.info("Test compilation: FAILED - " + validation.getErrorCount() + " errors found, 0/" + totalTests + " compiled");
            }

            metricsService.trackUnitTest(
                    "test-" + System.currentTimeMillis(),
                    totalTests,
                    wordCount,
                    generationTimeMs,
                    testsCompiled,           // ✅ Real compilation result
                    testsPassedImmediately   // 0 - not running tests
            );

            LOG.info("Unit test metrics tracked: " + totalTests + " tests, " +
                    wordCount + " words, compilation: " + (validation.compiles() ? "SUCCESS" : "FAILED"));

        } catch (Exception e) {
            LOG.warn("Failed to track unit test metrics", e);
        }
    }
}